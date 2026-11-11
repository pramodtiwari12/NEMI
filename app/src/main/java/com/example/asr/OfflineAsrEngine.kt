package com.example.asr

/**
 * Stage 3 contract for grammar-constrained offline Vosk speech recognition.
 */
interface OfflineAsrEngine {
    val isWarmedUp: Boolean
    fun updateContactGrammar(contactNames: List<String>)
}
