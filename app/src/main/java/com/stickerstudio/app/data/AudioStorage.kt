package com.stickerstudio.app.data

import java.io.File

/** A file found in the audio folder. */
data class DiskFile(val name: String, val lastModified: Long)

/** Cleanup of audio files that no sticker uses any more. */
object AudioStorage {

    /** Files younger than this are never removed: they may be saved a moment before their database row. */
    const val GRACE_MS = 60_000L

    /** Names of files that nothing refers to and that are old enough to remove. Pure, unit tested. */
    fun orphans(files: List<DiskFile>, referenced: Set<String>, now: Long, graceMs: Long = GRACE_MS): List<String> =
        files.filter { it.name !in referenced && now - it.lastModified >= graceMs }.map { it.name }

    /** Deletes the orphans of [dir]; returns how many were removed. Never touches referenced files. */
    fun sweep(dir: File, referenced: Set<String>, now: Long = System.currentTimeMillis()): Int {
        val files = dir.listFiles()?.filter { it.isFile }?.map { DiskFile(it.name, it.lastModified()) } ?: return 0
        var removed = 0
        for (name in orphans(files, referenced, now)) {
            if (File(dir, name).delete()) removed++
        }
        return removed
    }
}
