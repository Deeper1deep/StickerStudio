package com.stickerstudio.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Non-Flow functions are blocking on purpose: [PackRepository] calls them on Dispatchers.IO
 * and the WhatsApp content provider calls them on a binder thread.
 */
@Dao
interface StickerDao {

    // ---- packs
    @Query("SELECT * FROM packs ORDER BY updatedAt DESC")
    fun observePacks(): Flow<List<PackEntity>>

    /** "My packs": packs made in the app, plus legacy packs whose source is unknown. */
    @Query("SELECT * FROM packs WHERE source != 'IMPORTED' ORDER BY updatedAt DESC")
    fun observeCreatedPacks(): Flow<List<PackEntity>>

    /** "Imported packs": only packs that came from an external file. */
    @Query("SELECT * FROM packs WHERE source = 'IMPORTED' ORDER BY updatedAt DESC")
    fun observeImportedPacks(): Flow<List<PackEntity>>

    /** The imported pack that came from this exact file content, if any (duplicate-import guard). */
    @Query("SELECT * FROM packs WHERE source = 'IMPORTED' AND importHash = :hash LIMIT 1")
    fun findImportedByHash(hash: String): PackEntity?

    @Query("SELECT * FROM packs WHERE id = :id")
    fun observePack(id: String): Flow<PackEntity?>

    @Query("SELECT * FROM packs WHERE id = :id")
    fun findPack(id: String): PackEntity?

    @Query("SELECT * FROM packs ORDER BY updatedAt DESC")
    fun allPacks(): List<PackEntity>

    @Insert
    fun insertPack(pack: PackEntity)

    @Update
    fun updatePack(pack: PackEntity)

    @Query("DELETE FROM packs WHERE id = :id")
    fun deletePack(id: String)

    // ---- stickers
    @Query("SELECT * FROM stickers WHERE packId = :packId ORDER BY position ASC, createdAt ASC")
    fun observeStickers(packId: String): Flow<List<StickerEntity>>

    @Query("SELECT * FROM stickers WHERE packId = :packId ORDER BY position ASC, createdAt ASC")
    fun stickersOf(packId: String): List<StickerEntity>

    @Query("SELECT * FROM stickers ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<StickerEntity>>

    @Query("SELECT * FROM stickers WHERE id = :id")
    fun findSticker(id: String): StickerEntity?

    @Query("SELECT packId AS packId, COUNT(*) AS cnt FROM stickers GROUP BY packId")
    fun observeCounts(): Flow<List<PackCount>>

    @Insert
    fun insertSticker(sticker: StickerEntity)

    @Update
    fun updateSticker(sticker: StickerEntity)

    @Query("DELETE FROM stickers WHERE id = :id")
    fun deleteSticker(id: String)

    @Query("UPDATE stickers SET position = :position WHERE id = :id")
    fun setPosition(id: String, position: Int)

    // ---- drafts
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun observeDrafts(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    fun findDraft(id: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertDraft(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE id = :id")
    fun deleteDraft(id: String)
}
