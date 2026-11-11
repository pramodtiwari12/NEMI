package com.example.apps

import kotlin.math.max
import kotlin.math.min

/**
 * Metadata cached in memory for each launchable application on the device.
 */
data class InstalledAppEntry(
    val label: String,
    val packageName: String,
    val normalizedLabel: String = PhoneticAndEditDistance.normalizeText(label),
    val collapsedLabel: String = PhoneticAndEditDistance.collapseSpaces(label),
    val phoneticKey: String = PhoneticAndEditDistance.encodePhonetic(label),
    val launchCount: Int = 0
)

/**
 * Result of matching a spoken app name against the installed app catalog.
 */
sealed class AppMatchOutcome {
    data class ConfidentMatch(
        val app: InstalledAppEntry,
        val matchedVia: String,
        val confidence: Double
    ) : AppMatchOutcome()

    data class Ambiguous(
        val topCandidates: List<InstalledAppEntry>,
        val spokenQuery: String,
        val reason: String
    ) : AppMatchOutcome()

    data class NoConfidentMatch(
        val topCandidates: List<InstalledAppEntry>,
        val spokenQuery: String
    ) : AppMatchOutcome()
}

/**
 * Section B.3 & B.4: App Matcher with strict safety against random opens.
 *
 * Matching order:
 * 1. Exact normalized label (or exact space-collapsed label, e.g. "you tube" -> "youtube")
 * 2. Alias table (`assets/app_aliases.txt` + user-learned aliases)
 * 3. Starts-with / Contains
 * 4. Phonetic (Double Metaphone) + Levenshtein distance (with and without spaces)
 *    + small bonus for frequently opened apps.
 *
 * Safety:
 * If the best match is not clearly better than the second, or confidence is below threshold,
 * returns [AppMatchOutcome.Ambiguous] or [AppMatchOutcome.NoConfidentMatch] with up to top 3 candidates.
 */
object AppMatcher {

    private val FILLER_APP_WORDS = setOf(
        "app", "application", "please", "the", "my", "kholo", "kholdo", "open", "launch"
    )

    val DEFAULT_BUNDLED_ALIASES: Map<String, String> = mapOf(
        "whats up" to "WhatsApp",
        "what s up" to "WhatsApp",
        "whats app" to "WhatsApp",
        "watsapp" to "WhatsApp",
        "watch app" to "WhatsApp",
        "you tube" to "YouTube",
        "u tube" to "YouTube",
        "utube" to "YouTube",
        "your tube" to "YouTube",
        "insta" to "Instagram",
        "insta gram" to "Instagram",
        "phone pay" to "PhonePe",
        "fone pay" to "PhonePe",
        "phone pe" to "PhonePe",
        "g pay" to "Google Pay",
        "google pe" to "Google Pay",
        "gpay" to "Google Pay",
        "pay tm" to "Paytm",
        "chrome browser" to "Chrome",
        "google map" to "Maps",
        "google maps" to "Maps",
        "g mail" to "Gmail",
        "calc" to "Calculator",
        "cam" to "Camera",
        "play store" to "Play Store",
        "google play" to "Play Store"
    )

