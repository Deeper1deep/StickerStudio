package com.stickerstudio.app.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 helpers used to verify downloaded update files. */
object Sha256 {

    fun hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    fun hex(file: File): String = file.inputStream().use { hex(it) }

    /** Case-insensitive comparison of two hex digests. */
    fun matches(actualHex: String, expectedHex: String): Boolean =
        actualHex.trim().equals(expectedHex.trim(), ignoreCase = true)

    fun isValidHex(value: String): Boolean = value.length == 64 && value.all {
        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
