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
    fun legacyRowsAreQuarantinedInsteadOfBecomingCashDue() {
        val copy = copy(RefundState.LEGACY_RECONCILIATION_REQUIRED)
        assertTrue(copy.contains("quarantined"))
        assertTrue(copy.contains("do not pay again"))
        assertTrue(copy.contains("owner reconciliation"))
    }

    private fun copy(state: String): String = refundTaskCopy(task(state))
        .let { "${it.first} ${it.second}" }
        .lowercase()

    private fun task(state: String) = RefundTask(
        localId = "refund-1",
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
        withdrawalAtMillis = null,
        receiptNo = null,
        error = null,
    )
}
