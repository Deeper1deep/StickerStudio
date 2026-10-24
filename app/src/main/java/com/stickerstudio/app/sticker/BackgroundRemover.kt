package com.stickerstudio.app.sticker

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Two fully on-device background removal methods. Nothing is uploaded anywhere.
 *
 *  1. [removeBorderColor]: flood-fill from the image border (works for plain backgrounds).
 *  2. [removePerson]: ML Kit selfie segmentation, model bundled in the APK (people only).
 *
 * For anything else the manual eraser in the editor is the fallback.
 */
object BackgroundRemover {

    /**
     * Makes transparent every pixel connected to the border whose colour is within
     * [tolerance] (0..255, per-channel max difference) of the dominant border colour.
     */
    fun removeBorderColor(src: Bitmap, tolerance: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val bg = dominantBorderColor(px, w, h)
        val tol = tolerance.coerceIn(1, 255)

        fun matches(c: Int): Boolean {
            if ((c ushr 24) < 16) return true
            return kotlin.math.abs(((c shr 16) and 0xFF) - ((bg shr 16) and 0xFF)) <= tol &&
                kotlin.math.abs(((c shr 8) and 0xFF) - ((bg shr 8) and 0xFF)) <= tol &&
                kotlin.math.abs((c and 0xFF) - (bg and 0xFF)) <= tol
        }

        val removed = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        fun push(i: Int) {
            if (!removed[i] && matches(px[i])) {
                removed[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }

        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }

        val out = IntArray(w * h)
        for (i in out.indices) {
            if (removed[i]) {
                out[i] = 0
            } else {
                val x = i % w
                val y = i / w
                val nextToRemoved = (x > 0 && removed[i - 1]) || (x < w - 1 && removed[i + 1]) ||
                    (y > 0 && removed[i - w]) || (y < h - 1 && removed[i + w])
                val a = (px[i] ushr 24)
                // Half-transparent edge pixels give a slightly softer cut-out border.
                val newA = if (nextToRemoved) a / 2 else a
                out[i] = (newA shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun dominantBorderColor(px: IntArray, w: Int, h: Int): Int {
        val counts = HashMap<Int, Int>()
        fun add(c: Int) {
            // Quantise to 16 levels per channel so noisy backgrounds still vote together.
            val key = (c and 0xF0F0F0)
            counts[key] = (counts[key] ?: 0) + 1
        }
        for (x in 0 until w) { add(px[x]); add(px[(h - 1) * w + x]) }
        for (y in 0 until h) { add(px[y * w]); add(px[y * w + w - 1]) }
        val best = counts.maxByOrNull { it.value }?.key ?: 0xFFFFFF
        return 0xFF000000.toInt() or best or 0x080808
    }

    /** Removes everything except people. Throws if the on-device model fails. */
    suspend fun removePerson(src: Bitmap): Bitmap = suspendCancellableCoroutine { cont ->
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        cont.invokeOnCancellation { segmenter.close() }

        segmenter.process(InputImage.fromBitmap(src, 0))
            .addOnSuccessListener { mask ->
                try {
                    val result = applyMask(src, mask)
                    segmenter.close()
                    cont.resume(result)
                } catch (e: Exception) {
                    segmenter.close()
                    cont.resumeWithException(e)
                }
            }
            .addOnFailureListener { e ->
                segmenter.close()
                cont.resumeWithException(e)
            }
    }

    private fun applyMask(src: Bitmap, mask: SegmentationMask): Bitmap {
        val w = src.width
        val h = src.height
        val mw = mask.width
        val mh = mask.height
        val buffer = mask.buffer
        buffer.rewind()
        val conf = FloatArray(mw * mh)
        for (i in conf.indices) conf[i] = buffer.getFloat()

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val my = (y * mh / h).coerceAtMost(mh - 1)
            for (x in 0 until w) {
                val mx = (x * mw / w).coerceAtMost(mw - 1)
                val c = conf[my * mw + mx]
                // Smooth step between "background" (<0.35) and "person" (>0.65).
                val t = ((c - 0.35f) / 0.30f).coerceIn(0f, 1f)
                val a = ((px[y * w + x] ushr 24) * t).toInt()
                px[y * w + x] = (a shl 24) or (px[y * w + x] and 0x00FFFFFF)
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(px, 0, w, 0, 0, w, h)
        return result
    }
}
