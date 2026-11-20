package com.stickerstudio.app.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stickerstudio.app.data.AppDatabase
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.data.StickerEntity
import java.io.File
import java.io.FileNotFoundException

/**
 * The official WhatsApp sticker integration: WhatsApp reads the pack metadata and the image
 * files through this exported provider after the user confirms "Add to WhatsApp".
 * Contract: https://github.com/WhatsApp/stickers (Android sample app).
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var matcher: UriMatcher
    private lateinit var authority: String

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        authority = authorityFor(ctx.packageName)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, METADATA, CODE_METADATA)
            addURI(authority, "$METADATA/*", CODE_METADATA_SINGLE)
            addURI(authority, "$STICKERS/*", CODE_STICKERS)
            addURI(authority, "$STICKERS_ASSET/*/*", CODE_ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val dao = AppDatabase.get(requireNotNull(context)).dao()
        return when (matcher.match(uri)) {
            CODE_METADATA -> packsCursor(uri, dao.allPacks().filter { dao.stickersOf(it.id).isNotEmpty() })
            CODE_METADATA_SINGLE -> {
                val id = uri.lastPathSegment ?: ""
                val part = EphemeralPacks.get(id)
                if (part != null) partCursor(uri, part) else packsCursor(uri, listOfNotNull(dao.findPack(id)))
            }
            CODE_STICKERS -> {
                val id = uri.lastPathSegment ?: ""
                val part = EphemeralPacks.get(id)
                stickersCursor(uri, part?.stickers ?: dao.stickersOf(id))
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    private fun packsCursor(uri: Uri, packs: List<PackEntity>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
                "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
                "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website", "image_data_version",
                "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
            )
        )
        for (p in packs) {
            cursor.newRow()
                .add(p.id)
                .add(p.name)
                .add(p.publisher)
                .add(p.trayFile ?: "")
                .add("")
                .add("")
                .add("")
                .add("")
                .add("")
                .add("")
                .add(p.imageDataVersion.toString())
                .add(0)
                .add(if (p.animated) 1 else 0)
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    /** Metadata row for one temporary split part (same column contract as [packsCursor]). */
    private fun partCursor(uri: Uri, part: EphemeralPacks.Part): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
                "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
                "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website", "image_data_version",
                "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
            )
        )
        cursor.newRow()
            .add(part.id)
            .add(part.name)
            .add(part.publisher)
            .add(part.trayName)
            .add("")
            .add("")
            .add("")
            .add("")
            .add("")
            .add("")
            .add(part.imageDataVersion.toString())
            .add(0)
            .add(if (part.animated) 1 else 0)
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    private fun stickersCursor(uri: Uri, stickers: List<StickerEntity>): Cursor {
        val cursor = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        for (s in stickers) cursor.addRow(arrayOf<Any>(s.fileName, s.emojis, ""))
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != CODE_ASSET) return null
        val segments = uri.pathSegments
        if (segments.size != 3) throw FileNotFoundException("Bad asset uri")
        val packId = segments[1]
        val name = segments[2]

        val ctx = requireNotNull(context)
        val packsRoot = File(ctx.filesDir, "packs").canonicalFile

        // Temporary split part: serve the ORIGINAL pack's files, resolved in place.
        val part = EphemeralPacks.get(packId)
        if (part != null) {
            val partDir = part.dir.canonicalFile
            val partFile = if (name == part.trayName && part.tray != null) {
                part.tray.canonicalFile
            } else {
                File(partDir, name).canonicalFile
            }
            if (!partFile.path.startsWith(partDir.path + File.separator)) {
                throw FileNotFoundException("Illegal path")
            }
            if (!partFile.isFile) throw FileNotFoundException("No such sticker file")
            val sticker = part.stickers.firstOrNull { it.fileName == name }
            val served = if (sticker != null) ExportFiles.resolve(partDir, sticker, part.animated) else partFile
            return AssetFileDescriptor(
                ParcelFileDescriptor.open(served, ParcelFileDescriptor.MODE_READ_ONLY), 0L, AssetFileDescriptor.UNKNOWN_LENGTH,
            )
        }

        val file = File(File(packsRoot, packId), name).canonicalFile
        // Path traversal guard: the resolved file must stay inside filesDir/packs/<packId>.
        if (!file.path.startsWith(File(packsRoot, packId).canonicalPath + File.separator)) {
            throw FileNotFoundException("Illegal path")
        }
        if (!file.isFile) throw FileNotFoundException("No such sticker file")
        val dao = AppDatabase.get(ctx).dao()
        val pack = dao.findPack(packId)
        val sticker = dao.stickersOf(packId).firstOrNull { it.fileName == name }
        // Static stickers of a pack that also has animated ones are served as animated WebPs.
        val served = if (pack != null && sticker != null) {
            ExportFiles.resolve(File(packsRoot, packId), sticker, pack.animated)
        } else {
            file
        }
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(served, ParcelFileDescriptor.MODE_READ_ONLY), 0L, AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        CODE_METADATA -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        CODE_METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        CODE_ASSET -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    companion object {
        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"
        private const val CODE_METADATA = 1
        private const val CODE_METADATA_SINGLE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_ASSET = 4

        /** Must match android:authorities in the manifest (`${applicationId}.stickercontentprovider`). */
        fun authorityFor(packageName: String): String = "$packageName.stickercontentprovider"
    }
}
