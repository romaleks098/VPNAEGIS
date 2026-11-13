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
    val coreLogs: StateFlow<List<String>> = MyVpnService.coreLogs

    val nodes: StateFlow<List<ProxyNode>> = repository.nodesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
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

    val includeGmsDependencies: StateFlow<Boolean> = repository.includeGmsDependenciesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val subscriptionSources: StateFlow<List<SubscriptionSource>> = repository.subscriptionSourcesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FreeServersRepository.DEFAULT_SUBSCRIPTION_SOURCES
    )

    val warpConfig: StateFlow<WarpConfig> = repository.warpConfigFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WarpConfig()
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
                val chosenNode = nodeList.find { it.tag == tag } ?: nodeList.firstOrNull { it.isAlive } ?: nodeList.firstOrNull()
                val json = if (nodeList.isNotEmpty()) {
                    runCatching {
                        SingBoxConfigBuilder.buildConfig(
                            nodes = nodeList,
                            warpConfig = if (warp.isRegistered) warp else null,
                            routingMode = mode,
                            selectedNodeTag = chosenNode?.tag
                        )
                    }.getOrDefault("{\n  \"error\": \"Невалидные параметры серверов\"\n}")
                } else {
                    "{\n  \"status\": \"Ожидание серверов. Нажмите «Обновить и протестировать серверы» или импортируйте ключ.\"\n}"
                }
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
            val pingLabel = if (node.latencyMs > 0) "${node.latencyMs} мс" else "Недоступен"
            _statusBannerMessage.value = "Выбрана нода: ${node.flagEmoji} ${node.name} ($pingLabel)"
            reloadVpnIfConnected()
        }
    }

    fun forceSyncAndTestFreeServers() {
        if (_isSyncingServers.value) return
        viewModelScope.launch {
            _isSyncingServers.value = true
            _statusBannerMessage.value = "Загрузка подписок и реальный замер задержки нод…"
            val result = repository.syncAndTestAllSources()
            _isSyncingServers.value = false
            result.fold(
                onSuccess = { list ->
                    val aliveCount = list.count { it.isAlive }
                    val best = list.firstOrNull { it.isAlive }
                    _statusBannerMessage.value = buildString {
                        append("Загружено ${list.size} нод (живых: $aliveCount). ")
                        if (best != null) {
                            append("Лучшая: ${best.flagEmoji} ${best.name} (${best.latencyMs} мс)")
                        } else {
                            append("Доступных нод не найдено — проверьте интернет.")
                        }
                    }
                    if (telemetryState.value.connectionState == VpnConnectionState.CONNECTED) {
                        val ctx = getApplication<Application>().applicationContext
                        ctx.startService(Intent(ctx, MyVpnService::class.java).apply {
                            action = MyVpnService.ACTION_URLTEST
                        })
                    }
                },
                onFailure = { err ->
                    _statusBannerMessage.value = err.localizedMessage ?: "Ошибка загрузки подписок"
                }
            )
        }
    }

    fun regenerateWarpCredentials() {
        if (_isGeneratingWarp.value) return
        viewModelScope.launch {
            _isGeneratingWarp.value = true
            _statusBannerMessage.value = "Регистрация устройства в Cloudflare WARP API (X25519)…"
            repository.registerCloudflareWarp().fold(
                onSuccess = { newWarp ->
                    _isGeneratingWarp.value = false
                    _statusBannerMessage.value =
                        "WARP зарегистрирован: ${newWarp.localAddressIpv4} • Reserved ${newWarp.reservedBytes}"
                    reloadVpnIfConnected()
                },
                onFailure = { err ->
                    _isGeneratingWarp.value = false
                    _statusBannerMessage.value =
                        "Ошибка регистрации Cloudflare WARP: ${err.localizedMessage ?: "Нет ответа от API"}"
                }
            )
        }
    }

    fun toggleIncludeGmsDependencies(enabled: Boolean) {
        viewModelScope.launch {
            repository.setIncludeGmsDependencies(enabled)
            _statusBannerMessage.value = if (enabled) {
                "GMS / WebView добавлены в белый список (стабильная авторизация Google/Gemini)"
            } else {
                "GMS / WebView исключены из белого списка"
            }
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
            _statusBannerMessage.value = "Восстановлен стандартный белый список + GMS/WebView"
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

    fun clearCoreLogs() {
        MyVpnService.clearLogs()
    }

    fun dismissBanner() {
        _statusBannerMessage.value = null
    }

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
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
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
        }
    }
}
