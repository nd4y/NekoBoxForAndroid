package io.nekohasekai.sagernet.control

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticTailTest {
    @Test
    fun cursorReadsOnlyNewBytesAndResetsAfterRotation() {
        val f = Files.createTempFile("neko-log", ".txt").toFile()
        try {
            f.writeText("x".repeat(80))
            val first = DiagnosticTail.read(f, null, 256)
            f.appendText("Привет 日本語\n")
            val next = DiagnosticTail.read(f, first.cursor, 256)
            assertEquals("Привет 日本語\n", next.text)
            assertFalse(next.reset)
            assertEquals("", DiagnosticTail.read(f, next.cursor, 256).text)
            f.writeText("new generation")
            val rotated = DiagnosticTail.read(f, next.cursor, 256)
            assertTrue(rotated.reset)
            assertEquals("new generation", rotated.text)
        } finally {
            f.delete()
        }
    }

    @Test
    fun readsAreBoundedAndMissingFilesAreValidEmptySources() {
        val f = Files.createTempFile("neko-log", ".txt").toFile()
        try {
            f.writeText("x".repeat(4096))
            val tail = DiagnosticTail.read(f, null, 256)
            assertEquals(256, tail.text.length)
            assertTrue(tail.truncated)
            f.delete()
            assertEquals("", DiagnosticTail.read(f, null, 256).text)
        } finally {
            f.delete()
        }
    }

    @Test
    fun redactionPreservesFramesAndRemovesKnownAndStructuredCredentials() {
        val raw =
            "password=abc123 token: xyz789 https://user:pass@example.test/path?k=secret\n" +
                "Authorization: Bearer opaque-auth\nknown-secret-value\n at example.Test.call(Test.kt:42)"
        val text = DiagnosticRedaction.redact(raw, listOf("known-secret-value"))
        for (secret in
            listOf(
                "abc123",
                "xyz789",
                "user:pass",
                "opaque-auth",
                "known-secret-value",
            )) assertFalse(text.contains(secret))
        assertTrue(text.contains("Test.kt:42"))
    }

    @Test
    fun cursorsNeverSplitUtf8AcrossChunks() {
        val f = Files.createTempFile("neko-utf8", ".txt").toFile()
        try {
            val text = "日本語".repeat(150)
            f.writeText(text)
            val tail = DiagnosticTail.read(f, null, 256)
            var cursor = tail.cursor.substringBefore(':') + ":0"
            val result = StringBuilder()
            repeat(20) {
                val chunk = DiagnosticTail.read(f, cursor, 256)
                result.append(chunk.text)
                cursor = chunk.cursor
            }
            assertEquals(text, result.toString())
        } finally {
            f.delete()
        }
    }

    @Test
    fun eventRedactionPreservesNumbersAndJsonSyntax() {
        val text = """{"timeMs":1791234567890,"pid":12345,"fields":{"reason":"1234"}}"""
        val safe = DiagnosticRedaction.redactEventLines(text, listOf("1234"))
        assertTrue(DiagnosticRedaction.redactEventLines(text + "\n", listOf("1234")).endsWith("\n"))
        val row = ControlJson.parseObject(safe)
        assertEquals(1791234567890L, row["timeMs"].asLong)
        assertEquals(12345, row["pid"].asInt)
        assertEquals(ControlJson.REDACTED, row["fields"].asJsonObject["reason"].asString)
    }
}
