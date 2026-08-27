package cloud.dcompany.erp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun `small and status text tokens remain readable on overlay surfaces`() {
        listOf(
            "ForegroundFaint" to Brand.ForegroundFaint,
            "GoldMuted" to Brand.GoldMuted,
            "Danger" to Brand.Danger,
        ).forEach { (name, color) ->
            val ratio = contrastRatio(color, Brand.SurfaceOverlay)
            assertTrue("$name contrast was $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun `filled action content meets small-text contrast`() {
        listOf(
            "Primary action" to (Brand.Background to Brand.Gold),
            "Destructive action" to (Brand.Background to Brand.Danger),
        ).forEach { (name, colors) ->
            val (content, container) = colors
            val ratio = contrastRatio(content, container)
            assertTrue("$name contrast was $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun `operational banner tones meet small-text contrast`() {
        listOf(
            "Offline warning" to (Brand.Warning to Brand.WarningMuted),
            "Server error" to (Brand.Danger to Brand.DangerMuted),
            "View-only information" to (Brand.Information to Brand.SurfaceRaised),
            "View-only detail" to (Brand.ForegroundMuted to Brand.SurfaceRaised),
        ).forEach { (name, colors) ->
            val (content, container) = colors
            val ratio = contrastRatio(content, container)
            assertTrue("$name contrast was $ratio", ratio >= 4.5f)
        }
    }
}

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}
