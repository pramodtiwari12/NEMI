package com.example.parser

import com.example.asr.VoskGrammarBuilder

/**
 * Tolerant, rule-based offline command parser (Section A.8, Section B, Section C).
 *
 * - Strips filler phrases ("please", "can you", "could you", "the", wake phrases).
 * - Supports Hinglish verbs:
 *   - "kholo" / "khol do" -> OpenApp
 *   - "call karo" / "phone karo" / "ko call karo" -> Call
 *   - "chalu karo" / "band karo" -> on / off
 *   - "alarm lagao" / "alarm laga do" -> Alarm
 * - Supports all basic offline assistant commands:
 *   Call, Torch, Alarm, OpenApp, SetTimer, Volume, Brightness, Time/Date/Battery, Media, Wi-Fi/BT/DND.
 */
object CommandParser {

    private val WORD_TO_NUMBER = mapOf(
        "zero" to 0, "oh" to 0, "o" to 0,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90, "hundred" to 100
    )

    private val LEADING_FILLER_PHRASES = listOf(
        "hey computer", "hey phone", "ok phone", "okay phone",
        "can you please", "could you please", "would you please",
        "can you", "could you", "would you", "please", "kindly", "just"
    )

    private val STOP_WORDS = setOf(
        "please", "kindly", "the", "a", "an", "my", "now", "right", "for", "me"
    )

