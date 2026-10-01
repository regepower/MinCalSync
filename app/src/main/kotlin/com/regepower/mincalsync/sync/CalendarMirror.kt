package com.regepower.mincalsync.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.security.MessageDigest
import java.util.TimeZone

/** Run refused because it would delete suspiciously many copies (e.g. source calendar is still syncing). */
class SyncAbortedException(message: String) : IllegalStateException(message)

/**
 * One-way mirror of a source calendar into a target calendar, within a time window.
 *
 * Recurring events are read through the Instances table, so every occurrence (including
 * moved or cancelled ones) is copied as a single, plain event. That keeps the copy
 * correct without re-implementing recurrence rules and exceptions.
 *
 * Ownership is proven by a marker line `[mcs:<hash of source key>]` at the end of the
 * copy's description. The local [MirrorStore] is only a cache: after a phone change or
 * reinstall the mapping is rebuilt from the markers in the target calendar, so nothing
 * is duplicated and stale copies are still cleaned up. Copies from older versions (no
 * marker) are adopted when title/time/description match exactly, or when the local
 * mapping still proves them via title/start/end.
 *
 * Safety rules:
 *  - Only rows with our marker, or legacy rows proven by the mapping, are ever changed.
 *  - Past the window edge copies are kept untouched.
 *  - A run that would delete more than half of the owned copies (and more than
 *    [MIN_GUARDED_DELETIONS]) aborts before writing anything, unless the source calendar
 *    was changed on purpose.
 *  - There is no erase-and-rebuild step; an interrupted run never empties the calendar.
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
        var adopted: Int = 0,
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
        val marker: String = markerFor(key)

        /** Description as stored in the copy, including the ownership marker. */
        val copyDescription: String =
            if (description.isBlank()) "[mcs:$marker]" else description.trimEnd() + "\n\n[mcs:$marker]"
    }

    private data class TargetRow(
        val title: String,
        val description: String,
        val location: String,
        val start: Long,
        val end: Long,
        val allDay: Boolean,
    ) {
        val marker: String? = MARKER_REGEX.find(description)?.groupValues?.get(1)

        fun carriesFingerprint(entry: MirrorEntry) =
            title == entry.title && start == entry.start && end == entry.end

        fun hasContentOf(source: SourceInstance) =
            title == source.title && description.trim() == source.copyDescription.trim() &&
                location == source.location && start == source.begin &&
                end == source.end && allDay == source.allDay

        /** A copy from before markers existed: same content as the source, minus the marker. */
        fun isLegacyCopyOf(source: SourceInstance) =
            marker == null && title == source.title && description.trim() == source.description.trim() &&
                location == source.location && start == source.begin &&
                end == source.end && allDay == source.allDay
    }

    private class Plan(val source: SourceInstance, val rowId: Long?, val adopted: Boolean)

    fun run(
        sourceCalendarId: Long,
        targetCalendarId: Long,
        windowStart: Long,
        windowEnd: Long,
        dryRun: Boolean = false,
    ): Stats {
        require(sourceCalendarId != targetCalendarId) { "Quelle und Ziel sind identisch" }
        check(calendarExists(sourceCalendarId)) { "Quellkalender nicht gefunden" }
        check(isWritable(targetCalendarId)) { "Zielkalender fehlt oder ist nicht beschreibbar" }

        val sources = readSourceInstances(sourceCalendarId, windowStart, windowEnd)
        val rows = readTargetRows(targetCalendarId)
        val mapping = store.load()
        val stats = Stats()

        // --- Plan: decide which target row (if any) belongs to each source instance. ---
        val markedRows = HashMap<String, MutableList<Long>>()
        val legacyRows = HashMap<String, MutableList<Long>>()
        for ((id, row) in rows.entries.sortedBy { it.key }) {
            val marker = row.marker
            if (marker != null) {
                markedRows.getOrPut(marker) { mutableListOf() } += id
            } else {
                legacyRows.getOrPut(legacyKey(row.title, row.start, row.end, row.allDay)) { mutableListOf() } += id
            }
        }

        val claimed = HashSet<Long>()
        val plans = ArrayList<Plan>(sources.size)
        for (source in sources) {
            val entry = mapping[source.key]
            val mappedId = entry?.targetEventId?.takeIf { id ->
                val row = rows[id]
                row != null && (row.marker == source.marker || (row.marker == null && row.carriesFingerprint(entry)))
            }
            val markedId = markedRows[source.marker]?.firstOrNull { it !in claimed }
            val legacyId = legacyRows[legacyKey(source.title, source.begin, source.end, source.allDay)]
                ?.firstOrNull { it !in claimed && rows.getValue(it).isLegacyCopyOf(source) }

            val rowId = mappedId?.takeIf { it !in claimed } ?: markedId ?: legacyId
            if (rowId != null) claimed += rowId
            plans += Plan(source, rowId, adopted = rowId != null && entry?.targetEventId != rowId)
        }

        // --- Deletions: our copies (marker, or legacy via mapping) that no source instance claims. ---
        val seenKeys = sources.mapTo(HashSet()) { it.key }
        val deletions = LinkedHashSet<Long>()
        for ((id, row) in rows) {
            if (id in claimed || row.marker == null || row.start < windowStart) continue
            deletions += id
        }
        for ((key, entry) in mapping) {
            val row = rows[entry.targetEventId] ?: continue
            if (key !in seenKeys && entry.targetEventId !in claimed && row.marker == null &&
                entry.start >= windowStart && row.carriesFingerprint(entry)
            ) {
                deletions += entry.targetEventId
            }
        }

        val owned = claimed.size + deletions.size
        val sourceChanged = store.lastSourceId?.let { it != sourceCalendarId } ?: false
        if (!sourceChanged && deletions.size > MIN_GUARDED_DELETIONS && deletions.size * 2 > owned) {
            throw SyncAbortedException(
                "Abbruch: ${deletions.size} von $owned Kopien würden gelöscht " +
                    "(Quellkalender evtl. noch nicht synchronisiert). Nichts geändert.",
            )
        }

        // --- Apply. The mapping is only a cache, so it is written once at the end. ---
        val newMapping = mutableMapOf<String, MirrorEntry>()
        try {
            for (plan in plans) {
                val source = plan.source
                val row = plan.rowId?.let { rows[it] }
                when {
                    plan.rowId != null && row != null && row.hasContentOf(source) -> {
                        stats.unchanged++
                        if (plan.adopted) stats.adopted++
                        newMapping[source.key] = entryFor(plan.rowId, source)
                    }

                    plan.rowId != null && row != null -> {
                        if (dryRun || updateEvent(plan.rowId, source)) {
                            if (plan.adopted) stats.adopted++
                            stats.updated++
                            newMapping[source.key] = entryFor(plan.rowId, source)
                        } else {
                            stats.failed++
                            newMapping[source.key] = MirrorEntry(plan.rowId, row.title, row.start, row.end)
                        }
                    }

                    else -> {
                        if (dryRun) {
                            stats.created++
                        } else {
                            val newId = insertEvent(targetCalendarId, source)
                            if (newId != null) {
                                newMapping[source.key] = entryFor(newId, source)
                                stats.created++
                            } else {
                                stats.failed++
                            }
                        }
                    }
                }
            }

            for (id in deletions) {
                if (dryRun || deleteEvent(id)) stats.deleted++ else stats.failed++
            }
        } finally {
            if (!dryRun) {
                store.save(newMapping)
                store.lastSourceId = sourceCalendarId
            }
        }
        return stats
    }

    private fun entryFor(rowId: Long, source: SourceInstance) =
        MirrorEntry(rowId, source.title, source.begin, source.end)

    private fun legacyKey(title: String, start: Long, end: Long, allDay: Boolean) =
        "$start|$end|$allDay|$title"

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
        put(Events.DESCRIPTION, source.copyDescription)
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

private const val MIN_GUARDED_DELETIONS = 10
private val MARKER_REGEX = Regex("""\[mcs:([0-9a-f]{16})]\s*$""")

private fun markerFor(key: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}
