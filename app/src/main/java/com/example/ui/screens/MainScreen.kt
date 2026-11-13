package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ProxyNode
import com.example.model.RoutingMode
import com.example.model.VpnConnectionState
import com.example.model.VpnTelemetryState
import com.example.model.WarpConfig
import com.example.ui.components.LiquidGlassButton
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.WarpViolet
import com.example.ui.components.HazeState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    hazeState: HazeState,
    telemetry: VpnTelemetryState,
    coreLogs: List<String>,
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
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showConfigDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }

    val isConnected = telemetry.connectionState == VpnConnectionState.CONNECTED
    val activeNode = telemetry.activeNode ?: nodes.firstOrNull { it.isAlive } ?: nodes.firstOrNull()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("main_screen_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Top Security & Core Telemetry Pill
        item {
            TopSecurityGlassHeader(
                hazeState = hazeState,
                isConnected = isConnected,
                coreVersion = telemetry.coreVersion,
                whitelistedCount = whitelistedCount,
                onInspectLogs = { showLogsDialog = true }
            )
        }

        // 2. Hero Liquid Glass Orb
        item {
            HeroLiquidGlassOrb(
                connectionState = telemetry.connectionState,
                routingMode = routingMode,
                activeNode = activeNode,
                warpRegistered = warpConfig.isRegistered,
                onToggleVpn = onToggleVpn
            )
        }

        // 3. Explicit Core / Connection Error Lens
        if (!telemetry.lastError.isNullOrBlank()) {
            item {
                LiquidGlassCard(
                    hazeState = hazeState,
                    accentGlowColor = CoralError,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Ошибка подключения",
                            tint = CoralError,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Причина сбоя туннеля",
                                style = MaterialTheme.typography.titleMedium,
                                color = CoralError
                            )
                            Text(
                                text = telemetry.lastError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 4. Active Server Telemetry & Routing Mode Glass Card
        item {
            ActiveStatusGlassCard(
                hazeState = hazeState,
                telemetry = telemetry,
                activeNode = activeNode,
                warpConfig = warpConfig,
                routingMode = routingMode,
                onSelectMode = onSelectMode,
                onSwitchNextNode = onSwitchNextNode
            )
        }

        // 5. Operations Bar (no text truncation, dynamic height)
        item {
            QuickOperationsGlassBlock(
                hazeState = hazeState,
                isSyncing = isSyncing,
                nodeCount = nodes.size,
                logCount = coreLogs.size,
                onForceRefreshNodes = onForceRefreshNodes,
                onOpenGeminiSettings = { onOpenGeminiSettings(context) },
                onInspectConfig = { showConfigDialog = true },
                onInspectLogs = { showLogsDialog = true }
            )
        }

        // 6. Nodes Pool Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Пул нод (urltest 180с)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Реальный замер TCP/URLTest • без заглушек",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ElectricCyan.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.35f))
                ) {
                    val aliveCount = nodes.count { it.isAlive }
                    Text(
                        text = "$aliveCount / ${nodes.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // 7. Empty State or Live Nodes List
        if (nodes.isEmpty()) {
            item {
                LiquidGlassCard(
                    hazeState = hazeState,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Список серверов пока пуст",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Нажмите «Обновить и протестировать серверы» выше или импортируйте свою ссылку vless:// / hy2:// во вкладке «Источники».",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(nodes, key = { it.id }) { node ->
                val isSelected = activeNode?.tag == node.tag
                ProxyNodeGlassCard(
                    hazeState = hazeState,
                    node = node,
                    isSelected = isSelected,
                    routingMode = routingMode,
                    warpRegistered = warpConfig.isRegistered,
                    onClick = { onSelectNode(node) }
                )
            }
        }
    }

    if (showConfigDialog) {
        SingBoxConfigDialog(
            jsonConfig = telemetry.activeSingBoxJson,
            onDismiss = { showConfigDialog = false }
        )
    }

    if (showLogsDialog) {
        CoreLogsDialog(
            logs = coreLogs,
            onClear = onClearLogs,
            onDismiss = { showLogsDialog = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopSecurityGlassHeader(
    hazeState: HazeState,
    isConnected: Boolean,
    coreVersion: String,
    whitelistedCount: Int,
    onInspectLogs: () -> Unit
) {
    LiquidGlassCard(
        hazeState = hazeState,
        accentGlowColor = if (isConnected) CyberEmerald else ElectricCyan,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "sing-box Ядро",
                        tint = if (isConnected) CyberEmerald else ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "SING-BOX ${coreVersion.ifBlank { "LIBBOX" }}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ElectricCyan.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onInspectLogs)
                        .testTag("header_logs_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Логи ядра",
                            tint = ElectricCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Логи",
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
                GlassTechBadge("TUN 172.19.0.1/30", ElectricCyan)
                GlassTechBadge("IPv4 Only", CyberEmerald)
                GlassTechBadge("FakeIP 198.18.0.0/15", WarpViolet)
                GlassTechBadge("DoH 1.1.1.1", ElectricCyan)
                GlassTechBadge("Whitelist: $whitelistedCount", SignalAmber)
            }
        }
    }
}

@Composable
private fun GlassTechBadge(label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(0.8.dp, accent.copy(alpha = 0.34f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun HeroLiquidGlassOrb(
    connectionState: VpnConnectionState,
    routingMode: RoutingMode,
    activeNode: ProxyNode?,
    warpRegistered: Boolean,
    onToggleVpn: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val isConnected = connectionState == VpnConnectionState.CONNECTED
    val isConnecting = connectionState == VpnConnectionState.CONNECTING
    val isError = connectionState == VpnConnectionState.ERROR

    val infiniteTransition = rememberInfiniteTransition(label = "orb_effects")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_breathing"
    )
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_connecting_wave"
    )

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "orb_press_scale"
    )

    val primaryAccent by animateColorAsState(
        targetValue = when (connectionState) {
            VpnConnectionState.CONNECTED -> CyberEmerald
            VpnConnectionState.CONNECTING -> ElectricCyan
            VpnConnectionState.ERROR -> CoralError
            else -> ElectricCyan
        },
        animationSpec = tween(450),
        label = "orb_accent"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(204.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
        ) {
            // Outer glass aura & state-driven animated wave
            Canvas(modifier = Modifier.fillMaxSize()) {
                val baseRadius = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryAccent.copy(alpha = if (isConnected) 0.34f else if (isError) 0.28f else 0.16f),
                            primaryAccent.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        radius = baseRadius * breathingScale
                    ),
                    radius = baseRadius * breathingScale
                )

                // Specular glass halo ring
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.38f),
                            primaryAccent.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.06f)
                        )
                    ),
                    radius = baseRadius * 0.84f,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Running neon wave when connecting
                if (isConnecting) {
                    rotate(degrees = sweepAngle) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    ElectricCyan,
                                    WarpViolet,
                                    Color.Transparent
                                )
                            ),
                            startAngle = 0f,
                            sweepAngle = 210f,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                            topLeft = androidx.compose.ui.geometry.Offset(
                                size.width * 0.08f,
                                size.height * 0.08f
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                size.width * 0.84f,
                                size.height * 0.84f
                            )
                        )
                    }
                }
            }

            // Glass Sphere Core
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(152.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.22f),
                                primaryAccent.copy(alpha = if (isConnected) 0.24f else 0.10f),
                                Color(0xFF091021).copy(alpha = 0.85f)
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.55f),
                                primaryAccent.copy(alpha = 0.55f),
                                Color.White.copy(alpha = 0.10f)
                            )
                        ),
                        shape = CircleShape
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleVpn()
                    }
                    .testTag("vpn_connect_button")
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            color = ElectricCyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(42.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = if (isConnected) "Отключить VPN" else "Подключить VPN",
                            tint = primaryAccent,
                            modifier = Modifier.size(48.dp)
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

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = when {
                isConnected && routingMode == RoutingMode.VLESS_WARP_CHAIN && warpRegistered ->
                    "Активен каскад: ${activeNode?.flagEmoji ?: "🌐"} ${activeNode?.tag ?: "VLESS"} → Cloudflare WARP"
                isConnected ->
                    "Активен туннель: ${activeNode?.flagEmoji ?: "🌐"} ${activeNode?.name ?: "sing-box"}"
                isError ->
                    "Не удалось поднять туннель — проверьте детали ниже"
                else ->
                    "ТСПУ-защита • Gemini + YouTube 4K + Telegram"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (isConnected) CyberEmerald else if (isError) CoralError else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ActiveStatusGlassCard(
    hazeState: HazeState,
    telemetry: VpnTelemetryState,
    activeNode: ProxyNode?,
    warpConfig: WarpConfig,
    routingMode: RoutingMode,
    onSelectMode: (RoutingMode) -> Unit,
    onSwitchNextNode: () -> Unit
) {
    LiquidGlassCard(
        hazeState = hazeState,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_telemetry_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Active Node & Quick Switch
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
                        text = activeNode?.flagEmoji ?: "🛰️",
                        fontSize = 28.sp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = activeNode?.name ?: "Сервер не выбран",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (activeNode != null) {
                                "${activeNode.countryName} • ${activeNode.protocol.displayName}"
                            } else {
                                "Загрузите подписку или импортируйте ключ"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                LiquidGlassButton(
                    hazeState = hazeState,
                    onClick = onSwitchNextNode,
                    accentColor = ElectricCyan,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("switch_node_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Сменить ноду",
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Нода",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan
                        )
                    }
                }
            }

            // Real Ping & Live Speed Metrics Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val pingValue = when {
                    activeNode == null -> "—"
                    activeNode.latencyMs > 0 -> "${activeNode.latencyMs} мс"
                    else -> "Недоступен"
                }
                val pingAccent = when {
                    activeNode == null || activeNode.latencyMs <= 0 -> CoralError
                    activeNode.latencyMs < 90 -> CyberEmerald
                    else -> SignalAmber
                }

                GlassMetricTile(
                    label = "ПИНГ",
                    value = pingValue,
                    accent = pingAccent,
                    modifier = Modifier.weight(1f)
                )
                GlassMetricTile(
                    label = "СКАЧИВАНИЕ ↓",
                    value = formatSpeedShort(telemetry.downloadSpeedBps),
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                GlassMetricTile(
                    label = "ОТДАЧА ↑",
                    value = formatSpeedShort(telemetry.uploadSpeedBps),
                    accent = WarpViolet,
                    modifier = Modifier.weight(1f)
                )
            }

            // Mode Switcher (2 clean columns with wrapping, zero ellipsis truncation!)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "РЕЖИМ МАРШРУТИЗАЦИИ SING-BOX",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RoutingMode.entries.forEach { mode ->
                        val selected = routingMode == mode
                        val accentColor = if (mode == RoutingMode.VLESS_WARP_CHAIN) WarpViolet else ElectricCyan
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) accentColor.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) accentColor else Color.White.copy(alpha = 0.14f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 68.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelectMode(mode) }
                                .testTag("mode_${mode.id}")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = if (mode == RoutingMode.VLESS_WARP_CHAIN) {
                                        if (warpConfig.isRegistered) {
                                            "WARP ${warpConfig.localAddressIpv4.substringBefore("/")}"
                                        } else {
                                            "Нужна регистрация WARP"
                                        }
                                    } else {
                                        "urltest 180с • 204"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = routingMode == RoutingMode.VLESS_WARP_CHAIN) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = WarpViolet.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, WarpViolet.copy(alpha = 0.35f)),
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
                                text = if (warpConfig.isRegistered) {
                                    "Цепочка для Gemini: трафик идёт через ${activeNode?.tag ?: "прокси-ноду"} и выходит через endpoint Cloudflare WARP (${warpConfig.endpointHost})."
                                } else {
                                    "Профиль WARP ещё не зарегистрирован — при запуске будет выполнена автоматическая регистрация или откройте вкладку «Источники»."
                                },
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
private fun GlassMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickOperationsGlassBlock(
    hazeState: HazeState,
    isSyncing: Boolean,
    nodeCount: Int,
    logCount: Int,
    onForceRefreshNodes: () -> Unit,
    onOpenGeminiSettings: () -> Unit,
    onInspectConfig: () -> Unit,
    onInspectLogs: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Primary Refresh & Test CTA — dynamic height, full non-clipped Russian text!
        LiquidGlassButton(
            hazeState = hazeState,
            onClick = onForceRefreshNodes,
            enabled = !isSyncing,
            accentColor = ElectricCyan,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 54.dp)
                .testTag("force_refresh_servers_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        color = ElectricCyan,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Загрузка подписок и замер задержки…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить и протестировать серверы",
                        tint = ElectricCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Обновить и протестировать серверы ($nodeCount)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Balanced 3-tile row with concise, unclipped labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LiquidGlassButton(
                hazeState = hazeState,
                onClick = onOpenGeminiSettings,
                accentColor = SignalAmber,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 60.dp)
                    .testTag("clear_gemini_cache_button")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsApplications,
                        contentDescription = "Настройки Gemini",
                        tint = SignalAmber,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Настройки Gemini",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }

            LiquidGlassButton(
                hazeState = hazeState,
                onClick = onInspectConfig,
                accentColor = ElectricCyan,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 60.dp)
                    .testTag("open_config_dialog_button")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = "sing-box JSON",
                        tint = ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Конфиг JSON",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }

            LiquidGlassButton(
                hazeState = hazeState,
                onClick = onInspectLogs,
                accentColor = WarpViolet,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 60.dp)
                    .testTag("open_logs_dialog_button")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Логи ядра",
                        tint = WarpViolet,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Логи ($logCount)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyNodeGlassCard(
    hazeState: HazeState,
    node: ProxyNode,
    isSelected: Boolean,
    routingMode: RoutingMode,
    warpRegistered: Boolean,
    onClick: () -> Unit
) {
    val pingColor = when {
        !node.isAlive || node.latencyMs <= 0 -> CoralError
        node.latencyMs < 90 -> CyberEmerald
        node.latencyMs < 180 -> ElectricCyan
        else -> SignalAmber
    }

    val pingText = if (node.isAlive && node.latencyMs > 0) "${node.latencyMs} мс" else "Недоступен"

    LiquidGlassCard(
        hazeState = hazeState,
        accentGlowColor = if (isSelected) ElectricCyan else null,
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
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
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
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
                            if (routingMode == RoutingMode.VLESS_WARP_CHAIN && warpRegistered) {
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

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = pingColor.copy(alpha = 0.16f),
                border = BorderStroke(0.8.dp, pingColor.copy(alpha = 0.35f))
            ) {
                Text(
                    text = pingText,
                    style = MaterialTheme.typography.labelLarge,
                    color = pingColor,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
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
                    text = "sing-box JSON",
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF05080F))
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
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

@Composable
private fun CoreLogsDialog(
    logs: List<String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val joined = remember(logs) {
        if (logs.isEmpty()) "Логов ядра пока нет. Запустите туннель или проверьте конфиг." else logs.joinToString("\n")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Живые логи libbox",
                    style = MaterialTheme.typography.titleLarge
                )
                Row {
                    TextButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Очистить",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("libbox-logs.txt", joined))
                            copied = true
                        }
                    ) {
                        Text(if (copied) "Скоп." else "Копия")
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF05080F))
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = joined,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = CyberEmerald
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
