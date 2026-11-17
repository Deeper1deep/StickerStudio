package com.stickerstudio.app.sticker

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.stickerstudio.app.core.WhatsAppLimits
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A source of frames addressed by time. */
interface FrameSource : Closeable {
    val durationMs: Long
    val width: Int
    val height: Int
    fun frameAt(timeMs: Long): Bitmap?
}

/** Animated GIF, decoded in order by [GifDecoder] (fast for sequential reads). */
class GifFrameSource(file: File) : FrameSource {
    private val decoder = GifDecoder(file.readBytes())
    private val buffer: IntArray

    override val durationMs: Long = decoder.durationMs
    override val width: Int = decoder.width
    override val height: Int = decoder.height

    init {
        if (decoder.frameCount < 2 || durationMs <= 0L || width <= 0 || height <= 0) throw IOException("GIF is not animated")
        buffer = IntArray(width * height)
    }

    override fun frameAt(timeMs: Long): Bitmap? {
        decoder.renderFrame(decoder.indexAt(timeMs), buffer)
        return Bitmap.createBitmap(buffer, width, height, Bitmap.Config.ARGB_8888)
    }

    override fun close() = Unit
}

class VideoFrameSource(file: File) : FrameSource {
    private val retriever = MediaMetadataRetriever()

    override val durationMs: Long
    override val width: Int
    override val height: Int

    init {
        var d = 0L
        var w = 0
        var h = 0
        try {
            retriever.setDataSource(file.path)
            d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val vw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val swap = rotation == 90 || rotation == 270
            w = if (swap) vh else vw
            h = if (swap) vw else vh
        } catch (e: RuntimeException) {
            d = 0L
        }
        durationMs = d
        width = w
        height = h
        if (durationMs <= 0L || width <= 0 || height <= 0) {
            retriever.release()
            throw IOException("Not a readable video")
        }
    }

    override fun frameAt(timeMs: Long): Bitmap? {
        val us = timeMs.coerceIn(0L, durationMs) * 1000L
        // Some WebM files cannot seek to an exact frame; fall back to the nearest key frame.
        return retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    override fun close() = retriever.release()
}

enum class ConvertError { DECODE, MEMORY, TOO_LARGE, INVALID_RESULT }

sealed interface ConvertOutcome {
    class Success(val bytes: ByteArray, val info: WebpInfo, val fps: Int) : ConvertOutcome
    class Failure(val error: ConvertError, val lastSizeBytes: Int = 0) : ConvertOutcome
}

/**
 * GIF / video -> WhatsApp animated sticker (512x512 animated WebP, <= 500 KB, <= 10 s).
 *
 * Every frame is rendered with [StickerRenderer], compressed as a lossy WebP frame and the
 * frames are joined with [AnimatedWebpMuxer]. If the result is larger than 500 KB, the
 * conversion is retried with lower quality / fewer frames per second.
 */
object AnimatedConverter {

    private class Attempt(val quality: Int, val fps: Int)

    fun open(file: File, isVideo: Boolean): FrameSource {
        if (!isVideo) return GifFrameSource(file)
        // VP9 WebM (e.g. Telegram stickers): decode in order, which also keeps transparency.
        if (WebmFrameSource.looksLikeWebm(file)) {
            try {
                val src = WebmFrameSource(file)
                val first = try { src.frameAt(0L) } catch (e: Exception) { null }
                if (first != null) {
                    first.recycle()
                    return src
                }
                src.close()
            } catch (e: Exception) {
                // not VP9 / no decoder: use the generic reader below
            }
        }
        // Other videos (MP4 ...): fast in-order decoding, with the old reader as a safety net.
        return try {
            FallbackFrameSource(CodecFrameSource(file)) { VideoFrameSource(file) }
        } catch (e: Exception) {
            VideoFrameSource(file)
        }
    }

    /** Project used for every frame: fit or cover, plus an optional caption. */
    fun projectFor(src: FrameSource, settings: AnimatedSettings): StickerProject {
        val layers = if (settings.caption.isBlank()) emptyList() else listOf(
            Layer(
                id = "caption", kind = LayerKind.TEXT, text = settings.caption, color = Colors.WHITE,
                size = 64f, strokeColor = Colors.BLACK, strokeWidth = 5f, x = 256f, y = 440f,
            )
        )
        val px = CropMath.pixels(settings.crop, src.width, src.height)
        return StickerProject(
            xform = Xform(scale = if (settings.cover) StickerRenderer.coverScale(px[2], px[3]) else 1f),
            layers = layers,
            crop = settings.crop,
        )
    }

