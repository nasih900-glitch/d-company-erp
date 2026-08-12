package cloud.dcompany.erp.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * D Company palette, taken from the existing web/iOS apps so all three
 * surfaces look like the same product: near-black background with the gold
 * brand accent.
 */
object Brand {
    val Background = Color(0xFF050403)
    val Surface = Color(0xFF0F0C0A)
    val SurfaceRaised = Color(0xFF17120E)
    val Gold = Color(0xFFD2B36D)
    val GoldMuted = Color(0xFF8A7645)
    val Foreground = Color(0xFFF5EFE4)
    val ForegroundMuted = Color(0xFF9C9184)
    val Danger = Color(0xFFE5484D)
    val Good = Color(0xFF46A758)
    val Border = Color(0xFF2A2119)
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
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
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
