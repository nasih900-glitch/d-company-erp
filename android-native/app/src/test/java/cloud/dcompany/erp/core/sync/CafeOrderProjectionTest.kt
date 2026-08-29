package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.CafeActionKind
import cloud.dcompany.erp.core.db.CafeActionLine
import cloud.dcompany.erp.core.db.CafeActionPayload
import cloud.dcompany.erp.core.db.CafeActionState
import cloud.dcompany.erp.core.db.CafeBillCacheEntity
import cloud.dcompany.erp.core.db.CafeBillLineSnapshot
import cloud.dcompany.erp.core.db.LocalCafeActionEntity
import cloud.dcompany.erp.core.db.LocalCafeBillEntity
import cloud.dcompany.erp.core.db.LocalModifierSelectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CafeOrderProjectionTest {

    @Test
    fun `ordered round cancellation and handoff remain visible across restart projection`() {
        val projected = projectCafeBills(
            serverBills = listOf(serverBill()),
            localBills = listOf(localBill()),
            actions = listOf(
                action(
                    id = "append",
                    sequence = 1,
                    kind = CafeActionKind.APPEND_ROUND,
                    payload = CafeActionPayload(
                        lines = listOf(
                            CafeActionLine(
                                clientLineId = "client-b",
                                menuItemId = "menu-b",
                                name = "Fries",
                                qty = 2,
                                note = "No salt",
                                estimateUnitMinor = 400,
                                variantId = "variant-large",
                                variantName = "Large",
                                variantPriceDeltaMinor = 100,
                                modifiers = listOf(
                                    LocalModifierSelectionSnapshot(
                                        modifierId = "modifier-cheese",
                                        name = "Cheese",
                                        priceDeltaMinor = 50,
                                        qty = 1,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    capturedVersion = 3,
                ),
                action(
                    id = "void",
                    sequence = 2,
                    kind = CafeActionKind.VOID_LINE,
                    payload = CafeActionPayload(
                        targetClientLineId = "client-a",
                        reason = "Guest changed their mind",
                    ),
                ),
                action(id = "send", sequence = 3, kind = CafeActionKind.SEND_TO_POS),
            ),
        ).single()

        assertEquals("sending_to_pos", projected.status)
        assertEquals(3, projected.pendingActionCount)
        assertEquals(800L, projected.totalMinor)
        assertEquals(1_000L, projected.confirmedTotalMinor)
        assertTrue(projected.amountPending)
        assertTrue(projected.lines.single { it.clientLineId == "client-a" }.voided)
        val newLine = projected.lines.single { it.clientLineId == "client-b" }
        assertEquals("No salt", newLine.note)
        assertEquals(800L, newLine.lineTotalMinor)
        assertEquals("Large", newLine.variantSnapshot?.name)
        assertEquals(listOf("Cheese"), newLine.modifiers.map { it.name })
        assertTrue(newLine.locallyPending)
    }

    @Test
    fun `ambiguous response never duplicates a client line already present in server truth`() {
        val server = serverBill().copy(
            totalMinor = 1_500,
            subtotalMinor = 1_500,
            checkoutVersion = 4,
            lines = serverBill().lines + line(
                id = "server-b",
                clientId = "client-b",
                name = "Fries",
                total = 500,
            ),
        )

        val projected = projectCafeBills(
            serverBills = listOf(server),
            localBills = listOf(localBill()),
            actions = listOf(
                action(
                    id = "append",
                    sequence = 1,
                    kind = CafeActionKind.APPEND_ROUND,
                    payload = CafeActionPayload(
                        lines = listOf(CafeActionLine("client-b", "menu-b", "Fries", 1, null, 500)),
                    ),
                    capturedVersion = 3,
                ),
            ),
        ).single()

        assertEquals(1, projected.lines.count { it.clientLineId == "client-b" })
        assertEquals(1_500L, projected.totalMinor)
        assertEquals(1_500L, projected.confirmedTotalMinor)
        assertTrue(projected.amountPending)
    }

    @Test
    fun `held server bill is read only and conflict explains why open bill is blocked`() {
        val held = projectCafeBills(
            serverBills = listOf(serverBill().copy(status = "held", heldAt = "2026-08-26T12:00:00Z")),
            localBills = emptyList(),
            actions = emptyList(),
        ).single()
        assertTrue(held.heldOrSending)
        assertFalse(held.editable)

        val conflict = projectCafeBills(
            serverBills = listOf(serverBill()),
            localBills = listOf(localBill()),
            actions = listOf(
                action(
                    id = "conflict",
                    sequence = 1,
                    kind = CafeActionKind.SEND_TO_POS,
                    state = CafeActionState.CONFLICT,
                    error = "Another terminal changed this bill",
                ),
            ),
        ).single()
        assertFalse(conflict.editable)
        assertEquals("conflict", conflict.blockedActionId)
        assertEquals("Another terminal changed this bill", conflict.blockedMessage)
    }

    @Test
    fun `pending whole bill void shows zero without losing confirmed accounting truth`() {
        val projected = projectCafeBills(
            serverBills = listOf(serverBill().copy(taxMinor = 50, totalMinor = 1_050)),
            localBills = listOf(localBill()),
            actions = listOf(
                action(
                    id = "void-order",
                    sequence = 1,
                    kind = CafeActionKind.VOID_ORDER,
                    payload = CafeActionPayload(reason = "Duplicate bill"),
                    capturedVersion = 3,
                ),
            ),
        ).single()

        assertEquals("voiding", projected.status)
        assertEquals(0L, projected.subtotalMinor)
        assertEquals(0L, projected.taxMinor)
        assertEquals(0L, projected.totalMinor)
        assertEquals(1_050L, projected.confirmedTotalMinor)
        assertTrue(projected.amountPending)
        assertTrue(projected.lines.all { it.voided && it.voidReason == "Duplicate bill" })
        assertFalse(projected.editable)
    }

    @Test
    fun `rejected whole bill void keeps server lines and totals authoritative`() {
        val projected = projectCafeBills(
            serverBills = listOf(serverBill()),
            localBills = listOf(localBill()),
            actions = listOf(
                action(
                    id = "void-order",
                    sequence = 1,
                    kind = CafeActionKind.VOID_ORDER,
                    payload = CafeActionPayload(reason = "Customer left"),
                    state = CafeActionState.REJECTED,
                    error = "Only the shift opener may void this order",
                ),
            ),
        ).single()

        assertEquals("open", projected.status)
        assertEquals(1_000L, projected.totalMinor)
        assertFalse(projected.amountPending)
        assertFalse(projected.lines.single().voided)
        assertEquals("void-order", projected.blockedActionId)
        assertFalse(projected.editable)
    }

    private fun serverBill() = CafeBillCacheEntity(
        orderId = "server-order",
        tableId = "table-1",
        status = "open",
        type = "dine_in",
        sourceLabel = "Table T1",
        subtotalMinor = 1_000,
        taxMinor = 0,
        totalMinor = 1_000,
        openedAt = "2026-08-26T10:00:00Z",
        heldAt = null,
        checkoutVersion = 3,
        lines = listOf(line("server-a", "client-a", "Coffee", 1_000)),
        voidedLines = emptyList(),
    )

    private fun line(id: String, clientId: String, name: String, total: Long) =
        CafeBillLineSnapshot(
            id = id,
            clientLineId = clientId,
            menuItemId = "menu-$clientId",
            name = name,
            qty = 1.0,
            unitPriceMinor = total,
            lineTotalMinor = total,
            kitchenReleasedAt = "2026-08-26T10:00:00Z",
            kitchenRoundNo = 1,
        )

    private fun localBill() = LocalCafeBillEntity(
        localBillId = "local-bill",
        serverOrderId = "server-order",
        tableId = "table-1",
        tableCode = "T1",
        shiftId = "shift-1",
        confirmedCheckoutVersion = 3,
        createdAtMillis = 1_777_000_000_000,
    )

    private fun action(
        id: String,
        sequence: Long,
        kind: String,
        payload: CafeActionPayload = CafeActionPayload(),
        capturedVersion: Long? = null,
        state: String = CafeActionState.PENDING,
        error: String? = null,
    ) = LocalCafeActionEntity(
        actionId = id,
        localBillId = "local-bill",
        sequence = sequence,
        kind = kind,
        payload = payload,
        capturedCheckoutVersion = capturedVersion,
        dedupeKey = "dedupe-$id",
        createdAtMillis = 1_777_000_000_000 + sequence,
        state = state,
        lastError = error,
    )
}
