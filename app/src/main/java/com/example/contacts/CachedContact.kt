package com.example.contacts

/**
 * Represents one phone number stored under a contact.
 */
data class ContactPhoneEntry(
    val rawNumber: String,
    val normalizedNumber: String = PhoneNumberNormalizer.normalizePhoneNumber(rawNumber),
    val isPrimary: Boolean = false,
    val isMobile: Boolean = true
)

/**
 * Represents a single call log record read from CallLog.Calls.
 * Constants match Android's CallLog.Calls.* values so pure JUnit tests work without mocks.
 */
data class CallHistoryEntry(
    val rawNumber: String,
    val type: Int,
    val durationSeconds: Long,
    val dateEpochMs: Long = 0L
) {
    companion object {
        const val TYPE_INCOMING = 1
        const val TYPE_OUTGOING = 2
        const val TYPE_MISSED = 3
        const val TYPE_VOICEMAIL = 4
        const val TYPE_REJECTED = 5
        const val TYPE_BLOCKED = 6
    }
}

/**
 * A contact's phone number paired with its computed call-history score.
 */
data class ScoredNumber(
    val entry: ContactPhoneEntry,
    val outgoingCalls: Int,
    val answeredIncomingCalls: Int,
    val score: Int,
    val totalTalkSeconds: Long
)

/**
 * Result of selecting which phone number to dial for a contact.
 */
sealed class NumberChoiceResult {
    data class Chosen(
        val selected: ScoredNumber,
        val reason: String
    ) : NumberChoiceResult()

    data class Ambiguous(
        val candidates: List<ScoredNumber>,
        val reason: String
    ) : NumberChoiceResult()

    data object NoNumbers : NumberChoiceResult()
}

/**
 * In-memory cached contact with precomputed number selection.
 */
data class CachedContact(
    val contactId: Long,
    val displayName: String,
    val numbers: List<ContactPhoneEntry> = emptyList(),
    val precomputedChoice: NumberChoiceResult = NumberChoiceResult.NoNumbers
)
