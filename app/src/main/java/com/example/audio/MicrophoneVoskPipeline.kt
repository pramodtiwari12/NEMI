package com.example.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.actions.ActionExecutor
import com.example.asr.VoskGrammarBuilder
import com.example.parser.CommandParser
import com.example.service.NEMIPrefs
import com.example.service.ServiceStateHolder
import com.example.telemetry.HeartbeatLogEntry
import com.example.telemetry.HeartbeatLogger
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Section A: Fixed & Hardened Offline Voice Pipeline.
 *
 * Key fixes implemented:
 * 1. AudioRecord (16 kHz, mono, PCM_16BIT, bufferSize >= minBufferSize * 2) with
 *    AudioSource fallback chain: `VOICE_RECOGNITION` -> `MIC` -> `VOICE_COMMUNICATION`.
 * 2. Software gain/normalization (2.5x clamped PCM boost) so quiet `VOICE_RECOGNITION`
 *    streams on OEM phones are clearly audible to the recognizer.
 * 3. Live RMS meter computation on every frame + automatic "Microphone is silent" detection.
 * 4. Wake word detection using BOTH partial and final results, normalized text + fuzzy tolerance,
 *    and selectable wake phrases ("hey computer", "hey phone", "ok phone").
 * 5. Audible Beep (PCM AudioTrack with `USAGE_ASSISTANCE_SONIFICATION` + `USAGE_MEDIA` fallback)
 *    + short haptic vibration + 220ms recognizer pause window so the beep is never transcribed.
 *    Logs `BEEP_PLAYED`.
 * 6. Unpacks & warms up `vosk-model-small-en-in` (`assets/model-en-in`) at service start, and
 *    uses FREE recognition (no grammar restriction) in Mode 2 so any command/app name is transcribed.
 * 7. Includes an automatic on-device Android `SpeechRecognizer` bridge when the 36 MB binary
 *    Vosk `.mdl` weights haven't been copied into `assets/model-en-in/` yet, ensuring live
 *    transcription and wake-word detection work out of the box on any phone.
 */
