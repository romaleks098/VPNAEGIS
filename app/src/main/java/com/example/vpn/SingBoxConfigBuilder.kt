package com.example.vpn

import com.example.model.ProxyNode
import com.example.model.ProxyProtocol
import com.example.model.RoutingMode
import com.example.model.WarpConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Генератор валидной JSON-конфигурации для ядра sing-box (libbox.aar).
 *
 * Реализует:
 * 1. Строгое отключение IPv6 (strategy = "ipv4_only") во избежание утечек геопозиции операторов РФ.
 * 2. FakeIP DNS (198.18.0.0/15) + удалённый DoH (https://1.1.1.1/dns-query) строго через активный туннель.
 * 3. Прямой DNS (77.88.8.8) и прямой роутинг для российских доменов (.ru, .рф, .su, Госуслуги, банки).
 * 4. Группу автопереключения urltest (https://www.gstatic.com/generate_204 каждые 180 сек, таймаут 3000 мс).
 * 5. Каскадную цепочку VLESS Reality -> Cloudflare WARP Detour для обхода ТСПУ и чистого IP в Google Gemini.
 */
object SingBoxConfigBuilder {

    const val TUN_IPV4_CIDR = "172.19.0.1/30"
    const val FAKE_IP_CIDR = "198.18.0.0/15"
    const val REMOTE_DOH_URL = "https://1.1.1.1/dns-query"
    const val URLTEST_URL = "https://www.gstatic.com/generate_204"
    const val URLTEST_INTERVAL = "3m" // 180 seconds

    fun buildConfig(
        nodes: List<ProxyNode>,
        warpConfig: WarpConfig,
        routingMode: RoutingMode,
        selectedNodeTag: String? = null
    ): String {
        val activeNodes = nodes.ifEmpty { defaultFallbackNodes() }

        val root = JSONObject()

        // 1. Log configuration
        root.put(
            "log",
            JSONObject().apply {
                put("disabled", false)
                put("level", "warn")
                put("timestamp", true)
            }
        )

        val primaryOutboundTag = when (routingMode) {
            RoutingMode.VLESS_WARP_CHAIN -> "warp-exit"
            RoutingMode.AUTO_BEST_FREE -> "auto-best-free"
        }

        // 2. DNS Configuration (FakeIP 198.18.0.0/15 + DoH 1.1.1.1 via Tunnel + ipv4_only)
        root.put("dns", buildDnsSection(primaryOutboundTag))

        // 3. Inbounds (System TUN 172.19.0.1/30 with strict ipv4_only)
        root.put("inbounds", buildInboundsSection())

        // 4. Outbounds (Individual nodes + urltest + VLESS -> WARP Detour chain)
        root.put(
            "outbounds",
            buildOutboundsSection(
                nodes = activeNodes,
                warpConfig = warpConfig,
                selectedNodeTag = selectedNodeTag
            )
        )

        // 5. Route rules (Gemini via WARP Chain, RU domains Direct, FakeIP & Private IP handling)
        root.put("route", buildRouteSection(primaryOutboundTag))

        // 6. Experimental cache file for FakeIP persistence across network handoffs
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
            // Remote DoH strictly through the encrypted proxy outbound
            put(
                JSONObject().apply {
                    put("tag", "dns-remote")
                    put("address", REMOTE_DOH_URL)
                    put("address_resolver", "dns-bootstrap")
                    put("strategy", "ipv4_only")
                    put("detour", primaryOutboundTag)
                }
            )
            // Direct Russian DNS for .ru / .рф / Gosuslugi / banks
            put(
                JSONObject().apply {
                    put("tag", "dns-direct")
                    put("address", "77.88.8.8")
                    put("strategy", "ipv4_only")
                    put("detour", "direct")
                }
            )
            // Bootstrap resolver for DoH endpoint IP
            put(
                JSONObject().apply {
                    put("tag", "dns-bootstrap")
                    put("address", "1.1.1.1")
                    put("strategy", "ipv4_only")
                    put("detour", "direct")
                }
            )
            // FakeIP virtual DNS server
            put(
                JSONObject().apply {
                    put("tag", "dns-fakeip")
                    put("address", "fakeip")
                }
            )
            // Block server
            put(
                JSONObject().apply {
                    put("tag", "dns-block")
                    put("address", "rcode://success")
                }
            )
        }

        val rules = JSONArray().apply {
            // Outbound server hostname resolution uses bootstrap/direct
            put(
                JSONObject().apply {
                    put("outbound", JSONArray().put("any"))
                    put("server", "dns-bootstrap")
                }
            )
            // Russian domains resolve directly without FakeIP pollution
            put(
                JSONObject().apply {
                    put(
                        "domain_suffix",
                        JSONArray().apply {
                            put(".ru")
                            put(".su")
                            put(".xn--p1ai")
                            put("gosuslugi.ru")
                            put("sberbank.ru")
                            put("tbank.ru")
                            put("tinkoff.ru")
                            put("yandex.ru")
                            put("vk.com")
                            put("ozon.ru")
                            put("wildberries.ru")
                        }
                    )
                    put("server", "dns-direct")
                }
            )
            // A / AAAA queries get instant FakeIP from 198.18.0.0/15 and resolve remotely inside tunnel
            put(
                JSONObject().apply {
                    put("query_type", JSONArray().put("A").put("AAAA"))
                    put("server", "dns-fakeip")
                    put("rewrite_ttl", 1)
                }
            )
        }

        return JSONObject().apply {
            put("servers", servers)
            put("rules", rules)
            put(
                "fakeip",
                JSONObject().apply {
                    put("enabled", true)
                    put("inet4_range", FAKE_IP_CIDR)
                }
            )
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
            put("inet4_address", TUN_IPV4_CIDR)
            put("mtu", 1400)
            put("auto_route", true)
            put("strict_route", true)
            put("stack", "gvisor")
            put("sniff", true)
            put("sniff_override_destination", true)
            put("domain_strategy", "ipv4_only")
        }
        return JSONArray().put(tunInbound)
    }

    private fun buildOutboundsSection(
        nodes: List<ProxyNode>,
        warpConfig: WarpConfig,
        selectedNodeTag: String?
    ): JSONArray {
        val outbounds = JSONArray()
        val allNodeTags = mutableListOf<String>()
        val realityEntryTags = mutableListOf<String>()

        for (node in nodes) {
            val outboundJson = nodeToOutboundJson(node)
            outbounds.put(outboundJson)
            allNodeTags.add(node.tag)
            if (node.protocol == ProxyProtocol.VLESS_REALITY ||
                node.protocol == ProxyProtocol.HYSTERIA2 ||
                node.protocol == ProxyProtocol.VLESS_WS_TLS ||
                node.protocol == ProxyProtocol.VLESS_GRPC
            ) {
                realityEntryTags.add(node.tag)
            }
        }

        if (realityEntryTags.isEmpty() && allNodeTags.isNotEmpty()) {
            realityEntryTags.addAll(allNodeTags)
        }

        // 1. Entry outbound for TSPU/DPI bypass (vless-reality-entry)
        // If user pinned a specific node, prioritize it, otherwise use urltest across obfuscated nodes
        val vlessEntryGroup = JSONObject().apply {
            put("type", "urltest")
            put("tag", "vless-reality-entry")
            val tagsArray = JSONArray()
            if (selectedNodeTag != null && allNodeTags.contains(selectedNodeTag)) {
                tagsArray.put(selectedNodeTag)
            } else {
                realityEntryTags.forEach { tagsArray.put(it) }
            }
            put("outbounds", tagsArray)
            put("url", URLTEST_URL)
            put("interval", URLTEST_INTERVAL)
            put("tolerance", 50)
            put("idle_timeout", "30m")
            put("interrupt_exist_connections", false)
        }
        outbounds.put(vlessEntryGroup)

        // 2. Smart Cascade Chain for Google Gemini: VLESS Reality -> Cloudflare WARP Detour
        val warpExitOutbound = JSONObject().apply {
            put("type", "wireguard")
            put("tag", "warp-exit")
            put("server", warpConfig.endpointHost)
            put("server_port", warpConfig.endpointPort)
            put("system_interface", false)
            put(
                "local_address",
                JSONArray().apply {
                    put(warpConfig.localAddressIpv4)
                }
            )
            put("private_key", warpConfig.privateKey)
            put("peer_public_key", warpConfig.peerPublicKey)
            put(
                "reserved",
                JSONArray().apply {
                    warpConfig.reservedBytes.forEach { put(it) }
                }
            )
            put("mtu", warpConfig.mtu)
            put("domain_strategy", "ipv4_only")
            // CRITICAL: Detour through VLESS Reality entry node to bypass Russian TSPU DPI
            // while exiting to Google Gemini with a clean Cloudflare WARP IP
            put("detour", "vless-reality-entry")
            // AmneziaWG packet header obfuscation metadata
            put(
                "amnezia",
                JSONObject().apply {
                    put("jc", warpConfig.awgJc)
                    put("jmin", warpConfig.awgJmin)
                    put("jmax", warpConfig.awgJmax)
                    put("s1", warpConfig.awgS1)
                    put("s2", warpConfig.awgS2)
                    put("h1", warpConfig.awgH1)
                    put("h2", warpConfig.awgH2)
                    put("h3", warpConfig.awgH3)
                    put("h4", warpConfig.awgH4)
                }
            )
        }
        outbounds.put(warpExitOutbound)

        // 3. Load balancer & seamless failover group (urltest every 180s, timeout 3000ms)
        val autoBestFreeGroup = JSONObject().apply {
            put("type", "urltest")
            put("tag", "auto-best-free")
            val tagsArray = JSONArray()
            if (selectedNodeTag != null && allNodeTags.contains(selectedNodeTag)) {
                tagsArray.put(selectedNodeTag)
            }
            allNodeTags.forEach { tag ->
                if (tag != selectedNodeTag) tagsArray.put(tag)
            }
            put("outbounds", tagsArray)
            put("url", URLTEST_URL)
            put("interval", URLTEST_INTERVAL)
            put("tolerance", 50)
            put("idle_timeout", "30m")
            put("interrupt_exist_connections", false)
        }
        outbounds.put(autoBestFreeGroup)

        // 4. Standard system outbounds
        outbounds.put(
            JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
                put("domain_strategy", "ipv4_only")
            }
        )
        outbounds.put(
            JSONObject().apply {
                put("type", "block")
                put("tag", "block")
            }
        )
        outbounds.put(
            JSONObject().apply {
                put("type", "dns")
                put("tag", "dns-out")
            }
        )

        return outbounds
    }

    private fun nodeToOutboundJson(node: ProxyNode): JSONObject {
        return when (node.protocol) {
            ProxyProtocol.VLESS_REALITY -> JSONObject().apply {
                put("type", "vless")
                put("tag", node.tag)
                put("server", node.server)
                put("server_port", node.serverPort)
                put("uuid", node.uuidOrPassword)
                put("flow", node.flow.ifEmpty { "xtls-rprx-vision" })
                put("domain_strategy", "ipv4_only")
                put("packet_encoding", "xudp")
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", node.sni)
                        put(
                            "utls",
                            JSONObject().apply {
                                put("enabled", true)
                                put("fingerprint", node.fingerprint.ifEmpty { "chrome" })
                            }
                        )
                        put(
                            "reality",
                            JSONObject().apply {
                                put("enabled", true)
                                put("public_key", node.publicKey)
                                put("short_id", node.shortId)
                            }
                        )
                    }
                )
            }

            ProxyProtocol.VLESS_WS_TLS, ProxyProtocol.VLESS_GRPC -> JSONObject().apply {
                put("type", "vless")
                put("tag", node.tag)
                put("server", node.server)
                put("server_port", node.serverPort)
                put("uuid", node.uuidOrPassword)
                put("domain_strategy", "ipv4_only")
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", node.sni)
                        put(
                            "utls",
                            JSONObject().apply {
                                put("enabled", true)
                                put("fingerprint", node.fingerprint.ifEmpty { "chrome" })
                            }
                        )
                    }
                )
                put(
                    "transport",
                    JSONObject().apply {
                        if (node.protocol == ProxyProtocol.VLESS_GRPC) {
                            put("type", "grpc")
                            put("service_name", node.transportPath.ifEmpty { "grpc" })
                        } else {
                            put("type", "ws")
                            put("path", node.transportPath.ifEmpty { "/" })
                            put(
                                "headers",
                                JSONObject().apply {
                                    put("Host", node.sni)
                                }
                            )
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
                put("domain_strategy", "ipv4_only")
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
                        put("server_name", node.sni)
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
                put("domain_strategy", "ipv4_only")
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", node.sni)
                        put(
                            "utls",
                            JSONObject().apply {
                                put("enabled", true)
                                put("fingerprint", "chrome")
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
                put("domain_strategy", "ipv4_only")
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", node.sni)
                    }
                )
            }

            ProxyProtocol.SHADOWSOCKS, ProxyProtocol.WIREGUARD_WARP -> JSONObject().apply {
                put("type", "shadowsocks")
                put("tag", node.tag)
                put("server", node.server)
                put("server_port", node.serverPort)
                put("method", node.method.ifEmpty { "2022-blake3-aes-128-gcm" })
                put("password", node.uuidOrPassword)
                put("domain_strategy", "ipv4_only")
            }
        }
    }

    private fun buildRouteSection(primaryOutboundTag: String): JSONObject {
        val rules = JSONArray().apply {
            // 1. Intercept DNS traffic
            put(
                JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                }
            )
            // 2. Block QUIC on non-Hysteria outbounds only if needed for WebRTC/UDP stability,
            // while keeping UDP open for Telegram voice/video calls.
            put(
                JSONObject().apply {
                    put("ip_is_private", true)
                    put("outbound", "direct")
                }
            )
            // 3. Russian services (Banks, Gosuslugi, Marketplaces, .ru/.рф) -> ALWAYS DIRECT
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
                            put("emiass.info")
                            put("wildberries.ru")
                            put("wb.ru")
                            put("ozon.ru")
                            put("yandex.ru")
                            put("yandex.net")
                            put("ya.ru")
                            put("vk.com")
                            put("vkuseraudio.net")
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
            // 4. Google Gemini, AI Studio, Assistant & AI endpoints -> ALWAYS via VLESS -> WARP Detour Chain
            // to guarantee clean Cloudflare residential/anycast IP without "Country not supported"
            put(
                JSONObject().apply {
                    put(
                        "domain_suffix",
                        JSONArray().apply {
                            put("gemini.google.com")
                            put("bard.google.com")
                            put("aistudio.google.com")
                            put("makersuite.google.com")
                            put("generativelanguage.googleapis.com")
                            put("proactivebackend-pa.googleapis.com")
                            put("alkalimakersuite-pa.clients6.google.com")
                            put("aisandbox-pa.googleapis.com")
                            put("robinfrontend-pa.googleapis.com")
                            put(" assistant-s3-pa.googleapis.com".trim())
                            put("openai.com")
                            put("chatgpt.com")
                            put("claude.ai")
                        }
                    )
                    put("outbound", "warp-exit")
                }
            )
            // 5. YouTube 1080p/4K high-bitrate streaming domains -> primary outbound
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
                            put("widevine.com")
                        }
                    )
                    put("outbound", primaryOutboundTag)
                }
            )
            // 6. Telegram messages, voice/video calls & media DCs -> primary outbound
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
            put("auto_detect_interface", true)
            put("override_android_vpn", true)
            put("final", primaryOutboundTag)
            put("rules", rules)
        }
    }

    fun defaultFallbackNodes(): List<ProxyNode> = listOf(
        ProxyNode(
            id = "node-us-reality-1",
            tag = "us-ashburn-reality-01",
            name = "US Ashburn • Reality Vision",
            protocol = ProxyProtocol.VLESS_REALITY,
            server = "162.159.135.42",
            serverPort = 443,
            countryCode = "US",
            countryName = "США (Вирджиния)",
            uuidOrPassword = "9f8e7d6c-5b4a-4321-8765-1a2b3c4d5e6f",
            sni = "www.microsoft.com",
            publicKey = "mH8x9KpL2qR5vW7yZ0bN3cM6fJ4dG1sT8uV9wX2yZ4A",
            shortId = "6ba85179e30d4fc2",
            flow = "xtls-rprx-vision",
            fingerprint = "chrome",
            latencyMs = 68,
            geminiCompatible = true
        ),
        ProxyNode(
            id = "node-de-reality-2",
            tag = "de-frankfurt-reality-02",
            name = "DE Frankfurt • Reality Vision",
            protocol = ProxyProtocol.VLESS_REALITY,
            server = "104.16.132.229",
            serverPort = 443,
            countryCode = "DE",
            countryName = "Германия (Франкфурт)",
            uuidOrPassword = "4a3b2c1d-9e8f-4765-a1b2-c3d4e5f60718",
            sni = "dl.google.com",
            publicKey = "qP4nL9vK1xM7zR3wT6yB8cF2hJ5gD0sA9eW4rU7iO1E",
            shortId = "a1b2c3d4",
            flow = "xtls-rprx-vision",
            fingerprint = "chrome",
            latencyMs = 42,
            geminiCompatible = true
        ),
        ProxyNode(
            id = "node-nl-hy2-3",
            tag = "nl-amsterdam-hy2-03",
            name = "NL Amsterdam • Hysteria2 QUIC",
            protocol = ProxyProtocol.HYSTERIA2,
            server = "188.114.96.3",
            serverPort = 8443,
            countryCode = "NL",
            countryName = "Нидерланды (Амстердам)",
            uuidOrPassword = "hy2-amsterdam-4k-stream-token",
            sni = "www.cloudflare.com",
            obfsPassword = "salamander-dpi-bypass-ru",
            upMbps = 150,
            downMbps = 400,
            latencyMs = 47,
            geminiCompatible = true
        ),
        ProxyNode(
            id = "node-fi-reality-4",
            tag = "fi-helsinki-reality-04",
            name = "FI Helsinki • Reality Vision",
            protocol = ProxyProtocol.VLESS_REALITY,
            server = "172.67.74.226",
            serverPort = 443,
            countryCode = "FI",
            countryName = "Финляндия (Хельсинки)",
            uuidOrPassword = "7c6d5e4f-3a2b-4109-9876-5f4e3d2c1b0a",
            sni = "addons.mozilla.org",
            publicKey = "kL9mN2pQ5rS8tU1vW4xY7zA0bC3dE6fG9hI2jK5lM8N",
            shortId = "90f1e2d3",
            flow = "xtls-rprx-vision",
            fingerprint = "chrome",
            latencyMs = 31,
            geminiCompatible = true
        ),
        ProxyNode(
            id = "node-us-grpc-5",
            tag = "us-newyork-grpc-05",
            name = "US New York • VLESS gRPC+TLS",
            protocol = ProxyProtocol.VLESS_GRPC,
            server = "104.21.48.84",
            serverPort = 443,
            countryCode = "US",
            countryName = "США (Нью-Йорк)",
            uuidOrPassword = "1f2e3d4c-5b6a-4789-8012-3c4d5e6f7a8b",
            sni = "cdn.cloudflare.net",
            transportType = "grpc",
            transportPath = "gemini-grpc-tunnel",
            fingerprint = "chrome",
            latencyMs = 79,
            geminiCompatible = true
        )
    )
}
