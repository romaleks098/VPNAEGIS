package com.example.model

enum class RoutingMode(val id: String, val title: String, val subtitle: String) {
    VLESS_WARP_CHAIN(
        id = "vless_warp_chain",
        title = "VLESS + WARP Chain",
        subtitle = "Каскад VLESS Reality → Cloudflare WARP (Чистый IP для Gemini + обход ТСПУ)"
    ),
    AUTO_BEST_FREE(
        id = "auto_best_free",
        title = "Auto Best Free",
        subtitle = "Балансировщик urltest (180с) по быстрейшим нодам США / ЕС (YouTube 4K + TG)"
    )
}

enum class ProxyProtocol(val displayName: String, val obfuscationWeight: Int) {
    VLESS_REALITY("VLESS Reality", 100),
    HYSTERIA2("Hysteria2", 95),
    VLESS_WS_TLS("VLESS WS+TLS", 85),
    VLESS_GRPC("VLESS gRPC", 80),
    TROJAN_TLS("Trojan TLS", 70),
    VMESS_TLS("VMess TLS", 60),
    SHADOWSOCKS("Shadowsocks", 50),
    WIREGUARD_WARP("WARP AmneziaWG", 90)
}

data class ProxyNode(
    val id: String,
    val tag: String,
    val name: String,
    val protocol: ProxyProtocol,
    val server: String,
    val serverPort: Int,
    val countryCode: String,
    val countryName: String,
    val uuidOrPassword: String = "",
    // VLESS / Reality / TLS fields
    val sni: String = "www.microsoft.com",
    val publicKey: String = "",
    val shortId: String = "",
    val flow: String = "xtls-rprx-vision",
    val fingerprint: String = "chrome",
    val transportType: String = "tcp", // tcp, ws, grpc, quic
    val transportPath: String = "",
    val alpn: List<String> = listOf("h2", "http/1.1"),
    // Shadowsocks / Hysteria2 specifics
    val method: String = "2022-blake3-aes-128-gcm",
    val upMbps: Int = 100,
    val downMbps: Int = 250,
    val obfsPassword: String = "",
    // Telemetry
    val latencyMs: Int = 48,
    val isAlive: Boolean = true,
    val geminiCompatible: Boolean = true,
    val sourceUrl: String = "builtin",
    val rawUri: String = ""
) {
    val flagEmoji: String
        get() = when (countryCode.uppercase()) {
            "US" -> "🇺🇸"
            "DE" -> "🇩🇪"
            "NL" -> "🇳🇱"
            "FI" -> "🇫🇮"
            "SE" -> "🇸🇪"
            "GB", "UK" -> "🇬🇧"
            "FR" -> "🇫🇷"
            "PL" -> "🇵🇱"
            else -> "🌐"
        }
}

data class WarpConfig(
    val deviceId: String,
    val accountToken: String,
    val privateKey: String,
    val peerPublicKey: String = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
    val endpointHost: String = "162.159.192.1",
    val endpointPort: Int = 2408,
    val localAddressIpv4: String = "172.16.0.2/32",
    val localAddressIpv6: String = "2606:4700:110:8a3f:c412:91b2:1104:5c81/128",
    val reservedBytes: List<Int> = listOf(142, 88, 219),
    val mtu: Int = 1280,
    // AmneziaWG DPI obfuscation parameters for Russian TSPU bypass
    val awgJc: Int = 5,
    val awgJmin: Int = 50,
    val awgJmax: Int = 1000,
    val awgS1: Int = 32,
    val awgS2: Int = 48,
    val awgH1: Long = 1249018421L,
    val awgH2: Long = 1984120512L,
    val awgH3: Long = 1658923104L,
    val awgH4: Long = 1409581293L,
    val generatedAtEpochMs: Long = System.currentTimeMillis()
)

data class SubscriptionSource(
    val id: String,
    val title: String,
    val url: String,
    val formatHint: String, // "Base64 / URI", "Clash YAML", "Mirror"
    val enabled: Boolean = true,
    val lastNodeCount: Int = 0,
    val lastSyncTimeMs: Long = 0L
)

data class AppRoutingItem(
    val packageName: String,
    val appName: String,
    val category: String, // "Gemini & AI", "Видео и Соцсети", "Браузеры", "Установленные"
    val isWhitelisted: Boolean,
    val isInstalledOnDevice: Boolean,
    val isRecommendedTarget: Boolean = false
)

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class VpnTelemetryState(
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val routingMode: RoutingMode = RoutingMode.VLESS_WARP_CHAIN,
    val activeNode: ProxyNode? = null,
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
    val totalUploadedBytes: Long = 0L,
    val connectedSinceEpochMs: Long = 0L,
    val tunAddress: String = "172.19.0.1/30",
    val fakeIpPool: String = "198.18.0.0/15",
    val remoteDoh: String = "https://1.1.1.1/dns-query",
    val lastError: String? = null,
    val activeSingBoxJson: String = ""
)
