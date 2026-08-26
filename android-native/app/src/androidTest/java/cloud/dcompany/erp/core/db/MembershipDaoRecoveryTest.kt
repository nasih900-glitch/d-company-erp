package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MembershipDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: MembershipDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.membershipDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun acceptedCashRefundMustSettleOrWithdrawBeforeItsExactShiftCanClose() = runBlocking {
        val refund = refund("membership-refund-1")
        dao.insertLocalRefund(refund)
        assertEquals(listOf(refund), dao.pushableRefundRequests())
        assertEquals(1, dao.unresolvedMoneyCountForShift("shift-1", null))
        assertEquals(0, dao.unresolvedMoneyCountForShift("other-shift", null))

        dao.markRefundAcceptedCashDue(refund.localId, "server-refund-1")
        assertTrue(dao.pushableRefundRequests().isEmpty())
        val accepted = dao.observeLocalRefunds().first().single()
        assertEquals(MembershipRefundWriteState.ACCEPTED_CASH_DUE, accepted.syncState)
        assertEquals("server-refund-1", accepted.serverRefundId)
        assertEquals(1, dao.unresolvedMoneyCountForShift("local-shift", "shift-1"))

        assertEquals(1, dao.confirmCashRefundHandover(refund.localId, 2_000))
        assertEquals(0, dao.confirmCashRefundHandover(refund.localId, 2_001))
        assertEquals(
            MembershipRefundWriteState.CASH_SETTLE_PENDING,
            dao.pushableCashRefundSettlements().single().syncState,
        )
        dao.markCashRefundSettlementRejected(refund.localId, "Temporary server refusal")
        dao.retryRefund(refund.localId)
        assertEquals(1, dao.pushableCashRefundSettlements().size)

        dao.markRefundSettled(refund.localId, "server-refund-1", "R/MAIN/26-27/00001")
        assertTrue(dao.observeLocalRefunds().first().isEmpty())
        assertEquals(0, dao.unresolvedMoneyCountForShift("local-shift", "shift-1"))
    }

    @Test
    fun noCashHandoverUsesDurableWithdrawalInsteadOfDeletingTheAcceptance() = runBlocking {
        val refund = refund("membership-refund-2")
        dao.insertLocalRefund(refund)
        dao.markRefundAcceptedCashDue(refund.localId, "server-refund-2")

        assertEquals(
            1,
            dao.requestRefundWithdrawal(
                localId = refund.localId,
                reason = "Customer left before handover",
                withdrawalAtMillis = 3_000,
            ),
        )
        assertEquals(
            0,
            dao.requestRefundWithdrawal(
                localId = refund.localId,
                reason = "Duplicate tap",
                withdrawalAtMillis = 3_001,
            ),
        )
        val pending = dao.pushableRefundWithdrawals().single()
        assertEquals("Customer left before handover", pending.withdrawalReason)
        assertEquals(1, dao.unresolvedMoneyCountForShift("shift-1", null))

        dao.markRefundWithdrawn(refund.localId)
        assertTrue(dao.observeLocalRefunds().first().isEmpty())
        assertEquals(0, dao.unresolvedMoneyCountForShift("shift-1", null))
    }

    @Test
    fun pendingAndRejectedSubscriptionRemainShiftBoundAndKeepTheirStablePrice() = runBlocking {
        val subscription = LocalSubscriptionEntity(
            localId = "membership-sale-1",
            customerId = "customer-1",
            tierId = "gold",
            shiftId = "server-shift-1",
            expectedAmountMinor = 199_900,
            billingCycle = "monthly",
            paidVia = "upi",
            createdAtMillis = 4_000,
        )
        dao.insertLocalSubscription(subscription)
        assertEquals(1, dao.unresolvedMoneyCountForShift("local-shift-1", "server-shift-1"))
        assertEquals(0, dao.unresolvedMoneyCountForShift("other-shift", null))
        assertEquals(199_900L, dao.pushableSubscriptions().single().expectedAmountMinor)

        dao.markSubscriptionRejected(subscription.localId, "Tier price changed")
        assertTrue(dao.pushableSubscriptions().isEmpty())
        assertEquals(1, dao.unresolvedMoneyCountForShift("local-shift-1", "server-shift-1"))
        dao.retrySubscription(subscription.localId)
        assertEquals(subscription.localId, dao.pushableSubscriptions().single().localId)
        dao.markSubscriptionSynced(subscription.localId)
        assertEquals(0, dao.unresolvedMoneyCountForShift("local-shift-1", "server-shift-1"))
    }

    private fun refund(localId: String) = LocalMembershipRefundEntity(
        localId = localId,
        customerId = "customer-1",
        subscriptionId = "membership-1",
        shiftId = "shift-1",
        expectedAmountMinor = 199_900,
        method = "cash",
        reason = "Customer requested refund",
        createdAtMillis = 1_000,
    )
}
