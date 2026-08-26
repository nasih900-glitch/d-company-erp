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
class HeldOrderDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: HeldOrderDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.heldOrderDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rejectedPaymentRetryPreservesIdentityAndAccountLockUntilServerConfirmation() = runBlocking {
        val original = payment(localId = "stable-payment-id", targetOrderId = "order-1")
        assertTrue(dao.insertPayment(original) >= 0L)

        // Process recreation or a stale second confirmation cannot create a
        // second settlement for the same server order.
        assertEquals(
            -1L,
            dao.insertPayment(payment(localId = "different-id", targetOrderId = "order-1")),
        )

        dao.markPaymentRejected(original.localId, "Checkout total changed")
        val rejected = dao.observeUnresolvedPayments().first().single()
        assertEquals(HeldOrderPaymentState.REJECTED, rejected.syncState)
        assertEquals("Checkout total changed", rejected.lastError)
        assertEquals(rejected, dao.paymentForTarget(original.targetOrderId))
        assertEquals(rejected, dao.observePayment(original.localId).first())

        val rejectedLock = db.outboxSafetyDao().unresolvedGroups().single()
        assertEquals("held_order_payments", rejectedLock.resource)
        assertEquals(HeldOrderPaymentState.REJECTED, rejectedLock.state)
        assertEquals(1, rejectedLock.count)

        assertEquals(1, dao.retryRejectedPayment(original.localId))
        // Guarded state transition makes stale UI/double taps harmless.
        assertEquals(0, dao.retryRejectedPayment(original.localId))

        val pending = dao.observeUnresolvedPayments().first().single()
        assertEquals(
            rejected.copy(syncState = HeldOrderPaymentState.PENDING, lastError = null),
            pending,
        )
        assertEquals("stable-payment-id", pending.localId)
        assertEquals("order-1", pending.targetOrderId)

        val pendingLock = db.outboxSafetyDao().unresolvedGroups().single()
        assertEquals(HeldOrderPaymentState.PENDING, pendingLock.state)

        dao.markPaymentSynced(original.localId)
        assertTrue(dao.observeUnresolvedPayments().first().isEmpty())
        assertTrue(db.outboxSafetyDao().unresolvedGroups().isEmpty())
    }

    @Test
    fun alarmSnapshotsExposeAuthoritativeRowsAndEveryLocallyConfirmedTarget() = runBlocking {
        val order = HeldOrderCacheEntity(
            id = "order-1",
            invoiceNo = null,
            type = "dine_in",
            sourceLabel = "Table 4",
            totalMinor = 12_500,
            paidMinor = 0,
            itemsCount = 2,
            customerName = null,
            createdAt = "2026-08-26T09:55:00Z",
            heldAt = "2026-08-26T10:00:00Z",
            checkoutVersion = 7,
        )
        dao.replace(listOf(order))
        dao.insertPayment(payment(localId = "payment-1", targetOrderId = order.id))
        dao.markPaymentRejected("payment-1", "Manager review required")

        assertEquals(listOf(order), dao.allForAlarms())
        assertEquals(order, dao.orderForAlarm(order.id))
        assertEquals(listOf(order.id), dao.confirmedTargetIdsForAlarms())
    }

    private fun payment(localId: String, targetOrderId: String) = LocalHeldOrderPaymentEntity(
        localId = localId,
        targetOrderId = targetOrderId,
        method = "upi",
        amountMinor = 12_500,
        tenderedMinor = null,
        expectedTotalMinor = 12_500,
        expectedDueMinor = 12_500,
        claimToken = "claim-token",
        claimExpiresAtMillis = 2_000,
        claimOrderVersion = 7,
        createdAtMillis = 1_000,
        syncState = HeldOrderPaymentState.PENDING,
    )
}
