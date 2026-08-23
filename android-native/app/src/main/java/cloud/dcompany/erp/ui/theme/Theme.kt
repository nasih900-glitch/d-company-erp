package cloud.dcompany.erp.ui.theme

import android.app.Activity
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
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
    val Background = Color(0xFF050403)
    val Surface = Color(0xFF0F0C0A)
    val SurfaceRaised = Color(0xFF17120E)
    // One tier above SurfaceRaised — dialogs and popovers floating over an
    // already-raised card need their own step, or they visually merge into it.
    val SurfaceOverlay = Color(0xFF201810)
    val Gold = Color(0xFFD2B36D)
    val GoldBright = Color(0xFFEACB93)
    val GoldMuted = Color(0xFF8A7645)
    val Foreground = Color(0xFFF5EFE4)
    val ForegroundMuted = Color(0xFF9C9184)
    // A third text tier for genuinely tertiary labels (timestamps, hints) —
    // ForegroundMuted was doing double duty as both "secondary" and "barely
    // visible," which flattened the hierarchy on text-dense screens.
    val ForegroundFaint = Color(0xFF6C6355)
    val Danger = Color(0xFFE5484D)
    val DangerMuted = Color(0xFF5C2A2C)
    val Good = Color(0xFF46A758)
    val GoodMuted = Color(0xFF1F3D26)
    val Border = Color(0xFF2A2119)
    val BorderSubtle = Color(0xFF1A1510)

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
    val xl = 24.dp
    val xxl = 32.dp
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
    secondary = Brand.GoldMuted,
    background = Brand.Background,
    onBackground = Brand.Foreground,
    surface = Brand.Surface,
    onSurface = Brand.Foreground,
    surfaceVariant = Brand.SurfaceRaised,
    onSurfaceVariant = Brand.ForegroundMuted,
    error = Brand.Danger,
    outline = Brand.Border,
)

private val DCompanyTypography = Typography(
    displayLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
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
        content = content,
    )
}
