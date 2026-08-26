package cloud.dcompany.erp.core.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.db.CafeActionKind
import cloud.dcompany.erp.core.db.CafeActionLine
import cloud.dcompany.erp.core.db.CafeActionPayload
import cloud.dcompany.erp.core.db.CafeActionState
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.LocalCafeActionEntity
import cloud.dcompany.erp.core.db.LocalCafeBillEntity
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.tables.CafeTable
import cloud.dcompany.erp.ui.screens.tables.Floor
import cloud.dcompany.erp.ui.screens.tables.OrderLineBody
import cloud.dcompany.erp.ui.screens.tables.OrderLinesAppendBody
import cloud.dcompany.erp.ui.screens.tables.SendToPosBody
import cloud.dcompany.erp.ui.screens.tables.TableOrder
import cloud.dcompany.erp.ui.screens.tables.TableOrderCreateBody
import cloud.dcompany.erp.ui.screens.tables.TableOrderLine
import cloud.dcompany.erp.ui.screens.tables.TablesApi
import cloud.dcompany.erp.ui.screens.tables.VoidOrderLineBody
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CafeOrderSyncCoordinatorRecoveryTest {

    private lateinit var db: ErpDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun offlineRoundsAndHandoffReplayInOrderUsingReturnedVersionsAndStableKeys() = runBlocking {
        val dao = db.cafeOrderDao()
        val bill = bill()
        assertTrue(dao.captureNewBill(bill, createAction()))
        assertTrue(dao.captureAction(bill, appendAction()))
        assertTrue(dao.captureAction(bill, sendAction()))
        val api = RecordingTablesApi()

        val result = CafeOrderSyncCoordinator(db, dao, api).push()

        assertFalse(result.stoppedOnAmbiguousFailure)
        assertTrue(result.changedHeldQueue)
        assertEquals(
            listOf(
                "create:cafe-action:create-action",
                "append:1:cafe-action:append-action",
                "send:2:cafe-action:send-action",
            ),
            api.calls,
        )
        assertEquals("client-1", api.createdLines.single().clientLineId)
        assertEquals("No sugar", api.createdLines.single().note)
        assertEquals("client-2", api.appendedLines.single().clientLineId)
        assertNull(dao.localBill(bill.localBillId))
        assertTrue(dao.actionsForBill(bill.localBillId).isEmpty())
        assertEquals("held", dao.billCacheByOrderId("server-order")?.status)
        assertEquals(3L, dao.billCacheByOrderId("server-order")?.checkoutVersion)
    }

    @Test
    fun ambiguousCreateKeepsExactActionAndRetryDoesNotCreateANewIdentity() = runBlocking {
        val dao = db.cafeOrderDao()
        val bill = bill()
        assertTrue(dao.captureNewBill(bill, createAction()))
        val api = RecordingTablesApi(failFirstCreateAmbiguously = true)
        val coordinator = CafeOrderSyncCoordinator(db, dao, api)

        val uncertain = coordinator.push()
        assertTrue(uncertain.stoppedOnAmbiguousFailure)
        val saved = requireNotNull(dao.action("create-action"))
        assertEquals(CafeActionState.PENDING, saved.state)
        assertTrue(saved.lastError.orEmpty().contains("do not repeat"))

        val recovered = coordinator.push()
        assertFalse(recovered.stoppedOnAmbiguousFailure)
        assertEquals(
            listOf(
                "create:cafe-action:create-action",
                "create:cafe-action:create-action",
            ),
            api.calls,
        )
        assertNull(dao.action("create-action"))
        assertEquals("server-order", dao.billCacheByOrderId("server-order")?.orderId)
    }

    @Test
    fun deterministicPreflightFailureBecomesVisibleRecoveryInsteadOfInfiniteRetry() = runBlocking {
        val dao = db.cafeOrderDao()
        val bill = bill(serverOrderId = "server-order", version = null)
        val send = sendAction()
        assertTrue(dao.captureNewBill(bill, send))

        val result = CafeOrderSyncCoordinator(db, dao, RecordingTablesApi()).push()

        assertFalse(result.stoppedOnAmbiguousFailure)
        val rejected = requireNotNull(dao.action(send.actionId))
        assertEquals(CafeActionState.REJECTED, rejected.state)
        assertTrue(rejected.lastError.orEmpty().contains("checkout version"))
    }

    private fun bill(serverOrderId: String? = null, version: Long? = null) =
        LocalCafeBillEntity(
            localBillId = "local-bill",
            serverOrderId = serverOrderId,
            tableId = "table-1",
            tableCode = "T1",
            shiftId = "server-shift",
            confirmedCheckoutVersion = version,
            createdAtMillis = 1_777_000_000_000,
        )

    private fun createAction() = action(
        id = "create-action",
        kind = CafeActionKind.CREATE_ROUND,
        payload = CafeActionPayload(
            lines = listOf(CafeActionLine("client-1", "menu-1", "Coffee", 1, "No sugar", 1_000)),
        ),
        dedupe = "round:table-1:client-1",
    )

    private fun appendAction() = action(
        id = "append-action",
        kind = CafeActionKind.APPEND_ROUND,
        payload = CafeActionPayload(
            lines = listOf(CafeActionLine("client-2", "menu-2", "Fries", 1, null, 500)),
        ),
        dedupe = "round:table-1:client-2",
    )

    private fun sendAction() = action(
        id = "send-action",
        kind = CafeActionKind.SEND_TO_POS,
        payload = CafeActionPayload(),
        dedupe = "send:local-bill",
    )

    private fun action(
        id: String,
        kind: String,
        payload: CafeActionPayload,
        dedupe: String,
    ) = LocalCafeActionEntity(
        actionId = id,
        localBillId = "local-bill",
        kind = kind,
        payload = payload,
        dedupeKey = dedupe,
        createdAtMillis = 1_777_000_000_000,
    )
}

