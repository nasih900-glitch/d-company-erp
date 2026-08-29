package cloud.dcompany.erp.ui.screens.refunds

import cloud.dcompany.erp.core.db.RefundState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefundTaskCopyTest {

    @Test
    fun requestStatesNeverAuthoriseCash() {
        for (state in listOf(RefundState.REQUEST_PENDING, RefundState.REQUEST_REJECTED)) {
            val copy = copy(state)
            assertTrue(copy.contains("no payout") || copy.contains("no cash is authorised"))
            assertFalse(copy.contains("hand over the exact"))
        }
    }

    @Test
    fun acceptanceStillRequiresServerHandoverBeforeDrawerAccess() {
        val copy = copy(RefundState.ACCEPTED_CASH_DUE)
        assertTrue(copy.contains("drawer untouched"))
        assertTrue(copy.contains("server-confirmed handover"))
    }

    @Test
    fun restartSensitiveStatesAlwaysWarnAgainstDuplicatePayout() {
        for (
            state in listOf(
                RefundState.CASH_HANDOFF_IN_PROGRESS,
                RefundState.CASH_SETTLE_PENDING,
                RefundState.CASH_SETTLE_REJECTED,
            )
        ) {
            val copy = copy(state)
            assertTrue(copy.contains("do not pay twice") || copy.contains("do not pay again"))
        }
    }

    @Test
    fun providerStatesRequireAcceptanceAndAlwaysWarnAgainstDuplicatePayout() {
        val accepted = copy(RefundState.ACCEPTED_PROVIDER_DUE)
        assertTrue(accepted.contains("payout not started"))
        assertTrue(accepted.contains("server-confirmed provider start"))

        for (
            state in listOf(
                RefundState.PROVIDER_PAYOUT_IN_PROGRESS,
                RefundState.PROVIDER_COMPLETION_PENDING,
                RefundState.PROVIDER_COMPLETION_REJECTED,
                RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING,
                RefundState.PROVIDER_FINALIZE_REJECTED,
            )
        ) {
            val copy = copy(state)
            assertTrue(copy.contains("do not start twice") || copy.contains("do not run the payout again"))
        }
    }

    @Test
    fun legacyRowsAreQuarantinedInsteadOfBecomingCashDue() {
        val copy = copy(RefundState.LEGACY_RECONCILIATION_REQUIRED)
        assertTrue(copy.contains("quarantined"))
        assertTrue(copy.contains("do not pay again"))
        assertTrue(copy.contains("owner reconciliation"))
    }

    @Test
    fun withdrawalConflictBlocksSecondPayoutAndRequiresOwnerReconciliation() {
        val copy = refundTaskCopy(task(RefundState.WITHDRAWN).copy(payoutConflict = true))
            .let { "${it.first} ${it.second}" }
            .lowercase()

        assertTrue(copy.contains("do not pay again"))
        assertTrue(copy.contains("protected owner"))
        assertTrue(copy.contains("server records no payout"))
        assertTrue(copy.contains("drawer or provider"))
    }

    private fun copy(state: String): String = refundTaskCopy(task(state))
        .let { "${it.first} ${it.second}" }
        .lowercase()

    private fun task(state: String) = RefundTask(
        localId = "refund-1",
        orderId = "order-1",
        invoiceNo = "INV-1",
        amountMinor = 10_000,
        reasonCode = "billing_error",
        createdAtMillis = 1_000,
        state = state,
        mode = "cash",
        settlementMethod = if (state == RefundState.REQUEST_PENDING) null else "cash",
        serverRequestId = null,
        acceptedAtMillis = null,
        handoffStartedAtMillis = null,
        settledAtMillis = null,
        localPayoutAtMillis = null,
        withdrawalAtMillis = null,
        receiptNo = null,
        externalReference = null,
        acceptedByUserId = null,
        acceptedByName = null,
        moneyStartedByUserId = null,
        moneyStartedByName = null,
        moneyCompletedByUserId = null,
        moneyCompletedByName = null,
        settledByUserId = null,
        settledByName = null,
        withdrawnByUserId = null,
        withdrawnByName = null,
        providerVerificationStatus = null,
        providerVerificationReference = null,
        customerSpendReconciled = null,
        loyaltyReconciliationState = null,
        capturedTimeReconciled = null,
        providerEvidenceReconciled = null,
        payoutConflict = false,
        error = null,
    )
}
