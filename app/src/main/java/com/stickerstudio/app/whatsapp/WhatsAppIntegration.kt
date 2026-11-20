package com.stickerstudio.app.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.data.PackRepository
import com.stickerstudio.app.data.StickerEntity
import com.stickerstudio.app.sticker.WebpParser
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WhatsAppIntegration {

    const val PACKAGE_WHATSAPP = "com.whatsapp"
    const val PACKAGE_BUSINESS = "com.whatsapp.w4b"

    private const val ACTION_ENABLE = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"

    /** Which of WhatsApp / WhatsApp Business are installed (the manifest declares <queries> for both). */
    fun installedTargets(context: Context): List<String> =
        listOf(PACKAGE_WHATSAPP, PACKAGE_BUSINESS).filter { isInstalled(context, it) }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The official intent that asks WhatsApp to add a pack served by our content provider. */
    fun enableIntent(context: Context, pack: PackEntity, targetPackage: String): Intent =
        enableIntent(context, pack.id, pack.name, targetPackage)

    /** Same intent for a temporary split part ([EphemeralPacks]) that is not a stored pack. */
    fun enableIntent(context: Context, id: String, name: String, targetPackage: String): Intent =
        Intent(ACTION_ENABLE)
            .setPackage(targetPackage)
            .putExtra("sticker_pack_id", id)
            .putExtra("sticker_pack_authority", StickerContentProvider.authorityFor(context.packageName))
            .putExtra("sticker_pack_name", name)

    /** True if the pack is already added in [targetPackage]; false when unknown or on any error. */
    fun isPackAdded(context: Context, targetPackage: String, packId: String): Boolean {
        val uri = Uri.Builder()
            .scheme("content")
            .authority("$targetPackage.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", StickerContentProvider.authorityFor(context.packageName))
            .appendQueryParameter("identifier", packId)
            .build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Measures the real files of a pack and validates them against WhatsApp's rules. */
    suspend fun validate(repo: PackRepository, pack: PackEntity): List<PackIssue> = withContext(Dispatchers.IO) {
        val stickers = repo.stickersOf(pack.id)
        val checks = stickers.map { s -> checkSticker(repo, pack, s) }

        val trayFile = repo.trayFile(pack)
        val tray = if (trayFile == null || !trayFile.isFile) {
            null
        } else {
            val bytes = trayFile.readBytes()
            val dims = readTrayDimensions(trayFile)
            TrayCheck(true, bytes.size.toLong(), dims.first, dims.second)
        }
        PackValidator.validate(pack.name, pack.publisher, checks, tray)
    }

    private fun checkSticker(repo: PackRepository, pack: PackEntity, s: StickerEntity): StickerCheck {
        val file = ExportFiles.resolve(repo.paths.packDir(s.packId), s, pack.animated)
        if (!file.isFile) return StickerCheck(s.fileName, false, 0, null, 0)
        val bytes = file.readBytes()
        val emojiCount = PackRepository.splitEmojis(s.emojis).size
        return StickerCheck(s.fileName, true, bytes.size.toLong(), WebpParser.parse(bytes), emojiCount)
    }

    private fun readTrayDimensions(file: File): Pair<Int, Int> {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, opts)
        return opts.outWidth to opts.outHeight
    }

    /**
     * Writes a `.wastickers` file (a ZIP): `title.txt`, `author.txt`, the tray icon, numbered `.webp`
     * stickers and a `pack.json` with the emojis. Sticker Maker style apps and this app can import
     * it. It does NOT add the pack to WhatsApp by itself.
     */
    suspend fun writeZip(repo: PackRepository, pack: PackEntity, out: OutputStream) = withContext(Dispatchers.IO) {
        val stickers = repo.stickersOf(pack.id)
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("title.txt", pack.name.toByteArray(Charsets.UTF_8))
            put("author.txt", pack.publisher.toByteArray(Charsets.UTF_8))

            val json = StringBuilder()
            json.append("{\n  \"identifier\": ").append(quote(pack.id))
                .append(",\n  \"name\": ").append(quote(pack.name))
                .append(",\n  \"publisher\": ").append(quote(pack.publisher))
                .append(",\n  \"animated\": ").append(pack.animated)
                .append(",\n  \"stickers\": [")
            stickers.forEachIndexed { i, s ->
                val entryName = "${i + 1}.webp"
                put(entryName, ExportFiles.resolve(repo.paths.packDir(s.packId), s, pack.animated).readBytes())
                if (i > 0) json.append(',')
                json.append("\n    {\"file\": ").append(quote(entryName)).append(", \"emojis\": ").append(quote(s.emojis)).append('}')
            }
            json.append("\n  ]\n}\n")

            repo.trayFile(pack)?.takeIf { it.isFile }?.let { tray -> put("tray." + tray.extension, tray.readBytes()) }
            put("pack.json", json.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun quote(s: String): String = com.stickerstudio.app.core.MiniJson.stringify(s)
}
