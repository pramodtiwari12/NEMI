package com.example.actions

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.apps.AppCatalogCache
import com.example.apps.AppMatchOutcome
import com.example.apps.AppMatcher
import com.example.apps.InstalledAppEntry
import com.example.contacts.CachedContact
import com.example.contacts.ContactCallCache
import com.example.contacts.ContactMatchOutcome
import com.example.contacts.ContactMatcher
import com.example.contacts.NumberChoiceResult
import com.example.contacts.PhoneNumberNormalizer
import com.example.parser.BrightnessAction
import com.example.parser.Command
import com.example.parser.DeviceQueryType
import com.example.parser.MediaAction
import com.example.parser.SystemPanelType
import com.example.parser.VolumeAction
import com.example.service.PendingDisambiguation
import com.example.service.ServiceStateHolder
import com.example.telemetry.HeartbeatLogEntry
import com.example.telemetry.HeartbeatLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * On-device action executor for all supported NEMI commands:
 * - Call (with CallLog smart number scoring + 2-digit disambiguation)
 * - Torch on/off
 * - Alarm
 * - Open App (with alias learning, phonetic + Levenshtein matching, and lock-screen check)
 * - Set Timer
 * - Volume up/down/mute/percent
 * - Brightness up/down/percent
 * - Time / Date / Battery (with on-device TextToSpeech reply)
 * - Media Play / Pause / Next / Previous
 * - Wi-Fi / Bluetooth / Do Not Disturb settings panels
 *
 * Strictly enforces Section D privacy: never writes contact names or full phone numbers to logs.
 */
object ActionExecutor {

    private const val CHANNEL_ID_ACTIONS = "NEMI_action_feedback_channel"
    private const val NOTIF_ID_ACTION_STATUS = 2200

    private var tts: TextToSpeech? = null

    @Volatile
    private var isTtsReady = false

