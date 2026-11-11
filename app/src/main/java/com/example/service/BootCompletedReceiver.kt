package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.R
import com.example.telemetry.HeartbeatLogEntry
import com.example.telemetry.HeartbeatLogger

/**
 * Handles device boot (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`) and app updates (`MY_PACKAGE_REPLACED`).
 *
 * ### Android 15 (API 35+) Microphone Foreground Service Boot Limitation:
 * Starting with Android 15, apps targeting API 35+ are prohibited by the OS from directly
 * starting a `microphone` foreground service from a `BOOT_COMPLETED` broadcast receiver.
 *
 * Therefore, on boot:
 * 1. We log the boot event to [HeartbeatLogger].
 * 2. We re-register the [ServiceWatchdogScheduler].
 * 3. On Android 15+ (or if direct start throws `ForegroundServiceStartNotAllowedException`),
 *    we post a high-priority persistent notification (`Tap to activate NEMI`) that
 *    launches [com.example.MainActivity] and immediately starts the service in a user-initiated context.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        HeartbeatLogger.init(appContext)

        val wasEnabled = NEMIPrefs.isServiceEnabled(appContext)
        HeartbeatLogger.log(
            appContext,
            HeartbeatLogEntry.EventType.BOOT_RECEIVED,
            "Received $action (sdk=${Build.VERSION.SDK_INT}, serviceEnabled=$wasEnabled)"
        )

        if (!wasEnabled) return

        // Re-arm periodic watchdogs across reboots
        ServiceWatchdogScheduler.scheduleAllWatchdogs(appContext)

        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15+ blocks starting FOREGROUND_SERVICE_TYPE_MICROPHONE from BOOT_COMPLETED.
            // Show a high-priority tap-to-start notification as required by Section 9.
            HeartbeatLogger.log(
                appContext,
                HeartbeatLogEntry.EventType.BOOT_RECEIVED,
                "Android 15+ detected: posting tap-to-start notification for microphone service."
            )
            NEMIService.postRecoveryNotification(
                context = appContext,
                title = appContext.getString(R.string.notif_boot_title),
                body = appContext.getString(R.string.notif_boot_body)
            )
        } else {
            // On Android 8..14, attempt direct start and fall back to notification if blocked
            NEMIService.startServiceSafely(
                context = appContext,
                reason = "BootCompletedReceiver ($action)"
            )
        }
    }
}
