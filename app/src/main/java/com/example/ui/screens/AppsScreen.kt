package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppRoutingItem
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.WarpViolet
import com.example.ui.components.HazeState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppsScreen(
    hazeState: HazeState,
    apps: List<AppRoutingItem>,
    whitelistedPackages: Set<String>,
    includeGmsDependencies: Boolean,
    onToggleGmsDependencies: (Boolean) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Все") }

    val categories = listOf("Все", "В белом списке", "Gemini & AI", "Видео и Соцсети", "Браузеры", "Установленные")

    val filteredApps = remember(apps, searchQuery, selectedCategory, whitelistedPackages) {
        apps.filter { item ->
            val matchesQuery = searchQuery.isBlank() ||
                item.appName.contains(searchQuery, ignoreCase = true) ||
                item.packageName.contains(searchQuery, ignoreCase = true)

            val matchesCategory = when (selectedCategory) {
                "Все" -> true
                "В белом списке" -> whitelistedPackages.contains(item.packageName)
                else -> item.category == selectedCategory
            }
            matchesQuery && matchesCategory
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("apps_screen_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header Liquid Glass Card + GMS / WebView System Dependencies Toggle
        item {
            LiquidGlassCard(
                hazeState = hazeState,
                accentGlowColor = ElectricCyan,
                modifier = Modifier.fillMaxWidth()
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Белый список маршрутов",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Только addAllowedApplication() • ${whitelistedPackages.size} прил.",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }
                        TextButton(
                            onClick = onResetDefaults,
                            modifier = Modifier.testTag("reset_whitelist_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = "Сбросить по умолчанию",
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Сброс", color = ElectricCyan)
                        }
                    }

                    Text(
                        text = "В TUN попадают только отмеченные приложения. Банки РФ, Госуслуги и маркетплейсы идут напрямую мимо VPN.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, (if (includeGmsDependencies) CyberEmerald else Color.White).copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Системные зависимости (GMS / WebView)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Добавляет Google Play Services, GSF и WebView для корректной авторизации в Gemini и YouTube",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = includeGmsDependencies,
                                onCheckedChange = onToggleGmsDependencies,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF002416),
                                    checkedTrackColor = CyberEmerald
                                ),
                                modifier = Modifier.testTag("gms_dependencies_switch")
                            )
                        }
                    }
                }
            }
        }

        // 2. Glass Search Bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск по названию или packageName…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Поиск"
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Очистить поиск"
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.08f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.16f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_apps_input")
            )
        }

        // 3. Category Filter Chips
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                categories.forEach { category ->
                    val selected = selectedCategory == category
                    FilterChip(
                        selected = selected,
                        onClick = { selectedCategory = category },
                        label = { Text(category) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.White.copy(alpha = 0.05f),
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                            selectedLabelColor = ElectricCyan
                        )
                    )
                }
            }
        }

        // 4. App Items
        items(filteredApps, key = { it.packageName }) { app ->
            val isChecked = whitelistedPackages.contains(app.packageName)
            AppWhitelistGlassRow(
                hazeState = hazeState,
                item = app,
                isChecked = isChecked,
                onCheckedChange = { enabled -> onToggleApp(app.packageName, enabled) }
            )
        }
    }
}

@Composable
private fun AppWhitelistGlassRow(
    hazeState: HazeState,
    item: AppRoutingItem,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val appIconBitmap = remember(item.packageName, item.isInstalledOnDevice) {
        if (!item.isInstalledOnDevice) return@remember null
        try {
            val drawable = context.packageManager.getApplicationIcon(item.packageName)
            drawableToBitmap(drawable)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    val categoryAccent = when (item.category) {
        "Gemini & AI" -> WarpViolet
        "Видео и Соцсети" -> ElectricCyan
        "Браузеры" -> CyberEmerald
        else -> SignalAmber
    }

    LiquidGlassCard(
        hazeState = hazeState,
        accentGlowColor = if (isChecked) ElectricCyan else null,
        shape = RoundedCornerShape(18.dp),
        onClick = { onCheckedChange(!isChecked) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_row_${item.packageName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (appIconBitmap != null) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = item.appName,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(11.dp))
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(categoryAccent.copy(alpha = 0.18f))
                    ) {
                        val icon = when (item.category) {
                            "Gemini & AI" -> Icons.Default.SmartToy
                            "Видео и Соцсети" -> Icons.Default.VideoLibrary
                            "Браузеры" -> Icons.Default.Public
                            else -> Icons.Default.Apps
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = item.appName,
                            tint = categoryAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.appName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = categoryAccent.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = item.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = categoryAccent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        if (item.isInstalledOnDevice) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = CyberEmerald.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = "Установлено",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberEmerald,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = ElectricCyan,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("checkbox_${item.packageName}")
            )
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    return try {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap
    } catch (_: Exception) {
        null
    }
}
