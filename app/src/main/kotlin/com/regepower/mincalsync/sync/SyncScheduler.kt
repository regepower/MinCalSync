package com.regepower.mincalsync.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.regepower.mincalsync.worker.CalendarSyncWorker
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val PERIODIC_WORK = "calendar_sync_periodic"
    private const val MANUAL_WORK = "calendar_sync_now"

    /** (Re)schedules the periodic sync; a changed interval replaces the old schedule. */
    fun schedulePeriodic(context: Context, intervalHours: Long) {
        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
            intervalHours.coerceAtLeast(1L), TimeUnit.HOURS,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK, ExistingWorkPolicy.KEEP, request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }
}
