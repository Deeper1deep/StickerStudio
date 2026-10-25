package com.stickerstudio.app.sticker

/** One video frame of a WebM file; [alpha] is the separate VP9 stream that carries transparency. */
class WebmFrame(val ptsMs: Long, val data: ByteArray, var alpha: ByteArray? = null)

class WebmTrack(
    val width: Int,
    val height: Int,
    val codec: String,
    val frames: List<WebmFrame>,
    val durationMs: Long,
) {
    val hasAlpha: Boolean get() = frames.isNotEmpty() && frames.all { it.alpha != null }
}

/**
 * Minimal WebM / Matroska reader (pure Kotlin) for the first video track.
 *
 * Why it exists: Telegram-style stickers are VP9 WebM files with transparency and often have no
 * duration or seek index. Android's MediaMetadataRetriever then returns the same frame for every
 * time (the sticker "does not move") and drops the transparency. Reading the frames ourselves lets
 * us decode them in order and join the colour and alpha streams.
 */
object WebmDemuxer {
    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val VIDEO = 0xE0L
    private const val CLUSTER = 0x1F43B675L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK_ADDITIONS = 0x75A1L
    private const val BLOCK_MORE = 0xA6L

    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_TYPE = 0x83L
    private const val CODEC_ID = 0x86L
    private const val PIXEL_W = 0xB0L
    private const val PIXEL_H = 0xBAL
    private const val CLUSTER_TIMECODE = 0xE7L
    private const val TIMECODE_SCALE = 0x2AD7B1L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK = 0xA1L
    private const val BLOCK_ADD_ID = 0xEEL
    private const val BLOCK_ADDITIONAL = 0xA5L

    private val MASTERS = setOf(SEGMENT, INFO, TRACKS, TRACK_ENTRY, VIDEO, CLUSTER, BLOCK_GROUP, BLOCK_ADDITIONS, BLOCK_MORE)

