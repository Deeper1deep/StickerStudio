package com.stickerstudio.app.audio

import com.stickerstudio.app.data.AudioStorage
import com.stickerstudio.app.data.DiskFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioStorageTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun onlyUnreferencedOldFilesAreOrphans() {
        val now = 1_000_000L
        val files = listOf(
            DiskFile("used.ogg", now - 999_999),
            DiskFile("old_orphan.ogg", now - 120_000),
            DiskFile("fresh_orphan.ogg", now - 5_000),
        )
        assertEquals(listOf("old_orphan.ogg"), AudioStorage.orphans(files, setOf("used.ogg"), now))
    }

    @Test fun sweepDeletesOrphansAndKeepsUsedAndFreshFiles() {
        val dir = tmp.newFolder("audio")
        val used = File(dir, "used.ogg").apply { writeText("x"); setLastModified(1_000) }
        val orphan = File(dir, "orphan.ogg").apply { writeText("x"); setLastModified(1_000) }
        val fresh = File(dir, "fresh.ogg").apply { writeText("x"); setLastModified(1_000_000L) }
        val removed = AudioStorage.sweep(dir, setOf("used.ogg"), now = 1_000_000L)
        assertEquals(1, removed)
        assertTrue(used.exists())
        assertFalse(orphan.exists())
        assertTrue(fresh.exists())
    }

    @Test fun missingFolderIsHarmless() {
        assertEquals(0, AudioStorage.sweep(File(tmp.root, "nope"), emptySet()))
    }
}