    fun parseCommand(rawText: String): Command {
        val rawTrimmed = rawText.trim()
        if (rawTrimmed.isEmpty()) return Command.Unknown("")

        // Normalize punctuation while keeping colons for "6:30" and apostrophes attached
        var cleaned = rawTrimmed
            .lowercase()
            .replace("[unk]", " ")
            .replace("'", "")
            .replace("’", "")
            .replace("-", " ")
            .replace(Regex("[^a-z0-9:%\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Strip leading wake phrase or polite filler phrases
        for (phrase in LEADING_FILLER_PHRASES) {
            if (cleaned.startsWith("$phrase ")) {
                cleaned = cleaned.removePrefix("$phrase ").trim()
            }
        }

        if (cleaned.isEmpty()) return Command.Unknown(rawTrimmed)

        // Normalize Hinglish compound verbs into canonical tokens
        val hinglishNormalized = cleaned
            .replace("chalu karo", "on")
            .replace("chalu kar do", "on")
            .replace("on karo", "on")
            .replace("band karo", "off")
            .replace("band kar do", "off")
            .replace("off karo", "off")

        val words = hinglishNormalized.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return Command.Unknown(rawTrimmed)

        // ---------------------------------------------------------------------
        // 0. Disambiguation Option Choice ("one", "two", "three", "option 1")
        // ---------------------------------------------------------------------
        val optionChoice = parseOptionChoice(words)
        if (optionChoice != null) {
            return optionChoice
        }

        // ---------------------------------------------------------------------
        // 1. Torch / Flashlight ("torch on/off", "flashlight chalu karo/band karo")
        // ---------------------------------------------------------------------
        if (words.contains("torch") || words.contains("flashlight")) {
            val hasOn = words.contains("on")
            val hasOff = words.contains("off")
            if (hasOn && !hasOff) return Command.Torch(on = true)
            if (hasOff && !hasOn) return Command.Torch(on = false)
        }

        // ---------------------------------------------------------------------
        // 2. Timer ("timer 5 minutes", "set timer for 30 seconds", "timer 2 hours")
        // ---------------------------------------------------------------------
        if (words.contains("timer")) {
            val timerCmd = parseTimerCommand(words)
            if (timerCmd != null) return timerCmd
        }

        // ---------------------------------------------------------------------
        // 3. Alarm ("set alarm 7 am", "alarm 6 30 pm", "7 am ka alarm lagao")
        // ---------------------------------------------------------------------
        if (words.contains("alarm")) {
            val alarmCmd = parseAlarmCommand(words)
            if (alarmCmd != null) return alarmCmd
        }

        // ---------------------------------------------------------------------
        // 4. Volume ("volume up/down", "mute", "unmute", "volume 50 percent")
        // ---------------------------------------------------------------------
        val volumeCmd = parseVolumeCommand(words)
        if (volumeCmd != null) return volumeCmd

        // ---------------------------------------------------------------------
        // 5. Brightness ("brightness up/down", "brightness 50 percent")
        // ---------------------------------------------------------------------
        if (words.contains("brightness")) {
            val brightnessCmd = parseBrightnessCommand(words)
            if (brightnessCmd != null) return brightnessCmd
        }

        // ---------------------------------------------------------------------
        // 6. Time / Date / Battery ("what time is it", "what is the date", "battery level")
        // ---------------------------------------------------------------------
        val statusCmd = parseDeviceQueryCommand(words)
        if (statusCmd != null) return statusCmd

        // ---------------------------------------------------------------------
        // 7. Media ("play", "pause", "next", "previous")
        // ---------------------------------------------------------------------
        val mediaCmd = parseMediaCommand(words)
        if (mediaCmd != null) return mediaCmd

        // ---------------------------------------------------------------------
        // 8. System Panels: Wi-Fi / Bluetooth / Do Not Disturb
        // ---------------------------------------------------------------------
        val panelCmd = parseSystemPanelCommand(words, cleaned)
        if (panelCmd != null) return panelCmd

        // ---------------------------------------------------------------------
        // 9. Call ("call <name>", "phone <name>", "dial <name>", "<name> ko call karo")
        // ---------------------------------------------------------------------
        val callCmd = parseCallCommand(cleaned)
        if (callCmd != null) return callCmd

        // ---------------------------------------------------------------------
        // 10. Open App ("open <app>", "launch <app>", "<app> kholo", "<app> khol do")
        // ---------------------------------------------------------------------
        val openAppCmd = parseOpenAppCommand(cleaned)
        if (openAppCmd != null) return openAppCmd

        return Command.Unknown(rawTrimmed)
    }

    private fun parseOptionChoice(words: List<String>): Command.PickOption? {
        val meaningful = words.filter { it !in setOf("option", "number", "choice", "pick", "select", "the") }
        if (meaningful.size != 1) return null
        return when (meaningful.first()) {
            "1", "one", "first" -> Command.PickOption(1)
            "2", "two", "second" -> Command.PickOption(2)
            "3", "three", "third" -> Command.PickOption(3)
            else -> null
        }
    }

    private fun parseCallCommand(cleaned: String): Command.Call? {
        // Case A: Hinglish "<name> ko call karo" or "<name> call karo" or "<name> ko phone karo"
        val hinglishSuffixes = listOf(
            " ko call karo", " ko call kar do", " call karo", " call kar do",
            " ko phone karo", " ko phone kar do", " phone karo", " phone kar do"
        )
        for (suffix in hinglishSuffixes) {
            if (cleaned.endsWith(suffix)) {
                val rawName = cleaned.removeSuffix(suffix).trim()
                val stripped = stripFillerTokens(rawName)
                if (stripped.isNotEmpty()) {
                    return Command.Call(stripped)
                }
            }
        }

        // Case B: Hinglish prefix "call karo <name>" or "phone karo <name>"
        val hinglishPrefixes = listOf("call karo ", "call kar do ", "phone karo ", "phone kar do ")
        for (prefix in hinglishPrefixes) {
            if (cleaned.startsWith(prefix)) {
                val rawName = cleaned.removePrefix(prefix).removeSuffix(" ko").trim()
                val stripped = stripFillerTokens(rawName)
                if (stripped.isNotEmpty()) {
                    return Command.Call(stripped)
                }
            }
        }

        // Case C: Standard "call <name>", "dial <name>", "phone <name>"
        val tokens = cleaned.split(" ")
        val triggerIdx = tokens.indexOfFirst { it in setOf("call", "dial") }
        if (triggerIdx >= 0 && triggerIdx + 1 < tokens.size) {
            val after = tokens.subList(triggerIdx + 1, tokens.size)
                .filter { it !in setOf("karo", "kardo", "ko", "to", "on", "please", "the") }
            if (after.isNotEmpty()) {
                return Command.Call(after.joinToString(" "))
            }
        }

        // "phone <name>" (only if not "phone pay" or "phone settings")
        val phoneIdx = tokens.indexOf("phone")
        if (phoneIdx == 0 && tokens.size >= 2 && tokens[1] !in setOf("pay", "pe", "settings", "kholo")) {
            val after = tokens.subList(1, tokens.size)
                .filter { it !in setOf("karo", "kardo", "ko", "please") }
            if (after.isNotEmpty()) {
                return Command.Call(after.joinToString(" "))
            }
        }

        return null
    }

    private fun parseOpenAppCommand(cleaned: String): Command.OpenApp? {
        // Case A: Hinglish "<app> kholo" or "<app> khol do"
        val kholoSuffixes = listOf(" khol do", " kholdo", " kholo")
        for (suffix in kholoSuffixes) {
            if (cleaned.endsWith(suffix)) {
                val rawApp = cleaned.removeSuffix(suffix).trim()
                val stripped = stripFillerTokens(rawApp)
                if (stripped.isNotEmpty()) {
                    return Command.OpenApp(stripped)
                }
            }
        }

        // Case B: Hinglish prefix "kholo <app>" or "khol do <app>"
        val kholoPrefixes = listOf("khol do ", "kholdo ", "kholo ")
        for (prefix in kholoPrefixes) {
            if (cleaned.startsWith(prefix)) {
                val rawApp = cleaned.removePrefix(prefix).trim()
                val stripped = stripFillerTokens(rawApp)
                if (stripped.isNotEmpty()) {
                    return Command.OpenApp(stripped)
                }
            }
        }

        // Case C: Standard "open <app>" or "launch <app>"
        val tokens = cleaned.split(" ")
        val openIdx = tokens.indexOfFirst { it in setOf("open", "launch") }
        if (openIdx >= 0 && openIdx + 1 < tokens.size) {
            val after = tokens.subList(openIdx + 1, tokens.size)
                .filter { it !in setOf("the", "app", "application", "please", "up") }
            if (after.isNotEmpty()) {
                return Command.OpenApp(after.joinToString(" "))
            }
        }

        return null
    }

    private fun parseAlarmCommand(words: List<String>): Command.Alarm? {
        // Support both "set alarm 7 am" and Hinglish "7 am ka alarm lagao" / "alarm lagao 6 30 pm"
        val timeCandidateWords = words.filter {
            it !in setOf(
                "set", "an", "a", "the", "alarm", "for", "at", "to", "on",
                "wake", "me", "up", "please", "ka", "ki", "ko", "lagao", "laga", "do", "karo"
            )
        }
        val parsed = parseTimeTokens(timeCandidateWords) ?: return null
        return Command.Alarm(hour24 = parsed.first, minute = parsed.second)
    }

    private fun parseTimerCommand(words: List<String>): Command.SetTimer? {
        val filtered = words.filter {
            it !in setOf("set", "a", "an", "the", "timer", "for", "of", "please", "lagao", "laga", "do")
        }
        if (filtered.isEmpty()) return null

        val numbers = extractNumbersFromTokens(filtered)
        if (numbers.isEmpty()) return null
        val value = numbers.first()
        if (value <= 0) return null

        val seconds = when {
            filtered.any { it.startsWith("sec") } -> value
            filtered.any { it.startsWith("hour") || it.startsWith("hr") } -> value * 3600
            else -> value * 60 // Default to minutes ("timer 5 minutes" or "timer 5")
        }
        return Command.SetTimer(durationSeconds = seconds)
    }

    private fun parseVolumeCommand(words: List<String>): Command.Volume? {
        if (words.contains("unmute")) {
            return Command.Volume(VolumeAction.UNMUTE)
        }
        if (words.contains("mute") || (words.contains("silent") && words.contains("volume"))) {
            return Command.Volume(VolumeAction.MUTE)
        }
        if (!words.contains("volume") && !words.contains("sound")) return null

        if (words.any { it in setOf("up", "increase", "raise", "higher", "louder", "badhao") }) {
            return Command.Volume(VolumeAction.UP)
        }
        if (words.any { it in setOf("down", "decrease", "lower", "quieter", "kam") }) {
            return Command.Volume(VolumeAction.DOWN)
        }

        val numbers = extractNumbersFromTokens(words)
        if (numbers.isNotEmpty()) {
            val pct = numbers.first().coerceIn(0, 100)
            return Command.Volume(VolumeAction.SET_PERCENT, pct)
        }
        return null
    }

    private fun parseBrightnessCommand(words: List<String>): Command.Brightness? {
        if (words.any { it in setOf("up", "increase", "raise", "higher", "badhao") }) {
            return Command.Brightness(BrightnessAction.UP)
        }
        if (words.any { it in setOf("down", "decrease", "lower", "dim", "kam") }) {
            return Command.Brightness(BrightnessAction.DOWN)
        }
        val numbers = extractNumbersFromTokens(words)
        if (numbers.isNotEmpty()) {
            val pct = numbers.first().coerceIn(0, 100)
            return Command.Brightness(BrightnessAction.SET_PERCENT, pct)
        }
        return null
    }

    private fun parseDeviceQueryCommand(words: List<String>): Command.DeviceQuery? {
        if (words.contains("battery") || words.contains("charge")) {
            return Command.DeviceQuery(DeviceQueryType.BATTERY)
        }
        if (words.contains("time") && !words.contains("timer")) {
            if (words.any { it in setOf("what", "whats", "tell", "current", "check", "kitna", "kya") } ||
                words.size <= 4
            ) {
                return Command.DeviceQuery(DeviceQueryType.TIME)
            }
        }
        if (words.contains("date") || words.contains("day") && words.any { it in setOf("what", "whats", "today", "todays") }) {
            return Command.DeviceQuery(DeviceQueryType.DATE)
        }
        return null
    }

    private fun parseMediaCommand(words: List<String>): Command.Media? {
        // Avoid matching "play store" as a media command
        if (words.contains("store")) return null
        val filtered = words.filter { it !in setOf("music", "song", "track", "audio", "media", "the", "please") }
        if (filtered.isEmpty()) return null
        return when {
            filtered == listOf("play") || filtered == listOf("resume") -> Command.Media(MediaAction.PLAY)
            filtered == listOf("pause") || filtered == listOf("stop") -> Command.Media(MediaAction.PAUSE)
            filtered == listOf("next") || filtered == listOf("skip") -> Command.Media(MediaAction.NEXT)
            filtered == listOf("previous") || filtered == listOf("back") -> Command.Media(MediaAction.PREVIOUS)
            else -> null
        }
    }

    private fun parseSystemPanelCommand(words: List<String>, cleaned: String): Command.SystemPanel? {
        if (words.contains("wifi") || cleaned.contains("wi fi")) {
            return Command.SystemPanel(SystemPanelType.WIFI)
        }
        if (words.contains("bluetooth")) {
            return Command.SystemPanel(SystemPanelType.BLUETOOTH)
        }
        if (cleaned.contains("do not disturb") || words.contains("dnd")) {
            return Command.SystemPanel(SystemPanelType.DO_NOT_DISTURB)
        }
        return null
    }

    private fun stripFillerTokens(raw: String): String {
        return raw.split(" ")
            .filter { it.isNotBlank() && it !in STOP_WORDS }
            .joinToString(" ")
            .trim()
    }

    private fun parseTimeTokens(tokens: List<String>): Pair<Int, Int>? {
        if (tokens.isEmpty()) return null

        var isAm = false
        var isPm = false
        val timeWords = mutableListOf<String>()

        for (token in tokens) {
            when (token) {
                "am", "a.m" -> isAm = true
                "pm", "p.m" -> isPm = true
                else -> {
                    if (token.endsWith("am") && token.length > 2) {
                        isAm = true
                        timeWords.add(token.removeSuffix("am"))
                    } else if (token.endsWith("pm") && token.length > 2) {
                        isPm = true
                        timeWords.add(token.removeSuffix("pm"))
                    } else {
                        timeWords.add(token)
                    }
                }
            }
        }

        if (timeWords.isEmpty()) return null

        if (timeWords[0].contains(":")) {
            val parts = timeWords[0].split(":")
            if (parts.size == 2) {
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                return to24Hour(h, m, isAm, isPm)
            }
        }

        val numbers = extractNumbersFromTokens(timeWords)
        if (numbers.isEmpty()) return null

        val rawHour = numbers[0]
        val rawMinute = when {
            numbers.size == 1 -> 0
            numbers.size == 2 -> numbers[1]
            else -> return null
        }

        return to24Hour(rawHour, rawMinute, isAm, isPm)
    }

    private fun extractNumbersFromTokens(tokens: List<String>): List<Int> {
        val rawValues = mutableListOf<Int>()
        for (rawToken in tokens) {
            val token = rawToken.removeSuffix("%")
            val digitVal = token.toIntOrNull()
            if (digitVal != null) {
                rawValues.add(digitVal)
            } else {
                val wordVal = WORD_TO_NUMBER[token]
                if (wordVal != null) {
                    rawValues.add(wordVal)
                }
            }
        }

        val combined = mutableListOf<Int>()
        var i = 0
        while (i < rawValues.size) {
            val current = rawValues[i]
            if (current in listOf(20, 30, 40, 50, 60, 70, 80, 90) &&
                i + 1 < rawValues.size &&
                rawValues[i + 1] in 1..9
            ) {
                combined.add(current + rawValues[i + 1])
                i += 2
            } else {
                combined.add(current)
                i += 1
            }
        }
        return combined
    }

    private fun to24Hour(hour: Int, minute: Int, isAm: Boolean, isPm: Boolean): Pair<Int, Int>? {
        if (minute !in 0..59) return null
        val hour24 = when {
            isPm -> {
                if (hour !in 1..12) return null
                if (hour == 12) 12 else hour + 12
            }
            isAm -> {
                if (hour !in 1..12) return null
                if (hour == 12) 0 else hour
            }
            else -> {
                if (hour !in 0..23) return null
                hour
            }
        }
        return hour24 to minute
    }
}
