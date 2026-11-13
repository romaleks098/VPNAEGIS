package com.example.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppRoutingItem
import com.example.model.ProxyNode
import com.example.model.ProxyProtocol
import com.example.model.RoutingMode
import com.example.model.SubscriptionSource
import com.example.model.WarpConfig
import com.example.vpn.X25519
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.system.measureTimeMillis

private val Context.vpnDataStore by preferencesDataStore(name = "aegisbox_vpn_prefs")

/**
 * Агрегатор реальных бесплатных источников серверов, парсер URI / Base64 / sing-box JSON / Clash YAML,
 * реальный регистратор Cloudflare WARP (X25519) и менеджер поприложенческого Белого списка.
 */
class FreeServersRepository(private val context: Context) {

    companion object {
        private val KEY_ROUTING_MODE = stringPreferencesKey("routing_mode")
        private val KEY_SELECTED_NODE_TAG = stringPreferencesKey("selected_node_tag")
        private val KEY_WHITELIST_PACKAGES = stringSetPreferencesKey("whitelist_packages")
        private val KEY_INCLUDE_GMS_DEPS = booleanPreferencesKey("include_gms_deps")
        private val KEY_SUBSCRIPTION_SOURCES = stringPreferencesKey("subscription_sources_json")
        private val KEY_CACHED_NODES = stringPreferencesKey("cached_nodes_json")
        private val KEY_WARP_CONFIG = stringPreferencesKey("warp_config_json")

        val GEMINI_SUPPORTED_COUNTRIES = setOf("US", "DE", "NL", "FI", "SE", "GB", "UK", "FR", "PL", "CA", "JP", "SG")

        val SYSTEM_GMS_PACKAGES = setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.webview"
        )

        val DEFAULT_WHITELIST_PACKAGES = setOf(
            "com.google.android.googlequicksearchbox", // Google App
            "com.google.android.apps.bard",           // Google Gemini
            "com.google.android.youtube",             // YouTube
            "org.telegram.messenger",                 // Telegram
            "org.thunderdog.challegram",              // Telegram X
            "com.android.chrome",                     // Google Chrome
            "org.mozilla.firefox",                    // Firefox
            "com.brave.browser",                      // Brave
            "com.opera.browser",                      // Opera
            "com.microsoft.emmx"                      // Microsoft Edge
        )

        val DEFAULT_SUBSCRIPTION_SOURCES = listOf(
            SubscriptionSource(
                id = "sub-barry-vless",
                title = "GitHub • Barry-Far V2Ray Configs (Sub1)",
                url = "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt",
                formatHint = "Base64 / URI",
                enabled = true
            ),
            SubscriptionSource(
                id = "sub-mahdibland",
                title = "GitHub • Mahdibland V2Ray Aggregator",
                url = "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge_base64.txt",
                formatHint = "Base64",
                enabled = true
            ),
            SubscriptionSource(
                id = "sub-epodonios",
                title = "GitHub • Epodonios All Configs",
                url = "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
                formatHint = "Plain URI",
                enabled = true
            )
        )
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val routingModeFlow: Flow<RoutingMode> = context.vpnDataStore.data.map { prefs ->
        val raw = prefs[KEY_ROUTING_MODE] ?: RoutingMode.VLESS_WARP_CHAIN.id
        RoutingMode.entries.find { it.id == raw } ?: RoutingMode.VLESS_WARP_CHAIN
    }

    val selectedNodeTagFlow: Flow<String?> = context.vpnDataStore.data.map { prefs ->
        prefs[KEY_SELECTED_NODE_TAG]
    }

    val includeGmsDependenciesFlow: Flow<Boolean> = context.vpnDataStore.data.map { prefs ->
        prefs[KEY_INCLUDE_GMS_DEPS] ?: true
    }

    val whitelistPackagesFlow: Flow<Set<String>> = context.vpnDataStore.data.map { prefs ->
        prefs[KEY_WHITELIST_PACKAGES] ?: detectDefaultWhitelistWithBrowsers()
    }

    val subscriptionSourcesFlow: Flow<List<SubscriptionSource>> = context.vpnDataStore.data.map { prefs ->
        val json = prefs[KEY_SUBSCRIPTION_SOURCES]
        if (json.isNullOrBlank()) {
            DEFAULT_SUBSCRIPTION_SOURCES
        } else {
            parseSourcesJson(json).ifEmpty { DEFAULT_SUBSCRIPTION_SOURCES }
        }
    }

    val nodesFlow: Flow<List<ProxyNode>> = context.vpnDataStore.data.map { prefs ->
        val json = prefs[KEY_CACHED_NODES]
        if (json.isNullOrBlank()) emptyList() else parseNodesJson(json)
    }

    val warpConfigFlow: Flow<WarpConfig> = context.vpnDataStore.data.map { prefs ->
        val json = prefs[KEY_WARP_CONFIG]
        if (json.isNullOrBlank()) WarpConfig() else (parseWarpJson(json) ?: WarpConfig())
    }

