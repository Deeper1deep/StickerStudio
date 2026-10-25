package com.stickerstudio.app.sticker

import java.io.IOException

/**
 * Small pure-Kotlin GIF reader that composites frames in order.
 *
 * Why it exists: android.graphics.Movie re-decodes from the start for many GIFs, which made
 * converting a few-second GIF take minutes. Here every frame is decoded once, sequentially
 * (LZW + disposal handling), so reading frames in time order is cheap. Seeking backwards simply
 * restarts from the first frame.
 */
class GifDecoder(private val data: ByteArray) {

    private class Frame(
        val left: Int, val top: Int, val w: Int, val h: Int,
        val interlaced: Boolean,
        val palette: IntArray,
        val transparentIndex: Int,
        val disposal: Int,
        val delayMs: Int,
        val dataStart: Int,   // LZW minimum code size byte
        val startMs: Long,
    )

    val width: Int
    val height: Int
    val frameCount: Int get() = frames.size
    val durationMs: Long

    private val frames = ArrayList<Frame>()
    private val canvas: IntArray
    private var saved: IntArray? = null
    private var current = -1

    init {
        if (data.size < 13 || data[0] != 'G'.code.toByte() || data[1] != 'I'.code.toByte() || data[2] != 'F'.code.toByte()) {
            throw IOException("Not a GIF")
        }
        var w = u16(6)
        var h = u16(8)
        val flags = data[10].toInt() and 0xFF
        var pos = 13
        var global: IntArray? = null
        if (flags and 0x80 != 0) {
            val n = 1 shl ((flags and 7) + 1)
            global = readPalette(pos, n)
            pos += n * 3
        }

        var delay = 100
        var disposal = 0
        var transparent = -1
        var total = 0L
        loop@ while (pos < data.size) {
            when (data[pos].toInt() and 0xFF) {
                0x3B -> break@loop
                0x21 -> {
                    if (pos + 2 > data.size) break@loop
                    val label = data[pos + 1].toInt() and 0xFF
                    if (label == 0xF9 && pos + 7 < data.size) {
                        val packed = data[pos + 3].toInt() and 0xFF
                        disposal = (packed shr 2) and 7
                        transparent = if (packed and 1 != 0) data[pos + 6].toInt() and 0xFF else -1
                        val raw = u16(pos + 4)
                        delay = if (raw < 2) 100 else raw * 10    // like browsers: 0/10 ms means "default"
                    }
                    pos = skipBlocks(pos + 2)
                }
                0x2C -> {
                    if (pos + 10 > data.size) break@loop
                    val left = u16(pos + 1)
                    val top = u16(pos + 3)
                    val fw = u16(pos + 5)
                    val fh = u16(pos + 7)
                    val packed = data[pos + 9].toInt() and 0xFF
                    pos += 10
                    var palette = global
                    if (packed and 0x80 != 0) {
                        val n = 1 shl ((packed and 7) + 1)
                        if (pos + n * 3 > data.size) break@loop
                        palette = readPalette(pos, n)
                        pos += n * 3
                    }
                    if (pos >= data.size || palette == null || fw <= 0 || fh <= 0) break@loop
                    frames += Frame(
                        left, top, fw, fh, packed and 0x40 != 0, palette, transparent,
                        disposal, delay, pos, total,
                    )
                    total += delay
                    pos = skipBlocks(pos + 1)
                    delay = 100; disposal = 0; transparent = -1
                }
                else -> break@loop
            }
        }
        if (frames.isEmpty()) throw IOException("GIF has no frames")
        if (w <= 0 || h <= 0) {
            w = frames.maxOf { it.left + it.w }
            h = frames.maxOf { it.top + it.h }
        }
        width = w
        height = h
        durationMs = total
        canvas = IntArray(width * height)
    }

