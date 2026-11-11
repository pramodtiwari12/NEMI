package com.example.audio

/**
 * Stage 2 contract for the persistent single-instance 16 kHz PCM_16BIT AudioRecord pipeline
 * and 1.5s pre-roll ring buffer.
 */
interface AudioPipelineController {
    val sampleRateHz: Int get() = 16_000
    val ringBufferDurationMs: Int get() = 1_500
    fun startPermanentCapture()
    fun stopCapture()
}
