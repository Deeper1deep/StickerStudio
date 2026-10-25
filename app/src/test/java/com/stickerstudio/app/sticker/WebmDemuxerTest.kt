package com.stickerstudio.app.sticker

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebmDemuxerTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun el(id: ByteArray, payload: ByteArray, unknownSize: Boolean = false): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(id)
        if (unknownSize) o.write(bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
        else {
            require(payload.size < 127)
            o.write(0x80 or payload.size)
        }
        o.write(payload)
        return o.toByteArray()
    }

    private fun cat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        parts.forEach { o.write(it) }
        return o.toByteArray()
    }

    private fun sample(): ByteArray {
        val track = el(
            bytes(0xAE),
            cat(
                el(bytes(0xD7), bytes(1)), el(bytes(0x83), bytes(1)), el(bytes(0x86), "V_VP9".toByteArray()),
                el(bytes(0xE0), cat(el(bytes(0xB0), bytes(64)), el(bytes(0xBA), bytes(48)))),
            ),
        )
        val simple = el(bytes(0xA3), cat(bytes(0x81, 0x00, 0x00, 0x80), bytes(1, 2, 3)))
        val group = el(
            bytes(0xA0),
            cat(
                el(bytes(0xA1), cat(bytes(0x81, 0x00, 0x21, 0x00), bytes(4, 5))),
                el(bytes(0x75, 0xA1), el(bytes(0xA6), cat(el(bytes(0xEE), bytes(1)), el(bytes(0xA5), bytes(9, 8, 7))))),
            ),
        )
        return cat(
            el(bytes(0x1A, 0x45, 0xDF, 0xA3), ByteArray(0)),
            el(
                bytes(0x18, 0x53, 0x80, 0x67),
                cat(
                    el(bytes(0x16, 0x54, 0xAE, 0x6B), track),
                    el(bytes(0x1F, 0x43, 0xB6, 0x75), cat(el(bytes(0xE7), bytes(100)), simple, group), unknownSize = true),
                ),
                unknownSize = true,
            ),
        )
    }

    @Test fun readsFramesTimesAndAlphaFromUnknownSizeContainers() {
        val t = WebmDemuxer.parse(sample())
        assertNotNull(t)
        t!!
        assertEquals("V_VP9", t.codec)
        assertEquals(64, t.width)
        assertEquals(48, t.height)
        assertEquals(2, t.frames.size)
        assertEquals(100L, t.frames[0].ptsMs)
        assertEquals(133L, t.frames[1].ptsMs)
        assertArrayEquals(bytes(1, 2, 3), t.frames[0].data)
        assertArrayEquals(bytes(4, 5), t.frames[1].data)
        assertNull(t.frames[0].alpha)
        assertArrayEquals(bytes(9, 8, 7), t.frames[1].alpha)
        assertFalse(t.hasAlpha)
        assertEquals(166L, t.durationMs)
    }

    @Test fun rejectsNonWebm() {
        assertNull(WebmDemuxer.parse(bytes(1, 2, 3, 4, 5)))
        assertFalse(WebmDemuxer.isWebm(bytes(0, 0, 0, 0x18)))
        assertTrue(WebmDemuxer.isWebm(bytes(0x1A, 0x45, 0xDF, 0xA3)))
    }

    @Test fun readsColourFromVp9KeyFrame() {
        val a = Vp9Header.color(bytes(0x82, 0x49, 0x83, 0x42, 0x50))!!
        assertTrue(a.bt709); assertTrue(a.fullRange)
        val b = Vp9Header.color(bytes(0x82, 0x49, 0x83, 0x42, 0x20))!!
        assertFalse(b.bt709); assertFalse(b.fullRange)
        assertNull(Vp9Header.color(bytes(0x86, 0x49, 0x83, 0x42, 0x50)))   // not a key frame
    }

    @Test fun convertsYuvToArgbWithAlpha() {
        val gray = YuvConvert.toArgb(
            2, 2, ByteArray(4) { 128.toByte() }, ByteArray(1) { 128.toByte() }, ByteArray(1) { 128.toByte() },
            ByteArray(4) { 77 }, Vp9Color(bt709 = true, fullRange = true),
        )
        assertEquals(0x4D808080, gray[0])
        val white = YuvConvert.toArgb(
            2, 2, ByteArray(4) { 235.toByte() }, ByteArray(1) { 128.toByte() }, ByteArray(1) { 128.toByte() },
            null, Vp9Color(bt709 = true, fullRange = false),
        )
        assertEquals(0xFFFFFFFF.toInt(), white[3])
    }
}
