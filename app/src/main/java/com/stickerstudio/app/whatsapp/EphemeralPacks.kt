package com.stickerstudio.app.whatsapp

import com.stickerstudio.app.data.StickerEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Temporary packs that exist only in memory while a split export is running.
 * They are never written to the database, never listed in the app, and their files
 * are the original pack's own files, read in place. When the process ends, they are gone.
 */
object EphemeralPacks {

    /** One temporary part: a slice of a real pack, served to WhatsApp under its own id. */
    data class Part(
        val id: String,
        val name: String,
        val publisher: String,
        val trayName: String,
        val tray: File?,
        val animated: Boolean,
        val imageDataVersion: Int,
        /** This part's stickers, a contiguous slice of the original pack in the original order. */
        val stickers: List<StickerEntity>,
        /** Directory of the ORIGINAL pack (filesDir/packs/<original id>); files are read from here. */
        val dir: File,
    )

    private val parts = ConcurrentHashMap<String, Part>()

    fun put(part: Part) {
        parts[part.id] = part
    }

    fun get(id: String): Part? = parts[id]

    fun remove(id: String) {
        parts.remove(id)
    }

    fun clear() = parts.clear()
}
