package com.stickerstudio.app.whatsapp

import com.stickerstudio.app.core.WhatsAppLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackSplitTest {

    @Test
    fun `pack within the limit is never split`() {
        assertTrue(PackSplit.partSizes(30).isEmpty())
        assertTrue(PackSplit.partSizes(3).isEmpty())
        assertTrue(PackSplit.partSizes(0).isEmpty())
        assertEquals(1, PackSplit.partCount(30))
    }

    @Test
    fun `31 stickers become 30 plus 1`() {
        assertEquals(listOf(30, 1), PackSplit.partSizes(31))
        assertEquals(2, PackSplit.partCount(31))
    }

    @Test
    fun `60 stickers become two full parts`() {
        assertEquals(listOf(30, 30), PackSplit.partSizes(60))
        assertEquals(2, PackSplit.partCount(60))
    }

    @Test
    fun `75 stickers become 30 30 15`() {
        assertEquals(listOf(30, 30, 15), PackSplit.partSizes(75))
        assertEquals(3, PackSplit.partCount(75))
    }

    @Test
    fun `parts cover every sticker exactly once and in order`() {
        for (total in listOf(31, 45, 60, 61, 75, 90, 400)) {
            val sizes = PackSplit.partSizes(total)
            assertEquals(total, sizes.sum())
            assertTrue(sizes.all { it in 1..WhatsAppLimits.MAX_STICKERS })
            // Contiguous slices keep the original order: part i starts where part i-1 ended.
            var offset = 0
            for (size in sizes) {
                val slice = (0 until total).toList().subList(offset, offset + size)
                assertEquals((offset until offset + size).toList(), slice)
                offset += size
            }
        }
    }

    @Test
    fun `part names are numbered from one`() {
        assertEquals("Cats (1/2)", PackSplit.partName("Cats", 1, 2))
        assertEquals("Cats (2/2)", PackSplit.partName("Cats", 2, 2))
        assertEquals("قطط (1/3)", PackSplit.partName("قطط", 1, 3))
    }
}
