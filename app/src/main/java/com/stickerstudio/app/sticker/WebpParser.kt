package com.stickerstudio.app.sticker

/** What we need to know about a WebP file to validate it against WhatsApp's rules. */
data class WebpInfo(
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val hasAlpha: Boolean,
    val frameCount: Int,
    val totalDurationMs: Long,
    val minFrameDurationMs: Int,
)

/**
 * Minimal RIFF/WebP header reader (pure Kotlin, no Android classes), so the real output
 * files can be checked on-device and in plain JVM unit tests.
 */
object WebpParser {

    fun parse(b: ByteArray): WebpInfo? {
        if (b.size < 20) return null
        if (!tag(b, 0, "RIFF") || !tag(b, 8, "WEBP")) return null

        var pos = 12
        var width = 0
        var height = 0
        var gotDims = false
        var animated = false
        var alpha = false
        var frames = 0
        var total = 0L
        var minDuration = Int.MAX_VALUE

        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.ISO_8859_1)
            val size = le32(b, pos + 4)
            val ds = pos + 8
            if (size < 0 || ds.toLong() + size > b.size) return null

            when (id) {
                "VP8X" -> {
                    if (size < 10) return null
                    val flags = b[ds].toInt() and 0xFF
                    animated = (flags and 0x02) != 0
                    if ((flags and 0x10) != 0) alpha = true
                    width = 1 + le24(b, ds + 4)
                    height = 1 + le24(b, ds + 7)
                    gotDims = true
                }
                "ALPH" -> alpha = true
                "VP8 " -> if (!gotDims) {
                    if (size < 10) return null
                    // Frame tag (3 bytes) + start code 9D 01 2A.
                    if ((b[ds + 3].toInt() and 0xFF) != 0x9D ||
                        (b[ds + 4].toInt() and 0xFF) != 0x01 ||
                        (b[ds + 5].toInt() and 0xFF) != 0x2A
                    ) return null
                    width = le16(b, ds + 6) and 0x3FFF
                    height = le16(b, ds + 8) and 0x3FFF
                    gotDims = true
                }
                "VP8L" -> if (!gotDims) {
                    if (size < 5 || (b[ds].toInt() and 0xFF) != 0x2F) return null
                    val b1 = b[ds + 1].toInt() and 0xFF
                    val b2 = b[ds + 2].toInt() and 0xFF
                    val b3 = b[ds + 3].toInt() and 0xFF
                    val b4 = b[ds + 4].toInt() and 0xFF
                    width = 1 + (b1 or ((b2 and 0x3F) shl 8))
                    height = 1 + ((b2 shr 6) or (b3 shl 2) or ((b4 and 0x0F) shl 10))
                    if (((b4 shr 4) and 1) == 1) alpha = true
                    gotDims = true
                }
                "ANMF" -> {
                    frames++
                    if (size >= 16) {
                        val d = le24(b, ds + 12)
                        total += d
                        if (d < minDuration) minDuration = d
                    }
                }
            }
            pos = ds + size + (size and 1)
        }

        if (!gotDims || width <= 0 || height <= 0) return null
        return WebpInfo(
            width = width,
            height = height,
            animated = animated,
            hasAlpha = alpha,
            frameCount = if (animated) frames else 1,
            totalDurationMs = total,
            minFrameDurationMs = if (minDuration == Int.MAX_VALUE) 0 else minDuration,
        )
    }

    private fun tag(b: ByteArray, off: Int, t: String): Boolean {
        for (i in t.indices) if (b[off + i].toInt() != t[i].code) return false
        return true
    }

    internal fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    internal fun le24(b: ByteArray, o: Int): Int =
        le16(b, o) or ((b[o + 2].toInt() and 0xFF) shl 16)

    internal fun le32(b: ByteArray, o: Int): Int =
        le24(b, o) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
