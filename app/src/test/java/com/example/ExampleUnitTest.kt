package com.example

import com.example.apps.AppMatchOutcome
import com.example.apps.AppMatcher
import com.example.apps.InstalledAppEntry
import com.example.asr.VoskGrammarBuilder
import com.example.contacts.CachedContact
import com.example.contacts.CallHistoryEntry
import com.example.contacts.ContactMatchOutcome
import com.example.contacts.ContactMatcher
import com.example.contacts.ContactPhoneEntry
import com.example.contacts.NumberChoiceResult
import com.example.contacts.PhoneNumberNormalizer
import com.example.contacts.SmartNumberSelector
import com.example.parser.BrightnessAction
import com.example.parser.Command
import com.example.parser.CommandParser
import com.example.parser.DeviceQueryType
import com.example.parser.MediaAction
import com.example.parser.SystemPanelType
import com.example.parser.VolumeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEMI Unit Test Suite (46 test cases):
 * - 32 App Opening & Matcher tests (exact, partial, split words, Hinglish, aliases,
 *   misrecognized text, phonetic/Levenshtein, similar apps ambiguity, nonsense rejection)
 * - Tolerant CommandParser tests (filler words, Hinglish verbs, timer, volume, brightness,
 *   time/date/battery, media, system panels, wake phrase fuzzy matching)
 * - Smart Call Number Selection & Phone Number Normalization tests
 */
class ExampleUnitTest {

    private fun makeApp(
        label: String,
        pkg: String,
        launchCount: Int = 0
    ): InstalledAppEntry {
        return InstalledAppEntry(
            label = label,
            packageName = pkg,
            launchCount = launchCount
        )
    }

    private val sampleApps = listOf(
        makeApp("WhatsApp", "com.whatsapp"),
        makeApp("YouTube", "com.google.android.youtube"),
        makeApp("Instagram", "com.instagram.android"),
        makeApp("PhonePe", "com.phonepe.app"),
        makeApp("Google Pay", "com.google.android.apps.nbu.paisa.user"),
        makeApp("Chrome", "com.android.chrome"),
        makeApp("Gmail", "com.google.android.gm"),
        makeApp("Maps", "com.google.android.apps.maps"),
        makeApp("Calculator", "com.google.android.calculator"),
        makeApp("Calendar", "com.google.android.calendar"),
        makeApp("Camera", "com.android.camera2"),
        makeApp("Clock", "com.google.android.deskclock"),
        makeApp("Settings", "com.android.settings"),
        makeApp("Spotify", "com.spotify.music"),
        makeApp("Telegram", "org.telegram.messenger"),
        makeApp("Files", "com.google.android.apps.nbu.files"),
        makeApp("Gallery", "com.simplemobiletools.gallery"),
        makeApp("Contacts", "com.google.android.contacts"),
        makeApp("Paytm", "net.one97.paytm")
    )

    private val defaultAliases = AppMatcher.DEFAULT_BUNDLED_ALIASES + mapOf(
        "spot if i" to "Spotify",
        "tele gram" to "Telegram"
    )

    // =========================================================================
    // 1. APP OPENING & APP MATCHER TESTS (32 Cases)
    // =========================================================================

