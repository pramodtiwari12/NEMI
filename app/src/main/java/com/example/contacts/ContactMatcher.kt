package com.example.contacts

/**
 * Outcome of matching a spoken contact name against the phone's contacts.
 */
sealed class ContactMatchOutcome {
    data class SingleMatch(val contact: CachedContact) : ContactMatchOutcome()
    data class MultipleMatches(val contacts: List<CachedContact>) : ContactMatchOutcome()
    data class NoMatch(val query: String) : ContactMatchOutcome()
}

/**
 * Section 5: Simple case-insensitive contact matcher.
 * Priority tiers:
 * 1. Exact case-insensitive match
 * 2. "Starts with" case-insensitive match
 * 3. "Contains" case-insensitive match
 *
 * If more than one DIFFERENT contact matches at the winning tier, returns [ContactMatchOutcome.MultipleMatches]
 * so the app does NOT guess and instead lists the matching names in a notification.
 */
object ContactMatcher {

    fun matchContact(
        spokenName: String,
        contacts: List<CachedContact>
    ): ContactMatchOutcome {
        val query = spokenName.trim().lowercase().replace(Regex("\\s+"), " ")
        if (query.isEmpty() || contacts.isEmpty()) {
            return ContactMatchOutcome.NoMatch(spokenName)
        }

        // Deduplicate by contactId
        val uniqueContacts = contacts.distinctBy { it.contactId }

        // 1. Exact match
        val exactMatches = uniqueContacts.filter {
            it.displayName.trim().lowercase().replace(Regex("\\s+"), " ") == query
        }
        if (exactMatches.size == 1) {
            return ContactMatchOutcome.SingleMatch(exactMatches.first())
        }
        if (exactMatches.size > 1) {
            return ContactMatchOutcome.MultipleMatches(exactMatches)
        }

        // 2. Starts-with match
        val startsWithMatches = uniqueContacts.filter {
            it.displayName.trim().lowercase().replace(Regex("\\s+"), " ").startsWith(query)
        }
        if (startsWithMatches.size == 1) {
            return ContactMatchOutcome.SingleMatch(startsWithMatches.first())
        }
        if (startsWithMatches.size > 1) {
            return ContactMatchOutcome.MultipleMatches(startsWithMatches)
        }

        // 3. Contains match
        val containsMatches = uniqueContacts.filter {
            it.displayName.trim().lowercase().replace(Regex("\\s+"), " ").contains(query)
        }
        if (containsMatches.size == 1) {
            return ContactMatchOutcome.SingleMatch(containsMatches.first())
        }
        if (containsMatches.size > 1) {
            return ContactMatchOutcome.MultipleMatches(containsMatches)
        }

        return ContactMatchOutcome.NoMatch(spokenName)
    }
}
