package com.stickerstudio.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the mandatory separation rules between "My packs" and "Imported packs"
 * (spec section 7): no mixing, no duplicates across sections, unknown sources never guessed.
 */
class PackSourceTest {

    private fun pack(id: String, source: String) = PackEntity(
        id = id, name = id, publisher = "p", trayFile = null, trayStickerId = null,
        animated = false, imageDataVersion = 1, source = source, importHash = null,
        createdAt = 0L, updatedAt = 0L,
    )

    @Test fun storedValueRoundTrips() {
        assertEquals(PackSource.CREATED, pack("a", PackSource.CREATED.name).packSource)
        assertEquals(PackSource.IMPORTED, pack("b", PackSource.IMPORTED.name).packSource)
    }

    @Test fun unknownIsNeverGuessed() {
        assertEquals(PackSource.UNKNOWN, pack("c", PackSource.UNKNOWN.name).packSource)
        assertEquals(PackSource.UNKNOWN, pack("d", "some-garbage").packSource)
        assertEquals(PackSource.UNKNOWN, pack("e", "").packSource)
    }

    @Test fun myPacksContainCreatedAndLegacyButNeverImported() {
        val created = pack("1", "CREATED")
        val legacy = pack("2", "UNKNOWN")
        val imported = pack("3", "IMPORTED")
        val mine = PackSourceRules.myPacks(listOf(created, legacy, imported))
        assertEquals(listOf("1", "2"), mine.map { it.id })
        assertFalse(mine.any { it.packSource == PackSource.IMPORTED })
    }

    @Test fun importedPacksContainOnlyImported() {
        val created = pack("1", "CREATED")
        val legacy = pack("2", "UNKNOWN")
        val imported = pack("3", "IMPORTED")
        val imported2 = pack("4", "IMPORTED")
        val result = PackSourceRules.importedPacks(listOf(created, legacy, imported, imported2))
        assertEquals(listOf("3", "4"), result.map { it.id })
    }

    @Test fun theTwoSectionsNeverOverlapAndCoverEverything() {
        val all = listOf(pack("1", "CREATED"), pack("2", "UNKNOWN"), pack("3", "IMPORTED"))
        val mine = PackSourceRules.myPacks(all).map { it.id }.toSet()
        val imported = PackSourceRules.importedPacks(all).map { it.id }.toSet()
        assertTrue(mine.intersect(imported).isEmpty())
        assertEquals(all.map { it.id }.toSet(), mine + imported)
    }

    @Test fun stickerLimitSeparatesStorageFromWhatsAppExport() {
        // WhatsApp's export limit (30) governs created packs only; imported packs keep whole files.
        assertEquals(30, PackSourceRules.stickerLimit(PackSource.CREATED))
        assertEquals(30, PackSourceRules.stickerLimit(PackSource.UNKNOWN))
        assertEquals(PackSourceRules.IMPORTED_MAX_STICKERS, PackSourceRules.stickerLimit(PackSource.IMPORTED))
        assertTrue(PackSourceRules.stickerLimit(PackSource.IMPORTED) > 75)
    }
}
