package com.stickerstudio.app.sticker

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.IOException

/**
 * VP9 WebM (Telegram-style stickers included) decoded frame by frame in order.
 *
 * MediaMetadataRetriever cannot seek in WebM files that have no index (every time returns the same
 * picture, so the sticker looks frozen) and it throws away transparency. Here [WebmDemuxer] reads
 * the frames and the system VP9 decoder decodes them sequentially; when the file carries an alpha
 * stream a second decoder produces the transparency and both are merged.
 */
class WebmFrameSource(file: File) : FrameSource {
    private val track: WebmTrack
    private val color: Vp9Color

    override val durationMs: Long
    override val width: Int
    override val height: Int

    private var colorDec: Vp9Decoder? = null
    private var alphaDec: Vp9Decoder? = null
    private var fed = 0
    private var eosSent = false
    private var broken = false
    private var lastIndex = -1
    private var lastPixels: IntArray? = null

    init {
        if (file.length() > MAX_BYTES) throw IOException("Video too large")
        val t = WebmDemuxer.parse(file.readBytes()) ?: throw IOException("Not a readable WebM")
        if (t.codec != "V_VP9") throw IOException("Not VP9: ${t.codec}")
        track = t
        color = Vp9Header.color(t.frames[0].data) ?: Vp9Color(bt709 = true, fullRange = false)
        width = t.width
        height = t.height
        durationMs = t.durationMs
    }

    private fun start() {
        close()
        colorDec = Vp9Decoder(width, height, needChroma = true)
        alphaDec = if (track.hasAlpha) Vp9Decoder(width, height, needChroma = false) else null
        fed = 0
        eosSent = false
    }

    override fun frameAt(timeMs: Long): Bitmap? {
        var idx = 0
        for (i in track.frames.indices) if (track.frames[i].ptsMs <= timeMs) idx = i else break
        if (idx != lastIndex || lastPixels == null) {
            if (colorDec == null || idx < lastIndex) start()
            val px = decodeUpTo(idx) ?: return null
            lastPixels = px
            lastIndex = idx
        }
        val px = lastPixels ?: return null
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decodeUpTo(idx: Int): IntArray? {
        if (broken) throw IOException("WebM decoder stalled")
        val c = colorDec ?: return null
        val a = alphaDec
        // Some decoders hold back a few frames, so feed a little ahead of the one we want.
        val target = minOf(idx + LOOKAHEAD, track.frames.size - 1)
        while (fed <= target) {
            val f = track.frames[fed]
            val pts = f.ptsMs * 1000L
            c.feed(f.data, pts) { a?.pump() }
            if (a != null) a.feed(f.alpha ?: f.data, pts) { c.pump() }
            fed++
        }
        if (fed >= track.frames.size && !eosSent) {
            c.sendEos { a?.pump() }
            a?.sendEos { c.pump() }
            eosSent = true
        }
        val pts = track.frames[idx].ptsMs * 1000L
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        var yuv: Yuv? = null
        var al: Yuv? = null
        while (true) {
            c.pump(); a?.pump()
            yuv = c.take(pts) ?: yuv
            al = a?.take(pts) ?: al
            if (yuv != null && (a == null || al != null)) break
            if (SystemClock.elapsedRealtime() > deadline) {
                broken = true   // fail fast instead of waiting for every remaining frame
                throw IOException("WebM decoder stalled")
            }
            Thread.sleep(2)
        }
        c.dropBefore(pts); a?.dropBefore(pts)
        val y = yuv ?: return null
        return YuvConvert.toArgb(width, height, y.y, y.u, y.v, al?.y, color)
    }

    override fun close() {
        try { colorDec?.release() } catch (_: Exception) {}
        try { alphaDec?.release() } catch (_: Exception) {}
        colorDec = null
        alphaDec = null
        lastIndex = -1
        lastPixels = null
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val WAIT_MS = 2500L
        private const val LOOKAHEAD = 6

        fun looksLikeWebm(file: File): Boolean = try {
            file.inputStream().use { i -> val b = ByteArray(4); i.read(b) == 4 && WebmDemuxer.isWebm(b) }
        } catch (e: Exception) {
            false
        }
    }
}

private class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

/** Synchronous wrapper around the system VP9 decoder that keeps outputs by presentation time. */
private class Vp9Decoder(private val w: Int, private val h: Int, private val needChroma: Boolean) {
    private val codec: MediaCodec = createCodec()
    private val pending = HashMap<Long, Yuv>()
    private val info = MediaCodec.BufferInfo()

    init {
        val f = MediaFormat.createVideoFormat(MIME, w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec.configure(f, null, null, 0)
        codec.start()
    }

    fun feed(data: ByteArray, ptsUs: Long, whileWaiting: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 3000L
        while (true) {
            val i = codec.dequeueInputBuffer(5_000)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i) ?: throw IOException("No input buffer")
                buf.clear()
                buf.put(data)
                codec.queueInputBuffer(i, 0, data.size, ptsUs, 0)
                return
            }
            pump()
            whileWaiting()
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("Decoder is not accepting data")
        }
    }

    fun sendEos(whileWaiting: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 2000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val i = codec.dequeueInputBuffer(5_000)
            if (i >= 0) {
                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            pump()
            whileWaiting()
        }
    }

    fun pump() {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> {
                    try {
                        val img = if (info.size > 0) codec.getOutputImage(o) else null
                        if (img != null) {
                            pending[info.presentationTimeUs] = copy(img)
                            img.close()
                        }
                    } catch (e: IllegalStateException) {
                        // an empty / end-of-stream buffer: nothing to copy
                    } finally {
                        codec.releaseOutputBuffer(o, false)
                    }
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> return
            }
        }
    }

    fun take(ptsUs: Long): Yuv? = pending.remove(ptsUs)

    fun dropBefore(ptsUs: Long) {
        val it = pending.keys.iterator()
        while (it.hasNext()) if (it.next() < ptsUs) it.remove()
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    private fun plane(p: Image.Plane, pw: Int, ph: Int): ByteArray {
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val out = ByteArray(pw * ph)
        for (r in 0 until ph) {
            val rowStart = r * rs
            for (c in 0 until pw) out[r * pw + c] = buf.get(rowStart + c * ps)
        }
        return out
    }

    private fun copy(img: Image): Yuv {
        val planes = img.planes
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val y = plane(planes[0], w, h)
        return if (needChroma) Yuv(y, plane(planes[1], cw, ch), plane(planes[2], cw, ch)) else Yuv(y, ByteArray(0), ByteArray(0))
    }

    companion object {
        private const val MIME = "video/x-vnd.on2.vp9"

        /** The built-in software decoder is the most predictable; vendor ones may buffer or use odd formats. */
        private fun createCodec(): MediaCodec {
            for (name in listOf("c2.android.vp9.decoder", "OMX.google.vp9.decoder")) {
                try {
                    return MediaCodec.createByCodecName(name)
                } catch (e: Exception) {
                    // try the next one
                }
            }
            return MediaCodec.createDecoderByType(MIME)
        }
    }
}
