package com.example

import com.example.asr.VoskGrammarBuilder
import com.example.contacts.CallHistoryEntry
import com.example.contacts.ContactPhoneEntry
import com.example.contacts.NumberChoiceResult
import com.example.contacts.SmartNumberSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Additional pure unit tests for Vosk grammar generation and duplicate number merging.
 */
class ExampleRobolectricTest {

    @Test
    fun `wake word grammar contains hey computer and unk`() {
        val grammar = VoskGrammarBuilder.buildWakeWordGrammarJson()
        assertTrue(grammar.contains("hey computer"))
        assertTrue(grammar.contains("[unk]"))
    }

    @Test
    fun `same contact number stored twice with and without +91 merges into single number`() {
        val withPlus91 = ContactPhoneEntry(rawNumber = "+91 98765 43210", isPrimary = false, isMobile = true)
        val withoutPrefix = ContactPhoneEntry(rawNumber = "9876543210", isPrimary = true, isMobile = true)

        val result = SmartNumberSelector.chooseNumberForContact(
            contactNumbers = listOf(withPlus91, withoutPrefix),
            callHistory = listOf(
                CallHistoryEntry("9876543210", CallHistoryEntry.TYPE_OUTGOING, durationSeconds = 60)
            )
        )
        assertTrue(result is NumberChoiceResult.Chosen)
        val chosen = (result as NumberChoiceResult.Chosen).selected
        assertEquals("9876543210", chosen.entry.normalizedNumber)
    }
}
