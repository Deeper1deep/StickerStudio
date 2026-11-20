package com.stickerstudio.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.core.WhatsAppLimits
import com.stickerstudio.app.data.PackRuleException
import com.stickerstudio.app.data.PackSource
import com.stickerstudio.app.data.PackSourceRules
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

/**
 * [packId] is null when nothing could be imported ([error] says why).
 * [duplicate] is true when the exact same file content was already imported: the existing pack
 * is kept ([packId] points to it) and no second copy was created. [updated] is true when an
 * existing imported pack was refreshed in place from a new file.
 */
class PackImportResult(
    val packId: String?,
    val added: Int,
    val skipped: Int,
    @StringRes val error: Int?,
    val duplicate: Boolean = false,
    val updated: Boolean = false,
)

/**
 * Imports a pack from a .wastickers / .zip file into the "Imported packs" section.
 *
 * Every usable sticker of the file is kept, in the file's own order, and the real count is
 * reported. The 30-sticker cap of [WhatsAppLimits.MAX_STICKERS] is WhatsApp's EXPORT limit and
 * is not applied here; storage is only bounded by [PackSourceRules.IMPORTED_MAX_STICKERS]. The
 * original file is never modified and the pack is never split into several packs.
 *
 * With [replacePackId] the given imported pack is refreshed in place (re-import / update):
 * same pack id, no copy. A pack created inside the app is never touched through this path.
 */
object PackFileImport {

    /** One sticker decoded and ready to be stored. */
    private class Prepared(
        val webp: ByteArray,
        val animated: Boolean,
        val emojis: String,
        val sourceName: String?,
        val projectJson: String?,
    )

    suspend fun import(context: Context, uri: Uri, replacePackId: String? = null): PackImportResult = withContext(Dispatchers.Default) {
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

        val fingerprint = PackArchive.fingerprint(archive)

        // Duplicate handling: importing the exact same file again never creates a second pack.
        if (replacePackId == null) {
            val existing = repo.findImportedByHash(fingerprint)
            if (existing != null) {
                return@withContext PackImportResult(existing.id, repo.stickersOf(existing.id).size, 0, null, duplicate = true)
            }
        } else {
            val current = repo.findPack(replacePackId)
            if (current?.importHash == fingerprint) {
                return@withContext PackImportResult(replacePackId, repo.stickersOf(replacePackId).size, 0, null, duplicate = true)
            }
        }

        // Decode everything first: a file that cannot be read changes nothing.
        val prepared = ArrayList<Prepared>(items.size)
        var skipped = 0
        for (item in items) {
            if (prepared.size >= PackSourceRules.IMPORTED_MAX_STICKERS) {
                skipped += items.size - prepared.size
                break
            }
            val p = try {
                prepare(context, item)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (p == null) skipped++ else prepared += p
        }
        if (prepared.isEmpty()) {
            return@withContext PackImportResult(null, 0, skipped, R.string.import_pack_invalid)
        }

        val fallbackName = uri.lastPathSegment.orEmpty().substringAfterLast('/').substringBeforeLast('.').trim()
        val name = (archive.title ?: fallbackName).ifBlank { context.getString(R.string.import_pack_default_name) }
            .take(WhatsAppLimits.MAX_NAME_LENGTH)
        val publisher = (archive.author ?: c.settings.defaultPublisher.first()).take(WhatsAppLimits.MAX_NAME_LENGTH)

        val updated: Boolean
        val packId: String
        if (replacePackId != null) {
            val pack = repo.findPack(replacePackId)
            // Re-import only ever refreshes an imported pack; a created pack is never touched.
            if (pack == null || PackSourceRules.of(pack.source) != PackSource.IMPORTED) {
                prepared.forEach { p -> p.sourceName?.let { repo.paths.source(it).delete() } }
                return@withContext PackImportResult(null, 0, skipped, R.string.import_pack_invalid)
            }
            repo.clearStickers(replacePackId)
            repo.updateImportedMeta(replacePackId, name, publisher, fingerprint)
            packId = replacePackId
            updated = true
        } else {
            packId = repo.createPack(name, publisher, PackSource.IMPORTED, fingerprint)
            updated = false
        }

        var added = 0
        for (p in prepared) {
            val ok = try {
                repo.addSticker(packId, p.webp, p.animated, p.emojis, p.sourceName, p.projectJson)
                true
            } catch (e: PackRuleException) {
                false
            } catch (e: Exception) {
                false
            }
            if (ok) added++ else {
                skipped++
                p.sourceName?.let { repo.paths.source(it).delete() }
            }
        }
        if (added == 0 && !updated) {
            repo.deletePack(packId)
            return@withContext PackImportResult(null, 0, skipped, R.string.import_pack_invalid)
        }
        PackImportResult(packId, added, skipped, null, updated = updated)
    }

    /** Decodes one archive entry into a storable sticker. Valid WhatsApp WebP files are kept byte for byte. */
    private fun prepare(context: Context, item: ArchiveSticker): Prepared? {
        val repo = context.container.repository
        val emojis = item.emojis ?: "😀"
        val info = WebpParser.parse(item.bytes)

        if (info != null) {
            val limit = if (info.animated) WhatsAppLimits.MAX_ANIMATED_BYTES else WhatsAppLimits.MAX_STATIC_BYTES
            val ok = info.width == WhatsAppLimits.STICKER_SIZE && info.height == WhatsAppLimits.STICKER_SIZE && item.bytes.size <= limit
            if (ok) {
                if (info.animated) {
                    return Prepared(item.bytes, true, emojis, null, null)
                }
                // keep an editable copy of the picture so the sticker can be edited later
                val source = BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size)
                val sourceName = source?.let { BitmapLoader.savePng(repo.paths.sources, it).also { _ -> it.recycle() } }
                return Prepared(item.bytes, false, emojis, sourceName, sourceName?.let { ProjectJson.encode(StickerProject()) })
            }
            if (info.animated) return null   // an out-of-spec animation cannot be fixed here
        }
        // A picture, or a static WebP that is not 512x512 / too big: fit it into a proper sticker.
        val source = BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size) ?: return null
        val rendered = StickerRenderer.render(StickerProject(), source)
        val webp = StickerEncoder.encodeStatic(rendered)
        rendered.recycle()
        if (webp == null) { source.recycle(); return null }
        val sourceName = BitmapLoader.savePng(repo.paths.sources, source)
        source.recycle()
        return Prepared(webp, false, emojis, sourceName, ProjectJson.encode(StickerProject()))
    }
}
