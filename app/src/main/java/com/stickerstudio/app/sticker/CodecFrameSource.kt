package com.stickerstudio.app.sticker

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * MP4 / MOV / 3GP / MKV video decoded in order with MediaExtractor + MediaCodec.
 *
 * MediaMetadataRetriever.getFrameAtTime costs a seek plus a full-size decode for every single
 * frame, so converting a clip took minutes. Reading the stream once, front to back, is many times
 * faster; frames are also shrunk to at most [MAX_SIDE] pixels while they are copied out.
 * Asking for an earlier time than the current one restarts from the beginning.
 */
class CodecFrameSource(private val file: File) : FrameSource {

    private class Packed(
        val w: Int, val h: Int,
        val y: ByteArray, val u: ByteArray, val v: ByteArray,
        val ptsUs: Long,
    )

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var mime = ""
    private var inputDone = false
    private var outputDone = false
    private var rotation = 0
    private var bt709 = true
    private var fullRange = false

    private var held: Packed? = null
    private var ahead: Packed? = null
    private var basePts = 0L

    override var durationMs: Long = 0L
        private set
    override var width: Int = 0
        private set
    override var height: Int = 0
        private set

    init {
        try {
            open()
            val first = nextFrame() ?: throw IOException("No video frame")
            basePts = first.ptsUs
            held = first
            // Output size is only known now (the decoder may crop padding); apply rotation.
            val swap = rotation == 90 || rotation == 270
            width = if (swap) first.h else first.w
            height = if (swap) first.w else first.h
            if (durationMs <= 0L || width <= 0 || height <= 0) throw IOException("Not a readable video")
        } catch (e: Throwable) {
            close()
            if (e is IOException) throw e
            throw IOException("Video decoder failed: ${e.message}")
        }
    }

