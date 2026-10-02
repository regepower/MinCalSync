package com.regepower.mincalsync.sync

import android.app.job.JobParameters
import android.app.job.JobService

/** Periodic sync triggered by JobScheduler. Work runs off the main thread. */
class SyncJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val outcome = SyncRunner.run(this)
            jobFinished(params, outcome == SyncRunner.Outcome.RETRY)
        }.start()
        return true
    }

    /** The system stopped us early (constraints, quota). Retry later; partial runs are safe. */
    override fun onStopJob(params: JobParameters): Boolean = true
}
