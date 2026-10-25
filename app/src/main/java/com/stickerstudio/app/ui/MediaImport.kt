package com.stickerstudio.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.stickerstudio.app.container
import com.stickerstudio.app.data.PackRuleException
import com.stickerstudio.app.sticker.AnimatedConverter
import com.stickerstudio.app.sticker.AnimatedSettings
import com.stickerstudio.app.sticker.BitmapLoader
import com.stickerstudio.app.sticker.ConvertOutcome
import com.stickerstudio.app.sticker.GifFrameSource
import com.stickerstudio.app.sticker.MediaKind
import com.stickerstudio.app.sticker.ProjectJson
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picking pictures, GIFs and videos: one entry point for a single file and for a whole batch. */
object MediaImport {

    sealed interface Prepared {
        class Picture(val name: String) : Prepared
        class Animated(val name: String, val isVideo: Boolean) : Prepared
        data object Failed : Prepared
    }

    /** What the file really is (header bytes first, then MIME type, then file name). */
    fun kindOf(context: Context, uri: Uri): MediaKind {
        val header = try {
            context.contentResolver.openInputStream(uri)?.use { i ->
                val buf = ByteArray(16)
                val n = i.read(buf)
                if (n > 0) buf.copyOf(n) else null
            }
        } catch (e: Exception) {
            null
        }
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) {
            name = null
        }
        if (name == null) name = uri.lastPathSegment
        return MediaKind.detect(header, context.contentResolver.getType(uri), name)
    }

    /**
     * Copies one picked file into the app and says which editor should open it. A GIF with a single
     * frame is not animated, so it is handled as a normal picture.
     */
    suspend fun prepareSingle(context: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val kind = kindOf(context, uri)
        val repo = context.container.repository
        if (kind.isAnimatedSource) {
            val name = try {
                BitmapLoader.copyRaw(context, uri, repo.paths.sources, kind.extension)
            } catch (e: Exception) {
                return@withContext Prepared.Failed
            }
            if (kind == MediaKind.GIF) {
                val animated = try {
                    GifFrameSource(repo.paths.source(name)).close()
                    true
                } catch (e: Exception) {
                    false
                }
                if (!animated) {
                    repo.paths.source(name).delete()
                    return@withContext picture(context, uri)
                }
            }
            Prepared.Animated(name, kind.isVideo)
        } else {
            picture(context, uri)
        }
    }

    private suspend fun picture(context: Context, uri: Uri): Prepared =
        importSourcePicture(context, uri)?.let { Prepared.Picture(it) } ?: Prepared.Failed

    /**
     * Adds many files at once: pictures are fitted, GIFs and videos are converted to animated
     * stickers with default settings (whole clips, WhatsApp allows at most 10 seconds). [onProgress] gets (finished, total).
     */
    suspend fun addMixed(
        context: Context,
        uris: List<Uri>,
        packId: String,
        onProgress: (Int, Int) -> Unit,
    ): ImportReport = withContext(Dispatchers.Default) {
        var added = 0
        var failed = 0
        var stop: Int? = null
        for ((i, uri) in uris.withIndex()) {
            if (stop != null) break
            onProgress(i, uris.size)
            val kind = kindOf(context, uri)
            val r = try {
                if (kind.isAnimatedSource) addOneAnimated(context, uri, kind, packId)
                else StickerImport.addImages(context, listOf(uri), packId)
            } catch (e: PackRuleException) {
                ImportReport(0, 0, StickerImport.messageFor(e))
            } catch (e: IOException) {
                ImportReport(0, 1, null)
            } catch (e: OutOfMemoryError) {
                ImportReport(0, 1, null)
            } catch (e: RuntimeException) {
                ImportReport(0, 1, null)
            }
            added += r.added
            failed += r.failed
            stop = r.stoppedBecause
        }
        onProgress(uris.size, uris.size)
        ImportReport(added, failed, stop)
    }

    private suspend fun addOneAnimated(context: Context, uri: Uri, kind: MediaKind, packId: String): ImportReport {
        val repo = context.container.repository
        val name = withContext(Dispatchers.IO) { BitmapLoader.copyRaw(context, uri, repo.paths.sources, kind.extension) }
        val file = repo.paths.source(name)
        val duration = try {
            AnimatedConverter.open(file, kind.isVideo).use { it.durationMs }
        } catch (e: Exception) {
            null
        }
        if (duration == null) {
            file.delete()
            // a one-frame GIF is just a picture
            return if (kind == MediaKind.GIF) StickerImport.addImages(context, listOf(uri), packId) else ImportReport(0, 1, null)
        }
        val settings = AnimatedSettings(durationMs = minOf(duration, MAX_BATCH_MS).coerceAtLeast(500L))
        return when (val out = AnimatedConverter.convert(file, kind.isVideo, settings) { }) {
            is ConvertOutcome.Success -> try {
                repo.addSticker(packId, out.bytes, true, "😀", name, ProjectJson.encode(settings))
                ImportReport(1, 0, null)
            } catch (e: PackRuleException) {
                file.delete()
                throw e
            }
            is ConvertOutcome.Failure -> {
                file.delete()
                ImportReport(0, 1, null)
            }
        }
    }

    private const val MAX_BATCH_MS = 10_000L   // WhatsApp's limit; shorter clips are kept whole
}
