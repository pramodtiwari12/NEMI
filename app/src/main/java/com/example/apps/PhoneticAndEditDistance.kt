package com.example.apps

import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin phonetic encoder (Double-Metaphone style) and Levenshtein edit distance
 * used for offline app-name matching and wake-word fuzzy tolerance.
 */
object PhoneticAndEditDistance {

    /**
     * Normalizes a label to lowercase alphanumeric tokens separated by single spaces.
     * Note: strips apostrophes without adding spaces so "What's up" -> "whats up".
     */
    fun normalizeText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Removes all spaces from normalized text so split words like "you tube" -> "youtube"
     * and "whats app" -> "whatsapp".
     */
    fun collapseSpaces(raw: String): String {
        return normalizeText(raw).replace(" ", "")
    }

    /**
     * Encodes a phrase or word into a Double-Metaphone-style phonetic key.
     * Handles common English and Indian-English pronunciation equivalences:
     * ph -> F, wh -> W, sh/ch -> X, th -> 0, ck/q -> K, v/w -> F, z -> S, dge -> J, etc.
     */
    fun encodePhonetic(raw: String): String {
        val collapsed = collapseSpaces(raw)
        if (collapsed.isEmpty()) return ""

        var s = collapsed
            .replace("whats", "wats")
            .replace("phone", "fon")
            .replace("fone", "fon")
            .replace("ph", "f")
            .replace("wh", "w")
            .replace("sh", "x")
            .replace("ch", "x")
            .replace("th", "0")
            .replace("ck", "k")
            .replace("qu", "kw")
            .replace("dg", "j")
            .replace("gh", "g")
            .replace("kn", "n")
            .replace("wr", "r")
            .replace("you", "u")
            .replace("tube", "tub")
            .replace("pay", "p")
            .replace("pe", "p")

        val out = StringBuilder()
        var prevChar = ' '
        for (i in s.indices) {
            val c = s[i]
            val mapped = when (c) {
                'a', 'e', 'i', 'o', 'u', 'y' -> if (i == 0) 'A' else null
                'b', 'p' -> 'P'
                'c', 'k', 'q' -> 'K'
                'd', 't' -> 'T'
                'f', 'v', 'w' -> 'F'
                'g', 'j' -> 'J'
                'l' -> 'L'
                'm' -> 'M'
                'n' -> 'N'
                'r' -> 'R'
                's', 'z' -> 'S'
                'x' -> 'X'
                '0' -> '0'
                in '1'..'9' -> c
                else -> null
            }
            if (mapped != null && mapped != prevChar) {
                out.append(mapped)
                prevChar = mapped
            }
        }
        return out.toString()
    }

    /**
     * Standard Levenshtein edit distance between two strings.
     */
    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = min(
                    min(curr[j] + 1, prev[j + 1] + 1),
                    prev[j] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /**
     * Returns a similarity score in [0.0, 1.0] based on Levenshtein distance.
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = max(a.length, b.length)
        val dist = levenshteinDistance(a, b)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }
}
