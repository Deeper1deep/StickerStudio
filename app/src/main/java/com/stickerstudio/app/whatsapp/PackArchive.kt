package com.stickerstudio.app.whatsapp

import com.stickerstudio.app.core.MiniJson
import com.stickerstudio.app.core.Sha256
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class ArchiveSticker(val name: String, val bytes: ByteArray, val emojis: String?)

/** What was found in a sticker pack archive (.wastickers, or a plain .zip). */
class ArchivePack(
    val title: String?,
    val author: String?,
    /** WebP stickers, in pack order. */
    val webps: List<ArchiveSticker>,
    /** Ordinary pictures (png / jpg / gif ...), used only when there are no WebP stickers. */
    val images: List<ArchiveSticker>,
    /** Sticker entries dropped only for exceeding [MAX_ENTRY_BYTES]; counted, never silent. */
    val skippedOversized: Int = 0,
    /** Every sticker entry - WebP and plain pictures together - in the pack's own order. */
    val stickers: List<ArchiveSticker> = webps + images,
)

/**
 * Reads a sticker pack archive. A .wastickers file is a ZIP with `title.txt`, `author.txt`, a tray
 * picture and numbered `.webp` stickers; our own backups also carry `pack.json` (emojis) and the
 * WhatsApp sample format `contents.json` is understood too. Anything else in a ZIP is ignored.
 * Entries are only read into memory, never written by name, and sizes are capped.
 */
object PackArchive {
    const val MAX_ENTRIES = 400
    const val MAX_ENTRY_BYTES = 12 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "bmp")

    fun read(input: InputStream): ArchivePack {
        var title: String? = null
        var author: String? = null
        var order: Map<String, Pair<Int, String?>> = emptyMap()
        val webps = ArrayList<ArchiveSticker>()
        val images = ArrayList<ArchiveSticker>()
        var entries = 0
        var total = 0L
        var skippedOversized = 0

        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (++entries > MAX_ENTRIES) throw IOException("Too many files")
                val path = e.name.replace('\\', '/')
                if (path.startsWith("__MACOSX/")) continue
                val base = path.substringAfterLast('/')
                if (base.startsWith(".") || base.isEmpty()) continue
                val ext = base.substringAfterLast('.', "").lowercase()
                val lower = base.lowercase()
                val wanted = ext == "webp" || ext in IMAGE_EXT || ext == "txt" || ext == "json"
                if (!wanted) continue
                val bytes = readCapped(zip, MAX_ENTRY_BYTES)
                if (bytes == null) {
                    // oversized sticker entries are counted so the caller can report them
                    if (ext == "webp" || ext in IMAGE_EXT) skippedOversized++
                    continue
                }
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw IOException("Archive too large")

                when {
                    lower == "title.txt" -> title = firstLine(bytes)
                    lower == "author.txt" -> author = firstLine(bytes)
                    ext == "txt" -> Unit
                    ext == "json" -> if (lower == "pack.json" || lower == "contents.json") {
                        val parsed = parseOrder(bytes)
                        if (parsed.title != null && title == null) title = parsed.title
                        if (parsed.author != null && author == null) author = parsed.author
                        order = parsed.order
                    }
                    lower.contains("tray") -> Unit               // the tray icon is rebuilt from the first sticker
                    ext == "webp" -> webps += ArchiveSticker(base, bytes, null)
                    else -> images += ArchiveSticker(base, bytes, null)
                }
            }
        }

        fun arrange(list: List<ArchiveSticker>): List<ArchiveSticker> =
            list.map { s -> ArchiveSticker(s.name, s.bytes, order[s.name.lowercase()]?.second) }
                .sortedWith(
                    Comparator { a, b ->
                        val ia = order[a.name.lowercase()]?.first ?: Int.MAX_VALUE
                        val ib = order[b.name.lowercase()]?.first ?: Int.MAX_VALUE
                        if (ia != ib) ia.compareTo(ib) else naturalCompare(a.name, b.name)
                    }
                )
        val w = arrange(webps)
        val im = arrange(images)
        return ArchivePack(title, author, w, im, skippedOversized, arrange(w + im))
    }

    private class Parsed(val title: String?, val author: String?, val order: Map<String, Pair<Int, String?>>)

    /** Order and emojis from pack.json (ours) or contents.json (WhatsApp sample format). */
    private fun parseOrder(bytes: ByteArray): Parsed {
        val none = Parsed(null, null, emptyMap())
        val root = try {
            MiniJson.parse(String(bytes, Charsets.UTF_8)) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: return none
        val list = root["stickers"] as? List<*> ?: return none
        val out = LinkedHashMap<String, Pair<Int, String?>>()
        list.forEachIndexed { i, item ->
            val m = item as? Map<*, *> ?: return@forEachIndexed
            val file = (m["file"] ?: m["image_file"]) as? String ?: return@forEachIndexed
            val emojis = when (val raw = m["emojis"]) {
                is String -> raw
                is List<*> -> raw.filterIsInstance<String>().joinToString("")
                else -> null
            }?.takeIf { it.isNotBlank() }
            out[file.substringAfterLast('/').lowercase()] = i to emojis
        }
        val name = (root["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val publisher = (root["publisher"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return Parsed(name, publisher, out)
    }

    /**
     * A stable fingerprint of an archive's real content: title, author, and every sticker's name,
     * size and bytes in pack order. Two files with the same content produce the same fingerprint,
     * so importing the same pack twice can be recognised instead of duplicated. Order matters: a
     * reordered pack is a different pack.
     */
    fun fingerprint(pack: ArchivePack): String {
        val sb = StringBuilder()
        sb.append(pack.title.orEmpty()).append('\u0000')
        sb.append(pack.author.orEmpty()).append('\u0000')
        for (s in pack.stickers) {
            sb.append(s.name.lowercase()).append(':').append(s.bytes.size).append(':')
                .append(Sha256.hex(s.bytes)).append('\u0000')
        }
        return Sha256.hex(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun firstLine(bytes: ByteArray): String? =
        String(bytes, Charsets.UTF_8).removePrefix("﻿").lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Reads the current entry; null (and the rest is skipped) when it is bigger than [cap]. */
    private fun readCapped(zip: ZipInputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var tooBig = false
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            if (!tooBig) {
                if (out.size() + n > cap) tooBig = true else out.write(buf, 0, n)
            }
        }
        return if (tooBig) null else out.toByteArray()
    }

    /** "2.webp" before "10.webp". */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var si = i; while (si < a.length && a[si] == '0') si++
                var sj = j; while (sj < b.length && b[sj] == '0') sj++
                var ei = si; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = sj; while (ej < b.length && b[ej].isDigit()) ej++
                val la = ei - si
                val lb = ej - sj
                if (la != lb) return la.compareTo(lb)
                val c = a.substring(si, ei).compareTo(b.substring(sj, ej))
                if (c != 0) return c
                i = ei; j = ej
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
