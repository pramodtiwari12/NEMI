package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.actions.ActionExecutor
import com.example.apps.AppCatalogCache
import com.example.audio.MicrophoneVoskPipeline
import com.example.contacts.ContactCallCache
import com.example.telemetry.HeartbeatLogEntry
import com.example.telemetry.HeartbeatLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-Ready Offline Voice Assistant Foreground Service.
 */
class NEMIService : Service() {

    companion object {
        const val CHANNEL_ID_SERVICE = "NEMI_always_ready_channel"
        const val CHANNEL_ID_RECOVERY = "NEMI_boot_recovery_channel"
        const val NOTIFICATION_ID_FOREGROUND = 1001
        const val NOTIFICATION_ID_RECOVERY = 1002

        const val ACTION_START_SERVICE = "com.example.NEMI.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.NEMI.action.STOP_SERVICE"
        const val ACTION_PING_HEARTBEAT = "com.example.NEMI.action.PING_HEARTBEAT"
        const val ACTION_TEST_BEEP = "com.example.NEMI.action.TEST_BEEP"
        const val ACTION_MIC_TEST = "com.example.NEMI.action.MIC_TEST"
        const val ACTION_UPDATE_WAKE_PHRASE = "com.example.NEMI.action.UPDATE_WAKE_PHRASE"
        const val ACTION_SIMULATE_VOICE_COMMAND = "com.example.NEMI.action.SIMULATE_VOICE_COMMAND"

        const val EXTRA_START_REASON = "extra_start_reason"
        const val EXTRA_SPOKEN_COMMAND = "extra_spoken_command"
        const val EXTRA_WAKE_PHRASE = "extra_wake_phrase"

        private const val COMMAND_WAKELOCK_TIMEOUT_MS = 5_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        @Volatile
        var isRunningInstance: Boolean = false
            private set

        fun startServiceSafely(context: Context, reason: String = "User toggle"): Boolean {
            val appContext = context.applicationContext
            HeartbeatLogger.init(appContext)

            val hasMicPermission = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPermission) {
                val msg = "Blocked service start ($reason): RECORD_AUDIO permission not granted."
                HeartbeatLogger.log(appContext, HeartbeatLogEntry.EventType.ERROR, msg)
                ServiceStateHolder.update {
                    it.copy(
                        isRunning = false,
                        isMicOpen = false,
                        statusDetail = "Off"
                    )
                }
                return false
            }

            NEMIPrefs.setServiceEnabled(appContext, true)
            ServiceWatchdogScheduler.scheduleAllWatchdogs(appContext)

            val intent = Intent(appContext, NEMIService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra(EXTRA_START_REASON, reason)
            }

            return try {
                ContextCompat.startForegroundService(appContext, intent)
                true
            } catch (e: Exception) {
                val err = "startForegroundService failed ($reason): ${e.javaClass.simpleName}"
                HeartbeatLogger.log(appContext, HeartbeatLogEntry.EventType.ERROR, err)
                postRecoveryNotification(
                    context = appContext,
                    title = appContext.getString(R.string.notif_watchdog_recovery_title),
                    body = appContext.getString(R.string.notif_watchdog_recovery_body)
                )
                false
            }
        }

        fun stopServiceIntentionally(context: Context, reason: String = "User toggle off") {
            val appContext = context.applicationContext
            NEMIPrefs.setServiceEnabled(appContext, false)
            NEMIPrefs.setLastStopReason(appContext, reason)
            ServiceWatchdogScheduler.cancelAllWatchdogs(appContext)

            val intent = Intent(appContext, NEMIService::class.java).apply {
                action = ACTION_STOP_SERVICE
                putExtra(EXTRA_START_REASON, reason)
            }
            runCatching {
                appContext.stopService(intent)
            }
        }

