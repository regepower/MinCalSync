package com.regepower.mincalsync.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.util.TimeZone

/** A sync that cannot start because the chosen calendars are unusable; retrying won't help. */
class SyncException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { SAME_CALENDAR, SOURCE_MISSING, TARGET_NOT_WRITABLE }
}

/**
 * One-way mirror of a source calendar into a target calendar, within a time window.
 *
 * Recurring events are read through the Instances table, so every occurrence (including
 * moved or cancelled ones) is copied as a single, plain event. That keeps the copy
 * correct without re-implementing recurrence rules and exceptions.
 *
 * Safety rules:
 *  - Only rows recorded in [MirrorStore] are ever updated or deleted.
 *  - Before updating or deleting, the row must still carry the title/start/end we wrote
 *    (or already hold the new source values). Anything else is left untouched.
 *  - There is no erase-and-rebuild step; an interrupted run leaves at worst some stale
 *    copies, never an emptied calendar.
 *  - Copies that fall out of the past edge of the window are kept, just no longer tracked.
 */
class CalendarMirror(
    private val resolver: ContentResolver,
    private val store: MirrorStore,
) {

    data class Stats(
        var created: Int = 0,
        var updated: Int = 0,
        var unchanged: Int = 0,
        var deleted: Int = 0,
        var failed: Int = 0,
    )

    private data class SourceInstance(
        val key: String,
        val title: String,
        val description: String,
        val location: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val timeZone: String?,
    )

    private data class TargetRow(
        val title: String,
        val description: String,
        val location: String,
        val start: Long,
        val end: Long,
        val allDay: Boolean,
    ) {
        fun carriesFingerprint(entry: MirrorEntry) =
            title == entry.title && start == entry.start && end == entry.end

        fun hasContentOf(source: SourceInstance) =
            title == source.title && description == source.description &&
                location == source.location && start == source.begin &&
                end == source.end && allDay == source.allDay
    }

    fun run(sourceCalendarId: Long, targetCalendarId: Long, windowStart: Long, windowEnd: Long): Stats {
        if (sourceCalendarId == targetCalendarId) throw SyncException(SyncException.Reason.SAME_CALENDAR)
        if (!calendarExists(sourceCalendarId)) throw SyncException(SyncException.Reason.SOURCE_MISSING)
        if (!isWritable(targetCalendarId)) throw SyncException(SyncException.Reason.TARGET_NOT_WRITABLE)

        val sourceInstances = readSourceInstances(sourceCalendarId, windowStart, windowEnd)
        val targetRows = readTargetRows(targetCalendarId)
        val mapping = store.load()
        val stats = Stats()
        val seenKeys = HashSet<String>()

        for (source in sourceInstances) {
            seenKeys += source.key
            val entry = mapping[source.key]
            val row = entry?.let { targetRows[it.targetEventId] }

            when {
                entry != null && row != null && row.hasContentOf(source) -> {
                    stats.unchanged++
                    val current = entry.copy(title = source.title, start = source.begin, end = source.end)
                    if (current != entry) {
                        mapping[source.key] = current
                        store.save(mapping)
                    }
                }

                entry != null && row != null && row.carriesFingerprint(entry) -> {
                    if (updateEvent(entry.targetEventId, source)) {
                        mapping[source.key] = entry.copy(title = source.title, start = source.begin, end = source.end)
                        store.save(mapping)
                        stats.updated++
                    } else {
                        stats.failed++
                    }
                }

                else -> {
                    // Not copied yet, or the old copy is gone / no longer provably ours.
                    val newId = insertEvent(targetCalendarId, source)
                    if (newId != null) {
                        mapping[source.key] = MirrorEntry(newId, source.title, source.begin, source.end)
                        store.save(mapping)
                        stats.created++
                    } else {
                        stats.failed++
                    }
                }
            }
        }

        val iterator = mapping.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (key in seenKeys) continue
            val row = targetRows[entry.targetEventId]
            when {
                row == null -> iterator.remove()
                entry.start < windowStart -> iterator.remove()
                !row.carriesFingerprint(entry) -> iterator.remove()
                else -> {
                    if (deleteEvent(entry.targetEventId)) {
                        iterator.remove()
                        stats.deleted++
                    } else {
                        stats.failed++
                    }
                }
            }
        }
        store.save(mapping)
        return stats
    }

    private fun calendarExists(calendarId: Long): Boolean =
        resolver.query(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
            arrayOf(Calendars._ID), null, null, null,
        )?.use { it.moveToFirst() } ?: false

    private fun isWritable(calendarId: Long): Boolean =
        resolver.query(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
            arrayOf(Calendars.CALENDAR_ACCESS_LEVEL), null, null, null,
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) >= Calendars.CAL_ACCESS_CONTRIBUTOR
        } ?: false

    /** Event id -> identifier that survives re-syncs of the source account. */
    private fun readStableIds(calendarId: Long): Map<Long, String> {
        val withSyncId = arrayOf(Events._ID, Events.UID_2445, Events._SYNC_ID)
        val withoutSyncId = arrayOf(Events._ID, Events.UID_2445)
        val selection = "${Events.CALENDAR_ID} = ?"
        val args = arrayOf(calendarId.toString())

        val cursor = try {
            resolver.query(Events.CONTENT_URI, withSyncId, selection, args, null)
        } catch (e: IllegalArgumentException) {
            resolver.query(Events.CONTENT_URI, withoutSyncId, selection, args, null)
        }

        val result = HashMap<Long, String>()
        cursor?.use {
            val syncIdIndex = it.getColumnIndex(Events._SYNC_ID)
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val uid = it.getString(1)
                val syncId = if (syncIdIndex >= 0) it.getString(syncIdIndex) else null
                result[id] = when {
                    !uid.isNullOrBlank() -> "uid:$uid"
                    !syncId.isNullOrBlank() -> "sync:$syncId"
                    else -> "id:$id"
                }
            }
        }
        return result
    }

    private fun readSourceInstances(calendarId: Long, windowStart: Long, windowEnd: Long): List<SourceInstance> {
        val stableIds = readStableIds(calendarId)
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, windowStart)
            ContentUris.appendId(it, windowEnd)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.BEGIN,
            Instances.END,
            Instances.TITLE,
            Instances.DESCRIPTION,
            Instances.EVENT_LOCATION,
            Instances.ALL_DAY,
            Instances.EVENT_TIMEZONE,
            Instances.STATUS,
        )
        val selection = "${Instances.CALENDAR_ID} = ?"
        val args = arrayOf(calendarId.toString())

        val result = mutableListOf<SourceInstance>()
        val keyCounts = HashMap<String, Int>()
        resolver.query(uri, projection, selection, args, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val status = if (c.isNull(8)) Events.STATUS_CONFIRMED else c.getInt(8)
                if (status == Events.STATUS_CANCELED) continue

                val eventId = c.getLong(0)
                val begin = c.getLong(1)
                val baseKey = "${stableIds[eventId] ?: "id:$eventId"}@$begin"
                val n = keyCounts.merge(baseKey, 1, Int::plus) ?: 1
                val key = if (n == 1) baseKey else "$baseKey#$n"

                result += SourceInstance(
                    key = key,
                    title = c.getString(3).orEmpty(),
                    description = c.getString(4).orEmpty(),
                    location = c.getString(5).orEmpty(),
                    begin = begin,
                    end = c.getLong(2),
                    allDay = c.getInt(6) == 1,
                    timeZone = c.getString(7),
                )
            }
        }
        return result
    }

    private fun readTargetRows(calendarId: Long): Map<Long, TargetRow> {
        val projection = arrayOf(
            Events._ID,
            Events.TITLE,
            Events.DESCRIPTION,
            Events.EVENT_LOCATION,
            Events.DTSTART,
            Events.DTEND,
            Events.ALL_DAY,
        )
        val selection = "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0"
        val args = arrayOf(calendarId.toString())

        val result = HashMap<Long, TargetRow>()
        resolver.query(Events.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                result[c.getLong(0)] = TargetRow(
                    title = c.getString(1).orEmpty(),
                    description = c.getString(2).orEmpty(),
                    location = c.getString(3).orEmpty(),
                    start = c.getLong(4),
                    end = if (c.isNull(5)) 0L else c.getLong(5),
                    allDay = c.getInt(6) == 1,
                )
            }
        }
        return result
    }

    private fun contentValuesFor(source: SourceInstance) = ContentValues().apply {
        put(Events.TITLE, source.title)
        put(Events.DESCRIPTION, source.description)
        put(Events.EVENT_LOCATION, source.location)
        put(Events.DTSTART, source.begin)
        put(Events.DTEND, source.end)
        put(Events.ALL_DAY, if (source.allDay) 1 else 0)
        // All-day events must be stored in UTC, timed ones keep the source zone.
        put(
            Events.EVENT_TIMEZONE,
            if (source.allDay) "UTC" else source.timeZone ?: TimeZone.getDefault().id,
        )
    }

    private fun insertEvent(calendarId: Long, source: SourceInstance): Long? = perEvent(null) {
        val values = contentValuesFor(source).apply { put(Events.CALENDAR_ID, calendarId) }
        resolver.insert(Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
    }

    private fun updateEvent(eventId: Long, source: SourceInstance): Boolean = perEvent(false) {
        resolver.update(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            contentValuesFor(source), null, null,
        ) > 0
    }

    private fun deleteEvent(eventId: Long): Boolean = perEvent(false) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) > 0
    }

    /**
     * One malformed event must not block the whole run, so provider errors are counted
     * as failures. A lost calendar permission is different and aborts the run.
     */
    private inline fun <T> perEvent(onError: T, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw e
    } catch (e: RuntimeException) {
        onError
    }
}
