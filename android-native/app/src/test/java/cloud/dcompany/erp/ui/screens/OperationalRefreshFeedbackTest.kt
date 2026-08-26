package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.ui.screens.kitchen.KitchenUiState
import cloud.dcompany.erp.ui.screens.tables.TablesUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies the exact state fields observed by the two operational screens. */
class OperationalRefreshFeedbackTest {

    @Test
    fun `Kitchen state exposes its own refresh failure as a blocking first-load error`() {
        val message = "Could not refresh kitchen data. Saved data is still available."
        val state = KitchenUiState(refreshError = message)

        assertEquals(message, state.refreshError)
        assertEquals(message, state.blockingLoadError)
        assertNull(state.error)
    }

    @Test
    fun `Tables state exposes its own refresh failure without turning it into an action error`() {
        val message = "Could not refresh tables data. Saved data is still available."
        val state = TablesUiState(refreshError = message)

        assertEquals(message, state.refreshError)
        assertEquals(message, state.blockingLoadError)
        assertNull(state.error)
    }
}
