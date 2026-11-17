package com.stickerstudio.app.sticker

import kotlin.math.pow

/**
 * Builds the 4x5 colour matrix (android.graphics.ColorMatrix layout) for [Adjust].
 * Pure Kotlin, so it is covered by unit tests.
 */
object AdjustMath {

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Result of applying [b] first and then [a]. */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(20)
        for (r in 0 until 4) {
            for (c in 0 until 5) {
                var s = 0f
                for (k in 0 until 4) s += a[r * 5 + k] * b[k * 5 + c]
                if (c == 4) s += a[r * 5 + 4]
                o[r * 5 + c] = s
            }
        }
        return o
    }

    private fun scaleRgb(r: Float, g: Float, b: Float, shift: Float = 0f) = floatArrayOf(
        r, 0f, 0f, 0f, shift,
        0f, g, 0f, 0f, shift,
        0f, 0f, b, 0f, shift,
        0f, 0f, 0f, 1f, 0f,
    )

    private fun saturation(s: Float): FloatArray {
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        val inv = 1f - s
        return floatArrayOf(
            lr * inv + s, lg * inv, lb * inv, 0f, 0f,
            lr * inv, lg * inv + s, lb * inv, 0f, 0f,
            lr * inv, lg * inv, lb * inv + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private val SEPIA = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Null when nothing needs to be applied. */
    fun matrix(a: Adjust): FloatArray? {
        if (a.brightness == 0f && a.contrast == 0f && a.saturation == 0f && a.warmth == 0f &&
            a.exposure == 0f && !a.grayscale && !a.sepia
        ) return null

        var m = IDENTITY
        if (a.exposure != 0f) {
            val f = 2f.pow(a.exposure.coerceIn(-1f, 1f))
            m = mul(scaleRgb(f, f, f), m)
        }
        if (a.brightness != 0f) m = mul(scaleRgb(1f, 1f, 1f, a.brightness.coerceIn(-1f, 1f) * 255f), m)
        if (a.contrast != 0f) {
            val c = 1f + a.contrast.coerceIn(-1f, 1f)
            m = mul(scaleRgb(c, c, c, 128f * (1f - c)), m)
        }
        if (a.saturation != 0f) m = mul(saturation(1f + a.saturation.coerceIn(-1f, 1f)), m)
        if (a.warmth != 0f) {
            val w = a.warmth.coerceIn(-1f, 1f)
            m = mul(scaleRgb(1f + 0.25f * w, 1f, 1f - 0.25f * w), m)
        }
        if (a.grayscale) m = mul(saturation(0f), m)
        if (a.sepia) m = mul(SEPIA, m)
        return m
    }

    /** Blur radius factor used when rendering: 1 = none. */
    fun blurDivisor(a: Adjust): Float = 1f + a.blur.coerceIn(0f, 1f) * 14f
}