    suspend fun getEffectiveWhitelistPackages(): Set<String> {
        val base = whitelistPackagesFlow.first().toMutableSet()
        if (includeGmsDependenciesFlow.first()) {
            base.addAll(SYSTEM_GMS_PACKAGES)
        }
        return base
    }

    suspend fun setRoutingMode(mode: RoutingMode) {
        context.vpnDataStore.edit { it[KEY_ROUTING_MODE] = mode.id }
    }

    suspend fun setSelectedNodeTag(tag: String?) {
        context.vpnDataStore.edit { prefs ->
            if (tag == null) prefs.remove(KEY_SELECTED_NODE_TAG) else prefs[KEY_SELECTED_NODE_TAG] = tag
        }
    }

    suspend fun setIncludeGmsDependencies(enabled: Boolean) {
        context.vpnDataStore.edit { it[KEY_INCLUDE_GMS_DEPS] = enabled }
    }

    suspend fun toggleWhitelistedPackage(packageName: String, enabled: Boolean) {
        context.vpnDataStore.edit { prefs ->
            val current = (prefs[KEY_WHITELIST_PACKAGES] ?: detectDefaultWhitelistWithBrowsers()).toMutableSet()
            if (enabled) current.add(packageName) else current.remove(packageName)
            prefs[KEY_WHITELIST_PACKAGES] = current
        }
    }

    suspend fun resetDefaultWhitelist() {
        context.vpnDataStore.edit { prefs ->
            prefs[KEY_WHITELIST_PACKAGES] = detectDefaultWhitelistWithBrowsers()
            prefs[KEY_INCLUDE_GMS_DEPS] = true
        }
    }

    suspend fun addSubscriptionSource(title: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val current = subscriptionSourcesFlow.first().toMutableList()
        if (current.any { it.url.equals(cleanUrl, ignoreCase = true) }) return

        val newSource = SubscriptionSource(
            id = "sub-${UUID.randomUUID().toString().take(8)}",
            title = title.ifBlank { Uri.parse(cleanUrl).host ?: "Подписка" },
            url = cleanUrl,
            formatHint = when {
                cleanUrl.endsWith(".json", ignoreCase = true) -> "sing-box JSON"
                cleanUrl.endsWith(".yaml", ignoreCase = true) || cleanUrl.endsWith(".yml", ignoreCase = true) -> "Clash YAML"
                else -> "Base64 / URI"
            },
            enabled = true
        )
        current.add(0, newSource)
        saveSubscriptionSources(current)
    }

    suspend fun removeSubscriptionSource(id: String) {
        val current = subscriptionSourcesFlow.first().filterNot { it.id == id }
        saveSubscriptionSources(current)
    }

