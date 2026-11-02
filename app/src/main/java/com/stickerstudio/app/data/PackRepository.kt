package com.stickerstudio.app.data

import android.content.Context
import android.graphics.BitmapFactory
import com.stickerstudio.app.core.WhatsAppLimits
import com.stickerstudio.app.sticker.StickerEncoder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Raised when an operation would break a WhatsApp pack rule. The UI maps [code] to a message. */
class PackRuleException(val code: Code, val limit: Int = 0) : Exception(code.name) {
    enum class Code { PACK_NOT_FOUND, PACK_FULL, MIXED_ANIMATION, SAVE_FAILED }
}

/** Where user data lives. Everything is inside filesDir, so it survives app updates. */
class AppPaths(context: Context) {
    val packs: File = File(context.filesDir, "packs").apply { mkdirs() }
    val sources: File = File(context.filesDir, "sources").apply { mkdirs() }

    fun packDir(packId: String): File = File(packs, packId).apply { mkdirs() }
    fun source(name: String): File = File(sources, name)
}

class PackRepository(context: Context, db: AppDatabase) {

    private val dao = db.dao()
    private val database = db

    /** Explicit Runnable avoids the Runnable/Callable overload ambiguity of runInTransaction. */
    private fun tx(block: () -> Unit) = database.runInTransaction(Runnable { block() })
    val paths = AppPaths(context)

    fun observePacks(): Flow<List<PackEntity>> = dao.observePacks()
    fun observePack(id: String): Flow<PackEntity?> = dao.observePack(id)
    fun observeStickers(packId: String): Flow<List<StickerEntity>> = dao.observeStickers(packId)
    fun observeRecent(limit: Int): Flow<List<StickerEntity>> = dao.observeRecent(limit)
    fun observeCounts(): Flow<List<PackCount>> = dao.observeCounts()
    fun observeDrafts(): Flow<List<DraftEntity>> = dao.observeDrafts()

    fun stickerFile(sticker: StickerEntity): File = File(paths.packDir(sticker.packId), sticker.fileName)
    fun trayFile(pack: PackEntity): File? = pack.trayFile?.let { File(paths.packDir(pack.id), it) }

    // ------------------------------------------------------------------ packs

    suspend fun createPack(name: String, publisher: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertPack(
            PackEntity(
                id = id, name = name.trim(), publisher = publisher.trim(), trayFile = null,
                trayStickerId = null, animated = false, imageDataVersion = 1, createdAt = now, updatedAt = now,
            )
        )
        paths.packDir(id)
        id
    }