private class RecordingTablesApi(
    private var failFirstCreateAmbiguously: Boolean = false,
) : TablesApi {
    val calls = mutableListOf<String>()
    var createdLines: List<OrderLineBody> = emptyList()
    var appendedLines: List<OrderLineBody> = emptyList()
    private var current = order(version = 0, lines = emptyList())

    override suspend fun floors(): List<Floor> = emptyList()

    override suspend fun tables(): List<CafeTable> = emptyList()

    override suspend fun createOrder(
        body: TableOrderCreateBody,
        key: String,
        provenance: Map<String, String>,
    ): TableOrder {
        calls += "create:$key"
        if (failFirstCreateAmbiguously) {
            failFirstCreateAmbiguously = false
            throw ApiException("Gateway timeout", status = 504)
        }
        createdLines = body.lines
        current = order(
            version = 1,
            lines = body.lines.mapIndexed { index, line -> line.toRead("server-line-$index") },
        )
        return current
    }

    override suspend fun appendRound(
        id: String,
        body: OrderLinesAppendBody,
        key: String,
        provenance: Map<String, String>,
    ): TableOrder {
        calls += "append:${body.expectedCheckoutVersion}:$key"
        appendedLines = body.lines
        current = current.copy(
            checkoutVersion = body.expectedCheckoutVersion + 1,
            totalMinor = 1_500,
            subtotalMinor = 1_500,
            lines = current.lines + body.lines.mapIndexed { index, line ->
                line.toRead("server-appended-$index")
            },
        )
        return current
    }

    override suspend fun voidLine(
        orderId: String,
        lineId: String,
        body: VoidOrderLineBody,
        key: String,
        provenance: Map<String, String>,
    ): TableOrder = error("Not used")

    override suspend fun sendToPos(
        id: String,
        body: SendToPosBody,
        key: String,
        provenance: Map<String, String>,
    ): TableOrder {
        calls += "send:${body.expectedCheckoutVersion}:$key"
        current = current.copy(
            status = "held",
            heldAt = "2026-08-26T11:00:00Z",
            checkoutVersion = body.expectedCheckoutVersion + 1,
        )
        return current
    }

    override suspend fun activeOrders(): List<TableOrder> = listOf(current)

    override suspend fun order(id: String): TableOrder = current

    private companion object {
        fun order(version: Long, lines: List<TableOrderLine>) = TableOrder(
            id = "server-order",
            status = "open",
            tableId = "table-1",
            subtotalMinor = lines.sumOf { it.lineTotalMinor },
            totalMinor = lines.sumOf { it.lineTotalMinor },
            openedAt = "2026-08-26T10:00:00Z",
            checkoutVersion = version,
            lines = lines,
        )

        fun OrderLineBody.toRead(id: String) = TableOrderLine(
            id = id,
            clientLineId = clientLineId,
            menuItemId = menuItemId,
            name = if (menuItemId == "menu-1") "Coffee" else "Fries",
            qty = qty.toDouble(),
            unitPriceMinor = if (menuItemId == "menu-1") 1_000 else 500,
            lineTotalMinor = if (menuItemId == "menu-1") 1_000L * qty else 500L * qty,
            note = note,
            kitchenReleasedAt = "2026-08-26T10:00:00Z",
            kitchenRoundNo = if (menuItemId == "menu-1") 1 else 2,
        )
    }
}
