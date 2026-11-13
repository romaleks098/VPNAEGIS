package com.example.model

enum class RoutingMode(val id: String, val title: String, val subtitle: String) {
    VLESS_WARP_CHAIN(
        id = "vless_warp_chain",
        title = "VLESS + WARP",
        subtitle = "Каскад VLESS → WARP Detour для Google Gemini"
    ),
    AUTO_BEST_FREE(
        id = "auto_best_free",
        title = "Auto Best Free",
        subtitle = "Балансировщик urltest (180с) по быстрейшим нодам"
    )
}

enum class ProxyProtocol(val displayName: String, val obfuscationWeight: Int) {
    VLESS_REALITY("VLESS Reality", 100),
    HYSTERIA2("Hysteria2", 95),
    VLESS_XHTTP("VLESS XHTTP", 90),
    VLESS_WS_TLS("VLESS WS+TLS", 85),
    VLESS_GRPC("VLESS gRPC", 80),
    TROJAN_TLS("Trojan TLS", 70),
    VMESS_TLS("VMess TLS", 60),
    SHADOWSOCKS("Shadowsocks", 50)
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
    val sni: String = "www.microsoft.com",
    val publicKey: String = "",
    val shortId: String = "",
    val flow: String = "",
    val fingerprint: String = "chrome",
    val transportType: String = "tcp", // tcp, ws, grpc, xhttp, quic
    val transportPath: String = "",
    val tlsEnabled: Boolean = true,
    val alpn: List<String> = listOf("h2", "http/1.1"),
    val method: String = "2022-blake3-aes-128-gcm",
    val upMbps: Int = 100,
    val downMbps: Int = 250,
    val obfsPassword: String = "",
    // -1 means unreachable / not tested yet; >= 0 means real measured RTT in ms
    val latencyMs: Int = -1,
    val isAlive: Boolean = false,
    val geminiCompatible: Boolean = true,
    val sourceUrl: String = "subscription",
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
            "CA" -> "🇨🇦"
            "JP" -> "🇯🇵"
            "SG" -> "🇸🇬"
            else -> "🌐"
        }
}

data class WarpConfig(
    val deviceId: String = "",
    val accountToken: String = "",
    val privateKey: String = "",
    val publicKey: String = "",
    val peerPublicKey: String = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
    val endpointHost: String = "162.159.192.1",
    val endpointPort: Int = 2408,
    val localAddressIpv4: String = "",
    val localAddressIpv6: String = "",
    val reservedBytes: List<Int> = emptyList(),
    val mtu: Int = 1280,
    val generatedAtEpochMs: Long = 0L
) {
    val isRegistered: Boolean
        get() = privateKey.isNotBlank() && localAddressIpv4.isNotBlank() && reservedBytes.size == 3
}

data class SubscriptionSource(
    val id: String,
    val title: String,
    val url: String,
    val formatHint: String,
    val enabled: Boolean = true,
    val lastNodeCount: Int = 0,
    val lastSyncTimeMs: Long = 0L
)

data class AppRoutingItem(
    val packageName: String,
    val appName: String,
    val category: String,
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
    val activeCoreOutboundTag: String = "",
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
    val totalUploadedBytes: Long = 0L,
    val activeConnectionsCount: Int = 0,
    val coreMemoryBytes: Long = 0L,
    val connectedSinceEpochMs: Long = 0L,
    val tunAddress: String = "172.19.0.1/30",
    val fakeIpPool: String = "198.18.0.0/15",
    val remoteDoh: String = "https://1.1.1.1/dns-query",
    val lastError: String? = null,
    val activeSingBoxJson: String = "",
    val coreVersion: String = "",
    val nodeDelaysMs: Map<String, Int> = emptyMap()
)
