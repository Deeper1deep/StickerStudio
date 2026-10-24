package com.stickerstudio.app.whatsapp

import com.stickerstudio.app.core.WhatsAppLimits
import com.stickerstudio.app.sticker.WebpInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackValidatorTest {

    private fun staticInfo(w: Int = 512, h: Int = 512) =
        WebpInfo(w, h, animated = false, hasAlpha = true, frameCount = 1, totalDurationMs = 0, minFrameDurationMs = 0)

    private fun animInfo(durationMs: Long = 3000, minFrame: Int = 80) =
        WebpInfo(512, 512, animated = true, hasAlpha = true, frameCount = 30, totalDurationMs = durationMs, minFrameDurationMs = minFrame)

    private fun ok(n: Int, info: WebpInfo = staticInfo(), size: Long = 40_000, emoji: Int = 1) =
        StickerCheck("s$n.webp", true, size, info, emoji)

    private val goodTray = TrayCheck(true, 12_000, 96, 96)

    private fun codes(issues: List<PackIssue>) = issues.map { it.code }

    @Test
    fun validStaticPackHasNoIssues() {
        val issues = PackValidator.validate("Pack", "Me", (1..5).map { ok(it) }, goodTray)
        assertEquals(emptyList<PackIssue>(), issues)
    }

    @Test
    fun validAnimatedPackHasNoIssues() {
        val issues = PackValidator.validate("Pack", "Me", (1..3).map { ok(it, animInfo(), size = 300_000) }, goodTray)
        assertEquals(emptyList<PackIssue>(), issues)
    }

    @Test
    fun packNeedsAtLeastThreeStickers() {
        val issues = PackValidator.validate("Pack", "Me", (1..2).map { ok(it) }, goodTray)
        assertEquals(listOf(IssueCode.TOO_FEW_STICKERS), codes(issues))
    }

    @Test
    fun packCannotExceedThirtyStickers() {
        val issues = PackValidator.validate("Pack", "Me", (1..31).map { ok(it) }, goodTray)
        assertEquals(listOf(IssueCode.TOO_MANY_STICKERS), codes(issues))
    }

    @Test
    fun blankNameAndPublisherAreReported() {
        val issues = PackValidator.validate("  ", "", (1..3).map { ok(it) }, goodTray)
        assertTrue(codes(issues).containsAll(listOf(IssueCode.NAME_EMPTY, IssueCode.PUBLISHER_EMPTY)))
    }

    @Test
    fun staticStickerOver100KbIsRejected() {
        val big = ok(1, size = WhatsAppLimits.MAX_STATIC_BYTES + 1L)
        val issues = PackValidator.validate("P", "Me", listOf(big, ok(2), ok(3)), goodTray)
        assertEquals(1, issues.size)
        assertEquals(IssueCode.TOO_LARGE, issues[0].code)
        assertEquals(1, issues[0].stickerNumber)
    }

    @Test
    fun animatedStickerLimitsAre500KbAnd10SecondsAnd8ms() {
        val tooBig = ok(1, animInfo(), size = WhatsAppLimits.MAX_ANIMATED_BYTES + 1L)
        val tooLong = ok(2, animInfo(durationMs = 10_001))
        val tooFast = ok(3, animInfo(minFrame = 4))
        val issues = PackValidator.validate("P", "Me", listOf(tooBig, tooLong, tooFast), goodTray)
        assertEquals(
            listOf(IssueCode.TOO_LARGE, IssueCode.DURATION_TOO_LONG, IssueCode.FRAME_TOO_SHORT),
            codes(issues),
        )
    }

    @Test
    fun wrongDimensionsAreReported() {
        val issues = PackValidator.validate("P", "Me", listOf(ok(1, staticInfo(500, 512)), ok(2), ok(3)), goodTray)
        assertEquals(listOf(IssueCode.BAD_DIMENSIONS), codes(issues))
    }

    @Test
    fun mixingAnimatedAndStaticIsAllowed() {
        val issues = PackValidator.validate("P", "Me", listOf(ok(1), ok(2), ok(3, animInfo())), goodTray)
        assertEquals(emptyList<IssueCode>(), codes(issues))
    }

    @Test
    fun missingAndCorruptFilesAreReported() {
        val missing = StickerCheck("a.webp", false, 0, null, 1)
        val corrupt = StickerCheck("b.webp", true, 100, null, 1)
        val issues = PackValidator.validate("P", "Me", listOf(missing, corrupt, ok(3)), goodTray)
        assertEquals(listOf(IssueCode.FILE_MISSING, IssueCode.FILE_CORRUPT), codes(issues))
    }

    @Test
    fun emojiCountMustBeBetweenOneAndThree() {
        val issues = PackValidator.validate("P", "Me", listOf(ok(1, emoji = 0), ok(2, emoji = 4), ok(3, emoji = 3)), goodTray)
        assertEquals(listOf(IssueCode.EMOJI_MISSING, IssueCode.EMOJI_TOO_MANY), codes(issues))
    }

    @Test
    fun trayIconRules() {
        val stickers = (1..3).map { ok(it) }
        assertEquals(listOf(IssueCode.TRAY_MISSING), codes(PackValidator.validate("P", "Me", stickers, null)))
        assertEquals(listOf(IssueCode.TRAY_MISSING), codes(PackValidator.validate("P", "Me", stickers, TrayCheck(false, 0, 0, 0))))
        assertEquals(listOf(IssueCode.TRAY_INVALID), codes(PackValidator.validate("P", "Me", stickers, TrayCheck(true, 60_000, 96, 96))))
        assertEquals(listOf(IssueCode.TRAY_INVALID), codes(PackValidator.validate("P", "Me", stickers, TrayCheck(true, 5_000, 128, 128))))
    }
}
