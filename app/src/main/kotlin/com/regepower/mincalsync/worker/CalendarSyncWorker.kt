package com.regepower.mincalsync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.regepower.mincalsync.sync.CalendarMirror
import com.regepower.mincalsync.sync.MirrorStore
import com.regepower.mincalsync.sync.SyncSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class CalendarSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = syncLock.withLock {
        val settings = SyncSettings(applicationContext)
        val sourceId = settings.sourceCalendarId
        val targetId = settings.targetCalendarId

        if (sourceId == null || targetId == null || sourceId == targetId) {
            settings.recordResult("Nicht gestartet: Quell- und Zielkalender wählen")
            return@withLock Result.failure()
        }

        val now = System.currentTimeMillis()
        val windowStart = now - TimeUnit.DAYS.toMillis(PAST_WINDOW_DAYS)
        val windowEnd = now + TimeUnit.DAYS.toMillis(FUTURE_WINDOW_DAYS)

        try {
            val stats = CalendarMirror(
                applicationContext.contentResolver,
                MirrorStore(applicationContext, targetId),
            ).run(sourceId, targetId, windowStart, windowEnd)

            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
            val message = "$time: ${stats.created} neu, ${stats.updated} geändert, " +
                "${stats.deleted} gelöscht, ${stats.unchanged} unverändert" +
                if (stats.failed > 0) ", ${stats.failed} Fehler" else ""
            settings.recordResult(message)
            Timber.d("Sync done: %s", message)
            Result.success()
        } catch (e: SecurityException) {
            settings.recordResult("Fehler: Kalenderberechtigung fehlt")
            Timber.e(e, "Calendar permission missing")
            Result.failure()
        } catch (e: IllegalStateException) {
            settings.recordResult("Fehler: ${e.message}")
            Timber.e(e, "Sync aborted")
            Result.failure()
        } catch (e: Exception) {
            settings.recordResult("Fehler, neuer Versuch folgt: ${e.message}")
            Timber.e(e, "Sync failed, will retry")
            Result.retry()
        }
    }

    companion object {
        /** Periodic and manual runs must never write to the same calendar concurrently. */
        private val syncLock = Mutex()
        private const val PAST_WINDOW_DAYS = 30L
        private const val FUTURE_WINDOW_DAYS = 365L
    }
}
