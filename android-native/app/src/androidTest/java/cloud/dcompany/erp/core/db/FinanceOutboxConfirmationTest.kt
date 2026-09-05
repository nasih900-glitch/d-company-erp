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
