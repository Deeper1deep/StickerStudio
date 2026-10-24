package com.stickerstudio.app.core

/**
 * Limits taken from WhatsApp's official "Sticker contract" for third-party sticker apps
 * (github.com/WhatsApp/stickers). They are centralised here so that the editor, the
 * encoder, the validator and the UI all agree. If WhatsApp changes its rules, change
 * them in this single file.
 */
object WhatsAppLimits {
    const val STICKER_SIZE = 512
    const val TRAY_SIZE = 96

    const val MAX_STATIC_BYTES = 100 * 1024
    const val MAX_ANIMATED_BYTES = 500 * 1024
    const val MAX_TRAY_BYTES = 50 * 1024

    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30

    const val MAX_ANIMATED_DURATION_MS = 10_000
    const val MIN_FRAME_DURATION_MS = 8

    const val MIN_EMOJI = 1
    const val MAX_EMOJI = 3

    const val MAX_NAME_LENGTH = 128
}
