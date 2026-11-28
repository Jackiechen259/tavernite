package com.stmobile.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stmobile.core.model.ReadingMode
import com.stmobile.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "tavernite_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val READING = stringPreferencesKey("reading_mode")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile_id")
        val STREAMING = booleanPreferencesKey("streaming_enabled")
        val CONTEXT_LENGTH = intPreferencesKey("context_length")
        val CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK_AT = longPreferencesKey("last_update_check_at")
        val SKIPPED_UPDATE_VERSION = stringPreferencesKey("skipped_update_version")
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: "") }.getOrDefault(ThemeMode.SYSTEM)
    }

    val readingMode: Flow<ReadingMode> = context.settingsDataStore.data.map { prefs ->
        runCatching { ReadingMode.valueOf(prefs[Keys.READING] ?: "") }.getOrDefault(ReadingMode.BUBBLE)
    }

    val activeProfileId: Flow<String?> = context.settingsDataStore.data.map { it[Keys.ACTIVE_PROFILE] }

    val streamingEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.STREAMING] ?: true }

    val contextLength: Flow<Int> = context.settingsDataStore.data.map { it[Keys.CONTEXT_LENGTH] ?: 8192 }

    val cacheEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.CACHE_ENABLED] ?: true }

    val autoUpdateCheck: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.AUTO_UPDATE_CHECK] ?: true
    }

    val lastUpdateCheckAt: Flow<Long> = context.settingsDataStore.data.map {
        it[Keys.LAST_UPDATE_CHECK_AT] ?: 0L
    }

    val skippedUpdateVersion: Flow<String?> = context.settingsDataStore.data.map {
        it[Keys.SKIPPED_UPDATE_VERSION]
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.settingsDataStore.edit {
        it[Keys.THEME] = mode.name
    }

    suspend fun setReadingMode(mode: ReadingMode) = context.settingsDataStore.edit {
        it[Keys.READING] = mode.name
    }

    suspend fun setActiveProfileId(id: String?) = context.settingsDataStore.edit { prefs ->
        if (id == null) prefs.remove(Keys.ACTIVE_PROFILE) else prefs[Keys.ACTIVE_PROFILE] = id
    }

    suspend fun setStreamingEnabled(enabled: Boolean) = context.settingsDataStore.edit {
        it[Keys.STREAMING] = enabled
    }

    suspend fun setContextLength(value: Int) = context.settingsDataStore.edit {
        it[Keys.CONTEXT_LENGTH] = value.coerceIn(1024, 200_000)
    }

    suspend fun setCacheEnabled(enabled: Boolean) = context.settingsDataStore.edit {
        it[Keys.CACHE_ENABLED] = enabled
    }

    suspend fun setAutoUpdateCheck(enabled: Boolean) = context.settingsDataStore.edit {
        it[Keys.AUTO_UPDATE_CHECK] = enabled
    }

    suspend fun setLastUpdateCheckAt(value: Long) = context.settingsDataStore.edit {
        it[Keys.LAST_UPDATE_CHECK_AT] = value
    }

    suspend fun setSkippedUpdateVersion(version: String?) = context.settingsDataStore.edit { prefs ->
        if (version == null) prefs.remove(Keys.SKIPPED_UPDATE_VERSION)
        else prefs[Keys.SKIPPED_UPDATE_VERSION] = version
    }
}
