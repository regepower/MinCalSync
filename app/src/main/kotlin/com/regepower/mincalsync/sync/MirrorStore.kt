package com.regepower.mincalsync.sync

import android.content.Context
import org.json.JSONObject

/**
 * One copied event as last written by MinCalSync: which row in the target calendar it
 * is, and the title/start/end we wrote there. The fingerprint lets us prove a row is
 * still ours before touching it - if the row id was reused by the provider or the user
 * edited the copy, it won't match and we leave the row alone.
 */
data class MirrorEntry(
    val targetEventId: Long,
    val title: String,
    val start: Long,
    val end: Long,
)

/**
 * Cache of which target-calendar rows MinCalSync created, keyed by source instance.
 * The authoritative proof of ownership is the marker in the copy's description (see
 * [CalendarMirror]), so losing this cache (new phone, reinstall) is harmless.
 * Stored per target calendar, so switching targets never lets us touch the old one.
 *
 * Kept locally because the CalendarProvider only lets sync adapters write
 * ExtendedProperties, so ownership can't be tagged on the event itself.
 */
class MirrorStore(context: Context, targetCalendarId: Long) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val storageKey = "map_$targetCalendarId"
    private val sourceKey = "src_$targetCalendarId"

    fun load(): MutableMap<String, MirrorEntry> {
        val raw = prefs.getString(storageKey, null) ?: return mutableMapOf()
        val result = mutableMapOf<String, MirrorEntry>()
        val json = JSONObject(raw)
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = json.getJSONObject(key)
            result[key] = MirrorEntry(
                targetEventId = item.getLong("id"),
                title = item.optString("t", ""),
                start = item.getLong("s"),
                end = item.getLong("e"),
            )
        }
        return result
    }

    /** Source calendar of the last real run; a different source makes mass deletion legitimate. */
    var lastSourceId: Long?
        get() = prefs.getLong(sourceKey, NO_ID).takeIf { it != NO_ID }
        set(value) {
            prefs.edit().putLong(sourceKey, value ?: NO_ID).commit()
        }

    /** Synchronous write: the worker may be killed right after, and the map must survive. */
    fun save(entries: Map<String, MirrorEntry>) {
        val json = JSONObject()
        for ((key, entry) in entries) {
            json.put(
                key,
                JSONObject()
                    .put("id", entry.targetEventId)
                    .put("t", entry.title)
                    .put("s", entry.start)
                    .put("e", entry.end),
            )
        }
        prefs.edit().putString(storageKey, json.toString()).commit()
    }

    companion object {
        private const val PREFS_NAME = "mincalsync_mirror"
        private const val NO_ID = -1L
    }
}
