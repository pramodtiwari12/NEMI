package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.actions.ActionExecutor
import com.example.apps.AppCatalogCache
import com.example.asr.VoskGrammarBuilder
import com.example.contacts.ContactCallCache
import com.example.parser.CommandParser
import com.example.service.NEMIPrefs
import com.example.service.NEMIRuntimeStatus
import com.example.service.NEMIService
import com.example.service.ServiceStateHolder
import com.example.service.ServiceWatchdogScheduler
import com.example.telemetry.HeartbeatLogEntry
import com.example.telemetry.HeartbeatLogger
import com.example.telemetry.RecentCommandRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class NEMIScreen {
    HOME,
    SETTINGS,
    DIAGNOSTICS
}

data class Stage1UiState(
    val currentScreen: NEMIScreen = NEMIScreen.HOME,
    val serviceEnabledPref: Boolean = false,
    val runtimeStatus: NEMIRuntimeStatus = NEMIRuntimeStatus(),
    val selectedWakePhrase: String = VoskGrammarBuilder.WAKE_PHRASE,
    val availableWakePhrases: List<String> = VoskGrammarBuilder.WAKE_PHRASES,
    val permissions: List<PermissionRequirement> = emptyList(),
    val recentCommands: List<RecentCommandRecord> = emptyList(),
    val oemGuides: List<OemBatteryGuide> = emptyList(),
    val logEntries: List<HeartbeatLogEntry> = emptyList(),
    val statusBannerMessage: String? = null
)

class NEMIViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context
        get() = getApplication<Application>().applicationContext

    private val _currentScreen = MutableStateFlow(NEMIScreen.HOME)
    private val _permissions = MutableStateFlow<List<PermissionRequirement>>(emptyList())
    private val _serviceEnabledPref = MutableStateFlow(false)
    private val _selectedWakePhrase = MutableStateFlow(VoskGrammarBuilder.WAKE_PHRASE)
    private val _statusBanner = MutableStateFlow<String?>(null)

    val uiState: StateFlow<Stage1UiState> = combine(
        _currentScreen,
        _serviceEnabledPref,
        ServiceStateHolder.status,
        _permissions,
        HeartbeatLogger.entries
    ) { screen, enabledPref, runtimeStatus, perms, logs ->
        Stage1UiState(
            currentScreen = screen,
            serviceEnabledPref = enabledPref,
            runtimeStatus = runtimeStatus,
            selectedWakePhrase = _selectedWakePhrase.value,
            availableWakePhrases = VoskGrammarBuilder.WAKE_PHRASES,
            permissions = perms,
            recentCommands = HeartbeatLogger.recentCommands.value,
            oemGuides = PermissionHelper.getOemBatteryGuides(),
            logEntries = logs,
            statusBannerMessage = _statusBanner.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Stage1UiState()
    )

    init {
        HeartbeatLogger.init(appContext)
        ActionExecutor.initTts(appContext)
        refreshState()
    }

    fun navigateTo(screen: NEMIScreen) {
        _currentScreen.value = screen
        refreshState()
    }

    fun navigateBack(): Boolean {
        return when (_currentScreen.value) {
            NEMIScreen.DIAGNOSTICS -> {
                _currentScreen.value = NEMIScreen.SETTINGS
                true
            }
            NEMIScreen.SETTINGS -> {
                _currentScreen.value = NEMIScreen.HOME
                true
            }
            NEMIScreen.HOME -> false
        }
    }

    fun refreshState() {
        _permissions.value = PermissionHelper.evaluatePermissions(appContext)
        _serviceEnabledPref.value = NEMIPrefs.isServiceEnabled(appContext)
        _selectedWakePhrase.value = NEMIPrefs.getSelectedWakePhrase(appContext)

        ContactCallCache.refreshCacheAsync(appContext, reason = "UI refresh")
        AppCatalogCache.refreshCatalogAsync(appContext, reason = "UI refresh")

        ServiceStateHolder.update {
            it.copy(
                isRunning = NEMIService.isRunningInstance,
                selectedWakePhrase = _selectedWakePhrase.value,
                watchdogRunCount = NEMIPrefs.getWatchdogCount(appContext),
                lastStopReason = NEMIPrefs.getLastStopReason(appContext)
            )
        }
    }

    fun toggleMasterService(enable: Boolean) {
        if (enable) {
            val started = NEMIService.startServiceSafely(
                context = appContext,
                reason = "Home On/Off Switch"
            )
            _serviceEnabledPref.value = started
            if (!started) {
                _statusBanner.value = "Please grant Microphone permission in Settings first."
            } else {
                _statusBanner.value = null
            }
        } else {
            NEMIService.stopServiceIntentionally(
                context = appContext,
                reason = "Home On/Off Switch"
            )
            _serviceEnabledPref.value = false
            _statusBanner.value = null
        }
        refreshState()
    }

    fun selectWakePhrase(phrase: String) {
        NEMIPrefs.setSelectedWakePhrase(appContext, phrase)
        _selectedWakePhrase.value = phrase
        ServiceStateHolder.update {
            it.copy(selectedWakePhrase = phrase)
        }
        if (NEMIService.isRunningInstance) {
            val intent = Intent(appContext, NEMIService::class.java).apply {
                action = NEMIService.ACTION_UPDATE_WAKE_PHRASE
                putExtra(NEMIService.EXTRA_WAKE_PHRASE, phrase)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }
    }

    fun testWakeBeep() {
        if (NEMIService.isRunningInstance) {
            val intent = Intent(appContext, NEMIService::class.java).apply {
                action = NEMIService.ACTION_TEST_BEEP
            }
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            // Start service or play directly through a temporary pipeline helper
            val started = NEMIService.startServiceSafely(appContext, "Diagnostics Beep Test")
            if (started) {
                val intent = Intent(appContext, NEMIService::class.java).apply {
                    action = NEMIService.ACTION_TEST_BEEP
                }
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                HeartbeatLogger.log(
                    appContext,
                    HeartbeatLogEntry.EventType.ERROR,
                    "Grant Microphone permission first to run Beep/Mic test"
                )
            }
        }
    }

    fun startDiagnosticsMicTest() {
        if (!NEMIService.isRunningInstance) {
            NEMIService.startServiceSafely(appContext, "Diagnostics Mic Test")
        }
        val intent = Intent(appContext, NEMIService::class.java).apply {
            action = NEMIService.ACTION_MIC_TEST
        }
        runCatching {
            ContextCompat.startForegroundService(appContext, intent)
        }
    }

    fun runVoiceCommandTest(spokenCommand: String) {
        val trimmed = spokenCommand.trim()
        if (trimmed.isEmpty()) return

        if (NEMIService.isRunningInstance) {
            val intent = Intent(appContext, NEMIService::class.java).apply {
                action = NEMIService.ACTION_SIMULATE_VOICE_COMMAND
                putExtra(NEMIService.EXTRA_SPOKEN_COMMAND, trimmed)
            }
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            val speechEndElapsedMs = SystemClock.elapsedRealtime()
            ServiceStateHolder.update {
                it.copy(
                    wakeWordDetected = true,
                    livePartialTranscript = trimmed,
                    liveFinalTranscript = trimmed
                )
            }
            HeartbeatLogger.log(
                appContext,
                HeartbeatLogEntry.EventType.WAKE_DETECTED,
                "Wake word detected: \"${_selectedWakePhrase.value}\""
            )
            HeartbeatLogger.log(
                appContext,
                HeartbeatLogEntry.EventType.COMMAND_TEXT,
                "Heard command text: \"$trimmed\""
            )
            val parsed = CommandParser.parseCommand(trimmed)
            ActionExecutor.executeParsedCommand(
                context = appContext,
                rawSpokenText = trimmed,
                command = parsed,
                speechEndElapsedMs = speechEndElapsedMs
            )
        }
    }

    fun chooseDisambiguationOption(indexOneBased: Int) {
        ActionExecutor.resolvePendingDisambiguation(appContext, indexOneBased)
    }

    fun dismissDisambiguation() {
        ActionExecutor.dismissPendingDisambiguation()
    }

    fun triggerWatchdogCheckNow() {
        ServiceWatchdogScheduler.runWatchdogCheckNow(
            context = appContext,
            triggerSource = "Diagnostics Watchdog Check"
        )
        refreshState()
    }

    fun clearLogs() {
        HeartbeatLogger.clear(appContext)
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }
}
