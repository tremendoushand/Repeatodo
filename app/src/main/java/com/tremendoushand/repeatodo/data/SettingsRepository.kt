package com.tremendoushand.repeatodo.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    /** Times (minutes after midnight) when tasks without their own reminder are sent together. */
    val digestTimes: List<Int> = listOf(8 * 60),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val calendarEnabled: Boolean = false,
    val calendarId: Long? = null,
    val driveConnected: Boolean = false,
    /** 0 means manual only. */
    val syncIntervalHours: Int = 24,
    val syncWifiOnly: Boolean = true,
    val lastSyncAt: Long? = null,
    val lastSyncError: String? = null,
    val askedNotificationPermission: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val digestTimes = stringPreferencesKey("digest_times")
        val themeMode = stringPreferencesKey("theme_mode")
        val calendarEnabled = booleanPreferencesKey("calendar_enabled")
        val calendarId = longPreferencesKey("calendar_id")
        val driveConnected = booleanPreferencesKey("drive_connected")
        val syncInterval = intPreferencesKey("sync_interval_hours")
        val syncWifiOnly = booleanPreferencesKey("sync_wifi_only")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncError = stringPreferencesKey("last_sync_error")
        val askedNotification = booleanPreferencesKey("asked_notification_permission")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            digestTimes = this[Keys.digestTimes]?.let { raw ->
                raw.split(',').mapNotNull { it.trim().toIntOrNull() }.sorted()
            } ?: d.digestTimes,
            themeMode = this[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            calendarEnabled = this[Keys.calendarEnabled] ?: d.calendarEnabled,
            calendarId = this[Keys.calendarId],
            driveConnected = this[Keys.driveConnected] ?: d.driveConnected,
            syncIntervalHours = this[Keys.syncInterval] ?: d.syncIntervalHours,
            syncWifiOnly = this[Keys.syncWifiOnly] ?: d.syncWifiOnly,
            lastSyncAt = this[Keys.lastSyncAt],
            lastSyncError = this[Keys.lastSyncError],
            askedNotificationPermission = this[Keys.askedNotification] ?: false,
        )
    }

    suspend fun setDigestTimes(times: List<Int>) = context.dataStore.edit {
        it[Keys.digestTimes] = times.distinct().sorted().joinToString(",")
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.themeMode] = mode.name }

    suspend fun setCalendar(enabled: Boolean, calendarId: Long?) = context.dataStore.edit {
        it[Keys.calendarEnabled] = enabled
        if (calendarId != null) it[Keys.calendarId] = calendarId else it.remove(Keys.calendarId)
    }

    suspend fun setDriveConnected(connected: Boolean) = context.dataStore.edit {
        it[Keys.driveConnected] = connected
        if (!connected) it.remove(Keys.lastSyncError)
    }

    suspend fun setSyncInterval(hours: Int) = context.dataStore.edit { it[Keys.syncInterval] = hours }

    suspend fun setSyncWifiOnly(wifiOnly: Boolean) = context.dataStore.edit { it[Keys.syncWifiOnly] = wifiOnly }

    suspend fun setSyncResult(at: Long?, error: String?) = context.dataStore.edit {
        if (at != null) it[Keys.lastSyncAt] = at
        if (error != null) it[Keys.lastSyncError] = error else it.remove(Keys.lastSyncError)
    }

    suspend fun setAskedNotificationPermission() = context.dataStore.edit { it[Keys.askedNotification] = true }
}
