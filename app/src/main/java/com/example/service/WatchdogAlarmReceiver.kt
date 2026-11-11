package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager broadcast receiver that complements [ServiceWatchdogWorker]
 * for rapid service recovery after task removal or OS low-memory kills.
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ServiceWatchdogScheduler.runWatchdogCheckNow(
            context = context.applicationContext,
            triggerSource = "AlarmManager Watchdog"
        )
    }
}
