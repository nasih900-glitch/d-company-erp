package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.LocalKitchenAdvanceEntity
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.kitchen.KitchenOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KitchenAdvanceReconciliationTest {
    @Test
    fun `server state beyond stale target satisfies saved advance`() = runBlocking {
        val row = advance(target = "preparing")
        val resolver = KitchenAdvanceReconciler(
            setState = { throw ApiException("cannot move back", 422, "business_rule") },
            activeQueue = { listOf(ticket(row.orderId, "ready")) },
        )

        assertEquals(KitchenAdvanceDisposition.SATISFIED, resolver.reconcile(row).disposition)
    }

    @Test
    fun `ticket absent from authoritative active queue satisfies saved advance`() = runBlocking {
        val row = advance(target = "served")
        val resolver = KitchenAdvanceReconciler(
            setState = { throw ApiException("order has no active kitchen items", 422, "business_rule") },
            activeQueue = { emptyList() },
        )

        assertEquals(KitchenAdvanceDisposition.SATISFIED, resolver.reconcile(row).disposition)
    }

    @Test
    fun `successful response at target satisfies without queue read`() = runBlocking {
        val row = advance(target = "ready")
        var queueReads = 0
        val resolver = KitchenAdvanceReconciler(
            setState = { ticket(row.orderId, "ready") },
            activeQueue = { queueReads += 1; emptyList() },
        )

        assertEquals(KitchenAdvanceDisposition.SATISFIED, resolver.reconcile(row).disposition)
        assertEquals(0, queueReads)
    }

    @Test
    fun `server behind target becomes actionable rejection`() = runBlocking {
        val row = advance(target = "ready")
        val resolver = KitchenAdvanceReconciler(
            setState = { throw ApiException("cannot skip", 422, "business_rule") },
            activeQueue = { listOf(ticket(row.orderId, "received")) },
        )

        val result = resolver.reconcile(row)

        assertEquals(KitchenAdvanceDisposition.NEEDS_ATTENTION, result.disposition)
        assertTrue(result.message.orEmpty().contains("new on the server"))
        assertTrue(result.message.orEmpty().contains("retry or remove"))
    }

    @Test
    fun `unknown target is visible corruption and performs no network call`() = runBlocking {
        val row = advance(target = "plating")
        var calls = 0
        val resolver = KitchenAdvanceReconciler(
            setState = { calls += 1; ticket(row.orderId, "ready") },
            activeQueue = { calls += 1; emptyList() },
        )

        val result = resolver.reconcile(row)

        assertEquals(KitchenAdvanceDisposition.NEEDS_ATTENTION, result.disposition)
        assertTrue(result.message.orEmpty().contains("unknown target state"))
        assertEquals(0, calls)
    }

    @Test
    fun `invalid ticket id stays actionable instead of being mistaken for inactive`() = runBlocking {
        val row = advance(orderId = "damaged-ticket")
        val resolver = KitchenAdvanceReconciler(
            setState = { error("must not send") },
            activeQueue = { emptyList() },
        )

        val result = resolver.reconcile(row)

        assertEquals(KitchenAdvanceDisposition.NEEDS_ATTENTION, result.disposition)
        assertTrue(result.message.orEmpty().contains("damaged ticket reference"))
    }

    @Test
    fun `ambiguous write and failed verification keep row pending`() = runBlocking {
        val row = advance()
        val resolver = KitchenAdvanceReconciler(
            setState = { throw ApiException("connection lost") },
            activeQueue = { throw ApiException("still offline") },
        )

        val result = resolver.reconcile(row)

        assertEquals(KitchenAdvanceDisposition.KEEP_PENDING, result.disposition)
        assertTrue(result.message.orEmpty().contains("Keep it saved"))
    }

    @Test
    fun `unparsed successful write remains pending when verification is unavailable`() = runBlocking {
        val row = advance()
        val resolver = KitchenAdvanceReconciler(
            setState = { throw IOException("response could not be decoded") },
            activeQueue = { throw ApiException("connection lost") },
        )

        assertEquals(
            KitchenAdvanceDisposition.KEEP_PENDING,
            resolver.reconcile(row).disposition,
        )
    }

    @Test
    fun `definitive refusal stays actionable when verification is unavailable`() = runBlocking {
        val row = advance()
        val resolver = KitchenAdvanceReconciler(
            setState = { throw ApiException("permission denied", 403, "forbidden") },
            activeQueue = { throw ApiException("permission denied", 403, "forbidden") },
        )

        val result = resolver.reconcile(row)

        assertEquals(KitchenAdvanceDisposition.NEEDS_ATTENTION, result.disposition)
        assertTrue(result.message.orEmpty().contains("permission denied"))
    }

    @Test(expected = ApiException::class)
    fun `compatibility gate escapes so sync can stop app wide`() {
        runBlocking {
            val row = advance()
            KitchenAdvanceReconciler(
                setState = { throw ApiException("update required", 426, "client_update_required") },
                activeQueue = { emptyList() },
            ).reconcile(row)
        }
    }

    @Test
    fun `duplicate authoritative ticket rows remain visible for review`() = runBlocking {
        val row = advance()
        val resolver = KitchenAdvanceReconciler(
            setState = { throw ApiException("cannot move back", 422, "business_rule") },
            activeQueue = {
                listOf(ticket(row.orderId, "ready"), ticket(row.orderId, "ready"))
            },
        )

        val result = resolver.reconcile(row)

        assertEquals(KitchenAdvanceDisposition.NEEDS_ATTENTION, result.disposition)
        assertTrue(result.message.orEmpty().contains("more than once"))
    }

    private fun advance(
        orderId: String = "11111111-1111-4111-8111-111111111111",
        target: String = "preparing",
    ) = LocalKitchenAdvanceEntity(
        localId = "22222222-2222-4222-8222-222222222222",
        orderId = orderId,
        targetState = target,
        requestedAtMillis = 1_768_000_000_000L,
    )

    private fun ticket(id: String, state: String) = KitchenOrder(
        id = id,
        invoiceNo = null,
        type = "dine_in",
        tableCode = "4",
        customerName = null,
        openedAt = "2026-01-09T10:00:00Z",
        kitchenState = state,
    )
}
