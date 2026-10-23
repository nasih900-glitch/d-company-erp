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
 * The Executive Dark roles are shared by every native workspace. Semantic
 * colours accompany operational labels or icons; the original company mark is
 * unchanged.
 */
@Immutable
data class BrandPalette(
    val Background: Color,
    val BackgroundSecondary: Color,
    val Surface: Color,
    val SurfaceRaised: Color,
    val SurfaceHover: Color,
    val SurfaceOverlay: Color,
    /** Primary action/text accent; BrandAccent retains the original logo gold. */
    val Gold: Color,
    val BrandAccent: Color,
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
    Background = Color(0xFF080E17), BackgroundSecondary = Color(0xFF0B1421),
    Surface = Color(0xFF0F1A29), SurfaceRaised = Color(0xFF142235),
    SurfaceHover = Color(0xFF19283A), SurfaceOverlay = Color(0xFF1D2D41),
    Gold = Color(0xFFEBC577), BrandAccent = Color(0xFFC6A15B),
    GoldBright = Color(0xFFF4D895), GoldMuted = Color(0xFFC9AA68),
    Foreground = Color(0xFFF3F5F7), ForegroundMuted = Color(0xFFAAB5C5),
    // #718096 is decorative-only: it is 3.99:1 on the raised surface.
    ForegroundFaint = Color(0xFF96A3B5), Disabled = Color(0xFF8E9AAC),
    Good = Color(0xFF42C49A), GoodMuted = Color(0xFF12382F),
    Warning = Color(0xFFE7B95B), WarningMuted = Color(0xFF3B301B),
    Danger = Color(0xFFE26970), DangerMuted = Color(0xFF3B1D24),
    Information = Color(0xFF5AA7FF), InformationMuted = Color(0xFF142F4B),
    Border = Color(0xFF273344), BorderSubtle = Color(0xFF273344),
    ControlBorder = Color(0xFF708199), FocusRing = Color(0xFFEBC577),
)

// Build 45 had a dark palette only. The retained Day option uses the same brass
// identity with darker text/control accents so small labels remain readable.
internal val DayPalette = BrandPalette(
    Background = Color(0xFFF7F7F7), BackgroundSecondary = Color(0xFFFFFFFF),
    Surface = Color(0xFFFFFFFF), SurfaceRaised = Color(0xFFF0F0F0),
    SurfaceHover = Color(0xFFE8E8E8), SurfaceOverlay = Color(0xFFFFFFFF),
    Gold = Color(0xFF87621F), BrandAccent = Color(0xFFC6A15B),
    GoldBright = Color(0xFF77561B), GoldMuted = Color(0xFF715B37),
    Foreground = Color(0xFF111111), ForegroundMuted = Color(0xFF525252),
    ForegroundFaint = Color(0xFF686868), Disabled = Color(0xFF888888),
    Good = Color(0xFF176F43), GoodMuted = Color(0xFFDCEFE4),
    Warning = Color(0xFF8A570B), WarningMuted = Color(0xFFFFF0D6),
    Danger = Color(0xFFBE3236), DangerMuted = Color(0xFFF9DFE0),
    Information = Color(0xFF1B5F9E), InformationMuted = Color(0xFFDFECF7),
    Border = Color(0xFFD8D8D8), BorderSubtle = Color(0xFFD8D8D8),
    ControlBorder = Color(0xFF686868), FocusRing = Color(0xFF87621F),
)

private val LocalBrandPalette = staticCompositionLocalOf { NightPalette }

/** Semantic colours stay stable across appearances and always accompany text or an icon. */
object Brand {
    val Good = Color(0xFF42C49A)
    val GoodMuted = Color(0xFF12382F)
    val Warning = Color(0xFFE7B95B)
    val WarningMuted = Color(0xFF3B301B)
    val Danger = Color(0xFFE26970)
    val DangerMuted = Color(0xFF3B1D24)
    val Information = Color(0xFF4A83C2)
    val InformationMuted = Color(0xFFDCEAF8)
}

private val ColorScheme.usesDayPalette: Boolean
    get() = background.luminance() > 0.5f

val ColorScheme.brandAccent: Color
    get() = if (usesDayPalette) DayPalette.BrandAccent else NightPalette.BrandAccent

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
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
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
        primaryContainer = palette.Gold.copy(alpha = 0.16f), onPrimaryContainer = palette.GoldBright,
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
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = PreciseTextStyle.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = PreciseTextStyle.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
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
