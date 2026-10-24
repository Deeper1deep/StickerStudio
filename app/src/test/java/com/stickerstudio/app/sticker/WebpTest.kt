package com.stickerstudio.app.sticker

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Builds structurally valid (but not pixel-decodable) WebP files so the container logic can
 * be tested on a plain JVM. Pixel encoding itself is covered by the instrumented tests.
 */
class WebpTest {

    // ---- helpers to build RIFF/WebP bytes ----------------------------------

    private fun le(v: Int, bytes: Int): ByteArray = ByteArray(bytes) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun chunk(id: String, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(id.toByteArray(Charsets.ISO_8859_1))
        o.write(le(data.size, 4))
        o.write(data)
        if (data.size % 2 == 1) o.write(0)
        return o.toByteArray()
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        chunks.forEach { body.write(it) }
        val o = ByteArrayOutputStream()
        o.write("RIFF".toByteArray())
        o.write(le(body.size(), 4))
        o.write(body.toByteArray())
        return o.toByteArray()
    }

    private fun vp8Payload(w: Int, h: Int, extra: Int = 20): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x10, 0x02, 0x00))                 // frame tag
        o.write(byteArrayOf(0x9D.toByte(), 0x01, 0x2A))        // start code
        o.write(le(w, 2)); o.write(le(h, 2))
        o.write(ByteArray(extra) { 7 })
        return o.toByteArray()
    }

    private fun staticLossy(w: Int = 512, h: Int = 512) = riff(chunk("VP8 ", vp8Payload(w, h)))

    private fun staticLossyWithAlpha(w: Int = 512, h: Int = 512): ByteArray {
        val vp8x = chunk("VP8X", byteArrayOf(0x10, 0, 0, 0) + le(w - 1, 3) + le(h - 1, 3))
        return riff(vp8x, chunk("ALPH", ByteArray(9) { 1 }), chunk("VP8 ", vp8Payload(w, h, 21)))
    }

    private fun lossless(w: Int, h: Int, alpha: Boolean): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14) or ((if (alpha) 1 else 0) shl 28)
        return riff(chunk("VP8L", byteArrayOf(0x2F) + le(bits, 4) + ByteArray(10)))
    }

    // ---- parser --------------------------------------------------------------

    @Test
    fun parsesLossyDimensions() {
        val info = WebpParser.parse(staticLossy(512, 384))!!
        assertEquals(512, info.width)
        assertEquals(384, info.height)
        assertFalse(info.animated)
        assertFalse(info.hasAlpha)
        assertEquals(1, info.frameCount)
    }

    @Test
    fun parsesExtendedFormatWithAlpha() {
        val info = WebpParser.parse(staticLossyWithAlpha())!!
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertTrue(info.hasAlpha)
        assertFalse(info.animated)
    }

    @Test
    fun parsesLosslessDimensionsAndAlphaBit() {
        val info = WebpParser.parse(lossless(512, 512, alpha = true))!!
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertTrue(info.hasAlpha)
        assertFalse(WebpParser.parse(lossless(100, 37, alpha = false))!!.hasAlpha)
        assertEquals(37, WebpParser.parse(lossless(100, 37, alpha = false))!!.height)
    }

    @Test
    fun rejectsNonWebpAndTruncatedData() {
        assertNull(WebpParser.parse(ByteArray(0)))
        assertNull(WebpParser.parse("this is definitely not a webp file".toByteArray()))
        val good = staticLossy()
        assertNull(WebpParser.parse(good.copyOf(good.size - 15)))   // chunk runs past the end
        val badMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        assertNull(WebpParser.parse(badMagic))
    }

    // ---- muxer -----------------------------------------------------------------

    @Test
    fun muxProducesValidAnimatedContainer() {
        val frames = listOf(
            AnimatedWebpMuxer.Frame(staticLossyWithAlpha(), 80),
            AnimatedWebpMuxer.Frame(staticLossyWithAlpha(), 90),
            AnimatedWebpMuxer.Frame(staticLossyWithAlpha(), 100),
        )
        val bytes = AnimatedWebpMuxer.mux(frames)
        val info = WebpParser.parse(bytes)
        assertNotNull(info)
        info!!
        assertTrue(info.animated)
        assertTrue(info.hasAlpha)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertEquals(3, info.frameCount)
        assertEquals(270L, info.totalDurationMs)
        assertEquals(80, info.minFrameDurationMs)

        // RIFF size field must describe the whole file, and the file must be even-sized.
        assertEquals(bytes.size - 8, WebpParser.le32(bytes, 4))
        assertEquals(0, bytes.size % 2)
    }

    @Test
    fun muxKeepsOddSizedFramePayloadsAligned() {
        // VP8 payload of odd length => padded chunk inside ANMF.
        val odd = riff(chunk("VP8 ", vp8Payload(512, 512, extra = 21)))
        val bytes = AnimatedWebpMuxer.mux(listOf(AnimatedWebpMuxer.Frame(odd, 50), AnimatedWebpMuxer.Frame(odd, 50)))
        val info = WebpParser.parse(bytes)!!
        assertEquals(2, info.frameCount)
        assertEquals(0, bytes.size % 2)
        assertFalse(info.hasAlpha)
    }

    @Test
    fun muxRejectsEmptyAndInvalidFrames() {
        try {
            AnimatedWebpMuxer.mux(emptyList())
            fail("expected failure")
        } catch (e: IllegalArgumentException) { /* ok */ }
        try {
            AnimatedWebpMuxer.mux(listOf(AnimatedWebpMuxer.Frame(ByteArray(40), 50)))
            fail("expected failure")
        } catch (e: IllegalArgumentException) { /* ok */ }
    }

    @Test
    fun muxCarriesAlphaChunkIntoEachFrame() {
        val bytes = AnimatedWebpMuxer.mux(listOf(AnimatedWebpMuxer.Frame(staticLossyWithAlpha(), 60)))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("ANIM"))
        assertTrue(text.contains("ANMF"))
        assertTrue(text.contains("ALPH"))
        assertTrue(text.contains("VP8 "))
    }
}
