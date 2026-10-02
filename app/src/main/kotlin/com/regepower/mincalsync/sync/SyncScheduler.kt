package com.regepower.mincalsync.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val PERIODIC_JOB_ID = 1

    /** (Re)schedules the periodic sync; survives reboots. Replaces any previous schedule. */
    fun schedulePeriodic(context: Context, intervalHours: Long) {
        val job = JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setPeriodic(TimeUnit.HOURS.toMillis(intervalHours.coerceAtLeast(1L)))
            .setPersisted(true)
            .build()
        scheduler(context).schedule(job)
    }

    fun cancel(context: Context) {
        scheduler(context).cancel(PERIODIC_JOB_ID)
    }

    fun isScheduled(context: Context): Boolean = scheduler(context).getPendingJob(PERIODIC_JOB_ID) != null

    private fun scheduler(context: Context): JobScheduler =
        context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
}
