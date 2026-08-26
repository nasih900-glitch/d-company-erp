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
class OrderDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: OrderDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.orderDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rejectedSaleRetryIsGuardedAndPreservesOriginalIdempotencyIdentity() = runBlocking {
        val original = LocalOrderEntity(
            localId = "sale-stable-id",
            shiftId = "shift-1",
            type = "dine_in",
            customerName = "Counter guest",
            estimateMinor = 1_230,
            paymentMethod = "upi",
            tenderedMinor = 1_230,
            createdAtMillis = 1_725_000_000_000,
            syncState = SyncState.PENDING,
        )
        dao.capture(
            original,
            listOf(
                LocalOrderLineEntity(
                    orderLocalId = original.localId,
                    menuItemId = "item-1",
                    name = "Tea",
                    qty = 2,
                    unitPriceMinor = 615,
                ),
            ),
        )
        dao.markRejected(original.localId, "Shift was closed")

        val rejected = dao.observeRejected().first().single()
        val capturedLines = dao.linesFor(original.localId)
        assertEquals(original.localId, rejected.localId)
        assertEquals("Shift was closed", rejected.lastError)

        assertEquals(1, dao.retryRejected(original.localId))
        // A stale second tap cannot queue or clone the same captured payment.
        assertEquals(0, dao.retryRejected(original.localId))

        assertTrue(dao.observeRejected().first().isEmpty())
        val pending = dao.byState().single()
        assertEquals(
            rejected.copy(syncState = SyncState.PENDING, lastError = null),
            pending,
        )
        assertEquals("sale-stable-id", pending.localId)
        assertEquals(capturedLines, dao.linesFor(pending.localId))
        assertEquals(pending, dao.observeByLocalId(pending.localId).first())
    }
}
