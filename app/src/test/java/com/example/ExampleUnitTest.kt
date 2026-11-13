package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.FreeServersRepository
import com.example.model.ProxyNode
import com.example.model.ProxyProtocol
import com.example.model.RoutingMode
import com.example.model.WarpConfig
import com.example.vpn.SingBoxConfigBuilder
import com.example.vpn.X25519
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @Test
    fun x25519_matchesRfc7748BasePointVector() {
        // RFC 7748 Section 6.1 test scalar (Alice private key):
        // 77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a
        val alicePriv = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val expectedPub = hexToBytes("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")

        val actualPub = X25519.publicFromPrivate(alicePriv)
        assertArrayEquals(expectedPub, actualPub)

        val pair = X25519.generateKeyPair()
        assertEquals(32, Base64.decode(pair.privateKeyBase64, Base64.NO_WRAP).size)
        assertEquals(32, Base64.decode(pair.publicKeyBase64, Base64.NO_WRAP).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun singBoxConfigBuilder_throwsWhenNodesEmpty() {
        SingBoxConfigBuilder.buildConfig(
            nodes = emptyList(),
            warpConfig = null,
            routingMode = RoutingMode.VLESS_WARP_CHAIN
        )
    }

    @Test
    fun singBoxConfigBuilder_generatesValidSingBox112SchemaWithWarpEndpoint() {
        val node = ProxyNode(
            id = "vless-123456",
            tag = "de-vless-123456",
            name = "DE • VLESS Reality",
            protocol = ProxyProtocol.VLESS_REALITY,
            server = "203.0.113.10",
            serverPort = 443,
            countryCode = "DE",
            countryName = "Германия",
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            sni = "www.microsoft.com",
            publicKey = "test_reality_public_key",
            shortId = "ab12",
            flow = "xtls-rprx-vision",
            latencyMs = 45,
            isAlive = true
        )
        val warp = WarpConfig(
            deviceId = "dev-1",
            accountToken = "tok-1",
            privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            publicKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            endpointHost = "162.159.192.1",
            endpointPort = 2408,
            localAddressIpv4 = "172.16.0.2/32",
            reservedBytes = listOf(12, 34, 56),
            mtu = 1280
        )

        val rawJson = SingBoxConfigBuilder.buildConfig(
            nodes = listOf(node),
            warpConfig = warp,
            routingMode = RoutingMode.VLESS_WARP_CHAIN,
            selectedNodeTag = node.tag
        )

        assertFalse("Config must not contain unsupported amnezia fields", rawJson.contains("amnezia"))
        val root = JSONObject(rawJson)

        // Endpoints check (WireGuard WARP must be in root endpoints[] with detour)
        val endpoints = root.getJSONArray("endpoints")
        assertEquals(1, endpoints.length())
        val warpEp = endpoints.getJSONObject(0)
        assertEquals("wireguard", warpEp.getString("type"))
        assertEquals("warp-exit", warpEp.getString("tag"))
        assertFalse(warpEp.getBoolean("system"))
        assertEquals(1280, warpEp.getInt("mtu"))
        assertEquals("vless-reality-entry", warpEp.getString("detour"))

        // Inbounds TUN check
        val tun = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertEquals("172.19.0.1/30", tun.getJSONArray("address").getString(0))

        // DNS check
        val dns = root.getJSONObject("dns")
        assertEquals("ipv4_only", dns.getString("strategy"))
        val dnsServers = dns.getJSONArray("servers")
        val dnsTypes = (0 until dnsServers.length()).map { dnsServers.getJSONObject(it).getString("type") }
        assertTrue(dnsTypes.containsAll(listOf("https", "udp", "local", "fakeip")))

        // Route actions & loop prevention check
        val route = root.getJSONObject("route")
        assertEquals("dns-direct", route.getString("default_domain_resolver"))
        val rules = route.getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertEquals("hijack-dns", rules.getJSONObject(1).getString("action"))

        val hasServerBypass = (0 until rules.length()).any { idx ->
            val r = rules.getJSONObject(idx)
            if (r.optString("outbound") != "direct") return@any false
            val cidrs = r.optJSONArray("ip_cidr") ?: return@any false
            (0 until cidrs.length()).any { cIdx -> cidrs.optString(cIdx) == "203.0.113.10/32" }
        }
        assertTrue("Proxy server IP must be routed direct to prevent TUN loop", hasServerBypass)
    }

    @Test
    fun repository_parsesUriBase64AndSingBoxJson() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = FreeServersRepository(context)

        val vlessUri = "vless://11111111-2222-3333-4444-555555555555@198.51.100.25:443" +
            "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.google.com" +
            "&fp=chrome&pbk=RealPublicKey12345&sid=a1b2&type=tcp#DE-Frankfurt"
        val hy2Uri = "hy2://pass123@198.51.100.30:8443?sni=example.com#NL-Amsterdam"
        val badRealityUri = "vless://11111111-2222-3333-4444-555555555555@198.51.100.99:443?security=reality#NoPbk"

        val payload = listOf(vlessUri, hy2Uri, badRealityUri).joinToString("\n")
        val b64Payload = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val parsed = repo.parseSubscriptionPayload(b64Payload, "test-sub")
        assertEquals(3, parsed.size)

        val first = parsed[0]
        assertEquals(ProxyProtocol.VLESS_REALITY, first.protocol)
        assertEquals("DE", first.countryCode)
        assertEquals("RealPublicKey12345", first.publicKey)

        val second = parsed[1]
        assertEquals(ProxyProtocol.HYSTERIA2, second.protocol)
        assertEquals("NL", second.countryCode)

        // Reality URI without pbk must NOT be classified as VLESS_REALITY
        val third = parsed[2]
        assertFalse(third.protocol == ProxyProtocol.VLESS_REALITY)

        // Check sing-box JSON parsing
        val sbJson = """
            {
              "outbounds": [
                {
                  "type": "trojan",
                  "tag": "FI-Helsinki",
                  "server": "198.51.100.40",
                  "server_port": 443,
                  "password": "secret",
                  "tls": { "enabled": true, "server_name": "cdn.example.com" }
                }
              ]
            }
        """.trimIndent()
        val jsonParsed = repo.parseSubscriptionPayload(sbJson, "json-sub")
        assertEquals(1, jsonParsed.size)
        assertNotNull(jsonParsed.firstOrNull())
        assertEquals(ProxyProtocol.TROJAN_TLS, jsonParsed.first().protocol)
        assertEquals("FI", jsonParsed.first().countryCode)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return out
    }
}
