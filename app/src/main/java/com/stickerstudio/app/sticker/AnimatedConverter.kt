package com.stickerstudio.app.sticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
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

/** Animated GIF through android.graphics.Movie (works on every supported API level). */
class GifFrameSource(file: File) : FrameSource {
    @Suppress("DEPRECATION")
    private val movie: Movie = Movie.decodeFile(file.path) ?: throw IOException("Not a readable GIF")

    override val durationMs: Long = movie.duration().toLong()
    override val width: Int = movie.width()
    override val height: Int = movie.height()

    init {
        if (durationMs <= 0L || width <= 0 || height <= 0) throw IOException("GIF is not animated")
    }

    @Suppress("DEPRECATION")
    override fun frameAt(timeMs: Long): Bitmap? {
        movie.setTime((timeMs % durationMs).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        movie.draw(Canvas(bitmap), 0f, 0f)
        return bitmap
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

    override fun frameAt(timeMs: Long): Bitmap? =
        retriever.getFrameAtTime(
            timeMs.coerceIn(0L, durationMs) * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
        )

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

    fun open(file: File, isVideo: Boolean): FrameSource =
        if (isVideo) VideoFrameSource(file) else GifFrameSource(file)

    /** Project used for every frame: fit or cover, plus an optional caption. */
    fun projectFor(src: FrameSource, settings: AnimatedSettings): StickerProject {
        val layers = if (settings.caption.isBlank()) emptyList() else listOf(
            Layer(
                id = "caption", kind = LayerKind.TEXT, text = settings.caption, color = Colors.WHITE,
                size = 64f, strokeColor = Colors.BLACK, strokeWidth = 5f, x = 256f, y = 440f,
            )
        )
        return StickerProject(
            xform = Xform(scale = if (settings.cover) StickerRenderer.coverScale(src.width, src.height) else 1f),
            layers = layers,
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
                for ((index, attempt) in attempts.withIndex()) {
                    val bytes = encodeOnce(src, project, settings, attempt) { p ->
                        onProgress((index + p) / attempts.size)
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

    private suspend fun encodeOnce(
        src: FrameSource,
        project: StickerProject,
        settings: AnimatedSettings,
        attempt: Attempt,
        onProgress: (Float) -> Unit,
    ): ByteArray {
        val frameDuration = (1000f / attempt.fps).roundToInt().coerceAtLeast(WhatsAppLimits.MIN_FRAME_DURATION_MS)
        val available = max(0L, src.durationMs - settings.startMs)
        val totalMs = min(min(settings.durationMs, WhatsAppLimits.MAX_ANIMATED_DURATION_MS.toLong()), max(available, frameDuration * 2L))
        val frameCount = max(2, (totalMs / frameDuration).toInt())

        val frames = ArrayList<AnimatedWebpMuxer.Frame>(frameCount)
        var previous: ByteArray? = null
        for (i in 0 until frameCount) {
            currentCoroutineContext().ensureActive()
            val time = settings.startMs + i.toLong() * frameDuration
            val raw = src.frameAt(time)
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
            frames += AnimatedWebpMuxer.Frame(data, frameDuration)
            onProgress((i + 1).toFloat() / frameCount)
        }
        return AnimatedWebpMuxer.mux(frames)
    }
}
