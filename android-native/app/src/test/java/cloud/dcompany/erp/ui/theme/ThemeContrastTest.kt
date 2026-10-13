package cloud.dcompany.erp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import cloud.dcompany.erp.ui.screens.customers.LeaderboardBronzeAccent
import cloud.dcompany.erp.ui.screens.customers.LeaderboardSilverAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun `night and day palettes keep the approved monochrome foundation`() {
        assertEquals(Color(0xFF080808), NightPalette.Background)
        assertEquals(Color(0xFF111111), NightPalette.Surface)
        assertEquals(Color(0xFFF7F7F4), NightPalette.Foreground)
        assertEquals(Color(0xFFF6F6F4), DayPalette.Background)
        assertEquals(Color(0xFF161615), DayPalette.Gold)
        assertEquals(Color(0xFF111110), DayPalette.Foreground)
    }

    @Test
    fun `surface ladders remain ordered in both appearances`() {
        assertOrderedAscending(listOf(
            NightPalette.Background,
            NightPalette.BackgroundSecondary,
            NightPalette.Surface,
            NightPalette.SurfaceRaised,
            NightPalette.SurfaceHover,
            NightPalette.SurfaceOverlay,
        ))
        assertOrderedDescending(listOf(
            DayPalette.BackgroundSecondary,
            DayPalette.Background,
            DayPalette.SurfaceRaised,
            DayPalette.SurfaceHover,
        ))
    }

    @Test
    fun `body and muted text remain readable on core surfaces`() {
        listOf(NightPalette, DayPalette).forEach { palette ->
            listOf(palette.Background, palette.Surface, palette.SurfaceRaised).forEach { surface ->
                assertTrue(contrastRatio(palette.Foreground, surface) >= 7f)
                assertTrue(contrastRatio(palette.ForegroundMuted, surface) >= 4.5f)
            }
        }
    }

    @Test
    fun `focus and control boundaries remain distinct`() {
        listOf(NightPalette, DayPalette).forEach { palette ->
            assertTrue(contrastRatio(palette.ControlBorder, palette.SurfaceRaised) >= 3f)
            assertTrue(contrastRatio(palette.FocusRing, palette.SurfaceOverlay) >= 3f)
        }
    }

    @Test
    fun `leaderboard primary text stays readable across medal tinted rows in day and night`() {
        listOf(NightPalette, DayPalette).forEach { palette ->
            listOf(LeaderboardSilverAccent, LeaderboardBronzeAccent).forEach { medal ->
                val rowStart = medal.copy(alpha = 0.17f).compositeOver(palette.Surface)
                val identityBadge = medal.copy(alpha = 0.20f).compositeOver(rowStart)
                assertTrue(contrastRatio(palette.Foreground, rowStart) >= 7f)
                assertTrue(contrastRatio(palette.Foreground, identityBadge) >= 7f)
            }
        }
    }
}

private fun assertOrderedAscending(colors: List<Color>) {
    colors.zipWithNext().forEach { (lower, higher) ->
        assertTrue(lower.luminance() < higher.luminance())
    }
}

private fun assertOrderedDescending(colors: List<Color>) {
    colors.zipWithNext().forEach { (higher, lower) ->
        assertTrue(higher.luminance() > lower.luminance())
    }
}

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}
