package com.stickerstudio.app.sticker

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.stickerstudio.app.core.WhatsAppLimits
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns a [StickerProject] + source bitmap into the final transparent sticker bitmap.
 *
 * The same function produces the editor preview (smaller [outSize]) and the exported
 * 512x512 sticker, so what the user sees is what gets exported.
 *
 * All project coordinates live in a 512x512 space; [k] scales them to [outSize].
 */
object StickerRenderer {

    private const val SPACE = 512f
    private val FONT_FAMILIES = arrayOf("sans-serif", "serif", "monospace", "cursive")

    fun render(
        project: StickerProject,
        source: Bitmap?,
        outSize: Int = WhatsAppLimits.STICKER_SIZE,
    ): Bitmap {
        val k = outSize / SPACE
        val content = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(content)

        if (source != null && !source.isRecycled) {
            val base = renderBase(project, source, outSize, k)
            canvas.drawBitmap(base, 0f, 0f, null)
            base.recycle()
        }

        for (stroke in project.pen) drawPen(canvas, stroke, k)
        val bounds = RectF(0f, 0f, outSize.toFloat(), outSize.toFloat())
        for (layer in project.layers) {
            if (!layer.visible) continue
            val faded = layer.opacity < 0.999f
            if (faded) canvas.saveLayerAlpha(bounds, (layer.opacity.coerceIn(0f, 1f) * 255f).roundToInt())
            when (layer.kind) {
                LayerKind.TEXT -> drawText(canvas, layer, k)
                LayerKind.SHAPE -> drawShape(canvas, layer, k)
            }
            if (faded) canvas.restore()
        }

        return compose(content, project, outSize, k)
    }

    /** Scale that makes [source] cover the whole canvas, relative to the default "fit". */
    fun coverScale(width: Int, height: Int): Float {
        val a = max(width, height).toFloat()
        val b = min(width, height).toFloat()
        return if (b <= 0f) 1f else a / b
    }

    // ------------------------------------------------------------------ base image

    private fun renderBase(p: StickerProject, full: Bitmap, size: Int, k: Float): Bitmap {
        // Crop first: everything after this (fit, rotate, erase...) works on the cropped picture,
        // so the result keeps the crop's own aspect ratio and is centred on the 512x512 canvas.
        val cropped = !p.crop.isFull
        val src = if (cropped) {
            val px = CropMath.pixels(p.crop, full.width, full.height)
            Bitmap.createBitmap(full, px[0], px[1], px[2], px[3])
        } else {
            full
        }
        try {
            return renderBaseCropped(p, src, size, k)
        } finally {
            if (cropped && src !== full) src.recycle()
        }
    }

    private fun renderBaseCropped(p: StickerProject, src: Bitmap, size: Int, k: Float): Bitmap {
        var layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)

        val fit = min(SPACE / src.width, SPACE / src.height)
        val x = p.xform
        val m = Matrix()
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postScale(
            fit * x.scale * (if (x.flipX) -1f else 1f),
            fit * x.scale * (if (x.flipY) -1f else 1f),
        )
        m.postRotate(x.rotation)
        m.postTranslate(SPACE / 2f + x.dx, SPACE / 2f + x.dy)
        m.postScale(k, k)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.alpha = (p.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        AdjustMath.matrix(p.adjust)?.let { paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(it)) }
        c.drawBitmap(src, m, paint)

        if (p.erase.isNotEmpty()) applyErase(layer, c, p.erase, k)

        // Blur is applied to the picture itself (after erasing, before the mask), at render time only.
        var maskCanvas = c
        if (p.adjust.blur > 0.01f) {
            val blurred = softBlur(layer, AdjustMath.blurDivisor(p.adjust))
            layer.recycle()
            layer = blurred
            maskCanvas = Canvas(layer)
        }

