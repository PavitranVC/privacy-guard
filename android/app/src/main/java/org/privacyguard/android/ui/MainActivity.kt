package org.privacyguard.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    // Explicit ViewModelProvider.Factory avoids R8 full-mode reflection crashes
    private val viewModel: PrivacyGuardViewModel by viewModels {
        PrivacyGuardViewModel.provideFactory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PrivacyGuardTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                // Intercept back navigation when in detail screen
                BackHandler(enabled = uiState.selectedPackage != null) {
                    viewModel.selectPackage(null)
                }

                if (uiState.selectedApp != null) {
                    val isComponentStateSupported = uiState.capabilities.find { it.op == "pm_component_state_shell" }?.supported != false
                    AppDetailScreen(
                        app = uiState.selectedApp!!,
                        ops = uiState.selectedAppOps,
                        hasUnsafeRollback = uiState.hasUnsafeRollback,
                        isProtected = uiState.isSelectedAppProtected,
                        isComponentDisableSupported = isComponentStateSupported,
                        onBack = { viewModel.selectPackage(null) },
                        onDisableTrackers = { pkg -> viewModel.disableTrackerComponents(pkg) },
                        onRestrictBackground = { pkg -> viewModel.restrictBackgroundNetworking(pkg) },
                        onRollback = { pkg -> viewModel.rollbackPackage(pkg) },
                        onUnlockAndRetry = { pkg, op -> viewModel.unlockAndRetryPolicyOp(pkg, op) }
                    )
                } else {
                    MainOverviewScreen(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
fun MainOverviewScreen(
    uiState: UiState,
    viewModel: PrivacyGuardViewModel
) {
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            DashboardCard(
                uiState = uiState,
                onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
                onReprobeShield = { viewModel.reprobeShield() },
                onTriggerScan = { viewModel.triggerScanAndReconcile() },
                onDismissStatusMessage = { viewModel.clearStatusMessage() },
                modifier = Modifier.padding(16.dp)
            )

            AppListScreen(
                uiState = uiState,
                onSearchQueryChange = { query -> viewModel.setSearchQuery(query) },
                onFilterChange = { filter -> viewModel.setFilter(filter) },
                onSelectPackage = { pkg -> viewModel.selectPackage(pkg) }
            )
        }
    }
}
