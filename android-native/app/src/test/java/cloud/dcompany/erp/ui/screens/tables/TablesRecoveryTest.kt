package cloud.dcompany.erp.ui.screens.tables

import cloud.dcompany.erp.core.db.LocalTableOrderEntity
import cloud.dcompany.erp.core.db.LocalTableOrderLine
import cloud.dcompany.erp.core.db.TableOrderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class TablesRecoveryTest {

    @Test
    fun rejectedPresentationPreservesIdentityAndShowsCapturedItems() {
        val presentation = order(
            localId = "stable-table-order-id",
            lines = listOf(
                LocalTableOrderLine("coffee", 2),
                LocalTableOrderLine("deleted-item", 1),
            ),
            lastError = "  Shift was closed\nby a manager  ",
        ).toRejectedTableOrder(mapOf("coffee" to "Cold coffee"))

        assertEquals("stable-table-order-id", presentation.localId)
        assertEquals("table-7", presentation.tableId)
        assertEquals("T7", presentation.tableCode)
        assertEquals("2 × Cold coffee · 1 × Unavailable menu item", presentation.itemSummary)
        assertEquals("Shift was closed by a manager", presentation.error)
    }

    @Test
    fun rejectedStateWinsSoTableStaysBlockedUntilEveryLocalOrderResolves() {
        val states = unresolvedTableOrderStates(
            listOf(
                order(localId = "pending-same-table", state = TableOrderState.PENDING),
                order(localId = "rejected-same-table", state = TableOrderState.REJECTED),
                order(localId = "pending-other", tableId = "table-8", state = TableOrderState.PENDING),
            ),
        )

        assertEquals(TableOrderState.REJECTED, states["table-7"])
        assertEquals(TableOrderState.PENDING, states["table-8"])
    }

    @Test
    fun offlineQueueCopyNeverClaimsOrderReachedPos() {
        val message = tableOrderQueuedNotice("T7", online = false)

        assertTrue(message.startsWith("Offline:"))
        assertTrue(message.contains("saved on this tablet"))
        assertTrue(message.contains("has not reached POS"))
        assertTrue(message.contains("table stays blocked"))
    }

    @Test
    fun retryCopyNamesSameIdentityAndUnconfirmedState() {
        val message = tableOrderRetryQueuedNotice("T7")

        assertTrue(message.contains("original saved order"))
        assertTrue(message.contains("same order identity"))
        assertTrue(message.contains("not confirmed yet"))
    }

    @Test
    fun terminalOutcomeDistinguishesAcceptedFromRefused() {
        val accepted = tableOrderOutcomeNotice("T7", null)
        val refused = tableOrderOutcomeNotice(
            "T7",
            order(lastError = null, state = TableOrderState.REJECTED),
        )
        val pending = tableOrderOutcomeNotice("T7", order(state = TableOrderState.PENDING))

        assertTrue(accepted!!.contains("reached POS"))
        assertTrue(accepted.contains("Do not send a second order"))
        assertTrue(refused!!.contains("without an explanation"))
        assertTrue(refused.contains("remains blocked"))
        assertNull(pending)
    }

    @Test
    fun savedTimeUsesExplicitCafeDisplayZoneDeterministically() {
        val epoch = Instant.parse("2026-08-25T14:05:00Z").toEpochMilli()

        assertEquals(
            "25 Aug, 14:05",
            formatRejectedTableOrderTime(epoch, ZoneId.of("UTC"), Locale.UK),
        )
    }

    private fun order(
        localId: String = "order-7",
        tableId: String = "table-7",
        lines: List<LocalTableOrderLine> = listOf(LocalTableOrderLine("coffee", 1)),
        state: String = TableOrderState.REJECTED,
        lastError: String? = "Shift was closed",
    ) = LocalTableOrderEntity(
        localId = localId,
        orderId = "server-order-7",
        tableId = tableId,
        tableCode = if (tableId == "table-7") "T7" else "T8",
        shiftId = "shift-1",
        lines = lines,
        createdAtMillis = 1_724_598_300_000,
        state = state,
        lastError = lastError,
    )
}
