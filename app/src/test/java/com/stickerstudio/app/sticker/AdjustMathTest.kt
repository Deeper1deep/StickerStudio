package com.stickerstudio.app.sticker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustMathTest {

    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        m[0] * r + m[1] * g + m[2] * b + m[4],
        m[5] * r + m[6] * g + m[7] * b + m[9],
        m[10] * r + m[11] * g + m[12] * b + m[14],
    )

    @Test
    fun defaultAdjustmentsChangeNothing() {
        assertNull(AdjustMath.matrix(Adjust()))
        assertTrue(Adjust().isDefault)
    }

    @Test
    fun grayscaleMakesChannelsEqual() {
        val m = AdjustMath.matrix(Adjust(grayscale = true))!!
        val out = apply(m, 200f, 40f, 90f)
        assertEquals(out[0], out[1], 0.5f)
        assertEquals(out[1], out[2], 0.5f)
    }

    @Test
    fun brightnessAddsAndContrastKeepsMidGrey() {
        val b = AdjustMath.matrix(Adjust(brightness = 0.2f))!!
        assertEquals(100f + 51f, apply(b, 100f, 100f, 100f)[0], 0.5f)
        val c = AdjustMath.matrix(Adjust(contrast = 0.5f))!!
        assertEquals(128f, apply(c, 128f, 128f, 128f)[0], 0.5f)
        assertTrue(apply(c, 200f, 200f, 200f)[0] > 200f)
    }

    @Test
    fun warmthShiftsRedUpAndBlueDown() {
        val m = AdjustMath.matrix(Adjust(warmth = 1f))!!
        val out = apply(m, 100f, 100f, 100f)
        assertTrue(out[0] > 100f)
        assertTrue(out[2] < 100f)
        assertEquals(100f, out[1], 0.01f)
    }

    @Test
    fun mulAppliesRightMatrixFirst() {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        assertArrayEquals(id, AdjustMath.mul(id, id), 0f)
    }

    @Test
    fun blurDivisorGrowsWithBlur() {
        assertEquals(1f, AdjustMath.blurDivisor(Adjust()), 0f)
        assertTrue(AdjustMath.blurDivisor(Adjust(blur = 1f)) > AdjustMath.blurDivisor(Adjust(blur = 0.3f)))
    }

    @Test
    fun newFieldsSurviveJsonRoundTripAndOldProjectsStillOpen() {
        val p = StickerProject(
            layers = listOf(
                Layer(
                    id = "a", kind = LayerKind.TEXT, text = "مرحبا", name = "Title", visible = false, locked = true,
                    opacity = 0.5f, align = 2, letterSpacing = 0.2f, lineSpacing = 1.5f, bgEnabled = true,
                )
            ),
            adjust = Adjust(brightness = 0.3f, blur = 0.4f, sepia = true),
        )
        val back = ProjectJson.decodeProject(ProjectJson.encode(p))!!
        assertEquals(p, back)

        val old = ProjectJson.decodeProject("""{"v":1,"layers":[{"id":"x","kind":"TEXT","text":"hi"}]}""")!!
        val l = old.layers.single()
        assertTrue(l.visible)
        assertFalse(l.locked)
        assertEquals(1f, l.opacity, 0f)
        assertTrue(old.adjust.isDefault)
    }
}
