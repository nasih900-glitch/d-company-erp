package cloud.dcompany.erp.ui.screens.kitchen

import cloud.dcompany.erp.core.net.OrderModifierSnapshot
import cloud.dcompany.erp.core.net.OrderVariantSnapshot
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
        )

        assertTrue(kitchenFreshness(state.lastSyncedAtMillis, 20_000L).stale)
        assertTrue(shouldShowKitchenStaleWarning(state, nowMillis = 20_000L))
    }

    @Test
    fun `explicit queue error suppresses duplicate stale warning`() {
        val state = KitchenUiState(
            orders = emptyList(),
            refreshError = "Connection unavailable",
            everSynced = true,
            lastSyncedAtMillis = 1_000L,
        )

        assertTrue(kitchenFreshness(state.lastSyncedAtMillis, 20_000L).stale)
        assertFalse(shouldShowKitchenStaleWarning(state, nowMillis = 20_000L))
    }

    @Test
    fun `freshness clock is independent from immutable queue state`() {
        val state = KitchenUiState(lastSyncedAtMillis = 1_000L)

        assertFalse(kitchenFreshness(state.lastSyncedAtMillis, 15_000L).stale)
        assertTrue(kitchenFreshness(state.lastSyncedAtMillis, 20_000L).stale)
        assertEquals(19L, kitchenFreshness(state.lastSyncedAtMillis, 20_000L).secondsSinceSync)
    }

    @Test
    fun `post advance deadline locks taps without a per second state clock`() {
        assertFalse(KitchenUiState().tapsLocked)
        assertTrue(KitchenUiState(busyOrderId = "order-1").tapsLocked)
        assertTrue(KitchenUiState(advanceLockedUntilMillis = 20_000L).tapsLocked)
    }

    @Test
    fun `kitchen option labels preserve variant and modifier quantities`() {
        assertEquals(
            listOf("Large", "Oat milk", "2× Extra shot"),
            kitchenOptionLabels(
                variant = OrderVariantSnapshot("variant-large", " Large "),
                modifiers = listOf(
                    OrderModifierSnapshot(modifierId = "oat", name = "Oat milk"),
                    OrderModifierSnapshot(modifierId = "shot", name = "Extra shot", qty = 2),
                ),
            ),
        )
    }

    @Test
    fun `loading or failed history never falls back to active cache tickets`() {
        val active = listOf(kitchenOrder("active-1", "received"))
        val loading = beginKitchenHistoryLoad(KitchenHistorySnapshot())
        val failed = failKitchenHistoryLoad(loading, "Offline")

        assertTrue(
            visibleKitchenOrders(
                includeServed = true,
                activeOrders = active,
                history = loading,
            ).isEmpty(),
        )
        assertTrue(
            visibleKitchenOrders(
                includeServed = true,
                activeOrders = active,
                history = failed,
            ).isEmpty(),
        )
        assertEquals(KitchenHistoryStatus.LOADING, loading.status)
        assertEquals(KitchenHistoryStatus.FAILED, failed.status)
    }

    @Test
    fun `loaded history is the only source used by served mode`() {
        val active = listOf(kitchenOrder("active-1", "received"))
        val historyOrders = listOf(kitchenOrder("served-1", "served"))
        val history = KitchenHistorySnapshot(
            status = KitchenHistoryStatus.LOADED,
            orders = historyOrders,
        )

        assertEquals(
            historyOrders,
            visibleKitchenOrders(includeServed = true, activeOrders = active, history = history),
        )
        assertEquals(
            active,
            visibleKitchenOrders(includeServed = false, activeOrders = active, history = history),
        )
    }

    @Test
    fun `failed background history refresh keeps the last loaded history with warning`() {
        val served = listOf(kitchenOrder("served-1", "served"))
        val loaded = KitchenHistorySnapshot(
            status = KitchenHistoryStatus.LOADED,
            orders = served,
        )

        val refreshing = beginKitchenHistoryLoad(loaded)
        val failed = failKitchenHistoryLoad(loaded, "Connection unavailable")

        assertTrue(refreshing.refreshing)
        assertEquals(KitchenHistoryStatus.LOADED, failed.status)
        assertEquals(served, failed.orders)
        assertEquals("Connection unavailable", failed.error)
    }

    @Test
    fun `active queue stale warning is suppressed in served history mode`() {
        val state = KitchenUiState(
            includeServed = true,
            historyStatus = KitchenHistoryStatus.LOADED,
            lastSyncedAtMillis = 1_000L,
        )

        assertFalse(shouldShowKitchenStaleWarning(state, nowMillis = 20_000L))
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

    private fun kitchenOrder(id: String, state: String) = KitchenOrder(
        id = id,
        type = "dine_in",
        kitchenState = state,
    )
}
