package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FreeServersRepository
import com.example.data.ServerSyncWorker
import com.example.model.AppRoutingItem
import com.example.model.ProxyNode
import com.example.model.RoutingMode
import com.example.model.SubscriptionSource
import com.example.model.VpnConnectionState
import com.example.model.VpnTelemetryState
import com.example.model.WarpConfig
import com.example.vpn.MyVpnService
import com.example.vpn.SingBoxConfigBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FreeServersRepository(application.applicationContext)

    val telemetryState: StateFlow<VpnTelemetryState> = MyVpnService.telemetryState

    val nodes: StateFlow<List<ProxyNode>> = repository.nodesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SingBoxConfigBuilder.defaultFallbackNodes()
    )

    val routingMode: StateFlow<RoutingMode> = repository.routingModeFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RoutingMode.VLESS_WARP_CHAIN
    )

    val selectedNodeTag: StateFlow<String?> = repository.selectedNodeTagFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val subscriptionSources: StateFlow<List<SubscriptionSource>> = repository.subscriptionSourcesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FreeServersRepository.DEFAULT_SUBSCRIPTION_SOURCES
    )

    val warpConfig: StateFlow<WarpConfig> = repository.warpConfigFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WarpConfig(
            deviceId = "cf-warp-eb82a910",
            accountToken = "token-init",
            privateKey = "eG9yLXdhcnAtY2xvdWRmbGFyZS1hZWdpc2JveC0yMDI2"
        )
    )

    val whitelistPackages: StateFlow<Set<String>> = repository.whitelistPackagesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FreeServersRepository.DEFAULT_WHITELIST_PACKAGES
    )

    private val _appRoutingItems = MutableStateFlow<List<AppRoutingItem>>(emptyList())
    val appRoutingItems: StateFlow<List<AppRoutingItem>> = _appRoutingItems.asStateFlow()

    private val _isSyncingServers = MutableStateFlow(false)
    val isSyncingServers: StateFlow<Boolean> = _isSyncingServers.asStateFlow()

    private val _isGeneratingWarp = MutableStateFlow(false)
    val isGeneratingWarp: StateFlow<Boolean> = _isGeneratingWarp.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    init {
        ServerSyncWorker.schedulePeriodicSync(application.applicationContext)
        refreshAppRoutingList()
        observeAndBuildLiveConfigPreview()
    }

    private fun observeAndBuildLiveConfigPreview() {
        viewModelScope.launch {
            combine(
                nodes,
                warpConfig,
                routingMode,
                selectedNodeTag
            ) { nodeList, warp, mode, tag ->
                val chosenNode = nodeList.find { it.tag == tag } ?: nodeList.firstOrNull()
                val json = SingBoxConfigBuilder.buildConfig(
                    nodes = nodeList,
                    warpConfig = warp,
                    routingMode = mode,
                    selectedNodeTag = chosenNode?.tag
                )
                Triple(json, mode, chosenNode)
            }.collect { (json, mode, chosenNode) ->
                MyVpnService.updatePreviewConfig(json, mode, chosenNode)
            }
        }
    }

    fun refreshAppRoutingList() {
        viewModelScope.launch {
            _appRoutingItems.value = repository.loadAppsForSplitTunneling()
        }
    }

    fun startVpnService() {
        val ctx = getApplication<Application>().applicationContext
        val intent = Intent(ctx, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun stopVpnService() {
        val ctx = getApplication<Application>().applicationContext
        val intent = Intent(ctx, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        ctx.startService(intent)
    }

    fun switchToNextNode() {
        val ctx = getApplication<Application>().applicationContext
        val intent = Intent(ctx, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_SWITCH_NODE
        }
        ctx.startService(intent)
    }

    fun selectRoutingMode(mode: RoutingMode) {
        viewModelScope.launch {
            repository.setRoutingMode(mode)
            reloadVpnIfConnected()
        }
    }

    fun selectNode(node: ProxyNode) {
        viewModelScope.launch {
            repository.setSelectedNodeTag(node.tag)
            _statusBannerMessage.value = "Активная нода: ${node.flagEmoji} ${node.name} (${node.latencyMs} мс)"
            reloadVpnIfConnected()
        }
    }

    fun forceSyncAndTestFreeServers() {
        if (_isSyncingServers.value) return
        viewModelScope.launch {
            _isSyncingServers.value = true
            _statusBannerMessage.value = "Сбор и проверка бесплатных серверов (GitHub + Зеркала)…"
            val result = repository.syncAndTestAllSources()
            _isSyncingServers.value = false
            result.fold(
                onSuccess = { list ->
                    val best = list.firstOrNull()
                    _statusBannerMessage.value = buildString {
                        append("Обновлено ${list.size} нод (US/DE/NL/FI). ")
                        if (best != null) {
                            append("Лучшая: ${best.flagEmoji} ${best.name} (${best.latencyMs} мс)")
                        }
                    }
                    reloadVpnIfConnected()
                },
                onFailure = { err ->
                    _statusBannerMessage.value = "Ошибка обновления: ${err.localizedMessage}"
                }
            )
        }
    }

    fun regenerateWarpCredentials() {
        if (_isGeneratingWarp.value) return
        viewModelScope.launch {
            _isGeneratingWarp.value = true
            _statusBannerMessage.value = "Регистрация нового ключа Cloudflare WARP + AmneziaWG…"
            val newWarp = repository.regenerateCloudflareWarpConfig()
            _isGeneratingWarp.value = false
            _statusBannerMessage.value = "WARP обновлён: ${newWarp.localAddressIpv4} • Reserved ${newWarp.reservedBytes} • AWG Jc=${newWarp.awgJc}"
            reloadVpnIfConnected()
        }
    }

    fun toggleAppWhitelist(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleWhitelistedPackage(packageName, enabled)
            _appRoutingItems.value = repository.loadAppsForSplitTunneling()
            reloadVpnIfConnected()
        }
    }

    fun resetWhitelistToDefaults() {
        viewModelScope.launch {
            repository.resetDefaultWhitelist()
            _appRoutingItems.value = repository.loadAppsForSplitTunneling()
            _statusBannerMessage.value = "Восстановлен стандартный белый список (Gemini, YouTube, Telegram, Браузеры)"
            reloadVpnIfConnected()
        }
    }

    fun addSubscriptionSource(title: String, url: String) {
        viewModelScope.launch {
            repository.addSubscriptionSource(title, url)
            _statusBannerMessage.value = "Источник подписки добавлен"
        }
    }

    fun removeSubscriptionSource(id: String) {
        viewModelScope.launch {
            repository.removeSubscriptionSource(id)
            _statusBannerMessage.value = "Источник удалён"
        }
    }

    fun importClipboardKey(rawText: String) {
        viewModelScope.launch {
            repository.importRawKeyOrBlock(rawText).fold(
                onSuccess = { count ->
                    _statusBannerMessage.value = "Импортировано и проверено нод: $count"
                    reloadVpnIfConnected()
                },
                onFailure = { err ->
                    _statusBannerMessage.value = err.localizedMessage ?: "Некорректный формат ключа"
                }
            )
        }
    }

    fun dismissBanner() {
        _statusBannerMessage.value = null
    }

    /**
     * Открывает системные настройки приложения Google Gemini или Приложения Google
     * для быстрой очистки кэша при смене региона.
     */
    fun openGoogleOrGeminiAppSettings(context: Context, targetPackage: String = "com.google.android.apps.bard") {
        try {
            val pm = context.packageManager
            val resolvedPkg = try {
                pm.getPackageInfo(targetPackage, 0)
                targetPackage
            } catch (_: Exception) {
                "com.google.android.googlequicksearchbox"
            }

            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", resolvedPkg, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun reloadVpnIfConnected() {
        if (telemetryState.value.connectionState == VpnConnectionState.CONNECTED) {
            val ctx = getApplication<Application>().applicationContext
            val intent = Intent(ctx, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_RELOAD_CONFIG
            }
            ctx.startService(intent)
        } else {
            val nodeList = repository.nodesFlow.first()
            val warp = repository.warpConfigFlow.first()
            val mode = repository.routingModeFlow.first()
            val tag = repository.selectedNodeTagFlow.first()
            val active = nodeList.find { it.tag == tag } ?: nodeList.firstOrNull()
            val json = SingBoxConfigBuilder.buildConfig(nodeList, warp, mode, active?.tag)
            MyVpnService.updatePreviewConfig(json, mode, active)
        }
    }
}
