package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the local money boundary is a compare-and-set, not a late read of a
 * mutable cart. These races are realistic on a touch till: a rapid quantity
 * tap and PAY can be delivered before Room/Compose finish recomposing.
 */
@RunWith(AndroidJUnit4::class)
class PosPaymentCaptureDaoTest {

    private lateinit var db: ErpDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun exactDisplayedRevisionAndAmountAreCapturedImmutably() = runBlocking {
        val draft = order(revision = 4, estimateMinor = 12_500)
        db.orderDao().capture(draft, emptyList())

        assertEquals(
            1,
            db.orderDao().captureOfflineDraft(
                localId = draft.localId,
                expectedRevision = 4,
                expectedDueMinor = 12_500,
                method = "cash",
                tenderedMinor = 20_000,
                updatedAtMillis = 2_000,
            ),
        )

        val captured = db.orderDao().withLines(draft.localId)!!.order
        assertEquals(SyncState.PENDING, captured.syncState)
        assertEquals(12_500L, captured.capturedAmountMinor)
        assertEquals(20_000L, captured.tenderedMinor)
        assertEquals(4L, captured.revision)
    }

    @Test
    fun staleRevisionCannotCaptureOrChangeTheDraft() = runBlocking {
        val draft = order(revision = 5, estimateMinor = 12_500)
        db.orderDao().capture(draft, emptyList())

        assertEquals(
            0,
            db.orderDao().captureOfflineDraft(
                localId = draft.localId,
                expectedRevision = 4,
                expectedDueMinor = 12_500,
                method = "upi",
                tenderedMinor = 0,
                updatedAtMillis = 2_000,
            ),
        )

        val unchanged = db.orderDao().withLines(draft.localId)!!.order
        assertEquals(SyncState.DRAFT, unchanged.syncState)
        assertNull(unchanged.capturedAmountMinor)
    }

    @Test
    fun staleDisplayedAmountCannotCaptureOrChangeTheDraft() = runBlocking {
        val draft = order(revision = 4, estimateMinor = 13_000)
        db.orderDao().capture(draft, emptyList())

        assertEquals(
            0,
            db.orderDao().captureOfflineDraft(
                localId = draft.localId,
                expectedRevision = 4,
                expectedDueMinor = 12_500,
                method = "cash",
                tenderedMinor = 20_000,
                updatedAtMillis = 2_000,
            ),
        )

        val unchanged = db.orderDao().withLines(draft.localId)!!.order
        assertEquals(SyncState.DRAFT, unchanged.syncState)
        assertNull(unchanged.capturedAmountMinor)
    }

    @Test
    fun oldDraftWriteCannotOverwriteCapturedPayment() = runBlocking {
        val draft = order(revision = 4, estimateMinor = 12_500)
        db.orderDao().capture(draft, emptyList())
        assertEquals(
            1,
            db.orderDao().captureOfflineDraft(
                localId = draft.localId,
                expectedRevision = 4,
                expectedDueMinor = 12_500,
                method = "cash",
                tenderedMinor = 20_000,
                updatedAtMillis = 2_000,
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                db.orderDao().saveDraft(
                    draft.copy(revision = 5, updatedAtMillis = 3_000),
                    emptyList(),
                )
            }
        }
        val captured = db.orderDao().withLines(draft.localId)!!.order
        assertEquals(SyncState.PENDING, captured.syncState)
        assertEquals(12_500L, captured.capturedAmountMinor)
    }

    @Test
    fun firstDiscountRequestVersionSurvivesEveryRetry() = runBlocking {
        val preparing = order(revision = 2, estimateMinor = 12_500).copy(
            syncState = SyncState.PREPARING,
            manualDiscountMinor = 1_000,
        )
        db.orderDao().capture(preparing, emptyList())

        assertEquals(1, db.orderDao().preserveDiscountRequestVersion(preparing.localId, 7, 2_000))
        assertEquals(1, db.orderDao().preserveDiscountRequestVersion(preparing.localId, 9, 3_000))

        val recovered = db.orderDao().withLines(preparing.localId)!!.order
        assertEquals(7L, recovered.discountRequestVersion)
    }

    private fun order(revision: Long, estimateMinor: Long) = LocalOrderEntity(
        localId = "pos-cas-order",
        shiftId = "shift-1",
        type = "dine_in",
        estimateMinor = estimateMinor,
        revision = revision,
        createdAtMillis = 1_000,
        updatedAtMillis = 1_000,
        syncState = SyncState.DRAFT,
    )
}
