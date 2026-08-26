package cloud.dcompany.erp.ui.screens.kitchen

import cloud.dcompany.erp.core.db.KitchenOrderCacheEntity
import cloud.dcompany.erp.core.db.LocalKitchenAdvanceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KitchenSyncActionsTest {
    @Test
    fun `active KDS polling performs only the kitchen queue pull`() {
        val calls = mutableListOf<String>()
        val actions = actions(calls)

        actions.refresh(includeServed = false)

        assertEquals(listOf("pull-active-kitchen"), calls)
    }

    @Test
    fun `queued advance performs only the kitchen scoped outbox drain`() {
        val calls = mutableListOf<String>()
        val actions = actions(calls)

        actions.advancesQueued()

        assertEquals(listOf("drain-kitchen-outbox"), calls)
    }

    @Test
    fun `served history remains a direct kitchen history pull`() {
        val calls = mutableListOf<String>()
        val actions = actions(calls)

        actions.refresh(includeServed = true)

        assertEquals(listOf("pull-kitchen-history"), calls)
    }

    @Test
    fun `served ticket with no active lines remains visible as cancellation-only work`() {
        val cancellation = KitchenCancellation(
            lineId = "line-1",
            menuItemId = "menu-1",
            name = "Fries",
            type = "food",
            qty = 1.0,
            releasedAt = "2026-08-26T10:00:00Z",
            roundNo = 2,
            voidedAt = "2026-08-26T10:01:00Z",
            reason = "Guest changed order",
        )
        val cached = KitchenOrderCacheEntity(
            id = "order-1",
            type = "dine_in",
            tableCode = "T1",
            kitchenState = "served",
            lines = emptyList(),
            pendingCancellations = listOf(cancellation),
        )
        val orders = mergeCacheWithPending(listOf(cached), pending = emptyList())
        val ticket = orders.single()

        val state = KitchenUiState(orders = orders)

        assertEquals(listOf(ticket), state.cancellationOnly)
        assertTrue(state.lane(KitchenState.RECEIVED).isEmpty())
    }

    @Test
    fun `ordinary served ticket still leaves the active board`() {
        val cached = KitchenOrderCacheEntity(
            id = "order-2",
            type = "dine_in",
            tableCode = "T2",
            kitchenState = "ready",
        )
        val servedAdvance = LocalKitchenAdvanceEntity(
            localId = "advance-2",
            orderId = cached.id,
            targetState = "served",
            requestedAtMillis = 1L,
        )

        assertTrue(mergeCacheWithPending(listOf(cached), listOf(servedAdvance)).isEmpty())
    }

    private fun actions(calls: MutableList<String>) = KitchenSyncActions(
        pullActiveQueue = { calls += "pull-active-kitchen" },
        pullHistory = { calls += "pull-kitchen-history" },
        drainKitchenOutbox = { calls += "drain-kitchen-outbox" },
    )
}
