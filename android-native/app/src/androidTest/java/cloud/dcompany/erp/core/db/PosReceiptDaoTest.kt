package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PosReceiptDaoTest {
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
    fun receiptAndHeldSettlementResolveInOneTransaction() = runBlocking {
        val payment = heldPayment()
        db.heldOrderDao().insertPayment(payment)

        db.posReceiptDao().storeAndMarkSettlementSynced(receipt(), payment.localId)

        assertNotNull(db.posReceiptDao().byId("payment-1"))
        assertEquals(
            HeldOrderPaymentState.SYNCED,
            db.heldOrderDao().paymentForTarget(payment.targetOrderId)?.syncState,
        )
    }

    @Test
    fun receiptReplayIsIdempotentAndKeepsOriginalIdentity() = runBlocking {
        val payment = heldPayment()
        db.heldOrderDao().insertPayment(payment)
        val receipt = receipt()

        db.posReceiptDao().storeAndMarkSettlementSynced(receipt, payment.localId)
        db.posReceiptDao().storeAndMarkSettlementSynced(receipt, payment.localId)

        assertEquals("order-1", db.posReceiptDao().byId(receipt.receiptId)?.orderId)
    }

    @Test
    fun directOfflineSaleIsNotSyncedWithoutItsReceipt() = runBlocking {
        val sale = LocalOrderEntity(
            localId = "local-sale-1",
            shiftId = "shift-1",
            type = "dine_in",
            estimateMinor = 12_500,
            capturedAmountMinor = 12_500,
            paymentMethod = "cash",
            tenderedMinor = 20_000,
            createdAtMillis = 1_000,
            updatedAtMillis = 1_000,
            syncState = SyncState.PENDING,
        )
        db.orderDao().capture(sale, emptyList())

        db.posReceiptDao().storeAndMarkLocalSaleSynced(
            receipt = receipt(),
            localOrderId = sale.localId,
            serverOrderId = "order-1",
            invoiceNo = "INV-1",
            totalMinor = 12_500,
        )

        val resolved = db.orderDao().withLines(sale.localId)!!.order
        assertEquals(SyncState.SYNCED, resolved.syncState)
        assertEquals("INV-1", resolved.invoiceNo)
        assertNotNull(db.posReceiptDao().byId("payment-1"))
    }

    @Test
    fun acknowledgementIsOneWayButReceiptRemainsReopenable() = runBlocking {
        db.posReceiptDao().store(receipt())
        assertNull(db.posReceiptDao().byId("payment-1")?.acknowledgedAtMillis)

        assertEquals(1, db.posReceiptDao().acknowledge("payment-1", 2_000))
        assertEquals(0, db.posReceiptDao().acknowledge("payment-1", 3_000))
        assertEquals(2_000L, db.posReceiptDao().byId("payment-1")?.acknowledgedAtMillis)
    }

    private fun heldPayment() = LocalHeldOrderPaymentEntity(
        localId = "local-payment-1",
        targetOrderId = "order-1",
        method = "cash",
        amountMinor = 12_500,
        tenderedMinor = 20_000,
        expectedTotalMinor = 12_500,
        expectedDueMinor = 12_500,
        claimToken = "claim",
        claimExpiresAtMillis = 99_000,
        claimOrderVersion = 4,
        shiftId = "shift-1",
        terminalId = "terminal-1",
        createdAtMillis = 1_000,
    )

    private fun receipt() = PosReceiptEntity(
        receiptId = "payment-1",
        orderId = "order-1",
        paymentId = "payment-1",
        shiftId = "shift-1",
        sourceKind = PosReceiptSource.DIRECT,
        sourceLabel = "Main POS",
        customerName = "Guest",
        customerPhone = null,
        orderNote = null,
        subtotalMinor = 12_500,
        discountMinor = 0,
        taxMinor = 0,
        roundOffMinor = 0,
        totalMinor = 12_500,
        dueBeforePaymentMinor = 12_500,
        method = "cash",
        amountMinor = 12_500,
        billAmountMinor = 12_500,
        tipMinor = 0,
        tenderedMinor = 20_000,
        changeMinor = 7_500,
        refExternal = null,
        paidAt = "2026-08-27T12:00:00Z",
        orderStatus = "paid",
        invoiceNo = "INV-1",
        fiscalYear = "2026-27",
        invoiceIssuedAt = "2026-08-27T12:00:00Z",
        linesJson = "[]",
        createdAtMillis = 1_500,
    )
}