    suspend fun updatePackInfo(id: String, name: String, publisher: String) = withContext(Dispatchers.IO) {
        val pack = dao.findPack(id) ?: return@withContext
        dao.updatePack(
            pack.copy(
                name = name.trim(), publisher = publisher.trim(),
                imageDataVersion = pack.imageDataVersion + 1, updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deletePack(id: String) = withContext(Dispatchers.IO) {
        dao.stickersOf(id).forEach { s -> s.sourceFile?.let { paths.source(it).delete() } }
        dao.deletePack(id)   // stickers are removed by the foreign key cascade
        File(paths.packs, id).deleteRecursively()
    }

    // ------------------------------------------------------------------ stickers

    /** Adds a sticker file ([webp] is an already validated WhatsApp-ready WebP). */
    suspend fun addSticker(
        packId: String,
        webp: ByteArray,
        animated: Boolean,
        emojis: String,
        sourceFile: String?,
        projectJson: String?,
    ): StickerEntity = withContext(Dispatchers.IO) {
        val pack = dao.findPack(packId) ?: throw PackRuleException(PackRuleException.Code.PACK_NOT_FOUND)
        val existing = dao.stickersOf(packId)
        if (existing.size >= WhatsAppLimits.MAX_STICKERS) {
            throw PackRuleException(PackRuleException.Code.PACK_FULL, WhatsAppLimits.MAX_STICKERS)
        }

        val id = UUID.randomUUID().toString()
        val fileName = "$id.webp"
        writeAtomically(File(paths.packDir(packId), fileName), webp)

        val now = System.currentTimeMillis()
        val sticker = StickerEntity(
            id = id, packId = packId, fileName = fileName, emojis = normalizeEmojis(emojis),
            position = (existing.maxOfOrNull { it.position } ?: -1) + 1, animated = animated,
            sourceFile = sourceFile, projectJson = projectJson, createdAt = now,
        )
        tx {
            dao.insertSticker(sticker)
            dao.updatePack(pack.copy(animated = pack.animated || animated, imageDataVersion = pack.imageDataVersion + 1, updatedAt = now))
        }
        refreshTray(packId)
        sticker
    }

    /** Replaces the image of one sticker, keeping its position and the rest of the pack. */
    suspend fun replaceSticker(
        stickerId: String,
        webp: ByteArray,
        animated: Boolean,
        emojis: String?,
        sourceFile: String?,
        projectJson: String?,
    ): Unit = withContext(Dispatchers.IO) {
        val old = dao.findSticker(stickerId) ?: return@withContext
        val pack = dao.findPack(old.packId) ?: throw PackRuleException(PackRuleException.Code.PACK_NOT_FOUND)
        val others = dao.stickersOf(old.packId).filter { it.id != stickerId }

        // A new file name means WhatsApp / image caches can never show the old picture.
        val fileName = "${UUID.randomUUID()}.webp"
        writeAtomically(File(paths.packDir(old.packId), fileName), webp)

        val now = System.currentTimeMillis()
        tx {
            dao.updateSticker(
                old.copy(
                    fileName = fileName, animated = animated,
                    emojis = if (emojis != null) normalizeEmojis(emojis) else old.emojis,
                    sourceFile = sourceFile ?: old.sourceFile,
                    projectJson = projectJson ?: old.projectJson,
                )
            )
            dao.updatePack(pack.copy(animated = animated || others.any { it.animated }, imageDataVersion = pack.imageDataVersion + 1, updatedAt = now))
        }
        File(paths.packDir(old.packId), old.fileName).delete()
        File(File(paths.packDir(old.packId), "anim"), old.fileName).delete()
        if (sourceFile != null && old.sourceFile != null && old.sourceFile != sourceFile) {
            paths.source(old.sourceFile).delete()
        }
        refreshTray(old.packId)
    }

    suspend fun deleteSticker(stickerId: String) = withContext(Dispatchers.IO) {
        val s = dao.findSticker(stickerId) ?: return@withContext
        val pack = dao.findPack(s.packId)
        tx {
            dao.deleteSticker(stickerId)
            if (pack != null) {
                val remaining = dao.stickersOf(s.packId)
                dao.updatePack(
                    pack.copy(
                        animated = remaining.any { it.animated },
                        trayStickerId = if (pack.trayStickerId == stickerId) null else pack.trayStickerId,
                        imageDataVersion = pack.imageDataVersion + 1,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
        File(paths.packDir(s.packId), s.fileName).delete()
        s.sourceFile?.let { paths.source(it).delete() }
        refreshTray(s.packId)
    }

    /** Moves a sticker [delta] places (-1 = earlier, +1 = later). */
    suspend fun moveSticker(stickerId: String, delta: Int) = withContext(Dispatchers.IO) {
        val s = dao.findSticker(stickerId) ?: return@withContext
        val list = dao.stickersOf(s.packId).toMutableList()
        val from = list.indexOfFirst { it.id == stickerId }
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (from < 0 || from == to) return@withContext
        val item = list.removeAt(from)
        list.add(to, item)
        val pack = dao.findPack(s.packId)
        tx {
            list.forEachIndexed { index, st -> dao.setPosition(st.id, index) }
            if (pack != null) {
                dao.updatePack(pack.copy(imageDataVersion = pack.imageDataVersion + 1, updatedAt = System.currentTimeMillis()))
            }
        }
        refreshTray(s.packId)
    }

    suspend fun setEmojis(stickerId: String, emojis: String) = withContext(Dispatchers.IO) {
        val s = dao.findSticker(stickerId) ?: return@withContext
        val pack = dao.findPack(s.packId)
        tx {
            dao.updateSticker(s.copy(emojis = normalizeEmojis(emojis)))
            if (pack != null) dao.updatePack(pack.copy(imageDataVersion = pack.imageDataVersion + 1, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Applies a new order (ids from first to last). Unknown ids are ignored, missing ones keep their relative order at the end. */
    suspend fun reorder(packId: String, orderedIds: List<String>) = withContext(Dispatchers.IO) {
        val pack = dao.findPack(packId) ?: return@withContext
        val current = dao.stickersOf(packId)
        val known = current.map { it.id }.toSet()
        val head = orderedIds.filter { it in known }.distinct()
        val finalOrder = head + current.map { it.id }.filter { it !in head }
        if (finalOrder == current.map { it.id }) return@withContext
        tx {
            finalOrder.forEachIndexed { index, id -> dao.setPosition(id, index) }
            dao.updatePack(pack.copy(imageDataVersion = pack.imageDataVersion + 1, updatedAt = System.currentTimeMillis()))
        }
        refreshTray(packId)
    }

    /** Sets the same emojis on many stickers at once. */
    suspend fun setEmojisBulk(stickerIds: Collection<String>, emojis: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeEmojis(emojis)
        val touchedPacks = HashSet<String>()
        tx {
            stickerIds.forEach { id ->
                val s = dao.findSticker(id) ?: return@forEach
                dao.updateSticker(s.copy(emojis = normalized))
                touchedPacks += s.packId
            }
            touchedPacks.forEach { pid ->
                dao.findPack(pid)?.let {
                    dao.updatePack(it.copy(imageDataVersion = it.imageDataVersion + 1, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    /**
     * Copies stickers into another pack (the image and the editable source are duplicated, so
     * deleting one copy never affects the other). Returns how many were copied; stops at the 30 limit.
     */
    suspend fun copyStickersToPack(stickerIds: List<String>, targetPackId: String): Int = withContext(Dispatchers.IO) {
        val target = dao.findPack(targetPackId) ?: throw PackRuleException(PackRuleException.Code.PACK_NOT_FOUND)
        var count = dao.stickersOf(targetPackId).size
        var nextPos = (dao.stickersOf(targetPackId).maxOfOrNull { it.position } ?: -1) + 1
        var copied = 0
        var anyAnimated = false
        val inserts = ArrayList<StickerEntity>()
        for (sid in stickerIds) {
            if (count >= WhatsAppLimits.MAX_STICKERS) break
            val s = dao.findSticker(sid) ?: continue
            val src = File(paths.packDir(s.packId), s.fileName)
            if (!src.exists()) continue
            val newId = UUID.randomUUID().toString()
            val newName = "$newId.webp"
            src.copyTo(File(paths.packDir(targetPackId), newName), overwrite = true)
            val newSource = s.sourceFile?.let { old ->
                val f = paths.source(old)
                if (f.exists()) {
                    val ext = old.substringAfterLast('.', "")
                    val name = if (ext.isEmpty()) UUID.randomUUID().toString() else "${UUID.randomUUID()}.$ext"
                    f.copyTo(paths.source(name), overwrite = true)
                    name
                } else null
            }
            inserts += s.copy(
                id = newId, packId = targetPackId, fileName = newName, position = nextPos++,
                sourceFile = newSource, createdAt = System.currentTimeMillis(),
            )
            anyAnimated = anyAnimated || s.animated
            count++
            copied++
        }
        if (copied > 0) {
            tx {
                inserts.forEach { dao.insertSticker(it) }
                dao.updatePack(
                    target.copy(
                        animated = target.animated || anyAnimated,
                        imageDataVersion = target.imageDataVersion + 1,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            refreshTray(targetPackId)
        }
        copied
    }

    /** Copy then delete the originals (only the ones that were actually copied). */
    suspend fun moveStickersToPack(stickerIds: List<String>, targetPackId: String): Int {
        val room = WhatsAppLimits.MAX_STICKERS - stickersOf(targetPackId).size
        val ids = stickerIds.take(room.coerceAtLeast(0))
        val copied = copyStickersToPack(ids, targetPackId)
        ids.take(copied).forEach { deleteSticker(it) }
        return copied
    }

    /** Full copy of a pack (stickers, order, emojis and editable sources) named "<name> (copy)". */
    suspend fun duplicatePack(id: String, copySuffix: String): String? = withContext(Dispatchers.IO) {
        val pack = dao.findPack(id) ?: return@withContext null
        val newId = createPack("${pack.name} $copySuffix", pack.publisher)
        val ids = dao.stickersOf(id).map { it.id }
        copyStickersToPack(ids, newId)
        // keep the same tray choice when possible
        val newStickers = dao.stickersOf(newId)
        val old = dao.stickersOf(id)
        val idx = old.indexOfFirst { it.id == pack.trayStickerId }
        if (idx in newStickers.indices) setTraySticker(newId, newStickers[idx].id)
        newId
    }

    suspend fun setTraySticker(packId: String, stickerId: String?) = withContext(Dispatchers.IO) {
        val pack = dao.findPack(packId) ?: return@withContext
        dao.updatePack(pack.copy(trayStickerId = stickerId))
        refreshTray(packId)
    }

    suspend fun findSticker(id: String): StickerEntity? = withContext(Dispatchers.IO) { dao.findSticker(id) }
    suspend fun findPack(id: String): PackEntity? = withContext(Dispatchers.IO) { dao.findPack(id) }
    suspend fun stickersOf(packId: String): List<StickerEntity> = withContext(Dispatchers.IO) { dao.stickersOf(packId) }
    suspend fun allPacks(): List<PackEntity> = withContext(Dispatchers.IO) { dao.allPacks() }
    suspend fun countStickers(): Map<String, Int> = withContext(Dispatchers.IO) {
        dao.allPacks().associate { it.id to dao.stickersOf(it.id).size }
    }

    // ------------------------------------------------------------------ drafts

    suspend fun saveDraft(draft: DraftEntity) = withContext(Dispatchers.IO) { dao.upsertDraft(draft) }
    suspend fun findDraft(id: String): DraftEntity? = withContext(Dispatchers.IO) { dao.findDraft(id) }

    suspend fun deleteDraft(id: String, deleteSource: Boolean) = withContext(Dispatchers.IO) {
        val d = dao.findDraft(id)
        dao.deleteDraft(id)
        if (deleteSource) d?.sourceFile?.let { paths.source(it).delete() }
    }

    // ------------------------------------------------------------------ tray icon

    /** Rebuilds the 96x96 tray icon from the chosen (or first) sticker. Blocking, call on IO. */
    private fun refreshTray(packId: String) {
        val pack = dao.findPack(packId) ?: return
        val stickers = dao.stickersOf(packId)
        val dir = paths.packDir(packId)
        val source = stickers.firstOrNull { it.id == pack.trayStickerId } ?: stickers.firstOrNull()

        if (source == null) {
            pack.trayFile?.let { File(dir, it).delete() }
            dao.updatePack(pack.copy(trayFile = null, imageDataVersion = pack.imageDataVersion + 1))
            return
        }
        // For animated stickers BitmapFactory decodes the first frame.
        val bitmap = BitmapFactory.decodeFile(File(dir, source.fileName).path) ?: return
        val tray = StickerEncoder.createTray(bitmap)
        bitmap.recycle()

        val name = "tray_${System.currentTimeMillis()}.${tray.extension}"
        writeAtomically(File(dir, name), tray.bytes)
        pack.trayFile?.let { File(dir, it).delete() }
        dao.updatePack(pack.copy(trayFile = name, imageDataVersion = pack.imageDataVersion + 1))
    }

    // ------------------------------------------------------------------ helpers

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw PackRuleException(PackRuleException.Code.SAVE_FAILED)
        }
    }

    companion object {
        /** Keeps at most [WhatsAppLimits.MAX_EMOJI] emoji "characters" (grapheme clusters). */
        fun normalizeEmojis(raw: String): String {
            val trimmed = raw.filterNot { it.isWhitespace() || it == ',' }
            if (trimmed.isEmpty()) return "😀"
            return splitEmojis(trimmed).take(WhatsAppLimits.MAX_EMOJI).joinToString("")
        }

        fun splitEmojis(text: String): List<String> {
            val iterator = java.text.BreakIterator.getCharacterInstance()
            iterator.setText(text)
            val result = mutableListOf<String>()
            var start = iterator.first()
            var end = iterator.next()
            while (end != java.text.BreakIterator.DONE) {
                result += text.substring(start, end)
                start = end
                end = iterator.next()
            }
            return result
        }
    }
}
