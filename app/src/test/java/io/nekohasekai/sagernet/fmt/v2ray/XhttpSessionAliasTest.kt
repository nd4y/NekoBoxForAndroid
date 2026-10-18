package io.nekohasekai.sagernet.fmt.v2ray

import moe.matsuri.nb4a.SingBoxOptions.V2RayTransportOptions_XHTTPOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class XhttpSessionAliasTest {
    private fun bean() =
        VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            xhttpMode = "packet-up"
            xhttpSeqPlacement = "query"
            xhttpExtra = """{"sessionIDPlacement":"cookie","sessionIDKey":"sid"}"""
        }

    @Test
    fun subscriptionAliasesRetainSessionPlacementWhenSequenceIsInQuery() {
        val b = bean()
        val options = buildSingBoxOutboundStreamSettings(b) as V2RayTransportOptions_XHTTPOptions
        assertEquals("cookie", options.session_placement)
        assertEquals("sid", options.session_key)
        assertEquals("query", options.seq_placement)
        assertEquals("""{"sessionIDPlacement":"cookie","sessionIDKey":"sid"}""", b.xhttpExtra)
    }

    @Test
    fun canonicalAndExplicitGuiFieldsTakePrecedenceOverAliases() {
        val b =
            bean().apply {
                xhttpExtra =
                    """{"sessionIDPlacement":"cookie","session_placement":"query","sessionIDKey":"sid"}"""
            }
        var options = buildSingBoxOutboundStreamSettings(b) as V2RayTransportOptions_XHTTPOptions
        assertEquals("query", options.session_placement)
        b.xhttpSessionPlacement = "header"
        b.xhttpSessionKey = "X-Session"
        options = buildSingBoxOutboundStreamSettings(b) as V2RayTransportOptions_XHTTPOptions
        assertEquals("header", options.session_placement)
        assertEquals("X-Session", options.session_key)
    }
}
