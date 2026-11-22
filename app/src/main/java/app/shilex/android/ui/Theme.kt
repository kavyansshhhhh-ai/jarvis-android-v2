package app.shilex.android.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shilex.android.data.AppearanceMode
import app.shilex.android.data.Settings

// ==========================================
// AMOLED Liquid Glass Palette (Frosted Dark)
// ==========================================
private val DarkBackground = Color(0xFF000000)
private val DarkSurface = Color(0xFF151519)
private val DarkSurfaceVariant = Color(0xFF1C1C22)
private val DarkSurfaceElevated = Color(0xFF222229)
private val LightBackground = Color(0xFFF3F7F9)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE7EEF1)
private val LightSurfaceElevated = Color(0xFFFFFFFF)

// Accents
private val DarkCyan = Color(0xFF38E5FF)
private val LightCyan = Color(0xFF006C7B)
private val DeepCyan = Color(0xFF0B3542)
private val DarkViolet = Color(0xFF9D65FF)
private val LightViolet = Color(0xFF7044AE)
private val DarkPink = Color(0xFFF472B6)
private val LightPink = Color(0xFFB52F78)
private val DarkAmber = Color(0xFFFFB038)
private val LightAmber = Color(0xFF8A5300)
private val DarkError = Color(0xFFFF6363)
private val LightError = Color(0xFFB3261E)

val OledBlack: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val GlassSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
val GlassSurfaceVariant: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
val GlassSurfaceElevated: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
val GlassSurfaceHighlight: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
val ElectricCyan: Color @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background == DarkBackground) DarkCyan else LightCyan
val NeonViolet: Color @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background == DarkBackground) DarkViolet else LightViolet
val SoftPink: Color @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background == DarkBackground) DarkPink else LightPink
val AmberWarning: Color @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background == DarkBackground) DarkAmber else LightAmber
val CoralError: Color @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background == DarkBackground) DarkError else LightError
val SlateMuted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val PureWhite: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onBackground

// Glass Borders
val GlassBorderColor: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
val GlassBorderSubtle: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline.copy(alpha = 0.62f)
val GlassDividerColor: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)

private val Dark = darkColorScheme(
    primary = DarkCyan,
    onPrimary = Color(0xFF001B22),
    primaryContainer = DeepCyan,
    onPrimaryContainer = Color(0xFFD0F7FF),
    secondary = DarkViolet,
    onSecondary = Color(0xFF1E0A40),
    secondaryContainer = Color(0xFF2B1654),
    tertiary = DarkAmber,
    background = DarkBackground,
    onBackground = Color(0xFFF9FAFB),
    surface = DarkBackground,
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF9898A3),
    surfaceContainer = DarkSurface,
    outline = Color(0x2BFFFFFF),
    outlineVariant = Color(0x3AFFFFFF),
    error = DarkError,
)

private val Light = lightColorScheme(
    primary = LightCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8EDF1),
    onPrimaryContainer = Color(0xFF00363E),
    secondary = LightViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADDFF),
    onSecondaryContainer = Color(0xFF271044),
    tertiary = LightAmber,
    background = LightBackground,
    onBackground = Color(0xFF17252B),
    surface = LightSurface,
    onSurface = Color(0xFF17252B),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF526168),
    surfaceContainer = LightSurfaceElevated,
    outline = Color(0xFF84969D),
    outlineVariant = Color(0xFFC7D4D9),
    error = LightError,
)

private val Type = Typography(
    displaySmall = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
        fontFamily = FontFamily.SansSerif,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        fontFamily = FontFamily.SansSerif,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
        fontFamily = FontFamily.SansSerif,
    ),
    titleMedium = TextStyle(
        fontSize = 15.5.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
        fontFamily = FontFamily.SansSerif,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
    ),
    bodySmall = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.SansSerif,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
        fontFamily = FontFamily.SansSerif,
    ),
)

private val Corners = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
)

