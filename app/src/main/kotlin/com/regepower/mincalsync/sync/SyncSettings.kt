package com.regepower.mincalsync.sync

import android.content.Context

/** User choices (source, target, interval) plus the outcome of the last sync run. */
class SyncSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sourceCalendarId: Long?
        get() = prefs.getLong(KEY_SOURCE, NO_ID).takeIf { it != NO_ID }
        set(value) = prefs.edit().putLong(KEY_SOURCE, value ?: NO_ID).apply()

    var targetCalendarId: Long?
        get() = prefs.getLong(KEY_TARGET, NO_ID).takeIf { it != NO_ID }
        set(value) = prefs.edit().putLong(KEY_TARGET, value ?: NO_ID).apply()

    var intervalHours: Long
        get() = prefs.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_HOURS)
        set(value) = prefs.edit().putLong(KEY_INTERVAL, value).apply()

    var autoSync: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    val lastResult: String?
        get() = prefs.getString(KEY_LAST_RESULT, null)

    val lastRunMillis: Long
        get() = prefs.getLong(KEY_LAST_RUN, 0L)

    fun recordResult(message: String) {
        prefs.edit()
            .putString(KEY_LAST_RESULT, message)
            .putLong(KEY_LAST_RUN, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "mincalsync_settings"
        private const val KEY_SOURCE = "source_calendar_id"
        private const val KEY_TARGET = "target_calendar_id"
        private const val KEY_INTERVAL = "interval_hours"
        private const val KEY_AUTO = "auto_sync"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_LAST_RUN = "last_run"
        private const val NO_ID = -1L
        const val DEFAULT_INTERVAL_HOURS = 24L
    }
}
