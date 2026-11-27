package com.stickerstudio.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * ### Schema changes (read before editing entities)
 * 1. Bump `version`.
 * 2. Add a [Migration] to [MIGRATIONS] that keeps every existing row.
 * 3. Commit the new JSON from app/schemas/ and extend DatabaseMigrationTest.
 *
 * `fallbackToDestructiveMigration()` is deliberately NOT used: an app update must never wipe
 * the user's packs.
 */
@Database(
    entities = [PackEntity::class, StickerEntity::class, DraftEntity::class, StickerAudioEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dao(): StickerDao

    companion object {
        const val NAME = "sticker_studio.db"

        /**
         * 1 → 2: packs learn their [PackSource] and import fingerprint. Existing rows become
         * UNKNOWN: their source is never guessed, they are not moved between sections and
         * nothing (ids, stickers, thumbnails, settings) is deleted.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE packs ADD COLUMN source TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE packs ADD COLUMN importHash TEXT")
            }
        }

        /** 2 → 3: adds the separate sticker_audio table only; no existing row or column is touched. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sticker_audio` (`stickerId` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, `sourceKind` TEXT NOT NULL, `trimStartMs` INTEGER NOT NULL, " +
                        "`trimEndMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`stickerId`), " +
                        "FOREIGN KEY(`stickerId`) REFERENCES `stickers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
            }
        }

        /** Add future migrations here; `fallbackToDestructiveMigration` stays forbidden. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
