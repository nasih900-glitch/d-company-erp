package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.gaming.GameSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GamingSessionResolutionTest {

    @Test
    fun `exact resolution appends authoritative terminal sessions once`() = runBlocking {
        val board = listOf(session("active", "active"))
        val requested = mutableListOf<String>()

        val result = resolveMissingGamingSessions(
            boardSessions = board,
            localServerIds = listOf("active", "cancelled", "cancelled", "sent"),
            fetchExact = { id ->
                requested += id
                when (id) {
                    "cancelled" -> session(id, "cancelled")
                    "sent" -> session(id, "ended", orderId = "order-1")
                    else -> error("Unexpected exact lookup: $id")
                }
            },
        )

        assertEquals(listOf("cancelled", "sent"), requested)
        assertEquals(listOf("active", "cancelled", "sent"), result.map(GameSession::id))
        assertSame(board.single(), result.first())
    }

    @Test
    fun `404 retains board result and continues resolving later sessions`() = runBlocking {
        val notFound = mutableListOf<String>()
        val requested = mutableListOf<String>()

        val result = resolveMissingGamingSessions(
            boardSessions = listOf(session("active", "active")),
            localServerIds = listOf("deleted", "cancelled"),
            fetchExact = { id ->
                requested += id
                if (id == "deleted") throw ApiException("session not found", status = 404)
                session(id, "cancelled")
            },
            onNotFound = notFound::add,
        )

        assertEquals(listOf("deleted", "cancelled"), requested)
        assertEquals(listOf("deleted"), notFound)
        assertEquals(listOf("active", "cancelled"), result.map(GameSession::id))
    }

    @Test
    fun `mismatched exact response aborts reconciliation`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                resolveMissingGamingSessions(
                    boardSessions = emptyList(),
                    localServerIds = listOf("expected"),
                    fetchExact = { session("different", "cancelled") },
                )
            }
        }

        assertEquals(
            "Exact Gaming session response did not match the requested session.",
            failure.message,
        )
    }

    @Test
    fun `non-404 exact failure aborts reconciliation unchanged`() {
        val expected = ApiException("server unavailable", status = 503)

        val failure = assertThrows(ApiException::class.java) {
            runBlocking {
                resolveMissingGamingSessions(
                    boardSessions = listOf(session("active", "active")),
                    localServerIds = listOf("cancelled"),
                    fetchExact = { throw expected },
                )
            }
        }

        assertSame(expected, failure)
    }

    private fun session(
        id: String,
        status: String,
        orderId: String? = null,
    ) = GameSession(
        id = id,
        stationId = "station-$id",
        shiftId = "shift-1",
        status = status,
        startAt = "2026-09-04T12:00:00Z",
        endAt = if (status == "active") null else "2026-09-04T12:30:00Z",
        orderId = orderId,
    )
}
