package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.SubscriptionSource
import com.example.model.WarpConfig
import com.example.ui.components.LiquidGlassButton
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.WarpViolet
import dev.chrisbanes.haze.HazeState

@Composable
fun SourcesScreen(
    hazeState: HazeState,
    sources: List<SubscriptionSource>,
    warpConfig: WarpConfig,
    isGeneratingWarp: Boolean,
    onAddSource: (String, String) -> Unit,
    onRemoveSource: (String) -> Unit,
    onRegenerateWarp: () -> Unit,
    onImportKey: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var manualKeyText by remember { mutableStateOf("") }
    var newSourceTitle by remember { mutableStateOf("") }
    var newSourceUrl by remember { mutableStateOf("") }

    val glassTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White.copy(alpha = 0.08f),
        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
        focusedBorderColor = ElectricCyan,
        unfocusedBorderColor = Color.White.copy(alpha = 0.16f)
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("sources_screen_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Real Cloudflare WARP X25519 Registration Card
        item {
            LiquidGlassCard(
                hazeState = hazeState,
                accentGlowColor = WarpViolet,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("warp_generator_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                imageVector = Icons.Default.VpnKey,
                                contentDescription = "Cloudflare WARP",
                                tint = WarpViolet
                            )
                            Column {
                                Text(
                                    text = "Cloudflare WARP (X25519)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Регистрация устройства через Cloudflare API",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = (if (warpConfig.isRegistered) CyberEmerald else SignalAmber).copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = if (warpConfig.isRegistered) "Активен" else "Не создан",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (warpConfig.isRegistered) CyberEmerald else SignalAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.05f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (warpConfig.isRegistered) {
                                WarpKeyValRow("Device ID", warpConfig.deviceId)
                                WarpKeyValRow("Endpoint", "${warpConfig.endpointHost}:${warpConfig.endpointPort}")
                                WarpKeyValRow("Local IPv4", warpConfig.localAddressIpv4)
                                if (warpConfig.localAddressIpv6.isNotBlank()) {
                                    WarpKeyValRow("Local IPv6", warpConfig.localAddressIpv6)
                                }
                                WarpKeyValRow("Reserved", warpConfig.reservedBytes.joinToString(", ", "[", "]"))
                                WarpKeyValRow("PrivateKey", warpConfig.privateKey.take(22) + "…")
                                WarpKeyValRow("PeerKey", warpConfig.peerPublicKey.take(22) + "…")
                            } else {
                                Text(
                                    text = "Профиль WARP не зарегистрирован. Нажмите кнопку ниже, чтобы сгенерировать пару ключей Curve25519 и получить конфигурацию от api.cloudflareclient.com.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    LiquidGlassButton(
                        hazeState = hazeState,
                        onClick = onRegenerateWarp,
                        enabled = !isGeneratingWarp,
                        accentColor = WarpViolet,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 50.dp)
                            .testTag("regenerate_warp_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isGeneratingWarp) {
                                CircularProgressIndicator(
                                    color = WarpViolet,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Регистрация в Cloudflare API…",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = WarpViolet
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = "Зарегистрировать WARP",
                                    tint = WarpViolet
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (warpConfig.isRegistered) {
                                        "Перегенерировать ключ Cloudflare WARP"
                                    } else {
                                        "Зарегистрировать бесплатный WARP"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Manual Key / URI / sing-box JSON Importer
        item {
            LiquidGlassCard(
                hazeState = hazeState,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Импорт ключа",
                            tint = ElectricCyan
                        )
                        Column {
                            Text(
                                text = "Импорт ключа или списка",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "vless://, hy2://, trojan://, vmess://, ss://, Base64, JSON",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = manualKeyText,
                        onValueChange = { manualKeyText = it },
                        placeholder = {
                            Text("Вставьте vless://... или Base64-подписку")
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        colors = glassTextFieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_key_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LiquidGlassButton(
                            hazeState = hazeState,
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                if (clipText.isNotBlank()) {
                                    manualKeyText = clipText
                                    onImportKey(clipText)
                                }
                            },
                            accentColor = ElectricCyan,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("paste_clipboard_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Из буфера",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Из буфера",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        LiquidGlassButton(
                            hazeState = hazeState,
                            onClick = {
                                if (manualKeyText.isNotBlank()) {
                                    onImportKey(manualKeyText)
                                    manualKeyText = ""
                                }
                            },
                            accentColor = CyberEmerald,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("import_key_submit_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Импортировать",
                                    tint = CyberEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Импортировать",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Add New Subscription Repository
        item {
            LiquidGlassCard(
                hazeState = hazeState,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "Репозитории подписок",
                            tint = CyberEmerald
                        )
                        Column {
                            Text(
                                text = "Добавить URL подписки",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Авто-сбор каждые 3 часа через WorkManager",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = newSourceTitle,
                        onValueChange = { newSourceTitle = it },
                        placeholder = { Text("Название источника") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = glassTextFieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("source_title_input")
                    )

                    OutlinedTextField(
                        value = newSourceUrl,
                        onValueChange = { newSourceUrl = it },
                        placeholder = { Text("https://raw.githubusercontent.com/.../sub.txt") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = glassTextFieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("source_url_input")
                    )

                    LiquidGlassButton(
                        hazeState = hazeState,
                        onClick = {
                            if (newSourceUrl.isNotBlank()) {
                                onAddSource(newSourceTitle, newSourceUrl)
                                newSourceTitle = ""
                                newSourceUrl = ""
                            }
                        },
                        accentColor = CyberEmerald,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("add_source_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = CyberEmerald
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Добавить источник подписки",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 4. Active Subscription URLs Header
        item {
            Text(
                text = "Активные источники подписок (${sources.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(sources, key = { it.id }) { source ->
            SubscriptionSourceGlassRow(
                hazeState = hazeState,
                source = source,
                onDelete = { onRemoveSource(source.id) }
            )
        }
    }
}

@Composable
private fun WarpKeyValRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun SubscriptionSourceGlassRow(
    hazeState: HazeState,
    source: SubscriptionSource,
    onDelete: () -> Unit
) {
    LiquidGlassCard(
        hazeState = hazeState,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("source_item_${source.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = source.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ElectricCyan.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = source.formatHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SignalAmber.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "Нод: ${source.lastNodeCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SignalAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_source_${source.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Удалить подписку",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
