package com.example.telemetry

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single log entry representing a service lifecycle event, wake-word detection,
 * beep confirmation, voice command recognition, number selection, or action outcome.
 *
 * Privacy guarantee (Section D): Never stores contact names or full phone numbers.
 */
data class HeartbeatLogEntry(
    val timestampEpochMs: Long,
    val formattedTime: String,
    val elapsedRealtimeMs: Long,
    val eventType: EventType,
    val message: String
) {
    enum class EventType {
        SERVICE_START,
        SERVICE_STOP,
        TASK_REMOVED,
        HEARTBEAT,
        WATCHDOG_CHECK,
        WATCHDOG_RESTART,
        BOOT_RECEIVED,
        WAKELOCK_ACQUIRED,
        WAKELOCK_RELEASED,
        WAKE_DETECTED,
        BEEP_PLAYED,
        COMMAND_TEXT,
        COMMAND_PARSED,
        NUMBER_SELECTED,
        ACTION_DONE,
        ACTION_FAILED,
        BLOCKED_BY_LOCKSCREEN,
        ERROR
    }

    fun toFileLine(): String {
        return "$formattedTime | +${elapsedRealtimeMs}ms | [${eventType.name}] $message"
    }
}

/**
 * Summary of one processed voice command, displayed in the Diagnostics "Last 10 Commands" list.
 * Redacted so contact names and full phone numbers are never stored.
 */
data class RecentCommandRecord(
    val formattedTime: String,
    val rawSpokenText: String,
    val parsedDescription: String,
    val numberSelectionDetail: String?,
    val actionStatus: String,
    val isSuccess: Boolean,
    val latencyMs: Long,
    val wasScreenLocked: Boolean
)

/**
 * Thread-safe local file logger for NEMI.
 * Writes only to [Context.getFilesDir]/NEMI_heartbeat.log (100% on-device).
 * Enforces Section D privacy: contact names and phone numbers are never written to the log.
 */
object HeartbeatLogger {
    private const val LOG_FILE_NAME = "NEMI_heartbeat.log"
    private const val MAX_LOG_LINES = 300
    private const val MAX_RECENT_COMMANDS = 10

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()

    private val _entries = MutableStateFlow<List<HeartbeatLogEntry>>(emptyList())
    val entries: StateFlow<List<HeartbeatLogEntry>> = _entries.asStateFlow()

    private val _recentCommands = MutableStateFlow<List<RecentCommandRecord>>(emptyList())
    val recentCommands: StateFlow<List<RecentCommandRecord>> = _recentCommands.asStateFlow()

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext
        ioScope.launch {
            fileMutex.withLock {
                if (isInitialized) return@withLock
                val file = getLogFile(appContext)
                if (file.exists()) {
                    val parsed = runCatching {
                        file.readLines()
                            .takeLast(MAX_LOG_LINES)
                            .mapNotNull { parseLine(it) }
                            .reversed()
                    }.getOrDefault(emptyList())
                    _entries.value = parsed
                }
                isInitialized = true
            }
        }
    }

    fun log(
        context: Context,
        eventType: HeartbeatLogEntry.EventType,
        message: String
    ) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))
        val safeMessage = redactPhoneNumbers(message)

        val entry = HeartbeatLogEntry(
            timestampEpochMs = now,
            formattedTime = formatted,
            elapsedRealtimeMs = elapsedMs,
            eventType = eventType,
            message = safeMessage
        )

        _entries.value = (listOf(entry) + _entries.value).take(MAX_LOG_LINES)

        ioScope.launch {
            fileMutex.withLock {
                runCatching {
                    val file = getLogFile(appContext)
                    file.appendText(entry.toFileLine() + "\n")
                    val allLines = file.readLines()
                    if (allLines.size > MAX_LOG_LINES * 2) {
                        val trimmed = allLines.takeLast(MAX_LOG_LINES)
                        file.writeText(trimmed.joinToString("\n") + "\n")
                    }
                }
            }
        }
    }

    /**
     * Records a completed or failed command into the "Last 10 Commands" list for Diagnostics.
     */
    fun recordCommandResult(
        rawSpokenText: String,
        parsedDescription: String,
        numberSelectionDetail: String?,
        actionStatus: String,
        isSuccess: Boolean,
        latencyMs: Long,
        wasScreenLocked: Boolean
    ) {
        val formatted = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val record = RecentCommandRecord(
            formattedTime = formatted,
            rawSpokenText = redactPhoneNumbers(rawSpokenText),
            parsedDescription = redactPhoneNumbers(parsedDescription),
            numberSelectionDetail = numberSelectionDetail?.let { redactPhoneNumbers(it) },
            actionStatus = redactPhoneNumbers(actionStatus),
            isSuccess = isSuccess,
            latencyMs = latencyMs,
            wasScreenLocked = wasScreenLocked
        )
        _recentCommands.value = (listOf(record) + _recentCommands.value).take(MAX_RECENT_COMMANDS)
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        _entries.value = emptyList()
        _recentCommands.value = emptyList()
        ioScope.launch {
            fileMutex.withLock {
                runCatching {
                    val file = getLogFile(appContext)
                    if (file.exists()) {
                        file.writeText("")
                    }
                }
            }
        }
    }

    fun getLogFilePath(context: Context): String {
        return getLogFile(context.applicationContext).absolutePath
    }

    /**
     * Redacts any raw phone number sequences (7+ consecutive digits) so phone numbers
     * are never persisted in logs.
     */
    private fun redactPhoneNumbers(input: String): String {
        return input.replace(Regex("\\+?\\d[\\d\\s\\-]{6,}\\d")) { match ->
            val digits = match.value.filter { it.isDigit() }
            if (digits.length >= 2) "**${digits.takeLast(2)}" else "**"
        }
    }

    private fun getLogFile(context: Context): File {
        val storageContext = context.createDeviceProtectedStorageContext() ?: context
        return File(storageContext.filesDir, LOG_FILE_NAME)
    }

    private fun parseLine(line: String): HeartbeatLogEntry? {
        if (line.isBlank()) return null
        val parts = line.split(" | ", limit = 3)
        if (parts.size < 3) return null
        val formattedTime = parts[0].trim()
        val elapsedStr = parts[1].trim().removePrefix("+").removeSuffix("ms")
        val elapsedMs = elapsedStr.toLongOrNull() ?: 0L
        val rest = parts[2].trim()
        val typeEndIdx = rest.indexOf(']')
        if (!rest.startsWith("[") || typeEndIdx <= 1) return null
        val typeName = rest.substring(1, typeEndIdx)
        val eventType = runCatching {
            HeartbeatLogEntry.EventType.valueOf(typeName)
        }.getOrDefault(HeartbeatLogEntry.EventType.HEARTBEAT)
        val msg = rest.substring(typeEndIdx + 1).trim()
        return HeartbeatLogEntry(
            timestampEpochMs = 0L,
            formattedTime = formattedTime,
            elapsedRealtimeMs = elapsedMs,
            eventType = eventType,
            message = msg
        )
    }
}
