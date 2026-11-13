package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.WarpViolet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Состояние реального аппаратного размытия фона (Liquid Glass Backdrop Blur)
 * на базе нативного Compose 1.7 `GraphicsLayer` + `BlurEffect`.
 */
@Stable
class HazeState {
    internal var backgroundLayer: GraphicsLayer? by mutableStateOf(null)
    internal var sourceOffsetInRoot: Offset by mutableStateOf(Offset.Zero)
}

@Immutable
data class HazeTint(val color: Color)

@Immutable
data class HazeStyle(
    val backgroundColor: Color = Color(0xFF0A1020),
    val tint: HazeTint = HazeTint(Color(0xFF0C1428).copy(alpha = 0.55f)),
    val blurRadius: Dp = 22.dp,
    val noiseFactor: Float = 0.04f
)

@Composable
fun Modifier.hazeSource(state: HazeState): Modifier {
    val layer = rememberGraphicsLayer()
    SideEffect {
        state.backgroundLayer = layer
    }
    return this
        .onGloballyPositioned { coords ->
            state.sourceOffsetInRoot = coords.positionInRoot()
        }
        .drawWithContent {
            layer.record {
                this@drawWithContent.drawContent()
            }
            drawLayer(layer)
        }
}

@Composable
fun Modifier.hazeEffect(
    state: HazeState,
    style: HazeStyle = HazeStyle()
): Modifier {
    val blurLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { style.blurRadius.toPx() }
    var cardOffsetInRoot by remember { mutableStateOf(Offset.Zero) }

    return this
        .onGloballyPositioned { coords ->
            cardOffsetInRoot = coords.positionInRoot()
        }
        .drawWithContent {
            drawRect(color = style.backgroundColor.copy(alpha = 0.76f))
            val bgLayer = state.backgroundLayer
            if (bgLayer != null && size.width > 0f && size.height > 0f) {
                val dx = state.sourceOffsetInRoot.x - cardOffsetInRoot.x
                val dy = state.sourceOffsetInRoot.y - cardOffsetInRoot.y
                blurLayer.apply {
                    if (blurRadiusPx > 0f) {
                        renderEffect = BlurEffect(
                            radiusX = blurRadiusPx,
                            radiusY = blurRadiusPx,
                            edgeTreatment = TileMode.Clamp
                        )
                    }
                    record {
                        clipRect(0f, 0f, size.width, size.height) {
                            translate(left = dx, top = dy) {
                                drawLayer(bgLayer)
                            }
                        }
                    }
                }
                drawLayer(blurLayer)
            }
            drawRect(color = style.tint.color)
            drawContent()
        }
}

/**
 * Живой глубинный фон (Dynamic Fluid Mesh):
 * глубокий обсидиановый космос с медленно дрейфующими размытыми пятнами
 * Electric Cyan, Deep Indigo и Soft Neon Violet.
 */
@Composable
fun FluidMeshBackground(
    isVpnConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fluid_mesh")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mesh_phase"
    )

    val cyanAlpha by animateFloatAsState(
        targetValue = if (isVpnConnected) 0.42f else 0.28f,
        animationSpec = tween(900),
        label = "cyan_alpha"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        val w = size.width
        val h = size.height

        // 1. Drifting Electric Cyan / Emerald orb (top-left -> center)
        val c1 = Offset(
            x = w * (0.28f + 0.16f * cos(phase)),
            y = h * (0.22f + 0.10f * sin(phase * 0.8f))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    (if (isVpnConnected) Color(0xFF10B981) else ElectricCyan).copy(alpha = cyanAlpha),
                    ElectricCyan.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = c1,
                radius = w * 0.72f
            ),
            radius = w * 0.72f,
            center = c1
        )

        // 2. Deep Indigo orb (mid-right)
        val c2 = Offset(
            x = w * (0.78f + 0.14f * sin(phase + 1.2f)),
            y = h * (0.48f + 0.14f * cos(phase * 0.7f))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF2563EB).copy(alpha = 0.34f),
                    Color(0xFF1E3A8A).copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = c2,
                radius = w * 0.78f
            ),
            radius = w * 0.78f,
            center = c2
        )

        // 3. Soft Neon Violet orb (bottom-left)
        val c3 = Offset(
            x = w * (0.26f + 0.18f * sin(phase * 0.9f + 2.4f)),
            y = h * (0.78f + 0.10f * cos(phase + 0.6f))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    WarpViolet.copy(alpha = 0.30f),
                    WarpViolet.copy(alpha = 0.07f),
                    Color.Transparent
                ),
                center = c3,
                radius = w * 0.68f
            ),
            radius = w * 0.68f,
            center = c3
        )
    }
}

