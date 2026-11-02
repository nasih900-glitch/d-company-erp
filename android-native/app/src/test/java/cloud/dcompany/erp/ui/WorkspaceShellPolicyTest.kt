package cloud.dcompany.erp.ui

import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceShellPolicyTest {
    @Test
    fun `1280 by 800 landscape workspace uses the approved rail`() {
        assertTrue(usesExecutiveNavigationRail(1280.dp, 800.dp))
        assertEquals(184.dp, ExecutiveSidebarWidth)
        assertEquals(56.dp, ExecutiveUtilityHeight)
    }

    @Test
    fun `compact and portrait workspaces retain fallback navigation`() {
        assertFalse(usesExecutiveNavigationRail(840.dp, 600.dp))
        assertFalse(usesExecutiveNavigationRail(800.dp, 1280.dp))
    }

    @Test
    fun `rail filters its stable order through existing permission results`() {
        val allowed = listOf(
            Destination.Settings,
            Destination.Reports,
            Destination.Pos,
            Destination.Gaming,
            Destination.Help,
        )

        assertEquals(
            listOf(Destination.Gaming, Destination.Pos, Destination.Reports),
            executiveRailDestinations(allowed),
        )
        assertFalse(Destination.Settings in executiveRailDestinations(allowed))
        assertFalse(Destination.Dashboard in executiveRailDestinations(allowed))
        assertEquals("Home", executiveRailVisibleLabel(Destination.Dashboard))
        assertEquals("Inventory", executiveRailVisibleLabel(Destination.Inventory))
        assertEquals("Stock", Destination.Inventory.label)
    }

    @Test
    fun `workspace clock presentation retains the explicit system zone and minute`() {
        val now = ZonedDateTime.of(2026, 10, 23, 14, 5, 47, 0, ZoneId.of("Asia/Dubai"))

        assertEquals(
            WorkspaceDateTimePresentation(
                date = "Fri, 23 Oct",
                time = "2:05 PM",
                zoneId = "Asia/Dubai",
            ),
            workspaceDateTimePresentation(now),
        )
    }

    @Test
    fun `pending work compacts its status before clipping wide header controls`() {
        assertEquals(
            WorkspaceHeaderLayoutPolicy(searchWidth = 264.dp, showOutboxDetail = false),
            workspaceHeaderLayoutPolicy(1_096.dp, hasOutboxWork = true),
        )
        assertEquals(
            WorkspaceHeaderLayoutPolicy(searchWidth = 264.dp, showOutboxDetail = false),
            workspaceHeaderLayoutPolicy(920.dp, hasOutboxWork = true),
        )
        assertEquals(
            WorkspaceHeaderLayoutPolicy(searchWidth = 264.dp, showOutboxDetail = true),
            workspaceHeaderLayoutPolicy(1_104.dp, hasOutboxWork = true),
        )
        assertFalse(workspaceHeaderLayoutPolicy(920.dp, hasOutboxWork = false).showOutboxDetail)
        assertEquals(112.dp, homeAppearanceControlWidth(fontScale = 1f))
        assertEquals(136.dp, homeAppearanceControlWidth(fontScale = 1.3f))
        assertEquals(184.dp, homeDateTimeWidth(fontScale = 1f))
        assertEquals(208.dp, homeDateTimeWidth(fontScale = 1.3f))
        assertEquals(62.dp, homeTimeTextWidth(fontScale = 1f))
        assertEquals(76.dp, homeTimeTextWidth(fontScale = 1.3f))
    }
}
