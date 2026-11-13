package com.example.vpn

import com.example.model.ProxyNode
import com.example.model.ProxyProtocol
import com.example.model.RoutingMode
import com.example.model.WarpConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Генератор строго валидной JSON-конфигурации для sing-box 1.12–1.14+ (`net.clever-vpn:libbox-android`).
 *
 * Ключевые требования актуальной схемы sing-box:
 * 1. `endpoints` (корневая секция): Cloudflare WARP (`type: "wireguard"`, `system: false`, `mtu: 1280`,
 *    `detour` на активную входную группу `vless-reality-entry`). Без устаревших полей AmneziaWG.
 * 2. `inbounds`: TUN `address: ["172.19.0.1/30"]`, `mtu: 1400`, `auto_route: true`, `strict_route: true`, `stack: "gvisor"`.
 * 3. `dns`: современные типы серверов `fakeip` (`198.18.0.0/15`), `https` (`1.1.1.1`, `detour`), `udp` (`77.88.8.8`), `local`;
 *    `strategy: "ipv4_only"` для блокировки утечек геолокации через IPv6.
 * 4. `route.rules`: `{"action": "sniff"}`, `{"protocol": "dns", "action": "hijack-dns"}`, прямые правила
 *    для приватных IP, `.ru`/`.рф`/банков и IP/доменов самих прокси-серверов (исключение петли маршрутизации),
 *    блокировка утечного QUIC (UDP 443) для Gemini и строгая маршрутизация Gemini через `warp-exit` (при наличии WARP).
 */
object SingBoxConfigBuilder {

    const val TUN_IPV4_CIDR = "172.19.0.1/30"
    const val FAKE_IP_CIDR = "198.18.0.0/15"
    const val REMOTE_DOH_HOST = "1.1.1.1"
    const val URLTEST_URL = "https://www.gstatic.com/generate_204"
    const val URLTEST_INTERVAL = "3m"

    fun buildConfig(
        nodes: List<ProxyNode>,
        warpConfig: WarpConfig?,
        routingMode: RoutingMode,
        selectedNodeTag: String? = null
    ): String {
        val usableNodes = nodes.filter { it.server.isNotBlank() && it.serverPort in 1..65535 }
        require(usableNodes.isNotEmpty()) {
            "Список серверов пуст. Нажмите «Обновить и протестировать серверы» или импортируйте ключ (vless://, hy2://, trojan://)."
        }

        val hasValidWarp = warpConfig != null && warpConfig.isRegistered
        val useWarpChain = routingMode == RoutingMode.VLESS_WARP_CHAIN && hasValidWarp

        val primaryOutboundTag = if (useWarpChain) "warp-exit" else "auto-best-free"
        val geminiOutboundTag = if (hasValidWarp) "warp-exit" else "auto-best-free"

        val root = JSONObject()

        // 1. Log
        root.put(
            "log",
            JSONObject().apply {
                put("disabled", false)
                put("level", "info")
                put("timestamp", true)
            }
        )

        // 2. DNS (sing-box 1.12+ typed servers + FakeIP + ipv4_only)
        root.put("dns", buildDnsSection(primaryOutboundTag))

        // 3. Inbounds (sing-box 1.12+ address array)
        root.put("inbounds", buildInboundsSection())

        // 4. Endpoints (Cloudflare WARP WireGuard lives in root endpoints[] since sing-box 1.11+)
        if (hasValidWarp && warpConfig != null) {
            root.put("endpoints", buildEndpointsSection(warpConfig))
        }

        // 5. Outbounds
        root.put(
            "outbounds",
            buildOutboundsSection(
                nodes = usableNodes,
                selectedNodeTag = selectedNodeTag
            )
        )

        // 6. Route (actions: sniff, hijack-dns, reject + loop prevention + Gemini WARP Chain + RU direct)
        root.put(
            "route",
            buildRouteSection(
                nodes = usableNodes,
                warpConfig = if (hasValidWarp) warpConfig else null,
                primaryOutboundTag = primaryOutboundTag,
                geminiOutboundTag = geminiOutboundTag
            )
        )

        // 7. Experimental cache_file for FakeIP state
        root.put(
            "experimental",
            JSONObject().apply {
                put(
                    "cache_file",
                    JSONObject().apply {
                        put("enabled", true)
                        put("store_fakeip", true)
                    }
                )
            }
        )

        return root.toString(2)
    }

