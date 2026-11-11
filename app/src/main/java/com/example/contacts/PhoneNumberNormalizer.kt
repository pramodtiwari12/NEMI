package com.example.contacts

/**
 * Normalizes phone numbers so different representations of the same number
 * (e.g., "+91 98765-43210", "00919876543210", "098765 43210", "9876543210")
 * produce the exact same canonical string ("9876543210").
 */
object PhoneNumberNormalizer {

    /**
     * Strips formatting characters (spaces, dashes, brackets, dots) and removes
     * international/trunk prefixes (+91, 0091, 91 on 12-digit numbers, or leading 0).
     */
    fun normalizePhoneNumber(
        rawNumber: String,
        defaultCountryCallingCode: String = "91"
    ): String {
        if (rawNumber.isBlank()) return ""

        // Keep only digits and a leading '+'
        val trimmed = rawNumber.trim()
        val cleaned = buildString {
            for (i in trimmed.indices) {
                val c = trimmed[i]
                if (c.isDigit() || (c == '+' && isEmpty())) {
                    append(c)
                }
            }
        }

        if (cleaned.isEmpty()) return ""

        var digits = cleaned

        // 1. Strip "+<countryCode>" (e.g. "+919876543210" -> "9876543210")
        val plusPrefix = "+$defaultCountryCallingCode"
        if (digits.startsWith(plusPrefix)) {
            digits = digits.removePrefix(plusPrefix)
        } else if (digits.startsWith("+")) {
            digits = digits.removePrefix("+")
        }

        // 2. Strip "00<countryCode>" (e.g. "00919876543210" -> "9876543210")
        val doubleZeroPrefix = "00$defaultCountryCallingCode"
        if (digits.startsWith(doubleZeroPrefix) && digits.length > doubleZeroPrefix.length) {
            digits = digits.removePrefix(doubleZeroPrefix)
        }

        // 3. Strip "<countryCode>" when followed by a 10-digit subscriber number (e.g. "919876543210")
        if (digits.length == defaultCountryCallingCode.length + 10 &&
            digits.startsWith(defaultCountryCallingCode)
        ) {
            digits = digits.removePrefix(defaultCountryCallingCode)
        }

        // 4. Strip leading trunk '0's when followed by at least 10 digits (e.g. "09876543210")
        while (digits.startsWith("0") && digits.length > 10) {
            digits = digits.removePrefix("0")
        }

        // 5. If still longer than 10 digits, keep the last 10 national significant digits
        if (digits.length > 10) {
            digits = digits.takeLast(10)
        }

        return digits
    }

    /**
     * Returns the last [count] digits of a phone number for user-friendly confirmation
     * messages (e.g., "Calling Jagdish, number ending 3210").
     */
    fun endingDigits(number: String, count: Int = 4): String {
        val digitsOnly = number.filter { it.isDigit() }
        return if (digitsOnly.length >= count) {
            digitsOnly.takeLast(count)
        } else {
            digitsOnly.ifEmpty { number }
        }
    }
}
