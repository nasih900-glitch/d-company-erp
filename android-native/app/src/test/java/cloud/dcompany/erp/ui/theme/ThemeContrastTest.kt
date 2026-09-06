package cloud.dcompany.erp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun `executive navy palette remains the approved neutral foundation`() {
        assertEquals(Color(0xFF08131B), Brand.Background)
        assertEquals(Color(0xFF0F1D26), Brand.Surface)
        assertEquals(Color(0xFF162832), Brand.SurfaceRaised)
        assertEquals(Color(0xFFC6A15B), Brand.Gold)
        assertEquals(Color(0xFFF4F6F7), Brand.Foreground)
        assertEquals(Color(0xFF9BA8B0), Brand.ForegroundMuted)
    }

    @Test
    fun `surface ladder increases luminance without decorative effects`() {
        val surfaces = listOf(
            Brand.Background,
            Brand.BackgroundSecondary,
            Brand.Surface,
            Brand.SurfaceRaised,
            Brand.SurfaceHover,
            Brand.SurfaceOverlay,
        )
        surfaces.zipWithNext().forEach { (lower, higher) ->
            assertTrue("Surface ladder must remain ordered", lower.luminance() < higher.luminance())
        }
    }

    @Test
    fun `interactive boundaries are distinct while decorative borders stay restrained`() {
        assertTrue(contrastRatio(Brand.ControlBorder, Brand.SurfaceRaised) >= 3f)
        assertTrue(contrastRatio(Brand.FocusRing, Brand.SurfaceOverlay) >= 3f)
        assertTrue(contrastRatio(Brand.BorderSubtle, Brand.Surface) < 3f)
    }

    @Test
    fun `small and status text tokens remain readable on overlay surfaces`() {
        listOf(
            "ForegroundMuted" to Brand.ForegroundMuted,
            "ForegroundFaint" to Brand.ForegroundFaint,
            "GoldMuted" to Brand.GoldMuted,
            "Danger" to Brand.Danger,
        ).forEach { (name, color) ->
            val ratio = contrastRatio(color, Brand.SurfaceOverlay)
            assertTrue("$name contrast was $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun `body text remains readable across every business surface`() {
        listOf(
            Brand.Background,
            Brand.BackgroundSecondary,
            Brand.Surface,
            Brand.SurfaceRaised,
            Brand.SurfaceHover,
            Brand.SurfaceOverlay,
        ).forEach { surface ->
            assertTrue(contrastRatio(Brand.Foreground, surface) >= 7f)
            assertTrue(contrastRatio(Brand.ForegroundMuted, surface) >= 4.5f)
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
