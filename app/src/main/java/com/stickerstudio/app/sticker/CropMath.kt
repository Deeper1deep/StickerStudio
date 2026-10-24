package com.stickerstudio.app.sticker

import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry for the crop tool (no Android classes, so it is unit-tested on the JVM).
 * Rectangles are fractions of the source image; [imgW]/[imgH] are the source pixel size,
 * needed to keep an aspect ratio correct on non-square pictures.
 */
object CropMath {

    enum class Handle { TL, T, TR, L, R, BL, B, BR, MOVE }

    const val MIN_SIZE = 0.06f

    /** Largest centred rectangle with width/height == [aspect] (in pixels) inside the image. */
    fun withAspect(imgW: Int, imgH: Int, aspect: Float): CropRect {
        val imageAspect = imgW.toFloat() / imgH
        return if (imageAspect >= aspect) {
            val w = aspect / imageAspect
            CropRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        } else {
            val h = imageAspect / aspect
            CropRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        }
    }

    /** Moves one handle by ([dx], [dy]) (fractions of the image). [aspect] = locked width/height or null. */
    fun drag(c: CropRect, h: Handle, dx: Float, dy: Float, imgW: Int, imgH: Int, aspect: Float?): CropRect {
        if (h == Handle.MOVE) {
            val w = c.r - c.l
            val ht = c.b - c.t
            val nl = (c.l + dx).coerceIn(0f, 1f - w)
            val nt = (c.t + dy).coerceIn(0f, 1f - ht)
            return CropRect(nl, nt, nl + w, nt + ht)
        }
        var l = c.l
        var t = c.t
        var r = c.r
        var b = c.b
        val left = h == Handle.TL || h == Handle.L || h == Handle.BL
        val right = h == Handle.TR || h == Handle.R || h == Handle.BR
        val top = h == Handle.TL || h == Handle.T || h == Handle.TR
        val bottom = h == Handle.BL || h == Handle.B || h == Handle.BR
        if (left) l = (l + dx).coerceIn(0f, r - MIN_SIZE)
        if (right) r = (r + dx).coerceIn(l + MIN_SIZE, 1f)
        if (top) t = (t + dy).coerceIn(0f, b - MIN_SIZE)
        if (bottom) b = (b + dy).coerceIn(t + MIN_SIZE, 1f)
        if (aspect == null) return CropRect(l, t, r, b)

        // Aspect locked: width drives the height for left/right/corner handles, height drives the width for top/bottom.
        val byWidth = left || right
        var w = r - l
        var ht = b - t
        if (byWidth) ht = w * imgW / (aspect * imgH) else w = ht * aspect * imgH / imgW
        val cx = (c.l + c.r) / 2f
        val cy = (c.t + c.b) / 2f

        // Which side stays fixed.
        val fixedX = when { left -> r; right -> l; else -> Float.NaN }   // NaN = centred
        val fixedY = when { top -> b; bottom -> t; else -> Float.NaN }

        val maxW = when { left -> fixedX; right -> 1f - fixedX; else -> 2f * min(cx, 1f - cx) }
        val maxH = when { top -> fixedY; bottom -> 1f - fixedY; else -> 2f * min(cy, 1f - cy) }
        val s = min(1f, min(maxW / w, maxH / ht))
        w *= s
        ht *= s
        w = max(w, 0.001f)
        ht = max(ht, 0.001f)

        val nl = when { left -> fixedX - w; right -> fixedX; else -> cx - w / 2f }
        val nt = when { top -> fixedY - ht; bottom -> fixedY; else -> cy - ht / 2f }
        return CropRect(nl, nt, nl + w, nt + ht)
    }

    /** Pixel rectangle (left, top, width, height) of [c] inside an image of the given size. */
    fun pixels(c: CropRect, imgW: Int, imgH: Int): IntArray {
        val l = (c.l * imgW).toInt().coerceIn(0, imgW - 1)
        val t = (c.t * imgH).toInt().coerceIn(0, imgH - 1)
        val r = (c.r * imgW).toInt().coerceIn(l + 1, imgW)
        val b = (c.b * imgH).toInt().coerceIn(t + 1, imgH)
        return intArrayOf(l, t, r - l, b - t)
    }
}
