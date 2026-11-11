package com.example.service

import android.content.Context
import android.content.SharedPreferences
import com.example.asr.VoskGrammarBuilder

/**
 * Direct-Boot-aware local preferences for NEMI.
 * Stores service enabled state and user-selected wake phrase ("hey computer", "hey phone", "ok phone").
 */
object NEMIPrefs {
    private const val PREFS_NAME = "NEMI_service_prefs"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_WAKE_PHRASE = "selected_wake_phrase"
    private const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
    private const val KEY_LAST_STOP_REASON = "last_stop_reason"
    private const val KEY_WATCHDOG_RUN_COUNT = "watchdog_run_count"

    private fun prefs(context: Context): SharedPreferences {
        val deviceContext = context.applicationContext.createDeviceProtectedStorageContext()
            ?: context.applicationContext
        return deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isServiceEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SERVICE_ENABLED, false)
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun getSelectedWakePhrase(context: Context): String {
        val saved = prefs(context).getString(KEY_WAKE_PHRASE, VoskGrammarBuilder.WAKE_PHRASE)
            ?: VoskGrammarBuilder.WAKE_PHRASE
        return if (saved in VoskGrammarBuilder.WAKE_PHRASES) saved else VoskGrammarBuilder.WAKE_PHRASE
    }

    fun setSelectedWakePhrase(context: Context, phrase: String) {
        val valid = if (phrase in VoskGrammarBuilder.WAKE_PHRASES) phrase else VoskGrammarBuilder.WAKE_PHRASE
        prefs(context).edit().putString(KEY_WAKE_PHRASE, valid).apply()
    }

    fun recordHeartbeat(context: Context, timestampMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_HEARTBEAT_MS, timestampMs).apply()
    }

    fun getLastHeartbeatMs(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_HEARTBEAT_MS, 0L)
    }

    fun setLastStopReason(context: Context, reason: String) {
        prefs(context).edit().putString(KEY_LAST_STOP_REASON, reason).apply()
    }

    fun getLastStopReason(context: Context): String {
        return prefs(context).getString(KEY_LAST_STOP_REASON, "None recorded") ?: "None recorded"
    }

    fun incrementWatchdogCount(context: Context): Int {
        val next = getWatchdogCount(context) + 1
        prefs(context).edit().putInt(KEY_WATCHDOG_RUN_COUNT, next).apply()
        return next
    }

    fun getWatchdogCount(context: Context): Int {
        return prefs(context).getInt(KEY_WATCHDOG_RUN_COUNT, 0)
    }
}
