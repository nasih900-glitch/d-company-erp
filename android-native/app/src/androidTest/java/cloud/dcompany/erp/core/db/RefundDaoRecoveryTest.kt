package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefundDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: RefundDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.refundDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun cashNeedsServerHandoffBeforePhysicalSettlementAndCannotDuplicate() = runBlocking {
        val original = refund("refund-stable", "order-1")
        assertTrue(dao.captureIfNoUnresolved(original))
        assertFalse(dao.captureIfNoUnresolved(refund("refund-duplicate", "order-1")))
        assertEquals(listOf(original), dao.pushableRefundRequests())

        applyServer(original, RefundState.ACCEPTED_CASH_DUE)
        assertEquals(0, dao.confirmCashHandedOver(original.localId, 2_000))
        assertEquals(1, dao.unresolvedMoneyCountForShift("shift-1", "shift-1"))
        assertEquals(0, dao.unresolvedMoneyCountForShift("other-shift", "other-shift"))

        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.CASH_HANDOFF_IN_PROGRESS,
            handoffStartedAtMillis = 1_500,
        )
        assertEquals(1, dao.confirmCashHandedOver(original.localId, 2_000))
        assertEquals(0, dao.confirmCashHandedOver(original.localId, 2_001))
        assertEquals(original.localId, dao.pushableCashSettlements().single().localId)

        dao.markCashSettlementRejected(original.localId, "Temporary server refusal")
        assertEquals(1, dao.retryRejected(original.localId))
        assertEquals(RefundState.CASH_SETTLE_PENDING, dao.refundById(original.localId)!!.state)

        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.SETTLED,
            settledAtMillis = 2_000,
            serverRefundId = "refund-server-1",
            receiptNo = "R/MAIN/26-27/00001",
        )
        assertTrue(dao.observeUnresolvedRefunds().first().isEmpty())
        assertTrue(db.outboxSafetyDao().unresolvedGroups().isEmpty())
        assertTrue(dao.captureIfNoUnresolved(refund("refund-next", "order-1")))
    }

    @Test
    fun acceptedCashCanBeWithdrawnOnlyAsASeparateQueuedResolution() = runBlocking {
        val original = refund("refund-withdraw", "order-2")
        dao.insertLocalRefund(original)
        applyServer(original, RefundState.ACCEPTED_CASH_DUE)

        assertEquals(
            1,
            dao.requestWithdrawal(
                original.localId,
                "Customer left before cash was paid",
                2_000,
            ),
        )
        assertEquals(original.localId, dao.pushableWithdrawals().single().localId)
        assertEquals(0, dao.confirmCashHandedOver(original.localId, 2_001))

        dao.markWithdrawalRejected(original.localId, "Owner session expired")
        assertEquals(1, dao.retryRejected(original.localId))
        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.WITHDRAWN,
            withdrawnAtMillis = 2_000,
        )
        assertTrue(dao.observeUnresolvedRefunds().first().isEmpty())
    }

    @Test
    fun definitiveCashRequestRefusalCanRetryOrCancelWithSameIdentity() = runBlocking {
        val original = refund("refund-refused", "order-3")
        dao.insertLocalRefund(original)
        assertEquals(1, dao.markRequestRejected(original.localId, "Shift is closed"))
        assertEquals("refund-refused", dao.refundById(original.localId)!!.clientActionId)
        assertEquals(1, dao.retryRejected(original.localId))
        assertEquals("refund-refused", dao.pushableRefundRequests().single().clientActionId)
        dao.markRequestRejected(original.localId, "Shift is still closed")
        assertEquals(1, dao.cancelRejectedRequest(original.localId))
        assertEquals(RefundState.CANCELLED, dao.refundById(original.localId)!!.state)
    }

    @Test
    fun legacyUnknownRowsRemainQuarantinedAndAreNeverPushed() = runBlocking {
        val legacy = refund(
            localId = "legacy-refund",
            orderId = "order-old",
            state = RefundState.LEGACY_RECONCILIATION_REQUIRED,
        ).copy(
            clientActionId = null,
            shiftId = null,
            serverShiftId = null,
            branchId = null,
            terminalId = null,
            capturedByUserId = null,
        )
        dao.insertLocalRefund(legacy)

        assertTrue(dao.pushableRefundRequests().isEmpty())
        assertTrue(dao.pushableCashSettlements().isEmpty())
        assertTrue(dao.pushableWithdrawals().isEmpty())
        assertEquals(1, dao.legacyReconciliationCount())
        assertEquals(RefundState.LEGACY_RECONCILIATION_REQUIRED, dao.observeUnresolvedRefunds().first().single().state)
        assertEquals(
            UnresolvedOutboxGroup("refunds", RefundState.LEGACY_RECONCILIATION_REQUIRED, 1),
            db.outboxSafetyDao().unresolvedGroups().single(),
        )
    }

    private suspend fun applyServer(
        row: LocalRefundEntity,
        state: String,
        handoffStartedAtMillis: Long? = null,
        settledAtMillis: Long? = null,
        withdrawnAtMillis: Long? = null,
        serverRefundId: String? = null,
        receiptNo: String? = null,
    ) {
        assertEquals(
            1,
            dao.applyServerState(
                localId = row.localId,
                state = state,
                serverRequestId = "request-server-${row.localId}",
                serverRefundId = serverRefundId,
                serverShiftId = "shift-1",
                branchId = "branch-1",
                terminalId = "terminal-1",
                settlementMethod = "cash",
                acceptedAtMillis = 1_200,
                cashHandoffStartedAtMillis = handoffStartedAtMillis,
                settledAtMillis = settledAtMillis,
                withdrawalAtMillis = withdrawnAtMillis,
                externalReference = null,
                receiptNo = receiptNo,
                lastError = null,
            ),
        )
    }

    private fun refund(
        localId: String,
        orderId: String,
        state: String = RefundState.REQUEST_PENDING,
    ) = LocalRefundEntity(
        localId = localId,
        clientActionId = localId,
        orderId = orderId,
        invoiceNo = "INV-101",
        shiftId = "shift-1",
        serverShiftId = "shift-1",
        branchId = "branch-1",
        terminalId = "terminal-1",
        capturedByUserId = "owner-1",
        reasonCode = "billing_error",
        amountMinor = 12_500,
        expectedPaidMinor = 20_000,
        expectedRefundableMinor = 20_000,
        mode = "cash",
        note = "Customer charged twice",
        createdAtMillis = 1_000,
        state = state,
    )
}
