package io.nekohasekai.sagernet.control

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Bounded incremental reads; detect truncation/rotation rather than silently losing a new log. */
internal object DiagnosticTail {
    data class Result(
        val text: String,
        val cursor: String,
        val reset: Boolean,
        val truncated: Boolean,
    )

    private fun readAvailable(input: RandomAccessFile, limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val n = input.read(bytes, count, limit - count)
            if (n <= 0) break
            count += n
        }
        return bytes.copyOf(count)
    }

    fun read(file: File, cursor: String?, maxBytes: Int): Result {
        require(maxBytes in 256..262144)
        if (!file.exists()) return Result("", "", cursor != null, false)
        return RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            val head = readAvailable(input, minOf(size, 64).toInt())
            val generation =
                MessageDigest.getInstance("SHA-256").digest(head).take(8).joinToString("") {
                    "%02x".format(it)
                }
            val parts = cursor?.split(":")
            val offset = parts?.getOrNull(1)?.toLongOrNull()
            val valid =
                parts?.size == 2 && parts[0] == generation && offset != null && offset in 0..size
            val start = if (valid) offset!! else maxOf(0, size - maxBytes)
            input.seek(start)
            val bytes = readAvailable(input, minOf(size - start, maxBytes.toLong()).toInt())
            var end = bytes.size
            if (end > 0) {
                var lead = end - 1
                while (lead > 0 && bytes[lead].toInt() and 0xc0 == 0x80) lead--
                val first = bytes[lead].toInt() and 0xff
                val width =
                    when {
                        first >= 0xf0 -> 4
                        first >= 0xe0 -> 3
                        first >= 0xc0 -> 2
                        else -> 1
                    }
                if (lead + width > end) end = lead
            }
            // A tail may start inside a UTF-8 character. Continuations cannot begin a new code
            // point.
            var skip = 0
            if (!valid) while (skip < bytes.size && bytes[skip].toInt() and 0xc0 == 0x80) skip++
            Result(
                String(bytes, minOf(skip, end), maxOf(0, end - skip), Charsets.UTF_8),
                "$generation:${start + end}",
                cursor != null && !valid,
                start > 0 && !valid || start + end < size,
            )
        }
    }
}
