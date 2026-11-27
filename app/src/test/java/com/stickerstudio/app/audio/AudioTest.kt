package com.stickerstudio.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTest {

    private fun clip(duration: Long = 4_000, start: Long = 0, end: Long = duration) =
        AudioClip("a.ogg", duration, AudioSourceKind.RECORDED, start, end)

    // ---- duration validation (limits are settings)

    @Test fun clipWithinLimitsIsFine() {
        assertTrue(AudioRules.validate(clip(4_000), animated = true).isEmpty())
        assertTrue(AudioRules.validate(clip(9_000), animated = false).isEmpty())
    }

    @Test fun tooLongDependsOnStickerKind() {
        val c = clip(8_000)
        assertEquals(AudioIssueCode.TOO_LONG, AudioRules.validate(c, animated = true).single().code)
        assertTrue(AudioRules.validate(c, animated = false).isEmpty())
    }

    @Test fun limitsAreConfigurable() {
        val custom = AudioLimits(maxStaticMs = 3_000, maxAnimatedMs = 2_000)
        assertEquals(AudioIssueCode.TOO_LONG, AudioRules.validate(clip(4_000), false, custom).single().code)
        assertTrue(AudioRules.validate(clip(4_000), false, AudioLimits(maxStaticMs = 60_000)).isEmpty())
    }

    @Test fun zeroOrTinyDurationIsRejected() {
        assertEquals(AudioIssueCode.DURATION_UNKNOWN, AudioRules.validate(clip(0), false).single().code)
        assertEquals(AudioIssueCode.TOO_SHORT, AudioRules.validate(clip(50), false).single().code)
    }

    // ---- trimming

    @Test fun trimKeepsTheOriginalAndSetsTheRange() {
        val r = AudioRules.trim(clip(8_000), 1_000, 4_500) as TrimResult.Ok
        assertEquals(1_000, r.clip.trimStartMs)
        assertEquals(4_500, r.clip.trimEndMs)
        assertEquals(3_500, r.clip.playedMs)
        assertEquals(8_000, r.clip.durationMs)
    }

    @Test fun badTrimRangesAreRejected() {
        val c = clip(8_000)
        for ((s, e) in listOf(-1L to 1_000L, 2_000L to 1_000L, 1_000L to 9_000L, 1_000L to 1_000L)) {
            val r = AudioRules.trim(c, s, e)
            assertEquals("$s..$e", AudioIssueCode.TRIM_INVALID, (r as TrimResult.Rejected).issue.code)
        }
        assertEquals(AudioIssueCode.TOO_SHORT, ((AudioRules.trim(c, 0, 50)) as TrimResult.Rejected).issue.code)
    }

    @Test fun trimmedClipUsesTheTrimmedLengthForValidation() {
        val c = (AudioRules.trim(clip(12_000), 0, 4_000) as TrimResult.Ok).clip
        assertTrue(AudioRules.validate(c, animated = true).isEmpty())
    }

    @Test fun fitToLimitShortensFromTheEnd() {
        val fitted = AudioRules.fitToLimit(clip(12_000, start = 2_000), animated = true)
        assertEquals(2_000, fitted.trimStartMs)
        assertEquals(7_000, fitted.trimEndMs)
        assertTrue(AudioRules.validate(fitted, true).isEmpty())
        val same = clip(3_000)
        assertEquals(same, AudioRules.fitToLimit(same, true))
    }

    @Test fun invalidStoredTrimIsFlagged() {
        assertEquals(AudioIssueCode.TRIM_INVALID, AudioRules.validate(clip(4_000, 3_000, 2_000), false).single().code)
        assertEquals(AudioIssueCode.TRIM_INVALID, AudioRules.validate(clip(4_000, 0, 5_000), false).single().code)
    }

    // ---- missing / corrupted files

    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun fileStatusTellsMissingEmptyAndCorrupt() {
        assertEquals(AudioFileStatus.MISSING, AudioFiles.inspect(false, 0, ByteArray(0)))
        assertEquals(AudioFileStatus.EMPTY, AudioFiles.inspect(true, 0, ByteArray(0)))
        assertEquals(AudioFileStatus.UNRECOGNIZED, AudioFiles.inspect(true, 100, bytes("this is not audio at all")))
        assertEquals(AudioFileStatus.OK, AudioFiles.inspect(true, 100, bytes("OggS\u0000\u0002")))
    }

    @Test fun commonContainersAreRecognised() {
        assertEquals(AudioFormat.OGG, AudioFiles.sniff(bytes("OggS....")))
        assertEquals(AudioFormat.M4A_MP4, AudioFiles.sniff(bytes("\u0000\u0000\u0000 ftypM4A ")))
        assertEquals(AudioFormat.WAV, AudioFiles.sniff(bytes("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")))
        assertEquals(AudioFormat.MP3, AudioFiles.sniff(bytes("ID3\u0003")))
        assertEquals(AudioFormat.WEBM, AudioFiles.sniff(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
        assertEquals(AudioFormat.AAC_ADTS, AudioFiles.sniff(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50)))
        assertNull(AudioFiles.sniff(ByteArray(0)))
        assertNull(AudioFiles.sniff(bytes("PK\u0003\u0004")))
    }

    // ---- sticker with / without audio, old data

    @Test fun jsonRoundTripOfAClipWithTrim() {
        val c = AudioClip("clip_1.m4a", 7_500, AudioSourceKind.EXTRACTED_FROM_VIDEO, 500, 4_000)
        assertEquals(c, AudioClipJson.decode(AudioClipJson.encode(c)))
    }

    @Test fun stickersWithoutAudioStayWithoutAudio() {
        assertNull(AudioClipJson.decode(null))
        assertNull(AudioClipJson.decode(""))
        assertNull(AudioClipJson.decode("{}"))
        assertNull(AudioClipJson.decode("not json"))
    }

    @Test fun damagedOrUnsafeAudioDataIsIgnored() {
        assertNull(AudioClipJson.decode("""{"file":"../../etc/passwd","durationMs":1000}"""))
        assertNull(AudioClipJson.decode("""{"file":"a/b.ogg","durationMs":1000}"""))
        assertNull(AudioClipJson.decode("""{"file":"a.ogg","durationMs":0}"""))
        val c = AudioClipJson.decode("""{"file":"a.ogg","durationMs":1000,"trimStartMs":-5,"trimEndMs":99999,"source":"???"}""")!!
        assertEquals(0, c.trimStartMs)
        assertEquals(1000, c.trimEndMs)
        assertEquals(AudioSourceKind.IMPORTED_FILE, c.sourceKind)
    }

    // ---- waveform

    @Test fun waveformPeaksAreNormalisedAndSized() {
        val s = ShortArray(1000) { if (it in 500..520) 16384 else 0 }
        val p = Waveform.peaks(s, 10)
        assertEquals(10, p.size)
        assertEquals(0.5f, p.max(), 0.001f)
        assertEquals(0f, p[0], 0f)
        assertEquals(0, Waveform.peaks(s, 0).size)
        assertTrue(Waveform.peaks(ShortArray(0), 5).all { it == 0f })
    }

    // ---- export adapter

    @Test fun soundExportIsReportedAsUnsupportedNeverFaked() {
        assertFalse(WhatsAppSoundSupport.SUPPORTED)
        val r = UnsupportedSoundExport.export(clip())
        assertTrue(r is SoundExportResult.Unsupported)
    }
}