    suspend fun convert(
        file: File,
        isVideo: Boolean,
        settings: AnimatedSettings,
        onProgress: (Float) -> Unit,
    ): ConvertOutcome = withContext(Dispatchers.Default) {
        try {
            open(file, isVideo).use { src ->
                val project = projectFor(src, settings)
                val slow = max(8, settings.fps * 2 / 3)
                val attempts = listOf(
                    Attempt(65, settings.fps), Attempt(48, settings.fps), Attempt(34, settings.fps),
                    Attempt(34, slow), Attempt(22, slow), Attempt(14, 8),
                )
                var lastSize = 0
                // Predict each attempt's size from a few sample frames, so we do not run (and throw
                // away) a full encode for settings that are obviously too big.
                val start = estimateStart(src, project, settings, attempts)
                for ((index, attempt) in attempts.withIndex()) {
                    if (index < start) continue
                    val bytes = encodeOnce(src, project, settings, attempt) { p ->
                        onProgress((index - start + p) / (attempts.size - start))
                    }
                    lastSize = bytes.size
                    if (bytes.size <= WhatsAppLimits.MAX_ANIMATED_BYTES) {
                        val info = WebpParser.parse(bytes)
                        if (info == null || !info.animated) {
                            return@withContext ConvertOutcome.Failure(ConvertError.INVALID_RESULT)
                        }
                        onProgress(1f)
                        return@withContext ConvertOutcome.Success(bytes, info, attempt.fps)
                    }
                }
                ConvertOutcome.Failure(ConvertError.TOO_LARGE, lastSize)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            ConvertOutcome.Failure(ConvertError.MEMORY)
        } catch (e: IOException) {
            ConvertOutcome.Failure(ConvertError.DECODE)
        } catch (e: RuntimeException) {
            ConvertOutcome.Failure(ConvertError.DECODE)
        }
    }

    private const val SAMPLES = 5

    /** Frame length and count for [fps]; shared by the estimate and the real encode. */
    private fun plan(src: FrameSource, settings: AnimatedSettings, fps: Int): Pair<Int, Int> {
        val frameDuration = (1000f / fps).roundToInt().coerceAtLeast(WhatsAppLimits.MIN_FRAME_DURATION_MS)
        val available = max(0L, src.durationMs - settings.startMs)
        val totalMs = min(min(settings.durationMs, WhatsAppLimits.MAX_ANIMATED_DURATION_MS.toLong()), max(available, frameDuration * 2L))
        return frameDuration to max(2, (totalMs / frameDuration).toInt())
    }

    /**
     * The frames to encode for [fps]: the hand-edited timeline when there is one (thinned out for the
     * lower-fps retries), otherwise even steps from the start / length / fps settings.
     */
    fun entries(src: FrameSource, settings: AnimatedSettings, fps: Int): List<TimelineFrame> {
        val manual = settings.frames
        if (manual != null && manual.size >= FramePlan.MIN_FRAMES) {
            val clamped = FramePlan.clampTotal(manual, FramePlan.MAX_TOTAL_MS)
            return if (fps < settings.fps) FramePlan.thin(clamped, (1000f / fps).roundToInt()) else clamped
        }
        val (frameDuration, count) = plan(src, settings, fps)
        return FramePlan.uniform(settings.startMs, frameDuration, count)
    }

    /** First attempt whose predicted size fits (about 90% of the limit); the last one if none does. */
    private suspend fun estimateStart(
        src: FrameSource,
        project: StickerProject,
        settings: AnimatedSettings,
        attempts: List<Attempt>,
    ): Int {
        val base = entries(src, settings, settings.fps)
        val count = base.size
        val n = min(SAMPLES, count)
        val rendered = ArrayList<Bitmap>(n)
        try {
            for (k in 0 until n) {
                currentCoroutineContext().ensureActive()
                val i = if (n == 1) 0 else k * (count - 1) / (n - 1)
                val raw = src.frameAt(base[i].sourceMs) ?: continue
                rendered += StickerRenderer.render(project, raw)
                raw.recycle()
            }
            if (rendered.isEmpty()) return 0
            val budget = WhatsAppLimits.MAX_ANIMATED_BYTES * 0.9
            for ((index, attempt) in attempts.withIndex()) {
                val perFrame = rendered.sumOf { StickerEncoder.compress(it, attempt.quality).size.toLong() } / rendered.size
                val frames = entries(src, settings, attempt.fps).size
                if (perFrame * frames + 1024 <= budget) return index
            }
            return attempts.size - 1
        } finally {
            rendered.forEach { it.recycle() }
        }
    }

    private suspend fun encodeOnce(
        src: FrameSource,
        project: StickerProject,
        settings: AnimatedSettings,
        attempt: Attempt,
        onProgress: (Float) -> Unit,
    ): ByteArray {
        val entries = entries(src, settings, attempt.fps)
        val frameCount = entries.size

        val frames = ArrayList<AnimatedWebpMuxer.Frame>(frameCount)
        var previous: ByteArray? = null
        var previousTime = Long.MIN_VALUE
        for (i in 0 until frameCount) {
            currentCoroutineContext().ensureActive()
            val time = entries[i].sourceMs
            // A duplicated frame is the same picture again: reuse the compressed data.
            val raw = if (time == previousTime && previous != null) null else src.frameAt(time)
            val data: ByteArray = if (raw == null) {
                previous ?: throw IOException("No frame at $time ms")
            } else {
                val rendered = StickerRenderer.render(project, raw)
                raw.recycle()
                val bytes = StickerEncoder.compress(rendered, attempt.quality)
                rendered.recycle()
                bytes
            }
            previous = data
            previousTime = time
            frames += AnimatedWebpMuxer.Frame(data, entries[i].durationMs.coerceAtLeast(WhatsAppLimits.MIN_FRAME_DURATION_MS))
            onProgress((i + 1).toFloat() / frameCount)
        }
        return AnimatedWebpMuxer.mux(frames)
    }
}
