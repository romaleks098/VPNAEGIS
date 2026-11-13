package com.example

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.VpnConnectionState
import com.example.ui.VpnViewModel
import com.example.ui.components.FloatingLiquidDock
import com.example.ui.components.FluidMeshBackground
import com.example.ui.components.LiquidDockTab
import com.example.ui.components.LiquidGlassCard
import com.example.ui.screens.AppsScreen
import com.example.ui.screens.MainScreen
import com.example.ui.screens.SourcesScreen
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.components.HazeState
import com.example.ui.components.hazeSource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AegisBoxVpnApp()
            }
        }
    }
}

@Composable
fun AegisBoxVpnApp(
    vpnViewModel: VpnViewModel = viewModel()
) {
    val context = LocalContext.current
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val hazeState = remember { HazeState() }

    val telemetry by vpnViewModel.telemetryState.collectAsStateWithLifecycle()
    val coreLogs by vpnViewModel.coreLogs.collectAsStateWithLifecycle()
    val nodes by vpnViewModel.nodes.collectAsStateWithLifecycle()
    val routingMode by vpnViewModel.routingMode.collectAsStateWithLifecycle()
    val warpConfig by vpnViewModel.warpConfig.collectAsStateWithLifecycle()
    val sources by vpnViewModel.subscriptionSources.collectAsStateWithLifecycle()
    val whitelistPackages by vpnViewModel.whitelistPackages.collectAsStateWithLifecycle()
    val includeGmsDependencies by vpnViewModel.includeGmsDependencies.collectAsStateWithLifecycle()
    val appItems by vpnViewModel.appRoutingItems.collectAsStateWithLifecycle()
    val isSyncing by vpnViewModel.isSyncingServers.collectAsStateWithLifecycle()
    val isGeneratingWarp by vpnViewModel.isGeneratingWarp.collectAsStateWithLifecycle()
    val bannerMessage by vpnViewModel.statusBannerMessage.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vpnViewModel.startVpnService()
        }
    }

    val handleToggleVpn = {
        val currentState = telemetry.connectionState
        if (currentState == VpnConnectionState.CONNECTED || currentState == VpnConnectionState.CONNECTING) {
            vpnViewModel.stopVpnService()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val prepareIntent = runCatching { VpnService.prepare(context) }.getOrNull()
            if (prepareIntent != null) {
                vpnConsentLauncher.launch(prepareIntent)
            } else {
                vpnViewModel.startVpnService()
            }
        }
    }

    if (selectedTabIndex != 0) {
        BackHandler {
            selectedTabIndex = 0
        }
    }

    val tabs = listOf(
        LiquidDockTab(
            title = stringResource(R.string.nav_dashboard),
            icon = Icons.Filled.Shield,
            testTag = "nav_tab_dashboard"
        ),
        LiquidDockTab(
            title = stringResource(R.string.nav_apps),
            icon = Icons.Filled.Apps,
            badgeCount = whitelistPackages.size,
            testTag = "nav_tab_apps"
        ),
        LiquidDockTab(
            title = stringResource(R.string.nav_sources),
            icon = Icons.Filled.CloudQueue,
            testTag = "nav_tab_sources"
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Live Dynamic Fluid Mesh Backdrop + Haze Source Layer
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            FluidMeshBackground(
                isVpnConnected = telemetry.connectionState == VpnConnectionState.CONNECTED,
                modifier = Modifier.hazeSource(state = hazeState)
            )

            // 2. Main Content Container (status bar safe padding + generous bottom padding in LazyColumns)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                AnimatedVisibility(
                    visible = bannerMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LiquidGlassCard(
                        hazeState = hazeState,
                        accentGlowColor = ElectricCyan,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = bannerMessage.orEmpty(),
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vpnViewModel.dismissBanner() }) {
                                Text("ОК", color = ElectricCyan)
                            }
                        }
                    }
                }

                when (selectedTabIndex) {
                    0 -> MainScreen(
                        hazeState = hazeState,
                        telemetry = telemetry,
                        coreLogs = coreLogs,
                        nodes = nodes,
                        warpConfig = warpConfig,
                        routingMode = routingMode,
                        isSyncing = isSyncing,
                        whitelistedCount = whitelistPackages.size,
                        onToggleVpn = handleToggleVpn,
                        onSelectMode = vpnViewModel::selectRoutingMode,
                        onSelectNode = vpnViewModel::selectNode,
                        onSwitchNextNode = vpnViewModel::switchToNextNode,
                        onForceRefreshNodes = vpnViewModel::forceSyncAndTestFreeServers,
                        onOpenGeminiSettings = vpnViewModel::openGoogleOrGeminiAppSettings,
                        onClearLogs = vpnViewModel::clearCoreLogs,
                        modifier = Modifier.weight(1f)
                    )

                    1 -> AppsScreen(
                        hazeState = hazeState,
                        apps = appItems,
                        whitelistedPackages = whitelistPackages,
                        includeGmsDependencies = includeGmsDependencies,
                        onToggleGmsDependencies = vpnViewModel::toggleIncludeGmsDependencies,
                        onToggleApp = vpnViewModel::toggleAppWhitelist,
                        onResetDefaults = vpnViewModel::resetWhitelistToDefaults,
                        modifier = Modifier.weight(1f)
                    )

                    else -> SourcesScreen(
                        hazeState = hazeState,
                        sources = sources,
                        warpConfig = warpConfig,
                        isGeneratingWarp = isGeneratingWarp,
                        onAddSource = vpnViewModel::addSubscriptionSource,
                        onRemoveSource = vpnViewModel::removeSubscriptionSource,
                        onRegenerateWarp = vpnViewModel::regenerateWarpCredentials,
                        onImportKey = vpnViewModel::importClipboardKey,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. Floating Liquid Glass Pill Dock
        FloatingLiquidDock(
            hazeState = hazeState,
            tabs = tabs,
            selectedIndex = selectedTabIndex,
            onSelectTab = { selectedTabIndex = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
