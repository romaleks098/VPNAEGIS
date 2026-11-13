package com.example.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import com.example.BuildConfig
import com.example.MainActivity
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.ServiceStatusMessage
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * Полноценный мост к реальному нативному ядру sing-box (`net.clever-vpn:libbox-android` / `io.nekohasekai.libbox`).
 *
 * Реализует:
 * - `PlatformInterface`: `openTun()` с `detachFd()`, `autoDetectInterfaceControl()` с `protect(fd)`,
 *   монитор системного интерфейса (`ConnectivityManager.NetworkCallback`), `getInterfaces()`, `findConnectionOwner()`.
 * - `CommandServer`: валидация `checkConfig()`, запуск `startOrReloadService()`, остановка `closeService()`.
 * - `CommandClient`: живое чтение `StatusMessage` (`uplink`, `downlink`, `uplinkTotal`, `downlinkTotal`, `connectionsOut`, `memory`),
 *   потоковые логи (`writeLogs`), группы (`writeGroups`), реальный `urlTest()` и `selectOutbound()`.
 */
class LibboxBridge(
    private val vpnService: VpnService,
    private val getAllowedWhitelistPackages: () -> Set<String>,
    private val onStatusUpdate: (StatusMessage) -> Unit,
    private val onGroupsUpdate: (activeSelectedTag: String, delaysMs: Map<String, Int>) -> Unit,
    private val onLogLine: (String) -> Unit,
    private val onServiceStopRequested: () -> Unit,
    private val onServiceError: (String) -> Unit
) {
    private val appContext: Context = vpnService.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val isSetupDone = AtomicBoolean(false)
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun getCoreVersion(): String = runCatching {
        ensureSetup()
        Libbox.version()
    }.getOrElse { "libbox" }

    fun checkConfig(configJson: String): Result<Unit> = runCatching {
        ensureSetup()
        Libbox.checkConfig(configJson)
    }

    fun startCore(configJson: String): Result<Unit> = runCatching {
        ensureSetup()
        Libbox.checkConfig(configJson)

        val server = commandServer ?: Libbox.newCommandServer(serverHandler, platformInterface).also {
            it.start()
            commandServer = it
        }

        val overrides = OverrideOptions()
        server.startOrReloadService(configJson, overrides)
        connectCommandClient()
        onLogLine("[core] sing-box ${Libbox.version()} запущен через CommandServer")
    }.onFailure { err ->
        onLogLine("[error] Ошибка старта ядра: ${err.message}")
    }

    fun reloadCore(configJson: String): Result<Unit> = runCatching {
        ensureSetup()
        Libbox.checkConfig(configJson)
        val server = commandServer
        if (server == null) {
            return startCore(configJson)
        }
        server.startOrReloadService(configJson, OverrideOptions())
        onLogLine("[core] Конфигурация ядра перезагружена")
    }

    fun triggerUrlTest(groupTag: String = "auto-best-free"): Result<Unit> = runCatching {
        val client = commandClient ?: error("CommandClient не подключён")
        client.urlTest(groupTag)
        onLogLine("[urltest] Запущена проверка группы $groupTag")
    }

    fun selectOutbound(groupTag: String, outboundTag: String): Result<Unit> = runCatching {
        val client = commandClient ?: error("CommandClient не подключён")
        client.selectOutbound(groupTag, outboundTag)
        onLogLine("[route] Выбрана нода $outboundTag в группе $groupTag")
    }

    fun stopCore() {
        runCatching { commandClient?.disconnect() }
        commandClient = null
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        unregisterDefaultNetworkMonitor()
        onLogLine("[core] Туннель sing-box остановлен")
    }

    private fun ensureSetup() {
        if (isSetupDone.compareAndSet(false, true)) {
            val baseDir = File(appContext.filesDir, "singbox").apply { mkdirs() }
            val workDir = File(baseDir, "work").apply { mkdirs() }
            val tempDir = File(appContext.cacheDir, "singbox_tmp").apply { mkdirs() }

            val options = SetupOptions().apply {
                setBasePath(baseDir.absolutePath)
                setWorkingPath(workDir.absolutePath)
                setTempPath(tempDir.absolutePath)
                setFixAndroidStack(true)
                setLogMaxLines(500L)
                setDebug(BuildConfig.DEBUG)
            }
            Libbox.setup(options)
        }
    }

    private fun connectCommandClient() {
        runCatching { commandClient?.disconnect() }
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandLog)
            addCommand(Libbox.CommandGroup)
            setStatusInterval(1000L)
        }
        val client = Libbox.newCommandClient(clientHandler, options)
        client.connect()
        commandClient = client
    }

    private val serverHandler = object : CommandServerHandler {
        override fun serviceStop() {
            onLogLine("[core] Получен сигнал serviceStop от CommandServer")
            onServiceStopRequested()
        }

        override fun serviceReload() {
            onLogLine("[core] Получен сигнал serviceReload")
        }

        override fun getSystemProxyStatus(): SystemProxyStatus {
            return SystemProxyStatus().apply {
                available = false
                enabled = false
            }
        }

        override fun setSystemProxyEnabled(isEnabled: Boolean) {
            // Android VPN relies on TUN rather than system HTTP proxy
        }

        override fun writeDebugMessage(message: String?) {
            if (!message.isNullOrBlank()) {
                onLogLine("[debug] $message")
            }
        }
    }

    private val clientHandler = object : CommandClientHandler {
        override fun connected() {
            onLogLine("[ipc] CommandClient подключён к ядру sing-box")
        }

        override fun disconnected(message: String?) {
            if (!message.isNullOrBlank()) {
                onLogLine("[ipc] CommandClient отключён: $message")
            }
        }

        override fun clearLogs() {
            // Keep UI log history until user clears
        }

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null) return
            while (messageList.hasNext()) {
                val entry = messageList.next()
                val text = entry?.message ?: continue
                if (text.isNotBlank()) {
                    onLogLine(text)
                }
            }
        }

        override fun writeStatus(message: StatusMessage?) {
            if (message != null) {
                onStatusUpdate(message)
            }
        }

        override fun writeServiceStatus(message: ServiceStatusMessage?) {
            val apiErr = message?.apiError
            if (apiErr != null && !apiErr.message.isNullOrBlank()) {
                onServiceError(apiErr.message)
            }
        }

        override fun writeGroups(groups: OutboundGroupIterator?) {
            if (groups == null) return
            var activeTag = ""
            val delays = mutableMapOf<String, Int>()
            while (groups.hasNext()) {
                val group = groups.next() ?: continue
                if (group.tag == "auto-best-free" || group.tag == "vless-reality-entry") {
                    if (activeTag.isBlank() && !group.selected.isNullOrBlank()) {
                        activeTag = group.selected
                    }
                }
                val items = group.items
                while (items != null && items.hasNext()) {
                    val item = items.next() ?: continue
                    if (item.urlTestDelay > 0) {
                        delays[item.tag] = item.urlTestDelay
                    }
                }
            }
            onGroupsUpdate(activeTag, delays)
        }

        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
        override fun updateClashMode(newMode: String?) {}
        override fun writeConnectionEvents(events: ConnectionEvents?) {}
        override fun setDefaultLogLevel(level: Int) {}
    }

    private val platformInterface = object : PlatformInterface {
        override fun openTun(options: TunOptions): Int {
            if (VpnService.prepare(appContext) != null) {
                error("Отсутствует разрешение VpnService (пользователь не подтвердил VPN)")
            }

            val builder = vpnService.Builder()
                .setSession("AegisBox sing-box")
                .setMtu(options.mtu.takeIf { it in 1280..9000 } ?: 1400)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // IPv4 TUN addresses from sing-box TunOptions
            var hasAddress = false
            val v4Addrs = options.inet4Address
            while (v4Addrs != null && v4Addrs.hasNext()) {
                val prefix = v4Addrs.next() ?: continue
                builder.addAddress(prefix.address(), prefix.prefix())
                hasAddress = true
            }
            if (!hasAddress) {
                builder.addAddress("172.19.0.1", 30)
            }

            // Routes from sing-box TunOptions
            if (options.autoRoute) {
                val dnsAddr = runCatching { options.dnsServerAddress?.value }.getOrNull()
                if (!dnsAddr.isNullOrBlank()) {
                    builder.addDnsServer(dnsAddr)
                } else {
                    builder.addDnsServer("198.18.0.2")
                }

                var hasRoute = false
                val v4Routes = options.inet4RouteAddress
                while (v4Routes != null && v4Routes.hasNext()) {
                    val route = v4Routes.next() ?: continue
                    builder.addRoute(route.address(), route.prefix())
                    hasRoute = true
                }
                if (!hasRoute) {
                    builder.addRoute("0.0.0.0", 0)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val excludes = options.inet4RouteExcludeAddress
                    while (excludes != null && excludes.hasNext()) {
                        val ex = excludes.next() ?: continue
                        runCatching {
                            builder.excludeRoute(IpPrefix(InetAddress.getByName(ex.address()), ex.prefix()))
                        }
                    }
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
                builder.addDnsServer("198.18.0.2")
            }

            // STRICT WHITELIST MODE: call ONLY addAllowedApplication(pkg).
            // NEVER mix addDisallowedApplication() in the same Builder (throws IllegalStateException on Android).
            val allowedPackages = getAllowedWhitelistPackages()
            for (pkg in allowedPackages) {
                if (pkg == appContext.packageName) continue
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Package is not installed on this device — skip safely
                }
            }

            val configureIntent = PendingIntent.getActivity(
                appContext,
                0,
                Intent(appContext, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setConfigureIntent(configureIntent)

            val pfd = builder.establish()
                ?: error("Не удалось поднять TUN-интерфейс (Builder.establish вернул null)")

            val detachedFd = pfd.detachFd()
            onLogLine("[tun] Открыт TUN-интерфейс fd=$detachedFd (whitelist=${allowedPackages.size} пакетов)")
            return detachedFd
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun autoDetectInterfaceControl(fd: Int) {
            if (!vpnService.protect(fd)) {
                onLogLine("[warn] VpnService.protect(fd=$fd) вернул false")
            }
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            unregisterDefaultNetworkMonitor()
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    notifyInterfaceChanged(network, listener)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    notifyInterfaceChanged(network, listener, linkProperties)
                }

                override fun onLost(network: Network) {
                    val active = connectivityManager.activeNetwork
                    if (active != null) {
                        notifyInterfaceChanged(active, listener)
                    } else {
                        listener.updateDefaultInterface("", -1, false, false)
                    }
                }
            }
            networkCallback = callback
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    connectivityManager.registerBestMatchingNetworkCallback(
                        request,
                        callback,
                        android.os.Handler(android.os.Looper.getMainLooper())
                    )
                } else {
                    connectivityManager.registerNetworkCallback(request, callback)
                }
            }.onFailure {
                runCatching { connectivityManager.requestNetwork(request, callback) }
            }

            // Immediately push current active non-VPN network if available
            connectivityManager.activeNetwork?.let { active ->
                notifyInterfaceChanged(active, listener)
            }
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            unregisterDefaultNetworkMonitor()
        }

        override fun getInterfaces(): NetworkInterfaceIterator {
            val interfaces = mutableListOf<LibboxNetworkInterface>()
            runCatching {
                val enumIfs = NetworkInterface.getNetworkInterfaces()
                while (enumIfs != null && enumIfs.hasMoreElements()) {
                    val nif = enumIfs.nextElement() ?: continue
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.interfaceAddresses
                        .mapNotNull { ia ->
                            val host = ia.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                            "$host/${ia.networkPrefixLength}"
                        }
                    if (addrs.isEmpty()) continue

                    val libIf = LibboxNetworkInterface().apply {
                        setName(nif.name)
                        setIndex(nif.index)
                        setMTU(nif.mtu.takeIf { it > 0 } ?: 1500)
                        setAddresses(SimpleStringIterator(addrs))
                        val type = when {
                            nif.name.startsWith("wlan") || nif.name.startsWith("wifi") -> Libbox.InterfaceTypeWIFI
                            nif.name.startsWith("rmnet") || nif.name.startsWith("ccmni") -> Libbox.InterfaceTypeCellular
                            nif.name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
                            else -> Libbox.InterfaceTypeOther
                        }
                        setType(type)
                    }
                    interfaces.add(libIf)
                }
            }
            return SimpleNetworkInterfaceIterator(interfaces)
        }

        override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int
        ): ConnectionOwner {
            val owner = ConnectionOwner()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val proto = when (ipProtocol) {
                        OsConstants.IPPROTO_TCP -> OsConstants.IPPROTO_TCP
                        OsConstants.IPPROTO_UDP -> OsConstants.IPPROTO_UDP
                        else -> ipProtocol
                    }
                    val src = InetSocketAddress(InetAddress.getByName(sourceAddress), sourcePort)
                    val dst = InetSocketAddress(InetAddress.getByName(destinationAddress), destinationPort)
                    val uid = connectivityManager.getConnectionOwnerUid(proto, src, dst)
                    owner.setUserId(uid)
                    if (uid > 0) {
                        val pkg = appContext.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
                        owner.setAndroidPackageName(pkg)
                    }
                }
            }
            return owner
        }

        override fun includeAllNetworks(): Boolean = false
        override fun underNetworkExtension(): Boolean = false
        override fun clearDNSCache() {}
        override fun localDNSTransport(): LocalDNSTransport? = null
        override fun readWIFIState(): WIFIState? = null
        override fun sendNotification(notification: Notification?) {}
        override fun systemCertificates(): StringIterator = SimpleStringIterator(emptyList())
    }

    private fun notifyInterfaceChanged(
        network: Network,
        listener: InterfaceUpdateListener,
        knownLinkProps: LinkProperties? = null
    ) {
        runCatching {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
            val lp = knownLinkProps ?: connectivityManager.getLinkProperties(network) ?: return
            val ifName = lp.interfaceName ?: return
            val ifIndex = runCatching { NetworkInterface.getByName(ifName)?.index ?: -1 }.getOrDefault(-1)
            val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            listener.updateDefaultInterface(ifName, ifIndex, isExpensive, false)
        }
    }

    private fun unregisterDefaultNetworkMonitor() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(cb) }
    }

    private class SimpleStringIterator(items: List<String>) : StringIterator {
        private val iterator = items.iterator()
        private val count = items.size
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
        override fun len(): Int = count
    }

    private class SimpleNetworkInterfaceIterator(items: List<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        private val iterator = items.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }
}
