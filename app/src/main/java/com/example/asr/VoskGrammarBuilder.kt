package com.example.asr

import com.example.apps.PhoneticAndEditDistance

/**
 * Wake word phrases, grammar builder, and fuzzy wake-phrase matcher (Section A.4).
 *
 * Note: Per Section A.7, grammar is used ONLY for the wake phrase (Mode 1).
 * Mode 2 (Command listening) uses free recognition without a grammar restriction.
 */
object VoskGrammarBuilder {

    /**
     * Single constant list of supported wake phrases selectable in Settings.
     */
    val WAKE_PHRASES: List<String> = listOf(
        "hey computer",
        "hey phone",
        "ok phone"
    )

    const val WAKE_PHRASE: String = "hey computer"

    /**
     * Accented & phonetic variants included in the Mode 1 wake grammar so accented
     * pronunciation is not rejected by Vosk's grammar decoder.
     */
    private val WAKE_PHRASE_VARIANTS: Map<String, List<String>> = mapOf(
        "hey computer" to listOf(
            "hey computer", "he computer", "a computer", "hi computer", "okay computer"
        ),
        "hey phone" to listOf(
            "hey phone", "he phone", "a phone", "hi phone", "hey fon"
        ),
        "ok phone" to listOf(
            "ok phone", "okay phone", "o k phone", "ok fon"
        )
    )

    /**
     * Builds the JSON grammar array for Mode 1 (Wake Word waiting).
     */
    fun buildWakeWordGrammarJson(selectedWakePhrase: String = WAKE_PHRASE): String {
        val phrases = linkedSetOf<String>()
        val normSelected = PhoneticAndEditDistance.normalizeText(selectedWakePhrase)
            .ifEmpty { WAKE_PHRASE }

        phrases.add(normSelected)
        WAKE_PHRASE_VARIANTS[normSelected]?.let { phrases.addAll(it) }
        WAKE_PHRASES.forEach { phrases.add(it) }
        phrases.add("[unk]")

        return toJsonStringArray(phrases.toList())
    }

    /**
     * Checks whether [recognizedRawText] (from either partial or final result) matches
     * the [selectedWakePhrase] (or any configured wake phrase) with normalized text
     * and small fuzzy Levenshtein / phonetic tolerance.
     */
    fun matchesWakePhrase(
        recognizedRawText: String,
        selectedWakePhrase: String = WAKE_PHRASE
    ): Boolean {
        val normText = PhoneticAndEditDistance.normalizeText(
            recognizedRawText.replace("[unk]", " ")
        )
        if (normText.isEmpty()) return false

        val targets = linkedSetOf(
            PhoneticAndEditDistance.normalizeText(selectedWakePhrase).ifEmpty { WAKE_PHRASE }
        )
        targets.addAll(WAKE_PHRASES)

        for (target in targets) {
            // 1. Direct substring match
            if (normText.contains(target)) return true

            // 2. Check accepted accented variants
            val variants = WAKE_PHRASE_VARIANTS[target].orEmpty()
            if (variants.any { normText.contains(it) }) return true

            // 3. Fuzzy tolerance on sliding 2-word windows and collapsed string
            val targetCollapsed = target.replace(" ", "")
            val textWords = normText.split(" ").filter { it.isNotBlank() }

            for (i in textWords.indices) {
                // Single or 2-word window comparison
                val window2 = if (i + 1 < textWords.size) {
                    "${textWords[i]} ${textWords[i + 1]}"
                } else {
                    textWords[i]
                }
                val windowCollapsed = window2.replace(" ", "")
                val dist = PhoneticAndEditDistance.levenshteinDistance(windowCollapsed, targetCollapsed)
                if (targetCollapsed.length >= 7 && dist <= 2) {
                    return true
                }
                // Phonetic match + edit distance <= 3
                val winPhonetic = PhoneticAndEditDistance.encodePhonetic(window2)
                val targetPhonetic = PhoneticAndEditDistance.encodePhonetic(target)
                if (winPhonetic.isNotEmpty() && winPhonetic == targetPhonetic && dist <= 3) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Legacy helper kept for unit-test compatibility.
     */
    fun buildCommandGrammarJson(contactPhrases: List<String>): String {
        val items = ( listOf("call", "torch", "on", "off", "set", "alarm") + contactPhrases + "[unk]" ).distinct()
        return toJsonStringArray(items)
    }

    private fun toJsonStringArray(items: List<String>): String {
        return items.joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
            val escaped = item.replace("\\", "\\\\").replace("\"", "\\\"")
            "\"$escaped\""
        }
    }
}
