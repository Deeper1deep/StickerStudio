package com.stickerstudio.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.core.WhatsAppLimits
import com.stickerstudio.app.data.PackRuleException
import com.stickerstudio.app.sticker.BitmapLoader
import com.stickerstudio.app.sticker.ProjectJson
import com.stickerstudio.app.sticker.StickerEncoder
import com.stickerstudio.app.sticker.StickerProject
import com.stickerstudio.app.sticker.StickerRenderer
import com.stickerstudio.app.sticker.WebpParser
import com.stickerstudio.app.whatsapp.ArchiveSticker
import com.stickerstudio.app.whatsapp.PackArchive
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** [packId] is null when nothing could be imported ([error] says why). */
class PackImportResult(val packId: String?, val added: Int, val skipped: Int, @StringRes val error: Int?)

/** Creates a new pack from a .wastickers / .zip file. */
object PackFileImport {

    suspend fun import(context: Context, uri: Uri): PackImportResult = withContext(Dispatchers.Default) {
        val c = context.container
        val repo = c.repository

        val archive = try {
            context.contentResolver.openInputStream(uri)?.use { PackArchive.read(it) }
        } catch (e: IOException) {
            null
        } catch (e: OutOfMemoryError) {
            return@withContext PackImportResult(null, 0, 0, R.string.err_memory)
        } ?: return@withContext PackImportResult(null, 0, 0, R.string.import_pack_invalid)

        val items = archive.webps.ifEmpty { archive.images }
        if (items.isEmpty()) return@withContext PackImportResult(null, 0, 0, R.string.import_pack_invalid)

        val fallbackName = uri.lastPathSegment.orEmpty().substringAfterLast('/').substringBeforeLast('.').trim()
        val name = (archive.title ?: fallbackName).ifBlank { context.getString(R.string.import_pack_default_name) }
            .take(WhatsAppLimits.MAX_NAME_LENGTH)
        val publisher = (archive.author ?: c.settings.defaultPublisher.first()).take(WhatsAppLimits.MAX_NAME_LENGTH)
        val packId = repo.createPack(name, publisher)

        var added = 0
        var skipped = 0
        for ((index, item) in items.withIndex()) {
            if (added >= WhatsAppLimits.MAX_STICKERS) { skipped += items.size - index; break }
            try {
                if (addOne(context, packId, item)) added++ else skipped++
            } catch (e: PackRuleException) {
                skipped += items.size - index
                break
            } catch (e: Exception) {
                skipped++
            } catch (e: OutOfMemoryError) {
                skipped++
            }
        }
        if (added == 0) {
            repo.deletePack(packId)
            return@withContext PackImportResult(null, 0, skipped, R.string.import_pack_invalid)
        }
        PackImportResult(packId, added, skipped, null)
    }

    /** True when the sticker was added. Valid WhatsApp WebP files are kept byte for byte. */
    private suspend fun addOne(context: Context, packId: String, item: ArchiveSticker): Boolean {
        val repo = context.container.repository
        val emojis = item.emojis ?: "😀"
        val info = WebpParser.parse(item.bytes)

        if (info != null) {
            val limit = if (info.animated) WhatsAppLimits.MAX_ANIMATED_BYTES else WhatsAppLimits.MAX_STATIC_BYTES
            val ok = info.width == WhatsAppLimits.STICKER_SIZE && info.height == WhatsAppLimits.STICKER_SIZE && item.bytes.size <= limit
            if (ok) {
                if (info.animated) {
                    repo.addSticker(packId, item.bytes, true, emojis, null, null)
                } else {
                    // keep an editable copy of the picture so the sticker can be edited later
                    val source = BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size)
                    val sourceName = source?.let { BitmapLoader.savePng(repo.paths.sources, it).also { _ -> it.recycle() } }
                    repo.addSticker(packId, item.bytes, false, emojis, sourceName, sourceName?.let { ProjectJson.encode(StickerProject()) })
                }
                return true
            }
            if (info.animated) return false   // an out-of-spec animation cannot be fixed here
        }
        // A picture, or a static WebP that is not 512x512 / too big: fit it into a proper sticker.
        val source = BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size) ?: return false
        val rendered = StickerRenderer.render(StickerProject(), source)
        val webp = StickerEncoder.encodeStatic(rendered)
        rendered.recycle()
        if (webp == null) { source.recycle(); return false }
        val sourceName = BitmapLoader.savePng(repo.paths.sources, source)
        source.recycle()
        repo.addSticker(packId, webp, false, emojis, sourceName, ProjectJson.encode(StickerProject()))
        return true
    }
}
