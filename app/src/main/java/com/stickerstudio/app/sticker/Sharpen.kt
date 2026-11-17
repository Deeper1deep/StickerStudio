package com.stickerstudio.app.sticker

/**
 * Unsharp-mask style sharpening on ARGB pixels (pure Kotlin, unit tested).
 * Each colour channel becomes `c + amount * (c - blur3x3(c))`; alpha is never touched, so
 * transparent edges stay clean.
 */
object Sharpen {

    /** Strength of the mask for a 0..1 slider value. */
    fun strength(amount: Float): Float = amount.coerceIn(0f, 1f) * 2.5f

    fun apply(pixels: IntArray, width: Int, height: Int, amount: Float): IntArray {
        val k = strength(amount)
        if (k <= 0f || width < 3 || height < 3 || pixels.size < width * height) return pixels
        val out = pixels.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val a = pixels[i] ushr 24
                if (a == 0) continue
                out[i] = (a shl 24) or
                    (channel(pixels, i, width, 16, k) shl 16) or
                    (channel(pixels, i, width, 8, k) shl 8) or
                    channel(pixels, i, width, 0, k)
            }
        }
        return out
    }

    private fun channel(p: IntArray, i: Int, w: Int, shift: Int, k: Float): Int {
        val c = (p[i] shr shift) and 0xFF
        var sum = 0
        for (dy in -1..1) for (dx in -1..1) sum += (p[i + dy * w + dx] shr shift) and 0xFF
        val blur = sum / 9f
        return (c + k * (c - blur)).toInt().coerceIn(0, 255)
    }
}
