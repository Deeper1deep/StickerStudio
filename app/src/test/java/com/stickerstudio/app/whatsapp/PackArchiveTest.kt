package com.stickerstudio.app.whatsapp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackArchiveTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray =
        zipBytes(*files.map { (n, c) -> n to c.toByteArray() }.toTypedArray())

    private fun zipBytes(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in files) {
                z.putNextEntry(ZipEntry(name)); z.write(content); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(b: ByteArray) = PackArchive.read(ByteArrayInputStream(b))

    @Test fun wastickersLayout() {
        val p = read(zip(
            "title.txt" to "My Pack\n", "author.txt" to "Someone", "tray.png" to "x",
            "10.webp" to "j", "2.webp" to "b", "1.webp" to "a",
        ))
        assertEquals("My Pack", p.title)
        assertEquals("Someone", p.author)
        assertEquals(listOf("1.webp", "2.webp", "10.webp"), p.webps.map { it.name })
        assertTrue(p.images.isEmpty())
    }

    @Test fun ourPackJsonGivesOrderAndEmojis() {
        val json = """{"name":"N","publisher":"P","stickers":[{"file":"stickers/b.webp","emojis":"😀🎉"},{"file":"stickers/a.webp","emojis":"😎"}]}"""
        val p = read(zip("stickers/a.webp" to "a", "stickers/b.webp" to "b", "pack.json" to json))
        assertEquals("N", p.title)
        assertEquals("P", p.author)
        assertEquals(listOf("b.webp", "a.webp"), p.webps.map { it.name })
        assertEquals("😀🎉", p.webps[0].emojis)
        assertEquals("😎", p.webps[1].emojis)
    }

    @Test fun whatsAppSampleContentsJson() {
        val json = """{"name":"S","publisher":"Q","tray_image_file":"tray.png","stickers":[{"image_file":"one.webp","emojis":["😀","🙂"]}]}"""
        val p = read(zip("contents.json" to json, "one.webp" to "x", "tray.png" to "t"))
        assertEquals("S", p.title)
        assertEquals("😀🙂", p.webps.single().emojis)
    }

    @Test fun plainZipOfPicturesFallsToImages() {
        val p = read(zip("a.png" to "1", "b.JPG" to "2", "readme.md" to "ignored", "__MACOSX/._a.png" to "x"))
        assertTrue(p.webps.isEmpty())
        assertEquals(listOf("a.png", "b.JPG"), p.images.map { it.name })
        assertNull(p.title)
    }

    @Test fun naturalOrder() {
        assertTrue(PackArchive.naturalCompare("2.webp", "10.webp") < 0)
        assertEquals(0, PackArchive.naturalCompare("a01.webp", "a1.webp"))
        assertTrue(PackArchive.naturalCompare("b.webp", "a.webp") > 0)
    }

    @Test fun notAZipFails() {
        // ZipInputStream yields no entries for random bytes: the result is simply empty
        val p = read(ByteArray(100) { 7 })
        assertTrue(p.webps.isEmpty() && p.images.isEmpty())
    }

    @Test fun mixedPackKeepsWebpsAndPicturesInOrder() {
        val json = """{"name":"M","stickers":[{"file":"a.webp"},{"file":"b.png"},{"file":"c.webp"}]}"""
        val p = read(zip("c.webp" to "3", "b.png" to "2", "a.webp" to "1", "contents.json" to json))
        assertEquals(listOf("a.webp", "c.webp"), p.webps.map { it.name })
        assertEquals(listOf("b.png"), p.images.map { it.name })
        assertEquals(listOf("a.webp", "b.png", "c.webp"), p.stickers.map { it.name })
    }

    @Test fun oversizedStickerEntriesAreCountedNotSilent() {
        val big = ByteArray(PackArchive.MAX_ENTRY_BYTES + 1) { 1 }
        val p = read(zipBytes(
            "title.txt" to "T".toByteArray(),
            "1.webp" to "a".toByteArray(),
            "2.webp" to big,
            "3.webp" to "c".toByteArray(),
        ))
        assertEquals(listOf("1.webp", "3.webp"), p.webps.map { it.name })
        assertEquals(1, p.skippedOversized)
    }

    @Test fun totalCapFitsLargeAnimatedPacks() {
        // a 60-sticker animated pack easily exceeds the old 64 MB whole-archive cap
        assertTrue(PackArchive.MAX_TOTAL_BYTES >= 128L * 1024 * 1024)
    }

    @Test fun tooManyEntriesFails() {
        val files = (1..PackArchive.MAX_ENTRIES + 1).map { "$it.webp" to "x" }.toTypedArray()
        var failed = false
        try { read(zip(*files)) } catch (e: IOException) { failed = true }
        assertTrue(failed)
    }
}
