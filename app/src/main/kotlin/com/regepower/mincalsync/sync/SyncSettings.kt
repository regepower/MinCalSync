package com.regepower.mincalsync.sync

import android.content.Context
import android.content.SharedPreferences

/** User choices (source, target, interval) plus the outcome of the last sync run. */
class SyncSettings(context: Context) {

    /** Raw store, also used for config export/import. */
    val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sourceCalendarId: Long?
        get() = prefs.getLong(KEY_SOURCE, NO_ID).takeIf { it != NO_ID }
        set(value) = prefs.edit().putLong(KEY_SOURCE, value ?: NO_ID).apply()

    var targetCalendarId: Long?
        get() = prefs.getLong(KEY_TARGET, NO_ID).takeIf { it != NO_ID }
        set(value) = prefs.edit().putLong(KEY_TARGET, value ?: NO_ID).apply()

    /** Device-independent choice; the IDs above are only a per-device cache. */
    val sourceRef: CalendarRef? get() = CalendarRef.decode(prefs.getString(KEY_SOURCE_REF, null))
    val targetRef: CalendarRef? get() = CalendarRef.decode(prefs.getString(KEY_TARGET_REF, null))

    val isSourceChosen: Boolean get() = sourceRef != null || sourceCalendarId != null

    fun source(calendars: List<CalendarInfo>) = CalendarRepository.resolve(calendars, sourceRef, sourceCalendarId)
    fun target(calendars: List<CalendarInfo>) = CalendarRepository.resolve(calendars, targetRef, targetCalendarId)

    fun setSource(calendar: CalendarInfo?) = store(KEY_SOURCE_REF, KEY_SOURCE, calendar)
    fun setTarget(calendar: CalendarInfo?) = store(KEY_TARGET_REF, KEY_TARGET, calendar)

    /**
     * Brings the ID cache in line with the refs (calendar re-created, other phone, imported
     * config) and gives settings from before refs existed their ref.
     */
    fun refreshIds(calendars: List<CalendarInfo>) {
        source(calendars)?.let { if (it.id != sourceCalendarId || sourceRef == null) setSource(it) }
        target(calendars)?.let { if (it.id != targetCalendarId || targetRef == null) setTarget(it) }
    }

    private fun store(refKey: String, idKey: String, calendar: CalendarInfo?) {
        val editor = prefs.edit()
        if (calendar == null) {
            editor.remove(refKey).remove(idKey)
        } else {
            editor.putString(refKey, calendar.ref.encode()).putLong(idKey, calendar.id)
        }
        editor.apply()
    }

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
        private const val KEY_SOURCE_REF = "source_calendar"
        private const val KEY_TARGET_REF = "target_calendar"
        private const val KEY_INTERVAL = "interval_hours"
        private const val KEY_AUTO = "auto_sync"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_LAST_RUN = "last_run"
        private const val NO_ID = -1L
        const val DEFAULT_INTERVAL_HOURS = 24L

        /** Calendar IDs differ per phone and the last result is runtime state: not exported.
         *  The calendar refs (account + name) are exported, so a config moves to another phone. */
        val DEVICE_KEYS = setOf(KEY_SOURCE, KEY_TARGET, KEY_LAST_RESULT, KEY_LAST_RUN)
    }
}
