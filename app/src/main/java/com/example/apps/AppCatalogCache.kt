package com.example.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.example.telemetry.HeartbeatLogEntry
import com.example.telemetry.HeartbeatLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Section B.1: Caches all launchable apps (`MAIN` / `LAUNCHER`) in memory at service start
 * and refreshes automatically when packages are added, removed, or replaced.
 *
 * Also loads the editable `assets/app_aliases.txt` file and user-learned aliases
 * from `filesDir/learned_app_aliases.txt`, plus on-device app launch counts.
 */
object AppCatalogCache {

    private const val ASSET_ALIASES_FILE = "app_aliases.txt"
    private const val LEARNED_ALIASES_FILE = "learned_app_aliases.txt"
    private const val PREFS_LAUNCH_COUNTS = "NEMI_app_launch_counts"

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _installedApps = MutableStateFlow<List<InstalledAppEntry>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppEntry>> = _installedApps.asStateFlow()

    private val _aliasMap = MutableStateFlow<Map<String, String>>(AppMatcher.DEFAULT_BUNDLED_ALIASES)
    val aliasMap: StateFlow<Map<String, String>> = _aliasMap.asStateFlow()

    private var packageReceiver: BroadcastReceiver? = null

    fun startObserving(context: Context) {
        val appContext = context.applicationContext
        registerPackageReceiver(appContext)
        refreshCatalogAsync(appContext, reason = "Service start")
    }

    fun stopObserving(context: Context) {
        val appContext = context.applicationContext
        packageReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        packageReceiver = null
    }

    fun refreshCatalogAsync(context: Context, reason: String = "Manual refresh") {
        val appContext = context.applicationContext
        cacheScope.launch {
            val aliases = loadMergedAliases(appContext)
            _aliasMap.value = aliases

            val apps = queryLaunchableApps(appContext)
            _installedApps.value = apps

            HeartbeatLogger.log(
                appContext,
                HeartbeatLogEntry.EventType.HEARTBEAT,
                "AppCatalogCache refreshed ($reason): ${apps.size} launchable apps, ${aliases.size} aliases"
            )
        }
    }

    /**
     * Saves a spoken misrecognition as a new alias for the chosen app so the same
     * spoken phrase works immediately next time (Section B.4).
     */
    fun saveLearnedAlias(context: Context, spokenPhrase: String, targetAppLabel: String) {
        val normKey = PhoneticAndEditDistance.normalizeText(spokenPhrase)
        if (normKey.isEmpty() || targetAppLabel.isBlank()) return
        val appContext = context.applicationContext

        val updated = _aliasMap.value.toMutableMap()
        updated[normKey] = targetAppLabel.trim()
        _aliasMap.value = updated

        cacheScope.launch {
            runCatching {
                val file = File(appContext.filesDir, LEARNED_ALIASES_FILE)
                file.appendText("$normKey = ${targetAppLabel.trim()}\n")
            }
        }
    }

    /**
     * Increments the on-device launch counter for [packageName] to give frequently
     * opened apps a small tie-breaker bonus (Section B.3).
     */
    fun incrementLaunchCount(context: Context, packageName: String) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_LAUNCH_COUNTS, Context.MODE_PRIVATE)
        val next = prefs.getInt(packageName, 0) + 1
        prefs.edit().putInt(packageName, next).apply()

        _installedApps.value = _installedApps.value.map {
            if (it.packageName == packageName) it.copy(launchCount = next) else it
        }
    }

    private fun registerPackageReceiver(context: Context) {
        if (packageReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                val action = intent?.action ?: return
                refreshCatalogAsync(ctx, reason = "Broadcast $action")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            packageReceiver = receiver
        }
    }

    private fun queryLaunchableApps(context: Context): List<InstalledAppEntry> {
        val pm = context.packageManager
        val prefs = context.getSharedPreferences(PREFS_LAUNCH_COUNTS, Context.MODE_PRIVATE)
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
        }.getOrDefault(emptyList())

        val results = mutableListOf<InstalledAppEntry>()
        for (info in resolveInfos) {
            val pkg = info.activityInfo?.packageName ?: continue
            val label = runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault("")
            if (label.isEmpty()) continue
            val count = prefs.getInt(pkg, 0)
            results.add(
                InstalledAppEntry(
                    label = label,
                    packageName = pkg,
                    launchCount = count
                )
            )
        }
        return results.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private fun loadMergedAliases(context: Context): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(AppMatcher.DEFAULT_BUNDLED_ALIASES)

        // 1. Read bundled assets/app_aliases.txt
        runCatching {
            context.assets.open(ASSET_ALIASES_FILE).bufferedReader().useLines { lines ->
                parseAliasLines(lines, merged)
            }
        }

        // 2. Read user-learned aliases from filesDir/learned_app_aliases.txt
        runCatching {
            val file = File(context.filesDir, LEARNED_ALIASES_FILE)
            if (file.exists()) {
                file.bufferedReader().useLines { lines ->
                    parseAliasLines(lines, merged)
                }
            }
        }
        return merged
    }

    private fun parseAliasLines(lines: Sequence<String>, targetMap: MutableMap<String, String>) {
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eqIdx = line.indexOf('=')
            if (eqIdx > 0 && eqIdx < line.length - 1) {
                val spoken = PhoneticAndEditDistance.normalizeText(line.substring(0, eqIdx))
                val target = line.substring(eqIdx + 1).trim()
                if (spoken.isNotEmpty() && target.isNotEmpty()) {
                    targetMap[spoken] = target
                }
            }
        }
    }
}
