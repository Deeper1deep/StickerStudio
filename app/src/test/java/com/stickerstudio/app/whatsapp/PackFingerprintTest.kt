package com.stickerstudio.app.whatsapp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import integrity: the archive reader keeps EVERY sticker (30, 31, 60, 75...) in the original
 * order, and the fingerprint recognises identical files so they are never imported twice.
 */
class PackFingerprintTest {

    private fun archive(count: Int, title: String? = "T", reversed: Boolean = false): ArchivePack {
        var webps = (1..count).map { ArchiveSticker("$it.webp", byteArrayOf((it % 251).toByte(), 7), null) }
        if (reversed) webps = webps.reversed()
        return ArchivePack(title, "A", webps, emptyList())
    }

    private fun zipWithStickers(n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (i in 1..n) {
                z.putNextEntry(ZipEntry("$i.webp"))
                z.write(ByteArray(8) { (i % 251).toByte() })
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun readerKeepsEveryStickerPastThirtyInOriginalOrder() {
        for (n in listOf(30, 31, 60, 75)) {
            val p = PackArchive.read(ByteArrayInputStream(zipWithStickers(n)))
            assertEquals("file with $n stickers", n, p.webps.size)
            assertEquals((1..n).map { "$it.webp" }, p.webps.map { it.name })
        }
    }

    @Test fun sameContentSameFingerprint() {
        assertEquals(PackArchive.fingerprint(archive(60)), PackArchive.fingerprint(archive(60)))
        assertEquals(PackArchive.fingerprint(archive(1)), PackArchive.fingerprint(archive(1)))
    }

    @Test fun differentStickerCountsGiveDifferentFingerprints() {
        val f30 = PackArchive.fingerprint(archive(30))
        assertTrue(f30 != PackArchive.fingerprint(archive(31)))
        assertTrue(f30 != PackArchive.fingerprint(archive(60)))
        assertTrue(f30 != PackArchive.fingerprint(archive(75)))
    }

    @Test fun orderAndMetadataArePartOfTheFingerprint() {
        assertTrue(PackArchive.fingerprint(archive(5)) != PackArchive.fingerprint(archive(5, reversed = true)))
        assertTrue(PackArchive.fingerprint(archive(5, title = "A")) != PackArchive.fingerprint(archive(5, title = "B")))
    }

    @Test fun realArchiveRoundTripFingerprint() {
        val bytes = zipWithStickers(60)
        val a = PackArchive.read(ByteArrayInputStream(bytes))
        val b = PackArchive.read(ByteArrayInputStream(bytes))
        assertEquals(PackArchive.fingerprint(a), PackArchive.fingerprint(b))
    }
}
