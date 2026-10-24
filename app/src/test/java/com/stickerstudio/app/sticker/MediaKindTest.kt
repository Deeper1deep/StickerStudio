package com.stickerstudio.app.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKindTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun webmHeaderWinsOverWrongMime() {
        val h = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01, 0x00)
        assertEquals(MediaKind.WEBM, MediaKind.detect(h, "application/octet-stream", "sticker"))
        assertTrue(MediaKind.WEBM.isVideo)
        assertEquals("webm", MediaKind.WEBM.extension)
    }

    @Test fun mp4AndGifHeaders() {
        assertEquals(MediaKind.VIDEO, MediaKind.detect(bytes(0, 0, 0, 0x18, 'f'.code, 't'.code, 'y'.code, 'p'.code), null, null))
        assertEquals(MediaKind.GIF, MediaKind.detect("GIF89a".toByteArray(), "image/png", "x.png"))
    }

    @Test fun fallsBackToMimeThenName() {
        assertEquals(MediaKind.WEBM, MediaKind.detect(null, "video/webm", null))
        assertEquals(MediaKind.VIDEO, MediaKind.detect(bytes(1, 2, 3), "application/octet-stream", "clip.MOV"))
        assertEquals(MediaKind.WEBM, MediaKind.detect(null, null, "a.webm"))
    }

    @Test fun unknownIsImage() {
        assertEquals(MediaKind.IMAGE, MediaKind.detect(bytes(1, 2, 3), null, null))
        assertEquals(false, MediaKind.IMAGE.isAnimatedSource)
    }
}
