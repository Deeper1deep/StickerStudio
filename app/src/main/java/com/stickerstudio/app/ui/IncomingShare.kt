package com.stickerstudio.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Files other apps hand to us: "Share" of pictures / GIFs / videos, or opening a `.wastickers` /
 * `.zip` pack. MainActivity puts them here; the home screen picks them up.
 */
object IncomingShare {
    class Item(val media: List<Uri>, val packs: List<Uri>) {
        val isEmpty: Boolean get() = media.isEmpty() && packs.isEmpty()
    }

    val pending = MutableStateFlow<Item?>(null)

    fun consume() { pending.value = null }

    fun handle(context: Context, intent: Intent?) {
        val item = from(context, intent) ?: return
        if (!item.isEmpty) pending.value = item
    }

    fun from(context: Context, intent: Intent?): Item? {
        intent ?: return null
        val uris = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
            else -> return null
        }
        if (uris.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
        }
        val (packs, media) = uris.partition { isPackFile(context, it) }
        return Item(media, packs)
    }

    /** A `.wastickers` or `.zip` file, judged by its name (or MIME type when the name is hidden). */
    fun isPackFile(context: Context, uri: Uri): Boolean {
        val name = displayName(context, uri)?.lowercase() ?: uri.lastPathSegment.orEmpty().lowercase()
        if (name.endsWith(".wastickers") || name.endsWith(".zip")) return true
        val type = try { context.contentResolver.getType(uri).orEmpty().lowercase() } catch (e: Exception) { "" }
        if (type.contains("wastickers") || type == "application/zip" || type == "application/x-zip-compressed") return true
        // A generic file with no useful name: a pack is a ZIP, which starts with "PK\u0003\u0004".
        if (type == "application/octet-stream" || type.isEmpty()) {
            return try {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    val b = ByteArray(4)
                    s.read(b) == 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
                } ?: false
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
