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
 * Ownership: [MirrorStore] maps source instances to target rows, and every copy carries an
 * invisible [CopyMarker] with the source tag. When the map is missing (new phone, account
 * re-added, row IDs changed) copies are re-adopted instead of duplicated: first marked rows,
 * then - in case the marker got lost - unmarked rows with the same title, start and end.
 *
 * Safety rules:
 *  - Only rows recorded in the map or carrying our marker are ever updated or deleted.
 *  - A mapped row is only changed if it still holds the title/start/end we wrote (or
 *    already the new source values); a copy the user edited keeps its content and only
 *    loses the marker, so it is never touched again.
 *  - Marked copies without a source in the window are deleted; anything outside the
 *    window is never touched.
 *  - There is no erase-and-rebuild step; an interrupted run leaves at worst some stale
 *    copies, never an emptied calendar.
 */
class CalendarMirror(
    private val resolver: ContentResolver,
    private val store: MirrorStore,
    private val ownTag: Int,
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
    ) {
        val slot: Triple<String, Long, Long> get() = Triple(title, begin, end)
    }

    private data class TargetRow(
        val id: Long,
        val title: String,
        /** Without markers. */
        val description: String,
        val location: String,
        val start: Long,
        val end: Long,
        val allDay: Boolean,
        val markerTag: Int?,
    ) {
        val slot: Triple<String, Long, Long> get() = Triple(title, start, end)

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

        // Forget map entries whose row is gone; what is left over is up for adoption.
        mapping.values.removeAll { it.targetEventId !in targetRows }
        val mappedIds = mapping.values.mapTo(HashSet()) { it.targetEventId }
        val unmapped = targetRows.values.filter { it.id !in mappedIds }
        val ownUnmapped = unmapped.filter { it.markerTag == ownTag }.groupByTo(HashMap()) { it.slot }
        val plainUnmapped = unmapped.filter { it.markerTag == null }.groupByTo(HashMap()) { it.slot }

        for (source in sourceInstances) {
            seenKeys += source.key
            val entry = mapping[source.key]
            val row = entry?.let { targetRows[it.targetEventId] }

            when {
                entry != null && row != null && row.hasContentOf(source) &&
                    (row.markerTag == ownTag || entry.marked) -> {
                    stats.unchanged++
                    val current = entry.copy(title = source.title, start = source.begin, end = source.end)
                    if (current != entry) {
                        mapping[source.key] = current
                        store.save(mapping)
                    }
                }

                entry != null && row != null && row.carriesFingerprint(entry) -> {
                    // Changed in the source, or written by a version without marker.
                    if (updateEvent(entry.targetEventId, source)) {
                        mapping[source.key] = entry.copy(
                            title = source.title, start = source.begin, end = source.end, marked = true,
                        )
                        store.save(mapping)
                        stats.updated++
                    } else {
                        stats.failed++
                    }
                }

                else -> {
                    // A copy the user edited stays, but is no longer ours.
                    if (entry != null && row != null) release(row)
                    val adopted = ownUnmapped.take(source.slot) ?: plainUnmapped.take(source.slot)
                    if (adopted != null) {
                        adopt(adopted, source, mapping, stats)
                    } else {
                        // Not copied yet, or the old copy is gone / no longer provably ours.
                        val newId = insertEvent(targetCalendarId, source)
                        if (newId != null) {
                            mapping[source.key] = MirrorEntry(newId, source.title, source.begin, source.end, marked = true)
                            store.save(mapping)
                            stats.created++
                        } else {
                            stats.failed++
                        }
                    }
                }
            }
        }

        // An empty source is more likely a half-finished account sync than a calendar that
        // really lost every event: delete nothing then, the next run catches up.
        if (sourceInstances.isEmpty()) {
            store.save(mapping)
            return stats
        }

        val iterator = mapping.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (key in seenKeys) continue
            val row = targetRows[entry.targetEventId]
            when {
                row == null -> iterator.remove()
                entry.start < windowStart -> iterator.remove()
                !row.carriesFingerprint(entry) -> {
                    release(row)
                    iterator.remove()
                }
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

        // Our marked copies that no source instance claimed: removed from the source
        // while the map was lost. Only inside the window, like everything else.
        for (row in ownUnmapped.values.flatten()) {
            if (row.start < windowStart || row.start >= windowEnd) continue
            if (deleteEvent(row.id)) stats.deleted++ else stats.failed++
        }
        return stats
    }

    /** Removes our marker from a copy the user edited, so it is never touched again. */
    private fun release(row: TargetRow) {
        if (row.markerTag != ownTag) return
        perEvent(Unit) {
            val values = ContentValues().apply { put(Events.DESCRIPTION, row.description) }
            resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, row.id), values, null, null)
        }
    }

    private fun adopt(row: TargetRow, source: SourceInstance, mapping: MutableMap<String, MirrorEntry>, stats: Stats) {
        if (row.hasContentOf(source) && row.markerTag == ownTag) {
            stats.unchanged++
        } else if (updateEvent(row.id, source)) {
            stats.updated++
        } else {
            stats.failed++
            return
        }
        mapping[source.key] = MirrorEntry(row.id, source.title, source.begin, source.end, marked = true)
        store.save(mapping)
    }

    private fun HashMap<Triple<String, Long, Long>, MutableList<TargetRow>>.take(
        slot: Triple<String, Long, Long>,
    ): TargetRow? {
        val rows = get(slot) ?: return null
        val row = rows.removeAt(0)
        if (rows.isEmpty()) remove(slot)
        return row
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
                    description = CopyMarker.strip(c.getString(4).orEmpty()),
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
                val rawDescription = c.getString(2).orEmpty()
                result[c.getLong(0)] = TargetRow(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    description = CopyMarker.strip(rawDescription),
                    location = c.getString(3).orEmpty(),
                    start = c.getLong(4),
                    end = if (c.isNull(5)) 0L else c.getLong(5),
                    allDay = c.getInt(6) == 1,
                    markerTag = CopyMarker.tagOf(rawDescription),
                )
            }
        }
        return result
    }

    private fun contentValuesFor(source: SourceInstance) = ContentValues().apply {
        put(Events.TITLE, source.title)
        put(Events.DESCRIPTION, source.description + CopyMarker.encode(ownTag))
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
