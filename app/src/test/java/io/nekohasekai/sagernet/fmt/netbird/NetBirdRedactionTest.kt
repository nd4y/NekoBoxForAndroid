package io.nekohasekai.sagernet.fmt.netbird

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBirdRedactionTest {
    @Test fun redactsNativeAndBeanCredentialsAtEveryDepth() {
        val input = """{"outbounds":[{"setup_key":"secret-one","hostname":"phone"}],"map":{"bean":{"setupKey":"secret-two"}}}"""
        val output = input.redactNetBirdSecrets()
        assertFalse(output.contains("secret-one"))
        assertFalse(output.contains("secret-two"))
        assertTrue(output.contains("phone"))
        assertTrue(output.contains("[redacted]"))
    }

    @Test fun malformedDiagnosticsAreSuppressedInsteadOfLeakingCredentials() {
        val output = "invalid setup_key=secret-value".redactNetBirdSecrets()
        assertFalse(output.contains("secret-value"))
        assertTrue(output.contains("omitted"))
    }
}
