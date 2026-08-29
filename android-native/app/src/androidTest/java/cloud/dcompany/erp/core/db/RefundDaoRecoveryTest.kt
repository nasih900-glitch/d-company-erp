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
            RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING,
        )
        assertEquals(original.localId, dao.pushableCashFinalizations().single().localId)
        dao.markCashFinalizationRejected(original.localId, "Accounting temporarily unavailable")
        assertEquals(1, dao.retryRejected(original.localId))

        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.SETTLED,
            settledAtMillis = 2_000,
            serverRefundId = "refund-server-1",
            receiptNo = "R/MAIN/26-27/00001",
        )
        assertTrue(dao.observeUnresolvedRefunds().first().isEmpty())
        val completed = dao.observeRecentCompletedRefunds().first().single()
        assertEquals("Owner", completed.acceptedByName)
        assertEquals("R/MAIN/26-27/00001", completed.receiptNo)
        assertTrue(db.outboxSafetyDao().unresolvedGroups().isEmpty())
        assertTrue(dao.captureIfNoUnresolved(refund("refund-next", "order-1")))
    }

    @Test
    fun providerNeedsServerStartBeforeCompletionAndFinalizesWithoutDuplicatePayout() = runBlocking {
        val original = refund("refund-provider", "order-provider").copy(mode = "original")
        assertTrue(dao.captureIfNoUnresolved(original))
        applyServer(
            original,
            RefundState.ACCEPTED_PROVIDER_DUE,
            settlementMethod = "upi",
        )
        assertEquals(0, dao.confirmProviderCompleted(original.localId, "upi-ref-1", 2_000))

        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.PROVIDER_PAYOUT_IN_PROGRESS,
            settlementMethod = "upi",
        )
        assertEquals(1, dao.confirmProviderCompleted(original.localId, "upi-ref-1", 2_000))
        assertEquals(0, dao.confirmProviderCompleted(original.localId, "upi-ref-2", 2_001))
        assertEquals(original.localId, dao.pushableProviderCompletions().single().localId)

        dao.markProviderCompletionRejected(original.localId, "Connection lost after provider success")
        assertEquals(1, dao.retryRejected(original.localId))
        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING,
            settlementMethod = "upi",
            providerSettledAtMillis = 2_000,
            externalReference = "upi-ref-1",
        )
        assertEquals(original.localId, dao.pushableProviderFinalizations().single().localId)

        dao.markProviderFinalizationRejected(original.localId, "Accounting unavailable")
        assertEquals(1, dao.retryRejected(original.localId))
        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.SETTLED,
            settlementMethod = "upi",
            providerSettledAtMillis = 2_000,
            settledAtMillis = 2_100,
            externalReference = "upi-ref-1",
            serverRefundId = "refund-server-provider",
            receiptNo = "R/MAIN/26-27/00002",
            providerPayoutStartedAtMillis = 1_500,
            providerPayoutStartedByName = "Rafi",
            providerCompletionRecordedAtMillis = 2_050,
            providerCompletedByName = "Nasih",
            settledByName = "Nasih",
            capturedTimeReconciled = true,
            providerEvidenceReconciled = true,
            customerSpendReconciled = true,
            loyaltyReconciliationState = "applied",
        )
        assertTrue(dao.observeUnresolvedRefunds().first().isEmpty())
        val completed = dao.observeRecentCompletedRefunds().first().single()
        assertEquals("Rafi", completed.providerPayoutStartedByName)
        assertEquals("Nasih", completed.providerCompletedByName)
        assertEquals("Nasih", completed.settledByName)
        assertEquals("upi-ref-1", completed.externalReference)
        assertEquals(true, completed.capturedTimeReconciled)
        assertEquals(true, completed.providerEvidenceReconciled)
        assertEquals(true, completed.customerSpendReconciled)
        assertEquals("applied", completed.loyaltyReconciliationState)
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
        assertFalse(dao.refundById(original.localId)!!.payoutConflict)
        assertEquals(RefundState.WITHDRAWN, dao.observeRecentCompletedRefunds().first().single().state)
    }

    @Test
    fun serverWithdrawalAfterLocalCashCompletionBecomesBlockingConflictWithoutSecondPayout() = runBlocking {
        val original = refund("refund-cash-conflict", "order-cash-conflict")
        assertTrue(dao.captureIfNoUnresolved(original))
        applyServer(original, RefundState.ACCEPTED_CASH_DUE)
        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.CASH_HANDOFF_IN_PROGRESS,
            handoffStartedAtMillis = 1_500,
        )
        assertEquals(1, dao.confirmCashHandedOver(original.localId, 2_000))

        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.WITHDRAWN,
            withdrawnAtMillis = 2_100,
            withdrawnByName = "Other owner",
            payoutConflict = true,
            lastError = "Do not pay again. Protected-owner reconciliation is required.",
        )

        val conflict = dao.refundById(original.localId)!!
        assertEquals(RefundState.WITHDRAWN, conflict.state)
        assertTrue(conflict.payoutConflict)
        assertEquals(2_000L, conflict.settledAtMillis)
        assertEquals("Other owner", conflict.withdrawnByName)
        assertEquals(1, dao.observeUnresolvedRefunds().first().size)
        assertEquals(1, dao.observeRejectedCount().first())
        assertTrue(dao.observeRecentCompletedRefunds().first().isEmpty())
        assertTrue(dao.pushableCashSettlements().isEmpty())
        assertTrue(dao.reconcilableRefunds().isEmpty())
        assertEquals(1, dao.unresolvedMoneyCountForShift("shift-1", "shift-1"))
        assertFalse(dao.captureIfNoUnresolved(refund("refund-duplicate-conflict", original.orderId)))
        assertEquals(1, db.shiftCloseSafetyDao().blockersForExactShift("shift-1", "shift-1", "terminal-1").attentionLocalCount)
        assertEquals(
            UnresolvedOutboxGroup("refunds", RefundState.WITHDRAWN, 1),
            db.outboxSafetyDao().unresolvedGroups().single(),
        )
    }

    @Test
    fun serverWithdrawalAfterLocalProviderCompletionPreservesReferenceAndNeverReplays() = runBlocking {
        val original = refund("refund-provider-conflict", "order-provider-conflict").copy(mode = "original")
        assertTrue(dao.captureIfNoUnresolved(original))
        applyServer(original, RefundState.ACCEPTED_PROVIDER_DUE, settlementMethod = "upi")
        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.PROVIDER_PAYOUT_IN_PROGRESS,
            settlementMethod = "upi",
        )
        assertEquals(1, dao.confirmProviderCompleted(original.localId, "UPI-IMMUTABLE-REF", 2_000))

        applyServer(
            dao.refundById(original.localId)!!,
            RefundState.WITHDRAWN,
            settlementMethod = "upi",
            withdrawnAtMillis = 2_100,
            withdrawnByName = "Other owner",
            payoutConflict = true,
            lastError = "Do not pay again. Protected-owner reconciliation is required.",
        )

        val conflict = dao.refundById(original.localId)!!
        assertEquals(RefundState.WITHDRAWN, conflict.state)
        assertTrue(conflict.payoutConflict)
        assertEquals(2_000L, conflict.providerSettledAtMillis)
        assertEquals("UPI-IMMUTABLE-REF", conflict.externalReference)
        assertTrue(dao.pushableProviderCompletions().isEmpty())
        assertTrue(dao.pushableProviderFinalizations().isEmpty())
        assertTrue(dao.reconcilableRefunds().isEmpty())
        assertEquals(1, dao.observeUnresolvedRefunds().first().size)
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
    fun terminalRefundInvalidationRemovesStaleBalanceAndAggregateSnapshots() = runBlocking {
        dao.upsertOrderCache(
            listOf(
                RefundOrderCacheEntity(
                    id = "order-stale",
                    invoiceNo = "INV-STALE",
                    status = "paid",
                    type = "pos",
                    totalMinor = 20_000,
                    paidMinor = 20_000,
                    refundableMinor = 20_000,
                    pendingRefundMinor = 0,
                    paymentMethodsCsv = "cash",
                ),
            ),
        )
        db.reportSnapshotDao().put(
            ReportSnapshotEntity("daily:2026-08-28", "{}", 1_000),
        )

        assertEquals(1, dao.deleteOrderCacheById("order-stale"))
        db.reportSnapshotDao().invalidateAll()

        assertTrue(dao.observeRefundableOrders().first().isEmpty())
        assertEquals(null, db.reportSnapshotDao().get("daily:2026-08-28"))
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
        settlementMethod: String = "cash",
        providerSettledAtMillis: Long? = null,
        externalReference: String? = null,
        providerPayoutStartedAtMillis: Long? = null,
        providerPayoutStartedByName: String? = null,
        providerCompletionRecordedAtMillis: Long? = null,
        providerCompletedByName: String? = null,
        settledByName: String? = null,
        capturedTimeReconciled: Boolean? = null,
        providerEvidenceReconciled: Boolean? = null,
        customerSpendReconciled: Boolean? = null,
        loyaltyReconciliationState: String? = null,
        payoutConflict: Boolean = false,
        withdrawnByName: String? = null,
        lastError: String? = null,
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
                settlementMethod = settlementMethod,
                acceptedAtMillis = 1_200,
                acceptedByUserId = "owner-1",
                acceptedByName = "Owner",
                cashHandoffStartedAtMillis = handoffStartedAtMillis,
                cashHandoffStartedByUserId = null,
                cashHandoffStartedByName = null,
                cashHandedOverAtMillis = null,
                cashHandedOverRecordedAtMillis = null,
                cashHandedOverByUserId = null,
                cashHandedOverByName = null,
                providerPayoutStartedAtMillis = providerPayoutStartedAtMillis,
                providerPayoutStartedByUserId = providerPayoutStartedByName?.let { "provider-starter-1" },
                providerPayoutStartedByName = providerPayoutStartedByName,
                providerSettledAtMillis = providerSettledAtMillis,
                providerCompletionRecordedAtMillis = providerCompletionRecordedAtMillis,
                providerCompletedByUserId = providerCompletedByName?.let { "provider-completer-1" },
                providerCompletedByName = providerCompletedByName,
                settledAtMillis = settledAtMillis,
                settledByUserId = settledByName?.let { "settler-1" },
                settledByName = settledByName,
                clientOccurredAtMillis = null,
                capturedTimeReconciled = capturedTimeReconciled,
                providerEvidenceReconciled = providerEvidenceReconciled,
                payoutConflict = payoutConflict,
                withdrawalAtMillis = withdrawnAtMillis,
                withdrawnByUserId = null,
                withdrawnByName = withdrawnByName,
                providerVerificationStatus = null,
                providerVerificationReference = null,
                providerVerifiedAtMillis = null,
                customerSpendReconciled = customerSpendReconciled,
                loyaltyReconciliationState = loyaltyReconciliationState,
                externalReference = externalReference,
                receiptNo = receiptNo,
                lastError = lastError,
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
