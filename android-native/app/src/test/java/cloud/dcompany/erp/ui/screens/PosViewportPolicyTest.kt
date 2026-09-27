package cloud.dcompany.erp.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosViewportPolicyTest {

    @Test
    fun `target tablet keeps a sixty four thirty six workspace and three product columns`() {
        // 960dp control deck has bottom navigation, no rail; subtract 24dp POS padding.
        val workspace = posWorkspaceMetrics(maxWidth = 936.dp)

        assertTrue(workspace.sideBySide)
        assertEquals(336.96f, requireNotNull(workspace.cartWidth).value, 0.01f)
        assertEquals(587.04f, workspace.productWidth.value, 0.01f)
        assertEquals(
            0.36f,
            requireNotNull(workspace.cartWidth).value /
                (workspace.productWidth.value + requireNotNull(workspace.cartWidth).value),
            0.01f,
        )
        assertEquals(3, posProductColumnCount(workspace.productWidth))
    }

    @Test
    fun `larger tablet uses four columns without an oversized checkout`() {
        // 1280dp control deck has bottom navigation, no rail; subtract 24dp POS padding.
        val workspace = posWorkspaceMetrics(maxWidth = 1_256.dp)

        assertTrue(workspace.sideBySide)
        assertEquals(400f, requireNotNull(workspace.cartWidth).value, 0.01f)
        assertEquals(4, posProductColumnCount(workspace.productWidth))

        val desktop = posWorkspaceMetrics(maxWidth = 1_600.dp)
        assertEquals(400f, requireNotNull(desktop.cartWidth).value, 0.01f)
        assertEquals(4, posProductColumnCount(desktop.productWidth))
    }

    @Test
    fun `narrow workspace stacks panels instead of forcing an unusable cart`() {
        val workspace = posWorkspaceMetrics(maxWidth = 719.dp)

        assertFalse(workspace.sideBySide)
        assertNull(workspace.cartWidth)
        assertEquals(719.dp, workspace.productWidth)
    }

    @Test
    fun `held notification reveals only its exact visible order`() {
        val visible = setOf("table-4", "gaming-2")

        assertTrue(shouldRevealHeldOrderQueue("table-4", visible))
        assertFalse(shouldRevealHeldOrderQueue("removed-order", visible))
        assertFalse(shouldRevealHeldOrderQueue(null, visible))
    }
}
