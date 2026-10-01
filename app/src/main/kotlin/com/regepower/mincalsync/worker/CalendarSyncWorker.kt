package com.regepower.mincalsync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.regepower.mincalsync.sync.SyncRunner
import com.regepower.mincalsync.sync.SyncSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

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

        try {
            val stats = SyncRunner.execute(applicationContext, settings, dryRun = false)
            val message = SyncRunner.describe(stats, dryRun = false)
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
        val syncLock = Mutex()
    }
}
