package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CafeOrderDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: CafeOrderDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.cafeOrderDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rapidFirstRoundTapsAndASecondBillForTheSameTableAreDeduplicated() = runBlocking {
        val bill = localBill()
        val first = action("action-1", CafeActionKind.CREATE_ROUND, "round:table-1:client-1")

        assertTrue(dao.captureNewBill(bill, first))
        assertFalse(dao.captureNewBill(bill, first))
        assertFalse(
            dao.captureNewBill(
                bill.copy(localBillId = "other-bill"),
                first.copy(actionId = "other-action", localBillId = "other-bill"),
            ),
        )
        assertEquals(listOf("action-1"), dao.actionsForBill(bill.localBillId).map { it.actionId })
        assertEquals(1, dao.observePendingActionCount().first())
    }

    @Test
    fun actionChainIsStrictlyOrderedAndOnlyItsHeadOwnsTheCapturedVersion() = runBlocking {
        val bill = localBill(serverOrderId = "server-order", version = 7)
        assertTrue(
            dao.captureAction(
                bill,
                action("append", CafeActionKind.APPEND_ROUND, "append:client-2", version = 7),
            ),
        )
        assertTrue(
            dao.captureAction(
                bill,
                action("send", CafeActionKind.SEND_TO_POS, "send:server-order", version = 7),
            ),
        )

        val rows = dao.actionsForBill(bill.localBillId)
        assertEquals(listOf(1L, 2L), rows.map { it.sequence })
        assertEquals(7L, rows[0].capturedCheckoutVersion)
        assertNull(rows[1].capturedCheckoutVersion)
    }

    @Test
    fun confirmationAtomicallyAdvancesVersionThenRemovesTheShortLivedLocalAnchor() = runBlocking {
        val bill = localBill(serverOrderId = "server-order", version = 7)
        dao.captureAction(
            bill,
            action("append", CafeActionKind.APPEND_ROUND, "append:client-2", version = 7),
        )
        dao.captureAction(
            bill,
            action("send", CafeActionKind.SEND_TO_POS, "send:server-order", version = 7),
        )

        dao.confirmAction(bill.localBillId, "append", serverBill(version = 8, status = "open"))
        assertEquals(8L, dao.localBill(bill.localBillId)?.confirmedCheckoutVersion)
        assertEquals("send", dao.firstAction(bill.localBillId)?.actionId)
        assertEquals(8L, dao.billCacheByOrderId("server-order")?.checkoutVersion)

        dao.confirmAction(bill.localBillId, "send", serverBill(version = 9, status = "held"))
        assertNull(dao.localBill(bill.localBillId))
        assertTrue(dao.actionsForBill(bill.localBillId).isEmpty())
        assertEquals("held", dao.billCacheByOrderId("server-order")?.status)
    }

    @Test
    fun conflictRebaseAndRejectedDiscardPreserveTheOriginalActionIdentity() = runBlocking {
        val bill = localBill(serverOrderId = "server-order", version = 3)
        val row = action("send", CafeActionKind.SEND_TO_POS, "send:server-order", version = 3)
        dao.captureAction(bill, row)
        dao.setActionFailure(row.actionId, CafeActionState.CONFLICT, "Changed elsewhere")

        assertEquals(1, dao.rebaseAndRetryConflict(row.actionId, 9))
        assertEquals(0, dao.rebaseAndRetryConflict(row.actionId, 10))
        val retried = requireNotNull(dao.action(row.actionId))
        assertEquals(CafeActionState.PENDING, retried.state)
        assertEquals(9L, retried.capturedCheckoutVersion)
        assertEquals("send", retried.actionId)

        dao.setActionFailure(row.actionId, CafeActionState.REJECTED, "Shift closed")
        assertTrue(dao.discardBlockedAction(row.actionId))
        assertFalse(dao.discardBlockedAction(row.actionId))
        assertNull(dao.localBill(bill.localBillId))
    }

    @Test
    fun discardingFailedCreateRemovesDependentChainWhileLaterDiscardRebasesSuccessor() = runBlocking {
        val newBill = localBill()
        val create = action("create", CafeActionKind.CREATE_ROUND, "create-key")
        dao.captureNewBill(newBill, create)
        dao.captureAction(
            newBill,
            action("dependent-send", CafeActionKind.SEND_TO_POS, "dependent-send-key"),
        )
        dao.setActionFailure(create.actionId, CafeActionState.REJECTED, "Table occupied")
        assertTrue(dao.discardBlockedAction(create.actionId))
        assertTrue(dao.actionsForBill(newBill.localBillId).isEmpty())
        assertNull(dao.localBill(newBill.localBillId))

        val anchoredBill = localBill(serverOrderId = "server-order", version = 3)
        val append = action("append", CafeActionKind.APPEND_ROUND, "append-key", version = 3)
        dao.captureAction(anchoredBill, append)
        dao.captureAction(
            anchoredBill,
            action("surviving-send", CafeActionKind.SEND_TO_POS, "surviving-send-key"),
        )
        dao.setActionFailure(append.actionId, CafeActionState.REJECTED, "Item unavailable")
        dao.upsertBillCache(serverBill(version = 9, status = "open"))

        assertTrue(dao.discardBlockedAction(append.actionId))
        assertEquals("surviving-send", dao.firstAction(anchoredBill.localBillId)?.actionId)
        assertEquals(9L, dao.localBill(anchoredBill.localBillId)?.confirmedCheckoutVersion)
    }

    private fun localBill(serverOrderId: String? = null, version: Long? = null) =
        LocalCafeBillEntity(
            localBillId = "local-bill",
            serverOrderId = serverOrderId,
            tableId = "table-1",
            tableCode = "T1",
            shiftId = "shift-1",
            confirmedCheckoutVersion = version,
            createdAtMillis = 1_777_000_000_000,
        )

    private fun action(
        id: String,
        kind: String,
        dedupe: String,
        version: Long? = null,
    ) = LocalCafeActionEntity(
        actionId = id,
        localBillId = "local-bill",
        kind = kind,
        payload = CafeActionPayload(
            lines = if (kind == CafeActionKind.CREATE_ROUND || kind == CafeActionKind.APPEND_ROUND) {
                listOf(CafeActionLine("client-1", "menu-1", "Coffee", 1, null, 1_000))
            } else emptyList(),
        ),
        capturedCheckoutVersion = version,
        dedupeKey = dedupe,
        createdAtMillis = 1_777_000_000_000,
    )

    private fun serverBill(version: Long, status: String) = CafeBillCacheEntity(
        orderId = "server-order",
        tableId = "table-1",
        status = status,
        type = "dine_in",
        sourceLabel = "Table T1",
        subtotalMinor = 1_000,
        taxMinor = 0,
        totalMinor = 1_000,
        openedAt = "2026-08-26T10:00:00Z",
        heldAt = if (status == "held") "2026-08-26T11:00:00Z" else null,
        checkoutVersion = version,
        lines = emptyList(),
        voidedLines = emptyList(),
    )
}
