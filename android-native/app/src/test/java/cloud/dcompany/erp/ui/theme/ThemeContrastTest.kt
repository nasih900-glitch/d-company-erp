package cloud.dcompany.erp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import cloud.dcompany.erp.ui.screens.customers.LeaderboardIdentityTintAlpha
import cloud.dcompany.erp.ui.screens.customers.LeaderboardRowTintAlpha
import cloud.dcompany.erp.ui.screens.customers.LeaderboardBronzeAccent
import cloud.dcompany.erp.ui.screens.customers.LeaderboardSilverAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun `classic night palette restores Build 45 identity and keeps the original mark`() {
        assertEquals(Color(0xFF08131B), NightPalette.Background)
        assertEquals(Color(0xFF0B1821), NightPalette.BackgroundSecondary)
        assertEquals(Color(0xFF0F1D26), NightPalette.Surface)
        assertEquals(Color(0xFF162832), NightPalette.SurfaceRaised)
        assertEquals(Color(0xFFF4F6F7), NightPalette.Foreground)
        assertEquals(Color(0xFFC6A15B), NightPalette.Gold)
        assertEquals(Color(0xFFD2B675), NightPalette.GoldBright)
        assertEquals(Color(0xFFC6A15B), DayPalette.BrandAccent)
        assertEquals(DayPalette.BrandAccent, NightPalette.BrandAccent)
    }

    @Test
    fun `classic semantic colours stay readable on their paired containers`() {
        assertEquals(Color(0xFFF3A83B), NightPalette.Warning)
        assertEquals(Color(0xFF5AA7FF), NightPalette.Information)
        listOf(NightPalette, DayPalette).forEach { palette ->
            assertTrue(contrastRatio(palette.Warning, palette.WarningMuted) >= 4.5f)
            assertTrue(contrastRatio(palette.Information, palette.InformationMuted) >= 4.5f)
            val goodContrast = contrastRatio(palette.Good, palette.GoodMuted)
            assertTrue("Success text/container contrast is $goodContrast, requires 4.5:1", goodContrast >= 4.5f)
            assertTrue(contrastRatio(palette.Danger, palette.DangerMuted) >= 4.5f)
        }
    }

    @Test
    fun `brass actions and selected labels remain readable in both appearances`() {
        listOf(NightPalette, DayPalette).forEach { palette ->
            assertTrue(contrastRatio(palette.Gold, palette.Background) >= 4.5f)
            val selectedSurface = palette.Gold.copy(alpha = 0.16f).compositeOver(palette.Surface)
            assertTrue(contrastRatio(palette.GoldBright, selectedSurface) >= 4.5f)
            assertTrue(contrastRatio(palette.GoldMuted, palette.Background) >= 4.5f)
        }
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
                val rowStart = medal.copy(alpha = LeaderboardRowTintAlpha).compositeOver(palette.Surface)
                val identityBadge = medal.copy(alpha = LeaderboardIdentityTintAlpha).compositeOver(rowStart)
                assertTrue(contrastRatio(palette.Foreground, rowStart) >= 7f)
                val badgeContrast = contrastRatio(palette.Foreground, identityBadge)
                assertTrue("Medal badge text contrast is $badgeContrast, requires 7:1", badgeContrast >= 7f)
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
