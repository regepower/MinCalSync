package com.regepower.mincalsync.worker

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import timber.log.Timber
import java.util.*

class CalendarSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Timber.d("Starting calendar sync...")

            val sourceCalendarId = getCalendarId("Exchange") // Example: Exchange calendar
            val targetCalendarId = getCalendarId("Google") // Example: Google Calendar

            if (sourceCalendarId == null || targetCalendarId == null) {
                Timber.w("Source or target calendar not found")
                return Result.retry()
            }

            syncCalendars(sourceCalendarId, targetCalendarId)

            Timber.d("Calendar sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Calendar sync failed")
            Result.retry()
        }
    }

    private fun getCalendarId(calendarName: String): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val selection = "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$calendarName%")

        return applicationContext.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
            } else {
                null
            }
        }
    }

    private fun syncCalendars(sourceCalendarId: Long, targetCalendarId: Long) {
        val sourceEvents = getCalendarEvents(sourceCalendarId)
        Timber.d("Found ${sourceEvents.size} events in source calendar")

        sourceEvents.forEach { event ->
            addOrUpdateEvent(targetCalendarId, event)
        }
    }

    private fun getCalendarEvents(calendarId: Long): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.UID
        )
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ?"
        val selectionArgs = arrayOf(calendarId.toString())

        applicationContext.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                events.add(
                    CalendarEvent(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)),
                        title = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)) ?: "",
                        description = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)) ?: "",
                        dtStart = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)),
                        dtEnd = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)),
                        uid = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.UID)) ?: UUID.randomUUID().toString()
                    )
                )
            }
        }

        return events
    }

    private fun addOrUpdateEvent(targetCalendarId: Long, event: CalendarEvent) {
        val existingEventId = findEventByUid(targetCalendarId, event.uid)

        if (existingEventId != null) {
            updateEvent(existingEventId, event)
        } else {
            createEvent(targetCalendarId, event)
        }
    }

    private fun findEventByUid(calendarId: Long, uid: String): Long? {
        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.UID} = ?"
        val selectionArgs = arrayOf(calendarId.toString(), uid)

        return applicationContext.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events._ID))
            } else {
                null
            }
        }
    }

    private fun createEvent(calendarId: Long, event: CalendarEvent) {
        val contentValues = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.DTSTART, event.dtStart)
            put(CalendarContract.Events.DTEND, event.dtEnd)
            put(CalendarContract.Events.UID, event.uid)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        applicationContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, contentValues)
        Timber.d("Created event: ${event.title}")
    }

    private fun updateEvent(eventId: Long, event: CalendarEvent) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val contentValues = android.content.ContentValues().apply {
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.DTSTART, event.dtStart)
            put(CalendarContract.Events.DTEND, event.dtEnd)
        }

        applicationContext.contentResolver.update(uri, contentValues, null, null)
        Timber.d("Updated event: ${event.title}")
    }

    data class CalendarEvent(
        val id: Long,
        val title: String,
        val description: String,
        val dtStart: Long,
        val dtEnd: Long,
        val uid: String
    )
}
