package com.regepower.mincalsync.sync

import android.content.Context
import android.util.Log
import com.regepower.mincalsync.R
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** Runs one sync and records a localized one-line result in [SyncSettings]. */
object SyncRunner {

    enum class Outcome { SUCCESS, RETRY, FAILED }

    private const val TAG = "MinCalSync"
    private const val PAST_WINDOW_DAYS = 30L
    private const val FUTURE_WINDOW_DAYS = 365L

    /** Periodic job and "sync now" must never write to the target calendar at the same time. */
    private val lock = Any()

    fun run(context: Context): Outcome = synchronized(lock) {
        val app = context.applicationContext
        val settings = SyncSettings(app)
        if (!settings.isSourceChosen || (settings.targetRef == null && settings.targetCalendarId == null)) {
            settings.recordResult(app.getString(R.string.result_not_configured))
            return Outcome.FAILED
        }

        val now = System.currentTimeMillis()
        val windowStart = now - TimeUnit.DAYS.toMillis(PAST_WINDOW_DAYS)
        val windowEnd = now + TimeUnit.DAYS.toMillis(FUTURE_WINDOW_DAYS)

        try {
            // Calendars are found by account + name; row IDs may differ from the saved ones.
            val calendars = CalendarRepository(app.contentResolver).loadCalendars()
            val legacyTargetId = settings.targetCalendarId
            val source = settings.source(calendars) ?: throw SyncException(SyncException.Reason.SOURCE_MISSING)
            val target = settings.target(calendars) ?: throw SyncException(SyncException.Reason.TARGET_NOT_WRITABLE)
            if (source.id == target.id) throw SyncException(SyncException.Reason.SAME_CALENDAR)
            settings.refreshIds(calendars)

            val stats = CalendarMirror(
                app.contentResolver,
                MirrorStore(app, target.ref, legacyTargetId),
                CopyMarker.tagFor(source.ref),
            ).run(source.id, target.id, windowStart, windowEnd)
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
            var message = app.getString(
                R.string.result_ok, time, stats.created, stats.updated, stats.deleted, stats.unchanged,
            )
            if (stats.failed > 0) message += app.getString(R.string.result_failed_suffix, stats.failed)
            settings.recordResult(message)
            Outcome.SUCCESS
        } catch (e: SecurityException) {
            Log.w(TAG, "calendar permission missing", e)
            settings.recordResult(app.getString(R.string.result_no_permission))
            Outcome.FAILED
        } catch (e: SyncException) {
            val text = when (e.reason) {
                SyncException.Reason.SAME_CALENDAR -> R.string.result_not_configured
                SyncException.Reason.SOURCE_MISSING -> R.string.result_source_missing
                SyncException.Reason.TARGET_NOT_WRITABLE -> R.string.result_target_not_writable
            }
            settings.recordResult(app.getString(text))
            Outcome.FAILED
        } catch (e: RuntimeException) {
            Log.w(TAG, "sync failed, will retry", e)
            settings.recordResult(app.getString(R.string.result_retry, e.javaClass.simpleName))
            Outcome.RETRY
        }
    }
}
