package cloud.dcompany.erp.ui.theme

import android.app.Activity
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * D Company executive palette.
 *
 * The interface uses the same monochrome foundation as the Web ERP. The
 * original gold company mark remains the brand accent; semantic colours are
 * reserved for operational state and always accompany text or an icon.
 */
@Immutable
data class BrandPalette(
    val Background: Color,
    val BackgroundSecondary: Color,
    val Surface: Color,
    val SurfaceRaised: Color,
    val SurfaceHover: Color,
    val SurfaceOverlay: Color,
    val Gold: Color,
    val GoldBright: Color,
    val GoldMuted: Color,
    val Foreground: Color,
    val ForegroundMuted: Color,
    val ForegroundFaint: Color,
    val Disabled: Color,
    val Good: Color,
    val GoodMuted: Color,
    val Warning: Color,
    val WarningMuted: Color,
    val Danger: Color,
    val DangerMuted: Color,
    val Information: Color,
    val InformationMuted: Color,
    val Border: Color,
    val BorderSubtle: Color,
    val ControlBorder: Color,
    val FocusRing: Color,
)

internal val NightPalette = BrandPalette(
    Background = Color(0xFF080808), BackgroundSecondary = Color(0xFF0F0F0F),
    Surface = Color(0xFF111111), SurfaceRaised = Color(0xFF1D1D1C),
    SurfaceHover = Color(0xFF272725), SurfaceOverlay = Color(0xFF30302E),
    Gold = Color(0xFFF5F5F1), GoldBright = Color(0xFFFFFFFF), GoldMuted = Color(0xFFC7C7C0),
    Foreground = Color(0xFFF7F7F4), ForegroundMuted = Color(0xFFB0B0A8),
    ForegroundFaint = Color(0xFF909089), Disabled = Color(0xFF6F6F69),
    Good = Color(0xFF52C37E), GoodMuted = Color(0xFF153722),
    Warning = Color(0xFFF3A83B), WarningMuted = Color(0xFF3D2B12),
    Danger = Color(0xFFF4686E), DangerMuted = Color(0xFF471F25),
    Information = Color(0xFF86B9F4), InformationMuted = Color(0xFF172D45),
    Border = Color(0xFF3D3D3A), BorderSubtle = Color(0xFF2B2B29),
    ControlBorder = Color(0xFF74746E), FocusRing = Color(0xFFF5F5F1),
)

internal val DayPalette = BrandPalette(
    Background = Color(0xFFF6F6F4), BackgroundSecondary = Color(0xFFFFFFFF),
    Surface = Color(0xFFFFFFFF), SurfaceRaised = Color(0xFFEBEBE8),
    SurfaceHover = Color(0xFFE1E1DD), SurfaceOverlay = Color(0xFFFFFFFF),
    Gold = Color(0xFF161615), GoldBright = Color(0xFF000000), GoldMuted = Color(0xFF4F4F4B),
    Foreground = Color(0xFF111110), ForegroundMuted = Color(0xFF5B5B56),
    ForegroundFaint = Color(0xFF74746E), Disabled = Color(0xFF9A9A94),
    Good = Color(0xFF1B7E4B), GoodMuted = Color(0xFFDCEFE4),
    Warning = Color(0xFF9A5900), WarningMuted = Color(0xFFFFE8C4),
    Danger = Color(0xFFBE3236), DangerMuted = Color(0xFFF9DFE0),
    Information = Color(0xFF2565A9), InformationMuted = Color(0xFFDCEAF8),
    Border = Color(0xFFCACAC5), BorderSubtle = Color(0xFFDEDEDA),
    ControlBorder = Color(0xFF70706A), FocusRing = Color(0xFF111110),
)

private val LocalBrandPalette = staticCompositionLocalOf { NightPalette }

/** Semantic colours stay stable across appearances and always accompany text or an icon. */
object Brand {
    val Good = Color(0xFF2B9B5C)
    val GoodMuted = Color(0xFFDCEFE4)
    val Warning = Color(0xFFD17B0F)
    val WarningMuted = Color(0xFFFFE8C4)
    val Danger = Color(0xFFD94B51)
    val DangerMuted = Color(0xFFF9DFE0)
    val Information = Color(0xFF4A83C2)
    val InformationMuted = Color(0xFFDCEAF8)
}

private val ColorScheme.usesDayPalette: Boolean
    get() = background.luminance() > 0.5f

val ColorScheme.operationalGood: Color
    get() = if (usesDayPalette) DayPalette.Good else NightPalette.Good
val ColorScheme.operationalGoodContainer: Color
    get() = if (usesDayPalette) DayPalette.GoodMuted else NightPalette.GoodMuted
val ColorScheme.operationalWarning: Color
    get() = if (usesDayPalette) DayPalette.Warning else NightPalette.Warning
val ColorScheme.operationalWarningContainer: Color
    get() = if (usesDayPalette) DayPalette.WarningMuted else NightPalette.WarningMuted
