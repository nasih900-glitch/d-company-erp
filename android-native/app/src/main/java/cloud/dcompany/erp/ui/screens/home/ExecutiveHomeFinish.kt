package cloud.dcompany.erp.ui.screens.home

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Read-only presentation insets supplied by the Dashboard-only wide shell. */
internal data class ExecutiveHomeShellLayout(
    val enabled: Boolean = false,
    val railWidth: Dp = 0.dp,
    val utilityHeight: Dp = 0.dp,
    val noticeHeight: Dp = 0.dp,
)

internal val LocalExecutiveHomeShellLayout = staticCompositionLocalOf { ExecutiveHomeShellLayout() }

/** Keeps muted status copy at >= 4.5:1 even when the underlying hero pixel is white. */
internal const val ExecutiveHomeStatusGlassMinimumBlackAlpha = 0.74f

/** Keeps the disabled refresh indicator distinguishable over the brightest possible hero pixel. */
internal const val ExecutiveHomeRefreshContainerAlpha = 0.72f

internal object ExecutiveHomeFinish {
    val Canvas = Color(0xFF00080F)
    val CanvasRaised = Color(0xFF020C15)
    val Rail = Color(0xF200080E)
    val Utility = Color(0xB500080E)
    val CardTop = Color(0xF20B131E)
    val CardBottom = Color(0xF208111B)
    val PanelTop = Color(0xF2060F18)
    val PanelBottom = Color(0xF2040A11)
    val SelectedTop = Color(0xF21B2026)
    val SelectedBottom = Color(0xF20B1119)
    val Gold = Color(0xFFE8BD62)
    val Lavender = Color(0xFFCEBDFF)
    val Text = Color(0xFFF4F6FA)
    val TextMuted = Color(0xFFEEF2F8)
    val TextQuiet = Color(0xFFEEF2F8)
    val Outline = Color(0xFF243247)
    val InnerHighlight = Color.White.copy(alpha = 0.075f)
    val SelectedOutline = Color(0xFFCF9E47)
    val ErrorOnDark = Color(0xFFFFB4AB)

    fun canvasBrush(): Brush = Brush.verticalGradient(listOf(CanvasRaised, Canvas))
    fun cardBrush(): Brush = Brush.verticalGradient(listOf(CardTop, CardBottom))
    fun panelBrush(): Brush = Brush.verticalGradient(listOf(PanelTop, PanelBottom))
    fun selectedBrush(): Brush = Brush.verticalGradient(listOf(SelectedTop, SelectedBottom))
}

internal data class ExecutiveHomePalette(
    val canvas: Color,
    val canvasRaised: Color,
    val rail: Color,
    val utility: Color,
    val cardTop: Color,
    val cardBottom: Color,
    val panelTop: Color,
    val panelBottom: Color,
    val selectedTop: Color,
    val selectedBottom: Color,
    val gold: Color,
    val lavender: Color,
    val text: Color,
    val textMuted: Color,
    val textQuiet: Color,
    val outline: Color,
    val innerHighlight: Color,
    val selectedOutline: Color,
    val error: Color,
) {
    fun canvasBrush(): Brush = Brush.verticalGradient(listOf(canvasRaised, canvas))
    fun cardBrush(): Brush = Brush.verticalGradient(listOf(cardTop, cardBottom))
    fun panelBrush(): Brush = Brush.verticalGradient(listOf(panelTop, panelBottom))
    fun selectedBrush(): Brush = Brush.verticalGradient(listOf(selectedTop, selectedBottom))
}

private val NightExecutiveHomePalette = ExecutiveHomePalette(
    canvas = ExecutiveHomeFinish.Canvas,
    canvasRaised = ExecutiveHomeFinish.CanvasRaised,
    rail = ExecutiveHomeFinish.Rail,
    utility = ExecutiveHomeFinish.Utility,
    cardTop = ExecutiveHomeFinish.CardTop,
    cardBottom = ExecutiveHomeFinish.CardBottom,
    panelTop = ExecutiveHomeFinish.PanelTop,
    panelBottom = ExecutiveHomeFinish.PanelBottom,
    selectedTop = ExecutiveHomeFinish.SelectedTop,
    selectedBottom = ExecutiveHomeFinish.SelectedBottom,
    gold = ExecutiveHomeFinish.Gold,
    lavender = ExecutiveHomeFinish.Lavender,
    text = ExecutiveHomeFinish.Text,
    textMuted = ExecutiveHomeFinish.TextMuted,
    textQuiet = ExecutiveHomeFinish.TextMuted,
    outline = ExecutiveHomeFinish.Outline,
    innerHighlight = ExecutiveHomeFinish.InnerHighlight,
    selectedOutline = ExecutiveHomeFinish.SelectedOutline,
    error = ExecutiveHomeFinish.ErrorOnDark,
)

private val DayExecutiveHomePalette = ExecutiveHomePalette(
    canvas = Color(0xFFF0F3F6),
    canvasRaised = Color(0xFFF8FAFC),
    rail = Color(0xFFF7F9FB),
    utility = Color(0xE6FFFFFF),
    cardTop = Color(0xFFFFFFFF),
    cardBottom = Color(0xFFF0F4F8),
    panelTop = Color(0xFFF8FAFC),
    panelBottom = Color(0xFFE9EFF5),
    selectedTop = Color(0xFFFFF8E8),
    selectedBottom = Color(0xFFF2E4C1),
    gold = Color(0xFF76530C),
    lavender = Color(0xFF5F4C9B),
    text = Color(0xFF101824),
    textMuted = Color(0xFF34445A),
    textQuiet = Color(0xFF34445A),
    outline = Color(0xFFC6D1DD),
    innerHighlight = Color.White.copy(alpha = 0.72f),
    selectedOutline = Color(0xFF9A722A),
    error = Color(0xFFA5272D),
)

/** Home-only finish; the surrounding application theme remains untouched. */
@Composable
internal fun executiveHomePalette(): ExecutiveHomePalette =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        DayExecutiveHomePalette
    } else {
        NightExecutiveHomePalette
    }

internal object ExecutiveHomeType {
    val Rail = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val Utility = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val Greeting = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
    val HeroMeta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val KpiLabel = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    val KpiValue = TextStyle(fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
    val KpiDetail = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal)
    val Shortcut = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val ShortcutMeta = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal)
    val PanelTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val PanelBody = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
}
