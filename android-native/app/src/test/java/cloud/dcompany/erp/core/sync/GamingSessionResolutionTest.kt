package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.db.GamingCleanupLocalSnapshot
import cloud.dcompany.erp.core.db.gamingCleanupSnapshotSha256
import cloud.dcompany.erp.ui.screens.gaming.GameSession
import cloud.dcompany.erp.ui.screens.gaming.GamingCleanupReconciliation
import cloud.dcompany.erp.ui.screens.gaming.GamingCleanupReportBody
import cloud.dcompany.erp.ui.screens.gaming.SessionStartBody
import cloud.dcompany.erp.ui.screens.gaming.SessionStopBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GamingSessionResolutionTest {

    @Test
    fun `cleanup candidate hash binds scope state and child count`() {
        val body = GamingCleanupReportBody(
            installationId = "11111111-1111-4111-8111-111111111111",
            localActionId = "22222222-2222-4222-8222-222222222222",
            serverSessionId = "33333333-3333-4333-8333-333333333333",
            branchId = "44444444-4444-4444-8444-444444444444",
            terminalId = "55555555-5555-4555-8555-555555555555",
            stationId = "66666666-6666-4666-8666-666666666666",
            reportedLocalState = "stop_pending",
            localSnapshot = GamingCleanupLocalSnapshot(
                shiftId = "77777777-7777-4777-8777-777777777777",
                startedAtMillis = 1_795_000_000_123,
                endedAtMillis = 1_795_003_600_456,
                timerMinutes = 60,
                timerEndsAtMillis = 1_795_003_600_123,
                billableMinutes = 60,
                amountMinor = 15_000,
                ratePerHourMinor = 15_000,
                customerName = "Reviewed customer",
                extraControllers = 0,
                evidenceRevision = 7,
            ),
            localSnapshotSha256 = "a".repeat(64),
            startRequestHash = "b".repeat(64),
            stopRequestHash = "c".repeat(64),
            candidateSha256 = "0".repeat(64),
            unresolvedChildCount = 0,
        )
        val first = gamingCleanupCandidateSha256(body)
        assertEquals(64, first.length)
        org.junit.Assert.assertNotEquals(
            first,
            gamingCleanupCandidateSha256(body.copy(unresolvedChildCount = 1)),
        )
    }

    @Test
    fun `start and stop request hashes use exact ApiClient JSON order and defaults`() {
        val snapshot = GamingCleanupLocalSnapshot(
            shiftId = "77777777-7777-4777-8777-777777777777",
            startedAtMillis = 1_795_000_000_123,
            endedAtMillis = 1_795_003_600_456,
            timerMinutes = 60,
            timerEndsAtMillis = 1_795_003_600_123,
            billableMinutes = 60,
            amountMinor = 15_000,
            ratePerHourMinor = 15_000,
            customerName = "Reviewed customer",
            extraControllers = 0,
            evidenceRevision = 7,
        )
        assertEquals(
            "b4696654edb865f03bb56620ba017b7f59cc43553d798251aac542605d63936e",
            gamingCleanupSnapshotSha256(snapshot),
        )
        val start = SessionStartBody(
            stationId = "66666666-6666-4666-8666-666666666666",
            shiftId = "77777777-7777-4777-8777-777777777777",
            startedAt = "2026-11-18T11:06:40.123Z",
            customerName = "Reviewed customer",
            timerMinutes = 60,
            expectedRatePerHourMinor = 15_000,
        )
        assertEquals(
            "{\"station_id\":\"66666666-6666-4666-8666-666666666666\"," +
                "\"shift_id\":\"77777777-7777-4777-8777-777777777777\"," +
                "\"started_at\":\"2026-11-18T11:06:40.123Z\"," +
                "\"customer_name\":\"Reviewed customer\",\"timer_minutes\":60," +
                "\"expected_rate_per_hour_minor\":15000}",
            ApiClient.json.encodeToString(start),
        )
        val startHash = gamingStartRequestHash(
            "22222222-2222-4222-8222-222222222222", start,
        )
        assertEquals(
            "f6e013c354f5887d7a57a6d4fe85554dbc5f6e6c6bf7b116be9d30532aa59d1b",
            startHash,
        )
        val stop = SessionStopBody("2026-11-18T12:06:40.456Z")
        assertEquals("{\"ended_at\":\"2026-11-18T12:06:40.456Z\"}", ApiClient.json.encodeToString(stop))
        val stopHash = gamingStopRequestHash(
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            stop,
        )
        assertEquals(
            "9cc5a00061afc4a8e497db2effb54a5a58db720f030d27af5c85fcb2cb8ef3e3",
            stopHash,
        )
        val report = GamingCleanupReportBody(
            installationId = "11111111-1111-4111-8111-111111111111",
            localActionId = "22222222-2222-4222-8222-222222222222",
            serverSessionId = "33333333-3333-4333-8333-333333333333",
            branchId = "44444444-4444-4444-8444-444444444444",
            terminalId = "55555555-5555-4555-8555-555555555555",
            stationId = "66666666-6666-4666-8666-666666666666",
            reportedLocalState = "stop_pending",
            localSnapshot = snapshot,
            localSnapshotSha256 = gamingCleanupSnapshotSha256(snapshot),
            startRequestHash = startHash,
            stopRequestHash = stopHash,
            candidateSha256 = "0".repeat(64),
            unresolvedChildCount = 0,
        )
        assertEquals(
            "3d7bbe08e7d0695e6e7bc4d78b6f4a59558ca8b63d53b3a5a0af87bbc10bcaea",
            gamingCleanupCandidateSha256(report),
        )
    }

    @Test
    fun `cleanup acknowledgement must match exact terminal evidence`() {
        val acknowledgement = GamingCleanupReconciliation(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            installationId = "11111111-1111-4111-8111-111111111111",
            branchId = "44444444-4444-4444-8444-444444444444",
            terminalId = "55555555-5555-4555-8555-555555555555",
            stationId = "66666666-6666-4666-8666-666666666666",
            localActionId = "22222222-2222-4222-8222-222222222222",
            serverSessionId = "33333333-3333-4333-8333-333333333333",
            revision = 1,
            isCurrent = true,
            reportedLocalState = "stop_pending",
            localEvidenceRevision = 7,
            localSnapshotSha256 = "a".repeat(64),
            candidateSha256 = "b".repeat(64),
            unresolvedChildCount = 0,
            cleanupReceiptAuditId = 28204,
            status = "applied",
            approvalReason = "Owner reviewed exact evidence",
            deviceDirective = "already_applied",
        )
        val matches = { row: GamingCleanupReconciliation ->
            cleanupAcknowledgementMatches(
                row,
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "b".repeat(64),
            )
        }
        assertEquals(true, matches(acknowledgement))
        assertEquals(false, matches(acknowledgement.copy(candidateSha256 = "c".repeat(64))))
        assertEquals(false, matches(acknowledgement.copy(status = "approved")))
        assertEquals(false, matches(acknowledgement.copy(deviceDirective = "cleanup_retire")))
    }

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
