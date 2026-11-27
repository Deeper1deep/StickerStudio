package com.stickerstudio.app.audio

import com.stickerstudio.app.core.MiniJson
import com.stickerstudio.app.core.asObject
import com.stickerstudio.app.core.optLong
import com.stickerstudio.app.core.optString

/** Where an audio clip came from. */
enum class AudioSourceKind { RECORDED, IMPORTED_FILE, EXTRACTED_FROM_VIDEO }

/**
 * Duration limits for sticker audio. These are SETTINGS, not WhatsApp rules: WhatsApp has not
 * published a sound-sticker specification for third-party apps (see [WhatsAppSoundSupport]).
 * The defaults are the figures that were reported when sound stickers launched.
 */
data class AudioLimits(
    val maxStaticMs: Long = 10_000,
    val maxAnimatedMs: Long = 5_000,
    val minMs: Long = 100,
) {
    fun maxFor(animated: Boolean): Long = if (animated) maxAnimatedMs else maxStaticMs

    companion object {
        val DEFAULT = AudioLimits()
    }
}

/**
 * The audio attached to ONE sticker. The audio lives in its own file ([fileName], inside the app's
 * audio folder); nothing is ever written into the sticker's image. [trimStartMs]..[trimEndMs] is the
 * part that is played; the original file is never cut, so trimming can be changed later.
 */
data class AudioClip(
    val fileName: String,
    /** Length of the whole audio file. */
    val durationMs: Long,
    val sourceKind: AudioSourceKind,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = durationMs,
) {
    /** The part that is actually played. */
    val playedMs: Long get() = trimEndMs - trimStartMs
}

enum class AudioIssueCode { DURATION_UNKNOWN, TRIM_INVALID, TOO_SHORT, TOO_LONG }

data class AudioIssue(val code: AudioIssueCode, val value: Long = 0, val limit: Long = 0)

sealed class TrimResult {
    data class Ok(val clip: AudioClip) : TrimResult()
    data class Rejected(val issue: AudioIssue) : TrimResult()
}

/** Pure rules for audio length and trimming (unit tested). */
object AudioRules {

    /** Problems of [clip] for a static or animated sticker; an empty list means it is fine. */
    fun validate(clip: AudioClip, animated: Boolean, limits: AudioLimits = AudioLimits.DEFAULT): List<AudioIssue> {
        if (clip.durationMs <= 0) return listOf(AudioIssue(AudioIssueCode.DURATION_UNKNOWN))
        if (clip.trimStartMs < 0 || clip.trimEndMs > clip.durationMs || clip.trimEndMs <= clip.trimStartMs) {
            return listOf(AudioIssue(AudioIssueCode.TRIM_INVALID, clip.trimEndMs - clip.trimStartMs, clip.durationMs))
        }
        val issues = ArrayList<AudioIssue>()
        if (clip.playedMs < limits.minMs) issues += AudioIssue(AudioIssueCode.TOO_SHORT, clip.playedMs, limits.minMs)
        val max = limits.maxFor(animated)
        if (clip.playedMs > max) issues += AudioIssue(AudioIssueCode.TOO_LONG, clip.playedMs, max)
        return issues
    }

    /** Sets the played range. The range must lie inside the file and respect the minimum length. */
    fun trim(clip: AudioClip, startMs: Long, endMs: Long, limits: AudioLimits = AudioLimits.DEFAULT): TrimResult {
        if (clip.durationMs <= 0) return TrimResult.Rejected(AudioIssue(AudioIssueCode.DURATION_UNKNOWN))
        if (startMs < 0 || endMs > clip.durationMs || endMs <= startMs) {
            return TrimResult.Rejected(AudioIssue(AudioIssueCode.TRIM_INVALID, endMs - startMs, clip.durationMs))
        }
        if (endMs - startMs < limits.minMs) {
            return TrimResult.Rejected(AudioIssue(AudioIssueCode.TOO_SHORT, endMs - startMs, limits.minMs))
        }
        return TrimResult.Ok(clip.copy(trimStartMs = startMs, trimEndMs = endMs))
    }