    fun matchApp(
        rawQuery: String,
        installedApps: List<InstalledAppEntry>,
        aliasTable: Map<String, String> = DEFAULT_BUNDLED_ALIASES
    ): AppMatchOutcome {
        val cleanedQuery = cleanAppQuery(rawQuery)
        val collapsedQuery = cleanedQuery.replace(" ", "")
        if (cleanedQuery.isEmpty() || installedApps.isEmpty()) {
            return AppMatchOutcome.NoConfidentMatch(emptyList(), rawQuery.trim())
        }

        val uniqueApps = installedApps.distinctBy { it.packageName }

        // ---------------------------------------------------------------------
        // 1. Exact normalized label OR exact space-collapsed label ("you tube" -> "youtube")
        // ---------------------------------------------------------------------
        val exactMatches = uniqueApps.filter { app ->
            app.normalizedLabel == cleanedQuery ||
                (collapsedQuery.length >= 2 && app.collapsedLabel == collapsedQuery)
        }
        if (exactMatches.size == 1) {
            return AppMatchOutcome.ConfidentMatch(
                app = exactMatches.first(),
                matchedVia = "Exact label",
                confidence = 1.0
            )
        }
        if (exactMatches.size > 1) {
            val sortedExact = exactMatches.sortedByDescending { it.launchCount }
            if (sortedExact[0].launchCount >= sortedExact[1].launchCount + 3) {
                return AppMatchOutcome.ConfidentMatch(
                    app = sortedExact[0],
                    matchedVia = "Exact label + frequent usage",
                    confidence = 0.95
                )
            }
            return AppMatchOutcome.Ambiguous(
                topCandidates = sortedExact.take(3),
                spokenQuery = cleanedQuery,
                reason = "Multiple apps share the exact name '$cleanedQuery'"
            )
        }

        // ---------------------------------------------------------------------
        // 2. Alias table lookup (checks raw normalized, cleaned, and space-collapsed keys)
        // ---------------------------------------------------------------------
        val rawNormQuery = PhoneticAndEditDistance.normalizeText(rawQuery)
        val normalizedAliasMap = mutableMapOf<String, String>()
        val collapsedAliasMap = mutableMapOf<String, String>()
        for ((k, v) in aliasTable) {
            val target = v.trim()
            val normK = PhoneticAndEditDistance.normalizeText(k)
            val cleanK = cleanAppQuery(k)
            if (normK.isNotEmpty()) {
                normalizedAliasMap[normK] = target
                collapsedAliasMap[normK.replace(" ", "")] = target
            }
            if (cleanK.isNotEmpty()) {
                normalizedAliasMap[cleanK] = target
                collapsedAliasMap[cleanK.replace(" ", "")] = target
            }
        }

        val aliasTarget = normalizedAliasMap[rawNormQuery]
            ?: normalizedAliasMap[cleanedQuery]
            ?: collapsedAliasMap[collapsedQuery]
        if (aliasTarget != null) {
            val targetNorm = PhoneticAndEditDistance.normalizeText(aliasTarget)
            val targetCollapsed = PhoneticAndEditDistance.collapseSpaces(aliasTarget)
            val aliasMatchedApps = uniqueApps.filter { app ->
                app.packageName.equals(aliasTarget, ignoreCase = true) ||
                    app.normalizedLabel == targetNorm ||
                    app.collapsedLabel == targetCollapsed
            }
            if (aliasMatchedApps.size == 1) {
                return AppMatchOutcome.ConfidentMatch(
                    app = aliasMatchedApps.first(),
                    matchedVia = "Alias table ('$cleanedQuery' -> '$aliasTarget')",
                    confidence = 0.98
                )
            } else if (aliasMatchedApps.size > 1) {
                return AppMatchOutcome.Ambiguous(
                    topCandidates = aliasMatchedApps.take(3),
                    spokenQuery = cleanedQuery,
                    reason = "Alias '$aliasTarget' matched multiple apps"
                )
            }
        }

        // ---------------------------------------------------------------------
        // 3. Starts-with / Contains matching
        // ---------------------------------------------------------------------
        if (collapsedQuery.length >= 3) {
            val prefixOrContains = uniqueApps.mapNotNull { app ->
                val bonus = min(0.05, app.launchCount * 0.01)
                when {
                    app.normalizedLabel.startsWith(cleanedQuery) ||
                        app.collapsedLabel.startsWith(collapsedQuery) -> {
                        val ratio = collapsedQuery.length.toDouble() / max(1, app.collapsedLabel.length).toDouble()
                        app to (0.84 + 0.10 * ratio + bonus)
                    }
                    // Token-level startsWith (e.g. "store" in "Play Store")
                    app.normalizedLabel.split(" ").any { it.startsWith(cleanedQuery) && cleanedQuery.length >= 4 } -> {
                        val ratio = cleanedQuery.length.toDouble() / max(1, app.normalizedLabel.length).toDouble()
                        app to (0.80 + 0.08 * ratio + bonus)
                    }
                    collapsedQuery.length >= 4 && app.collapsedLabel.contains(collapsedQuery) -> {
                        val ratio = collapsedQuery.length.toDouble() / max(1, app.collapsedLabel.length).toDouble()
                        app to (0.76 + 0.08 * ratio + bonus)
                    }
                    else -> null
                }
            }.sortedByDescending { it.second }

            if (prefixOrContains.size == 1) {
                val (winner, score) = prefixOrContains.first()
                return AppMatchOutcome.ConfidentMatch(
                    app = winner,
                    matchedVia = "Starts-with / Contains",
                    confidence = score.coerceAtMost(0.99)
                )
            } else if (prefixOrContains.size > 1) {
                val best = prefixOrContains[0]
                val second = prefixOrContains[1]
                if (best.second - second.second >= 0.07) {
                    return AppMatchOutcome.ConfidentMatch(
                        app = best.first,
                        matchedVia = "Starts-with / Contains",
                        confidence = best.second.coerceAtMost(0.99)
                    )
                } else {
                    return AppMatchOutcome.Ambiguous(
                        topCandidates = prefixOrContains.take(3).map { it.first },
                        spokenQuery = cleanedQuery,
                        reason = "Multiple similar apps match '$cleanedQuery'"
                    )
                }
            }
        }

        // ---------------------------------------------------------------------
        // 4. Phonetic (Double Metaphone) + Levenshtein distance (with and without spaces)
        //    + small bonus for frequently opened apps
        // ---------------------------------------------------------------------
        val queryPhonetic = PhoneticAndEditDistance.encodePhonetic(cleanedQuery)
        val scoredCandidates = uniqueApps.map { app ->
            val simSpaced = PhoneticAndEditDistance.similarity(cleanedQuery, app.normalizedLabel)
            val simCollapsed = PhoneticAndEditDistance.similarity(collapsedQuery, app.collapsedLabel)
            val bestLexicalSim = max(simSpaced, simCollapsed)

            val phoneticSim = if (queryPhonetic.isNotEmpty() && app.phoneticKey.isNotEmpty()) {
                PhoneticAndEditDistance.similarity(queryPhonetic, app.phoneticKey)
            } else {
                0.0
            }

            val exactPhoneticBonus = if (queryPhonetic.length >= 3 && queryPhonetic == app.phoneticKey) 0.08 else 0.0
            val launchBonus = min(0.05, app.launchCount * 0.01)

            val combinedScore = (bestLexicalSim * 0.72) + (phoneticSim * 0.28) + exactPhoneticBonus + launchBonus
            Triple(app, combinedScore, bestLexicalSim)
        }.sortedByDescending { it.second }

        val bestCandidate = scoredCandidates.firstOrNull()
            ?: return AppMatchOutcome.NoConfidentMatch(emptyList(), cleanedQuery)

        val secondScore = scoredCandidates.getOrNull(1)?.second ?: 0.0
        val (_, bestCombined, bestLexical) = bestCandidate

        // Strict threshold so nonsense words never open an app randomly
        if (bestCombined >= 0.72 && bestLexical >= 0.58) {
            if (bestCombined - secondScore >= 0.08) {
                return AppMatchOutcome.ConfidentMatch(
                    app = bestCandidate.first,
                    matchedVia = "Phonetic + Levenshtein",
                    confidence = bestCombined.coerceAtMost(0.99)
                )
            } else {
                return AppMatchOutcome.Ambiguous(
                    topCandidates = scoredCandidates.take(3).map { it.first },
                    spokenQuery = cleanedQuery,
                    reason = "Top phonetic matches are too close (${String.format("%.2f", bestCombined)} vs ${String.format("%.2f", secondScore)})"
                )
            }
        }

        // If moderately close (0.55..0.72), offer top 3 choices without auto-opening
        val plausibleTop3 = scoredCandidates
            .filter { it.second >= 0.52 }
            .take(3)
            .map { it.first }

        return if (plausibleTop3.isNotEmpty()) {
            AppMatchOutcome.Ambiguous(
                topCandidates = plausibleTop3,
                spokenQuery = cleanedQuery,
                reason = "Low confidence match for '$cleanedQuery' — asking user"
            )
        } else {
            AppMatchOutcome.NoConfidentMatch(
                topCandidates = scoredCandidates.take(3).map { it.first },
                spokenQuery = cleanedQuery
            )
        }
    }

    fun cleanAppQuery(rawQuery: String): String {
        val norm = PhoneticAndEditDistance.normalizeText(rawQuery)
        if (norm.isEmpty()) return ""
        val tokens = norm.split(" ").toMutableList()
        while (tokens.size > 1 && tokens.first() in FILLER_APP_WORDS) {
            tokens.removeAt(0)
        }
        while (tokens.size > 1 && tokens.last() in FILLER_APP_WORDS) {
            tokens.removeAt(tokens.lastIndex)
        }
        return tokens.joinToString(" ").trim()
    }
}
