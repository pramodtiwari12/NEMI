package com.example.service

import com.example.apps.InstalledAppEntry
import com.example.asr.VoskGrammarBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Represents a small disambiguation prompt when either:
 * 1. A contact has multiple phone numbers with equal score (shows ONLY spoken name + last 2 digits), OR
 * 2. Multiple apps match a spoken app query (shows top 3 apps to pick by tap or saying "one/two/three").
 */
sealed class PendingDisambiguation {
    data class ContactNumberChoice(
        val spokenName: String,
        val optionsLastTwoDigits: List<String>,
        val internalNumbersToDial: List<String>
    ) : PendingDisambiguation()

    data class AppChoice(
        val spokenQuery: String,
        val topApps: List<InstalledAppEntry>
    ) : PendingDisambiguation()
}

/**
 * Reactive runtime snapshot of [NEMIService], the 16 kHz [android.media.AudioRecord],
 * live microphone RMS level, live transcripts, wake word status, and disambiguation state.
 */
data class NEMIRuntimeStatus(
    val isRunning: Boolean = false,
    val isMicOpen: Boolean = false,
    val activeAudioSource: String = "VOICE_RECOGNITION",
    val micRmsValue: Float = 0f,
    val micLevelNormalized: Float = 0f,
    val isMicSilentWarning: Boolean = false,
    val softwareGainFactor: Float = 2.0f,
    val isVoskModelLoaded: Boolean = false,
    val modelStatusMessage: String = "Ready to unpack vosk-model-small-en-in",
    val selectedWakePhrase: String = VoskGrammarBuilder.WAKE_PHRASE,
    val wakeWordDetected: Boolean = false,
    val lastWakeDetectedTime: String = "Never",
    val livePartialTranscript: String = "",
    val liveFinalTranscript: String = "",
    val listeningMode: String = "Off",
    val lastSpokenCommand: String = "None yet",
    val contactGrammarCount: Int = 0,
    val startedAtElapsedMs: Long = 0L,
    val startedAtEpochMs: Long = 0L,
    val isCommandWakeLockHeld: Boolean = false,
    val heartbeatCount: Int = 0,
    val lastHeartbeatEpochMs: Long = 0L,
    val lastWatchdogCheckEpochMs: Long = 0L,
    val watchdogRunCount: Int = 0,
    val lastStopReason: String = "Not stopped yet",
    val statusDetail: String = "Off",
    val pendingDisambiguation: PendingDisambiguation? = null
)

object ServiceStateHolder {
    private val _status = MutableStateFlow(NEMIRuntimeStatus())
    val status: StateFlow<NEMIRuntimeStatus> = _status.asStateFlow()

    fun update(transform: (NEMIRuntimeStatus) -> NEMIRuntimeStatus) {
        _status.update(transform)
    }
}
