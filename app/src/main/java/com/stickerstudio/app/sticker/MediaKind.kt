package com.stickerstudio.app.sticker

/**
 * What a picked file really is. Many file managers report WebM / MP4 as `application/octet-stream`
 * (or nothing), so the first bytes decide first, then the MIME type, then the file name.
 */
enum class MediaKind(val extension: String) {
    GIF("gif"), WEBM("webm"), VIDEO("mp4"), IMAGE("png");

    val isAnimatedSource: Boolean get() = this != IMAGE
    val isVideo: Boolean get() = this == WEBM || this == VIDEO

    companion object {
        fun fromHeader(h: ByteArray): MediaKind? {
            fun at(i: Int, vararg v: Int) = h.size >= i + v.size && v.indices.all { h[i + it].toInt() and 0xFF == v[it] }
            return when {
                at(0, 'G'.code, 'I'.code, 'F'.code, '8'.code) -> GIF
                at(0, 0x1A, 0x45, 0xDF, 0xA3) -> WEBM                       // EBML: WebM / Matroska
                at(4, 'f'.code, 't'.code, 'y'.code, 'p'.code) -> VIDEO      // MP4 / MOV / 3GP
                at(0, 0x89, 'P'.code, 'N'.code, 'G'.code) -> IMAGE
                at(0, 0xFF, 0xD8, 0xFF) -> IMAGE                            // JPEG
                at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && at(8, 'W'.code, 'E'.code, 'B'.code, 'P'.code) -> IMAGE
                else -> null
            }
        }

        fun fromMime(mime: String?): MediaKind? {
            val m = mime.orEmpty().lowercase()
            return when {
                m == "image/gif" -> GIF
                m == "video/webm" || m == "video/x-matroska" -> WEBM
                m.startsWith("video/") -> VIDEO
                m.startsWith("image/") -> IMAGE
                else -> null
            }
        }

        fun fromName(name: String?): MediaKind? {
            val ext = name.orEmpty().substringAfterLast('.', "").lowercase()
            return when (ext) {
                "gif" -> GIF
                "webm", "mkv" -> WEBM
                "mp4", "m4v", "mov", "3gp", "avi" -> VIDEO
                "png", "jpg", "jpeg", "webp", "bmp", "heic" -> IMAGE
                else -> null
            }
        }

        fun detect(header: ByteArray?, mime: String?, name: String?): MediaKind =
            header?.let { fromHeader(it) } ?: fromMime(mime) ?: fromName(name) ?: IMAGE
    }
}
