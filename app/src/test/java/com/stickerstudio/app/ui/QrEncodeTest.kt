package com.stickerstudio.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncodeTest {
    @Test
    fun encodesTheDownloadLinkWithHighErrorCorrection() {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 1)
        val m = QRCodeWriter().encode(
            "https://github.com/Deeper1deep/StickerStudio-Updates/releases/latest", BarcodeFormat.QR_CODE, 768, 768, hints,
        )
        assertEquals(768, m.width)
        var dark = 0
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) dark++
        assertTrue(dark > 1000)
    }
}
