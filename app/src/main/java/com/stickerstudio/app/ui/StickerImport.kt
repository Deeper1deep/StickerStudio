package com.stickerstudio.app.ui

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.PackRuleException
import com.stickerstudio.app.sticker.BitmapLoader
import com.stickerstudio.app.sticker.ProjectJson
import com.stickerstudio.app.sticker.StickerEncoder
import com.stickerstudio.app.sticker.StickerProject
import com.stickerstudio.app.sticker.StickerRenderer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of importing pictures without opening the editor. */
class ImportReport(val added: Int, val failed: Int, @StringRes val stoppedBecause: Int?)

object StickerImport {

    /** Message for an exception raised while saving into a pack. */
    @StringRes
    fun messageFor(e: PackRuleException): Int = when (e.code) {
        PackRuleException.Code.PACK_FULL -> R.string.err_pack_full
        PackRuleException.Code.MIXED_ANIMATION -> R.string.err_mixed
        PackRuleException.Code.PACK_NOT_FOUND -> R.string.err_pack_missing
        PackRuleException.Code.SAVE_FAILED -> R.string.err_save_failed
    }

    /**
     * Adds every picture as a fitted 512x512 sticker. Unreadable pictures are skipped and
     * counted; a full pack stops the import with an explanation.
     */
    suspend fun addImages(context: Context, uris: List<Uri>, packId: String, replaceStickerId: String? = null): ImportReport =
        withContext(Dispatchers.Default) {
            val repo = context.container.repository
            var added = 0
            var failed = 0
            var stop: Int? = null

            for (uri in uris) {
                try {
                    val source = BitmapLoader.decode(context, uri)
                    val sourceName = BitmapLoader.savePng(repo.paths.sources, source)
                    val rendered = StickerRenderer.render(StickerProject(), source)
                    val webp = StickerEncoder.encodeStatic(rendered)
                    rendered.recycle()
                    source.recycle()
                    if (webp == null) {
                        repo.paths.source(sourceName).delete()
                        failed++
                        continue
                    }
                    val json = ProjectJson.encode(StickerProject())
                    if (replaceStickerId != null) {
                        repo.replaceSticker(replaceStickerId, webp, false, null, sourceName, json)
                    } else {
                        repo.addSticker(packId, webp, false, "😀", sourceName, json)
                    }
                    added++
                    if (replaceStickerId != null) break
                } catch (e: PackRuleException) {
                    stop = messageFor(e)
                    break
                } catch (e: IOException) {
                    failed++
                } catch (e: OutOfMemoryError) {
                    failed++
                }
            }
            ImportReport(added, failed, stop)
        }
}
