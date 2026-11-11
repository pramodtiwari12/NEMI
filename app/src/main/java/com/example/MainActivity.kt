package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.NEMIService
import com.example.ui.NEMIViewModel
import com.example.ui.PermissionHelper
import com.example.ui.Stage1DashboardScreen
import com.example.ui.theme.NEMITheme

class MainActivity : ComponentActivity() {

    private val viewModel: NEMIViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingBootOrRecoveryIntent(intent)

        setContent {
            NEMITheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                val singlePermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ ->
                    viewModel.refreshState()
                }

                val multiPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    viewModel.refreshState()
                }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshState()
                }

                LaunchedEffect(Unit) {
                    viewModel.refreshState()
                }

                Stage1DashboardScreen(
                    uiState = uiState,
                    onToggleMasterService = { enabled ->
                        viewModel.toggleMasterService(enabled)
                    },
                    onNavigateTo = { screen ->
                        viewModel.navigateTo(screen)
                    },
                    onNavigateBack = {
                        viewModel.navigateBack()
                    },
                    onSelectWakePhrase = { phrase ->
                        viewModel.selectWakePhrase(phrase)
                    },
                    onFixPermission = { requirement ->
                        when {
                            requirement.runtimePermissionString != null -> {
                                singlePermLauncher.launch(requirement.runtimePermissionString)
                            }
                            requirement.specialIntentAction != null -> {
                                runCatching {
                                    startActivity(
                                        PermissionHelper.buildSpecialPermissionIntent(
                                            this@MainActivity,
                                            requirement.specialIntentAction
                                        )
                                    )
                                }.onFailure {
                                    runCatching {
                                        startActivity(
                                            PermissionHelper.buildAppDetailsSettingsIntent(this@MainActivity)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onRequestAllRuntimePermissions = {
                        val missingRuntime = uiState.permissions
                            .mapNotNull { if (!it.isGranted) it.runtimePermissionString else null }
                            .toTypedArray()
                        if (missingRuntime.isNotEmpty()) {
                            multiPermLauncher.launch(missingRuntime)
                        }
                    },
                    onTestBeep = {
                        viewModel.testWakeBeep()
                    },
                    onStartMicTest = {
                        viewModel.startDiagnosticsMicTest()
                    },
                    onRunVoiceCommandTest = { cmd ->
                        viewModel.runVoiceCommandTest(cmd)
                    },
                    onChooseDisambiguationOption = { indexOneBased ->
                        viewModel.chooseDisambiguationOption(indexOneBased)
                    },
                    onDismissDisambiguation = {
                        viewModel.dismissDisambiguation()
                    },
                    onRunWatchdogNow = {
                        viewModel.triggerWatchdogCheckNow()
                    },
                    onOpenAppSettings = {
                        runCatching {
                            startActivity(PermissionHelper.buildAppDetailsSettingsIntent(this@MainActivity))
                        }
                    },
                    onClearLogs = {
                        viewModel.clearLogs()
                    },
                    onDismissBanner = {
                        viewModel.dismissBanner()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingBootOrRecoveryIntent(intent)
    }

    private fun handleIncomingBootOrRecoveryIntent(intent: Intent?) {
        if (intent?.action == NEMIService.ACTION_START_SERVICE) {
            NEMIService.startServiceSafely(
                context = applicationContext,
                reason = "Tapped boot/recovery notification"
            )
            viewModel.refreshState()
        }
    }
}
