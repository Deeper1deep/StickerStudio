package com.stickerstudio.app.sticker

import java.io.ByteArrayOutputStream

/**
 * Builds an animated WebP file out of single-frame WebP files.
 *
 * Android can encode a single WebP frame (Bitmap.compress) but has no animated WebP
 * encoder. The animated container is only a RIFF wrapper (VP8X + ANIM + one ANMF chunk
 * per frame), so we assemble it ourselves from the already-encoded frames. Pixel data is
 * copied untouched; only the container is written here.
 *
 * Spec: https://developers.google.com/speed/webp/docs/riff_container
 */
object AnimatedWebpMuxer {

    class Frame(val webp: ByteArray, val durationMs: Int)

    /**
     * @param loopCount 0 = loop forever.
     * @throws IllegalArgumentException if a frame is not a valid WebP file.
     */
    fun mux(frames: List<Frame>, loopCount: Int = 0): ByteArray {
        require(frames.isNotEmpty()) { "No frames" }

        val infos = frames.mapIndexed { i, f ->
            WebpParser.parse(f.webp) ?: throw IllegalArgumentException("Frame $i is not a valid WebP")
        }
        val canvasW = infos.maxOf { it.width }
        val canvasH = infos.maxOf { it.height }
        val alpha = infos.any { it.hasAlpha }

        val body = ByteArrayOutputStream()

        // VP8X: feature flags + canvas size.
        writeTag(body, "VP8X")
        writeLe32(body, 10)
        body.write(0x02 or (if (alpha) 0x10 else 0x00)) // animation (+ alpha) flags
        body.write(0); body.write(0); body.write(0)       // reserved
        writeLe24(body, canvasW - 1)
        writeLe24(body, canvasH - 1)

        // ANIM: background colour (transparent) + loop count.
        writeTag(body, "ANIM")
        writeLe32(body, 6)
        writeLe32(body, 0)
        writeLe16(body, loopCount.coerceIn(0, 65535))

        for ((i, frame) in frames.withIndex()) {
            val info = infos[i]
            val payload = imageChunks(frame.webp)
            writeTag(body, "ANMF")
            writeLe32(body, 16 + payload.size)
            writeLe24(body, 0)                       // frame X / 2
            writeLe24(body, 0)                       // frame Y / 2
            writeLe24(body, info.width - 1)
            writeLe24(body, info.height - 1)
            writeLe24(body, frame.durationMs.coerceIn(0, 0xFFFFFF))
            body.write(0x02)                         // no blending, no disposal: each frame replaces the canvas
            body.write(payload)
        }

        val out = ByteArrayOutputStream(body.size() + 12)
        writeTag(out, "RIFF")
        writeLe32(out, 4 + body.size())
        writeTag(out, "WEBP")
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    /** Copies the ALPH / VP8 / VP8L chunks (with headers and padding) of a single-frame file. */
    private fun imageChunks(webp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(webp.size)
        var pos = 12
        while (pos + 8 <= webp.size) {
            val id = String(webp, pos, 4, Charsets.ISO_8859_1)
            val size = WebpParser.le32(webp, pos + 4)
            val ds = pos + 8
            if (size < 0 || ds.toLong() + size > webp.size) throw IllegalArgumentException("Truncated WebP")
            if (id == "ALPH" || id == "VP8 " || id == "VP8L") {
                out.write(webp, pos, 8 + size)
                if ((size and 1) == 1) out.write(0)
            }
            pos = ds + size + (size and 1)
        }
        val result = out.toByteArray()
        require(result.isNotEmpty()) { "WebP frame has no image data" }
        return result
    }

    private fun writeTag(o: ByteArrayOutputStream, tag: String) {
        for (c in tag) o.write(c.code)
    }

    private fun writeLe16(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v shr 8) and 0xFF)
    }

    private fun writeLe24(o: ByteArrayOutputStream, v: Int) {
        writeLe16(o, v)
        o.write((v shr 16) and 0xFF)
    }

    private fun writeLe32(o: ByteArrayOutputStream, v: Int) {
        writeLe24(o, v)
        o.write((v shr 24) and 0xFF)
    }
}
