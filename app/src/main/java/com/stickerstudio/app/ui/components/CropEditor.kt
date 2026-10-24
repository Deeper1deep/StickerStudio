package com.stickerstudio.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.stickerstudio.app.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.stickerstudio.app.sticker.CropMath
import com.stickerstudio.app.sticker.CropRect
import kotlin.math.hypot
import kotlin.math.min

/**
 * The picture at its own aspect ratio with a draggable crop frame on top (corners, edges and
 * move). The frame can be free or locked to [aspect] (width / height in pixels).
 */
@Composable
fun CropEditor(
    bitmap: Bitmap,
    crop: CropRect,
    aspect: Float?,
    onChange: (CropRect) -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCrop by rememberUpdatedState(crop)
    val currentAspect by rememberUpdatedState(aspect)
    val currentChange by rememberUpdatedState(onChange)
    val touchSlop = with(LocalDensity.current) { 36.dp.toPx() }
    val stroke = with(LocalDensity.current) { 2.dp.toPx() }
    val bracket = with(LocalDensity.current) { 22.dp.toPx() }

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min(boxW / bitmap.width, boxH / bitmap.height)
        val iw = bitmap.width * scale
        val ih = bitmap.height * scale
        val ox = (boxW - iw) / 2f
        val oy = (boxH - ih) / 2f

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bitmap, iw, ih, ox, oy) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val handle = hitTest(down.position, currentCrop, ox, oy, iw, ih, touchSlop)
                        if (handle != null) {
                            onGestureStart()
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.position - last
                                last = change.position
                                if (delta != Offset.Zero) {
                                    currentChange(
                                        CropMath.drag(
                                            currentCrop, handle, delta.x / iw, delta.y / ih,
                                            bitmap.width, bitmap.height, currentAspect,
                                        ),
                                    )
                                    change.consume()
                                }
                            }
                            onGestureEnd()
                        }
                    }
                },
        ) {
            val l = ox + crop.l * iw
            val t = oy + crop.t * ih
            val r = ox + crop.r * iw
            val b = oy + crop.b * ih
            val dim = Color(0x99000000)
            // Darken everything outside the frame (inside the picture).
            drawRect(dim, Offset(ox, oy), Size(iw, t - oy))
            drawRect(dim, Offset(ox, b), Size(iw, oy + ih - b))
            drawRect(dim, Offset(ox, t), Size(l - ox, b - t))
            drawRect(dim, Offset(r, t), Size(ox + iw - r, b - t))

            drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(stroke))
            val grid = Color(0x66FFFFFF)
            for (i in 1..2) {
                val gx = l + (r - l) * i / 3f
                val gy = t + (b - t) * i / 3f
                drawLine(grid, Offset(gx, t), Offset(gx, b), 1f)
                drawLine(grid, Offset(l, gy), Offset(r, gy), 1f)
            }
            val w = stroke * 2f
            val bl = min(bracket, min(r - l, b - t) / 2f)
            drawLine(Color.White, Offset(l, t), Offset(l + bl, t), w); drawLine(Color.White, Offset(l, t), Offset(l, t + bl), w)
            drawLine(Color.White, Offset(r, t), Offset(r - bl, t), w); drawLine(Color.White, Offset(r, t), Offset(r, t + bl), w)
            drawLine(Color.White, Offset(l, b), Offset(l + bl, b), w); drawLine(Color.White, Offset(l, b), Offset(l, b - bl), w)
            drawLine(Color.White, Offset(r, b), Offset(r - bl, b), w); drawLine(Color.White, Offset(r, b), Offset(r, b - bl), w)
            val mx = (l + r) / 2f
            val my = (t + b) / 2f
            for (p in listOf(Offset(mx, t), Offset(mx, b), Offset(l, my), Offset(r, my))) drawCircle(Color.White, stroke * 2f, p)
        }
    }
}

private fun hitTest(p: Offset, c: CropRect, ox: Float, oy: Float, iw: Float, ih: Float, slop: Float): CropMath.Handle? {
    val l = ox + c.l * iw
    val t = oy + c.t * ih
    val r = ox + c.r * iw
    val b = oy + c.b * ih
    val mx = (l + r) / 2f
    val my = (t + b) / 2f
    val points = listOf(
        CropMath.Handle.TL to Offset(l, t), CropMath.Handle.TR to Offset(r, t),
        CropMath.Handle.BL to Offset(l, b), CropMath.Handle.BR to Offset(r, b),
        CropMath.Handle.T to Offset(mx, t), CropMath.Handle.B to Offset(mx, b),
        CropMath.Handle.L to Offset(l, my), CropMath.Handle.R to Offset(r, my),
    )
    val nearest = points.minByOrNull { hypot(p.x - it.second.x, p.y - it.second.y) }
    if (nearest != null && hypot(p.x - nearest.second.x, p.y - nearest.second.y) <= slop) return nearest.first
    return if (p.x in l..r && p.y in t..b) CropMath.Handle.MOVE else null
}

/** Aspect ratio presets shared by the editor and the animated screen. */
enum class CropPreset(val ratio: Float?) {
    ORIGINAL(null), FREE(null), SQUARE(1f), R43(4f / 3f), R34(3f / 4f), R169(16f / 9f), R916(9f / 16f)
}

/** The crop frame and locked aspect that a preset produces for a picture of the given size. */
fun CropPreset.resolve(imgW: Int, imgH: Int, current: CropRect): Pair<CropRect, Float?> = when (this) {
    CropPreset.ORIGINAL -> CropRect() to (imgW.toFloat() / imgH)
    CropPreset.FREE -> current to null
    else -> CropMath.withAspect(imgW, imgH, ratio!!) to ratio
}

@Composable
fun CropPresetChips(selected: CropPreset, onSelect: (CropPreset) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(CropPreset.entries.toList()) { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                label = {
                    Text(
                        when (preset) {
                            CropPreset.ORIGINAL -> stringResource(R.string.crop_original)
                            CropPreset.FREE -> stringResource(R.string.crop_free)
                            CropPreset.SQUARE -> "1:1"
                            CropPreset.R43 -> "4:3"
                            CropPreset.R34 -> "3:4"
                            CropPreset.R169 -> "16:9"
                            CropPreset.R916 -> "9:16"
                        },
                    )
                },
            )
        }
    }
}