    private fun buildDnsSection(primaryOutboundTag: String): JSONObject {
        val servers = JSONArray().apply {
            // Remote DoH via active tunnel (using IP 1.1.1.1 so no extra domain_resolver needed for DoH host)
            put(
                JSONObject().apply {
                    put("tag", "dns-remote")
                    put("type", "https")
                    put("server", REMOTE_DOH_HOST)
                    put("server_port", 443)
                    put("path", "/dns-query")
                    put("detour", primaryOutboundTag)
                }
            )
            // Direct Russian DNS for .ru / .рф / Gosuslugi / banks
            put(
                JSONObject().apply {
                    put("tag", "dns-direct")
                    put("type", "udp")
                    put("server", "77.88.8.8")
                    put("server_port", 53)
                }
            )
            // System local resolver fallback
            put(
                JSONObject().apply {
                    put("tag", "dns-local")
                    put("type", "local")
                }
            )
            // FakeIP virtual DNS server
            put(
                JSONObject().apply {
                    put("tag", "dns-fakeip")
                    put("type", "fakeip")
                    put("inet4_range", FAKE_IP_CIDR)
                }
            )
        }

        val rules = JSONArray().apply {
            // Russian domains resolve directly without FakeIP
            put(
                JSONObject().apply {
                    put(
                        "domain_suffix",
                        JSONArray().apply {
                            put(".ru")
                            put(".su")
                            put(".xn--p1ai")
                            put("gosuslugi.ru")
                            put("gu-st.ru")
                            put("sberbank.ru")
                            put("sber.ru")
                            put("tbank.ru")
                            put("tinkoff.ru")
                            put("vtb.ru")
                            put("alfabank.ru")
                            put("yandex.ru")
                            put("yandex.net")
                            put("vk.com")
                            put("ozon.ru")
                            put("wildberries.ru")
                        }
                    )
                    put("action", "route")
                    put("server", "dns-direct")
                }
            )
            // A / AAAA queries receive instant FakeIP from 198.18.0.0/15
            put(
                JSONObject().apply {
                    put("query_type", JSONArray().put("A").put("AAAA"))
                    put("action", "route")
                    put("server", "dns-fakeip")
                }
            )
        }

        return JSONObject().apply {
            put("servers", servers)
            put("rules", rules)
            put("final", "dns-remote")
            put("strategy", "ipv4_only")
            put("independent_cache", true)
        }
    }

