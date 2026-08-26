package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.MembershipPaymentActionKind
import cloud.dcompany.erp.core.db.MembershipPaymentTaskStatus
import cloud.dcompany.erp.core.db.MembershipRefundActionKind
import cloud.dcompany.erp.core.db.MembershipRefundTaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipMoneyPolicyTest {

    @Test
    fun `explicit truncated task list always fails closed`() {
        assertTrue(taskListIsIncomplete("true", rowCount = 1, limit = 200))
        assertTrue(taskListIsIncomplete(" TRUE ", rowCount = 0, limit = 200))
    }

    @Test
    fun `old server without marker fails closed at exact limit`() {
        assertFalse(taskListIsIncomplete(null, rowCount = 199, limit = 200))
        assertTrue(taskListIsIncomplete(null, rowCount = 200, limit = 200))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid completeness marker is rejected`() {
        taskListIsIncomplete("maybe", rowCount = 2, limit = 200)
    }

    @Test
    fun `payment begin never treats merely accepted task as physical authorization`() {
        assertFalse(
            paymentStageSatisfied(
                MembershipPaymentActionKind.BEGIN_CASH,
                MembershipPaymentTaskStatus.ACCEPTED_PAYMENT_DUE,
            ),
        )
        assertTrue(
            paymentStageSatisfied(
                MembershipPaymentActionKind.BEGIN_CASH,
                MembershipPaymentTaskStatus.CASH_COLLECTION_IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `payment completion cannot converge from an in-progress task`() {
        assertFalse(
            paymentStageSatisfied(
                MembershipPaymentActionKind.COMPLETE,
                MembershipPaymentTaskStatus.PROVIDER_ACTION_IN_PROGRESS,
            ),
        )
        assertTrue(
            paymentStageSatisfied(
                MembershipPaymentActionKind.COMPLETE,
                MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING,
            ),
        )
    }

    @Test
    fun `refund accept is zero-value while begin needs exact rail state`() {
        assertTrue(
            refundStageSatisfied(
                MembershipRefundActionKind.ACCEPT,
                MembershipRefundTaskStatus.ACCEPTED_PROVIDER_DUE,
            ),
        )
        assertFalse(
            refundStageSatisfied(
                MembershipRefundActionKind.BEGIN_CASH,
                MembershipRefundTaskStatus.ACCEPTED_CASH_DUE,
            ),
        )
        assertTrue(
            refundStageSatisfied(
                MembershipRefundActionKind.BEGIN_CASH,
                MembershipRefundTaskStatus.CASH_HANDOFF_IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `refund completion requires payout evidence stage and finalization requires settled`() {
        assertFalse(
            refundStageSatisfied(
                MembershipRefundActionKind.COMPLETE_PROVIDER,
                MembershipRefundTaskStatus.PROVIDER_ACTION_IN_PROGRESS,
            ),
        )
        assertTrue(
            refundStageSatisfied(
                MembershipRefundActionKind.COMPLETE_PROVIDER,
                MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING,
            ),
        )
        assertFalse(
            refundStageSatisfied(
                MembershipRefundActionKind.FINALIZE,
                MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING,
            ),
        )
        assertTrue(
            refundStageSatisfied(
                MembershipRefundActionKind.FINALIZE,
                MembershipRefundTaskStatus.SETTLED,
            ),
        )
    }

    @Test
    fun `legacy recovery kinds never auto-converge from ordinary tasks`() {
        assertFalse(
            refundStageSatisfied(
                MembershipRefundActionKind.LEGACY_REGISTER,
                MembershipRefundTaskStatus.SETTLED,
            ),
        )
        assertFalse(
            paymentStageSatisfied(
                MembershipPaymentActionKind.LEGACY_PROBE,
                MembershipPaymentTaskStatus.SETTLED,
            ),
        )
    }
}
