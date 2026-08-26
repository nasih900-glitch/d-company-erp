package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TablesDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: TablesDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.tablesDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rejectedRetryIsGuardedAndPreservesCreateAndSendIdentity() = runBlocking {
        val original = LocalTableOrderEntity(
            localId = "stable-table-order-id",
            // Models a create leg that succeeded before send-to-POS was refused.
            orderId = "existing-server-order-id",
            tableId = "table-7",
            tableCode = "T7",
            shiftId = "shift-1",
            lines = listOf(
                LocalTableOrderLine("coffee", 2),
                LocalTableOrderLine("fries", 1),
            ),
            createdAtMillis = 1_724_598_300_000,
            state = TableOrderState.PENDING,
        )
        dao.insertLocalOrder(original)
        dao.markOrderRejected(original.localId, "Shift was closed")

        val rejected = dao.localOrderById(original.localId)!!
        assertEquals(1, dao.retryRejectedOrder(original.localId))
        // A rapid/stale second tap cannot enqueue or clone the same order.
        assertEquals(0, dao.retryRejectedOrder(original.localId))

        val pending = dao.localOrderById(original.localId)!!
        assertEquals(
            rejected.copy(state = TableOrderState.PENDING, lastError = null),
            pending,
        )
        assertEquals("stable-table-order-id", pending.localId)
        assertEquals("existing-server-order-id", pending.orderId)
        assertEquals(pending, dao.observeLocalOrderById(pending.localId).first())
    }
}
