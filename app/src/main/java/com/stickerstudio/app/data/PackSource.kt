package com.stickerstudio.app.data

import com.stickerstudio.app.core.WhatsAppLimits

/**
 * Where a pack came from. Stored on the pack itself, never guessed from its name or contents.
 *
 * - [CREATED]: the user made the pack inside the app. WhatsApp's 30-sticker limit applies,
 *   because the pack is meant to be sent to WhatsApp.
 * - [IMPORTED]: the pack came from an external file (.wastickers / .zip). Sticker Studio keeps
 *   every sticker of the file (up to [PackSourceRules.IMPORTED_MAX_STICKERS]); WhatsApp's limit
 *   is checked only when the pack is actually sent to WhatsApp, never at import or storage time.
 * - [UNKNOWN]: packs that already existed before sources were recorded. They are never guessed,
 *   never moved and never deleted; they simply stay where the user had them.
 */
enum class PackSource { CREATED, IMPORTED, UNKNOWN }

object PackSourceRules {

    /**
     * Storage limit for imported packs. This is NOT WhatsApp's limit: WhatsApp only allows
     * [WhatsAppLimits.MAX_STICKERS] stickers when a pack is exported to it. Inside Sticker
     * Studio an imported pack keeps every sticker of its file. The value matches the archive
     * reader's own entry cap ([com.stickerstudio.app.whatsapp.PackArchive.MAX_ENTRIES]).
     */
    const val IMPORTED_MAX_STICKERS = 400

    /** How many stickers a pack of this source may hold inside Sticker Studio. */
    fun stickerLimit(source: PackSource): Int =
        if (source == PackSource.IMPORTED) IMPORTED_MAX_STICKERS else WhatsAppLimits.MAX_STICKERS

    /** Parses the stored column; a missing or unrecognised value is [PackSource.UNKNOWN]. */
    fun of(raw: String?): PackSource =
        PackSource.values().firstOrNull { it.name == raw } ?: PackSource.UNKNOWN

    /** "My packs": everything that was not imported (created now, and legacy packs kept as-is). */
    fun isMine(source: PackSource): Boolean = source != PackSource.IMPORTED

    fun isImported(source: PackSource): Boolean = source == PackSource.IMPORTED

    /** The packs of "My packs": never contains an imported pack. */
    fun myPacks(packs: List<PackEntity>): List<PackEntity> = packs.filter { isMine(it.packSource) }

    /** The packs of "Imported packs": never contains a created or legacy pack. */
    fun importedPacks(packs: List<PackEntity>): List<PackEntity> = packs.filter { isImported(it.packSource) }
}

/** The pack's source, parsed from its stored column. */
val PackEntity.packSource: PackSource get() = PackSourceRules.of(source)
