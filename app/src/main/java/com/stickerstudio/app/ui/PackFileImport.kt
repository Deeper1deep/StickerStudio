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
 * Stickers are stored even when they miss WhatsApp's export spec (size or dimensions):
 * keeping the file whole matters more here, and the export validator reports any out-of-spec
 * sticker honestly when the pack is actually sent to WhatsApp. Packs mixing WebP stickers and
 * plain pictures import every entry in the file's order. Archive entries beyond the reader's
 * per-file safety cap are counted in [PackImportResult.skipped].
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

        // WebP stickers and plain pictures together, in the file's own order: nothing is
        // left behind just because the pack mixes formats.
        val items = archive.stickers
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
        // Oversized archive entries were dropped by the reader; they count as skipped, never vanish.
        val prepared = ArrayList<Prepared>(items.size)
        var skipped = archive.skippedOversized
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

    /**
     * Decodes one archive entry into a storable sticker. Nothing that is really a WebP or a
     * decodable picture is ever dropped: valid WhatsApp WebP files are kept byte for byte,
     * out-of-spec WebP files (wrong size, too big for WhatsApp) are kept too - the export
     * validator reports them honestly instead of the import silently losing them - and plain
     * pictures are fitted into proper stickers.
     */
    private fun prepare(context: Context, item: ArchiveSticker): Prepared? {
        val repo = context.container.repository
        val emojis = item.emojis ?: "😀"
        val info = WebpParser.parse(item.bytes)

        if (info != null) {
            // Animated stickers are always kept byte for byte.
            if (info.animated) {
                return Prepared(item.bytes, true, emojis, null, null)
            }
            val inSpec = info.width == WhatsAppLimits.STICKER_SIZE && info.height == WhatsAppLimits.STICKER_SIZE &&
                item.bytes.size <= WhatsAppLimits.MAX_STATIC_BYTES
            val source = BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size)
            if (source != null) {
                if (inSpec) {
                    // keep an editable copy of the picture so the sticker can be edited later
                    val sourceName = BitmapLoader.savePng(repo.paths.sources, source).also { source.recycle() }
                    return Prepared(item.bytes, false, emojis, sourceName, ProjectJson.encode(StickerProject()))
                }
                // a static WebP that is not 512x512 / too big: fit it into a proper sticker
                val rendered = StickerRenderer.render(StickerProject(), source)
                val webp = StickerEncoder.encodeStatic(rendered)
                rendered.recycle()
                if (webp != null) {
                    val sourceName = BitmapLoader.savePng(repo.paths.sources, source)
                    source.recycle()
                    return Prepared(webp, false, emojis, sourceName, ProjectJson.encode(StickerProject()))
                }
                source.recycle()
            }
            // undecodable or unfixable: keep the original WebP instead of dropping the sticker
            return Prepared(item.bytes, false, emojis, null, null)
        }

        // Not a WebP: a plain picture gets fitted into a proper sticker.
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