class MicrophoneVoskPipeline(
    private val context: Context,
    private val onRequestCommandWakeLock: (Long, String) -> Unit,
    private val onReleaseCommandWakeLock: (String) -> Unit
) {

    enum class ListeningMode {
        IDLE,
        MODE_1_WAITING_WAKE_WORD,
        MODE_2_LISTENING_COMMAND,
        MIC_TEST_FREE_LISTEN
    }

    private data class AudioSourceCandidate(
        val sourceId: Int,
        val name: String
    )

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val ASSET_MODEL_EN_IN = "model-en-in"
        private const val ASSET_MODEL_EN_US = "model-en-us"
        private const val COMMAND_MAX_DURATION_MS = 5_000L
        private const val COMMAND_SILENCE_TIMEOUT_MS = 1_200L
        private const val BEEP_MUTE_WINDOW_MS = 220L
        private const val SILENCE_RMS_THRESHOLD = 600.0
        private const val NEAR_ZERO_SILENT_MIC_RMS = 1.5
        private const val SILENT_MIC_WARNING_AFTER_MS = 4_000L
        private const val DEFAULT_SOFTWARE_GAIN = 2.5f

        private val AUDIO_SOURCE_CHAIN = listOf(
            AudioSourceCandidate(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
            AudioSourceCandidate(MediaRecorder.AudioSource.MIC, "MIC"),
            AudioSourceCandidate(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION")
        )
    }

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentMode: ListeningMode = ListeningMode.IDLE

    @Volatile
    private var muteRecognizerUntilElapsedMs: Long = 0L

    @Volatile
    private var selectedWakePhrase: String = VoskGrammarBuilder.WAKE_PHRASE

    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var voskModel: Model? = null
    private var wakeRecognizer: Recognizer? = null
    private var freeCommandRecognizer: Recognizer? = null

    private val recognizerLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fallbackSpeechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var isFallbackRecognizerListening = false

    fun start() {
        if (isRunning) return
        isRunning = true
        selectedWakePhrase = NEMIPrefs.getSelectedWakePhrase(context)
        ActionExecutor.initTts(context)

        audioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            runPipelineLoop()
        }, "NEMI-AudioRecord-Thread").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning = false
        currentMode = ListeningMode.IDLE
        audioThread?.interrupt()
        try {
            audioThread?.join(800L)
        } catch (_: InterruptedException) {
        }
        audioThread = null
        stopFallbackSpeechRecognizer()
        releaseResources()
    }

    /**
     * Updates the active wake phrase ("hey computer", "hey phone", "ok phone")
     * and rebuilds the Mode 1 wake recognizer grammar.
     */
    fun updateWakePhrase(newPhrase: String) {
        selectedWakePhrase = newPhrase
        ServiceStateHolder.update {
            it.copy(selectedWakePhrase = newPhrase)
        }
        synchronized(recognizerLock) {
            val model = voskModel ?: return
            runCatching {
                val wakeGrammar = VoskGrammarBuilder.buildWakeWordGrammarJson(newPhrase)
                val newWakeRec = Recognizer(model, SAMPLE_RATE_HZ.toFloat(), wakeGrammar)
                wakeRecognizer?.close()
                wakeRecognizer = newWakeRec
            }
        }
    }

    /**
     * Section A.5: Plays a clearly audible double-tone chime using `USAGE_ASSISTANCE_SONIFICATION`
     * (with `USAGE_MEDIA` and `ToneGenerator` fallback), triggers a short vibration, pauses
     * feeding audio to Vosk for ~220 ms so the beep is never fed back into the recognizer,
     * and logs `BEEP_PLAYED`.
     */
    fun playWakeBeepAndVibrate(reason: String = "Wake word detected") {
        // Pause feeding audio to recognizer for 220ms around the beep
        muteRecognizerUntilElapsedMs = SystemClock.elapsedRealtime() + BEEP_MUTE_WINDOW_MS

        // Short haptic vibration (75 ms)
        triggerShortVibration()

        // Play synthesized PCM chime on background thread so it never blocks
        Thread({
            var playedOk = playPcmChimeWithUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            if (!playedOk) {
                playedOk = playPcmChimeWithUsage(AudioAttributes.USAGE_MEDIA)
            }
            if (!playedOk) {
                playedOk = runCatching {
                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
                    Thread.sleep(150L)
                    tg.release()
                    true
                }.getOrDefault(false)
            }

            if (playedOk) {
                HeartbeatLogger.log(
                    context,
                    HeartbeatLogEntry.EventType.BEEP_PLAYED,
                    "Wake beep + vibration played ($reason, 220ms mic mute window active)"
                )
            } else {
                HeartbeatLogger.log(
                    context,
                    HeartbeatLogEntry.EventType.ERROR,
                    "Beep playback failed across USAGE_ASSISTANCE_SONIFICATION, USAGE_MEDIA, and ToneGenerator"
                )
            }
        }, "NEMI-BeepThread").start()
    }

    /**
     * Diagnostics "Mic Test: Say something, see what it heard" button:
     * Switches directly into Mode 2 command/free-listen mode (with beep) so the user
     * can speak any phrase and watch the live partial & final transcript in Diagnostics.
     */
    fun triggerDiagnosticsMicTest() {
        playWakeBeepAndVibrate(reason = "Diagnostics Mic Test")
        onRequestCommandWakeLock(COMMAND_MAX_DURATION_MS, "Diagnostics Mic Test")

        val nowStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        ServiceStateHolder.update {
            it.copy(
                wakeWordDetected = true,
                lastWakeDetectedTime = nowStr,
                livePartialTranscript = "Listening… speak now",
                listeningMode = "Listening (Mic Test)…"
            )
        }

        if (voskModel != null) {
            synchronized(recognizerLock) {
                freeCommandRecognizer?.reset()
            }
            currentMode = ListeningMode.MODE_2_LISTENING_COMMAND
        } else {
            startFallbackOnDeviceRecognizer(isWakeAlreadyTriggered = true)
        }
    }

    /**
     * Simulates saying the wake word + a spoken command for testing.
     */
    fun simulateSpokenWakeAndCommand(spokenCommandText: String) {
        Thread({
            val nowStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            HeartbeatLogger.log(
                context,
                HeartbeatLogEntry.EventType.WAKE_DETECTED,
                "Wake phrase detected: \"$selectedWakePhrase\""
            )
            playWakeBeepAndVibrate(reason = "Wake phrase '$selectedWakePhrase'")
            onRequestCommandWakeLock(COMMAND_MAX_DURATION_MS, "Voice command window")

            ServiceStateHolder.update {
                it.copy(
                    wakeWordDetected = true,
                    lastWakeDetectedTime = nowStr,
                    livePartialTranscript = spokenCommandText,
                    liveFinalTranscript = spokenCommandText,
                    listeningMode = "Listening"
                )
            }

            val speechEndElapsedMs = SystemClock.elapsedRealtime()
            HeartbeatLogger.log(
                context,
                HeartbeatLogEntry.EventType.COMMAND_TEXT,
                "Heard command text: \"${redactIfCallCommand(spokenCommandText)}\""
            )

            val parsed = CommandParser.parseCommand(spokenCommandText)
            ActionExecutor.executeParsedCommand(
                context = context,
                rawSpokenText = spokenCommandText,
                command = parsed,
                speechEndElapsedMs = speechEndElapsedMs
            )

            onReleaseCommandWakeLock("Command finished")
            ServiceStateHolder.update {
                it.copy(
                    lastSpokenCommand = spokenCommandText,
                    statusDetail = "Listening"
                )
            }
        }, "NEMI-CommandProcessor").start()
    }

    private fun runPipelineLoop() {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            val msg = "Microphone permission (RECORD_AUDIO) is denied."
            HeartbeatLogger.log(context, HeartbeatLogEntry.EventType.ERROR, msg)
            ServiceStateHolder.update {
                it.copy(
                    isMicOpen = false,
                    modelStatusMessage = msg,
                    statusDetail = "Off (Mic permission needed)"
                )
            }
            return
        }

        // 1. Unpack & load Indian English Vosk model (vosk-model-small-en-in) and warm it up
        val modelLoaded = initAndWarmUpVoskModel()

        // 2. Open AudioRecord using the fallback chain: VOICE_RECOGNITION -> MIC -> VOICE_COMMUNICATION
        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) {
            val msg = "AudioRecord error: invalid minBufferSize ($minBufBytes)"
            HeartbeatLogger.log(context, HeartbeatLogEntry.EventType.ERROR, msg)
            ServiceStateHolder.update {
                it.copy(isMicOpen = false, statusDetail = msg)
            }
            return
        }

        val bufferSizeBytes = maxOf(minBufBytes * 2, 6400)
        val openedPair = openAudioRecordWithFallbackChain(bufferSizeBytes)
        if (openedPair == null) {
            val msg = "Microphone is unavailable (all audio sources failed to initialize)"
            HeartbeatLogger.log(context, HeartbeatLogEntry.EventType.ERROR, msg)
            ServiceStateHolder.update {
                it.copy(
                    isMicOpen = false,
                    isMicSilentWarning = true,
                    statusDetail = msg
                )
            }
            return
        }

        var (record, activeSource) = openedPair
        audioRecord = record

        currentMode = ListeningMode.MODE_1_WAITING_WAKE_WORD
        ServiceStateHolder.update {
            it.copy(
                isMicOpen = true,
                activeAudioSource = activeSource.name,
                softwareGainFactor = DEFAULT_SOFTWARE_GAIN,
                selectedWakePhrase = selectedWakePhrase,
                listeningMode = "Listening",
                statusDetail = "Listening"
            )
        }

        // 100ms frame buffer (1600 shorts at 16,000 Hz)
        val rawPcmBuffer = ShortArray(1600)
        val gainAdjustedBuffer = ShortArray(1600)

        var mode2StartedAtMs = 0L
        var lastVoiceActivityAtMs = SystemClock.elapsedRealtime()
        var hasSpokenInMode2 = false
        var lastNonSilentFrameAtMs = SystemClock.elapsedRealtime()
        var currentSourceIdx = AUDIO_SOURCE_CHAIN.indexOfFirst { it.sourceId == activeSource.sourceId }.coerceAtLeast(0)
        var triedSourceFallbackOnSilence = false

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val shortsRead = record.read(rawPcmBuffer, 0, rawPcmBuffer.size)
            if (shortsRead <= 0) {
                SystemClock.sleep(15L)
                continue
            }

            val nowMs = SystemClock.elapsedRealtime()

            // Compute raw RMS and apply software gain/normalization before Vosk
            val rawRms = computeRms(rawPcmBuffer, shortsRead)
            applySoftwareGain(rawPcmBuffer, gainAdjustedBuffer, shortsRead, DEFAULT_SOFTWARE_GAIN)
            val boostedRms = computeRms(gainAdjustedBuffer, shortsRead)

            if (rawRms >= NEAR_ZERO_SILENT_MIC_RMS) {
                lastNonSilentFrameAtMs = nowMs
            }

            val silentDurationMs = nowMs - lastNonSilentFrameAtMs
            val isMicSilent = silentDurationMs >= SILENT_MIC_WARNING_AFTER_MS

            // If the current audio source stays strictly near zero for >4s, try switching
            // to the next audio source in the fallback chain (VOICE_RECOGNITION -> MIC -> VOICE_COMMUNICATION)
            if (isMicSilent && !triedSourceFallbackOnSilence && AUDIO_SOURCE_CHAIN.size > 1) {
                triedSourceFallbackOnSilence = true
                val nextIdx = (currentSourceIdx + 1) % AUDIO_SOURCE_CHAIN.size
                val nextCandidate = AUDIO_SOURCE_CHAIN[nextIdx]
                val switched = tryOpenSingleSource(nextCandidate, bufferSizeBytes)
                if (switched != null) {
                    runCatching {
                        record.stop()
                        record.release()
                    }
                    record = switched
                    audioRecord = switched
                    activeSource = nextCandidate
                    currentSourceIdx = nextIdx
                    lastNonSilentFrameAtMs = nowMs
                    HeartbeatLogger.log(
                        context,
                        HeartbeatLogEntry.EventType.HEARTBEAT,
                        "Switched AudioSource to ${nextCandidate.name} due to near-zero RMS on previous source"
                    )
                }
            }

            // Expose live RMS level (0..1 normalized) to Diagnostics screen
            val normalizedLevel = (boostedRms / 3500.0).toFloat().coerceIn(0f, 1f)
            ServiceStateHolder.update {
                it.copy(
                    micRmsValue = boostedRms.toFloat(),
                    micLevelNormalized = normalizedLevel,
                    isMicSilentWarning = isMicSilent,
                    activeAudioSource = activeSource.name
                )
            }

            // Section A.5: Pause feeding audio to Vosk for ~220ms around the wake beep
            // so the beep is never fed back into the recognizer.
            if (nowMs < muteRecognizerUntilElapsedMs) {
                continue
            }

            // If binary Vosk model isn't in assets yet, trigger on-device SpeechRecognizer bridge when user speaks
            if (!modelLoaded) {
                if (boostedRms > SILENCE_RMS_THRESHOLD * 1.4 && !isFallbackRecognizerListening) {
                    startFallbackOnDeviceRecognizer(isWakeAlreadyTriggered = false)
                }
                continue
            }

            when (currentMode) {
                ListeningMode.MODE_1_WAITING_WAKE_WORD -> {
                    var wakePartialOrFinal = ""
                    val detectedWake = synchronized(recognizerLock) {
                        val wakeRec = wakeRecognizer ?: return@synchronized false
                        val isFinal = wakeRec.acceptWaveForm(gainAdjustedBuffer, shortsRead)
                        // Section A.4: Check BOTH partialResult and result
                        val partialText = extractTextFromVoskJson(wakeRec.partialResult)
                        val finalText = if (isFinal) extractTextFromVoskJson(wakeRec.result) else ""
                        wakePartialOrFinal = finalText.ifEmpty { partialText }

                        VoskGrammarBuilder.matchesWakePhrase(partialText, selectedWakePhrase) ||
                            (finalText.isNotEmpty() && VoskGrammarBuilder.matchesWakePhrase(finalText, selectedWakePhrase))
                    }

                    if (wakePartialOrFinal.isNotEmpty()) {
                        ServiceStateHolder.update {
                            it.copy(livePartialTranscript = wakePartialOrFinal)
                        }
                    }

                    if (detectedWake) {
                        synchronized(recognizerLock) {
                            wakeRecognizer?.reset()
                            freeCommandRecognizer?.reset()
                        }

                        val wakeTimeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                        HeartbeatLogger.log(
                            context,
                            HeartbeatLogEntry.EventType.WAKE_DETECTED,
                            "Wake word detected: \"$selectedWakePhrase\""
                        )
                        playWakeBeepAndVibrate(reason = "Wake word '$selectedWakePhrase'")
                        onRequestCommandWakeLock(COMMAND_MAX_DURATION_MS, "Mode 2 command listening")

                        currentMode = ListeningMode.MODE_2_LISTENING_COMMAND
                        mode2StartedAtMs = SystemClock.elapsedRealtime() + BEEP_MUTE_WINDOW_MS
                        lastVoiceActivityAtMs = mode2StartedAtMs
                        hasSpokenInMode2 = false

                        ServiceStateHolder.update {
                            it.copy(
                                wakeWordDetected = true,
                                lastWakeDetectedTime = wakeTimeStr,
                                livePartialTranscript = "",
                                listeningMode = "Listening for command…"
                            )
                        }
                    }
                }

                ListeningMode.MODE_2_LISTENING_COMMAND,
                ListeningMode.MIC_TEST_FREE_LISTEN -> {
                    if (boostedRms > SILENCE_RMS_THRESHOLD) {
                        lastVoiceActivityAtMs = nowMs
                    }

                    var currentTranscript = ""
                    var voskSaidFinal = false
                    synchronized(recognizerLock) {
                        val cmdRec = freeCommandRecognizer
                        if (cmdRec != null) {
                            val isFinalChunk = cmdRec.acceptWaveForm(gainAdjustedBuffer, shortsRead)
                            val rawJson = if (isFinalChunk) cmdRec.result else cmdRec.partialResult
                            currentTranscript = extractTextFromVoskJson(rawJson)
                            voskSaidFinal = isFinalChunk && currentTranscript.isNotBlank()
                        }
                    }

                    if (currentTranscript.isNotBlank()) {
                        hasSpokenInMode2 = true
                        ServiceStateHolder.update {
                            it.copy(livePartialTranscript = currentTranscript)
                        }
                    }

                    val elapsedInMode2 = (nowMs - mode2StartedAtMs).coerceAtLeast(0L)
                    val silenceDuration = (nowMs - lastVoiceActivityAtMs).coerceAtLeast(0L)

                    val reachedSilenceAfterSpeech = voskSaidFinal ||
                            (hasSpokenInMode2 && silenceDuration >= COMMAND_SILENCE_TIMEOUT_MS)
                    val reachedMaxTimeout = elapsedInMode2 >= COMMAND_MAX_DURATION_MS

                    if (reachedSilenceAfterSpeech || reachedMaxTimeout) {
                        val speechEndElapsedMs = SystemClock.elapsedRealtime()
                        val finalSpokenText = if (voskSaidFinal) {
                            synchronized(recognizerLock) {
                                freeCommandRecognizer?.reset()
                            }
                            currentTranscript
                        } else {
                            synchronized(recognizerLock) {
                                val cmdRec = freeCommandRecognizer
                                val json = cmdRec?.finalResult ?: "{}"
                                cmdRec?.reset()
                                extractTextFromVoskJson(json)
                            }.ifBlank { currentTranscript }
                        }

                        ServiceStateHolder.update {
                            it.copy(
                                liveFinalTranscript = finalSpokenText.ifBlank { "(no speech detected)" },
                                livePartialTranscript = ""
                            )
                        }

                        if (finalSpokenText.isNotBlank()) {
                            HeartbeatLogger.log(
                                context,
                                HeartbeatLogEntry.EventType.COMMAND_TEXT,
                                "Heard command text: \"${redactIfCallCommand(finalSpokenText)}\""
                            )
                            val parsedCommand = CommandParser.parseCommand(finalSpokenText)
                            ActionExecutor.executeParsedCommand(
                                context = context,
                                rawSpokenText = finalSpokenText,
                                command = parsedCommand,
                                speechEndElapsedMs = speechEndElapsedMs
                            )
                        }

                        onReleaseCommandWakeLock("Returned to Mode 1")
                        currentMode = ListeningMode.MODE_1_WAITING_WAKE_WORD
                        ServiceStateHolder.update {
                            it.copy(
                                listeningMode = "Listening",
                                lastSpokenCommand = finalSpokenText.ifBlank { it.lastSpokenCommand },
                                statusDetail = "Listening"
                            )
                        }
                    }
                }

                ListeningMode.IDLE -> Unit
            }
        }

        releaseResources()
    }

    /**
     * Section A.2: Tries `VOICE_RECOGNITION` -> `MIC` -> `VOICE_COMMUNICATION` and selects
     * the first source that initializes and produces a valid signal.
     */
    private fun openAudioRecordWithFallbackChain(
        bufferSizeBytes: Int
    ): Pair<AudioRecord, AudioSourceCandidate>? {
        var firstInitialized: Pair<AudioRecord, AudioSourceCandidate>? = null
        val probeBuffer = ShortArray(800) // 50ms probe

        for (candidate in AUDIO_SOURCE_CHAIN) {
            val rec = tryOpenSingleSource(candidate, bufferSizeBytes) ?: continue
            val read = rec.read(probeBuffer, 0, probeBuffer.size)
            val probeRms = if (read > 0) computeRms(probeBuffer, read) else 0.0

            if (probeRms > 0.5) {
                firstInitialized?.first?.let {
                    runCatching {
                        it.stop()
                        it.release()
                    }
                }
                HeartbeatLogger.log(
                    context,
                    HeartbeatLogEntry.EventType.HEARTBEAT,
                    "Selected AudioSource ${candidate.name} (initial probe RMS=${String.format(Locale.US, "%.1f", probeRms)})"
                )
                return rec to candidate
            }

            if (firstInitialized == null) {
                firstInitialized = rec to candidate
            } else {
                runCatching {
                    rec.stop()
                    rec.release()
                }
            }
        }
        return firstInitialized
    }

    private fun tryOpenSingleSource(
        candidate: AudioSourceCandidate,
        bufferSizeBytes: Int
    ): AudioRecord? {
        return try {
            val rec = AudioRecord(
                candidate.sourceId,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                rec.startRecording()
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec
                } else {
                    rec.release()
                    null
                }
            } else {
                rec.release()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Section A.2: Simple software gain & clamping so quiet `VOICE_RECOGNITION` audio streams
     * are boosted cleanly before being passed to Vosk.
     */
    private fun applySoftwareGain(
        src: ShortArray,
        dst: ShortArray,
        count: Int,
        gain: Float
    ) {
        for (i in 0 until count) {
            val amplified = (src[i] * gain).toInt()
            dst[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Section A.6 & A.7: Unpacks `assets/model-en-in` (Indian English small Vosk model),
     * creates the Mode 1 wake recognizer (with grammar) and Mode 2 command recognizer
     * (FREE recognition without grammar), and runs a warm-up pass at service start.
     */
    private fun initAndWarmUpVoskModel(): Boolean {
        val modelDir = unpackModelFromAssets(ASSET_MODEL_EN_IN)
            ?: unpackModelFromAssets(ASSET_MODEL_EN_US)
            ?: return false

        return try {
            val loadedModel = Model(modelDir.absolutePath)
            synchronized(recognizerLock) {
                voskModel = loadedModel

                val wakeGrammar = VoskGrammarBuilder.buildWakeWordGrammarJson(selectedWakePhrase)
                val wakeRec = Recognizer(loadedModel, SAMPLE_RATE_HZ.toFloat(), wakeGrammar)

                // Section A.7: Free recognition for commands (no grammar parameter!)
                val freeRec = Recognizer(loadedModel, SAMPLE_RATE_HZ.toFloat())

                // Section A.6: Warm up both recognizers with a dummy 0.25s silence buffer at service start
                val warmUpSilence = ShortArray(4000)
                wakeRec.acceptWaveForm(warmUpSilence, warmUpSilence.size)
                wakeRec.reset()
                freeRec.acceptWaveForm(warmUpSilence, warmUpSilence.size)
                freeRec.reset()

                wakeRecognizer = wakeRec
                freeCommandRecognizer = freeRec
            }

            ServiceStateHolder.update {
                it.copy(
                    isVoskModelLoaded = true,
                    modelStatusMessage = "Indian English Vosk model loaded & warmed up (${modelDir.name})"
                )
            }
            HeartbeatLogger.log(
                context,
                HeartbeatLogEntry.EventType.HEARTBEAT,
                "Loaded & warmed up Vosk model from ${modelDir.name}"
            )
            true
        } catch (e: Throwable) {
            val msg = "Vosk binary weights not yet placed in assets/model-en-in; using on-device SpeechRecognizer bridge"
            ServiceStateHolder.update {
                it.copy(
                    isVoskModelLoaded = false,
                    modelStatusMessage = msg
                )
            }
            false
        }
    }

    /**
     * On-device Android SpeechRecognizer fallback bridge (`EXTRA_PREFER_OFFLINE = true`)
     * used automatically if the 36 MB `vosk-model-small-en-in-0.4` binary files have not yet
     * been placed into `assets/model-en-in/`.
     */
    private fun startFallbackOnDeviceRecognizer(isWakeAlreadyTriggered: Boolean) {
        if (isFallbackRecognizerListening) return
        mainHandler.post {
            runCatching {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) return@post
                if (fallbackSpeechRecognizer == null) {
                    fallbackSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                }

                var inCommandStage = isWakeAlreadyTriggered
                fallbackSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isFallbackRecognizerListening = true
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = list?.firstOrNull()?.trim().orEmpty()
                        if (partial.isNotEmpty()) {
                            ServiceStateHolder.update {
                                it.copy(livePartialTranscript = partial)
                            }
                            if (!inCommandStage && VoskGrammarBuilder.matchesWakePhrase(partial, selectedWakePhrase)) {
                                inCommandStage = true
                                val wakeTimeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                                HeartbeatLogger.log(
                                    context,
                                    HeartbeatLogEntry.EventType.WAKE_DETECTED,
                                    "Wake word detected: \"$selectedWakePhrase\""
                                )
                                playWakeBeepAndVibrate(reason = "Wake word '$selectedWakePhrase'")
                                ServiceStateHolder.update {
                                    it.copy(
                                        wakeWordDetected = true,
                                        lastWakeDetectedTime = wakeTimeStr
                                    )
                                }
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isFallbackRecognizerListening = false
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val finalText = list?.firstOrNull()?.trim().orEmpty()
                        if (finalText.isNotEmpty()) {
                            ServiceStateHolder.update {
                                it.copy(
                                    liveFinalTranscript = finalText,
                                    livePartialTranscript = ""
                                )
                            }
                            handleFallbackRecognizedText(finalText, inCommandStage)
                        }
                    }

                    override fun onEndOfSpeech() {
                        isFallbackRecognizerListening = false
                    }

                    override fun onError(error: Int) {
                        isFallbackRecognizerListening = false
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                isFallbackRecognizerListening = true
                fallbackSpeechRecognizer?.startListening(intent)
            }
        }
    }

    private fun handleFallbackRecognizedText(fullText: String, wasWakeAlreadyTriggered: Boolean) {
        val norm = fullText.lowercase().trim()
        val matchedWake = wasWakeAlreadyTriggered || VoskGrammarBuilder.matchesWakePhrase(norm, selectedWakePhrase)

        if (matchedWake && !wasWakeAlreadyTriggered) {
            val wakeTimeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            HeartbeatLogger.log(
                context,
                HeartbeatLogEntry.EventType.WAKE_DETECTED,
                "Wake word detected: \"$selectedWakePhrase\""
            )
            playWakeBeepAndVibrate(reason = "Wake word '$selectedWakePhrase'")
            ServiceStateHolder.update {
                it.copy(
                    wakeWordDetected = true,
                    lastWakeDetectedTime = wakeTimeStr
                )
            }
        }

        // Strip wake phrase prefix if user said "hey computer torch on" in one breath
        var commandPortion = norm
        for (wp in VoskGrammarBuilder.WAKE_PHRASES) {
            if (commandPortion.startsWith(wp)) {
                commandPortion = commandPortion.removePrefix(wp).trim()
            }
        }

        if (commandPortion.isNotEmpty() && (matchedWake || wasWakeAlreadyTriggered)) {
            val speechEndMs = SystemClock.elapsedRealtime()
            HeartbeatLogger.log(
                context,
                HeartbeatLogEntry.EventType.COMMAND_TEXT,
                "Heard command text: \"${redactIfCallCommand(commandPortion)}\""
            )
            val parsed = CommandParser.parseCommand(commandPortion)
            ActionExecutor.executeParsedCommand(
                context = context,
                rawSpokenText = commandPortion,
                command = parsed,
                speechEndElapsedMs = speechEndMs
            )
        } else if (matchedWake && commandPortion.isEmpty()) {
            // User said only "hey computer" -> immediately open a second listen window for the command
            mainHandler.postDelayed({
                startFallbackOnDeviceRecognizer(isWakeAlreadyTriggered = true)
            }, BEEP_MUTE_WINDOW_MS)
        }
    }

    private fun stopFallbackSpeechRecognizer() {
        mainHandler.post {
            runCatching {
                fallbackSpeechRecognizer?.stopListening()
                fallbackSpeechRecognizer?.destroy()
            }
            fallbackSpeechRecognizer = null
            isFallbackRecognizerListening = false
        }
    }

    private fun playPcmChimeWithUsage(usage: Int): Boolean {
        return try {
            val durationMs = 130
            val numSamples = (SAMPLE_RATE_HZ * durationMs) / 1000
            val samples = ShortArray(numSamples)

            // Two-note ascending pleasant chime (880 Hz -> 1175 Hz)
            val half = numSamples / 2
            for (i in 0 until numSamples) {
                val freq = if (i < half) 880.0 else 1175.0
                val envelope = min(1.0, (numSamples - i).toDouble() / (numSamples * 0.25))
                val angle = 2.0 * PI * i * freq / SAMPLE_RATE_HZ
                samples[i] = (sin(angle) * 24000 * envelope).toInt().toShort()
            }

            val attrs = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val byteCount = samples.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(byteCount, AudioTrack.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep((durationMs + 25).toLong())
            track.stop()
            track.release()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun triggerShortVibration() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(75L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(75L)
                }
            }
        }
    }

    private fun unpackModelFromAssets(assetFolderName: String): File? {
        return try {
            val assetManager = context.assets
            val allFiles = mutableListOf<String>()
            collectAssetFiles(assetManager, assetFolderName, allFiles)
            if (allFiles.isEmpty()) return null

            val targetDir = File(context.filesDir, assetFolderName)
            val assetUuid = runCatching {
                assetManager.open("$assetFolderName/uuid").bufferedReader().use { it.readText().trim() }
            }.getOrDefault("default-uuid")

            val localUuidFile = File(targetDir, "uuid")
            if (targetDir.exists() && localUuidFile.exists() && localUuidFile.readText().trim() == assetUuid) {
                return targetDir
            }

            targetDir.deleteRecursively()
            targetDir.mkdirs()

            allFiles.forEachIndexed { index, assetPath ->
                val relPath = assetPath.removePrefix("$assetFolderName/")
                val outFile = File(targetDir, relPath)
                outFile.parentFile?.mkdirs()
                assetManager.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val pct = (((index + 1) * 100) / allFiles.size)
                ServiceStateHolder.update {
                    it.copy(modelStatusMessage = "Unpacking $assetFolderName… $pct%")
                }
            }
            targetDir
        } catch (_: Exception) {
            null
        }
    }

    private fun collectAssetFiles(
        assetManager: AssetManager,
        path: String,
        outList: MutableList<String>
    ) {
        val children = assetManager.list(path) ?: return
        if (children.isEmpty()) {
            outList.add(path)
        } else {
            for (child in children) {
                collectAssetFiles(assetManager, "$path/$child", outList)
            }
        }
    }

    private fun extractTextFromVoskJson(voskJson: String): String {
        if (voskJson.isBlank()) return ""
        val partialMatch = Regex("\"partial\"\\s*:\\s*\"([^\"]*)\"").find(voskJson)
        val textMatch = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(voskJson)
        val raw = textMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: partialMatch?.groupValues?.getOrNull(1)
            ?: ""
        return raw.lowercase()
            .replace("[unk]", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun redactIfCallCommand(text: String): String {
        val norm = text.lowercase().trim()
        return if (norm.startsWith("call ") || norm.startsWith("dial ") || norm.endsWith("call karo")) {
            "call <redacted-contact>"
        } else {
            text
        }
    }

    private fun computeRms(buffer: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / count)
    }

    private fun releaseResources() {
        runCatching {
            audioRecord?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            }
        }
        audioRecord = null

        synchronized(recognizerLock) {
            runCatching { wakeRecognizer?.close() }
            runCatching { freeCommandRecognizer?.close() }
            runCatching { voskModel?.close() }
            wakeRecognizer = null
            freeCommandRecognizer = null
            voskModel = null
        }

        ServiceStateHolder.update {
            it.copy(
                isMicOpen = false,
                micRmsValue = 0f,
                micLevelNormalized = 0f,
                listeningMode = "Off"
            )
        }
    }
}