@Immutable
private data class ShilexBrushes(
    val glassBorderGradient: Brush,
    val glassSurfaceGradient: Brush,
    val glassDockSurfaceGradient: Brush,
    val glassDockBorderGradient: Brush,
    val glassAccentGradient: Brush,
    val userChatBubbleGradient: Brush,
    val liveStopButtonGradient: Brush,
    val talkButtonGradient: Brush,
    val buttonRimGradient: Brush,
    val neutralRowIconBgGradient: Brush,
    val neutralRowIconBorderGradient: Brush,
)

private fun buildGlassBrushes(
    colors: androidx.compose.material3.ColorScheme,
    dark: Boolean,
    accent: Color,
    violet: Color,
    bubbleEndViolet: Color,
    errorColor: Color,
): ShilexBrushes {
    val rimTop = if (dark) Color(0x52FFFFFF) else Color(0xD8FFFFFF)
    val rimBottom = if (dark) Color(0x28FFFFFF) else colors.outlineVariant.copy(alpha = 0.85f)
    val cardTop = if (dark) Color(0xFF1E2028) else Color(0xF5FFFFFF)
    val cardBottom = if (dark) Color(0xFF111218) else Color(0xFFEAF0F4)
    val dockTop = if (dark) Color(0xEB1C1F27) else Color(0xF5FFFFFF)
    val dockBottom = if (dark) Color(0xEB101218) else Color(0xFFE5EDF2)
    return ShilexBrushes(
        glassBorderGradient = Brush.verticalGradient(
            listOf(rimTop, colors.outline.copy(alpha = if (dark) 0.28f else 0.45f), rimBottom),
        ),
        glassSurfaceGradient = Brush.verticalGradient(listOf(cardTop, cardBottom)),
        glassDockSurfaceGradient = Brush.verticalGradient(listOf(dockTop, dockBottom)),
        glassDockBorderGradient = Brush.verticalGradient(
            listOf(rimTop, accent.copy(alpha = 0.48f), rimBottom),
        ),
        glassAccentGradient = Brush.horizontalGradient(listOf(accent, violet)),
        userChatBubbleGradient = Brush.horizontalGradient(listOf(accent, bubbleEndViolet)),
        liveStopButtonGradient = Brush.verticalGradient(listOf(errorColor, errorColor.copy(alpha = 0.80f))),
        talkButtonGradient = Brush.verticalGradient(listOf(accent.copy(alpha = 0.90f), accent)),
        buttonRimGradient = Brush.verticalGradient(
            listOf(rimTop, colors.onSurface.copy(alpha = 0.08f)),
        ),
        neutralRowIconBgGradient = Brush.verticalGradient(listOf(colors.surfaceVariant, colors.surfaceContainer)),
        neutralRowIconBorderGradient = Brush.verticalGradient(
            listOf(rimTop.copy(alpha = 0.65f), colors.outline.copy(alpha = 0.42f)),
        ),
    )
}

private fun buildFlatBrushes(
    colors: androidx.compose.material3.ColorScheme,
    accent: Color,
    violet: Color,
    bubbleEndViolet: Color,
    errorColor: Color,
): ShilexBrushes {
    val cardSolid = colors.surfaceContainer
    val borderSolid = colors.outlineVariant
    return ShilexBrushes(
        glassBorderGradient = Brush.verticalGradient(listOf(borderSolid, borderSolid)),
        glassSurfaceGradient = Brush.verticalGradient(listOf(cardSolid, cardSolid)),
        glassDockSurfaceGradient = Brush.verticalGradient(listOf(colors.surfaceVariant, colors.surfaceContainer)),
        glassDockBorderGradient = Brush.verticalGradient(listOf(borderSolid, borderSolid)),
        glassAccentGradient = Brush.horizontalGradient(listOf(accent, violet)),
        userChatBubbleGradient = Brush.horizontalGradient(listOf(accent, bubbleEndViolet)),
        liveStopButtonGradient = Brush.verticalGradient(listOf(errorColor, errorColor)),
        talkButtonGradient = Brush.verticalGradient(listOf(accent, accent)),
        buttonRimGradient = Brush.verticalGradient(listOf(borderSolid, borderSolid)),
        neutralRowIconBgGradient = Brush.verticalGradient(listOf(colors.surfaceVariant, colors.surfaceVariant)),
        neutralRowIconBorderGradient = Brush.verticalGradient(listOf(borderSolid, borderSolid)),
    )
}

