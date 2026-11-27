package com.stickerstudio.app.audio

/**
 * What is known about WhatsApp sound stickers for third-party apps. Kept as data so the UI and the
 * export adapter say the same thing and it can be updated in one place.
 *
 * Source: github.com/WhatsApp/stickers issue #1163 (24 Nov 2026): the reply says the API "isn't
 * available yet for third party stickers, but it's on our roadmap", without format, field names,
 * limits or dates. Until a specification is published, nothing may be exported as a sound sticker.
 */
object WhatsAppSoundSupport {
    const val SUPPORTED = false
    const val REFERENCE = "https://github.com/WhatsApp/stickers/issues/1163"
}

sealed class SoundExportResult {
    /** The standard sticker (image only) can still be exported with the normal pipeline. */
    data class Unsupported(val reason: String) : SoundExportResult()
}

/**
 * Separate export path for sound stickers. It never touches the standard sticker export (the
 * content provider, the validator and the `.wastickers` writer keep working exactly as before).
 * Today it always answers [SoundExportResult.Unsupported]; when WhatsApp publishes the format, a
 * real implementation replaces [UnsupportedSoundExport] without changing any caller.
 */
interface SoundStickerExport {
    fun export(clip: AudioClip): SoundExportResult
}

object UnsupportedSoundExport : SoundStickerExport {
    override fun export(clip: AudioClip): SoundExportResult =
        SoundExportResult.Unsupported("WhatsApp has not published a sound-sticker format for third-party apps yet")
}
