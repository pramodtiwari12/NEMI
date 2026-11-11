package com.example.contacts

/**
 * Section 6: Smart offline number selection for contacts with multiple phone numbers.
 *
 * Rules:
 * - Normalize every number before comparing (+91, 0091, leading 0, spaces, dashes, brackets).
 * - Score per number = outgoing calls + answered incoming calls (duration > 0).
 *   Missed and rejected calls score 0.
 * - Total talk time (seconds) is the second tie-breaker when call scores are > 0 and tied.
 * - If scores are equal or all zero:
 *   1. Use the contact's default/primary number (`isPrimary == true`).
 *   2. If none, use the first mobile number (`isMobile == true`).
 *   3. If still unclear, do NOT guess: return [NumberChoiceResult.Ambiguous] so the app
 *      shows a notification listing both numbers' ending digits.
 */
object SmartNumberSelector {

    fun chooseNumberForContact(
        contactNumbers: List<ContactPhoneEntry>,
        callHistory: List<CallHistoryEntry>
    ): NumberChoiceResult {
        if (contactNumbers.isEmpty()) {
            return NumberChoiceResult.NoNumbers
        }

        // Group/deduplicate contact numbers that normalize to the exact same canonical digits
        // (e.g., if a contact saved both "+91 98765 43210" and "9876543210" for the same line)
        val mergedNumbers = contactNumbers
            .filter { it.normalizedNumber.isNotEmpty() }
            .groupBy { it.normalizedNumber }
            .map { (normalized, entries) ->
                val first = entries.first()
                ContactPhoneEntry(
                    rawNumber = first.rawNumber,
                    normalizedNumber = normalized,
                    isPrimary = entries.any { it.isPrimary },
                    isMobile = entries.any { it.isMobile }
                )
            }

        if (mergedNumbers.isEmpty()) {
            return NumberChoiceResult.NoNumbers
        }

        // Single unique number -> immediate match
        if (mergedNumbers.size == 1) {
            val single = mergedNumbers.first()
            val scored = scoreSingleNumber(single, callHistory)
            return NumberChoiceResult.Chosen(
                selected = scored,
                reason = "Only phone number on contact (ending ${PhoneNumberNormalizer.endingDigits(single.rawNumber)})"
            )
        }

        // Multiple numbers -> compute call history scores for each number
        val scoredList = mergedNumbers.map { phoneEntry ->
            scoreSingleNumber(phoneEntry, callHistory)
        }

        // Sort by score descending, then totalTalkSeconds descending
        val sorted = scoredList.sortedWith(
            compareByDescending<ScoredNumber> { it.score }
                .thenByDescending { it.totalTalkSeconds }
        )

        val best = sorted[0]
        val second = sorted[1]

        // Check if the top number wins by call score or talk-time tie-breaker
        if (best.score > 0) {
            if (best.score > second.score) {
                return NumberChoiceResult.Chosen(
                    selected = best,
                    reason = "Highest call history score=${best.score} (${best.outgoingCalls} outgoing + ${best.answeredIncomingCalls} answered incoming)"
                )
            }
            if (best.score == second.score && best.totalTalkSeconds > second.totalTalkSeconds) {
                return NumberChoiceResult.Chosen(
                    selected = best,
                    reason = "Tied call count (${best.score}), won on talk time (${best.totalTalkSeconds}s vs ${second.totalTalkSeconds}s)"
                )
            }
        }

        // Tie or all scores are 0 -> Fallback 1: Contact's default/primary number
        val primaryCandidates = scoredList.filter { it.entry.isPrimary }
        if (primaryCandidates.size == 1) {
            val chosenPrimary = primaryCandidates.first()
            return NumberChoiceResult.Chosen(
                selected = chosenPrimary,
                reason = "Default/primary number fallback (call scores tied or zero)"
            )
        }

        // Fallback 2: First mobile number
        val mobileCandidates = scoredList.filter { it.entry.isMobile }
        if (mobileCandidates.isNotEmpty()) {
            val firstMobile = mobileCandidates.first()
            return NumberChoiceResult.Chosen(
                selected = firstMobile,
                reason = "First mobile number fallback (no primary number set)"
            )
        }

        // Fallback 3: Still unclear -> do not guess, let user choose from notification
        return NumberChoiceResult.Ambiguous(
            candidates = scoredList,
            reason = "Scores equal/zero and no primary or mobile number marked"
        )
    }

    private fun scoreSingleNumber(
        phoneEntry: ContactPhoneEntry,
        callHistory: List<CallHistoryEntry>
    ): ScoredNumber {
        var outgoing = 0
        var answeredIncoming = 0
        var talkSeconds = 0L

        for (call in callHistory) {
            val normalizedCallNum = PhoneNumberNormalizer.normalizePhoneNumber(call.rawNumber)
            if (normalizedCallNum.isNotEmpty() && normalizedCallNum == phoneEntry.normalizedNumber) {
                when (call.type) {
                    CallHistoryEntry.TYPE_OUTGOING -> {
                        outgoing += 1
                        if (call.durationSeconds > 0) {
                            talkSeconds += call.durationSeconds
                        }
                    }
                    CallHistoryEntry.TYPE_INCOMING -> {
                        // Only answered incoming calls (duration > 0) count
                        if (call.durationSeconds > 0) {
                            answeredIncoming += 1
                            talkSeconds += call.durationSeconds
                        }
                    }
                    // TYPE_MISSED and TYPE_REJECTED score 0
                }
            }
        }

        return ScoredNumber(
            entry = phoneEntry,
            outgoingCalls = outgoing,
            answeredIncomingCalls = answeredIncoming,
            score = outgoing + answeredIncoming,
            totalTalkSeconds = talkSeconds
        )
    }
}