private val DarkGlassBrushes = buildGlassBrushes(
    colors = Dark,
    dark = true,
    accent = DarkCyan,
    violet = DarkViolet,
    bubbleEndViolet = Color(0xFF7C3AED),
    errorColor = DarkError,
)

private val LightGlassBrushes = buildGlassBrushes(
    colors = Light,
    dark = false,
    accent = LightCyan,
    violet = LightViolet,
    bubbleEndViolet = LightViolet,
    errorColor = LightError,
)

private val DarkFlatBrushes = buildFlatBrushes(
    colors = Dark,
    accent = DarkCyan,
    violet = DarkViolet,
    bubbleEndViolet = Color(0xFF7C3AED),
    errorColor = DarkError,
)

private val LightFlatBrushes = buildFlatBrushes(
    colors = Light,
    accent = LightCyan,
    violet = LightViolet,
    bubbleEndViolet = LightViolet,
    errorColor = LightError,
)

private val LocalAppearanceMode = compositionLocalOf { AppearanceMode.Dark }
val LocalLiquidGlassEnabled = compositionLocalOf { true }
private val LocalShilexBrushes = staticCompositionLocalOf { DarkGlassBrushes }

/** JARVIS theme with persistent Dark, Light, System modes and optional iOS Liquid Glass styling. */
@Composable
fun ShilexTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var savedMode by remember(preferences) {
        mutableStateOf(Settings(context).appearanceMode)
    }
    var liquidGlassEnabled by remember(preferences) {
        mutableStateOf(Settings(context).liquidGlassEnabled)
    }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Settings.PREF_APPEARANCE_MODE -> savedMode = Settings(context).appearanceMode
                Settings.PREF_LIQUID_GLASS -> liquidGlassEnabled = Settings(context).liquidGlassEnabled
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val dark = when (savedMode) {
        AppearanceMode.Dark -> true
        AppearanceMode.Light -> false
        AppearanceMode.System -> systemDark
    }
    val brushes = when {
        dark && liquidGlassEnabled -> DarkGlassBrushes
        dark -> DarkFlatBrushes
        liquidGlassEnabled -> LightGlassBrushes
        else -> LightFlatBrushes
    }
    val view = LocalView.current
    val window = (context as? Activity)?.window
    SideEffect {
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalAppearanceMode provides savedMode,
        LocalLiquidGlassEnabled provides liquidGlassEnabled,
        LocalShilexBrushes provides brushes,
    ) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = Type,
            shapes = Corners,
            content = content,
        )
    }
}

@Composable
@ReadOnlyComposable
fun currentAppearanceMode(): AppearanceMode = LocalAppearanceMode.current

@Composable
@ReadOnlyComposable
fun currentLiquidGlassEnabled(): Boolean = LocalLiquidGlassEnabled.current

// Common color accessors for screens
val Accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val VioletAccent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondary
val Background: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val Surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Warning: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary

// Smoked Frosted Liquid Glass Brushes (cached once per theme to eliminate per-frame allocations)
val GlassBorderGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.glassBorderGradient
val GlassSurfaceGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.glassSurfaceGradient
val GlassDockSurfaceGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.glassDockSurfaceGradient
val GlassDockBorderGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.glassDockBorderGradient
val GlassAccentGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.glassAccentGradient
val UserChatBubbleGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.userChatBubbleGradient
val LiveStopButtonGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.liveStopButtonGradient
val TalkButtonGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.talkButtonGradient
val ButtonRimGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.buttonRimGradient
val NeutralRowIconBgGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.neutralRowIconBgGradient
val NeutralRowIconBorderGradient: Brush @Composable @ReadOnlyComposable get() = LocalShilexBrushes.current.neutralRowIconBorderGradient
