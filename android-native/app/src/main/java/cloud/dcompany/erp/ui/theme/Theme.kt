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
import androidx.compose.ui.graphics.Brush
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
 * D Company palette, taken from the existing web/iOS apps so all three
 * surfaces look like the same product: near-black background with the gold
 * brand accent.
 *
 * Extended with a tonal-elevation ladder, a gradient accent, and softer
 * secondary text tiers — a flat "Surface vs SurfaceRaised" pair reads as
 * plain once dialogs, nested cards, and pressed states all need to look
 * distinct from each other.
 */
object Brand {
    // Neutral navy surfaces match the approved operational dashboard.  They
    // keep large tablet screens calm and let semantic status colours carry
    // meaning without competing with a brown/yellow cast across the app.
    val Background = Color(0xFF061016)
    val BackgroundSecondary = Color(0xFF08141B)
    val Surface = Color(0xFF0A171E)
    val SurfaceRaised = Color(0xFF0E1E27)
    val SurfaceHover = Color(0xFF132731)
    // One tier above SurfaceRaised — dialogs and popovers floating over an
    // already-raised card need their own step, or they visually merge into it.
    val SurfaceOverlay = Color(0xFF152A35)
    val Gold = Color(0xFFF2B63D)
    val GoldBright = Color(0xFFFFD166)
    // Status/help copy uses this token on cards and dialogs, not just the base
    // background. Keep it muted relative to Gold while meeting 4.5:1 on the
    // darkest raised surface.
    val GoldMuted = Color(0xFFC99B3D)
    val Foreground = Color(0xFFF3F7F8)
    val ForegroundMuted = Color(0xFFAAB7BD)
    // A third text tier for genuinely tertiary labels (timestamps, hints) —
    // ForegroundMuted was doing double duty as both "secondary" and "barely
    // visible," which flattened the hierarchy on text-dense screens.
    // Still visibly tertiary, but no longer below WCAG AA: #8C8170 remains
    // at least 4.58:1 even on SurfaceOverlay, where timestamps and recovery
    // hints are commonly rendered at 11sp.
    val ForegroundFaint = Color(0xFF8E9DA4)
    val Disabled = Color(0xFF56656C)

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

    val Border = Color(0xFF29404B)
    val BorderSubtle = Color(0xFF172C36)

    /** The one recurring "premium" accent — a gold sheen on primary actions
     * and selected nav state, instead of a flat fill. Used sparingly: one
     * hero surface per screen, not every gold-colored element. */
    val GoldSheen = Brush.linearGradient(listOf(GoldBright, Gold))

    /** A faint top-down fade used behind headers/hero panels so they read as
     * sitting slightly above the base background rather than flush with it. */
    fun surfaceFade(top: Color = SurfaceRaised, bottom: Color = Surface) =
        Brush.verticalGradient(listOf(top, bottom))
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
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineLarge = PreciseTextStyle.copy(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.35).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = PreciseTextStyle.copy(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineSmall = PreciseTextStyle.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = PreciseTextStyle.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    titleMedium = PreciseTextStyle.copy(
        fontSize = 17.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    titleSmall = PreciseTextStyle.copy(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    bodyLarge = PreciseTextStyle.copy(fontSize = 16.sp, lineHeight = 23.sp, fontFeatureSettings = "tnum"),
    bodyMedium = PreciseTextStyle.copy(fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    bodySmall = PreciseTextStyle.copy(fontSize = 12.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum"),
    labelLarge = PreciseTextStyle.copy(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    labelMedium = PreciseTextStyle.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = PreciseTextStyle.copy(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
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
