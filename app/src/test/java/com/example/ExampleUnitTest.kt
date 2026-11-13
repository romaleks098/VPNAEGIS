package com.example

import com.example.model.RoutingMode
import com.example.model.WarpConfig
import com.example.vpn.SingBoxConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleUnitTest {
    @Test
    fun singBoxConfigBuilder_generatesValidCascadeAndFakeIpConfig() {
        val nodes = SingBoxConfigBuilder.defaultFallbackNodes()
        val warp = WarpConfig(
            deviceId = "test-warp-id",
            accountToken = "test-token",
            privateKey = "dGVzdC1wcml2YXRlLWtleS0zMi1ieXRlcy1iYXNlNjQ="
        )
        val jsonStr = SingBoxConfigBuilder.buildConfig(
            nodes = nodes,
            warpConfig = warp,
            routingMode = RoutingMode.VLESS_WARP_CHAIN,
            selectedNodeTag = nodes.first().tag
        )

        val root = JSONObject(jsonStr)
        val dns = root.getJSONObject("dns")
        assertEquals("ipv4_only", dns.getString("strategy"))
        assertEquals("198.18.0.0/15", dns.getJSONObject("fakeip").getString("inet4_range"))

        val inbounds = root.getJSONArray("inbounds")
        val tun = inbounds.getJSONObject(0)
        assertEquals("172.19.0.1/30", tun.getString("inet4_address"))
        assertEquals("ipv4_only", tun.getString("domain_strategy"))

        val outbounds = root.getJSONArray("outbounds")
        var foundWarpDetour = false
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.getJSONObject(i)
            if (ob.optString("tag") == "warp-exit") {
                assertEquals("vless-reality-entry", ob.optString("detour"))
                foundWarpDetour = true
            }
        }
        assertTrue("warp-exit must detour via vless-reality-entry", foundWarpDetour)
    }
}
