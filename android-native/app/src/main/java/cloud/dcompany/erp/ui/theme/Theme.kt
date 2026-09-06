package cloud.dcompany.erp.ui.theme

import android.app.Activity
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
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
 * The interface is a neutral business tool: navy establishes hierarchy, brass
 * is reserved for brand identity and primary actions, and semantic colours are
 * reserved for operational state. Gaming remains a workflow, not a visual
 * theme, so there are no decorative gradients, glows or neon treatments.
 */
object Brand {
    // Five deliberate surface steps keep dense tablet screens structured
    // without relying on shadows or colour effects.
    val Background = Color(0xFF08131B)
    val BackgroundSecondary = Color(0xFF0B1821)
    val Surface = Color(0xFF0F1D26)
    val SurfaceRaised = Color(0xFF162832)
    val SurfaceHover = Color(0xFF1A2E39)
    val SurfaceOverlay = Color(0xFF1D333F)

    // Legacy token names are retained to avoid screen-level churn. Together
    // they form one restrained brass family rather than multiple yellow CTAs.
    val Gold = Color(0xFFC6A15B)
    val GoldBright = Color(0xFFD2B675)
    val GoldMuted = Color(0xFFB99A5F)

    val Foreground = Color(0xFFF4F6F7)
    val ForegroundMuted = Color(0xFF9BA8B0)
    // Minimum 4.5:1 contrast on SurfaceOverlay for 11sp timestamps and hints.
    val ForegroundFaint = Color(0xFF8D9AA2)
    val Disabled = Color(0xFF65727A)

    // Semantic colours are deliberately independent of the brand accent.
    // State must always be paired with an icon or label in UI components.
    val Good = Color(0xFF43D17A)
    val GoodMuted = Color(0xFF123826)
    val Warning = Color(0xFFF3A83B)
    val WarningMuted = Color(0xFF3D2B12)
    val Danger = Color(0xFFFF6B70)
    val DangerMuted = Color(0xFF471F25)
    val Information = Color(0xFF5AA7FF)
    val InformationMuted = Color(0xFF142F4B)

    val Border = Color(0xFF314752)
    val BorderSubtle = Color(0xFF223640)
    // Actionable control boundaries must remain at least 3:1 against their
    // SurfaceRaised fill; decorative card borders intentionally stay quieter.
    val ControlBorder = Color(0xFF527483)
    val FocusRing = Gold
}

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

private val DCompanyColors = darkColorScheme(
    primary = Brand.Gold,
    onPrimary = Brand.Background,
    primaryContainer = Brand.Gold.copy(alpha = 0.16f),
    onPrimaryContainer = Brand.GoldBright,
    secondary = Brand.GoldMuted,
    onSecondary = Brand.Background,
    secondaryContainer = Brand.SurfaceHover,
    onSecondaryContainer = Brand.Foreground,
    tertiary = Brand.Information,
    onTertiary = Brand.Background,
    tertiaryContainer = Brand.InformationMuted,
    onTertiaryContainer = Brand.Information,
    background = Brand.Background,
    onBackground = Brand.Foreground,
    surface = Brand.Surface,
    onSurface = Brand.Foreground,
    surfaceVariant = Brand.SurfaceRaised,
    onSurfaceVariant = Brand.ForegroundMuted,
    error = Brand.Danger,
    onError = Brand.Background,
    errorContainer = Brand.DangerMuted,
    onErrorContainer = Brand.Danger,
    outline = Brand.Border,
    outlineVariant = Brand.BorderSubtle,
    inverseSurface = Brand.Foreground,
    inverseOnSurface = Brand.Background,
    inversePrimary = Brand.GoldMuted,
    surfaceTint = Color.Transparent,
    scrim = Color.Black.copy(alpha = 0.72f),
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

@Composable
fun DCompanyTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Always light system-bar icons: this UI is dark regardless of
            // the device's own light/dark setting. Deriving it from the
            // device theme is exactly the bug that made the clock and
            // battery invisible in the Capacitor build.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = DCompanyColors,
        typography = DCompanyTypography,
        shapes = DCompanyShapes,
        content = content,
    )
}