    suspend fun importRawKeyOrBlock(rawInput: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = parseSubscriptionPayload(rawInput.trim(), sourceUrl = "manual-import")
            if (parsed.isEmpty()) {
                throw IllegalArgumentException("Не найдено валидных ссылок vless://, hy2://, trojan://, vmess://, ss:// или sing-box JSON")
            }
            val tested = probeAndRankNodes(parsed)
            val existing = nodesFlow.first()
            val merged = (tested + existing)
                .distinctBy { "${it.server.lowercase()}:${it.serverPort}:${it.protocol}" }
                .take(50)
            saveNodes(merged)
            tested.firstOrNull { it.isAlive }?.let { setSelectedNodeTag(it.tag) }
                ?: tested.firstOrNull()?.let { setSelectedNodeTag(it.tag) }
            tested.size
        }
    }

    /**
     * Реальное обновление и тестирование серверов без заглушек:
     * скачивает включённые подписки, парсит, фильтрует и измеряет реальный TCP RTT.
     */
    suspend fun syncAndTestAllSources(): Result<List<ProxyNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val sources = subscriptionSourcesFlow.first()
            val collected = mutableListOf<ProxyNode>()
            val updatedSources = mutableListOf<SubscriptionSource>()
            val errors = mutableListOf<String>()

            for (source in sources) {
                if (!source.enabled) {
                    updatedSources.add(source)
                    continue
                }
                val fetchedResult = fetchSingleSubscription(source.url)
                fetchedResult.fold(
                    onSuccess = { list ->
                        collected.addAll(list)
                        updatedSources.add(
                            source.copy(
                                lastNodeCount = list.size,
                                lastSyncTimeMs = System.currentTimeMillis()
                            )
                        )
                    },
                    onFailure = { err ->
                        errors.add("${source.title}: ${err.message}")
                        updatedSources.add(source.copy(lastSyncTimeMs = System.currentTimeMillis()))
                    }
                )
            }

            saveSubscriptionSources(updatedSources)

            // Сохраняем также ранее импортированные вручную ноды
            val manualNodes = nodesFlow.first().filter { it.sourceUrl == "manual-import" }
            val combined = (manualNodes + collected)
                .distinctBy { "${it.server.lowercase()}:${it.serverPort}:${it.protocol}" }

            if (combined.isEmpty()) {
                val reason = if (errors.isNotEmpty()) errors.joinToString("; ") else "Подписки не вернули валидных узлов"
                throw IllegalStateException("Не удалось загрузить серверы: $reason")
            }

            val prioritized = combined
                .sortedWith(
                    compareByDescending<ProxyNode> { GEMINI_SUPPORTED_COUNTRIES.contains(it.countryCode.uppercase()) }
                        .thenByDescending { it.protocol.obfuscationWeight }
                )
                .take(40)

            val tested = probeAndRankNodes(prioritized)
            saveNodes(tested)
            tested
        }
    }

    suspend fun testExistingNodesTcp(): List<ProxyNode> = withContext(Dispatchers.IO) {
        val current = nodesFlow.first()
        if (current.isEmpty()) return@withContext emptyList()
        val tested = probeAndRankNodes(current)
        saveNodes(tested)
        tested
    }

    suspend fun updateNodeDelaysFromCore(delaysMs: Map<String, Int>) {
        if (delaysMs.isEmpty()) return
        val current = nodesFlow.first()
        if (current.isEmpty()) return
        val updated = current.map { node ->
            val delay = delaysMs[node.tag]
            if (delay != null && delay > 0) {
                node.copy(latencyMs = delay, isAlive = true)
            } else {
                node
            }
        }
        saveNodes(updated)
    }

    private fun fetchSingleSubscription(url: String): Result<List<ProxyNode>> {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "sing-box/1.12.0 (Android; AegisBox)")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                parseSubscriptionPayload(body, url).take(25)
            }
        }
    }

    fun parseSubscriptionPayload(payload: String, sourceUrl: String): List<ProxyNode> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Check sing-box JSON format directly
        if (trimmed.startsWith("{")) {
            val jsonNodes = parseSingBoxJsonOutbounds(trimmed, sourceUrl)
            if (jsonNodes.isNotEmpty()) return jsonNodes
        }

        // 2. Decode Base64 if payload doesn't already contain URI schemes or Clash proxies
        val decodedText = if (!trimmed.contains("://") && !trimmed.contains("proxies:")) {
            tryDecodeBase64(trimmed) ?: trimmed
        } else {
            trimmed
        }

        if (decodedText.trim().startsWith("{")) {
            val jsonNodes = parseSingBoxJsonOutbounds(decodedText, sourceUrl)
            if (jsonNodes.isNotEmpty()) return jsonNodes
        }

        val results = mutableListOf<ProxyNode>()

        if (decodedText.contains("proxies:")) {
            results.addAll(parseClashYamlNodes(decodedText, sourceUrl))
        }

        decodedText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                parseSingleUriLine(line, sourceUrl)?.let { results.add(it) }
            }

        return results
    }

    private fun parseSingBoxJsonOutbounds(jsonStr: String, sourceUrl: String): List<ProxyNode> {
        return try {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("outbounds") ?: return emptyList()
            val list = mutableListOf<ProxyNode>()
            for (i in 0 until arr.length()) {
                val ob = arr.optJSONObject(i) ?: continue
                val type = ob.optString("type").lowercase()
                val server = ob.optString("server").ifBlank { continue }
                val port = ob.optInt("server_port", 443)
                val tag = ob.optString("tag").ifBlank { "$type-$i" }
                val (cc, cn) = detectCountryFromRemarkOrHost(tag, server)
                val tls = ob.optJSONObject("tls")
                val reality = tls?.optJSONObject("reality")
                val transport = ob.optJSONObject("transport")
                val proto = when (type) {
                    "vless" -> when {
                        reality?.optBoolean("enabled") == true -> ProxyProtocol.VLESS_REALITY
                        transport?.optString("type") == "grpc" -> ProxyProtocol.VLESS_GRPC
                        transport?.optString("type") == "ws" -> ProxyProtocol.VLESS_WS_TLS
                        transport?.optString("type") == "httpupgrade" -> ProxyProtocol.VLESS_XHTTP
                        else -> ProxyProtocol.VLESS_WS_TLS
                    }
                    "hysteria2" -> ProxyProtocol.HYSTERIA2
                    "trojan" -> ProxyProtocol.TROJAN_TLS
                    "vmess" -> ProxyProtocol.VMESS_TLS
                    "shadowsocks" -> ProxyProtocol.SHADOWSOCKS
                    else -> continue
                }
                val hash = abs("$server:$port:$type".hashCode()).toString(16).take(6)
                list.add(
                    ProxyNode(
                        id = "$type-$hash",
                        tag = "${cc.lowercase()}-$type-$hash",
                        name = cleanNodeName(tag, cc, proto),
                        protocol = proto,
                        server = server,
                        serverPort = port,
                        countryCode = cc,
                        countryName = cn,
                        uuidOrPassword = ob.optString("uuid").ifBlank { ob.optString("password") },
                        sni = tls?.optString("server_name").orEmpty().ifBlank { server },
                        publicKey = reality?.optString("public_key").orEmpty(),
                        shortId = reality?.optString("short_id").orEmpty(),
                        flow = ob.optString("flow"),
                        fingerprint = tls?.optJSONObject("utls")?.optString("fingerprint").orEmpty().ifBlank { "chrome" },
                        transportType = transport?.optString("type").orEmpty().ifBlank { "tcp" },
                        transportPath = transport?.optString("path").orEmpty().ifBlank { transport?.optString("service_name").orEmpty() },
                        tlsEnabled = tls?.optBoolean("enabled", true) ?: true,
                        method = ob.optString("method", "2022-blake3-aes-128-gcm"),
                        sourceUrl = sourceUrl
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseSingleUriLine(uriLine: String, sourceUrl: String): ProxyNode? {
        return try {
            when {
                uriLine.startsWith("vless://", ignoreCase = true) -> parseVlessUri(uriLine, sourceUrl)
                uriLine.startsWith("hy2://", ignoreCase = true) ||
                    uriLine.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2Uri(uriLine, sourceUrl)
                uriLine.startsWith("trojan://", ignoreCase = true) -> parseTrojanUri(uriLine, sourceUrl)
                uriLine.startsWith("vmess://", ignoreCase = true) -> parseVmessUri(uriLine, sourceUrl)
                uriLine.startsWith("ss://", ignoreCase = true) -> parseShadowsocksUri(uriLine, sourceUrl)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVlessUri(rawUri: String, sourceUrl: String): ProxyNode? {
        val uri = Uri.parse(rawUri)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val uuid = uri.userInfo ?: return null
        val remark = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "$host:$port"

        val security = uri.getQueryParameter("security")?.lowercase() ?: "none"
        val type = uri.getQueryParameter("type")?.lowercase() ?: "tcp"
        val sni = uri.getQueryParameter("sni")
            ?: uri.getQueryParameter("host")
            ?: host
        val pbk = uri.getQueryParameter("pbk").orEmpty()
        val sid = uri.getQueryParameter("sid").orEmpty()
        val flow = uri.getQueryParameter("flow").orEmpty()
        val fp = uri.getQueryParameter("fp") ?: "chrome"
        val path = uri.getQueryParameter("path")
            ?: uri.getQueryParameter("serviceName")
            ?: ""

        // Do not accept "reality" without an authentic public key (pbk) — sing-box rejects invalid Reality configs
        val protocol = when {
            (security == "reality" || pbk.isNotBlank()) && pbk.isNotBlank() -> ProxyProtocol.VLESS_REALITY
            type == "grpc" -> ProxyProtocol.VLESS_GRPC
            type == "xhttp" || type == "httpupgrade" || type == "splithttp" -> ProxyProtocol.VLESS_XHTTP
            else -> ProxyProtocol.VLESS_WS_TLS
        }

        val (countryCode, countryName) = detectCountryFromRemarkOrHost(remark, host)
        val shortHash = abs("$host:$port:$uuid".hashCode()).toString(16).take(6)

        return ProxyNode(
            id = "vless-$shortHash",
            tag = "${countryCode.lowercase()}-vless-$shortHash",
            name = cleanNodeName(remark, countryCode, protocol),
            protocol = protocol,
            server = host,
            serverPort = port,
            countryCode = countryCode,
            countryName = countryName,
            uuidOrPassword = uuid,
            sni = sni,
            publicKey = pbk,
            shortId = sid,
            flow = flow,
            fingerprint = fp,
            transportType = type,
            transportPath = path,
            tlsEnabled = security == "tls" || security == "reality" || port == 443,
            sourceUrl = sourceUrl,
            rawUri = rawUri
        )
    }

    private fun parseHysteria2Uri(rawUri: String, sourceUrl: String): ProxyNode? {
        val normalized = rawUri.replaceFirst("hy2://", "hysteria2://", ignoreCase = true)
        val uri = Uri.parse(normalized)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val password = uri.userInfo ?: return null
        val remark = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "$host:$port"
        val sni = uri.getQueryParameter("sni") ?: host
        val obfsPassword = uri.getQueryParameter("obfs-password").orEmpty()
        val (countryCode, countryName) = detectCountryFromRemarkOrHost(remark, host)
        val shortHash = abs("$host:$port".hashCode()).toString(16).take(6)

        return ProxyNode(
            id = "hy2-$shortHash",
            tag = "${countryCode.lowercase()}-hy2-$shortHash",
            name = cleanNodeName(remark, countryCode, ProxyProtocol.HYSTERIA2),
            protocol = ProxyProtocol.HYSTERIA2,
            server = host,
            serverPort = port,
            countryCode = countryCode,
            countryName = countryName,
            uuidOrPassword = password,
            sni = sni,
            obfsPassword = obfsPassword,
            sourceUrl = sourceUrl,
            rawUri = rawUri
        )
    }

    private fun parseTrojanUri(rawUri: String, sourceUrl: String): ProxyNode? {
        val uri = Uri.parse(rawUri)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val password = uri.userInfo ?: return null
        val remark = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "$host:$port"
        val sni = uri.getQueryParameter("sni") ?: host
        val (countryCode, countryName) = detectCountryFromRemarkOrHost(remark, host)
        val shortHash = abs("$host:$port".hashCode()).toString(16).take(6)

        return ProxyNode(
            id = "trojan-$shortHash",
            tag = "${countryCode.lowercase()}-trojan-$shortHash",
            name = cleanNodeName(remark, countryCode, ProxyProtocol.TROJAN_TLS),
            protocol = ProxyProtocol.TROJAN_TLS,
            server = host,
            serverPort = port,
            countryCode = countryCode,
            countryName = countryName,
            uuidOrPassword = password,
            sni = sni,
            sourceUrl = sourceUrl,
            rawUri = rawUri
        )
    }

    private fun parseVmessUri(rawUri: String, sourceUrl: String): ProxyNode? {
        val b64 = rawUri.removePrefix("vmess://").removePrefix("VMESS://").trim()
        val jsonStr = tryDecodeBase64(b64) ?: return null
        val obj = JSONObject(jsonStr)
        val host = obj.optString("add").ifEmpty { return null }
        val port = obj.opt("port")?.toString()?.toIntOrNull() ?: 443
        val uuid = obj.optString("id").ifEmpty { return null }
        val ps = obj.optString("ps", "$host:$port")
        val sni = obj.optString("sni").ifEmpty { obj.optString("host", host) }
        val net = obj.optString("net", "tcp")
        val path = obj.optString("path", "/")
        val tlsEnabled = obj.optString("tls").equals("tls", ignoreCase = true)
        val (countryCode, countryName) = detectCountryFromRemarkOrHost(ps, host)
        val shortHash = abs("$host:$port".hashCode()).toString(16).take(6)

        return ProxyNode(
            id = "vmess-$shortHash",
            tag = "${countryCode.lowercase()}-vmess-$shortHash",
            name = cleanNodeName(ps, countryCode, ProxyProtocol.VMESS_TLS),
            protocol = ProxyProtocol.VMESS_TLS,
            server = host,
            serverPort = port,
            countryCode = countryCode,
            countryName = countryName,
            uuidOrPassword = uuid,
            sni = sni,
            transportType = net,
            transportPath = path,
            tlsEnabled = tlsEnabled,
            sourceUrl = sourceUrl,
            rawUri = rawUri
        )
    }

    private fun parseShadowsocksUri(rawUri: String, sourceUrl: String): ProxyNode? {
        val withoutScheme = rawUri.substringAfter("://")
        val remark = if (withoutScheme.contains("#")) {
            URLDecoder.decode(withoutScheme.substringAfter("#"), "UTF-8")
        } else {
            "SS-Node"
        }
        val mainPart = withoutScheme.substringBefore("#").substringBefore("?")
        val atSplit = if (mainPart.contains("@")) {
            val userEncoded = mainPart.substringBefore("@")
            val hostPort = mainPart.substringAfter("@")
            val userDecoded = tryDecodeBase64(userEncoded) ?: userEncoded
            "$userDecoded@$hostPort"
        } else {
            tryDecodeBase64(mainPart) ?: return null
        }

        val methodAndPass = atSplit.substringBeforeLast("@")
        val hostAndPort = atSplit.substringAfterLast("@")
        val method = methodAndPass.substringBefore(":")
        val password = methodAndPass.substringAfter(":")
        val host = hostAndPort.substringBeforeLast(":")
        val port = hostAndPort.substringAfterLast(":").toIntOrNull() ?: 443
        if (host.isBlank() || method.isBlank()) return null

        val (countryCode, countryName) = detectCountryFromRemarkOrHost(remark, host)
        val shortHash = abs("$host:$port".hashCode()).toString(16).take(6)

        return ProxyNode(
            id = "ss-$shortHash",
            tag = "${countryCode.lowercase()}-ss-$shortHash",
            name = cleanNodeName(remark, countryCode, ProxyProtocol.SHADOWSOCKS),
            protocol = ProxyProtocol.SHADOWSOCKS,
            server = host,
            serverPort = port,
            countryCode = countryCode,
            countryName = countryName,
            uuidOrPassword = password,
            method = method,
            sourceUrl = sourceUrl,
            rawUri = rawUri
        )
    }

    private fun parseClashYamlNodes(yamlText: String, sourceUrl: String): List<ProxyNode> {
        val list = mutableListOf<ProxyNode>()
        val regex = Regex("""\{([^}]+)\}""")
        regex.findAll(yamlText).forEach { match ->
            val props = match.groupValues[1].split(",").associate { pair ->
                val k = pair.substringBefore(":").trim()
                val v = pair.substringAfter(":", "").trim().trim('"', '\'')
                k to v
            }
            val type = props["type"]?.lowercase() ?: return@forEach
            val server = props["server"] ?: return@forEach
            val port = props["port"]?.toIntOrNull() ?: 443
            val name = props["name"] ?: "$server:$port"
            val pbk = props["public-key"].orEmpty()
            val protocol = when (type) {
                "vless" -> if (pbk.isNotBlank()) ProxyProtocol.VLESS_REALITY else ProxyProtocol.VLESS_WS_TLS
                "hysteria2" -> ProxyProtocol.HYSTERIA2
                "trojan" -> ProxyProtocol.TROJAN_TLS
                "vmess" -> ProxyProtocol.VMESS_TLS
                "ss" -> ProxyProtocol.SHADOWSOCKS
                else -> return@forEach
            }
            val (countryCode, countryName) = detectCountryFromRemarkOrHost(name, server)
            val shortHash = abs("$server:$port".hashCode()).toString(16).take(6)
            list.add(
                ProxyNode(
                    id = "clash-$shortHash",
                    tag = "${countryCode.lowercase()}-clash-$shortHash",
                    name = cleanNodeName(name, countryCode, protocol),
                    protocol = protocol,
                    server = server,
                    serverPort = port,
                    countryCode = countryCode,
                    countryName = countryName,
                    uuidOrPassword = props["uuid"] ?: props["password"] ?: return@forEach,
                    sni = props["servername"] ?: props["sni"] ?: server,
                    publicKey = pbk,
                    shortId = props["short-id"].orEmpty(),
                    method = props["cipher"] ?: "2022-blake3-aes-128-gcm",
                    sourceUrl = sourceUrl
                )
            )
        }
        return list
    }

    /**
     * Реальный замер задержки (TCP handshake RTT) без фейковых значений.
     * Недоступные узлы помечаются latencyMs = -1 и isAlive = false.
     */
    private suspend fun probeAndRankNodes(nodes: List<ProxyNode>): List<ProxyNode> = coroutineScope {
        val semaphore = Semaphore(8)
        nodes.map { node ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val ping = measureTcpLatencyMs(node.server, node.serverPort)
                    node.copy(
                        latencyMs = ping,
                        isAlive = ping in 1..2500
                    )
                }
            }
        }.awaitAll().sortedWith(
            compareByDescending<ProxyNode> { it.isAlive }
                .thenBy { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE }
                .thenByDescending { it.protocol.obfuscationWeight }
        )
    }

    private fun measureTcpLatencyMs(host: String, port: Int): Int {
        return try {
            var elapsed = -1L
            Socket().use { socket ->
                elapsed = measureTimeMillis {
                    socket.connect(InetSocketAddress(host, port), 2200)
                }
            }
            elapsed.toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Реальная регистрация устройства в Cloudflare WARP API (`POST /v0a2158/reg`)
     * с генерацией настоящей пары ключей Curve25519 (X25519).
     * При ошибке сети возвращает Failure без создания фейковых токенов.
     */
    suspend fun registerCloudflareWarp(): Result<WarpConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val keyPair = X25519.generateKeyPair()

            val reqJson = JSONObject().apply {
                put("key", keyPair.publicKeyBase64)
                put("install_id", "")
                put("fcm_token", "")
                put("tos", "2026-11-12T00:00:00.000Z")
                put("model", Build.MODEL ?: "Android")
                put("serial_number", UUID.randomUUID().toString().replace("-", "").take(12))
                put("locale", "en_US")
            }

            val request = Request.Builder()
                .url("https://api.cloudflareclient.com/v0a2158/reg")
                .header("User-Agent", "okhttp/4.10.0")
                .header("CF-Client-Version", "a-6.35-2158")
                .header("Content-Type", "application/json; charset=UTF-8")
                .post(reqJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Cloudflare API HTTP ${resp.code}: ${bodyStr.take(120)}")
                }
                val json = JSONObject(bodyStr)
                val deviceId = json.optString("id")
                val token = json.optString("token")
                val configObj = json.getJSONObject("config")

                val ifaceObj = configObj.getJSONObject("interface").getJSONObject("addresses")
                val rawV4 = ifaceObj.getString("v4")
                val rawV6 = ifaceObj.optString("v6", "")
                val ipv4 = if (rawV4.contains("/")) rawV4 else "$rawV4/32"
                val ipv6 = if (rawV6.isBlank()) "" else if (rawV6.contains("/")) rawV6 else "$rawV6/128"

                val clientIdB64 = configObj.getString("client_id")
                val clientIdBytes = Base64.decode(clientIdB64, Base64.DEFAULT)
                if (clientIdBytes.size < 3) {
                    throw IllegalStateException("Некорректный client_id от Cloudflare WARP")
                }
                val reserved = listOf(
                    clientIdBytes[0].toInt() and 0xFF,
                    clientIdBytes[1].toInt() and 0xFF,
                    clientIdBytes[2].toInt() and 0xFF
                )

                val peersArr = configObj.getJSONArray("peers")
                val peerObj = peersArr.getJSONObject(0)
                val peerPubKey = peerObj.getString("public_key")
                val endpointObj = peerObj.optJSONObject("endpoint")
                val rawV4Endpoint = endpointObj?.optString("v4").orEmpty()
                    .ifBlank { endpointObj?.optString("host").orEmpty() }
                val endpointHost = rawV4Endpoint.substringBefore(":").ifBlank { "162.159.192.1" }
                val endpointPort = rawV4Endpoint.substringAfter(":", "2408").toIntOrNull() ?: 2408

                val newConfig = WarpConfig(
                    deviceId = deviceId,
                    accountToken = token,
                    privateKey = keyPair.privateKeyBase64,
                    publicKey = keyPair.publicKeyBase64,
                    peerPublicKey = peerPubKey,
                    endpointHost = endpointHost,
                    endpointPort = endpointPort,
                    localAddressIpv4 = ipv4,
                    localAddressIpv6 = ipv6,
                    reservedBytes = reserved,
                    mtu = 1280,
                    generatedAtEpochMs = System.currentTimeMillis()
                )
                saveWarpConfig(newConfig)
                newConfig
            }
        }
    }

    suspend fun loadAppsForSplitTunneling(): List<AppRoutingItem> = withContext(Dispatchers.IO) {
        val whitelisted = whitelistPackagesFlow.first()
        val pm = context.packageManager

        val installedPackagesMap = mutableMapOf<String, String>()
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
            for (info in resolveInfos) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                installedPackagesMap[pkg] = label
            }
        } catch (_: Exception) {
        }

        val targetCatalog = listOf(
            Triple("com.google.android.apps.bard", "Google Gemini", "Gemini & AI"),
            Triple("com.google.android.googlequicksearchbox", "Google (Поиск и Ассистент)", "Gemini & AI"),
            Triple("com.google.android.youtube", "YouTube", "Видео и Соцсети"),
            Triple("org.telegram.messenger", "Telegram", "Видео и Соцсети"),
            Triple("org.thunderdog.challegram", "Telegram X", "Видео и Соцсети"),
            Triple("com.android.chrome", "Google Chrome", "Браузеры"),
            Triple("org.mozilla.firefox", "Mozilla Firefox", "Браузеры"),
            Triple("com.brave.browser", "Brave Browser", "Браузеры"),
            Triple("com.opera.browser", "Opera Browser", "Браузеры"),
            Triple("com.microsoft.emmx", "Microsoft Edge", "Браузеры")
        )

        val result = mutableListOf<AppRoutingItem>()
        val seenPackages = mutableSetOf<String>()

        for ((pkg, title, category) in targetCatalog) {
            seenPackages.add(pkg)
            val isInstalled = installedPackagesMap.containsKey(pkg) || isPackageInstalled(pm, pkg)
            result.add(
                AppRoutingItem(
                    packageName = pkg,
                    appName = installedPackagesMap[pkg] ?: title,
                    category = category,
                    isWhitelisted = whitelisted.contains(pkg),
                    isInstalledOnDevice = isInstalled,
                    isRecommendedTarget = true
                )
            )
        }

        installedPackagesMap.entries
            .filterNot { seenPackages.contains(it.key) }
            .sortedBy { it.value.lowercase() }
            .forEach { (pkg, label) ->
                result.add(
                    AppRoutingItem(
                        packageName = pkg,
                        appName = label,
                        category = "Установленные",
                        isWhitelisted = whitelisted.contains(pkg),
                        isInstalledOnDevice = true,
                        isRecommendedTarget = false
                    )
                )
            }

        result
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun detectDefaultWhitelistWithBrowsers(): Set<String> {
        val set = DEFAULT_WHITELIST_PACKAGES.toMutableSet()
        try {
            val pm = context.packageManager
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val browsers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(browserIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(browserIntent, 0)
            }
            browsers.mapNotNull { it.activityInfo?.packageName }.forEach { pkg ->
                if (!pkg.contains("yandex", ignoreCase = true) && pkg != context.packageName) {
                    set.add(pkg)
                }
            }
        } catch (_: Exception) {
        }
        return set
    }

    private fun tryDecodeBase64(input: String): String? {
        return try {
            val sanitized = input.replace("\\s".toRegex(), "")
            val bytes = Base64.decode(sanitized, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                val bytes = Base64.decode(input.trim(), Base64.URL_SAFE or Base64.NO_PADDING)
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun detectCountryFromRemarkOrHost(remark: String, host: String): Pair<String, String> {
        val combined = "$remark $host".uppercase()
        return when {
            combined.contains("🇩🇪") || combined.contains("DE") || combined.contains("GERMANY") || combined.contains("FRANKFURT") ->
                "DE" to "Германия"
            combined.contains("🇳🇱") || combined.contains("NL") || combined.contains("NETHERLANDS") || combined.contains("AMSTERDAM") ->
                "NL" to "Нидерланды"
            combined.contains("🇫🇮") || combined.contains("FI") || combined.contains("FINLAND") || combined.contains("HELSINKI") ->
                "FI" to "Финляндия"
            combined.contains("🇸🇪") || combined.contains("SE") || combined.contains("SWEDEN") || combined.contains("STOCKHOLM") ->
                "SE" to "Швеция"
            combined.contains("🇬🇧") || combined.contains("UK") || combined.contains("GB") || combined.contains("LONDON") ->
                "GB" to "Великобритания"
            combined.contains("🇺🇸") || combined.contains("US") || combined.contains("USA") || combined.contains("ASHBURN") || combined.contains("DALLAS") ->
                "US" to "США"
            else -> "US" to "США / Глобал"
        }
    }

    private fun cleanNodeName(rawRemark: String, countryCode: String, protocol: ProxyProtocol): String {
        val cleaned = rawRemark
            .replace(Regex("[^\\p{L}\\p{N}\\s•\\-_.]"), "")
            .trim()
            .take(30)
        return if (cleaned.length >= 3) cleaned else "$countryCode • ${protocol.displayName}"
    }

    private suspend fun saveSubscriptionSources(sources: List<SubscriptionSource>) {
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("url", s.url)
                    put("formatHint", s.formatHint)
                    put("enabled", s.enabled)
                    put("lastNodeCount", s.lastNodeCount)
                    put("lastSyncTimeMs", s.lastSyncTimeMs)
                }
            )
        }
        context.vpnDataStore.edit { it[KEY_SUBSCRIPTION_SOURCES] = arr.toString() }
    }

    private fun parseSourcesJson(json: String): List<SubscriptionSource> {
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        SubscriptionSource(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            url = o.optString("url"),
                            formatHint = o.optString("formatHint", "Base64 / URI"),
                            enabled = o.optBoolean("enabled", true),
                            lastNodeCount = o.optInt("lastNodeCount", 0),
                            lastSyncTimeMs = o.optLong("lastSyncTimeMs", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun saveNodes(nodes: List<ProxyNode>) {
        val arr = JSONArray()
        nodes.forEach { n ->
            arr.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("tag", n.tag)
                    put("name", n.name)
                    put("protocol", n.protocol.name)
                    put("server", n.server)
                    put("serverPort", n.serverPort)
                    put("countryCode", n.countryCode)
                    put("countryName", n.countryName)
                    put("uuidOrPassword", n.uuidOrPassword)
                    put("sni", n.sni)
                    put("publicKey", n.publicKey)
                    put("shortId", n.shortId)
                    put("flow", n.flow)
                    put("fingerprint", n.fingerprint)
                    put("transportType", n.transportType)
                    put("transportPath", n.transportPath)
                    put("tlsEnabled", n.tlsEnabled)
                    put("method", n.method)
                    put("obfsPassword", n.obfsPassword)
                    put("latencyMs", n.latencyMs)
                    put("isAlive", n.isAlive)
                    put("sourceUrl", n.sourceUrl)
                }
            )
        }
        context.vpnDataStore.edit { it[KEY_CACHED_NODES] = arr.toString() }
    }

    private fun parseNodesJson(json: String): List<ProxyNode> {
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val proto = runCatching {
                        ProxyProtocol.valueOf(o.optString("protocol", "VLESS_REALITY"))
                    }.getOrDefault(ProxyProtocol.VLESS_REALITY)
                    add(
                        ProxyNode(
                            id = o.optString("id"),
                            tag = o.optString("tag"),
                            name = o.optString("name"),
                            protocol = proto,
                            server = o.optString("server"),
                            serverPort = o.optInt("serverPort", 443),
                            countryCode = o.optString("countryCode", "US"),
                            countryName = o.optString("countryName", "США"),
                            uuidOrPassword = o.optString("uuidOrPassword"),
                            sni = o.optString("sni"),
                            publicKey = o.optString("publicKey"),
                            shortId = o.optString("shortId"),
                            flow = o.optString("flow"),
                            fingerprint = o.optString("fingerprint", "chrome"),
                            transportType = o.optString("transportType", "tcp"),
                            transportPath = o.optString("transportPath"),
                            tlsEnabled = o.optBoolean("tlsEnabled", true),
                            method = o.optString("method", "2022-blake3-aes-128-gcm"),
                            obfsPassword = o.optString("obfsPassword"),
                            latencyMs = o.optInt("latencyMs", -1),
                            isAlive = o.optBoolean("isAlive", false),
                            sourceUrl = o.optString("sourceUrl", "cached")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun saveWarpConfig(config: WarpConfig) {
        val obj = JSONObject().apply {
            put("deviceId", config.deviceId)
            put("accountToken", config.accountToken)
            put("privateKey", config.privateKey)
            put("publicKey", config.publicKey)
            put("peerPublicKey", config.peerPublicKey)
            put("endpointHost", config.endpointHost)
            put("endpointPort", config.endpointPort)
            put("localAddressIpv4", config.localAddressIpv4)
            put("localAddressIpv6", config.localAddressIpv6)
            put("reservedBytes", JSONArray(config.reservedBytes))
            put("mtu", config.mtu)
            put("generatedAtEpochMs", config.generatedAtEpochMs)
        }
        context.vpnDataStore.edit { it[KEY_WARP_CONFIG] = obj.toString() }
    }

    private fun parseWarpJson(json: String): WarpConfig? {
        return try {
            val o = JSONObject(json)
            val resArr = o.optJSONArray("reservedBytes")
            val reserved = if (resArr != null && resArr.length() == 3) {
                listOf(resArr.getInt(0), resArr.getInt(1), resArr.getInt(2))
            } else {
                emptyList()
            }
            WarpConfig(
                deviceId = o.optString("deviceId"),
                accountToken = o.optString("accountToken"),
                privateKey = o.optString("privateKey"),
                publicKey = o.optString("publicKey"),
                peerPublicKey = o.optString("peerPublicKey", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="),
                endpointHost = o.optString("endpointHost", "162.159.192.1"),
                endpointPort = o.optInt("endpointPort", 2408),
                localAddressIpv4 = o.optString("localAddressIpv4"),
                localAddressIpv6 = o.optString("localAddressIpv6"),
                reservedBytes = reserved,
                mtu = o.optInt("mtu", 1280),
                generatedAtEpochMs = o.optLong("generatedAtEpochMs", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }
}
