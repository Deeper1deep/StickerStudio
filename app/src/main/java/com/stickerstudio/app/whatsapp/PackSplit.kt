package com.stickerstudio.app.whatsapp

import com.stickerstudio.app.core.WhatsAppLimits

/**
 * Splitting an over-limit imported pack into temporary WhatsApp packs at export time.
 * The pack inside StickerStudio is never split, renamed or reordered: these are only
 * the ephemeral parts handed to WhatsApp, in the pack's original sticker order.
 */
object PackSplit {

    /**
     * Part sizes for a pack of [total] stickers, each at most [max] (WhatsApp's limit),
     * in order: 31 -> [30, 1], 60 -> [30, 30], 75 -> [30, 30, 15]. Empty when the pack
     * already fits (no split needed).
     */
    fun partSizes(total: Int, max: Int = WhatsAppLimits.MAX_STICKERS): List<Int> {
        if (total <= 0 || total <= max) return emptyList()
        val sizes = mutableListOf<Int>()
        var left = total
        while (left > 0) {
            val take = minOf(max, left)
            sizes += take
            left -= take
        }
        return sizes
    }

    /** Number of parts a pack of [total] stickers would split into (1 when it fits). */
    fun partCount(total: Int, max: Int = WhatsAppLimits.MAX_STICKERS): Int =
        if (total <= 0) 0 else (total + max - 1) / max

    /** WhatsApp-visible name of part [index] (1-based) of [parts], e.g. "Cats (2/3)". */
    fun partName(base: String, index: Int, parts: Int): String = "$base ($index/$parts)"
}
