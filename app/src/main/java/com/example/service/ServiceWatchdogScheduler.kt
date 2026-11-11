package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.telemetry.HeartbeatLogEntry
import com.example.telemetry.HeartbeatLogger
import java.util.concurrent.TimeUnit

/**
 * Coordinates both WorkManager (15-min periodic check) and AlarmManager (inexact/wakeup fallback)
 * to ensure [NEMIService] is monitored and restarted if killed by the OS or task swipe.
 */
object ServiceWatchdogScheduler {
    private const val PERIODIC_WORK_NAME = "NEMI_periodic_watchdog"
    private const val ALARM_REQUEST_CODE = 4040
    private const val ALARM_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * Schedules both the periodic WorkManager watchdog and the AlarmManager repeating check.
     */
    fun scheduleAllWatchdogs(context: Context) {
        val appContext = context.applicationContext
        schedulePeriodicWork(appContext)
        scheduleRepeatingAlarm(appContext)
    }

    /**
     * Cancels all scheduled watchdog jobs when the user intentionally disables NEMI.
     */
    fun cancelAllWatchdogs(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
        runCatching {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(buildAlarmPendingIntent(appContext))
        }
    }

    /**
     * Triggers an immediate watchdog check (e.g., from the UI test button or onTaskRemoved).
     */
    fun runWatchdogCheckNow(context: Context, triggerSource: String) {
        val appContext = context.applicationContext
        val count = NEMIPrefs.incrementWatchdogCount(appContext)
        val now = System.currentTimeMillis()
        val shouldBeRunning = NEMIPrefs.isServiceEnabled(appContext)
        val actuallyRunning = NEMIService.isRunningInstance

        ServiceStateHolder.update {
            it.copy(
                lastWatchdogCheckEpochMs = now,
                watchdogRunCount = count
            )
        }

        if (!shouldBeRunning) {
            HeartbeatLogger.log(
                appContext,
                HeartbeatLogEntry.EventType.WATCHDOG_CHECK,
                "Watchdog #$count ($triggerSource): Service disabled by user; no action needed."
            )
            return
        }

        if (actuallyRunning) {
            HeartbeatLogger.log(
                appContext,
                HeartbeatLogEntry.EventType.WATCHDOG_CHECK,
                "Watchdog #$count ($triggerSource): NEMIService is healthy and running."
            )
        } else {
            HeartbeatLogger.log(
                appContext,
                HeartbeatLogEntry.EventType.WATCHDOG_RESTART,
                "Watchdog #$count ($triggerSource): Detected dead service while enabled! Attempting restart."
            )
            NEMIService.startServiceSafely(
                context = appContext,
                reason = "Watchdog recovery ($triggerSource)"
            )
        }
    }

    /**
     * Schedules a short-delay wakeup alarm via [AlarmManager] when [NEMIService.onTaskRemoved]
     * or an unexpected `onDestroy` occurs.
     */
    fun scheduleImmediateRecoveryAlarm(context: Context, delayMs: Long = 2_500L) {
        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        runCatching {
            am.setWindow(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                2_000L,
                buildAlarmPendingIntent(appContext)
            )
        }
    }

    private fun schedulePeriodicWork(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    fun enqueueOneTimeWorkCheck(context: Context) {
        runCatching {
            val req = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>().build()
            WorkManager.getInstance(context.applicationContext).enqueue(req)
        }
    }

    private fun scheduleRepeatingAlarm(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val firstTrigger = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                firstTrigger,
                ALARM_INTERVAL_MS,
                buildAlarmPendingIntent(context)
            )
        }
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
