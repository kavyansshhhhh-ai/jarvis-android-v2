package app.shilex.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val FloatingDockHeight = LiquidDockMath.DOCK_HEIGHT_DP.dp
val FloatingDockOuterVerticalMargin = LiquidDockMath.DOCK_OUTER_VERTICAL_MARGIN_DP.dp
val FloatingDockScrollClearance = LiquidDockMath.SCROLL_CLEARANCE_DP.dp

/** Bottom padding needed for scrollable content so the last item scrolls completely above the floating dock + nav bar. */
@Composable
fun floatingDockContentBottomPadding(clearance: Dp = FloatingDockScrollClearance): Dp =
    clearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Spacer placed at the end of scrollable columns so bottom cards never hide behind the floating dock. */
@Composable
fun FloatingDockBottomSpacer(clearance: Dp = FloatingDockScrollClearance) {
    Spacer(Modifier.height(floatingDockContentBottomPadding(clearance)))
}

/** Controls animated background blur depth when modal Liquid Glass sheets are presented. */
val LocalModalOverlayActive = compositionLocalOf<(Boolean) -> Unit> { {} }

/** Smoked translucent Liquid Glass card container with crisp top-lit specular hairline rim. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    horizontalPadding: Int = 16,
    verticalPadding: Int = 6,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(GlassSurfaceGradient)
            .border(1.dp, GlassBorderGradient, shape)
            .padding(horizontal = horizontalPadding.dp, vertical = verticalPadding.dp),
        content = content,
    )
}

/** Clean hairline divider inside a grouped Liquid Glass card. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(GlassDividerColor),
    )
}

/** Compact frosted circular/rounded-square icon badge for list rows. */
@Composable
fun GlassRowIcon(
    icon: ImageVector,
    tint: Color = PureWhite,
    accentGlow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val iconShape = remember { RoundedCornerShape(10.dp) }
    val neutralBackground = NeutralRowIconBgGradient
    val bgBrush = remember(tint, accentGlow, neutralBackground) {
        if (accentGlow) {
            Brush.verticalGradient(listOf(tint.copy(alpha = 0.24f), tint.copy(alpha = 0.10f)))
        } else {
            neutralBackground
        }
    }
    val borderBrush = remember(tint, accentGlow) {
        if (accentGlow) {
            Brush.verticalGradient(listOf(tint.copy(alpha = 0.45f), Color(0x14FFFFFF)))
        } else {
            NeutralRowIconBorderGradient
        }
    }
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(iconShape)
            .background(bgBrush)
            .border(0.7.dp, borderBrush, iconShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Tactile spring press scale modifier for Liquid Glass pills and buttons. */
@Composable
fun Modifier.glassPressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "glassPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A titled group of rows inside a floating smoked Liquid Glass card (Title-case hierarchy). */
@Composable
fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Muted,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.15.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }
        GlassCard(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** An iOS-styled Liquid Glass switch row with spring-animated thumb and subtle icon badge. */
@Composable
fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color = ElectricCyan,
    onChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) { onChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            GlassRowIcon(icon = icon, tint = if (checked && enabled) iconTint else Muted, accentGlow = checked && enabled)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) PureWhite else Muted)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(14.dp))
        IosGlassSwitch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** iOS-style Liquid Glass toggle switch with fluid GPU-composited spring physics. */
@Composable
fun IosGlassSwitch(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val thumbProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "switchThumb",
    )
    val trackAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "switchTrackAlpha",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) ElectricCyan.copy(alpha = 0.72f) else GlassBorderColor.copy(alpha = 0.72f),
        animationSpec = tween(durationMillis = 200),
        label = "switchBorder",
    )
    val density = LocalDensity.current
    val baseStartPx = with(density) { 2.dp.toPx() }
    val travelPx = with(density) { 20.dp.toPx() }
    val switchShape = remember { RoundedCornerShape(16.dp) }
    val switchInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(switchShape)
            .background(GlassSurfaceHighlight)
            .border(1.dp, borderColor, switchShape)
            .clickable(
                interactionSource = switchInteraction,
                indication = null,
                enabled = enabled,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = trackAlpha }
                .background(GlassAccentGradient),
        )
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = baseStartPx + thumbProgress * travelPx }
                .size(26.dp)
                .shadow(elevation = if (checked) 5.dp else 3.dp, shape = CircleShape, spotColor = if (checked) ElectricCyan else Color.Black)
                .background(Color.White, CircleShape)
                .border(0.5.dp, Color(0x33000000), CircleShape),
        )
    }
}

/** A row of text with an optional leading icon badge and trailing control. */
@Composable
fun InfoRow(
    title: String,
    detail: String,
    icon: ImageVector? = null,
    iconTint: Color = PureWhite,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            GlassRowIcon(icon = icon, tint = iconTint)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = PureWhite)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A modern Liquid Glass pill badge with glowing dot and translucent rim. */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    dot: Boolean = true,
) {
    val pillShape = remember { RoundedCornerShape(50) }
    val bgBrush = remember(color) {
        Brush.horizontalGradient(
            listOf(
                color.copy(alpha = 0.16f),
                color.copy(alpha = 0.08f),
            ),
        )
    }
    val borderBrush = remember(color) {
        Brush.horizontalGradient(
            listOf(
                color.copy(alpha = 0.45f),
                color.copy(alpha = 0.16f),
            ),
        )
    }
    Row(
        modifier
            .background(bgBrush, pillShape)
            .border(0.8.dp, borderBrush, pillShape)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
                    .shadow(elevation = 3.dp, shape = CircleShape, ambientColor = color, spotColor = color),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Material microphone glyph. */
val MicIcon: ImageVector by lazy {
    ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 14f)
            curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
            lineTo(15f, 5f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
            reflectiveCurveTo(9f, 3.34f, 9f, 5f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
            close()
            moveTo(17.3f, 11f)
            curveToRelative(0f, 3f, -2.54f, 5.1f, -5.3f, 5.1f)
            reflectiveCurveTo(6.7f, 14f, 6.7f, 11f)
            lineTo(5f, 11f)
            curveToRelative(0f, 3.41f, 2.72f, 6.23f, 6f, 6.72f)
            lineTo(11f, 21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-3.28f)
            curveToRelative(3.28f, -0.48f, 6f, -3.3f, 6f, -6.72f)
            horizontalLineToRelative(-1.7f)
            close()
        }
    }.build()
}
