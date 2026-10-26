package com.stickerstudio.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User settings. They live in the app's private storage and survive app updates. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val SKIPPED_VERSION_CODE = intPreferencesKey("skipped_version_code")
        val DEFAULT_PUBLISHER = stringPreferencesKey("default_publisher")
    }

    val theme: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        ThemeMode.entries.firstOrNull { it.name == prefs[Keys.THEME] } ?: ThemeMode.DARK
    }

    val autoUpdateCheck: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.AUTO_UPDATE_CHECK] ?: true }

    val skippedVersionCode: Flow<Int> = context.settingsDataStore.data.map { it[Keys.SKIPPED_VERSION_CODE] ?: 0 }

    val defaultPublisher: Flow<String> = context.settingsDataStore.data.map { it[Keys.DEFAULT_PUBLISHER] ?: "" }

    suspend fun setTheme(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setAutoUpdateCheck(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_UPDATE_CHECK] = enabled }
    }

    suspend fun setSkippedVersionCode(code: Int) {
        context.settingsDataStore.edit { it[Keys.SKIPPED_VERSION_CODE] = code }
    }

    suspend fun setDefaultPublisher(name: String) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_PUBLISHER] = name.trim() }
    }
}