        if (p.mask != MaskKind.NONE) {
            val s = size.toFloat()
            val path = Path()
            if (p.mask == MaskKind.CIRCLE) {
                path.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW)
            } else {
                path.addRoundRect(RectF(0f, 0f, s, s), s * 0.18f, s * 0.18f, Path.Direction.CW)
            }
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            maskCanvas.drawPath(path, maskPaint)
        }
        return layer
    }

    /** Cheap, smooth blur: shrink by [divisor] then scale back up with filtering. */
    private fun softBlur(src: Bitmap, divisor: Float): Bitmap {
        val w = max(1, (src.width / divisor).roundToInt())
        val h = max(1, (src.height / divisor).roundToInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(small, null, RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        if (small !== src) small.recycle()
        return out
    }

    /** Eraser clears pixels; "restore" paints the original (pre-erase) pixels back. */
    private fun applyErase(layer: Bitmap, c: Canvas, strokes: List<EraseStroke>, k: Float) {
        val original = layer.copy(Bitmap.Config.ARGB_8888, false)
        val shader = BitmapShader(original, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        val eraser = Paint(Paint.ANTI_ALIAS_FLAG)
        eraser.style = Paint.Style.STROKE
        eraser.strokeCap = Paint.Cap.ROUND
        eraser.strokeJoin = Paint.Join.ROUND
        eraser.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

        val restorer = Paint(Paint.ANTI_ALIAS_FLAG)
        restorer.style = Paint.Style.STROKE
        restorer.strokeCap = Paint.Cap.ROUND
        restorer.strokeJoin = Paint.Join.ROUND
        restorer.shader = shader

        for (s in strokes) {
            val paint = if (s.restore) restorer else eraser
            paint.strokeWidth = max(1f, s.radius * 2f * k)
            drawStroke(c, s.points, k, paint)
        }
        original.recycle()
    }

    // ------------------------------------------------------------------ pen / text / shapes

    private fun drawStroke(c: Canvas, pts: List<Float>, k: Float, paint: Paint) {
        if (pts.size < 2) return
        if (pts.size < 4) {
            c.drawPoint(pts[0] * k, pts[1] * k, paint)
            return
        }
        val path = Path()
        path.moveTo(pts[0] * k, pts[1] * k)
        var i = 2
        while (i + 1 < pts.size) {
            path.lineTo(pts[i] * k, pts[i + 1] * k)
            i += 2
        }
        c.drawPath(path, paint)
    }

    private fun drawPen(c: Canvas, s: PenStroke, k: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = s.color
        paint.strokeWidth = max(1f, s.width * k)
        drawStroke(c, s.points, k, paint)
    }

    private fun hasArabic(text: String): Boolean = text.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F || it.code in 0xFB50..0xFEFF }

    private fun drawText(c: Canvas, l: Layer, k: Float) {
        if (l.text.isBlank()) return
        val lines = l.text.split('\n')
        val style = when {
            l.bold && l.italic -> Typeface.BOLD_ITALIC
            l.bold -> Typeface.BOLD
            l.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val typeface = Typeface.create(FONT_FAMILIES[l.font.coerceIn(0, FONT_FAMILIES.size - 1)], style)
        // Letter spacing would break the joining of Arabic letters, so it only applies to other scripts.
        val spacing = if (hasArabic(l.text)) 0f else l.letterSpacing
        val align = when (l.align) {
            0 -> Paint.Align.LEFT
            2 -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.typeface = typeface
        fill.textSize = l.size
        fill.color = l.color
        fill.textAlign = align
        fill.letterSpacing = spacing

        var outline: Paint? = null
        if (l.strokeWidth > 0f) {
            outline = Paint(Paint.ANTI_ALIAS_FLAG)
            outline.typeface = typeface
            outline.textSize = l.size
            outline.textAlign = align
            outline.letterSpacing = spacing
            outline.style = Paint.Style.STROKE
            outline.strokeJoin = Paint.Join.ROUND
            outline.strokeWidth = l.strokeWidth * 2f
            outline.color = l.strokeColor
        }
        if (l.shadow) {
            val target = outline ?: fill
            target.setShadowLayer(l.size * 0.08f, l.size * 0.04f, l.size * 0.04f, 0xAA000000.toInt())
        }

        c.save()
        c.scale(k, k)
        c.translate(l.x, l.y)
        c.rotate(l.rotation)
        c.scale(l.scale, l.scale)

        val fm = fill.fontMetrics
        val lineHeight = l.size * l.lineSpacing.coerceIn(0.6f, 3f)
        val baseline = -(fm.ascent + fm.descent) / 2f
        val widest = lines.maxOf { fill.measureText(it) }
        // x where each line starts, depending on alignment (the block itself stays centred on the layer)
        val anchorX = when (l.align) {
            0 -> -widest / 2f
            2 -> widest / 2f
            else -> 0f
        }

        if (l.bgEnabled) {
            val pad = l.size * 0.25f
            val halfH = lines.size * lineHeight / 2f
            val bg = Paint(Paint.ANTI_ALIAS_FLAG)
            bg.color = l.bgColor
            c.drawRoundRect(
                RectF(-widest / 2f - pad, -halfH - pad * 0.3f, widest / 2f + pad, halfH + pad * 0.3f),
                pad, pad, bg,
            )
        }
        lines.forEachIndexed { i, line ->
            val y = (i - (lines.size - 1) / 2f) * lineHeight + baseline
            if (outline != null) c.drawText(line, anchorX, y, outline)
            c.drawText(line, anchorX, y, fill)
        }
        c.restore()
    }

    private fun drawShape(c: Canvas, l: Layer, k: Float) {
        val r = l.size / 2f
        val path = Path()
        when (l.shape) {
            ShapeKind.CIRCLE -> path.addCircle(0f, 0f, r, Path.Direction.CW)
            ShapeKind.RECT -> path.addRoundRect(RectF(-r, -r, r, r), r * 0.2f, r * 0.2f, Path.Direction.CW)
            ShapeKind.STAR -> {
                for (i in 0 until 10) {
                    val radius = if (i % 2 == 0) r else r * 0.45f
                    val angle = -PI / 2 + i * PI / 5
                    val px = (cos(angle) * radius).toFloat()
                    val py = (sin(angle) * radius).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
            }
            ShapeKind.HEART -> {
                path.moveTo(0f, r * 0.9f)
                path.cubicTo(-r * 1.6f, -r * 0.1f, -r * 0.7f, -r * 1.1f, 0f, -r * 0.4f)
                path.cubicTo(r * 0.7f, -r * 1.1f, r * 1.6f, -r * 0.1f, 0f, r * 0.9f)
                path.close()
            }
        }

        c.save()
        c.scale(k, k)
        c.translate(l.x, l.y)
        c.rotate(l.rotation)
        c.scale(l.scale, l.scale)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = l.color
        if (l.shadow) fill.setShadowLayer(l.size * 0.06f, l.size * 0.03f, l.size * 0.03f, 0xAA000000.toInt())
        c.drawPath(path, fill)

        if (l.strokeWidth > 0f) {
            val line = Paint(Paint.ANTI_ALIAS_FLAG)
            line.style = Paint.Style.STROKE
            line.strokeJoin = Paint.Join.ROUND
            line.strokeWidth = l.strokeWidth * 2f
            line.color = l.strokeColor
            c.drawPath(path, line)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ outline + shadow

    private fun compose(content: Bitmap, p: StickerProject, size: Int, k: Float): Bitmap {
        if (!p.outline.enabled && !p.shadow) return content

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        var silhouette: Bitmap? = null
        if (p.outline.enabled) {
            silhouette = dilate(content, max(1, (p.outline.width * k).roundToInt()), p.outline.color)
        }
        if (p.shadow) drawShadow(c, silhouette ?: content, size)

        if (silhouette != null) {
            c.drawBitmap(silhouette, 0f, 0f, null)
            silhouette.recycle()
        }
        c.drawBitmap(content, 0f, 0f, null)
        content.recycle()
        return out
    }

    /** Coloured copy of [src]'s shape grown by about [radius] pixels (used as sticker border). */
    private fun dilate(src: Bitmap, radius: Int, color: Int): Bitmap {
        val w = src.width
        val h = src.height
        var current = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val tint = Paint()
        tint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        Canvas(current).drawBitmap(src, 0f, 0f, tint)

        val iterations = min(radius, 6)
        val step = radius.toFloat() / iterations
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        repeat(iterations) {
            val next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val nc = Canvas(next)
            nc.drawBitmap(current, 0f, 0f, paint)
            for (a in 0 until 8) {
                val angle = a * PI / 4
                nc.drawBitmap(current, (cos(angle) * step).toFloat(), (sin(angle) * step).toFloat(), paint)
            }
            current.recycle()
            current = next
        }
        return current
    }

    private fun drawShadow(c: Canvas, src: Bitmap, size: Int) {
        val blur = Paint()
        blur.maskFilter = BlurMaskFilter(max(1f, size * 0.02f), BlurMaskFilter.Blur.NORMAL)
        val offset = IntArray(2)
        val alpha = src.extractAlpha(blur, offset)
        val shadowPaint = Paint()
        shadowPaint.color = 0x88000000.toInt()
        val d = size * 0.012f
        c.drawBitmap(alpha, offset[0] + d, offset[1] + d, shadowPaint)
        alpha.recycle()
    }
}
