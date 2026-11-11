package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.service.PendingDisambiguation
import com.example.telemetry.HeartbeatLogEntry
import java.util.Locale

/**
 * Informational feature tile shown on the clean Home screen (Section D.2).
 */
private data class FeatureTileModel(
    val id: String,
    val title: String,
    val examplePhrase: String,
    val moreExamples: List<String>,
    val icon: ImageVector
)

private val HOME_FEATURE_TILES = listOf(
    FeatureTileModel(
        id = "call",
        title = "Call",
        examplePhrase = "\"Call Mom\"",
        moreExamples = listOf(
            "\"Call Mom\"",
            "\"Mom ko call karo\"",
            "\"Phone Rahul\""
        ),
        icon = Icons.Default.Call
    ),
    FeatureTileModel(
        id = "torch",
        title = "Torch",
        examplePhrase = "\"Torch on\"",
        moreExamples = listOf(
            "\"Torch on\" / \"Torch off\"",
            "\"Flashlight on\"",
            "\"Torch chalu karo\" / \"Torch band karo\""
        ),
        icon = Icons.Default.FlashlightOn
    ),
    FeatureTileModel(
        id = "alarm",
        title = "Alarm",
        examplePhrase = "\"Set alarm 7 AM\"",
        moreExamples = listOf(
            "\"Set alarm 7 AM\"",
            "\"Alarm 6 30 PM\"",
            "\"7 AM ka alarm lagao\""
        ),
        icon = Icons.Default.Alarm
    ),
    FeatureTileModel(
        id = "open_app",
        title = "Open App",
        examplePhrase = "\"Open YouTube\"",
        moreExamples = listOf(
            "\"Open YouTube\"",
            "\"WhatsApp kholo\"",
            "\"Launch Instagram\""
        ),
        icon = Icons.Default.Apps
    ),
    FeatureTileModel(
        id = "timer",
        title = "Timer",
        examplePhrase = "\"Timer 5 minutes\"",
        moreExamples = listOf(
            "\"Timer 5 minutes\"",
            "\"Set timer 10 minutes\"",
            "\"Timer 30 seconds\""
        ),
        icon = Icons.Default.Timer
    ),
    FeatureTileModel(
        id = "volume",
        title = "Volume",
        examplePhrase = "\"Volume up\"",
        moreExamples = listOf(
            "\"Volume up\" / \"Volume down\"",
            "\"Mute\" / \"Unmute\"",
            "\"Volume 50 percent\""
        ),
        icon = Icons.AutoMirrored.Filled.VolumeUp
    ),
    FeatureTileModel(
        id = "brightness",
        title = "Brightness",
        examplePhrase = "\"Brightness up\"",
        moreExamples = listOf(
            "\"Brightness up\" / \"Brightness down\"",
            "\"Brightness 50 percent\""
        ),
        icon = Icons.Default.Brightness6
    ),
    FeatureTileModel(
        id = "time_battery",
        title = "Time & Battery",
        examplePhrase = "\"What time is it\"",
        moreExamples = listOf(
            "\"What time is it\"",
            "\"What is the date\"",
            "\"Battery level\""
        ),
        icon = Icons.Default.AccessTime
    ),
    FeatureTileModel(
        id = "media",
        title = "Media",
        examplePhrase = "\"Pause\"",
        moreExamples = listOf(
            "\"Play\" / \"Pause\"",
            "\"Next\" / \"Previous\""
        ),
        icon = Icons.Default.PlayCircleOutline
    ),
    FeatureTileModel(
        id = "panels",
        title = "Wi-Fi & Bluetooth",
        examplePhrase = "\"Open Wi-Fi\"",
        moreExamples = listOf(
            "\"Open Wi-Fi\"",
            "\"Open Bluetooth\"",
            "\"Do Not Disturb\""
        ),
        icon = Icons.Default.Wifi
    )
)

