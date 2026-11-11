package com.example.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.telemetry.HeartbeatLogEntry
import com.example.telemetry.HeartbeatLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Section 6 Performance Requirement:
 * Never query Contacts or CallLog when a voice command is spoken.
 *
 * Instead, [ContactCallCache] loads contacts and call history in the background when
 * the service starts, precomputes [NumberChoiceResult] for every contact, and refreshes
 * automatically via [ContentObserver] when Contacts or CallLog change.
 */
object ContactCallCache {

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _cachedContacts = MutableStateFlow<List<CachedContact>>(emptyList())
    val cachedContacts: StateFlow<List<CachedContact>> = _cachedContacts.asStateFlow()

    private val _contactGrammarPhrases = MutableStateFlow<List<String>>(emptyList())
    val contactGrammarPhrases: StateFlow<List<String>> = _contactGrammarPhrases.asStateFlow()

    private var contactsObserver: ContentObserver? = null
    private var callLogObserver: ContentObserver? = null
    private var onGrammarUpdatedCallback: ((List<String>) -> Unit)? = null

    /**
     * Starts observing Contacts and CallLog and triggers an initial background cache build.
     */
    fun startObserving(
        context: Context,
        onGrammarChanged: (List<String>) -> Unit
    ) {
        val appContext = context.applicationContext
        onGrammarUpdatedCallback = onGrammarChanged
        registerObserversIfPermitted(appContext)
        refreshCacheAsync(appContext, reason = "Service start")
    }

    /**
     * Unregisters ContentObservers when the service stops.
     */
    fun stopObserving(context: Context) {
        val resolver = context.applicationContext.contentResolver
        contactsObserver?.let { runCatching { resolver.unregisterContentObserver(it) } }
        callLogObserver?.let { runCatching { resolver.unregisterContentObserver(it) } }
        contactsObserver = null
        callLogObserver = null
        onGrammarUpdatedCallback = null
    }

    /**
     * Rebuilds the contact + call-history score cache on a background IO thread.
     */
    fun refreshCacheAsync(context: Context, reason: String = "Manual refresh") {
        val appContext = context.applicationContext
        cacheScope.launch {
            val hasContactsPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasContactsPerm) {
                _cachedContacts.value = emptyList()
                _contactGrammarPhrases.value = emptyList()
                onGrammarUpdatedCallback?.invoke(emptyList())
                return@launch
            }

            val hasCallLogPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED

            val callHistory = if (hasCallLogPerm) {
                readCallHistoryFromDevice(appContext)
            } else {
                emptyList()
            }

            val rawContacts = readContactsFromDevice(appContext, callHistory)
            _cachedContacts.value = rawContacts

            // Build lowercase contact name phrases & individual words for Vosk grammar
            val grammarPhrases = rawContacts
                .flatMap { contact ->
                    val cleaned = contact.displayName
                        .lowercase()
                        .replace(Regex("[^a-z0-9\\s]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (cleaned.isEmpty()) emptyList()
                    else listOf(cleaned) + cleaned.split(" ").filter { it.length >= 2 }
                }
                .distinct()

            _contactGrammarPhrases.value = grammarPhrases
            onGrammarUpdatedCallback?.invoke(grammarPhrases)

            HeartbeatLogger.log(
                appContext,
                HeartbeatLogEntry.EventType.HEARTBEAT,
                "ContactCallCache refreshed ($reason): ${rawContacts.size} contacts, ${callHistory.size} call log entries"
            )
        }
    }

    private fun registerObserversIfPermitted(context: Context) {
        val resolver = context.contentResolver
        val mainHandler = Handler(Looper.getMainLooper())

        if (contactsObserver == null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val obs = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    refreshCacheAsync(context, reason = "Contacts ContentObserver")
                }
            }
            runCatching {
                resolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI,
                    true,
                    obs
                )
                contactsObserver = obs
            }
        }

        if (callLogObserver == null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val obs = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    refreshCacheAsync(context, reason = "CallLog ContentObserver")
                }
            }
            runCatching {
                resolver.registerContentObserver(
                    CallLog.Calls.CONTENT_URI,
                    true,
                    obs
                )
                callLogObserver = obs
            }
        }
    }

    private fun readContactsFromDevice(
        context: Context,
        callHistory: List<CallHistoryEntry>
    ): List<CachedContact> {
        val groupedNumbers = mutableMapOf<Long, MutableList<ContactPhoneEntry>>()
        val contactNames = mutableMapOf<Long, String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val superPrimaryIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)
                val primaryIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)
                val typeIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idIdx)
                    val displayName = cursor.getString(nameIdx)?.trim().orEmpty()
                    val rawNumber = cursor.getString(numIdx)?.trim().orEmpty()
                    if (displayName.isEmpty() || rawNumber.isEmpty()) continue

                    val isSuperPrimary = cursor.getInt(superPrimaryIdx) > 0
                    val isPrimary = cursor.getInt(primaryIdx) > 0 || isSuperPrimary
                    val phoneType = cursor.getInt(typeIdx)
                    val isMobile = phoneType == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE

                    contactNames[contactId] = displayName
                    val list = groupedNumbers.getOrPut(contactId) { mutableListOf() }
                    list.add(
                        ContactPhoneEntry(
                            rawNumber = rawNumber,
                            normalizedNumber = PhoneNumberNormalizer.normalizePhoneNumber(rawNumber),
                            isPrimary = isPrimary,
                            isMobile = isMobile
                        )
                    )
                }
            }
        }

        return contactNames.map { (contactId, name) ->
            val numbers = groupedNumbers[contactId].orEmpty()
            val choice = SmartNumberSelector.chooseNumberForContact(numbers, callHistory)
            CachedContact(
                contactId = contactId,
                displayName = name,
                numbers = numbers,
                precomputedChoice = choice
            )
        }
    }

    private fun readCallHistoryFromDevice(context: Context): List<CallHistoryEntry> {
        val entries = mutableListOf<CallHistoryEntry>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                var count = 0
                while (cursor.moveToNext() && count < 1000) {
                    val number = cursor.getString(numIdx)?.trim().orEmpty()
                    if (number.isNotEmpty()) {
                        entries.add(
                            CallHistoryEntry(
                                rawNumber = number,
                                type = cursor.getInt(typeIdx),
                                dateEpochMs = cursor.getLong(dateIdx),
                                durationSeconds = cursor.getLong(durIdx)
                            )
                        )
                    }
                    count++
                }
            }
        }
        return entries
    }
}