/**
 * Карточка-линза из «Жидкого стекла» (Liquid Glass):
 * - Реальное аппаратное размытие фона через Haze (Android 12+ RenderEffect + фоллбэк)
 * - Полупрозрачная заливка (alpha 0.08–0.14) с вертикальным бликом
 * - Спекулярная обводка 1.dp (от 0.38 в левом верхнем углу к 0.05 в правом нижнем)
 */
@Composable
fun LiquidGlassCard(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    accentGlowColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "glass_press_scale"
    )

    val specularBrush = remember(accentGlowColor) {
        Brush.linearGradient(
            colors = listOf(
                (accentGlowColor ?: Color.White).copy(alpha = 0.38f),
                Color.White.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.04f)
            )
        )
    }

    val surfaceFillBrush = remember(accentGlowColor) {
        Brush.verticalGradient(
            colors = listOf(
                (accentGlowColor ?: Color.White).copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.06f)
            )
        )
    }

    val hazeStyle = remember {
        HazeStyle(
            backgroundColor = Color(0xFF0A1020),
            tint = HazeTint(Color(0xFF0C1428).copy(alpha = 0.55f)),
            blurRadius = 22.dp,
            noiseFactor = 0.04f
        )
    }

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = (accentGlowColor ?: ElectricCyan).copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.55f)
            )
            .clip(shape)
            .hazeEffect(state = hazeState, style = hazeStyle)
            .background(surfaceFillBrush)
            .border(width = 1.dp, brush = specularBrush, shape = shape)
            .then(clickableModifier),
        content = content
    )
}

/**
 * Кнопка с пружинной физикой Liquid Glass (без обрезания текста, с динамической высотой).
 */
@Composable
fun LiquidGlassButton(
    hazeState: HazeState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = ElectricCyan,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(18.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "btn_scale"
    )

    val borderBrush = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = if (enabled) 0.65f else 0.25f),
            Color.White.copy(alpha = 0.22f),
            accentColor.copy(alpha = 0.12f)
        )
    )

    val fillBrush = Brush.verticalGradient(
        colors = listOf(
            accentColor.copy(alpha = if (enabled) 0.28f else 0.12f),
            accentColor.copy(alpha = if (enabled) 0.12f else 0.05f)
        )
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.6f
            }
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = Color(0xFF0A1020),
                    tint = HazeTint(accentColor.copy(alpha = 0.10f)),
                    blurRadius = 18.dp
                )
            )
            .background(fillBrush)
            .border(1.dp, borderBrush, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        content = content
    )
}

data class LiquidDockTab(
    val title: String,
    val icon: ImageVector,
    val badgeCount: Int? = null,
    val testTag: String
)

/**
 * Парящий стеклянный док-бар (Floating Pill Dock) с сильным блюром,
 * отступами от краёв и перетекающим жидким индикатором под активной вкладкой.
 */
@Composable
fun FloatingLiquidDock(
    hazeState: HazeState,
    tabs: List<LiquidDockTab>,
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val pillShape = RoundedCornerShape(32.dp)

    val borderBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.42f),
                ElectricCyan.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.08f)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .shadow(
                    elevation = 20.dp,
                    shape = pillShape,
                    ambientColor = ElectricCyan.copy(alpha = 0.22f),
                    spotColor = Color.Black.copy(alpha = 0.7f)
                )
                .clip(pillShape)
                .hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = Color(0xFF070C18),
                        tint = HazeTint(Color(0xFF0B1326).copy(alpha = 0.65f)),
                        blurRadius = 28.dp,
                        noiseFactor = 0.05f
                    )
                )
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                )
                .border(1.dp, borderBrush, pillShape)
                .padding(5.dp)
        ) {
            val tabWidth = maxWidth / tabs.size.coerceAtLeast(1)
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "liquid_indicator_offset"
            )

            // Sliding liquid indicator pill
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ElectricCyan.copy(alpha = 0.28f),
                                WarpViolet.copy(alpha = 0.18f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.45f),
                                ElectricCyan.copy(alpha = 0.15f)
                            )
                        ),
                        shape = RoundedCornerShape(26.dp)
                    )
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == selectedIndex
                    val tint by animateColorAsState(
                        targetValue = if (isSelected) ElectricCyan else Color(0xFFB8C4D9),
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        label = "dock_tab_tint"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(26.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectTab(index)
                            }
                            .testTag(tab.testTag)
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = tint,
                                modifier = Modifier.size(21.dp)
                            )
                            if (tab.badgeCount != null && tab.badgeCount > 0) {
                                Surface(
                                    shape = CircleShape,
                                    color = ElectricCyan,
                                    modifier = Modifier.offset(x = 8.dp, y = (-4).dp)
                                ) {
                                    Text(
                                        text = tab.badgeCount.toString(),
                                        color = Color(0xFF001B22),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tab.title,
                            color = tint,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