    @Test
    fun appOpen01_exactWhatsApp() {
        val cmd = CommandParser.parseCommand("open WhatsApp") as Command.OpenApp
        assertEquals("whatsapp", cmd.appQuery)
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.whatsapp", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen02_exactYouTube() {
        val cmd = CommandParser.parseCommand("open YouTube") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.google.android.youtube", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen03_launchCameraVerb() {
        val cmd = CommandParser.parseCommand("launch camera") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.android.camera2", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen04_openCalculatorWithFillerWords() {
        val cmd = CommandParser.parseCommand("please can you open the calculator") as Command.OpenApp
        assertEquals("calculator", cmd.appQuery)
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.google.android.calculator", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen05_partialInstaMatchesInstagram() {
        val cmd = CommandParser.parseCommand("open insta") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.instagram.android", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen06_partialCalcMatchesCalculator() {
        val res = AppMatcher.matchApp("calc", sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.google.android.calculator", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen07_partialTeleMatchesTelegram() {
        val res = AppMatcher.matchApp("tele", sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("org.telegram.messenger", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen08_splitWordsYouTube() {
        val cmd = CommandParser.parseCommand("open you tube") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.google.android.youtube", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen09_splitWordsInstaGram() {
        val cmd = CommandParser.parseCommand("open insta gram") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.instagram.android", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen10_splitWordsTeleGram() {
        val cmd = CommandParser.parseCommand("open tele gram") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("org.telegram.messenger", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen11_splitWordsPayTm() {
        val cmd = CommandParser.parseCommand("open pay tm") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("net.one97.paytm", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen12_hinglishWhatsappKholo() {
        val cmd = CommandParser.parseCommand("whatsapp kholo")
        assertTrue(cmd is Command.OpenApp)
        val res = AppMatcher.matchApp((cmd as Command.OpenApp).appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.whatsapp", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen13_hinglishYoutubeKholDo() {
        val cmd = CommandParser.parseCommand("you tube khol do")
        assertTrue(cmd is Command.OpenApp)
        val res = AppMatcher.matchApp((cmd as Command.OpenApp).appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.google.android.youtube", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen14_hinglishPrefixKholoInstagram() {
        val cmd = CommandParser.parseCommand("kholo instagram")
        assertTrue(cmd is Command.OpenApp)
        val res = AppMatcher.matchApp((cmd as Command.OpenApp).appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.instagram.android", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen15_hinglishPrefixKholDoCamera() {
        val cmd = CommandParser.parseCommand("khol do camera")
        assertTrue(cmd is Command.OpenApp)
        val res = AppMatcher.matchApp((cmd as Command.OpenApp).appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.android.camera2", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen16_misrecognizedWhatsUpToWhatsApp() {
        val cmd = CommandParser.parseCommand("open what's up") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.whatsapp", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen17_misrecognizedPhonePayToPhonePe() {
        val cmd = CommandParser.parseCommand("open phone pay") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.phonepe.app", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen18_misrecognizedGPayToGooglePay() {
        val cmd = CommandParser.parseCommand("open g pay") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.google.android.apps.nbu.paisa.user", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen19_misrecognizedSpotIfIToSpotify() {
        val cmd = CommandParser.parseCommand("open spot if i") as Command.OpenApp
        val res = AppMatcher.matchApp(cmd.appQuery, sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.spotify.music", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen20_phoneticWatsapWithoutAlias() {
        val res = AppMatcher.matchApp("watsap", sampleApps, emptyMap())
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.whatsapp", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen21_phoneticCalenderTypo() {
        val res = AppMatcher.matchApp("calender", sampleApps, emptyMap())
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.google.android.calendar", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen22_phoneticGaleryTypo() {
        val res = AppMatcher.matchApp("galery", sampleApps, emptyMap())
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.simplemobiletools.gallery", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen23_phoneticChroamTypo() {
        val res = AppMatcher.matchApp("chroam", sampleApps, emptyMap())
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.android.chrome", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen24_levenshteinInstagrmMissingVowel() {
        val res = AppMatcher.matchApp("instagrm", sampleApps, emptyMap())
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.instagram.android", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen25_levenshteinCalculaterTypo() {
        val res = AppMatcher.matchApp("calculater", sampleApps, emptyMap())
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.google.android.calculator", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen26_learnedUserAliasOverridesAndMatches() {
        val learned = defaultAliases + ("my bank app" to "PhonePe")
        val res = AppMatcher.matchApp("my bank app", sampleApps, learned)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.phonepe.app", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen27_twoSimilarAppsReturnsAmbiguousTopCandidates() {
        val similarCatalog = listOf(
            makeApp("Photo Editor Pro", "com.example.photo1"),
            makeApp("Photo Editor Max", "com.example.photo2"),
            makeApp("Calculator", "com.google.android.calculator")
        )
        val res = AppMatcher.matchApp("photo editor", similarCatalog, emptyMap())
        assertTrue(res is AppMatchOutcome.Ambiguous)
        val top = (res as AppMatchOutcome.Ambiguous).topCandidates
        assertEquals(2, top.size)
    }

    @Test
    fun appOpen28_frequentUseBonusBreaksExactLabelTie() {
        val duplicateLabelCatalog = listOf(
            makeApp("Gallery", "com.vendor.gallery1", launchCount = 0),
            makeApp("Gallery", "com.vendor.gallery2", launchCount = 8)
        )
        val res = AppMatcher.matchApp("gallery", duplicateLabelCatalog, emptyMap())
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.vendor.gallery2", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    @Test
    fun appOpen29_nonsenseWordDoesNotOpenRandomApp() {
        val res = AppMatcher.matchApp("xyzzyplugh", sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.NoConfidentMatch)
    }

    @Test
    fun appOpen30_unrelatedGibberishSentenceRejected() {
        val res = AppMatcher.matchApp("purple elephant dancing quantum", sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.NoConfidentMatch)
    }

    @Test
    fun appOpen31_emptyQueryRejected() {
        val res = AppMatcher.matchApp("   ", sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.NoConfidentMatch)
    }

    @Test
    fun appOpen32_multiWordGoogleMapsMatchesMaps() {
        val res = AppMatcher.matchApp("google maps", sampleApps, defaultAliases)
        assertTrue(res is AppMatchOutcome.ConfidentMatch)
        assertEquals("com.google.android.apps.maps", (res as AppMatchOutcome.ConfidentMatch).app.packageName)
    }

    // =========================================================================
    // 2. TOLERANT PARSER, HINGLISH & EXTRA OFFLINE COMMANDS TESTS
    // =========================================================================

    @Test
    fun parser_stripsFillerWordsAndParsesCall() {
        val cmd = CommandParser.parseCommand("hey phone please can you call Jagdish")
        assertEquals(Command.Call("jagdish"), cmd)
    }

    @Test
    fun parser_hinglishCallKaro() {
        assertEquals(Command.Call("jagdish"), CommandParser.parseCommand("jagdish ko call karo"))
        assertEquals(Command.Call("mom"), CommandParser.parseCommand("mom phone karo"))
    }

    @Test
    fun parser_hinglishTorchChaluAndBandKaro() {
        assertEquals(Command.Torch(true), CommandParser.parseCommand("torch chalu karo"))
        assertEquals(Command.Torch(false), CommandParser.parseCommand("flashlight band karo"))
    }

    @Test
    fun parser_hinglishAlarmLagao() {
        assertEquals(Command.Alarm(7, 0), CommandParser.parseCommand("7 am ka alarm lagao"))
    }

    @Test
    fun parser_timerMinutesAndSeconds() {
        assertEquals(Command.SetTimer(300), CommandParser.parseCommand("set timer for 5 minutes"))
        assertEquals(Command.SetTimer(45), CommandParser.parseCommand("timer 45 seconds"))
    }

    @Test
    fun parser_volumeAndBrightnessControls() {
        assertEquals(Command.Volume(VolumeAction.UP), CommandParser.parseCommand("volume up"))
        assertEquals(Command.Volume(VolumeAction.DOWN), CommandParser.parseCommand("volume kam"))
        assertEquals(Command.Volume(VolumeAction.MUTE), CommandParser.parseCommand("mute"))
        assertEquals(Command.Volume(VolumeAction.SET_PERCENT, 50), CommandParser.parseCommand("volume 50 percent"))
        assertEquals(Command.Brightness(BrightnessAction.UP), CommandParser.parseCommand("brightness up"))
        assertEquals(Command.Brightness(BrightnessAction.DOWN), CommandParser.parseCommand("decrease brightness"))
    }

    @Test
    fun parser_timeDateAndBatteryQueries() {
        assertEquals(Command.DeviceQuery(DeviceQueryType.TIME), CommandParser.parseCommand("what time is it"))
        assertEquals(Command.DeviceQuery(DeviceQueryType.DATE), CommandParser.parseCommand("what is today's date"))
        assertEquals(Command.DeviceQuery(DeviceQueryType.BATTERY), CommandParser.parseCommand("battery level"))
    }

    @Test
    fun parser_mediaControls() {
        assertEquals(Command.Media(MediaAction.PLAY), CommandParser.parseCommand("play music"))
        assertEquals(Command.Media(MediaAction.PAUSE), CommandParser.parseCommand("pause"))
        assertEquals(Command.Media(MediaAction.NEXT), CommandParser.parseCommand("next song"))
        assertEquals(Command.Media(MediaAction.PREVIOUS), CommandParser.parseCommand("previous track"))
    }

    @Test
    fun parser_systemSettingsPanels() {
        assertEquals(Command.SystemPanel(SystemPanelType.WIFI), CommandParser.parseCommand("wifi settings"))
        assertEquals(Command.SystemPanel(SystemPanelType.BLUETOOTH), CommandParser.parseCommand("open bluetooth"))
        assertEquals(Command.SystemPanel(SystemPanelType.DO_NOT_DISTURB), CommandParser.parseCommand("do not disturb"))
    }

    @Test
    fun wakeWord_fuzzyMatchingHandlesAccents() {
        assertTrue(VoskGrammarBuilder.matchesWakePhrase("hey computer", "hey computer"))
        assertTrue(VoskGrammarBuilder.matchesWakePhrase("he computer", "hey computer"))
        assertTrue(VoskGrammarBuilder.matchesWakePhrase("okay phone", "ok phone"))
        assertFalse(VoskGrammarBuilder.matchesWakePhrase("hello world", "hey computer"))
    }

    // =========================================================================
    // 3. PHONE NUMBER NORMALIZATION & CALL-HISTORY SELECTION TESTS
    // =========================================================================

    @Test
    fun phoneNumberNormalizer_handlesCountryCodesFormattingAndZeros() {
        val n1 = PhoneNumberNormalizer.normalizePhoneNumber("+91 98765-43210")
        val n2 = PhoneNumberNormalizer.normalizePhoneNumber("09876543210")
        val n3 = PhoneNumberNormalizer.normalizePhoneNumber("(987) 654-3210")
        assertEquals("9876543210", n1)
        assertEquals(n1, n2)
        assertEquals(n2, n3)
        assertEquals("10", PhoneNumberNormalizer.endingDigits("+91 98765-43210", count = 2))
    }

    @Test
    fun smartNumberSelector_singleNumber_callsImmediately() {
        val entries = listOf(ContactPhoneEntry("+91 98765 43210"))
        val outcome = SmartNumberSelector.chooseNumberForContact(entries, emptyList())
        assertTrue(outcome is NumberChoiceResult.Chosen)
    }

    @Test
    fun smartNumberSelector_clearWinner_picksMostCalledNumber() {
        val numA = "+91 98765 00011"
        val numB = "+91 98765 00022"
        val entries = listOf(
            ContactPhoneEntry(rawNumber = numA, isPrimary = false, isMobile = false),
            ContactPhoneEntry(rawNumber = numB, isPrimary = false, isMobile = false)
        )
        val history = listOf(
            CallHistoryEntry(rawNumber = "09876500011", type = CallHistoryEntry.TYPE_OUTGOING, durationSeconds = 120),
            CallHistoryEntry(rawNumber = "+91-98765-00011", type = CallHistoryEntry.TYPE_INCOMING, durationSeconds = 60),
            CallHistoryEntry(rawNumber = numB, type = CallHistoryEntry.TYPE_MISSED, durationSeconds = 0)
        )

        val outcome = SmartNumberSelector.chooseNumberForContact(entries, history)
        assertTrue(outcome is NumberChoiceResult.Chosen)
        assertEquals(numA, (outcome as NumberChoiceResult.Chosen).selected.entry.rawNumber)
    }

    @Test
    fun smartNumberSelector_ambiguousWhenNoHistoryAndNoPrimaryOrMobile() {
        val entries = listOf(
            ContactPhoneEntry(rawNumber = "9876500011", isPrimary = false, isMobile = false),
            ContactPhoneEntry(rawNumber = "9876500022", isPrimary = false, isMobile = false)
        )
        val outcome = SmartNumberSelector.chooseNumberForContact(entries, emptyList())
        assertTrue(outcome is NumberChoiceResult.Ambiguous)
        val candidates = (outcome as NumberChoiceResult.Ambiguous).candidates
        assertEquals(2, candidates.size)
    }

    @Test
    fun contactMatcher_ranksExactThenStartsWithThenContains() {
        val contacts = listOf(
            CachedContact(1L, "Jagdish Sharma", listOf(ContactPhoneEntry("9876500011"))),
            CachedContact(2L, "Jagdish", listOf(ContactPhoneEntry("9876500022")))
        )
        val exact = ContactMatcher.matchContact("jagdish", contacts)
        assertTrue(exact is ContactMatchOutcome.SingleMatch)
        assertEquals("Jagdish", (exact as ContactMatchOutcome.SingleMatch).contact.displayName)
    }
}
