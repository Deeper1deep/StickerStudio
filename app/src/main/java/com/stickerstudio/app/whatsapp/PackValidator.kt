package com.stickerstudio.app.whatsapp

import com.stickerstudio.app.core.WhatsAppLimits
import com.stickerstudio.app.sticker.WebpInfo

/** One file belonging to a pack, as measured from the real file on disk. */
data class StickerCheck(
    val fileName: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val info: WebpInfo?,
    val emojiCount: Int,
)

data class TrayCheck(val exists: Boolean, val sizeBytes: Long, val width: Int, val height: Int)

enum class IssueCode {
    NAME_EMPTY, NAME_TOO_LONG, PUBLISHER_EMPTY,
    TOO_FEW_STICKERS, TOO_MANY_STICKERS,
    MIXED_ANIMATION,
    FILE_MISSING, FILE_CORRUPT, BAD_DIMENSIONS, TOO_LARGE,
    DURATION_TOO_LONG, FRAME_TOO_SHORT,
    EMOJI_MISSING, EMOJI_TOO_MANY,
    TRAY_MISSING, TRAY_INVALID,
}

/**
 * @param stickerNumber 1-based sticker number for per-sticker issues, 0 otherwise.
 * @param value measured value (bytes, count, ms...) and [limit] the allowed one, for messages.
 */
data class PackIssue(
    val code: IssueCode,
    val stickerNumber: Int = 0,
    val value: Long = 0,
    val limit: Long = 0,
)

/** Pure validation of a pack against the WhatsApp sticker contract. */
object PackValidator {

    fun validate(
        name: String,
        publisher: String,
        stickers: List<StickerCheck>,
        tray: TrayCheck?,
    ): List<PackIssue> {
        val issues = mutableListOf<PackIssue>()

        if (name.isBlank()) issues += PackIssue(IssueCode.NAME_EMPTY)
        if (name.length > WhatsAppLimits.MAX_NAME_LENGTH) {
            issues += PackIssue(IssueCode.NAME_TOO_LONG, value = name.length.toLong(), limit = WhatsAppLimits.MAX_NAME_LENGTH.toLong())
        }
        if (publisher.isBlank()) issues += PackIssue(IssueCode.PUBLISHER_EMPTY)

        if (stickers.size < WhatsAppLimits.MIN_STICKERS) {
            issues += PackIssue(IssueCode.TOO_FEW_STICKERS, value = stickers.size.toLong(), limit = WhatsAppLimits.MIN_STICKERS.toLong())
        }
        if (stickers.size > WhatsAppLimits.MAX_STICKERS) {
            issues += PackIssue(IssueCode.TOO_MANY_STICKERS, value = stickers.size.toLong(), limit = WhatsAppLimits.MAX_STICKERS.toLong())
        }

        stickers.forEachIndexed { index, s ->
            val n = index + 1
            if (!s.exists) {
                issues += PackIssue(IssueCode.FILE_MISSING, n)
                return@forEachIndexed
            }
            val info = s.info
            if (info == null) {
                issues += PackIssue(IssueCode.FILE_CORRUPT, n)
                return@forEachIndexed
            }
            if (info.width != WhatsAppLimits.STICKER_SIZE || info.height != WhatsAppLimits.STICKER_SIZE) {
                issues += PackIssue(IssueCode.BAD_DIMENSIONS, n, value = info.width.toLong(), limit = WhatsAppLimits.STICKER_SIZE.toLong())
            }
            if (info.animated) {
                if (s.sizeBytes > WhatsAppLimits.MAX_ANIMATED_BYTES) {
                    issues += PackIssue(IssueCode.TOO_LARGE, n, s.sizeBytes, WhatsAppLimits.MAX_ANIMATED_BYTES.toLong())
                }
                if (info.totalDurationMs > WhatsAppLimits.MAX_ANIMATED_DURATION_MS) {
                    issues += PackIssue(IssueCode.DURATION_TOO_LONG, n, info.totalDurationMs, WhatsAppLimits.MAX_ANIMATED_DURATION_MS.toLong())
                }
                if (info.minFrameDurationMs < WhatsAppLimits.MIN_FRAME_DURATION_MS) {
                    issues += PackIssue(IssueCode.FRAME_TOO_SHORT, n, info.minFrameDurationMs.toLong(), WhatsAppLimits.MIN_FRAME_DURATION_MS.toLong())
                }
            } else {
                if (s.sizeBytes > WhatsAppLimits.MAX_STATIC_BYTES) {
                    issues += PackIssue(IssueCode.TOO_LARGE, n, s.sizeBytes, WhatsAppLimits.MAX_STATIC_BYTES.toLong())
                }
            }
            if (s.emojiCount < WhatsAppLimits.MIN_EMOJI) issues += PackIssue(IssueCode.EMOJI_MISSING, n)
            if (s.emojiCount > WhatsAppLimits.MAX_EMOJI) {
                issues += PackIssue(IssueCode.EMOJI_TOO_MANY, n, s.emojiCount.toLong(), WhatsAppLimits.MAX_EMOJI.toLong())
            }
        }

        if (tray == null || !tray.exists) {
            issues += PackIssue(IssueCode.TRAY_MISSING)
        } else if (tray.sizeBytes > WhatsAppLimits.MAX_TRAY_BYTES ||
            tray.width != WhatsAppLimits.TRAY_SIZE || tray.height != WhatsAppLimits.TRAY_SIZE
        ) {
            issues += PackIssue(IssueCode.TRAY_INVALID, value = tray.sizeBytes, limit = WhatsAppLimits.MAX_TRAY_BYTES.toLong())
        }

        return issues
    }
}
