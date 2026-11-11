package com.example.wakeword

/**
 * Contract for Stage 2 Wake Word Detection engines (Picovoice Porcupine & openWakeWord TFLite).
 */
interface WakeWordEngine {
    val name: String
    val requiresAccessKey: Boolean
    fun start(onDetected: () -> Unit)
    fun stop()
}
