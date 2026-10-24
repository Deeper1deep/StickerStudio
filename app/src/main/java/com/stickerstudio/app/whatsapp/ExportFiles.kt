package com.stickerstudio.app.whatsapp

import com.stickerstudio.app.data.StickerEntity
import com.stickerstudio.app.sticker.AnimatedWebpMuxer
import java.io.File

/**
 * The file that is really handed to WhatsApp for a sticker.
 *
 * A pack may mix static and animated stickers in the app. WhatsApp itself needs a pack to be
 * either all static or all animated, so when a pack contains any animated sticker the static
 * ones are exported as non-moving animated WebPs (cached next to the originals, which are
 * never modified).
 */
object ExportFiles {

    fun resolve(packDir: File, sticker: StickerEntity, packAnimated: Boolean): File {
        val source = File(packDir, sticker.fileName)
        if (!packAnimated || sticker.animated || !source.isFile) return source
        val cacheDir = File(packDir, "anim").apply { mkdirs() }
        val out = File(cacheDir, sticker.fileName)
        if (out.isFile && out.lastModified() >= source.lastModified()) return out
        val wrapped = try {
            AnimatedWebpMuxer.staticAsAnimated(source.readBytes())
        } catch (e: IllegalArgumentException) {
            null
        } ?: return source
        val tmp = File(cacheDir, sticker.fileName + ".tmp")
        tmp.writeBytes(wrapped)
        if (!tmp.renameTo(out)) {
            out.writeBytes(wrapped)
            tmp.delete()
        }
        return out
    }
}
