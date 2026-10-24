package com.stickerstudio.app.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CropMathTest {

    private fun ratio(c: CropRect, w: Int, h: Int) = ((c.r - c.l) * w) / ((c.b - c.t) * h)

    @Test fun squareAspectInsideLandscapeImage() {
        val c = CropMath.withAspect(480, 270, 1f)
        assertEquals(1f, ratio(c, 480, 270), 0.001f)
        assertEquals(0f, c.t, 0f); assertEquals(1f, c.b, 0f)
        assertEquals((c.l + c.r) / 2f, 0.5f, 0.001f)
    }

    @Test fun portraitAspectInsideLandscapeImage() {
        val c = CropMath.withAspect(480, 270, 9f / 16f)
        assertEquals(9f / 16f, ratio(c, 480, 270), 0.001f)
    }

    @Test fun originalAspectIsFullImage() {
        val c = CropMath.withAspect(480, 270, 480f / 270f)
        assertTrue(c.isFull)
    }

    @Test fun freeDragMovesOnlyThatEdge() {
        val c = CropMath.drag(CropRect(), CropMath.Handle.R, -0.3f, 0.5f, 100, 100, null)
        assertEquals(0.7f, c.r, 0.001f); assertEquals(0f, c.l, 0f); assertEquals(1f, c.b, 0f)
    }

    @Test fun dragCannotLeaveImageOrCollapse() {
        val c = CropMath.drag(CropRect(0.2f, 0.2f, 0.8f, 0.8f), CropMath.Handle.TL, 5f, 5f, 100, 100, null)
        assertTrue(c.r - c.l >= CropMath.MIN_SIZE - 1e-6f)
        val d = CropMath.drag(CropRect(0.2f, 0.2f, 0.8f, 0.8f), CropMath.Handle.BR, 5f, 5f, 100, 100, null)
        assertEquals(1f, d.r, 0f); assertEquals(1f, d.b, 0f)
    }

    @Test fun lockedAspectIsKeptOnCornerDrag() {
        val start = CropMath.withAspect(480, 270, 1f)
        val c = CropMath.drag(start, CropMath.Handle.BR, -0.1f, 0.3f, 480, 270, 1f)
        assertEquals(1f, ratio(c, 480, 270), 0.01f)
        assertTrue(c.l >= 0f && c.t >= 0f && c.r <= 1f && c.b <= 1f)
    }

    @Test fun lockedAspectIsKeptOnEdgeDrag() {
        val start = CropRect(0.25f, 0.25f, 0.75f, 0.75f)
        val c = CropMath.drag(start, CropMath.Handle.T, 0f, -0.1f, 100, 100, 1f)
        assertEquals(1f, ratio(c, 100, 100), 0.01f)
    }

    @Test fun moveKeepsSizeAndStaysInside() {
        val c = CropMath.drag(CropRect(0.1f, 0.1f, 0.5f, 0.5f), CropMath.Handle.MOVE, 2f, -2f, 100, 100, null)
        assertEquals(0.4f, c.r - c.l, 0.001f)
        assertEquals(1f, c.r, 0.001f); assertEquals(0f, c.t, 0.001f)
    }

    @Test fun pixelsAreInsideBitmap() {
        val px = CropMath.pixels(CropRect(0f, 0f, 1f, 1f), 480, 270)
        assertEquals(480, px[2]); assertEquals(270, px[3])
        val q = CropMath.pixels(CropRect(0.999f, 0.999f, 1f, 1f), 10, 10)
        assertTrue(q[0] + q[2] <= 10 && q[1] + q[3] <= 10 && q[2] >= 1 && q[3] >= 1)
    }

    @Test fun cropSurvivesJsonRoundTripAndOldJsonOpens() {
        val p = StickerProject(crop = CropRect(0.1f, 0.2f, 0.9f, 0.7f))
        assertEquals(p.crop, ProjectJson.decodeProject(ProjectJson.encode(p))!!.crop)
        val a = AnimatedSettings(crop = CropRect(0.25f, 0f, 0.75f, 1f))
        assertEquals(a.crop, ProjectJson.decodeAnimated(ProjectJson.encode(a))!!.crop)
        assertTrue(ProjectJson.decodeProject("{\"v\":1}")!!.crop.isFull)
        assertTrue(abs(1f - 1f) < 1e-6f)
    }
}