    fun isWebm(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()

    /** Null when the data is not a WebM with a readable video track. */
    fun parse(b: ByteArray): WebmTrack? {
        if (!isWebm(b)) return null
        var pos = 0
        val n = b.size

        var timecodeScale = 1_000_000L
        var clusterTc = 0L
        var vNumber = -1L
        var vWidth = 0
        var vHeight = 0
        var vCodec = ""
        // state of the TrackEntry being read
        var curNumber = -1L
        var curType = -1L
        var curCodec = ""
        var curW = 0
        var curH = 0
        fun commitTrack() {
            if (curType == 1L && vNumber < 0) {
                vNumber = curNumber; vWidth = curW; vHeight = curH; vCodec = curCodec
            }
            curNumber = -1; curType = -1; curCodec = ""; curW = 0; curH = 0
        }

        val frames = ArrayList<WebmFrame>()
        var lastBlockFrame: WebmFrame? = null
        var addId = 1L

        while (pos < n) {
            val idLen = vintLength(b[pos].toInt() and 0xFF, 4)
            if (idLen == 0 || pos + idLen > n) break
            var id = 0L
            for (i in 0 until idLen) id = (id shl 8) or (b[pos + i].toLong() and 0xFF)
            pos += idLen
            if (pos >= n) break
            val sizeLen = vintLength(b[pos].toInt() and 0xFF, 8)
            if (sizeLen == 0 || pos + sizeLen > n) break
            var size = (b[pos].toLong() and 0xFF) and ((1L shl (8 - sizeLen)) - 1)
            var allOnes = size == (1L shl (8 - sizeLen)) - 1
            for (i in 1 until sizeLen) {
                val v = b[pos + i].toLong() and 0xFF
                size = (size shl 8) or v
                if (v != 0xFFL) allOnes = false
            }
            pos += sizeLen
            val unknown = allOnes

            if (id in MASTERS) {
                if (id == TRACK_ENTRY) commitTrack()
                if (id == CLUSTER || id == SEGMENT) {
                    // a new top-level container: finish the last track entry first
                    if (curType != -1L || curNumber != -1L) commitTrack()
                }
                continue // descend: children are read inline
            }
            if (unknown || size < 0 || pos + size > n) break
            val end = pos + size.toInt()
            when (id) {
                TIMECODE_SCALE -> timecodeScale = uint(b, pos, end)
                TRACK_NUMBER -> curNumber = uint(b, pos, end)
                TRACK_TYPE -> curType = uint(b, pos, end)
                CODEC_ID -> curCodec = String(b, pos, end - pos, Charsets.US_ASCII)
                PIXEL_W -> curW = uint(b, pos, end).toInt()
                PIXEL_H -> curH = uint(b, pos, end).toInt()
                CLUSTER_TIMECODE -> clusterTc = uint(b, pos, end)
                SIMPLE_BLOCK, BLOCK -> {
                    if (curType != -1L || curNumber != -1L) commitTrack()
                    val frame = readBlock(b, pos, end, vNumber, clusterTc, timecodeScale)
                    if (frame != null) frames += frame
                    lastBlockFrame = frame
                    addId = 1L
                }
                BLOCK_ADD_ID -> addId = uint(b, pos, end)
                BLOCK_ADDITIONAL -> if (addId == 1L) lastBlockFrame?.alpha = b.copyOfRange(pos, end)
            }
            pos = end
        }
        if (curType != -1L || curNumber != -1L) commitTrack()
        if (frames.isEmpty() || vWidth <= 0 || vHeight <= 0) return null

        val last = frames.last().ptsMs
        val step = if (frames.size >= 2) last - frames[frames.size - 2].ptsMs else 33L
        return WebmTrack(vWidth, vHeight, vCodec, frames, last + step.coerceAtLeast(1L))
    }

    private fun readBlock(b: ByteArray, start: Int, end: Int, track: Long, clusterTc: Long, scale: Long): WebmFrame? {
        var p = start
        if (p >= end) return null
        val len = vintLength(b[p].toInt() and 0xFF, 8)
        if (len == 0 || p + len + 3 > end) return null
        var number = (b[p].toLong() and 0xFF) and ((1L shl (8 - len)) - 1)
        for (i in 1 until len) number = (number shl 8) or (b[p + i].toLong() and 0xFF)
        p += len
        if (track >= 0 && number != track) return null
        val rel = ((b[p].toInt() shl 8) or (b[p + 1].toInt() and 0xFF)).toShort().toInt()
        val flags = b[p + 2].toInt() and 0xFF
        p += 3
        if (flags and 0x06 != 0) return null // lacing is never used for video
        val ms = (clusterTc + rel) * scale / 1_000_000L
        return WebmFrame(ms, b.copyOfRange(p, end))
    }

    private fun vintLength(first: Int, max: Int): Int {
        for (i in 0 until max) if (first and (0x80 shr i) != 0) return i + 1
        return 0
    }

    private fun uint(b: ByteArray, from: Int, to: Int): Long {
        var v = 0L
        for (i in from until to) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }
}

/** What the first VP9 key frame says about colours (needed to turn YUV into RGB correctly). */
class Vp9Color(val bt709: Boolean, val fullRange: Boolean)

object Vp9Header {
    /** Reads colour space and range from a VP9 key frame; null for other frames. */
    fun color(frame: ByteArray): Vp9Color? {
        var bit = 0
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = bit shr 3
                if (byte >= frame.size) return -1
                v = (v shl 1) or ((frame[byte].toInt() shr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }
        if (read(2) != 2) return null
        val low = read(1)
        val high = read(1)
        val profile = (high shl 1) + low
        if (profile == 3) read(1)
        if (read(1) != 0) return null            // show_existing_frame
        if (read(1) != 0) return null            // frame_type: 0 = key frame
        read(1)                                  // show_frame
        read(1)                                  // error_resilient_mode
        if (read(24) != 0x498342) return null    // sync code
        if (profile >= 2) read(1)                // ten_or_twelve_bit
        val space = read(3)
        if (space < 0) return null
        val full = if (space == 7) true else read(1) == 1
        val bt709 = !(space == 1 || space == 3 || space == 4)
        return Vp9Color(bt709, full)
    }
}

object YuvConvert {
    private fun clamp(v: Float): Int = if (v < 0f) 0 else if (v > 255f) 255 else (v + 0.5f).toInt()

    /**
     * Planes are tightly packed: [y] and [alpha] are w*h, [u] and [v] are ((w+1)/2)*((h+1)/2).
     * [alpha] (the luma of the alpha stream) is optional; without it the picture is opaque.
     */
    fun toArgb(
        w: Int, h: Int,
        y: ByteArray, u: ByteArray, v: ByteArray, alpha: ByteArray?,
        color: Vp9Color,
    ): IntArray {
        val out = IntArray(w * h)
        val cw = (w + 1) / 2
        val rv: Float; val gu: Float; val gv: Float; val bu: Float
        if (color.bt709) { rv = 1.5748f; gu = 0.1873f; gv = 0.4681f; bu = 1.8556f }
        else { rv = 1.402f; gu = 0.3441f; gv = 0.7141f; bu = 1.772f }
        for (row in 0 until h) {
            for (col in 0 until w) {
                val yi = row * w + col
                val ci = (row shr 1) * cw + (col shr 1)
                var yy = (y[yi].toInt() and 0xFF).toFloat()
                var uu = (u[ci].toInt() and 0xFF) - 128f
                var vv = (v[ci].toInt() and 0xFF) - 128f
                if (!color.fullRange) {
                    yy = (yy - 16f) * (255f / 219f)
                    uu *= 255f / 224f
                    vv *= 255f / 224f
                }
                val r = clamp(yy + rv * vv)
                val g = clamp(yy - gu * uu - gv * vv)
                val bl = clamp(yy + bu * uu)
                val a = if (alpha != null) (alpha[yi].toInt() and 0xFF) else 255
                out[yi] = (a shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        return out
    }
}
