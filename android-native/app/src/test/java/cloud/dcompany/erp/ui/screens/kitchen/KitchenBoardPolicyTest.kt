package cloud.dcompany.erp.ui.screens.kitchen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitchenBoardPolicyTest {
    @Test
    fun `stale warning remains visible when the cached board is empty`() {
        val state = KitchenUiState(
            orders = emptyList(),
            everSynced = true,
            lastSyncedAtMillis = 1_000L,
            nowMillis = 20_000L,
        )

        assertTrue(state.stale)
        assertTrue(shouldShowKitchenStaleWarning(state))
    }

    @Test
    fun `explicit queue error suppresses duplicate stale warning`() {
        val state = KitchenUiState(
            orders = emptyList(),
            refreshError = "Connection unavailable",
            everSynced = true,
            lastSyncedAtMillis = 1_000L,
            nowMillis = 20_000L,
        )

        assertTrue(state.stale)
        assertFalse(shouldShowKitchenStaleWarning(state))
    }

    @Test
    fun `unknown-state cancellation ticket is assigned to exactly one active section`() {
        val cancellationOnly = KitchenOrder(
            id = "order-unknown-cancellation",
            type = "dine_in",
            kitchenState = "future_backend_state",
            lines = emptyList(),
            pendingCancellations = listOf(
                KitchenCancellation(
                    lineId = "line-1",
                    menuItemId = "menu-1",
                    name = "Fries",
                    type = "food",
                    qty = 1.0,
                    releasedAt = "2026-08-26T10:00:00Z",
                    roundNo = 1,
                    voidedAt = "2026-08-26T10:01:00Z",
                    reason = "Guest changed order",
                ),
            ),
        )
        val state = KitchenUiState(
            // A duplicate source row must not become a duplicate Compose key either.
            orders = listOf(cancellationOnly, cancellationOnly.copy()),
            includeServed = false,
        )

        val sections = buildKitchenBoardSections(state)
        val renderedOrders = sections.flatMap(KitchenBoardSection::orders)

        assertEquals(listOf("order-unknown-cancellation"), renderedOrders.map(KitchenOrder::id))
        assertEquals(
            listOf("order-unknown-cancellation"),
            sections.single { it.title == "Cancellations" }.orders.map(KitchenOrder::id),
        )
        assertFalse(sections.any { it.title == "Other" })
    }

    @Test
    fun `served-history policy keeps unknown cancellation ticket once in Other`() {
        val ticket = KitchenOrder(
            id = "order-unknown-history",
            type = "dine_in",
            kitchenState = "future_backend_state",
            pendingCancellations = listOf(
                KitchenCancellation(
                    lineId = "line-2",
                    menuItemId = "menu-2",
                    name = "Tea",
                    type = "drink",
                    qty = 1.0,
                    releasedAt = "2026-08-26T11:00:00Z",
                    roundNo = 2,
                    voidedAt = "2026-08-26T11:01:00Z",
                    reason = "Made by mistake",
                ),
            ),
        )

        val sections = buildKitchenBoardSections(
            KitchenUiState(orders = listOf(ticket), includeServed = true),
        )

        assertEquals(listOf(ticket), sections.single { it.title == "Other" }.orders)
        assertEquals(1, sections.flatMap(KitchenBoardSection::orders).size)
    }
}