val ColorScheme.operationalDanger: Color
    get() = if (usesDayPalette) DayPalette.Danger else NightPalette.Danger
val ColorScheme.operationalDangerContainer: Color
    get() = if (usesDayPalette) DayPalette.DangerMuted else NightPalette.DangerMuted
val ColorScheme.operationalInformation: Color
    get() = if (usesDayPalette) DayPalette.Information else NightPalette.Information
val ColorScheme.operationalInformationContainer: Color
    get() = if (usesDayPalette) DayPalette.InformationMuted else NightPalette.InformationMuted

/** 4px base spacing scale — every screen in this app hand-rolled its own
 * `8.dp`/`12.dp`/`16.dp` padding values; naming them stops the same gap
 * silently drifting between screens over time. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val lgPlus = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp
    val huge = 48.dp
}

/** Corner-radius scale — small controls vs. cards vs. dialogs vs. pills. */
object Radius {
    val sm = 8.dp
    val md = 10.dp
    val lg = 14.dp
    val xl = 18.dp
    val pill = 999.dp

    val shapeSm = RoundedCornerShape(sm)
    val shapeMd = RoundedCornerShape(md)
    val shapeLg = RoundedCornerShape(lg)
    val shapeXl = RoundedCornerShape(xl)
    val shapePill = RoundedCornerShape(pill)
}

/** Motion durations + the one easing curve used everywhere, so a screen
 * transition, a dialog, and a button press all feel like the same app
 * rather than each picking their own timing by feel. */
object Motion {
    const val fast = 120
    const val medium = 240
    const val slow = 360

    /** Material's "emphasized" curve — decelerates hard at the end, which
     * reads as more deliberate than the default FastOutSlowIn on anything
     * bigger than a button ripple (panel entrances, tab crossfades). */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

private fun companyColorScheme(palette: BrandPalette, dark: Boolean) =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = palette.Gold, onPrimary = palette.Background,
        primaryContainer = palette.SurfaceRaised, onPrimaryContainer = palette.Foreground,
        secondary = palette.GoldMuted, onSecondary = palette.Background,
        secondaryContainer = palette.SurfaceHover, onSecondaryContainer = palette.Foreground,
        tertiary = palette.Information, onTertiary = palette.Background,
        tertiaryContainer = palette.InformationMuted, onTertiaryContainer = palette.Information,
        background = palette.Background, onBackground = palette.Foreground,
        surface = palette.Surface, onSurface = palette.Foreground,
        surfaceVariant = palette.SurfaceRaised, onSurfaceVariant = palette.ForegroundMuted,
        surfaceDim = if (dark) palette.Background else palette.SurfaceHover,
        surfaceBright = if (dark) palette.SurfaceHover else palette.Surface,
        surfaceContainerLowest = palette.Background,
        surfaceContainerLow = palette.BackgroundSecondary,
        surfaceContainer = palette.Surface,
        surfaceContainerHigh = palette.SurfaceRaised,
        surfaceContainerHighest = palette.SurfaceHover,
        error = palette.Danger, onError = palette.Background,
        errorContainer = palette.DangerMuted, onErrorContainer = palette.Danger,
        outline = palette.Border, outlineVariant = palette.BorderSubtle,
        inverseSurface = palette.Foreground, inverseOnSurface = palette.Background,
        inversePrimary = palette.GoldMuted, surfaceTint = Color.Transparent,
        scrim = Color.Black.copy(alpha = if (dark) 0.72f else 0.42f),
    )

private val DCompanyShapes = Shapes(
    extraSmall = Radius.shapeSm,
    small = Radius.shapeSm,
    medium = Radius.shapeMd,
    large = Radius.shapeLg,
    extraLarge = Radius.shapeXl,
)

private val PreciseTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private val DCompanyTypography = Typography(
    displayLarge = PreciseTextStyle.copy(
        fontSize = 40.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = PreciseTextStyle.copy(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = PreciseTextStyle.copy(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = PreciseTextStyle.copy(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = PreciseTextStyle.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = PreciseTextStyle.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = PreciseTextStyle.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = PreciseTextStyle.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = PreciseTextStyle.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = PreciseTextStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = PreciseTextStyle.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = PreciseTextStyle.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.05.sp,
    ),
    labelSmall = PreciseTextStyle.copy(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.12.sp,
    ),
)

enum class AppearancePreference { System, Day, Night }

@Composable
fun DCompanyTheme(
    preference: AppearancePreference = AppearancePreference.System,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        AppearancePreference.System -> isSystemInDarkTheme()
        AppearancePreference.Day -> false
        AppearancePreference.Night -> true
    }
    val palette = if (dark) NightPalette else DayPalette
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalBrandPalette provides palette) {
        MaterialTheme(
            colorScheme = companyColorScheme(palette, dark),
            typography = DCompanyTypography,
            shapes = DCompanyShapes,
            content = content,
        )
    }
}
