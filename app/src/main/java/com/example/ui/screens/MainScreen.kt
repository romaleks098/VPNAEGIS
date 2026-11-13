package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ProxyNode
import com.example.model.RoutingMode
import com.example.model.VpnConnectionState
import com.example.model.VpnTelemetryState
import com.example.model.WarpConfig
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.WarpViolet

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    telemetry: VpnTelemetryState,
    nodes: List<ProxyNode>,
    warpConfig: WarpConfig,
    routingMode: RoutingMode,
    isSyncing: Boolean,
    whitelistedCount: Int,
    onToggleVpn: () -> Unit,
    onSelectMode: (RoutingMode) -> Unit,
    onSelectNode: (ProxyNode) -> Unit,
    onSwitchNextNode: () -> Unit,
    onForceRefreshNodes: () -> Unit,
    onOpenGeminiSettings: (Context) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showConfigDialog by remember { mutableStateOf(false) }

    val isConnected = telemetry.connectionState == VpnConnectionState.CONNECTED
    val isConnecting = telemetry.connectionState == VpnConnectionState.CONNECTING
    val activeNode = telemetry.activeNode ?: nodes.firstOrNull()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("main_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Top Architecture & Anti-DPI Status Strip
        item {
            TopSecurityHeader(
                isConnected = isConnected,
                whitelistedCount = whitelistedCount,
                onInspectConfig = { showConfigDialog = true }
            )
        }

        // 2. Hero VPN Connect / Disconnect Power Button
        item {
            VpnPowerOrbSection(
                connectionState = telemetry.connectionState,
                routingMode = routingMode,
                activeNode = activeNode,
                onToggleVpn = onToggleVpn
            )
        }

        // 3. Telemetry & Active Server Status Card
        item {
            ActiveStatusCard(
                telemetry = telemetry,
                activeNode = activeNode,
                warpConfig = warpConfig,
                routingMode = routingMode,
                onSelectMode = onSelectMode,
                onSwitchNextNode = onSwitchNextNode
            )
        }

        // 4. Action Bar: Refresh/Test Free Servers + Google/Gemini Cache Cleaner
        item {
            QuickOperationsCard(
                isSyncing = isSyncing,
                nodeCount = nodes.size,
                onForceRefreshNodes = onForceRefreshNodes,
                onOpenGeminiSettings = { onOpenGeminiSettings(context) },
                onInspectConfig = { showConfigDialog = true }
            )
        }

        // 5. Section Header for Free Obfuscated Nodes Pool (urltest + VLESS Entry)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Пул бесплатных нод (urltest 180с)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Локации для Gemini: США, Германия, Нидерланды, Финляндия",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "${nodes.size} АКТИВНЫХ",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // 6. Nodes List
        items(nodes, key = { it.id }) { node ->
            val isSelected = activeNode?.tag == node.tag
            ProxyNodeRowCard(
                node = node,
                isSelected = isSelected,
                routingMode = routingMode,
                onClick = { onSelectNode(node) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showConfigDialog) {
        SingBoxConfigDialog(
            jsonConfig = telemetry.activeSingBoxJson,
            onDismiss = { showConfigDialog = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopSecurityHeader(
    isConnected: Boolean,
    whitelistedCount: Int,
    onInspectConfig: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isConnected) CyberEmerald.copy(alpha = 0.45f) else ElectricCyan.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "sing-box Ядро",
                        tint = if (isConnected) CyberEmerald else ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "SING-BOX CORE • 16KB PAGE",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onInspectConfig)
                        .testTag("inspect_json_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "JSON конфиг",
                            tint = ElectricCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "JSON",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TechBadge("TUN 172.19.0.1/30", ElectricCyan)
                TechBadge("IPv6 BLOCKED (ipv4_only)", CyberEmerald)
                TechBadge("FakeIP 198.18.0.0/15", WarpViolet)
                TechBadge("DoH 1.1.1.1", ElectricCyan)
                TechBadge("Whitelist: $whitelistedCount прил.", SignalAmber)
            }
        }
    }
}

@Composable
private fun TechBadge(label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(0.8.dp, accent.copy(alpha = 0.35f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun VpnPowerOrbSection(
    connectionState: VpnConnectionState,
    routingMode: RoutingMode,
    activeNode: ProxyNode?,
    onToggleVpn: () -> Unit
) {
    val isConnected = connectionState == VpnConnectionState.CONNECTED
    val isConnecting = connectionState == VpnConnectionState.CONNECTING

    val infiniteTransition = rememberInfiniteTransition(label = "vpn_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val primaryAccent by animateColorAsState(
        targetValue = when (connectionState) {
            VpnConnectionState.CONNECTED -> CyberEmerald
            VpnConnectionState.CONNECTING -> SignalAmber
            VpnConnectionState.ERROR -> MaterialTheme.colorScheme.error
            else -> ElectricCyan
        },
        label = "orb_color"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(188.dp)
        ) {
            // Outer animated radar rings
            Canvas(modifier = Modifier.fillMaxSize()) {
                val baseRadius = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryAccent.copy(alpha = if (isConnected) 0.28f else 0.14f),
                            Color.Transparent
                        ),
                        radius = baseRadius * pulseScale
                    ),
                    radius = baseRadius * pulseScale
                )
                drawCircle(
                    color = primaryAccent.copy(alpha = 0.35f),
                    radius = baseRadius * 0.84f,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Main Tappable Power Orb
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(3.dp, primaryAccent),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .size(142.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleVpn)
                    .testTag("vpn_connect_button")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    primaryAccent.copy(alpha = 0.20f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            color = primaryAccent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = if (isConnected) "Отключить VPN" else "Подключить VPN",
                            tint = primaryAccent,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (connectionState) {
                            VpnConnectionState.CONNECTED -> "ЗАЩИЩЕНО"
                            VpnConnectionState.CONNECTING -> "ЗАПУСК…"
                            VpnConnectionState.ERROR -> "ОШИБКА"
                            else -> "ПОДКЛЮЧИТЬ"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = primaryAccent
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = if (isConnected) {
                when (routingMode) {
                    RoutingMode.VLESS_WARP_CHAIN ->
                        "Активен каскад: ${activeNode?.flagEmoji ?: "🇺🇸"} VLESS Reality → Cloudflare WARP"
                    RoutingMode.AUTO_BEST_FREE ->
                        "Балансировщик urltest: ${activeNode?.flagEmoji ?: "🇩🇪"} ${activeNode?.name ?: "Best Free"}"
                }
            } else {
                "Готов к обходу ТСПУ • Gemini + YouTube 4K + Telegram"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (isConnected) CyberEmerald else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActiveStatusCard(
    telemetry: VpnTelemetryState,
    activeNode: ProxyNode?,
    warpConfig: WarpConfig,
    routingMode: RoutingMode,
    onSelectMode: (RoutingMode) -> Unit,
    onSwitchNextNode: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_telemetry_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Active Node & Quick Switch button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = activeNode?.flagEmoji ?: "🇺🇸",
                        fontSize = 28.sp
                    )
                    Column {
                        Text(
                            text = activeNode?.name ?: "US Ashburn • Reality Vision",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${activeNode?.countryName ?: "США"} • ${activeNode?.protocol?.displayName ?: "VLESS Reality"} • SNI: ${activeNode?.sni ?: "microsoft.com"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onSwitchNextNode,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("switch_node_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Сменить ноду",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Нода", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Ping, Download Speed, Upload Speed metrics grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricTile(
                    label = "ПИНГ (URLTEST)",
                    value = "${activeNode?.latencyMs ?: 42} мс",
                    accent = CyberEmerald,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "СКАЧИВАНИЕ ↓",
                    value = formatSpeedShort(telemetry.downloadSpeedBps),
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "ОТДАЧА ↑",
                    value = formatSpeedShort(telemetry.uploadSpeedBps),
                    accent = WarpViolet,
                    modifier = Modifier.weight(1f)
                )
            }

            // Mode Switcher: Auto Best Free vs VLESS + WARP Chain
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "РЕЖИМ МАРШРУТИЗАЦИИ SING-BOX",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RoutingMode.entries.forEach { mode ->
                        val selected = routingMode == mode
                        val accentColor = if (mode == RoutingMode.VLESS_WARP_CHAIN) WarpViolet else ElectricCyan
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) accentColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) accentColor else Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectMode(mode) }
                                .testTag("mode_${mode.id}")
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (mode == RoutingMode.VLESS_WARP_CHAIN) Icons.Default.Hub else Icons.Default.Bolt,
                                        contentDescription = mode.title,
                                        tint = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = mode.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = if (mode == RoutingMode.VLESS_WARP_CHAIN) {
                                        "Detour → WARP (${warpConfig.localAddressIpv4.substringBefore("/")})"
                                    } else {
                                        "gstatic 204 • каждые 180с"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Visual explanation of active cascade chain
                AnimatedVisibility(visible = routingMode == RoutingMode.VLESS_WARP_CHAIN) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = WarpViolet.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, WarpViolet.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                tint = WarpViolet,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Цепочка для Gemini: трафик обходит ТСПУ через ${activeNode?.tag ?: "vless-reality"} и выходит в интернет с чистого IP Cloudflare WARP (${warpConfig.endpointHost}). Банки РФ и .ru работают напрямую.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = JetBrainsMonoFontFamily,
                color = accent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun QuickOperationsCard(
    isSyncing: Boolean,
    nodeCount: Int,
    onForceRefreshNodes: () -> Unit,
    onOpenGeminiSettings: () -> Unit,
    onInspectConfig: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onForceRefreshNodes,
            enabled = !isSyncing,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = Color(0xFF00222B)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("force_refresh_servers_button")
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    color = Color(0xFF00222B),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Тестирование пинга и обфускации нод…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Обновить серверы"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Обновить и протестировать серверы ($nodeCount)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenGeminiSettings,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("clear_gemini_cache_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CleaningServices,
                    contentDescription = "Очистить кэш Google Gemini",
                    tint = SignalAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Кэш Gemini / Google",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onInspectConfig,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("open_config_dialog_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = "Конфиг sing-box",
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "sing-box JSON",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ProxyNodeRowCard(
    node: ProxyNode,
    isSelected: Boolean,
    routingMode: RoutingMode,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) ElectricCyan else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val pingColor = when {
        node.latencyMs < 60 -> CyberEmerald
        node.latencyMs < 110 -> ElectricCyan
        else -> SignalAmber
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("node_item_${node.tag}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(text = node.flagEmoji, fontSize = 24.sp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Выбрана",
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = buildString {
                            append(node.protocol.displayName)
                            append(" • ")
                            append("${node.server}:${node.serverPort}")
                            if (routingMode == RoutingMode.VLESS_WARP_CHAIN) {
                                append(" → WARP")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = pingColor.copy(alpha = 0.14f)
            ) {
                Text(
                    text = "${node.latencyMs} мс",
                    style = MaterialTheme.typography.labelLarge,
                    color = pingColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SingBoxConfigDialog(
    jsonConfig: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Конфигурация sing-box",
                    style = MaterialTheme.typography.titleLarge
                )
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("sing-box.json", jsonConfig))
                        copied = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Скопировать JSON",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (copied) "Скопировано" else "Копировать")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background( Color(0xFF05080F) )
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = jsonConfig,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = ElectricCyan
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

private fun formatSpeedShort(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576L -> String.format("%.1f МБ/с", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024L -> String.format("%d КБ/с", bytesPerSec / 1_024L)
        else -> "$bytesPerSec Б/с"
    }
}