@Composable
fun Stage1DashboardScreen(
    uiState: Stage1UiState,
    onToggleMasterService: (Boolean) -> Unit,
    onNavigateTo: (NEMIScreen) -> Unit,
    onNavigateBack: () -> Unit,
    onSelectWakePhrase: (String) -> Unit,
    onFixPermission: (PermissionRequirement) -> Unit,
    onRequestAllRuntimePermissions: () -> Unit,
    onTestBeep: () -> Unit,
    onStartMicTest: () -> Unit,
    onRunVoiceCommandTest: (String) -> Unit,
    onChooseDisambiguationOption: (Int) -> Unit,
    onDismissDisambiguation: () -> Unit,
    onRunWatchdogNow: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onClearLogs: () -> Unit,
    onDismissBanner: () -> Unit
) {
    // Disambiguation Dialog (Section D: only shows spoken name + last 2 digits, or top 3 apps)
    val pending = uiState.runtimeStatus.pendingDisambiguation
    if (pending != null) {
        DisambiguationChoiceDialog(
            pending = pending,
            onSelectOption = onChooseDisambiguationOption,
            onDismiss = onDismissDisambiguation
        )
    }

    when (uiState.currentScreen) {
        NEMIScreen.HOME -> {
            CleanHomeScreen(
                uiState = uiState,
                onToggleMasterService = onToggleMasterService,
                onOpenSettings = { onNavigateTo(NEMIScreen.SETTINGS) },
                onDismissBanner = onDismissBanner
            )
        }
        NEMIScreen.SETTINGS -> {
            BackHandler { onNavigateBack() }
            SettingsScreen(
                uiState = uiState,
                onBack = onNavigateBack,
                onSelectWakePhrase = onSelectWakePhrase,
                onFixPermission = onFixPermission,
                onRequestAllRuntimePermissions = onRequestAllRuntimePermissions,
                onOpenAppSettings = onOpenAppSettings,
                onOpenDiagnostics = { onNavigateTo(NEMIScreen.DIAGNOSTICS) }
            )
        }
        NEMIScreen.DIAGNOSTICS -> {
            BackHandler { onNavigateBack() }
            AdvancedDiagnosticsScreen(
                uiState = uiState,
                onBack = onNavigateBack,
                onTestBeep = onTestBeep,
                onStartMicTest = onStartMicTest,
                onRunVoiceCommandTest = onRunVoiceCommandTest,
                onRunWatchdogNow = onRunWatchdogNow,
                onClearLogs = onClearLogs
            )
        }
    }
}

// =============================================================================
// 1. CLEAN CONSUMER HOME SCREEN (Section D)
// =============================================================================

