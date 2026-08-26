package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KitchenDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: KitchenDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.kitchenDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rejectedAdvanceRemainsVisibleAndCanRetryOrBeExplicitlyDiscarded() = runBlocking {
        val first = advance("11111111-1111-4111-8111-111111111111")
        dao.insertAdvance(first)
        assertEquals(1, dao.markAdvanceRejected(first.localId, "Ticket is still new"))

        val rejected = dao.observeUnresolvedAdvances().first().single()
        assertEquals(KitchenAdvanceState.REJECTED, rejected.state)
        assertEquals("Ticket is still new", rejected.lastError)
        assertEquals(1, db.outboxSafetyDao().unresolvedGroups().single().count)

        assertEquals(1, dao.retryRejectedAdvance(first.localId))
        assertEquals(0, dao.retryRejectedAdvance(first.localId))
        val retried = dao.observeUnresolvedAdvances().first().single()
        assertEquals(KitchenAdvanceState.PENDING, retried.state)
        assertNull(retried.lastError)

        dao.markAdvanceRejected(first.localId, "Still refused")
        assertEquals(1, dao.discardRejectedAdvance(first.localId))
        assertEquals(0, dao.discardRejectedAdvance(first.localId))
        assertTrue(dao.observeUnresolvedAdvances().first().isEmpty())
        assertTrue(db.outboxSafetyDao().unresolvedGroups().isEmpty())
    }

    @Test
    fun uncertainAdvanceKeepsItsIdentityAndPendingState() = runBlocking {
        val row = advance("33333333-3333-4333-8333-333333333333")
        dao.insertAdvance(row)

        assertEquals(
            1,
            dao.keepAdvancePending(
                row.localId,
                "Could not confirm whether this update reached the server.",
            ),
        )
        assertEquals(1, dao.deletePendingAdvance(row.localId))

        assertTrue(dao.observeUnresolvedAdvances().first().isEmpty())
    }

    @Test
    fun cancellationAcknowledgementIsDurableDeduplicatedAndRetryable() = runBlocking {
        val first = LocalKitchenCancellationAckEntity(
            localId = "ack-1",
            orderId = "order-1",
            lineId = "line-1",
            requestedAtMillis = 1_768_000_000_000L,
        )
        assertTrue(dao.insertCancellationAck(first) != -1L)
        // A rapid second tap has a different random local id, but the
        // semantic order+line unique index still absorbs it.
        assertEquals(
            -1L,
            dao.insertCancellationAck(first.copy(localId = "ack-rapid-tap")),
        )
        assertEquals("ack-1", dao.pendingCancellationAcks().single().localId)

        dao.rejectCancellationAck(first.localId, "Wrong branch")
        val rejected = dao.observeCancellationAcks().first().single()
        assertEquals(KitchenCancellationAckState.REJECTED, rejected.state)
        assertEquals("Wrong branch", rejected.lastError)
        assertEquals(1, dao.retryCancellationAck(first.localId))
        assertEquals(0, dao.retryCancellationAck(first.localId))
        assertEquals(KitchenCancellationAckState.PENDING, dao.pendingCancellationAcks().single().state)
        assertNull(dao.pendingCancellationAcks().single().lastError)
    }

    @Test
    fun cancellationOnlyTicketSurvivesCacheRoundTripUntilAcknowledged() = runBlocking {
        val cancellation = cloud.dcompany.erp.ui.screens.kitchen.KitchenCancellation(
            lineId = "line-1",
            menuItemId = "menu-1",
            name = "Fries",
            type = "food",
            qty = 1.0,
            releasedAt = "2026-08-26T10:00:00Z",
            roundNo = 2,
            voidedAt = "2026-08-26T10:05:00Z",
            reason = "Guest changed their mind",
        )
        dao.replaceOrderCache(
            listOf(
                KitchenOrderCacheEntity(
                    id = "order-1",
                    type = "dine_in",
                    tableCode = "T1",
                    kitchenState = "served",
                    lines = emptyList(),
                    pendingCancellations = listOf(cancellation),
                ),
            ),
        )

        val restored = dao.observeOrderCache().first().single()
        assertTrue(restored.lines.isEmpty())
        assertEquals("line-1", restored.pendingCancellations.single().lineId)
        assertEquals("Guest changed their mind", restored.pendingCancellations.single().reason)
    }

    private fun advance(localId: String) = LocalKitchenAdvanceEntity(
        localId = localId,
        orderId = "22222222-2222-4222-8222-222222222222",
        targetState = "preparing",
        requestedAtMillis = 1_768_000_000_000L,
    )
}