    private fun open() {
        val ex = MediaExtractor()
        extractor = ex
        ex.setDataSource(file.path)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) { track = i; format = f; break }
        }
        if (track < 0 || format == null) throw IOException("No video track")
        ex.selectTrack(track)
        mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        if (durationMs <= 0L && format.containsKey(MediaFormat.KEY_DURATION)) durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
        val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
        bt709 = h >= 720
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val c = MediaCodec.createDecoderByType(mime)
        codec = c
        c.configure(format, null, null, 0)
        c.start()
        inputDone = false
        outputDone = false
    }

    private fun restart() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
        held = null
        ahead = null
        open()
    }

    override fun frameAt(timeMs: Long): Bitmap? {
        val t = basePts + timeMs.coerceAtLeast(0L) * 1000L
        val h0 = held
        if (h0 != null && t < h0.ptsUs && h0.ptsUs != basePts) {
            restart()
        }
        while (true) {
            if (ahead == null && !outputDone) ahead = nextFrame()
            val a = ahead ?: break
            if (held == null || a.ptsUs <= t) {
                held = a
                ahead = null
            } else {
                break
            }
        }
        val p = held ?: return null
        return toBitmap(p)
    }

    private fun toBitmap(p: Packed): Bitmap {
        val px = YuvConvert.toArgb(p.w, p.h, p.y, p.u, p.v, null, Vp9Color(bt709, fullRange))
        val out = rotate(px, p.w, p.h)
        val swap = rotation == 90 || rotation == 270
        return Bitmap.createBitmap(out, if (swap) p.h else p.w, if (swap) p.w else p.h, Bitmap.Config.ARGB_8888)
    }

    private fun rotate(px: IntArray, w: Int, h: Int): IntArray {
        if (rotation == 0) return px
        val out = IntArray(px.size)
        for (r in 0 until h) {
            for (c in 0 until w) {
                val v = px[r * w + c]
                when (rotation) {
                    90 -> out[c * h + (h - 1 - r)] = v
                    180 -> out[(h - 1 - r) * w + (w - 1 - c)] = v
                    270 -> out[(w - 1 - c) * h + r] = v
                    else -> out[r * w + c] = v
                }
            }
        }
        return out
    }

    /** Decodes until the next picture is out; null at the end of the stream. */
    private fun nextFrame(): Packed? {
        val c = codec ?: return null
        val ex = extractor ?: return null
        var lastProgress = SystemClock.elapsedRealtime()
        while (true) {
            if (!inputDone) {
                val i = c.dequeueInputBuffer(0)
                if (i >= 0) {
                    val buf = c.getInputBuffer(i) ?: throw IOException("No input buffer")
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                    lastProgress = SystemClock.elapsedRealtime()
                    continue
                }
            }
            val o = c.dequeueOutputBuffer(info, 5_000)
            when {
                o >= 0 -> {
                    lastProgress = SystemClock.elapsedRealtime()
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var frame: Packed? = null
                    try {
                        val img = if (info.size > 0) c.getOutputImage(o) else null
                        if (img != null) {
                            frame = pack(img, info.presentationTimeUs)
                            img.close()
                        }
                    } finally {
                        c.releaseOutputBuffer(o, false)
                    }
                    if (eos) outputDone = true
                    if (frame != null) return frame
                    if (eos) return null
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                        // 1 = BT.709, 2 / 4 = BT.601; anything else is treated like BT.709
                        val s = f.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                        bt709 = !(s == 2 || s == 4)
                    }
                    if (f.containsKey(MediaFormat.KEY_COLOR_RANGE)) fullRange = f.getInteger(MediaFormat.KEY_COLOR_RANGE) == 1
                }
                else -> Unit
            }
            if (SystemClock.elapsedRealtime() - lastProgress > STALL_MS) throw IOException("Video decoder stalled")
        }
    }

    /** Copies the picture out as packed planes, shrunk by an integer step so its long side is <= MAX_SIDE. */
    private fun pack(img: Image, ptsUs: Long): Packed {
        val crop = img.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val d = max(1, (max(cw, ch) + MAX_SIDE - 1) / MAX_SIDE)
        val w = max(2, (cw / d) and 1.inv())
        val h = max(2, (ch / d) and 1.inv())
        val planes = img.planes
        val y = ByteArray(w * h)
        val u = ByteArray((w / 2) * (h / 2))
        val v = ByteArray((w / 2) * (h / 2))

        val yb = planes[0].buffer
        val yrs = planes[0].rowStride
        val yps = planes[0].pixelStride
        for (r in 0 until h) {
            val base = (crop.top + r * d) * yrs + crop.left * yps
            for (c in 0 until w) {
                val idx = base + c * d * yps
                y[r * w + c] = if (idx < yb.limit()) yb.get(idx) else 0
            }
        }
        fun chroma(p: Image.Plane, out: ByteArray) {
            val b = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            for (r in 0 until h / 2) {
                val base = (crop.top / 2 + r * d) * rs + (crop.left / 2) * ps
                for (c in 0 until w / 2) {
                    val idx = base + c * d * ps
                    out[r * (w / 2) + c] = if (idx < b.limit()) b.get(idx) else 0
                }
            }
        }
        chroma(planes[1], u)
        chroma(planes[2], v)
        return Packed(w, h, y, u, v, ptsUs)
    }

    override fun close() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { extractor?.release() } catch (_: Exception) {}
        codec = null
        extractor = null
        held = null
        ahead = null
    }

    companion object {
        private const val MAX_SIDE = 960
        private const val STALL_MS = 4000L
    }
}

/**
 * Uses the fast sequential reader, but if it fails on this device (odd decoder, unsupported format)
 * quietly switches to the slower, more tolerant [VideoFrameSource].
 */
class FallbackFrameSource(
    private var primary: FrameSource,
    private val fallback: () -> FrameSource,
) : FrameSource {
    override val durationMs: Long get() = primary.durationMs
    override val width: Int get() = primary.width
    override val height: Int get() = primary.height

    override fun frameAt(timeMs: Long): Bitmap? = try {
        primary.frameAt(timeMs)
    } catch (e: Exception) {
        val old = primary
        primary = fallback()
        try { old.close() } catch (_: Exception) {}
        primary.frameAt(timeMs)
    }

    override fun close() = primary.close()
}
