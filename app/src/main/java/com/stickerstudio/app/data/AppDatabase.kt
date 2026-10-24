package com.stickerstudio.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

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
    entities = [PackEntity::class, StickerEntity::class, DraftEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dao(): StickerDao

    companion object {
        const val NAME = "sticker_studio.db"

        /** Add future migrations here (version 1 is the first release, nothing to migrate yet). */
        val MIGRATIONS: Array<Migration> = arrayOf()

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