    /** Index of the frame that is visible at [timeMs] (the clip loops). */
    fun indexAt(timeMs: Long): Int {
        val t = if (durationMs > 0) ((timeMs % durationMs) + durationMs) % durationMs else 0L
        var lo = 0
        var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (frames[mid].startMs <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Writes the composited picture of frame [index] into [out] (width*height ARGB). */
    fun renderFrame(index: Int, out: IntArray) {
        val target = index.coerceIn(0, frames.size - 1)
        if (target < current) reset()
        while (current < target) advance()
        System.arraycopy(canvas, 0, out, 0, canvas.size)
    }

    private fun reset() {
        canvas.fill(0)
        saved = null
        current = -1
    }

    private fun advance() {
        val i = current + 1
        if (current >= 0) {
            val p = frames[current]
            when (p.disposal) {
                2 -> clearRect(p)
                3 -> saved?.let { System.arraycopy(it, 0, canvas, 0, canvas.size) }
            }
        }
        val f = frames[i]
        if (f.disposal == 3) {
            val s = saved ?: IntArray(canvas.size)
            System.arraycopy(canvas, 0, s, 0, canvas.size)
            saved = s
        }
        draw(f)
        current = i
    }

    private fun clearRect(f: Frame) {
        for (y in f.top until minOf(height, f.top + f.h)) {
            val from = y * width + f.left
            val to = y * width + minOf(width, f.left + f.w)
            if (from < to) canvas.fill(0, from, to)
        }
    }

    private fun draw(f: Frame) {
        val pixels = ByteArray(f.w * f.h)
        lzw(f, pixels)
        val rows = rowOrder(f)
        for (r in 0 until f.h) {
            val y = f.top + rows[r]
            if (y >= height) continue
            val srcBase = r * f.w
            for (x in 0 until f.w) {
                val cx = f.left + x
                if (cx >= width) break
                val idx = pixels[srcBase + x].toInt() and 0xFF
                if (idx == f.transparentIndex) continue
                canvas[y * width + cx] = if (idx < f.palette.size) f.palette[idx] else 0
            }
        }
    }

    /** Maps the n-th stored row to its picture row (identity unless interlaced). */
    private fun rowOrder(f: Frame): IntArray {
        val rows = IntArray(f.h) { it }
        if (!f.interlaced) return rows
        var n = 0
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var y = start
            while (y < f.h) { rows[n++] = y; y += step }
        }
        return rows
    }

    /** GIF flavour of LZW; stops quietly on damaged data (the rest stays index 0). */
    private fun lzw(f: Frame, out: ByteArray) {
        var pos = f.dataStart
        val minCode = data[pos].toInt() and 0xFF
        pos++
        if (minCode < 1 || minCode > 11) return

        // gather the sub-blocks
        var size = 0
        var p = pos
        while (p < data.size) {
            val n = data[p].toInt() and 0xFF
            if (n == 0) break
            size += minOf(n, data.size - p - 1)
            p += n + 1
        }
        val bytes = ByteArray(size)
        var o = 0
        p = pos
        while (p < data.size) {
            val n = data[p].toInt() and 0xFF
            if (n == 0) break
            val len = minOf(n, data.size - p - 1)
            System.arraycopy(data, p + 1, bytes, o, len)
            o += len
            p += n + 1
        }

        val clear = 1 shl minCode
        val eoi = clear + 1
        val prefix = ShortArray(4096)
        val suffix = ByteArray(4096)
        val stack = ByteArray(4097)
        var codeSize = minCode + 1
        var codeMask = (1 shl codeSize) - 1
        var next = clear + 2
        var old = -1
        var first = 0
        var bitBuf = 0
        var bits = 0
        var bi = 0
        var written = 0
        for (i in 0 until clear) suffix[i] = i.toByte()

        while (written < out.size) {
            while (bits < codeSize) {
                if (bi >= bytes.size) return
                bitBuf = bitBuf or ((bytes[bi++].toInt() and 0xFF) shl bits)
                bits += 8
            }
            var code = bitBuf and codeMask
            bitBuf = bitBuf ushr codeSize
            bits -= codeSize

            if (code == clear) {
                codeSize = minCode + 1
                codeMask = (1 shl codeSize) - 1
                next = clear + 2
                old = -1
                continue
            }
            if (code == eoi) return
            if (old == -1) {
                if (code >= clear) return
                out[written++] = suffix[code]
                old = code
                first = code
                continue
            }
            val inCode = code
            var sp = 0
            if (code >= next) {
                if (code > next) return          // damaged stream
                stack[sp++] = first.toByte()
                code = old
            }
            while (code >= clear) {
                if (sp >= stack.size - 1) return
                stack[sp++] = suffix[code]
                code = prefix[code].toInt() and 0xFFFF
            }
            first = suffix[code].toInt() and 0xFF
            stack[sp++] = first.toByte()
            if (next < 4096) {
                prefix[next] = old.toShort()
                suffix[next] = first.toByte()
                next++
                if (next and codeMask == 0 && next < 4096) {
                    codeSize++
                    codeMask = (1 shl codeSize) - 1
                }
            }
            old = inCode
            while (sp > 0 && written < out.size) out[written++] = stack[--sp]
        }
    }

    private fun readPalette(at: Int, n: Int): IntArray {
        val pal = IntArray(n)
        for (i in 0 until n) {
            val b = at + i * 3
            if (b + 2 >= data.size) break
            pal[i] = (0xFF shl 24) or ((data[b].toInt() and 0xFF) shl 16) or ((data[b + 1].toInt() and 0xFF) shl 8) or (data[b + 2].toInt() and 0xFF)
        }
        return pal
    }

    /** Skips a chain of data sub-blocks; [at] points at the first size byte. Returns the next position. */
    private fun skipBlocks(at: Int): Int {
        var p = at
        while (p < data.size) {
            val n = data[p].toInt() and 0xFF
            p += n + 1
            if (n == 0) return p
        }
        return data.size
    }

    private fun u16(at: Int): Int = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
}
