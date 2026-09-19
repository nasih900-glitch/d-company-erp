package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import cloud.dcompany.erp.ui.screens.finance.expenseIdempotencyKey
import cloud.dcompany.erp.ui.screens.finance.toExpenseCreate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceOutboxConfirmationTest {
    private lateinit var db: ErpDatabase
    private lateinit var dao: FinanceDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.financeDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun unknownExpenseResultKeepsPayloadUntilReceiptIsDurablyConfirmed() = runBlocking {
        val pending = LocalExpenseEntity(
            "local-expense", "branch", "category", null, 12_500, "cash",
            "2026-09-04T12:00:00Z", "Vendor", "INV-1", "repair", 1_000,
        )
        dao.insertLocalExpense(pending)
        dao.notePendingExpenseError(pending.localId, "Waiting for receipt")
        assertEquals(pending.copy(lastError = "Waiting for receipt"), dao.pushableExpenses().single())
        assertEquals(0, dao.retryExpense(pending.localId))

        val receipt = ExpenseCacheEntity(
            "server-expense", pending.branchId, pending.categoryId, pending.supplierId,
            pending.amountMinor, pending.paidVia, pending.paidAt, pending.vendorName,
            pending.invoiceNo, pending.note,
        )
        dao.confirmExpense(pending.localId, receipt)
        assertTrue(dao.pushableExpenses().isEmpty())
        assertTrue(dao.observeLocalExpenses().first().isEmpty())
        assertEquals(receipt, dao.observeExpenseCache().first().single())
    }

    @Test
    fun receiptWaitsForParentThenReplaysWithoutBlockingAccountSafetyGap() = runBlocking {
        val pendingExpense = LocalExpenseEntity(
            "local-expense-with-receipt", "branch", "category", null, 12_500, "cash",
            "2026-09-19T12:00:00Z", "Vendor", "INV-2", "repair", 1_000,
        )
        val content = "%PDF-1.7\n%%EOF".encodeToByteArray()
        val pendingReceipt = LocalExpenseReceiptEntity(
            localId = "local-receipt",
            expenseLocalId = pendingExpense.localId,
            filename = "bill.pdf",
            contentType = "application/pdf",
            source = "file",
            byteSize = content.size,
            contentSha256 = "sha-1",
            createdAtMillis = 1_001,
        )
        val pendingChunk = LocalExpenseReceiptChunkEntity(pendingReceipt.localId, 0, content)
        dao.insertLocalExpenseWithReceipt(pendingExpense, pendingReceipt, listOf(pendingChunk))

        assertTrue(dao.pushableExpenseReceipts().isEmpty())
        assertEquals(
            setOf("expenses", "expense_receipts"),
            db.outboxSafetyDao().unresolvedGroups().map { it.resource }.toSet(),
        )

        dao.confirmExpense(
            pendingExpense.localId,
            ExpenseCacheEntity(
                "server-expense", pendingExpense.branchId, pendingExpense.categoryId, null,
                pendingExpense.amountMinor, pendingExpense.paidVia, pendingExpense.paidAt,
                pendingExpense.vendorName, pendingExpense.invoiceNo, pendingExpense.note,
            ),
        )

        assertEquals(pendingReceipt, dao.pushableExpenseReceipts().single())
        assertTrue(content.contentEquals(dao.expenseReceiptChunks(pendingReceipt.localId).single().content))
        assertEquals(
            listOf("expense_receipts"),
            db.outboxSafetyDao().unresolvedGroups().map { it.resource },
        )

        dao.markExpenseReceiptRejected(pendingReceipt.localId, "Unsupported file")
        assertEquals(1, dao.retryExpenseReceipt(pendingReceipt.localId))
        assertEquals(0, dao.retryExpenseReceipt(pendingReceipt.localId))

        dao.confirmExpenseReceipt(pendingReceipt.localId, "server-receipt")
        val confirmed = requireNotNull(dao.expenseReceiptByLocalId(pendingReceipt.localId))
        assertEquals("synced", confirmed.syncState)
        assertEquals("server-receipt", confirmed.serverReceiptId)
        assertTrue(dao.expenseReceiptChunks(pendingReceipt.localId).isEmpty())
        assertTrue(db.outboxSafetyDao().unresolvedGroups().isEmpty())
    }

    @Test
    fun rejectedReceiptDiscardUsesExactCasAndKeepsExpenseAndOtherEvidence() = runBlocking {
        val expense = LocalExpenseEntity(
            "local-expense-recovery", "branch", "category", null, 2_500, "upi",
            "2026-09-19T12:00:00Z", "Vendor", "INV-3", null, 1_000,
            serverId = "server-expense",
            syncState = SyncState.SYNCED,
        )
        val rejected = LocalExpenseReceiptEntity(
            "rejected-receipt", expense.localId, "bad.pdf", "application/pdf", "file",
            4, "hash-rejected", 1_001, SyncState.REJECTED, lastError = "Invalid PDF",
        )
        val pending = LocalExpenseReceiptEntity(
            "pending-receipt", expense.localId, "pending.pdf", "application/pdf", "file",
            5, "hash-pending", 1_002,
        )
        dao.insertLocalExpenseWithReceipt(
            expense,
            rejected,
            listOf(LocalExpenseReceiptChunkEntity(rejected.localId, 0, byteArrayOf(1, 2, 3, 4))),
        )
        dao.insertLocalExpenseReceipt(pending)
        dao.insertLocalExpenseReceiptChunks(
            listOf(LocalExpenseReceiptChunkEntity(pending.localId, 0, byteArrayOf(5, 6, 7, 8, 9))),
        )

        assertEquals(
            0,
            dao.discardRejectedExpenseReceiptEvidence(
                rejected.localId,
                rejected.expenseLocalId,
                "wrong-hash",
            ),
        )
        assertEquals(
            0,
            dao.discardRejectedExpenseReceiptEvidence(
                pending.localId,
                pending.expenseLocalId,
                pending.contentSha256,
            ),
        )
        assertEquals(
            1,
            dao.discardRejectedExpenseReceiptEvidence(
                rejected.localId,
                rejected.expenseLocalId,
                rejected.contentSha256,
            ),
        )

        assertEquals(expense, dao.expenseByLocalId(expense.localId))
        assertEquals(null, dao.expenseReceiptByLocalId(rejected.localId))
        assertTrue(dao.expenseReceiptChunks(rejected.localId).isEmpty())
        assertEquals(pending, dao.expenseReceiptByLocalId(pending.localId))
        assertEquals(1, dao.expenseReceiptChunks(pending.localId).size)
    }

    @Test
    fun cashExpenseRetryReusesOneRowOneShiftAndOneCanonicalActionIdentity() = runBlocking {
        val localId = "123e4567-e89b-12d3-a456-426614174000"
        val shiftId = "6d330c0a-d038-40d8-befb-b9fd21898fec"
        dao.insertLocalExpense(
            LocalExpenseEntity(
                localId = localId,
                branchId = "branch",
                categoryId = "category",
                supplierId = null,
                amountMinor = 1_500,
                paidVia = "cash",
                paidAt = "2026-09-19T12:00:00Z",
                vendorName = "Vendor",
                invoiceNo = "CASH-1",
                note = "Cash paid-out",
                createdAtMillis = 1_000,
                shiftId = shiftId,
            ),
        )
        val original = dao.pushableExpenses().single()
        val originalBody = original.toExpenseCreate()
        val originalKey = expenseIdempotencyKey(original.localId)

        dao.markExpenseRejected(localId, "Temporary validation refusal")
        assertEquals(1, dao.retryExpense(localId))
        assertEquals(0, dao.retryExpense(localId))

        val replay = dao.pushableExpenses().single()
        assertEquals(1, dao.pushableExpenses().size)
        assertEquals(original, replay)
        assertEquals(originalBody, replay.toExpenseCreate())
        assertEquals(shiftId, replay.shiftId)
        assertEquals(originalKey, expenseIdempotencyKey(replay.localId))
    }

    @Test
    fun unknownAssetResultKeepsPurchaseFactsUntilReceiptIsDurablyConfirmed() = runBlocking {
        val pending = LocalAssetEntity(
            "local-asset", "branch", "Controller", "equipment", 50_000,
            "2026-09-04", 24, 0, "Replacement", 1_000,
        )
        dao.insertLocalAsset(pending)
        dao.notePendingAssetError(pending.localId, "Waiting for receipt")
        assertEquals(pending.copy(lastError = "Waiting for receipt"), dao.pushableAssets().single())
        assertEquals(0, dao.retryAsset(pending.localId))

        val receipt = AssetCacheEntity(
            "server-asset", pending.branchId, pending.name, pending.type, pending.purchaseMinor,
            pending.purchaseDate, pending.usefulLifeMonths, pending.salvageMinor,
            "straight_line", pending.notes, 0, pending.purchaseMinor,
        )
        dao.confirmAsset(pending.localId, receipt)
        assertTrue(dao.pushableAssets().isEmpty())
        assertTrue(dao.observeLocalAssets().first().isEmpty())
        assertEquals(receipt, dao.observeAssetCache().first().single())
    }

    @Test
    fun unknownCapitalResultKeepsReferenceAndAmountUntilReceiptIsDurablyConfirmed() = runBlocking {
        val pending = LocalCapitalEntryEntity(
            "local-capital", "partner", "investment", 100_000, "2026-09-04T12:00:00Z",
            "bank", "REF-1", "Contribution", 1_000,
        )
        dao.insertLocalCapitalEntry(pending)
        dao.notePendingCapitalEntryError(pending.localId, "Waiting for receipt")
        assertEquals(pending.copy(lastError = "Waiting for receipt"), dao.pushableCapitalEntries().single())
        assertEquals(0, dao.retryCapitalEntry(pending.localId))

        val receipt = CapitalEntryCacheEntity(
            "server-capital", pending.partnerId, pending.type, pending.amountMinor,
            pending.effectiveAt, pending.settlementAccount, pending.sourceRef,
            pending.note, "Owner", pending.effectiveAt, null, null, false,
        )
        dao.confirmCapitalEntry(pending.localId, receipt)
        assertTrue(dao.pushableCapitalEntries().isEmpty())
        assertTrue(dao.observeLocalCapitalEntries().first().isEmpty())
        assertEquals(receipt, dao.observeCapitalEntriesFor(pending.partnerId).first().single())
    }
}
