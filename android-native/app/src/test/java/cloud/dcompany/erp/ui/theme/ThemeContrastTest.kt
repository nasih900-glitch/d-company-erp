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
}

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}