    private fun buildInboundsSection(): JSONArray {
        val tunInbound = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "tun0")
            put("address", JSONArray().put(TUN_IPV4_CIDR))
            put("mtu", 1400)
            put("auto_route", true)
            put("strict_route", true)
            put("stack", "gvisor")
        }
        return JSONArray().put(tunInbound)
    }

    private fun buildEndpointsSection(warpConfig: WarpConfig): JSONArray {
        val addresses = JSONArray().apply {
            put(warpConfig.localAddressIpv4)
        }

        val peer = JSONObject().apply {
            put("address", warpConfig.endpointHost)
            put("port", warpConfig.endpointPort)
            put("public_key", warpConfig.peerPublicKey)
            put("allowed_ips", JSONArray().put("0.0.0.0/0"))
            if (warpConfig.reservedBytes.size == 3) {
                put(
                    "reserved",
                    JSONArray().apply {
                        warpConfig.reservedBytes.forEach { put(it) }
                    }
                )
            }
        }

        val warpEndpoint = JSONObject().apply {
            put("type", "wireguard")
            put("tag", "warp-exit")
            put("system", false)
            put("mtu", 1280)
            put("address", addresses)
            put("private_key", warpConfig.privateKey)
            put("peers", JSONArray().put(peer))
            // Detour WireGuard packets through the active VLESS Reality / proxy group
            put("detour", "vless-reality-entry")
        }

        return JSONArray().put(warpEndpoint)
    }

    private fun buildOutboundsSection(
        nodes: List<ProxyNode>,
        selectedNodeTag: String?
    ): JSONArray {
        val outbounds = JSONArray()
        val allTags = mutableListOf<String>()
        val entryCandidateTags = mutableListOf<String>()

        for (node in nodes) {
            val ob = nodeToOutboundJson(node) ?: continue
            outbounds.put(ob)
            allTags.add(node.tag)
            if (node.protocol != ProxyProtocol.SHADOWSOCKS) {
                entryCandidateTags.add(node.tag)
            }
        }

        if (entryCandidateTags.isEmpty()) {
            entryCandidateTags.addAll(allTags)
        }

        // Priority order: selected node first if provided
        val orderedTags = if (selectedNodeTag != null && allTags.contains(selectedNodeTag)) {
            listOf(selectedNodeTag) + allTags.filter { it != selectedNodeTag }
        } else {
            allTags
        }

        val orderedEntryTags = if (selectedNodeTag != null && entryCandidateTags.contains(selectedNodeTag)) {
            listOf(selectedNodeTag) + entryCandidateTags.filter { it != selectedNodeTag }
        } else {
            entryCandidateTags
        }

        // Entry selector/urltest used as detour upstream for WARP endpoint
        outbounds.put(
            JSONObject().apply {
                put("type", "urltest")
                put("tag", "vless-reality-entry")
                put("outbounds", JSONArray(orderedEntryTags))
                put("url", URLTEST_URL)
                put("interval", URLTEST_INTERVAL)
                put("tolerance", 50)
                put("idle_timeout", "30m")
                put("interrupt_exist_connections", false)
            }
        )

        // General auto-best-free urltest group
        outbounds.put(
            JSONObject().apply {
                put("type", "urltest")
                put("tag", "auto-best-free")
                put("outbounds", JSONArray(orderedTags))
                put("url", URLTEST_URL)
                put("interval", URLTEST_INTERVAL)
                put("tolerance", 50)
                put("idle_timeout", "30m")
                put("interrupt_exist_connections", false)
            }
        )

        outbounds.put(
            JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            }
        )

        return outbounds
    }

    private fun nodeToOutboundJson(node: ProxyNode): JSONObject? {
        return when (node.protocol) {
            ProxyProtocol.VLESS_REALITY -> {
                if (node.publicKey.isBlank()) return null
                JSONObject().apply {
                    put("type", "vless")
                    put("tag", node.tag)
                    put("server", node.server)
                    put("server_port", node.serverPort)
                    put("uuid", node.uuidOrPassword)
                    if (node.flow.isNotBlank()) {
                        put("flow", node.flow)
                    }
                    put("packet_encoding", "xudp")
                    put(
                        "tls",
                        JSONObject().apply {
                            put("enabled", true)
                            put("server_name", node.sni.ifBlank { "www.microsoft.com" })
                            put(
                                "utls",
                                JSONObject().apply {
                                    put("enabled", true)
                                    put("fingerprint", node.fingerprint.ifBlank { "chrome" })
                                }
                            )
                            put(
                                "reality",
                                JSONObject().apply {
                                    put("enabled", true)
                                    put("public_key", node.publicKey)
                                    if (node.shortId.isNotBlank()) {
                                        put("short_id", node.shortId)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            ProxyProtocol.VLESS_WS_TLS, ProxyProtocol.VLESS_GRPC, ProxyProtocol.VLESS_XHTTP -> JSONObject().apply {
                put("type", "vless")
                put("tag", node.tag)
                put("server", node.server)
                put("server_port", node.serverPort)
                put("uuid", node.uuidOrPassword)
                put("packet_encoding", "xudp")
                if (node.tlsEnabled) {
                    put(
                        "tls",
                        JSONObject().apply {
                            put("enabled", true)
                            put("server_name", node.sni.ifBlank { node.server })
                            put(
                                "utls",
                                JSONObject().apply {
                                    put("enabled", true)
                                    put("fingerprint", node.fingerprint.ifBlank { "chrome" })
                                }
                            )
                        }
                    )
                }
                put(
                    "transport",
                    JSONObject().apply {
                        when (node.protocol) {
                            ProxyProtocol.VLESS_GRPC -> {
                                put("type", "grpc")
                                put("service_name", node.transportPath.ifBlank { "grpc" })
                            }
                            ProxyProtocol.VLESS_XHTTP -> {
                                put("type", "httpupgrade")
                                put("host", node.sni.ifBlank { node.server })
                                put("path", node.transportPath.ifBlank { "/" })
                            }
                            else -> {
                                put("type", "ws")
                                put("path", node.transportPath.ifBlank { "/" })
                                put(
                                    "headers",
                                    JSONObject().apply {
                                        put("Host", node.sni.ifBlank { node.server })
                                    }
                                )
                            }
                        }
                    }
                )
            }

            ProxyProtocol.HYSTERIA2 -> JSONObject().apply {
                put("type", "hysteria2")
                put("tag", node.tag)
                put("server", node.server)
                put("server_port", node.serverPort)
                put("password", node.uuidOrPassword)
                put("up_mbps", node.upMbps)
                put("down_mbps", node.downMbps)
                if (node.obfsPassword.isNotBlank()) {
                    put(
                        "obfs",
                        JSONObject().apply {
                            put("type", "salamander")
                            put("password", node.obfsPassword)
                        }
                    )
                }
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", node.sni.ifBlank { node.server })
                        put("alpn", JSONArray().put("h3"))
                    }
                )
            }

            ProxyProtocol.TROJAN_TLS -> JSONObject().apply {
                put("type", "trojan")
                put("tag", node.tag)
                put("server", node.server)
                put("server_port", node.serverPort)
                put("password", node.uuidOrPassword)
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", node.sni.ifBlank { node.server })
                        put(
                            "utls",
                            JSONObject().apply {
                                put("enabled", true)
                                put("fingerprint", node.fingerprint.ifBlank { "chrome" })
                            }
                        )
                    }
                )
            }

            ProxyProtocol.VMESS_TLS -> JSONObject().apply {
                put("type", "vmess")
                put("tag", node.tag)
                put("server", node.server)
                put("server_port", node.serverPort)
                put("uuid", node.uuidOrPassword)
                put("security", "auto")
                put("alter_id", 0)
                if (node.tlsEnabled) {
                    put(
                        "tls",
                        JSONObject().apply {
                            put("enabled", true)
                            put("server_name", node.sni.ifBlank { node.server })
                        }
                    )
                }
                if (node.transportType == "ws") {
                    put(
                        "transport",
                        JSONObject().apply {
                            put("type", "ws")
                            put("path", node.transportPath.ifBlank { "/" })
                            put(
                                "headers",
                                JSONObject().apply {
                                    put("Host", node.sni.ifBlank { node.server })
                                }
                            )
                        }
                    )
                }
            }

            ProxyProtocol.SHADOWSOCKS -> JSONObject().apply {
                put("type", "shadowsocks")
                put("tag", node.tag)
                put("server", node.server)
                put("server_port", node.serverPort)
                put("method", node.method.ifBlank { "2022-blake3-aes-128-gcm" })
                put("password", node.uuidOrPassword)
            }
        }
    }

    private fun buildRouteSection(
        nodes: List<ProxyNode>,
        warpConfig: WarpConfig?,
        primaryOutboundTag: String,
        geminiOutboundTag: String
    ): JSONObject {
        val rules = JSONArray().apply {
            // 1. Sniff incoming TCP/UDP streams (sing-box 1.11+ rule action)
            put(JSONObject().apply { put("action", "sniff") })

            // 2. Hijack DNS traffic into sing-box DNS module
            put(
                JSONObject().apply {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                }
            )

            // 3. Private IPs direct
            put(
                JSONObject().apply {
                    put("ip_is_private", true)
                    put("outbound", "direct")
                }
            )

            // 4. Prevent routing loops: proxy server domains/IPs and WARP endpoint must bypass TUN when dialed
            val serverDomains = nodes.map { it.server }.filter { !isIpAddress(it) }.distinct()
            val serverIps = buildList {
                nodes.map { it.server }.filter { isIpAddress(it) }.forEach { add(if (it.contains("/")) it else "$it/32") }
                warpConfig?.endpointHost?.takeIf { isIpAddress(it) }?.let { add("$it/32") }
            }.distinct()

            if (serverDomains.isNotEmpty() || serverIps.isNotEmpty()) {
                put(
                    JSONObject().apply {
                        if (serverDomains.isNotEmpty()) {
                            put("domain", JSONArray(serverDomains))
                        }
                        if (serverIps.isNotEmpty()) {
                            put("ip_cidr", JSONArray(serverIps))
                        }
                        put("outbound", "direct")
                    }
                )
            }

            // 5. Russian services (Banks, Gosuslugi, Marketplaces, .ru/.рф) -> DIRECT
            put(
                JSONObject().apply {
                    put(
                        "domain_suffix",
                        JSONArray().apply {
                            put(".ru")
                            put(".su")
                            put(".xn--p1ai")
                            put("gosuslugi.ru")
                            put("gu-st.ru")
                            put("sberbank.ru")
                            put("sber.ru")
                            put("tbank.ru")
                            put("tinkoff.ru")
                            put("vtb.ru")
                            put("alfabank.ru")
                            put("nalog.ru")
                            put("mos.ru")
                            put("wildberries.ru")
                            put("wb.ru")
                            put("ozon.ru")
                            put("yandex.ru")
                            put("yandex.net")
                            put("ya.ru")
                            put("vk.com")
                            put("userapi.com")
                            put("dzen.ru")
                            put("avito.ru")
                            put("rutube.ru")
                            put("kinopoisk.ru")
                            put("2gis.ru")
                        }
                    )
                    put("outbound", "direct")
                }
            )

            val geminiDomains = JSONArray().apply {
                put("gemini.google.com")
                put("bard.google.com")
                put("aistudio.google.com")
                put("makersuite.google.com")
                put("generativelanguage.googleapis.com")
                put("proactivebackend-pa.googleapis.com")
                put("alkalimakersuite-pa.clients6.google.com")
                put("aisandbox-pa.googleapis.com")
                put("robinfrontend-pa.googleapis.com")
                put("assistant-s3-pa.googleapis.com")
                put("openai.com")
                put("chatgpt.com")
                put("claude.ai")
            }

            // 6. Reject UDP/443 (QUIC) on Gemini AI domains so Chrome/Android falls back to TCP TLS inside VLESS->WARP (prevents MTU/UDP leaks)
            put(
                JSONObject().apply {
                    put("network", "udp")
                    put("port", 443)
                    put("domain_suffix", geminiDomains)
                    put("action", "reject")
                }
            )

            // 7. Google Gemini & AI services -> WARP -> VLESS cascade chain
            put(
                JSONObject().apply {
                    put("domain_suffix", geminiDomains)
                    put("outbound", geminiOutboundTag)
                }
            )

            // 8. YouTube 1080p/4K streaming
            put(
                JSONObject().apply {
                    put(
                        "domain_suffix",
                        JSONArray().apply {
                            put("youtube.com")
                            put("googlevideo.com")
                            put("ytimg.com")
                            put("youtu.be")
                            put("ggpht.com")
                            put("youtubei.googleapis.com")
                        }
                    )
                    put("outbound", primaryOutboundTag)
                }
            )

            // 9. Telegram messages, voice/video calls & media DCs
            put(
                JSONObject().apply {
                    put(
                        "domain_suffix",
                        JSONArray().apply {
                            put("t.me")
                            put("telegram.org")
                            put("telegram.me")
                            put("tdesktop.com")
                            put("stel.com")
                            put("tx.me")
                        }
                    )
                    put(
                        "ip_cidr",
                        JSONArray().apply {
                            put("91.108.4.0/22")
                            put("91.108.8.0/22")
                            put("91.108.12.0/22")
                            put("91.108.16.0/22")
                            put("91.108.56.0/22")
                            put("149.154.160.0/20")
                            put("185.76.151.0/24")
                        }
                    )
                    put("outbound", primaryOutboundTag)
                }
            )
        }

        return JSONObject().apply {
            put("default_domain_resolver", "dns-direct")
            put("auto_detect_interface", true)
            put("override_android_vpn", true)
            put("final", primaryOutboundTag)
            put("rules", rules)
        }
    }

    private fun isIpAddress(host: String): Boolean {
        val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
        return ipv4Regex.matches(host.trim()) || host.contains(":")
    }
}
