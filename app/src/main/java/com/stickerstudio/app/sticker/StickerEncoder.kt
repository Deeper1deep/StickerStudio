package com.stickerstudio.app.sticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import com.stickerstudio.app.core.WhatsAppLimits
import java.io.ByteArrayOutputStream

/** Encodes sticker bitmaps into the file formats WhatsApp expects. */
object StickerEncoder {

    /** Result of tray icon creation: file bytes and the matching file extension. */
    class Tray(val bytes: ByteArray, val extension: String)

    @Suppress("DEPRECATION")
    fun lossyFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    /** One lossy WebP frame (alpha is preserved). */
    fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        bitmap.compress(lossyFormat(), quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }

    /**
     * Static 512x512 sticker, at most 100 KB: lossy WebP with decreasing quality, then
     * lossless as a last resort (API 30+). Returns null if the image cannot fit the limit.
     */
    fun encodeStatic(bitmap: Bitmap): ByteArray? {
        require(bitmap.width == WhatsAppLimits.STICKER_SIZE && bitmap.height == WhatsAppLimits.STICKER_SIZE) {
            "Sticker bitmap must be 512x512"
        }
        var quality = 95
        while (quality >= 20) {
            val bytes = compress(bitmap, quality)
            if (bytes.size <= WhatsAppLimits.MAX_STATIC_BYTES) return bytes
            quality -= 10
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val out = ByteArrayOutputStream(64 * 1024)
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            val bytes = out.toByteArray()
            if (bytes.size <= WhatsAppLimits.MAX_STATIC_BYTES) return bytes
        }
        return null
    }

    /** 96x96 tray icon (PNG, or WebP if PNG would exceed 50 KB). */
    fun createTray(source: Bitmap): Tray {
        val size = WhatsAppLimits.TRAY_SIZE
        val tray = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tray)
        val scale = minOf(size.toFloat() / source.width, size.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dst = android.graphics.RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
        canvas.drawBitmap(source, null, dst, paint)

        val png = ByteArrayOutputStream()
        tray.compress(Bitmap.CompressFormat.PNG, 100, png)
        val result = if (png.size() <= WhatsAppLimits.MAX_TRAY_BYTES) {
            Tray(png.toByteArray(), "png")
        } else {
            Tray(compress(tray, 80), "webp")
        }
        tray.recycle()
        return result
    }
}
