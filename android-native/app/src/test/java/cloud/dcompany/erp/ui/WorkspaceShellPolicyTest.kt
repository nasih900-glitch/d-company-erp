package cloud.dcompany.erp.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceShellPolicyTest {
    @Test
    fun `1280 by 800 landscape workspace uses the approved rail`() {
        assertTrue(usesExecutiveNavigationRail(1280.dp, 800.dp))
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
    }
}