    fun initTts(context: Context) {
        if (tts != null) return
        val appContext = context.applicationContext
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    isTtsReady = true
                }
            }
        }
    }

    fun shutdownTts() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isTtsReady = false
    }

    private fun speakShortReply(context: Context, text: String) {
        if (!isTtsReady) {
            initTts(context)
        }
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NEMI_utt_${SystemClock.elapsedRealtime()}")
        }
    }

    fun executeParsedCommand(
        context: Context,
        rawSpokenText: String,
        command: Command,
        speechEndElapsedMs: Long = SystemClock.elapsedRealtime(),
        overrideContactsForTest: List<CachedContact>? = null,
        overrideAppsForTest: List<InstalledAppEntry>? = null
    ) {
        val appContext = context.applicationContext
        val keyguardManager = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked == true

        // Privacy-safe log of parsed command (redacts contact names)
        HeartbeatLogger.log(
            appContext,
            HeartbeatLogEntry.EventType.COMMAND_PARSED,
            "Parsed: ${command.toSummaryString(redactContactName = true)} (locked=$isLocked)"
        )

        var numberSelectionNote: String? = null
        val outcome: Pair<Boolean, String> = when (command) {
            is Command.PickOption -> {
                resolvePendingDisambiguation(appContext, command.indexOneBased, isLocked)
            }
            is Command.Torch -> executeTorch(appContext, command.on)
            is Command.Alarm -> executeAlarm(appContext, command.hour24, command.minute)
            is Command.Call -> {
                val contacts = overrideContactsForTest ?: ContactCallCache.cachedContacts.value
                val callResult = executeCall(appContext, command.name, contacts)
                numberSelectionNote = callResult.logSafeNumberNote
                callResult.isSuccess to callResult.logSafeStatusMessage
            }
            is Command.OpenApp -> {
                val apps = overrideAppsForTest ?: AppCatalogCache.installedApps.value
                executeOpenApp(appContext, command.appQuery, apps, isLocked)
            }
            is Command.SetTimer -> executeTimer(appContext, command.durationSeconds)
            is Command.Volume -> executeVolume(appContext, command.action, command.percent)
            is Command.Brightness -> executeBrightness(appContext, command.action, command.percent)
            is Command.DeviceQuery -> executeDeviceQuery(appContext, command.queryType)
            is Command.Media -> executeMediaControl(appContext, command.mediaAction)
            is Command.SystemPanel -> executeSystemPanel(appContext, command.panelType, isLocked)
            is Command.Unknown -> {
                val msg = "Didn't understand"
                showActionNotification(appContext, "NEMI", msg)
                false to msg
            }
        }

        val actionDoneElapsedMs = SystemClock.elapsedRealtime()
        val latencyMs = (actionDoneElapsedMs - speechEndElapsedMs).coerceAtLeast(1L)

        val (isSuccess, logSafeStatus) = outcome
        val eventType = if (isSuccess) {
            HeartbeatLogEntry.EventType.ACTION_DONE
        } else {
            HeartbeatLogEntry.EventType.ACTION_FAILED
        }

        val safeRawSpoken = if (command is Command.Call) "call <contact>" else rawSpokenText

        HeartbeatLogger.log(
            appContext,
            eventType,
            "$logSafeStatus [latency=${latencyMs}ms, locked=$isLocked]"
        )

        HeartbeatLogger.recordCommandResult(
            rawSpokenText = safeRawSpoken,
            parsedDescription = command.toSummaryString(redactContactName = true),
            numberSelectionDetail = numberSelectionNote,
            actionStatus = logSafeStatus,
            isSuccess = isSuccess,
            latencyMs = latencyMs,
            wasScreenLocked = isLocked
        )
    }

    /**
     * Resolves a pending 1/2/3 disambiguation choice (either from voice "one/two/three" or dialog tap).
     */
    fun resolvePendingDisambiguation(
        context: Context,
        optionIndexOneBased: Int,
        isLocked: Boolean = false
    ): Pair<Boolean, String> {
        val pending = ServiceStateHolder.status.value.pendingDisambiguation
            ?: return false to "No pending choice to select"

        val idx = optionIndexOneBased - 1
        ServiceStateHolder.update { it.copy(pendingDisambiguation = null) }

        return when (pending) {
            is PendingDisambiguation.AppChoice -> {
                val chosenApp = pending.topApps.getOrNull(idx)
                    ?: return false to "Invalid option $optionIndexOneBased"
                // Save spoken phrase as a learned alias for this app (Section B.4)
                AppCatalogCache.saveLearnedAlias(context, pending.spokenQuery, chosenApp.label)
                launchResolvedApp(context, chosenApp, isLocked, matchedVia = "User choice #$optionIndexOneBased (alias saved)")
            }
            is PendingDisambiguation.ContactNumberChoice -> {
                val rawNumber = pending.internalNumbersToDial.getOrNull(idx)
                    ?: return false to "Invalid number option $optionIndexOneBased"
                val last2 = PhoneNumberNormalizer.endingDigits(rawNumber, count = 2)
                HeartbeatLogger.log(
                    context,
                    HeartbeatLogEntry.EventType.NUMBER_SELECTED,
                    "User selected option #$optionIndexOneBased (ending **$last2)"
                )
                placePhoneCallToNumber(
                    context = context,
                    spokenContactName = pending.spokenName,
                    rawNumber = rawNumber,
                    last2Digits = last2
                )
            }
        }
    }

    fun dismissPendingDisambiguation() {
        ServiceStateHolder.update { it.copy(pendingDisambiguation = null) }
    }

    // =========================================================================
    // Section B: App Opening
    // =========================================================================

    private fun executeOpenApp(
        context: Context,
        spokenQuery: String,
        installedApps: List<InstalledAppEntry>,
        isLocked: Boolean
    ): Pair<Boolean, String> {
        val match = AppMatcher.matchApp(
            rawQuery = spokenQuery,
            installedApps = installedApps,
            aliasTable = AppCatalogCache.aliasMap.value
        )

        return when (match) {
            is AppMatchOutcome.ConfidentMatch -> {
                launchResolvedApp(context, match.app, isLocked, match.matchedVia)
            }
            is AppMatchOutcome.Ambiguous -> {
                val top3 = match.topCandidates.take(3)
                ServiceStateHolder.update {
                    it.copy(
                        pendingDisambiguation = PendingDisambiguation.AppChoice(
                            spokenQuery = match.spokenQuery,
                            topApps = top3
                        )
                    )
                }
                val optionsText = top3.mapIndexed { i, app -> "${i + 1}) ${app.label}" }.joinToString("  ")
                showActionNotification(
                    context = context,
                    title = "Which app did you mean?",
                    body = "$optionsText — Tap or say one, two, or three"
                )
                speakShortReply(context, "Which app? $optionsText")
                false to "App choice needed: $optionsText"
            }
            is AppMatchOutcome.NoConfidentMatch -> {
                val top3 = match.topCandidates.take(3)
                if (top3.isNotEmpty()) {
                    ServiceStateHolder.update {
                        it.copy(
                            pendingDisambiguation = PendingDisambiguation.AppChoice(
                                spokenQuery = match.spokenQuery,
                                topApps = top3
                            )
                        )
                    }
                    val optionsText = top3.mapIndexed { i, app -> "${i + 1}) ${app.label}" }.joinToString("  ")
                    showActionNotification(
                        context = context,
                        title = "App not recognized clearly",
                        body = "Did you mean: $optionsText?"
                    )
                    false to "Unknown app '${match.spokenQuery}', prompted top 3 choices"
                } else {
                    val msg = "No matching app found for '${match.spokenQuery}'"
                    showActionNotification(context, "NEMI", msg)
                    false to msg
                }
            }
        }
    }

    private fun launchResolvedApp(
        context: Context,
        app: InstalledAppEntry,
        isLocked: Boolean,
        matchedVia: String
    ): Pair<Boolean, String> {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (launchIntent == null) {
            val msg = "Cannot open ${app.label} (no launch intent)"
            showActionNotification(context, "NEMI", msg)
            return false to msg
        }

        // Section B.5: Never try to bypass the lock screen; when locked, show a notification
        // and log BLOCKED_BY_LOCKSCREEN.
        if (isLocked) {
            HeartbeatLogger.log(
                context,
                HeartbeatLogEntry.EventType.BLOCKED_BY_LOCKSCREEN,
                "Blocked opening ${app.label} while screen is locked; posted tap notification"
            )
            showActionNotification(
                context = context,
                title = "Unlock to open ${app.label}",
                body = "Screen is locked. Tap to open ${app.label} after unlocking.",
                tapIntent = launchIntent
            )
            return false to "BLOCKED_BY_LOCKSCREEN: ${app.label}"
        }

        return try {
            context.startActivity(launchIntent)
            AppCatalogCache.incrementLaunchCount(context, app.packageName)
            val msg = "Opening ${app.label} ($matchedVia)"
            showActionNotification(context, "NEMI", "Opening ${app.label}")
            true to msg
        } catch (e: Exception) {
            showActionNotification(
                context = context,
                title = "Open ${app.label}",
                body = "Tap to launch ${app.label}",
                tapIntent = launchIntent
            )
            false to "Notification fallback for ${app.label} (${e.javaClass.simpleName})"
        }
    }

    // =========================================================================
    // Section C: Extra Basic Offline Commands
    // =========================================================================

    private fun executeTorch(context: Context, turnOn: Boolean): Pair<Boolean, String> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false to "CameraManager unavailable".also {
                showActionNotification(context, "NEMI", it)
            }

        return try {
            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (flashCameraId == null) {
                val msg = "No flashlight hardware found"
                showActionNotification(context, "NEMI", msg)
                return false to msg
            }

            cameraManager.setTorchMode(flashCameraId, turnOn)
            val msg = if (turnOn) "Torch on" else "Torch off"
            showActionNotification(context, "NEMI", msg)
            true to msg
        } catch (e: Exception) {
            val msg = "Torch failed: ${e.javaClass.simpleName}"
            showActionNotification(context, "NEMI", msg)
            false to msg
        }
    }

    private fun executeAlarm(context: Context, hour24: Int, minute: Int): Pair<Boolean, String> {
        val formattedTime = String.format("%02d:%02d", hour24, minute)
        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour24)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "NEMI Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(alarmIntent)
            val msg = "Alarm set for $formattedTime"
            showActionNotification(context, "NEMI", msg)
            true to msg
        } catch (e: Exception) {
            showActionNotification(
                context = context,
                title = "Set Alarm for $formattedTime",
                body = "Tap to set alarm for $formattedTime",
                tapIntent = alarmIntent
            )
            false to "Alarm notification fallback for $formattedTime"
        }
    }

    private fun executeTimer(context: Context, durationSeconds: Int): Pair<Boolean, String> {
        val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "NEMI Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val label = if (durationSeconds % 60 == 0) {
            "${durationSeconds / 60} min"
        } else {
            "${durationSeconds}s"
        }

        return try {
            context.startActivity(timerIntent)
            val msg = "Timer set for $label"
            showActionNotification(context, "NEMI", msg)
            true to msg
        } catch (e: Exception) {
            showActionNotification(
                context = context,
                title = "Start Timer ($label)",
                body = "Tap to start $label timer",
                tapIntent = timerIntent
            )
            false to "Timer notification fallback ($label)"
        }
    }

    private fun executeVolume(
        context: Context,
        action: VolumeAction,
        percent: Int?
    ): Pair<Boolean, String> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false to "AudioManager unavailable"

        return try {
            val stream = AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)

            val msg = when (action) {
                VolumeAction.UP -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "Volume increased"
                }
                VolumeAction.DOWN -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "Volume decreased"
                }
                VolumeAction.MUTE -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "Volume muted"
                }
                VolumeAction.UNMUTE -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    "Volume unmuted"
                }
                VolumeAction.SET_PERCENT -> {
                    val pct = (percent ?: 50).coerceIn(0, 100)
                    val targetIndex = ((pct / 100.0) * maxVol).roundToInt().coerceIn(0, maxVol)
                    audioManager.setStreamVolume(stream, targetIndex, AudioManager.FLAG_SHOW_UI)
                    "Volume set to $pct%"
                }
            }
            showActionNotification(context, "NEMI", msg)
            true to msg
        } catch (e: Exception) {
            val msg = "Volume change blocked (${e.javaClass.simpleName})"
            showActionNotification(context, "NEMI", msg)
            false to msg
        }
    }

    private fun executeBrightness(
        context: Context,
        action: BrightnessAction,
        percent: Int?
    ): Pair<Boolean, String> {
        if (!Settings.System.canWrite(context)) {
            val writeSettingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val msg = "Grant 'Modify system settings' in Settings to change brightness"
            showActionNotification(
                context = context,
                title = "Brightness Permission Needed",
                body = msg,
                tapIntent = writeSettingsIntent
            )
            return false to msg
        }

        return try {
            val resolver = context.contentResolver
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = when (action) {
                BrightnessAction.UP -> (current + 50).coerceIn(10, 255)
                BrightnessAction.DOWN -> (current - 50).coerceIn(10, 255)
                BrightnessAction.SET_PERCENT -> {
                    val pct = (percent ?: 50).coerceIn(5, 100)
                    ((pct / 100.0) * 255).roundToInt().coerceIn(10, 255)
                }
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
            val pctShown = ((next / 255.0) * 100).roundToInt()
            val msg = "Brightness set to $pctShown%"
            showActionNotification(context, "NEMI", msg)
            true to msg
        } catch (e: Exception) {
            val msg = "Brightness update failed (${e.javaClass.simpleName})"
            showActionNotification(context, "NEMI", msg)
            false to msg
        }
    }

    private fun executeDeviceQuery(context: Context, queryType: DeviceQueryType): Pair<Boolean, String> {
        val reply = when (queryType) {
            DeviceQueryType.TIME -> {
                val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                "It is $timeStr"
            }
            DeviceQueryType.DATE -> {
                val dateStr = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
                "Today is $dateStr"
            }
            DeviceQueryType.BATTERY -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    ?: readBatteryLevelFromStickyIntent(context)
                "Battery level is $level percent"
            }
        }

        speakShortReply(context, reply)
        showActionNotification(context, "NEMI", reply)
        return true to reply
    }

    private fun readBatteryLevelFromStickyIntent(context: Context): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else 100
    }

    private fun executeMediaControl(context: Context, mediaAction: MediaAction): Pair<Boolean, String> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false to "AudioManager unavailable"

        val keyCode = when (mediaAction) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }

        return try {
            val eventTime = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
            val msg = "Media: ${mediaAction.name.lowercase().replaceFirstChar { it.uppercase() }}"
            showActionNotification(context, "NEMI", msg)
            true to msg
        } catch (e: Exception) {
            false to "Media key dispatch failed (${e.javaClass.simpleName})"
        }
    }

    private fun executeSystemPanel(
        context: Context,
        panelType: SystemPanelType,
        isLocked: Boolean
    ): Pair<Boolean, String> {
        val (intent, label) = when (panelType) {
            SystemPanelType.WIFI -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_WIFI
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
                Intent(action) to "Wi-Fi settings"
            }
            SystemPanelType.BLUETOOTH -> {
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS) to "Bluetooth settings"
            }
            SystemPanelType.DO_NOT_DISTURB -> {
                Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS) to "Do Not Disturb settings"
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val spoken = "Opening $label"
        speakShortReply(context, spoken)

        if (isLocked) {
            HeartbeatLogger.log(
                context,
                HeartbeatLogEntry.EventType.BLOCKED_BY_LOCKSCREEN,
                "Blocked opening $label while locked; posted notification"
            )
            showActionNotification(
                context = context,
                title = "Open $label",
                body = "Unlock screen and tap to open $label",
                tapIntent = intent
            )
            return false to "BLOCKED_BY_LOCKSCREEN: $label"
        }

        return try {
            context.startActivity(intent)
            showActionNotification(context, "NEMI", spoken)
            true to spoken
        } catch (e: Exception) {
            showActionNotification(
                context = context,
                title = label,
                body = "Tap to open $label",
                tapIntent = intent
            )
            true to "$spoken (via notification)"
        }
    }

    // =========================================================================
    // Call Execution (Privacy-Safe Logging + Last 2 Digits Disambiguation)
    // =========================================================================

    private data class CallExecutionResult(
        val isSuccess: Boolean,
        val logSafeStatusMessage: String,
        val logSafeNumberNote: String?
    )

    private fun executeCall(
        context: Context,
        spokenName: String,
        contacts: List<CachedContact>
    ): CallExecutionResult {
        when (val match = ContactMatcher.matchContact(spokenName, contacts)) {
            is ContactMatchOutcome.NoMatch -> {
                val msg = "No matching contact found"
                showActionNotification(context, "NEMI Call", "No contact found for \"$spokenName\"")
                return CallExecutionResult(false, msg, null)
            }

            is ContactMatchOutcome.MultipleMatches -> {
                showActionNotification(
                    context = context,
                    title = "Multiple Contacts Match",
                    body = "More than one contact matched \"$spokenName\". Please say the full name."
                )
                return CallExecutionResult(
                    isSuccess = false,
                    logSafeStatusMessage = "Ambiguous contact match (${match.contacts.size} contacts)",
                    logSafeNumberNote = "Multiple contacts matched"
                )
            }

            is ContactMatchOutcome.SingleMatch -> {
                val contact = match.contact
                return when (val choice = contact.precomputedChoice) {
                    is NumberChoiceResult.NoNumbers -> {
                        showActionNotification(context, "NEMI Call", "No phone number saved")
                        CallExecutionResult(false, "No numbers saved on matched contact", null)
                    }

                    is NumberChoiceResult.Ambiguous -> {
                        // Section D Privacy Rule: Show ONLY the spoken name and the last 2 digits of each number
                        val last2List = choice.candidates.map {
                            PhoneNumberNormalizer.endingDigits(it.entry.rawNumber, count = 2)
                        }
                        val rawNumbers = choice.candidates.map { it.entry.rawNumber }

                        ServiceStateHolder.update {
                            it.copy(
                                pendingDisambiguation = PendingDisambiguation.ContactNumberChoice(
                                    spokenName = spokenName,
                                    optionsLastTwoDigits = last2List,
                                    internalNumbersToDial = rawNumbers
                                )
                            )
                        }

                        val endingsText = last2List.mapIndexed { i, d -> "${i + 1}) ..$d" }.joinToString("  ")
                        val logNote = "Ambiguous numbers ($endingsText) — ${choice.reason}"
                        HeartbeatLogger.log(
                            context,
                            HeartbeatLogEntry.EventType.NUMBER_SELECTED,
                            logNote
                        )
                        showActionNotification(
                            context = context,
                            title = "Choose number for $spokenName",
                            body = "$endingsText — Tap or say one or two"
                        )
                        CallExecutionResult(
                            isSuccess = false,
                            logSafeStatusMessage = "Prompted number choice ($endingsText)",
                            logSafeNumberNote = logNote
                        )
                    }

                    is NumberChoiceResult.Chosen -> {
                        val rawNumber = choice.selected.entry.rawNumber
                        val last2 = PhoneNumberNormalizer.endingDigits(rawNumber, count = 2)
                        val logNote = "Selected number ending **$last2 (${choice.reason})"

                        HeartbeatLogger.log(
                            context,
                            HeartbeatLogEntry.EventType.NUMBER_SELECTED,
                            logNote
                        )

                        val (ok, statusMsg) = placePhoneCallToNumber(
                            context = context,
                            spokenContactName = spokenName,
                            rawNumber = rawNumber,
                            last2Digits = last2
                        )
                        CallExecutionResult(
                            isSuccess = ok,
                            logSafeStatusMessage = statusMsg,
                            logSafeNumberNote = logNote
                        )
                    }
                }
            }
        }
    }

    private fun placePhoneCallToNumber(
        context: Context,
        spokenContactName: String,
        rawNumber: String,
        last2Digits: String
    ): Pair<Boolean, String> {
        val userFacingConfirm = "Calling $spokenContactName, number ending $last2Digits"
        val logSafeConfirm = "Calling matched contact, number ending **$last2Digits"

        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCallPerm) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$rawNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            showActionNotification(
                context = context,
                title = userFacingConfirm,
                body = "Tap to open dialer (ending $last2Digits)",
                tapIntent = dialIntent
            )
            return false to "$logSafeConfirm (CALL_PHONE missing; dialer notification posted)"
        }

        return try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$rawNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
            showActionNotification(context, "NEMI Call", userFacingConfirm)
            true to logSafeConfirm
        } catch (e: Exception) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$rawNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            showActionNotification(
                context = context,
                title = userFacingConfirm,
                body = "Tap to dial number ending $last2Digits",
                tapIntent = dialIntent
            )
            true to "$logSafeConfirm (Dialer notification fallback)"
        }
    }

    fun showActionNotification(
        context: Context,
        title: String,
        body: String,
        tapIntent: Intent? = null
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureActionChannel(nm)

        val targetIntent = tapIntent ?: Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pi = PendingIntent.getActivity(
            context,
            3001,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ACTIONS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            nm.notify(NOTIF_ID_ACTION_STATUS, notification)
        }
    }

    private fun ensureActionChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_ACTIONS,
                "NEMI Command Feedback",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows feedback for voice commands"
            }
            nm.createNotificationChannel(channel)
        }
    }
}
