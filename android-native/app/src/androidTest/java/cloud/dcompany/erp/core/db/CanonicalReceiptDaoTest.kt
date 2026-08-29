package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanonicalReceiptDaoTest {
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
    fun pageReplayReplacesCanonicalProjectionWithoutTouchingLocalEvidence() = runBlocking {
        db.posReceiptDao().store(localReceipt())
        val initial = receipt(status = "paid", paidMinor = 10_000)
        db.canonicalReceiptDao().storePage(
            receipts = listOf(initial),
            syncState = CanonicalReceiptSyncStateEntity(
                nextCursor = "page-2",
                hasMore = true,
                loadedCount = 50,
                fetchedAtMillis = 1_000,
            ),
            expectedCompanyId = "company-1",
            expectedBranchId = "branch-1",
        )
        db.canonicalReceiptDao().storePage(
            receipts = listOf(initial.copy(status = "refunded", paidMinor = 8_000)),
            syncState = CanonicalReceiptSyncStateEntity(
                hasMore = false,
                loadedCount = 50,
                fetchedAtMillis = 2_000,
            ),
            expectedCompanyId = "company-1",
            expectedBranchId = "branch-1",
        )

        val cached = db.canonicalReceiptDao().byOrderId("order-1", "company-1", "branch-1")
        assertEquals("refunded", cached?.status)
        assertEquals(8_000L, cached?.paidMinor)
        assertEquals(false, db.canonicalReceiptDao().syncState()?.hasMore)
        assertEquals(50, db.canonicalReceiptDao().syncState()?.loadedCount)
        assertEquals("order-1", db.posReceiptDao().byId("payment-1")?.orderId)
    }

    @Test
    fun exactDetailRefreshReplacesOnlyCanonicalProjectionAndKeepsLocalReceipt() = runBlocking {
        db.posReceiptDao().store(localReceipt())
        db.canonicalReceiptDao().upsert(receipt(status = "paid", paidMinor = 10_000))

        db.canonicalReceiptDao().upsert(receipt(status = "refunded", paidMinor = 8_000))

        assertEquals(
            "refunded",
            db.canonicalReceiptDao().byOrderId("order-1", "company-1", "branch-1")?.status,
        )
        assertEquals("order-1", db.posReceiptDao().byId("payment-1")?.orderId)
    }

    @Test
    fun pageCannotCrossCompanyOrBranchAndReadsRemainScopeFiltered() = runBlocking {
        val wrongCompany = receipt().copy(companyId = "company-2")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                db.canonicalReceiptDao().storePage(
                    receipts = listOf(wrongCompany),
                    syncState = CanonicalReceiptSyncStateEntity(fetchedAtMillis = 1_000),
                    expectedCompanyId = "company-1",
                    expectedBranchId = "branch-1",
                )
            }
        }

        db.canonicalReceiptDao().storePage(
            receipts = listOf(receipt()),
            syncState = CanonicalReceiptSyncStateEntity(fetchedAtMillis = 2_000),
            expectedCompanyId = "company-1",
            expectedBranchId = "branch-1",
        )
        assertNull(db.canonicalReceiptDao().byOrderId("order-1", "company-2", "branch-1"))
        assertEquals(
            emptyList<CanonicalReceiptEntity>(),
            db.canonicalReceiptDao().observeRecent("company-1", "branch-2", 50).first(),
        )
    }

    private fun receipt(
        status: String = "paid",
        paidMinor: Long = 10_000,
    ) = CanonicalReceiptEntity(
        orderId = "order-1",
        companyId = "company-1",
        branchId = "branch-1",
        terminalId = "terminal-1",
        invoiceNo = "INV-1",
        status = status,
        orderType = "gaming",
        totalMinor = 10_000,
        paidMinor = paidMinor,
        invoiceIssuedAt = "2026-08-29T10:00:00Z",
        invoiceIssuedAtMillis = 1_000,
        payloadJson = "{}",
        fetchedAtMillis = 1_000,
    )

    private fun localReceipt() = PosReceiptEntity(
        receiptId = "payment-1",
        orderId = "order-1",
        paymentId = "payment-1",
        shiftId = "shift-1",
        sourceKind = PosReceiptSource.DIRECT,
        sourceLabel = "POS",
        customerName = null,
        customerPhone = null,
        orderNote = null,
        subtotalMinor = 10_000,
        discountMinor = 0,
        taxMinor = 0,
        roundOffMinor = 0,
        totalMinor = 10_000,
        dueBeforePaymentMinor = 10_000,
        method = "cash",
        amountMinor = 10_000,
        billAmountMinor = 10_000,
        tipMinor = 0,
        tenderedMinor = 10_000,
        changeMinor = 0,
        refExternal = null,
        paidAt = "2026-08-29T10:00:00Z",
        orderStatus = "paid",
        invoiceNo = "INV-1",
        fiscalYear = "2026-27",
        invoiceIssuedAt = "2026-08-29T10:00:00Z",
        linesJson = "[]",
        createdAtMillis = 1_000,
    )
}
