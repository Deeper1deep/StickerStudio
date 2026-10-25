package com.stickerstudio.app.whatsapp

import android.content.Context

/**
 * Remembers which version of each pack (image_data_version) was last sent to WhatsApp, so the app
 * can offer "Update in WhatsApp" once the pack has been edited afterwards.
 */
object WaSyncStore {
    private const val FILE = "wa_sync"

    fun lastSent(context: Context, packId: String): Int? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (prefs.contains(packId)) prefs.getInt(packId, 0) else null
    }

    fun record(context: Context, packId: String, version: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(packId, version).apply()
    }

    fun forget(context: Context, packId: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(packId).apply()
    }
}