@Composable
private fun CleanHomeScreen(
    uiState: Stage1UiState,
    onToggleMasterService: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissBanner: () -> Unit
) {
    var selectedTileForInfo by remember { mutableStateOf<FeatureTileModel?>(null) }

    if (selectedTileForInfo != null) {
        val tile = selectedTileForInfo!!
        AlertDialog(
            onDismissRequest = { selectedTileForInfo = null },
            icon = {
                Icon(
                    imageVector = tile.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(tile.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "First say \"${uiState.selectedWakePhrase}\", wait for the beep, then say:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    tile.moreExamples.forEach { example ->
                        Text(
                            text = "• $example",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTileForInfo = null }) {
                    Text("Got it")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .testTag("home_feature_grid"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row: App Title + Small Settings Icon
                item(span = { GridItemSpan(2) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "NEMI",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("open_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (uiState.statusBannerMessage != null) {
                    item(span = { GridItemSpan(2) }) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = uiState.statusBannerMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissBanner) {
                                    Text("OK")
                                }
                            }
                        }
                    }
                }

                // 1) Large On/Off Switch with ONE status line ("Listening" / "Off")
                item(span = { GridItemSpan(2) }) {
                    val isListening = uiState.runtimeStatus.isRunning
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (isListening) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = RoundedCornerShape(24.dp)
                            ),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 26.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isListening) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicOff,
                                            contentDescription = null,
                                            tint = if (isListening) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(18.dp))
                                Column {
                                    Text(
                                        text = if (isListening) "Listening" else "Off",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = if (isListening) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isListening) {
                                            "Say \"${uiState.selectedWakePhrase}\""
                                        } else {
                                            "Turn on to listen offline"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Switch(
                                checked = isListening,
                                onCheckedChange = onToggleMasterService,
                                modifier = Modifier.testTag("master_service_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }

                item(span = { GridItemSpan(2) }) {
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // 2) Grid of Informational Feature Tiles
                items(HOME_FEATURE_TILES, key = { it.id }) { tile ->
                    FeatureTileCard(
                        tile = tile,
                        onClick = { selectedTileForInfo = tile }
                    )
                }

                item(span = { GridItemSpan(2) }) {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun FeatureTileCard(
    tile: FeatureTileModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(20.dp)
            )
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("feature_tile_${tile.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = tile.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column {
                Text(
                    text = tile.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tile.examplePhrase,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Small disambiguation dialog (Section D: shows ONLY spoken name + last 2 digits for numbers,
 * or top 3 app labels for apps).
 */
@Composable
private fun DisambiguationChoiceDialog(
    pending: PendingDisambiguation,
    onSelectOption: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            when (pending) {
                is PendingDisambiguation.ContactNumberChoice -> {
                    Text("Call ${pending.spokenName}")
                }
                is PendingDisambiguation.AppChoice -> {
                    Text("Open App")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (pending) {
                    is PendingDisambiguation.ContactNumberChoice -> {
                        Text(
                            text = "Choose a number (or say \"one\" or \"two\"):",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        pending.optionsLastTwoDigits.forEachIndexed { index, last2 ->
                            OutlinedButton(
                                onClick = { onSelectOption(index + 1) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("disambiguate_option_${index + 1}")
                            ) {
                                Text("${index + 1}) Number ending ..$last2")
                            }
                        }
                    }
                    is PendingDisambiguation.AppChoice -> {
                        Text(
                            text = "Which app did you mean for \"${pending.spokenQuery}\"?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        pending.topApps.forEachIndexed { index, app ->
                            OutlinedButton(
                                onClick = { onSelectOption(index + 1) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("disambiguate_option_${index + 1}")
                            ) {
                                Text("${index + 1}) ${app.label}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// =============================================================================
// 2. SETTINGS SCREEN (Permissions, Wake Phrase Selector, Battery Help, Advanced)
// =============================================================================

@Composable
private fun SettingsScreen(
    uiState: Stage1UiState,
    onBack: () -> Unit,
    onSelectWakePhrase: (String) -> Unit,
    onFixPermission: (PermissionRequirement) -> Unit,
    onRequestAllRuntimePermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .testTag("settings_screen_list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("settings_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }

                // 1) Wake Phrase Selector
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Wake Phrase",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        text = "Pick the phrase your voice triggers best",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                uiState.availableWakePhrases.forEach { phrase ->
                                    FilterChip(
                                        selected = uiState.selectedWakePhrase == phrase,
                                        onClick = { onSelectWakePhrase(phrase) },
                                        label = { Text("\"$phrase\"") },
                                        modifier = Modifier.testTag("wake_phrase_${phrase.replace(" ", "_")}")
                                    )
                                }
                            }
                        }
                    }
                }

                // 2) Permission Checklist with Fix Buttons
                item {
                    val grantedCount = uiState.permissions.count { it.isGranted }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Permissions Checklist",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        text = "$grantedCount of ${uiState.permissions.size} ready",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (uiState.permissions.any { !it.isGranted && it.runtimePermissionString != null }) {
                                    Button(
                                        onClick = onRequestAllRuntimePermissions,
                                        modifier = Modifier.testTag("grant_all_runtime_permissions_button")
                                    ) {
                                        Text("Grant All")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            uiState.permissions.forEachIndexed { idx, item ->
                                if (idx > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (item.isGranted) {
                                                Icons.Default.CheckCircle
                                            } else {
                                                Icons.Default.WarningAmber
                                            },
                                            contentDescription = null,
                                            tint = if (item.isGranted) {
                                                MaterialTheme.colorScheme.secondary
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                text = item.purpose,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    if (item.isGranted) {
                                        Text(
                                            text = "Granted",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    } else {
                                        Button(
                                            onClick = { onFixPermission(item) },
                                            modifier = Modifier.testTag("fix_${item.id}_button"),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Text("Fix")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3) Battery-Optimization Help
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Battery Optimization Help",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                TextButton(onClick = onOpenAppSettings) {
                                    Text("App Info")
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            uiState.oemGuides.forEach { guide ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = guide.brandTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = guide.steps,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4) Advanced / Diagnostics Entry
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onOpenDiagnostics)
                            .testTag("open_diagnostics_button"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Advanced & Diagnostics",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        text = "Live mic level, transcript, beep test, timing & logs",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}

// =============================================================================
// 3. ADVANCED / DIAGNOSTICS SCREEN (Hidden inside Settings -> Advanced)
// =============================================================================

@Composable
private fun AdvancedDiagnosticsScreen(
    uiState: Stage1UiState,
    onBack: () -> Unit,
    onTestBeep: () -> Unit,
    onStartMicTest: () -> Unit,
    onRunVoiceCommandTest: (String) -> Unit,
    onRunWatchdogNow: () -> Unit,
    onClearLogs: () -> Unit
) {
    val status = uiState.runtimeStatus
    val animatedLevel by animateFloatAsState(
        targetValue = status.micLevelNormalized.coerceIn(0f, 1f),
        label = "mic_level"
    )
    var testPhraseInput by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .testTag("diagnostics_screen_list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("diagnostics_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Diagnostics",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }

                // 1) Live Microphone Level Meter + Silent Mic Detection
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Live Microphone Level",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = String.format(Locale.US, "RMS: %.0f", status.micRmsValue),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            LinearProgressIndicator(
                                progress = { animatedLevel },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .testTag("mic_rms_progress_bar"),
                                color = if (status.isMicSilentWarning) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Source: ${status.activeAudioSource} • Gain: ${status.softwareGainFactor}x • 16 kHz Mono",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (status.isMicSilentWarning && status.isRunning) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Microphone is silent (another app may hold the mic, or the OS silenced it)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2) Live Transcript (Partial & Final) + Wake Word Status + Beep & Mic Test Buttons
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Live Speech Transcript",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Surface(
                                    color = if (status.wakeWordDetected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = if (status.wakeWordDetected) {
                                            "Wake Word: YES (${status.lastWakeDetectedTime})"
                                        } else {
                                            "Wake Word: NO"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (status.wakeWordDetected) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Partial: ${status.livePartialTranscript.ifBlank { "—" }}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Final: ${status.liveFinalTranscript.ifBlank { "—" }}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onTestBeep,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("diagnostics_beep_test_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Beep Test")
                                }

                                Button(
                                    onClick = onStartMicTest,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("diagnostics_mic_test_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Mic Test")
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = testPhraseInput,
                                    onValueChange = { testPhraseInput = it },
                                    placeholder = { Text("Simulate phrase (e.g. open youtube)") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("diagnostics_command_input")
                                )
                                Button(
                                    onClick = {
                                        if (testPhraseInput.isNotBlank()) {
                                            onRunVoiceCommandTest(testPhraseInput)
                                            testPhraseInput = ""
                                        }
                                    },
                                    modifier = Modifier.testTag("diagnostics_run_command_button")
                                ) {
                                    Text("Run")
                                }
                            }
                        }
                    }
                }

                // 3) Last 10 Commands with Timing (ms)
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Last 10 Commands & Timing",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            if (uiState.recentCommands.isEmpty()) {
                                Text(
                                    text = "No commands recorded yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    uiState.recentCommands.forEach { cmd ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = cmd.parsedDescription,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        text = "${cmd.latencyMs} ms",
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                if (!cmd.numberSelectionDetail.isNullOrBlank()) {
                                                    Text(
                                                        text = cmd.numberSelectionDetail,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Text(
                                                    text = "${cmd.formattedTime} • ${cmd.actionStatus} • ${if (cmd.wasScreenLocked) "Locked" else "Unlocked"}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4) Privacy-Safe Log Viewer (No contact names or phone numbers)
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Event Log (Privacy Redacted)",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Row {
                                    TextButton(onClick = onRunWatchdogNow) {
                                        Text("Watchdog")
                                    }
                                    IconButton(
                                        onClick = onClearLogs,
                                        modifier = Modifier.testTag("clear_heartbeat_log_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = "Clear Log"
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (uiState.logEntries.isEmpty()) {
                                    Text(
                                        text = "Log is empty.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        uiState.logEntries.take(30).forEach { entry ->
                                            DiagnosticLogRow(entry)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogRow(entry: HeartbeatLogEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "[${entry.eventType.name}] ${entry.formattedTime}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
