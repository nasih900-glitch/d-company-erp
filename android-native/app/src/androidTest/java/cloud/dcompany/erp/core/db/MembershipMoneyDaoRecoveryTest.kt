package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MembershipMoneyDaoRecoveryTest {

    private lateinit var db: ErpDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun paymentActionKeepsOneImmutableStageAcrossAmbiguousRetryAndSync() = runBlocking {
        val dao = db.membershipPaymentDao()
        val original = paymentAction(actionId = "payment-begin-1")

        assertNotEquals(-1L, dao.insertAction(original))
        assertEquals(
            -1L,
            dao.insertAction(original.copy(actionId = "duplicate-id", createdAtMillis = 2_000)),
        )
        assertEquals(original, dao.actionForStage(original.rootClientActionId, original.kind))
        assertEquals(1, dao.unresolvedActionCountForShift("shift-1", null))
        assertEquals(0, dao.unresolvedActionCountForShift("other-shift", null))

        assertEquals(1, dao.markAmbiguous(original.actionId, "request-1", "response lost"))
        assertEquals(
            MembershipMoneyActionState.AMBIGUOUS,
            dao.actionById(original.actionId)?.state,
        )
        assertEquals(1, dao.retryAction(original.actionId))
        assertEquals(original.actionId, dao.pushableActions().single().actionId)
        assertEquals(1, dao.markSynced(original.actionId, "request-1"))
        assertEquals(0, dao.unresolvedActionCountForShift("shift-1", null))
        assertTrue(dao.pushableActions().isEmpty())
    }

    @Test
    fun refundActionAndLegacyAttemptBlockOnlyTheirCapturedShift() = runBlocking {
        val dao = db.membershipRefundMoneyDao()
        val action = refundAction(actionId = "refund-complete-1")
        assertNotEquals(-1L, dao.insertAction(action))
        assertEquals(
            -1L,
            dao.insertAction(action.copy(actionId = "duplicate-id", externalReference = "other")),
        )

        assertEquals(1, dao.unresolvedActionCountForShift("local-shift", "shift-1"))
        assertEquals(0, dao.unresolvedActionCountForShift("other-shift", null))
        assertEquals(1, dao.markAmbiguous(action.actionId, "refund-1", "response lost"))
        assertEquals(1, dao.retryAction(action.actionId))
        assertEquals(action.actionId, dao.pushableActions().single().actionId)

        val attempt = refundAttempt("attempt-1", "terminal-1", "shift-1")
        dao.replaceAttemptsForTerminal("terminal-1", listOf(attempt))
        assertEquals(1, dao.unresolvedAttemptCountForShift("local-shift", "shift-1"))
        assertEquals(attempt, dao.observeUnresolvedAttempts().first().single())

        assertEquals(1, dao.markSynced(action.actionId, "refund-1"))
        assertEquals(0, dao.unresolvedActionCountForShift("local-shift", "shift-1"))
        assertEquals(1, dao.unresolvedAttemptCountForShift("local-shift", "shift-1"))
    }

    @Test
    fun unresolvedLegacyServerRefundLocksNormalPayoutUntilReconciled() = runBlocking {
        val dao = db.membershipRefundMoneyDao()
        val legacy = refundAction("legacy-refund-1").copy(
            kind = MembershipRefundActionKind.LEGACY_RECONCILE_SERVER,
            state = MembershipMoneyActionState.LEGACY_RECOVERY_REQUIRED,
            lastError = "Verify original cash handover; do not pay again.",
        )
        dao.insertAction(legacy)

        assertEquals(legacy, dao.unresolvedLegacyActionForServerRefund("refund-1"))
        assertEquals(null, dao.unresolvedLegacyActionForServerRefund("other-refund"))
        assertEquals(1, dao.markSynced(legacy.actionId, "refund-1"))
        assertEquals(null, dao.unresolvedLegacyActionForServerRefund("refund-1"))
    }

    @Test
    fun taskAndRecoveryReplacementIsStrictlyTerminalScoped() = runBlocking {
        val payments = db.membershipPaymentDao()
        val refunds = db.membershipRefundMoneyDao()

        payments.upsertTasks(
            listOf(
                paymentTask("payment-a-old", "terminal-a"),
                paymentTask("payment-b", "terminal-b"),
            ),
        )
        payments.replaceTasksForTerminal(
            "terminal-a",
            listOf(paymentTask("payment-a-new", "terminal-a")),
        )
        assertEquals(
            setOf("payment-a-new", "payment-b"),
            payments.unresolvedTasks().map { it.id }.toSet(),
        )

        refunds.upsertTasks(
            listOf(
                refundTask("refund-a-old", "terminal-a"),
                refundTask("refund-b", "terminal-b"),
            ),
        )
        refunds.replaceTasksForTerminal(
            "terminal-a",
            listOf(refundTask("refund-a-new", "terminal-a")),
        )
        assertEquals(
            setOf("refund-a-new", "refund-b"),
            refunds.unresolvedTasks().map { it.id }.toSet(),
        )

        refunds.upsertAttempts(
            listOf(
                refundAttempt("attempt-a-old", "terminal-a", "shift-a"),
                refundAttempt("attempt-b", "terminal-b", "shift-b"),
            ),
        )
        refunds.replaceAttemptsForTerminal(
            "terminal-a",
            listOf(refundAttempt("attempt-a-new", "terminal-a", "shift-a")),
        )
        assertEquals(
            setOf("attempt-a-new", "attempt-b"),
            refunds.observeUnresolvedAttempts().first().map { it.id }.toSet(),
        )
    }

    private fun paymentAction(actionId: String) = LocalMembershipPaymentActionEntity(
        actionId = actionId,
        rootClientActionId = "membership-payment:root-1",
        serverRequestId = "request-1",
        kind = MembershipPaymentActionKind.BEGIN_CASH,
        customerId = "customer-1",
        tierId = "tier-1",
        shiftId = "shift-1",
        branchId = "branch-1",
        terminalId = "terminal-1",
        actorUserId = "owner-1",
        billingCycle = "monthly",
        paidVia = "cash",
        expectedAmountMinor = 19_900,
        createdAtMillis = 1_000,
    )

    private fun refundAction(actionId: String) = LocalMembershipRefundActionEntity(
        actionId = actionId,
        rootClientActionId = "membership-refund:root-1",
        serverRefundId = "refund-1",
        kind = MembershipRefundActionKind.COMPLETE_PROVIDER,
        customerId = "customer-1",
        membershipId = "membership-1",
        paymentId = "payment-1",
        shiftId = "shift-1",
        branchId = "branch-1",
        terminalId = "terminal-1",
        actorUserId = "owner-1",
        paidVia = "upi",
        expectedAmountMinor = 19_900,
        reason = "Customer request",
        occurredAtMillis = 1_100,
        externalReference = "provider-reference-1",
        createdAtMillis = 1_100,
    )

    private fun paymentTask(id: String, terminalId: String) = MembershipPaymentTaskCacheEntity(
        id = id,
        branchId = "branch-1",
        terminalId = terminalId,
        customerId = "customer-$id",
        tierId = "tier-1",
        shiftId = "shift-$terminalId",
        billingCycle = "monthly",
        paidVia = "cash",
        amountMinor = 19_900,
        customerName = "Customer",
        customerPhone = "9999999999",
        tierCode = "gold",
        tierName = "Gold",
        status = MembershipPaymentTaskStatus.ACCEPTED_PAYMENT_DUE,
        acceptedAt = "2026-08-26T10:00:00Z",
        preparedBy = "owner-1",
        preparedByName = "Owner",
        collectionStartedAt = null,
        valueCompletedAt = null,
        valueCompletedBy = null,
        valueCompletedByName = null,
        actionStartedBy = null,
        actionStartedByName = null,
        actionKind = null,
        settledAt = null,
        settledBy = null,
        settledByName = null,
        membershipId = null,
        paymentId = null,
        receiptNo = null,
        externalReference = null,
        evidenceOccurredAt = null,
        evidenceTimeUntrusted = false,
        providerEvidenceReconciled = true,
        customerSpendReconciled = true,
        resolution = null,
        resolvedAt = null,
        resolvedBy = null,
        resolvedByName = null,
        actionStateVerified = false,
        providerVerificationStatus = null,
        providerVerificationReference = null,
        providerCheckedAt = null,
        cashReturnConfirmed = false,
        actionTakeoverConfirmed = false,
        actionTakeoverReason = null,
        clientActionId = "client-$id",
        fetchedAtMillis = 1_000,
    )

    private fun refundTask(id: String, terminalId: String) = MembershipRefundTaskCacheEntity(
        id = id,
        branchId = "branch-1",
        terminalId = terminalId,
        membershipId = "membership-$id",
        paymentId = "payment-$id",
        shiftId = "shift-$terminalId",
        method = "cash",
        amountMinor = 19_900,
        acceptedAt = "2026-08-26T10:00:00Z",
        status = MembershipRefundTaskStatus.ACCEPTED_CASH_DUE,
        handoffStartedAt = null,
        payoutCompletedAt = null,
        payoutCompletedBy = null,
        payoutCompletedByName = null,
        acceptedBy = "owner-1",
        acceptedByName = "Owner",
        actionStartedBy = null,
        actionStartedByName = null,
        actionKind = null,
        settledAt = null,
        settledBy = null,
        settledByName = null,
        reason = "Customer request",
        externalReference = null,
        receiptNo = null,
        entitlementRestored = false,
        customerId = "customer-1",
        customerName = "Customer",
        customerPhone = "9999999999",
        tierName = "Gold",
        originalPaymentReceiptNo = "M/MAIN/26-27/00001",
        resolution = null,
        resolutionReason = null,
        resolvedAt = null,
        resolvedBy = null,
        resolvedByName = null,
        evidenceOccurredAt = null,
        evidenceTimeUntrusted = false,
        providerEvidenceReconciled = true,
        customerSpendReconciled = true,
        actionStateVerified = false,
        providerVerificationStatus = null,
        providerVerificationReference = null,
        providerCheckedAt = null,
        cashReturnConfirmed = false,
        actionTakeoverConfirmed = false,
        actionTakeoverReason = null,
        fetchedAtMillis = 1_000,
    )

    private fun refundAttempt(
        id: String,
        terminalId: String,
        shiftId: String,
    ) = MembershipRefundAttemptCacheEntity(
        id = id,
        branchId = "branch-1",
        terminalId = terminalId,
        originalClientActionId = "client-$id",
        customerId = "customer-1",
        membershipId = "membership-1",
        paymentId = "payment-1",
        sourceShiftId = shiftId,
        expectedAmountMinor = 19_900,
        paidVia = "cash",
        capturedAt = "2026-08-26T09:59:00Z",
        capturedTimeUntrusted = true,
        registeredAt = "2026-08-26T10:00:00Z",
        registeredBy = "owner-1",
        registeredByName = "Owner",
        status = "unresolved",
        resolutionId = null,
        fetchedAtMillis = 1_000,
    )
}