    /** Shortens the played range from the end so that it fits the limit; nothing else changes. */
    fun fitToLimit(clip: AudioClip, animated: Boolean, limits: AudioLimits = AudioLimits.DEFAULT): AudioClip {
        val max = limits.maxFor(animated)
        return if (clip.playedMs <= max) clip else clip.copy(trimEndMs = clip.trimStartMs + max)
    }
}

/** What a stored audio file looks like on disk, judged from its first bytes. */
enum class AudioFileStatus { OK, MISSING, EMPTY, UNRECOGNIZED }

enum class AudioFormat { OGG, M4A_MP4, WAV, MP3, AAC_ADTS, WEBM }

object AudioFiles {

    /** Recognises common audio containers by their magic bytes; null when it is none of them. */
    fun sniff(head: ByteArray): AudioFormat? {
        fun at(i: Int, s: String) = head.size >= i + s.length && s.indices.all { head[i + it] == s[it].code.toByte() }
        fun b(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        return when {
            at(0, "OggS") -> AudioFormat.OGG
            at(4, "ftyp") -> AudioFormat.M4A_MP4
            at(0, "RIFF") && at(8, "WAVE") -> AudioFormat.WAV
            at(0, "ID3") -> AudioFormat.MP3
            b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3 -> AudioFormat.WEBM
            b(0) == 0xFF && (b(1) and 0xF6) == 0xF0 -> AudioFormat.AAC_ADTS   // MPEG-4/2 AAC in ADTS
            b(0) == 0xFF && (b(1) and 0xE0) == 0xE0 -> AudioFormat.MP3        // MPEG audio frame sync
            else -> null
        }
    }

    /** Missing, empty and corrupted files are told apart so the UI can say what is wrong. */
    fun inspect(exists: Boolean, sizeBytes: Long, head: ByteArray): AudioFileStatus = when {
        !exists -> AudioFileStatus.MISSING
        sizeBytes <= 0 -> AudioFileStatus.EMPTY
        sniff(head) == null -> AudioFileStatus.UNRECOGNIZED
        else -> AudioFileStatus.OK
    }
}

/** Peak heights (0..1) for drawing a waveform from decoded 16-bit samples. */
object Waveform {
    fun peaks(samples: ShortArray, buckets: Int): FloatArray {
        if (buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        if (samples.isEmpty()) return out
        for (b in 0 until buckets) {
            val from = (b.toLong() * samples.size / buckets).toInt()
            val to = (((b + 1).toLong() * samples.size / buckets).toInt()).coerceAtLeast(from + 1).coerceAtMost(samples.size)
            var peak = 0
            for (i in from until to) {
                val v = kotlin.math.abs(samples[i].toInt())
                if (v > peak) peak = v
            }
            out[b] = (peak / 32768f).coerceIn(0f, 1f)
        }
        return out
    }
}

/** Saved form of a clip (used by the pack backup and later by the database layer). */
object AudioClipJson {

    fun encode(clip: AudioClip): String = MiniJson.stringify(
        linkedMapOf<String, Any?>(
            "file" to clip.fileName, "durationMs" to clip.durationMs, "source" to clip.sourceKind.name,
            "trimStartMs" to clip.trimStartMs, "trimEndMs" to clip.trimEndMs,
        )
    )

    /** Null for missing or damaged data. Old data without audio simply has no clip. */
    fun decode(json: String?): AudioClip? {
        if (json.isNullOrBlank()) return null
        val m = try {
            MiniJson.parse(json).asObject()
        } catch (e: com.stickerstudio.app.core.JsonParseException) {
            null
        } ?: return null
        val file = m.optString("file")?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("..") } ?: return null
        val duration = m.optLong("durationMs", 0)
        if (duration <= 0) return null
        val kind = AudioSourceKind.values().firstOrNull { it.name == m.optString("source") } ?: AudioSourceKind.IMPORTED_FILE
        val start = m.optLong("trimStartMs", 0).coerceIn(0, duration)
        val end = m.optLong("trimEndMs", duration).coerceIn(start, duration)
        return AudioClip(file, duration, kind, start, end)
    }
}
