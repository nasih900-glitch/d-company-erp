package cloud.dcompany.erp.ui.screens.tables

import cloud.dcompany.erp.core.sync.CafeBillProjection
import org.junit.Assert.assertEquals
import org.junit.Test

class TablesOperationalFilterTest {

    @Test
    fun `bill-less reserved cleaning merged and unknown tables stay only in all`() {
        listOf("reserved", "cleaning", "merged", "unexpected_server_state").forEach { status ->
            assertEquals("all", tableOperationalFilterId(table(status), bill = null))
        }
    }

    @Test
    fun `only a real bill or explicit open status classifies a table as open`() {
        listOf("open", "open bill", "occupied").forEach { status ->
            assertEquals("open", tableOperationalFilterId(table(status), bill = null))
        }
        assertEquals("open", tableOperationalFilterId(table("reserved"), bill = bill()))
    }

    @Test
    fun `attention and pos states retain precedence over an existing bill`() {
        assertEquals(
            "attention",
            tableOperationalFilterId(table("reserved"), bill = bill(blockedActionId = "blocked-1")),
        )
        assertEquals("pos", tableOperationalFilterId(table("at_pos"), bill = bill(status = "held")))
    }

    private fun table(status: String) = CafeTable(
        id = "table-$status",
        floorId = "floor-1",
        code = "T1",
        status = status,
    )

    private fun bill(
        status: String = "open",
        blockedActionId: String? = null,
    ) = CafeBillProjection(
        localBillId = "local-bill",
        serverOrderId = "server-order",
        tableId = "table-reserved",
        tableCode = "T1",
        status = status,
        checkoutVersion = 1,
        subtotalMinor = 1_000,
        taxMinor = 0,
        confirmedTotalMinor = 1_000,
        totalMinor = 1_000,
        amountPending = false,
        lines = emptyList(),
        pendingActionCount = 0,
        blockedActionId = blockedActionId,
        blockedMessage = null,
    )
}
