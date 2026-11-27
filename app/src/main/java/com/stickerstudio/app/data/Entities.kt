package com.stickerstudio.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A sticker pack. [id] is also the WhatsApp "sticker pack identifier" (a UUID: letters,
 * digits and dashes only, which satisfies WhatsApp's character rules).
 */
@Entity(tableName = "packs")
data class PackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val publisher: String,
    /** File name (inside the pack folder) of the 96x96 tray icon, null while the pack is empty. */
    val trayFile: String?,
    /** Sticker chosen by the user as pack icon, null = first sticker. */
    val trayStickerId: String?,
    /** True when the pack contains animated stickers (static ones are wrapped at export). */
    val animated: Boolean,
    /** Sent to WhatsApp as image_data_version; must change whenever any image changes. */
    val imageDataVersion: Int,
    /** Where the pack came from (a [PackSource] name). Stored, never guessed. */
    val source: String,
    /** Fingerprint of the file the pack was imported from ([com.stickerstudio.app.whatsapp.PackArchive.fingerprint]); null for packs made in the app. */
    val importHash: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "stickers",
    foreignKeys = [
        ForeignKey(
            entity = PackEntity::class,
            parentColumns = ["id"],
            childColumns = ["packId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("packId")],
)
data class StickerEntity(
    @PrimaryKey val id: String,
    val packId: String,
    /** WebP file inside the pack folder. */
    val fileName: String,
    /** Emojis WhatsApp associates with the sticker, concatenated (1 to 3). */
    val emojis: String,
    val position: Int,
    val animated: Boolean,
    /** Working source image / GIF / video kept in filesDir/sources so the sticker can be re-edited. */
    val sourceFile: String?,
    /** ProjectJson (static) or AnimatedSettings JSON (animated). */
    val projectJson: String?,
    val createdAt: Long,
)

/**
 * The optional audio of ONE sticker (sound stickers). A separate table: stickers without audio have
 * no row here, so existing stickers are exactly as before. Deleted together with the sticker
 * (cascade); the audio file itself is removed by [AudioStorage.sweep].
 */
@Entity(
    tableName = "sticker_audio",
    foreignKeys = [
        ForeignKey(
            entity = StickerEntity::class,
            parentColumns = ["id"],
            childColumns = ["stickerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class StickerAudioEntity(
    @PrimaryKey val stickerId: String,
    /** File inside the app's audio folder. */
    val fileName: String,
    val durationMs: Long,
    /** An [com.stickerstudio.app.audio.AudioSourceKind] name. */
    val sourceKind: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val createdAt: Long,
)

/** Unfinished editing session kept so the user can come back to it. */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val id: String,
    val targetPackId: String?,
    val sourceFile: String?,
    val projectJson: String,
    val updatedAt: Long,
)

data class PackCount(
    @ColumnInfo(name = "packId") val packId: String,
    @ColumnInfo(name = "cnt") val count: Int,
)
