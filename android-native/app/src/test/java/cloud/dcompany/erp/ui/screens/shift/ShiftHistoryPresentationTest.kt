package cloud.dcompany.erp.ui.screens.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftHistoryPresentationTest {

    @Test
    fun `unrelated resource error is never attributed to shift history`() {
        val unrelatedInventoryError = "Inventory upload failed"
        val message = shiftHistoryStatusMessage(
            online = true,
            refreshing = false,
            lastSyncMillis = 900_000L,
            historyError = null,
            hasRows = true,
            nowMillis = 1_000_000L,
        )

        assertFalse(message.orEmpty().contains(unrelatedInventoryError))
        assertEquals("Shift history updated 1 min ago.", message)
    }

    @Test
    fun `failed refresh with cache identifies saved age and exact resource`() {
        val message = shiftHistoryStatusMessage(
            online = true,
            refreshing = false,
            lastSyncMillis = 700_000L,
            historyError = "Server unavailable",
            hasRows = true,
            nowMillis = 1_000_000L,
        )

        assertTrue(message.orEmpty().contains("showing saved history updated 5 min ago"))
        assertTrue(message.orEmpty().contains("Server unavailable"))
    }

    @Test
    fun `offline never-downloaded is distinct from offline stale cache`() {
        assertTrue(
            shiftHistoryStatusMessage(
                online = false,
                refreshing = false,
                lastSyncMillis = null,
                historyError = null,
                hasRows = false,
                nowMillis = 1_000_000L,
            ).orEmpty().contains("never been downloaded"),
        )
        assertTrue(
            shiftHistoryStatusMessage(
                online = false,
                refreshing = false,
                lastSyncMillis = 940_000L,
                historyError = null,
                hasRows = true,
                nowMillis = 1_000_000L,
            ).orEmpty().contains("updated 1 min ago"),
        )
    }
}
