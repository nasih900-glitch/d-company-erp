package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.RefundState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosRefundStateMergeTest {

    @Test
    fun `successful provider settlement advances to accounting`() {
        assertEquals(
            RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING,
            mergePosRefundState(
                RefundState.PROVIDER_COMPLETION_PENDING,
                RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING,
            ),
        )
    }

    @Test
    fun `successful cash handoff advances to accounting`() {
        assertEquals(
            RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING,
            mergePosRefundState(
                RefundState.CASH_SETTLE_PENDING,
                RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING,
            ),
        )
    }

    @Test
    fun `stale server response cannot erase locally recorded payout evidence`() {
        assertEquals(
            RefundState.PROVIDER_COMPLETION_PENDING,
            mergePosRefundState(
                RefundState.PROVIDER_COMPLETION_PENDING,
                RefundState.PROVIDER_PAYOUT_IN_PROGRESS,
            ),
        )
        assertEquals(
            RefundState.CASH_SETTLE_PENDING,
            mergePosRefundState(
                RefundState.CASH_SETTLE_PENDING,
                RefundState.CASH_HANDOFF_IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `terminal server state always wins`() {
        assertEquals(
            RefundState.SETTLED,
            mergePosRefundState(
                RefundState.PROVIDER_FINALIZE_REJECTED,
                RefundState.SETTLED,
            ),
        )
        assertEquals(
            RefundState.WITHDRAWN,
            mergePosRefundState(
                RefundState.WITHDRAWAL_PENDING,
                RefundState.WITHDRAWN,
            ),
        )
    }

    @Test
    fun `withdrawn server state flags locally completed cash without inventing a new state`() {
        assertTrue(
            hasPosRefundWithdrawalPayoutConflict(
                localState = RefundState.CASH_SETTLE_PENDING,
                serverState = RefundState.WITHDRAWN,
                settledAtMillis = 1_000,
                providerSettledAtMillis = null,
                externalReference = null,
            ),
        )
        assertEquals(
            RefundState.WITHDRAWN,
            mergePosRefundState(RefundState.CASH_SETTLE_PENDING, RefundState.WITHDRAWN),
        )
    }

    @Test
    fun `withdrawn server state flags locally completed provider reference`() {
        assertTrue(
            hasPosRefundWithdrawalPayoutConflict(
                localState = RefundState.PROVIDER_COMPLETION_REJECTED,
                serverState = RefundState.WITHDRAWN,
                settledAtMillis = null,
                providerSettledAtMillis = 2_000,
                externalReference = "UPI-REF-1",
            ),
        )
    }

    @Test
    fun `clean withdrawal without local payout evidence is not a payout conflict`() {
        assertFalse(
            hasPosRefundWithdrawalPayoutConflict(
                localState = RefundState.WITHDRAWAL_PENDING,
                serverState = RefundState.WITHDRAWN,
                settledAtMillis = null,
                providerSettledAtMillis = null,
                externalReference = null,
            ),
        )
        assertFalse(
            hasPosRefundWithdrawalPayoutConflict(
                localState = RefundState.CASH_SETTLE_PENDING,
                serverState = RefundState.SETTLED,
                settledAtMillis = 3_000,
                providerSettledAtMillis = null,
                externalReference = null,
            ),
        )
    }
}
