package cloud.dcompany.erp.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosViewportPolicyTest {

    @Test
    fun `target tablet keeps equal readable catalogue and checkout panels`() {
        // 960dp control deck has bottom navigation, no rail; subtract 24dp POS padding.
        val workspace = posWorkspaceMetrics(maxWidth = 936.dp)

        assertTrue(workspace.sideBySide)
        assertEquals(462f, requireNotNull(workspace.cartWidth).value, 0.01f)
        assertEquals(462f, workspace.productWidth.value, 0.01f)
        assertEquals(
            0.5f,
            requireNotNull(workspace.cartWidth).value /
                (workspace.productWidth.value + requireNotNull(workspace.cartWidth).value),
            0.01f,
        )
        assertEquals(2, posProductColumnCount(workspace.productWidth))
    }

    @Test
    fun `larger workspaces preserve equal checkout width while adding product columns`() {
        // 1280dp control deck has bottom navigation, no rail; subtract 24dp POS padding.
        val workspace = posWorkspaceMetrics(maxWidth = 1_256.dp)

        assertTrue(workspace.sideBySide)
        assertEquals(622f, requireNotNull(workspace.cartWidth).value, 0.01f)
        assertEquals(3, posProductColumnCount(workspace.productWidth))

        val desktop = posWorkspaceMetrics(maxWidth = 1_600.dp)
        assertEquals(794f, requireNotNull(desktop.cartWidth).value, 0.01f)
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

    @Test
    fun `catalogue cannot mutate a hidden cart while another checkout context is visible`() {
        fun enabled(
            heldReview: Boolean = false,
            preparedHeld: Boolean = false,
            directReview: Boolean = false,
            preparedDirect: Boolean = false,
            receipt: Boolean = false,
        ) = posCatalogueEditingEnabled(
            canCreateAndCollect = true,
            checkoutBusy = false,
            heldOrderReviewOpen = heldReview,
            preparedHeldCheckoutOpen = preparedHeld,
            directCheckoutReviewOpen = directReview,
            preparedDirectCheckoutOpen = preparedDirect,
            receiptVisible = receipt,
        )

        assertTrue(enabled())
        assertFalse(enabled(heldReview = true))
        assertFalse(enabled(preparedHeld = true))
        assertFalse(enabled(directReview = true))
        assertFalse(enabled(preparedDirect = true))
        assertFalse(enabled(receipt = true))
        assertFalse(
            posCatalogueEditingEnabled(
                canCreateAndCollect = true,
                checkoutBusy = true,
                heldOrderReviewOpen = false,
                preparedHeldCheckoutOpen = false,
                directCheckoutReviewOpen = false,
                preparedDirectCheckoutOpen = false,
                receiptVisible = false,
            ),
        )
    }
}
