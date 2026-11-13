package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.FreeServersRepository
import com.example.model.ProxyNode
import com.example.model.RoutingMode
import com.example.model.VpnConnectionState
import com.example.model.VpnTelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Системный сервис VPN поверх android.net.VpnService и реального ядра sing-box (libbox).
 *
 * - Поднимает Foreground Service с `specialUse` для Android 14+.
 * - Управляет жизненным циклом `CommandServer` + `PlatformInterface` (`openTun`, `protect`, `detachFd`).
 * - Получает реальную живую скорость (uplink / downlink), соединения и логи из `CommandClient`.
 * - Выполняет Per-App Whitelist исключительно через `addAllowedApplication()`.
 */
class MyVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        const val ACTION_SWITCH_NODE = "com.example.vpn.ACTION_SWITCH_NODE"
        const val ACTION_RELOAD_CONFIG = "com.example.vpn.ACTION_RELOAD_CONFIG"
        const val ACTION_URLTEST = "com.example.vpn.ACTION_URLTEST"

        private const val NOTIFICATION_CHANNEL_ID = "aegisbox_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_LOG_LINES = 250

        private val _telemetryState = MutableStateFlow(VpnTelemetryState())
        val telemetryState: StateFlow<VpnTelemetryState> = _telemetryState.asStateFlow()

        private val _coreLogs = MutableStateFlow<List<String>>(emptyList())
        val coreLogs: StateFlow<List<String>> = _coreLogs.asStateFlow()

        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun appendLog(line: String) {
            val formatted = "${timeFormat.format(Date())} $line"
            _coreLogs.update { prev ->
                (prev + formatted).takeLast(MAX_LOG_LINES)
            }
        }

        fun clearLogs() {
            _coreLogs.value = emptyList()
        }

        fun updatePreviewConfig(json: String, mode: RoutingMode, activeNode: ProxyNode?) {
            _telemetryState.update { state ->
                state.copy(
                    routingMode = mode,
                    activeNode = activeNode ?: state.activeNode,
                    activeSingBoxJson = json
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: FreeServersRepository
    private lateinit var libboxBridge: LibboxBridge

    @Volatile
    private var cachedAllowedPackages: Set<String> = emptySet()

    @Volatile
    private var lastNotificationUpdateMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        repository = FreeServersRepository(applicationContext)
        createNotificationChannel()

        libboxBridge = LibboxBridge(
            vpnService = this,
            getAllowedWhitelistPackages = { cachedAllowedPackages },
            onStatusUpdate = { status ->
                _telemetryState.update { prev ->
                    prev.copy(
                        downloadSpeedBps = status.downlink.coerceAtLeast(0L),
                        uploadSpeedBps = status.uplink.coerceAtLeast(0L),
                        totalDownloadedBytes = status.downlinkTotal.coerceAtLeast(0L),
                        totalUploadedBytes = status.uplinkTotal.coerceAtLeast(0L),
                        activeConnectionsCount = status.connectionsOut.coerceAtLeast(0),
                        coreMemoryBytes = status.memory.coerceAtLeast(0L)
                    )
                }
                val now = System.currentTimeMillis()
                if (now - lastNotificationUpdateMs >= 1500L &&
                    _telemetryState.value.connectionState == VpnConnectionState.CONNECTED
                ) {
                    lastNotificationUpdateMs = now
                    refreshNotification()
                }
            },
            onGroupsUpdate = { activeTag, delays ->
                serviceScope.launch {
                    if (delays.isNotEmpty()) {
                        repository.updateNodeDelaysFromCore(delays)
                    }
                }
                _telemetryState.update { prev ->
                    val mergedDelays = prev.nodeDelaysMs + delays
                    val updatedNode = prev.activeNode?.let { node ->
                        val measured = mergedDelays[node.tag]
                        if (measured != null && measured > 0) {
                            node.copy(latencyMs = measured, isAlive = true)
                        } else {
                            node
                        }
                    }
                    prev.copy(
                        activeCoreOutboundTag = activeTag.ifBlank { prev.activeCoreOutboundTag },
                        activeNode = updatedNode,
                        nodeDelaysMs = mergedDelays
                    )
                }
            },
            onLogLine = { line ->
                appendLog(line)
            },
            onServiceStopRequested = {
                serviceScope.launch { stopVpnTunnel() }
            },
            onServiceError = { msg ->
                appendLog("[core-error] $msg")
                _telemetryState.update {
                    it.copy(
                        connectionState = VpnConnectionState.ERROR,
                        lastError = msg
                    )
                }
                requestTileUpdate()
            }
        )

        _telemetryState.update { it.copy(coreVersion = libboxBridge.getCoreVersion()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpnTunnel()
                return START_NOT_STICKY
            }
            ACTION_SWITCH_NODE -> {
                serviceScope.launch { switchNextNodeInternal() }
                return START_STICKY
            }
            ACTION_URLTEST -> {
                serviceScope.launch {
                    libboxBridge.triggerUrlTest("auto-best-free")
                }
                return START_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                if (_telemetryState.value.connectionState == VpnConnectionState.CONNECTED) {
                    serviceScope.launch { startOrReloadVpnTunnel(isReload = true) }
                }
                return START_STICKY
            }
            else -> {
                startForegroundWithNotification(
                    title = "Запуск туннеля sing-box…",
                    subtitle = "Инициализация libbox и TUN 172.19.0.1/30"
                )
                serviceScope.launch { startOrReloadVpnTunnel(isReload = false) }
                return START_STICKY
            }
        }
    }

    private suspend fun startOrReloadVpnTunnel(isReload: Boolean) {
        _telemetryState.update {
            it.copy(
                connectionState = if (isReload) VpnConnectionState.CONNECTED else VpnConnectionState.CONNECTING,
                lastError = null
            )
        }
        requestTileUpdate()

        val nodes = repository.nodesFlow.first()
        if (nodes.isEmpty()) {
            val msg = "Нет серверов для подключения. Нажмите «Обновить и протестировать серверы» или импортируйте ключ."
            appendLog("[error] $msg")
            failAndStop(msg)
            return
        }

        val warpConfig = repository.warpConfigFlow.first()
        val routingMode = repository.routingModeFlow.first()
        val selectedTag = repository.selectedNodeTagFlow.first()

        if (routingMode == RoutingMode.VLESS_WARP_CHAIN && !warpConfig.isRegistered) {
            appendLog("[info] Профиль Cloudflare WARP ещё не зарегистрирован — пробуем авто-регистрацию…")
            repository.registerCloudflareWarp().onFailure { err ->
                appendLog("[warn] Авто-регистрация WARP не удалась (${err.message}); цепочка переключена на прямой прокси-пул")
            }
        }

        val refreshedWarp = repository.warpConfigFlow.first()
        val activeNode = nodes.find { it.tag == selectedTag } ?: nodes.firstOrNull { it.isAlive } ?: nodes.first()

        val configJson = try {
            SingBoxConfigBuilder.buildConfig(
                nodes = nodes,
                warpConfig = if (refreshedWarp.isRegistered) refreshedWarp else null,
                routingMode = routingMode,
                selectedNodeTag = activeNode.tag
            )
        } catch (e: Throwable) {
            failAndStop("Ошибка генерации конфига: ${e.message}")
            return
        }

        cachedAllowedPackages = repository.getEffectiveWhitelistPackages()
        appendLog("[whitelist] Разрешено приложений: ${cachedAllowedPackages.size}")

        val startResult = if (isReload) {
            libboxBridge.reloadCore(configJson)
        } else {
            libboxBridge.startCore(configJson)
        }

        startResult.fold(
            onSuccess = {
                val now = System.currentTimeMillis()
                _telemetryState.update { prev ->
                    prev.copy(
                        connectionState = VpnConnectionState.CONNECTED,
                        routingMode = routingMode,
                        activeNode = activeNode,
                        connectedSinceEpochMs = if (isReload && prev.connectedSinceEpochMs > 0L) {
                            prev.connectedSinceEpochMs
                        } else {
                            now
                        },
                        activeSingBoxJson = configJson,
                        lastError = null
                    )
                }
                refreshNotification()
                requestTileUpdate()
            },
            onFailure = { err ->
                val reason = err.localizedMessage ?: "Неизвестная ошибка ядра sing-box"
                failAndStop(reason)
            }
        )
    }

    private fun failAndStop(errorMessage: String) {
        libboxBridge.stopCore()
        _telemetryState.update {
            it.copy(
                connectionState = VpnConnectionState.ERROR,
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L,
                activeConnectionsCount = 0,
                lastError = errorMessage
            )
        }
        requestTileUpdate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun switchNextNodeInternal() {
        val nodes = repository.nodesFlow.first()
        if (nodes.isEmpty()) return

        val currentTag = _telemetryState.value.activeNode?.tag ?: repository.selectedNodeTagFlow.first()
        val currentIndex = nodes.indexOfFirst { it.tag == currentTag }
        val nextNode = nodes[(currentIndex + 1).mod(nodes.size)]

        repository.setSelectedNodeTag(nextNode.tag)

        if (_telemetryState.value.connectionState == VpnConnectionState.CONNECTED) {
            libboxBridge.selectOutbound("auto-best-free", nextNode.tag)
            libboxBridge.selectOutbound("vless-reality-entry", nextNode.tag)
            _telemetryState.update { it.copy(activeNode = nextNode) }
            refreshNotification()
        } else {
            val warp = repository.warpConfigFlow.first()
            val mode = repository.routingModeFlow.first()
            val json = runCatching {
                SingBoxConfigBuilder.buildConfig(
                    nodes = nodes,
                    warpConfig = if (warp.isRegistered) warp else null,
                    routingMode = mode,
                    selectedNodeTag = nextNode.tag
                )
            }.getOrDefault("")
            updatePreviewConfig(json, mode, nextNode)
        }
    }

    private fun stopVpnTunnel() {
        libboxBridge.stopCore()
        _telemetryState.update {
            it.copy(
                connectionState = VpnConnectionState.DISCONNECTED,
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L,
                activeConnectionsCount = 0,
                connectedSinceEpochMs = 0L
            )
        }
        requestTileUpdate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshNotification() {
        val current = _telemetryState.value
        val nodeTitle = current.activeNode?.let {
            val pingPart = if (it.latencyMs > 0) " (${it.latencyMs} мс)" else ""
            "${it.flagEmoji} ${it.name}$pingPart"
        } ?: "Активный туннель sing-box"

        val modeLabel = if (current.routingMode == RoutingMode.VLESS_WARP_CHAIN) {
            "VLESS → WARP"
        } else {
            "Auto urltest"
        }
        val speedText = "↓ ${formatSpeed(current.downloadSpeedBps)} • ↑ ${formatSpeed(current.uploadSpeedBps)} | $modeLabel"
        updateNotification(title = nodeTitle, subtitle = speedText)
    }

    override fun onRevoke() {
        stopVpnTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянный статус туннеля sing-box, скорость трафика и быстрая смена ноды"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(title: String, subtitle: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val switchNodeIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, MyVpnService::class.java).apply { action = ACTION_SWITCH_NODE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, MyVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_vpn_shield)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent)
            .addAction(
                R.drawable.ic_qs_vpn_shield,
                getString(R.string.action_switch_node),
                switchNodeIntent
            )
            .addAction(
                R.drawable.ic_qs_vpn_shield,
                getString(R.string.action_disconnect),
                disconnectIntent
            )
            .build()
    }

    private fun startForegroundWithNotification(title: String, subtitle: String) {
        val notification = buildForegroundNotification(title, subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, subtitle: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(title, subtitle))
    }

    private fun requestTileUpdate() {
        try {
            TileService.requestListeningState(
                applicationContext,
                ComponentName(applicationContext, VpnTileService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_048_576L -> String.format("%.1f МБ/с", bytesPerSec / 1_048_576.0)
            bytesPerSec >= 1_024L -> String.format("%d КБ/с", bytesPerSec / 1_024L)
            else -> "$bytesPerSec Б/с"
        }
    }
}
