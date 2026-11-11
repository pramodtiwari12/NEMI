package com.example.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager periodic worker that verifies [NEMIService] is still alive
 * whenever the user has left the master switch enabled.
 */
class ServiceWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceWatchdogScheduler.runWatchdogCheckNow(
            context = applicationContext,
            triggerSource = "WorkManager PeriodicWorker"
        )
        return Result.success()
    }
}