        fun postRecoveryNotification(context: Context, title: String, body: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureNotificationChannels(context, nm)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_START_SERVICE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                201,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_RECOVERY)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            runCatching {
                nm.notify(NOTIFICATION_ID_RECOVERY, notification)
            }
        }

        fun ensureNotificationChannels(context: Context, nm: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val serviceChannel = NotificationChannel(
                    CHANNEL_ID_SERVICE,
                    context.getString(R.string.notif_channel_service_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notif_channel_service_desc)
                    setShowBadge(false)
                }

                val recoveryChannel = NotificationChannel(
                    CHANNEL_ID_RECOVERY,
                    context.getString(R.string.notif_channel_boot_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notif_channel_boot_desc)
                    enableVibration(true)
                }

                nm.createNotificationChannel(serviceChannel)
                nm.createNotificationChannel(recoveryChannel)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null
    private var wakeLockReleaseJob: Job? = null
    private var commandWakeLock: PowerManager.WakeLock? = null
    private var micVoskPipeline: MicrophoneVoskPipeline? = null
    private var intentionalStop = false

    override fun onCreate() {
        super.onCreate()
        HeartbeatLogger.init(applicationContext)
        ActionExecutor.initTts(applicationContext)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        commandWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NEMI::CommandProcessingWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        micVoskPipeline = MicrophoneVoskPipeline(
            context = applicationContext,
            onRequestCommandWakeLock = { durationMs, reason ->
                acquireCommandWakeLock(durationMs, reason)
            },
            onReleaseCommandWakeLock = { reason ->
                releaseCommandWakeLock(reason)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SERVICE
        val reason = intent?.getStringExtra(EXTRA_START_REASON)
            ?: if ((flags and START_FLAG_REDELIVERY) != 0) "OS redelivery"
            else if (intent == null) "OS START_STICKY recreate"
            else "Direct command"

        if (action == ACTION_STOP_SERVICE) {
            intentionalStop = true
            HeartbeatLogger.log(
                applicationContext,
                HeartbeatLogEntry.EventType.SERVICE_STOP,
                "Service stopped intentionally ($reason)"
            )
            stopForegroundAndSelf("Intentional stop: $reason")
            return START_NOT_STICKY
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            HeartbeatLogger.log(
                applicationContext,
                HeartbeatLogEntry.EventType.ERROR,
                "Cannot enter foreground: RECORD_AUDIO permission missing."
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val promoted = promoteToForeground(isProcessingCommand = false)
        if (!promoted) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunningInstance) {
            isRunningInstance = true
            val nowEpoch = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val wakePhrase = NEMIPrefs.getSelectedWakePhrase(applicationContext)
            NEMIPrefs.recordHeartbeat(applicationContext, nowEpoch)
            ServiceStateHolder.update {
                it.copy(
                    isRunning = true,
                    selectedWakePhrase = wakePhrase,
                    startedAtEpochMs = nowEpoch,
                    startedAtElapsedMs = nowElapsed,
                    lastHeartbeatEpochMs = nowEpoch,
                    watchdogRunCount = NEMIPrefs.getWatchdogCount(applicationContext),
                    lastStopReason = NEMIPrefs.getLastStopReason(applicationContext),
                    listeningMode = "Listening",
                    statusDetail = "Listening"
                )
            }
            HeartbeatLogger.log(
                applicationContext,
                HeartbeatLogEntry.EventType.SERVICE_START,
                "NEMIService started [wake='$wakePhrase', reason=$reason]"
            )

            // Start Contact/CallLog cache & Launchable App Catalog cache
            ContactCallCache.startObserving(applicationContext) { _ -> }
            AppCatalogCache.startObserving(applicationContext)

            // Start persistent 16 kHz AudioRecord + Vosk loop
            micVoskPipeline?.start()
            startPeriodicHeartbeatLoop()
        }

        when (action) {
            ACTION_PING_HEARTBEAT -> {
                emitHeartbeat("Manual heartbeat ping")
            }
            ACTION_TEST_BEEP -> {
                micVoskPipeline?.playWakeBeepAndVibrate(reason = "Diagnostics Beep Test button")
            }
            ACTION_MIC_TEST -> {
                micVoskPipeline?.triggerDiagnosticsMicTest()
            }
            ACTION_UPDATE_WAKE_PHRASE -> {
                val phrase = intent?.getStringExtra(EXTRA_WAKE_PHRASE)
                    ?: NEMIPrefs.getSelectedWakePhrase(applicationContext)
                micVoskPipeline?.updateWakePhrase(phrase)
                promoteToForeground(isProcessingCommand = false)
            }
            ACTION_SIMULATE_VOICE_COMMAND -> {
                val cmdText = intent?.getStringExtra(EXTRA_SPOKEN_COMMAND).orEmpty()
                if (cmdText.isNotBlank()) {
                    micVoskPipeline?.simulateSpokenWakeAndCommand(cmdText)
                }
            }
        }

        return START_STICKY
    }

    private fun promoteToForeground(isProcessingCommand: Boolean): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannels(this, nm)

        val notification = buildForegroundNotification(isProcessingCommand)
        return try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID_FOREGROUND, notification, fgsType)
            true
        } catch (e: Exception) {
            HeartbeatLogger.log(
                applicationContext,
                HeartbeatLogEntry.EventType.ERROR,
                "ServiceCompat.startForeground failed: ${e.javaClass.simpleName}"
            )
            false
        }
    }

    private fun buildForegroundNotification(isProcessingCommand: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NEMIService::class.java).apply {
            action = ACTION_STOP_SERVICE
            putExtra(EXTRA_START_REASON, "Notification Stop button")
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakePhrase = NEMIPrefs.getSelectedWakePhrase(this)
        val subtitle = if (isProcessingCommand) {
            getString(R.string.notif_service_processing)
        } else {
            "Say \"$wakePhrase\" • Listening"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notif_action_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun startPeriodicHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                emitHeartbeat("Periodic 60s service alive check")
            }
        }
    }

    private fun emitHeartbeat(note: String) {
        val now = System.currentTimeMillis()
        NEMIPrefs.recordHeartbeat(applicationContext, now)
        val uptimeSec = (SystemClock.elapsedRealtime() - ServiceStateHolder.status.value.startedAtElapsedMs) / 1000L
        ServiceStateHolder.update {
            it.copy(
                heartbeatCount = it.heartbeatCount + 1,
                lastHeartbeatEpochMs = now
            )
        }
        HeartbeatLogger.log(
            applicationContext,
            HeartbeatLogEntry.EventType.HEARTBEAT,
            "$note (uptime=${uptimeSec}s, micOpen=${ServiceStateHolder.status.value.isMicOpen})"
        )
    }

    fun acquireCommandWakeLock(
        durationMs: Long = COMMAND_WAKELOCK_TIMEOUT_MS,
        reason: String = "Voice command window"
    ) {
        val safeTimeout = durationMs.coerceAtMost(COMMAND_WAKELOCK_TIMEOUT_MS)
        commandWakeLock?.acquire(safeTimeout)
        ServiceStateHolder.update {
            it.copy(isCommandWakeLockHeld = true)
        }
        promoteToForeground(isProcessingCommand = true)
        HeartbeatLogger.log(
            applicationContext,
            HeartbeatLogEntry.EventType.WAKELOCK_ACQUIRED,
            "Acquired PARTIAL_WAKE_LOCK for ${safeTimeout}ms ($reason)"
        )

        wakeLockReleaseJob?.cancel()
        wakeLockReleaseJob = serviceScope.launch {
            delay(safeTimeout)
            releaseCommandWakeLock("Timeout (${safeTimeout}ms) elapsed")
        }
    }

    fun releaseCommandWakeLock(reason: String = "Command completed") {
        wakeLockReleaseJob?.cancel()
        if (commandWakeLock?.isHeld == true) {
            runCatching { commandWakeLock?.release() }
        }
        if (isRunningInstance) {
            promoteToForeground(isProcessingCommand = false)
        }
        ServiceStateHolder.update {
            it.copy(isCommandWakeLockHeld = false)
        }
        HeartbeatLogger.log(
            applicationContext,
            HeartbeatLogEntry.EventType.WAKELOCK_RELEASED,
            "Released PARTIAL_WAKE_LOCK ($reason)"
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val shouldStayAlive = NEMIPrefs.isServiceEnabled(applicationContext)
        HeartbeatLogger.log(
            applicationContext,
            HeartbeatLogEntry.EventType.TASK_REMOVED,
            "onTaskRemoved() fired (enabled=$shouldStayAlive). Scheduling watchdog recovery."
        )
        if (shouldStayAlive) {
            ServiceWatchdogScheduler.scheduleImmediateRecoveryAlarm(applicationContext, delayMs = 2_500L)
            ServiceWatchdogScheduler.scheduleAllWatchdogs(applicationContext)
        }
    }

    override fun onDestroy() {
        val wasEnabled = NEMIPrefs.isServiceEnabled(applicationContext)
        val stopReason = if (intentionalStop || !wasEnabled) {
            "User stopped service"
        } else {
            "Unexpected system/OEM termination while enabled"
        }

        NEMIPrefs.setLastStopReason(applicationContext, stopReason)
        micVoskPipeline?.stop()
        ContactCallCache.stopObserving(applicationContext)
        AppCatalogCache.stopObserving(applicationContext)
        ActionExecutor.shutdownTts()

        if (commandWakeLock?.isHeld == true) {
            runCatching { commandWakeLock?.release() }
        }
        heartbeatJob?.cancel()
        wakeLockReleaseJob?.cancel()
        serviceScope.cancel()
        isRunningInstance = false

        ServiceStateHolder.update {
            it.copy(
                isRunning = false,
                isMicOpen = false,
                isCommandWakeLockHeld = false,
                listeningMode = "Off",
                lastStopReason = stopReason,
                statusDetail = "Off"
            )
        }

        HeartbeatLogger.log(
            applicationContext,
            HeartbeatLogEntry.EventType.SERVICE_STOP,
            "onDestroy() called: $stopReason"
        )

        if (wasEnabled && !intentionalStop) {
            ServiceWatchdogScheduler.scheduleImmediateRecoveryAlarm(applicationContext, delayMs = 3_000L)
        }

        super.onDestroy()
    }

    private fun stopForegroundAndSelf(reason: String) {
        NEMIPrefs.setLastStopReason(applicationContext, reason)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
