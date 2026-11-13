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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.VpnConnectionState
import com.example.ui.VpnViewModel
import com.example.ui.screens.AppsScreen
import com.example.ui.screens.MainScreen
import com.example.ui.screens.SourcesScreen
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.MyApplicationTheme

enum class AppDestination {
    DASHBOARD,
    APPS_SPLIT_TUNNEL,
    SOURCES_AND_KEYS
}

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
    var currentTab by rememberSaveable { mutableStateOf(AppDestination.DASHBOARD) }

    val telemetry by vpnViewModel.telemetryState.collectAsStateWithLifecycle()
    val nodes by vpnViewModel.nodes.collectAsStateWithLifecycle()
    val routingMode by vpnViewModel.routingMode.collectAsStateWithLifecycle()
    val warpConfig by vpnViewModel.warpConfig.collectAsStateWithLifecycle()
    val sources by vpnViewModel.subscriptionSources.collectAsStateWithLifecycle()
    val whitelistPackages by vpnViewModel.whitelistPackages.collectAsStateWithLifecycle()
    val appItems by vpnViewModel.appRoutingItems.collectAsStateWithLifecycle()
    val isSyncing by vpnViewModel.isSyncingServers.collectAsStateWithLifecycle()
    val isGeneratingWarp by vpnViewModel.isGeneratingWarp.collectAsStateWithLifecycle()
    val bannerMessage by vpnViewModel.statusBannerMessage.collectAsStateWithLifecycle()

    // Android 13+ notification permission launcher for Foreground VPN Service notification
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    // Android VpnService.prepare() consent dialog launcher
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
            val prepareIntent = try {
                VpnService.prepare(context)
            } catch (_: Exception) {
                null
            }
            if (prepareIntent != null) {
                vpnConsentLauncher.launch(prepareIntent)
            } else {
                vpnViewModel.startVpnService()
            }
        }
    }

    if (currentTab != AppDestination.DASHBOARD) {
        BackHandler {
            currentTab = AppDestination.DASHBOARD
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == AppDestination.DASHBOARD,
                    onClick = { currentTab = AppDestination.DASHBOARD },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == AppDestination.DASHBOARD) Icons.Filled.Shield else Icons.Outlined.Shield,
                            contentDescription = stringResource(R.string.nav_dashboard)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_dashboard)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        indicatorColor = ElectricCyan.copy(alpha = 0.16f)
                    ),
                    modifier = Modifier.testTag("nav_tab_dashboard")
                )

                NavigationBarItem(
                    selected = currentTab == AppDestination.APPS_SPLIT_TUNNEL,
                    onClick = { currentTab = AppDestination.APPS_SPLIT_TUNNEL },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == AppDestination.APPS_SPLIT_TUNNEL) Icons.Filled.Apps else Icons.Outlined.Apps,
                            contentDescription = stringResource(R.string.nav_apps)
                        )
                    },
                    label = { Text("${stringResource(R.string.nav_apps)} (${whitelistPackages.size})") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        indicatorColor = ElectricCyan.copy(alpha = 0.16f)
                    ),
                    modifier = Modifier.testTag("nav_tab_apps")
                )

                NavigationBarItem(
                    selected = currentTab == AppDestination.SOURCES_AND_KEYS,
                    onClick = { currentTab = AppDestination.SOURCES_AND_KEYS },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == AppDestination.SOURCES_AND_KEYS) Icons.Filled.CloudQueue else Icons.Outlined.CloudQueue,
                            contentDescription = stringResource(R.string.nav_sources)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_sources)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        indicatorColor = ElectricCyan.copy(alpha = 0.16f)
                    ),
                    modifier = Modifier.testTag("nav_tab_sources")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = bannerMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = bannerMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vpnViewModel.dismissBanner() }) {
                                Text("ОК", color = ElectricCyan)
                            }
                        }
                    }
                }

                when (currentTab) {
                    AppDestination.DASHBOARD -> MainScreen(
                        telemetry = telemetry,
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
                        modifier = Modifier.weight(1f)
                    )

                    AppDestination.APPS_SPLIT_TUNNEL -> AppsScreen(
                        apps = appItems,
                        whitelistedPackages = whitelistPackages,
                        onToggleApp = vpnViewModel::toggleAppWhitelist,
                        onResetDefaults = vpnViewModel::resetWhitelistToDefaults,
                        modifier = Modifier.weight(1f)
                    )

                    AppDestination.SOURCES_AND_KEYS -> SourcesScreen(
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
    }
}
