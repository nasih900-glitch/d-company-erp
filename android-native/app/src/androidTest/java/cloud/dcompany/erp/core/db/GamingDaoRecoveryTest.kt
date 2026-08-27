package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamingDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: GamingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.gamingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun legacyRejectedRowsRecoverIntoTheCorrectHumanResolutionQueues() = runBlocking {
        dao.insertLocalSession(legacyRow("start", serverId = null, status = "starting"))
        dao.insertLocalSession(legacyRow("stop", serverId = "session-2", status = "stopping"))
        dao.insertLocalSession(
            legacyRow(
                "send",
                serverId = "session-3",
                status = "ended",
                endAtMillis = 2_000,
                billableMinutes = 15,
                amountMinor = 37_500,
            ),
        )
        dao.insertLocalSession(
            legacyRow(
                "sent",
                serverId = "session-4",
                status = "ended",
                endAtMillis = 2_100,
                amountMinor = 40_000,
                orderId = "order-4",
            ),
        )

        val quarantined = db.outboxSafetyDao().unresolvedGroups().single()
        assertEquals("gaming_sessions", quarantined.resource)
        assertEquals("rejected", quarantined.state)
        assertEquals(4, quarantined.count)

        assertEquals(4, dao.recoverLegacyRejectedSessions())
        assertEquals(0, dao.recoverLegacyRejectedSessions())
        assertEquals(3, dao.observeRejectedCount().first())

        val start = dao.localSessionById("start")!!
        assertEquals(GamingSessionState.START_REJECTED, start.state)
        assertEquals("start_failed", start.status)
        assertEquals("legacy failure", start.lastError)

        val stop = dao.localSessionById("stop")!!
        assertEquals(GamingSessionState.STOP_REJECTED, stop.state)
        assertEquals("active", stop.status)
        assertEquals("session-2", stop.serverId)

        val send = dao.localSessionById("send")!!
        assertEquals(GamingSessionState.SEND_REJECTED, send.state)
        assertEquals("ended", send.status)
        assertEquals(37_500L, send.amountMinor)

        val sent = dao.localSessionById("sent")!!
        assertEquals(GamingSessionState.SENT, sent.state)
        assertEquals("order-4", sent.orderId)

        dao.requestSessionStop("stop", stoppedAtMillis = 3_000)
        assertEquals(1, dao.requestSessionSend("send"))

        val pushable = dao.pushableSessions().associateBy { it.localId }
        // A definitively rejected Start is retained and never returned to the
        // automatic outbox. Absence of a Stop timestamp is not proof that the
        // customer did not begin play; only the protected audit flow can
        // release its station and shift dependency.
        assertFalse(pushable.containsKey("start"))
        assertEquals(GamingSessionState.START_REJECTED, dao.localSessionById("start")?.state)
        assertEquals(GamingSessionState.STOP_PENDING, pushable.getValue("stop").state)
        assertEquals("stopping", pushable.getValue("stop").status)
        // This migrated legacy refusal never captured an original Stop tap.
        // Retrying must not invent one; SyncEngine uses the bodyless legacy
        // endpoint rather than silently changing the amount basis.
        assertNull(pushable.getValue("stop").endAtMillis)
        assertEquals(GamingSessionState.SEND_PENDING, pushable.getValue("send").state)
        assertNull(pushable.getValue("send").lastError)
        assertEquals(2, pushable.size)
    }

    @Test
    fun authoritativeServerOutcomesResolveStaleLocalOverlays() = runBlocking {
        dao.insertLocalSession(localRow("stopped", "session-stop", GamingSessionState.STOP_PENDING))
        dao.insertLocalSession(localRow("sent", "session-send", GamingSessionState.SEND_REJECTED))
        dao.insertLocalSession(localRow("cancelled", "session-cancel", GamingSessionState.SEND_REJECTED))

        dao.replaceSessionCache(
            listOf(
                serverRow("session-stop", status = "ended", amountMinor = 12_000),
                serverRow("session-send", status = "ended", amountMinor = 15_000, orderId = "order-1"),
                serverRow("session-cancel", status = "cancelled", amountMinor = 0),
            ),
        )

        assertEquals(
            GamingSessionState.ENDED_UNBILLED,
            dao.localSessionById("stopped")?.state,
        )
        assertEquals(GamingSessionState.SENT, dao.localSessionById("sent")?.state)
        assertEquals("order-1", dao.localSessionById("sent")?.orderId)
        assertEquals(GamingSessionState.CANCELLED, dao.localSessionById("cancelled")?.state)

        val visibleOverlays = dao.observeActiveLocalSessions().first()
        assertEquals(listOf("stopped"), visibleOverlays.map { it.localId })
    }

    @Test
    fun authoritativePullRefreshesSyncedOverlayWithoutLosingPendingStopCapture() = runBlocking {
        dao.insertLocalSession(
            localRow("refresh", "session-refresh", GamingSessionState.STOP_PENDING)
                .copy(
                    stationId = "station-old",
                    shiftId = "shift-old",
                    customerPhone = "1111111111",
                    timerMinutes = 30,
                    startedAtMillis = 1_000,
                    status = "stopping",
                    endAtMillis = 9_999,
                    timerEndsAtMillis = 31_000,
                    amountMinor = 5_000,
                    ratePerHourMinor = 10_000,
                    packageId = "package-old",
                    extraControllers = 0,
                    lastError = "keep pending-leg evidence",
                ),
        )

        dao.replaceSessionCache(
            listOf(
                serverRow("session-refresh", status = "active", amountMinor = 7_500).copy(
                    stationId = "station-new",
                    shiftId = "shift-new",
                    startAtMillis = 2_000,
                    timerMinutes = 60,
                    timerEndsAtMillis = 62_000,
                    ratePerHourMinor = 15_000,
                    packageId = "package-new",
                    extraControllers = 2,
                    customerPhone = "2222222222",
                    endAtMillis = null,
                    billableMinutes = null,
                ),
            ),
        )

        val refreshed = dao.localSessionById("refresh")!!
        assertEquals(GamingSessionState.STOP_PENDING, refreshed.state)
        assertEquals("station-new", refreshed.stationId)
        assertEquals("shift-new", refreshed.shiftId)
        assertEquals("2222222222", refreshed.customerPhone)
        assertEquals(60, refreshed.timerMinutes)
        assertEquals(2_000L, refreshed.startedAtMillis)
        assertEquals("active", refreshed.status)
        // This is the pending stop command payload until stop confirms.
        assertEquals(9_999L, refreshed.endAtMillis)
        assertEquals(62_000L, refreshed.timerEndsAtMillis)
        assertNull(refreshed.billableMinutes)
        assertEquals(7_500L, refreshed.amountMinor)
        assertEquals(15_000L, refreshed.ratePerHourMinor)
        assertEquals("package-new", refreshed.packageId)
        assertEquals(2, refreshed.extraControllers)
        assertEquals("keep pending-leg evidence", refreshed.lastError)
    }

    @Test
    fun rapidSecondStartIsRejectedUntilTheFirstLifecycleIsTerminal() = runBlocking {
        val first = localRow(
            localId = "first",
            serverId = null,
            state = GamingSessionState.START_PENDING,
            stationId = "station-shared",
        )
        val second = first.copy(localId = "second")

        assertTrue(dao.insertStartIfStationAvailable(first))
        assertFalse(dao.insertStartIfStationAvailable(second))

        dao.markSessionSent("first", orderId = "order-first", amountMinor = 10_000)
        assertTrue(dao.insertStartIfStationAvailable(second))
    }

    @Test
    fun confirmedStartCannotRemainStrandedInStartPendingAfterRestart() = runBlocking {
        val row = localRow(
            localId = "confirmed-start",
            serverId = null,
            state = GamingSessionState.START_PENDING,
        ).copy(
            packageId = "base-60",
            packagePriceMinor = 15_000,
            packageDurationMinutes = 60,
            packageVariant = "solo",
        )
        dao.insertLocalSession(row)

        dao.setSessionStarted(
            localId = row.localId,
            serverId = "session-confirmed",
            status = "active",
            shiftId = "shift-server",
            startedAtMillis = 2_000,
            timerMinutes = 60,
            timerEndsAtMillis = 62_000,
            amountMinor = 15_000,
            ratePerHourMinor = 15_000,
            packageId = "base-60",
            billingMode = "package",
            packagePriceMinor = 15_000,
            packageDurationMinutes = 60,
            packageVariant = "solo",
            packageStationTypeSnapshot = "ps5",
            extraControllers = 0,
        )

        val atomicallyConfirmed = dao.localSessionById(row.localId)!!
        assertEquals(GamingSessionState.START_SYNCED, atomicallyConfirmed.state)
        assertEquals("session-confirmed", atomicallyConfirmed.serverId)
        assertEquals("shift-server", atomicallyConfirmed.shiftId)
        assertEquals(60, atomicallyConfirmed.timerMinutes)
        assertEquals(15_000L, atomicallyConfirmed.amountMinor)
        assertEquals(15_000L, atomicallyConfirmed.packagePriceMinor)
        assertEquals(60, atomicallyConfirmed.packageDurationMinutes)
        assertEquals("solo", atomicallyConfirmed.packageVariant)
        assertEquals(1, dao.requestSessionStop(row.localId, stoppedAtMillis = 42_000))
        assertEquals(42_000L, dao.localSessionById(row.localId)?.endAtMillis)

        dao.transitionSessionState(
            row.localId,
            GamingSessionState.STOP_PENDING,
            GamingSessionState.START_PENDING,
        )
        dao.replaceSessionCache(listOf(serverRow("session-confirmed", status = "active", amountMinor = 0)))
        assertEquals(GamingSessionState.START_SYNCED, dao.localSessionById(row.localId)?.state)
    }

    @Test
    fun offlineStartCanCaptureOneExactStopBeforeStartSyncs() = runBlocking {
        val row = localRow("offline-stop", serverId = null, state = GamingSessionState.START_PENDING).copy(
            timerMinutes = 60,
            timerEndsAtMillis = 61_000,
        )
        dao.insertLocalSession(row)

        assertEquals(1, dao.requestSessionStop(row.localId, stoppedAtMillis = 42_123))
        assertEquals(0, dao.requestSessionStop(row.localId, stoppedAtMillis = 99_999))

        val captured = dao.localSessionById(row.localId)!!
        assertEquals(GamingSessionState.STOP_PENDING, captured.state)
        assertEquals("stopping", captured.status)
        assertEquals(42_123L, captured.endAtMillis)

        // A process death after the backdated Start response but before Stop
        // confirmation must not resurrect this card as active.
        dao.setSessionStarted(
            localId = row.localId,
            serverId = "server-offline-stop",
            status = "active",
            shiftId = "shift-server",
            startedAtMillis = 1_000,
            timerMinutes = 60,
            timerEndsAtMillis = 61_000,
            amountMinor = 10_000,
            ratePerHourMinor = 10_000,
            packageId = null,
            billingMode = "hourly",
            packagePriceMinor = null,
            packageDurationMinutes = null,
            packageVariant = null,
            packageStationTypeSnapshot = null,
            extraControllers = 0,
        )
        val startConfirmed = dao.localSessionById(row.localId)!!
        assertEquals(GamingSessionState.STOP_PENDING, startConfirmed.state)
        assertEquals("stopping", startConfirmed.status)
        assertEquals(42_123L, startConfirmed.endAtMillis)

        // A definitive refusal may be retried, but its immutable employee tap
        // remains the billing end; retry time must never extend the session.
        dao.markSessionRejected(
            row.localId,
            GamingSessionState.STOP_REJECTED,
            "temporary shift conflict",
        )
        assertEquals(1, dao.requestSessionStop(row.localId, stoppedAtMillis = 99_999))
        val retried = dao.localSessionById(row.localId)!!
        assertEquals(GamingSessionState.STOP_PENDING, retried.state)
        assertEquals("stopping", retried.status)
        assertEquals(42_123L, retried.endAtMillis)
    }

    @Test
    fun restoredLegacyPackageStartIsQuarantinedWithoutLosingCapturedStopEvidence() = runBlocking {
        val legacy = localRow(
            localId = "legacy-package-stop",
            serverId = null,
            state = GamingSessionState.STOP_PENDING,
        ).copy(
            packageId = "base-legacy",
            packagePriceMinor = null,
            packageDurationMinutes = null,
            packageVariant = null,
            endAtMillis = 42_123,
            status = "stopping",
        )
        dao.insertLocalSession(legacy)

        assertEquals(1, dao.quarantineUnverifiableLegacyPackageStarts())
        val quarantined = dao.localSessionById(legacy.localId)!!
        assertEquals(GamingSessionState.START_REJECTED, quarantined.state)
        assertEquals("start_failed", quarantined.status)
        assertEquals(42_123L, quarantined.endAtMillis)
        assertEquals(LEGACY_PACKAGE_START_REVIEW_ERROR, quarantined.lastError)
        assertEquals(legacy.localId, dao.localSessionById(legacy.localId)?.localId)
        assertEquals("gaming_sessions", db.outboxSafetyDao().unresolvedGroups().single().resource)
    }

    @Test
    fun quarantinedPackageStartsReleaseOnlyAfterRetainedAuditReceipt() = runBlocking {
        val noPlay = localRow(
            localId = "11111111-1111-4111-8111-111111111111",
            serverId = null,
            state = GamingSessionState.START_PENDING,
            stationId = "station-no-play",
        ).copy(
            shiftId = "shift-legacy",
            packageId = "base-legacy",
            packagePriceMinor = null,
            packageDurationMinutes = null,
            packageVariant = null,
            endAtMillis = null,
        )
        val capturedPlay = noPlay.copy(
            localId = "22222222-2222-4222-8222-222222222222",
            stationId = "station-played",
            endAtMillis = 42_123,
            state = GamingSessionState.STOP_PENDING,
            status = "stopping",
        )
        dao.insertLocalSession(noPlay)
        dao.insertLocalSession(capturedPlay)
        assertEquals(2, dao.quarantineUnverifiableLegacyPackageStarts())

        assertEquals(
            1,
            dao.captureLegacyPackageResolution(
                localId = noPlay.localId,
                resolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Verified that the customer never began play",
                referenceOrderId = null,
                actorUserId = "owner-1",
                capturedAtMillis = 50_000,
            ),
        )
        assertEquals(
            1,
            dao.markLegacyPackageResolutionAttempt(
                noPlay.localId,
                GamingLegacyResolutionAttemptState.AMBIGUOUS,
                "response lost",
            ),
        )
        // An ambiguous request is immutable: a different decision cannot
        // replace it while the first audit receipt may already exist.
        assertEquals(
            0,
            dao.captureLegacyPackageResolution(
                localId = noPlay.localId,
                resolution = GamingLegacyResolution.MANUAL_BILL_RECORDED,
                reason = "Changed decision",
                referenceOrderId = "33333333-3333-4333-8333-333333333333",
                actorUserId = "owner-1",
                capturedAtMillis = 60_000,
            ),
        )
        // A different protected owner must not replace an ambiguous attempt.
        // Backend idempotency is actor-bound, so preserving the original actor
        // is as important as preserving the decision body and action UUID.
        assertEquals(
            0,
            dao.captureLegacyPackageResolution(
                localId = noPlay.localId,
                resolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Verified that the customer never began play",
                referenceOrderId = null,
                actorUserId = "owner-2",
                capturedAtMillis = 65_000,
            ),
        )
        val immutableAttempt = dao.localSessionById(noPlay.localId)!!
        assertEquals("owner-1", immutableAttempt.legacyResolvedByUserId)
        assertEquals(
            GamingLegacyResolution.CONFIRMED_NO_PLAY,
            immutableAttempt.legacyResolution,
        )
        assertEquals(50_000L, immutableAttempt.legacyResolutionCapturedAtMillis)
        assertEquals(
            1,
            dao.confirmLegacyPackageResolution(
                localId = noPlay.localId,
                resolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Verified that the customer never began play",
                referenceOrderId = null,
                actorUserId = "owner-1",
                receiptId = 91,
                resolvedAtMillis = 70_000,
            ),
        )
        val noPlayResolved = dao.localSessionById(noPlay.localId)!!
        assertEquals(GamingSessionState.LEGACY_RESOLVED, noPlayResolved.state)
        assertEquals(GamingLegacyResolutionAttemptState.RESOLVED, noPlayResolved.legacyResolutionAttemptState)
        assertEquals(91L, noPlayResolved.legacyResolutionReceiptId)
        assertEquals(70_000L, noPlayResolved.legacyResolvedAtMillis)
        assertEquals("owner-1", noPlayResolved.legacyResolvedByUserId)
        assertEquals(
            "Verified that the customer never began play",
            noPlayResolved.legacyResolutionReason,
        )
        assertTrue(
            dao.insertStartIfStationAvailable(
                localRow("replacement", null, GamingSessionState.START_PENDING, "station-no-play"),
            ),
        )
        dao.markSessionSent("replacement", orderId = "replacement-order", amountMinor = 0)

        assertEquals(
            1,
            dao.captureLegacyPackageResolution(
                localId = capturedPlay.localId,
                resolution = GamingLegacyResolution.MANUAL_BILL_RECORDED,
                reason = "Matched to the separately collected POS receipt",
                referenceOrderId = "33333333-3333-4333-8333-333333333333",
                actorUserId = "owner-1",
                capturedAtMillis = 80_000,
            ),
        )
        assertEquals(
            1,
            dao.markLegacyPackageResolutionAttempt(
                capturedPlay.localId,
                GamingLegacyResolutionAttemptState.REJECTED,
                "order was voided",
            ),
        )
        // A definitive refusal made no server record, so the owner may correct
        // the immutable body for the next attempt while the row stays retained.
        assertEquals(
            1,
            dao.captureLegacyPackageResolution(
                localId = capturedPlay.localId,
                resolution = GamingLegacyResolution.MANUAL_BILL_RECORDED,
                reason = "Matched to the corrected non-voided POS receipt",
                referenceOrderId = "44444444-4444-4444-8444-444444444444",
                actorUserId = "owner-1",
                capturedAtMillis = 90_000,
            ),
        )
        assertEquals(
            1,
            dao.confirmLegacyPackageResolution(
                localId = capturedPlay.localId,
                resolution = GamingLegacyResolution.MANUAL_BILL_RECORDED,
                reason = "Matched to the corrected non-voided POS receipt",
                referenceOrderId = "44444444-4444-4444-8444-444444444444",
                actorUserId = "owner-1",
                receiptId = 92,
                resolvedAtMillis = 100_000,
            ),
        )
        val playedResolved = dao.localSessionById(capturedPlay.localId)!!
        assertEquals(GamingSessionState.LEGACY_RESOLVED, playedResolved.state)
        assertEquals(42_123L, playedResolved.endAtMillis)
        assertEquals(92L, playedResolved.legacyResolutionReceiptId)
        assertEquals(
            "44444444-4444-4444-8444-444444444444",
            playedResolved.legacyResolutionReferenceOrderId,
        )
        assertEquals(0, db.shiftDao().exactDependentRecordCount("shift-legacy"))
        assertTrue(db.outboxSafetyDao().unresolvedGroups().none { it.resource == "gaming_sessions" })
    }

    @Test
    fun recoveredActiveServerStartRestoresTheSameCapturedStopAction() = runBlocking {
        val action = localRow(
            localId = "55555555-5555-4555-8555-555555555555",
            serverId = null,
            state = GamingSessionState.START_REJECTED,
            stationId = "station-recovered-stop",
        ).copy(
            shiftId = "local-shift-1",
            status = "start_failed",
            startedAtMillis = 10_000,
            endAtMillis = 42_123,
            packageId = "retired-base-package",
            lastError = LEGACY_PACKAGE_START_REVIEW_ERROR,
        )
        dao.insertLocalSession(action)
        assertEquals(
            1,
            dao.captureLegacyPackageResolution(
                localId = action.localId,
                resolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
                reason = "Recover the exact accepted Start and replay its Stop",
                referenceOrderId = null,
                actorUserId = "owner-1",
                capturedAtMillis = 50_000,
            ),
        )
        assertEquals(
            1,
            dao.markLegacyPackageResolutionAttempt(
                action.localId,
                GamingLegacyResolutionAttemptState.AMBIGUOUS,
                "recovery response lost",
            ),
        )
        assertEquals(
            0,
            dao.captureLegacyPackageResolution(
                localId = action.localId,
                resolution = GamingLegacyResolution.MANUAL_BILL_RECORDED,
                reason = "Do not replace a possibly committed recovery probe",
                referenceOrderId = "33333333-3333-4333-8333-333333333333",
                actorUserId = "owner-2",
                capturedAtMillis = 55_000,
            ),
        )
        val exactAttempt = dao.localSessionById(action.localId)!!
        assertEquals(GamingLegacyResolution.SERVER_SESSION_RECOVERED, exactAttempt.legacyResolution)
        assertNull(exactAttempt.legacyResolutionReferenceOrderId)
        assertEquals("owner-1", exactAttempt.legacyResolvedByUserId)
        val authoritative = GamingSessionCacheEntity(
            id = "server-session-1",
            stationId = action.stationId,
            shiftId = "server-shift-1",
            status = "active",
            startAtMillis = 10_000,
            timerMinutes = 60,
            timerEndsAtMillis = 70_000,
            amountMinor = 15_000,
            ratePerHourMinor = 15_000,
            packageId = null,
            billingMode = "package",
            packagePriceMinorSnapshot = 15_000,
            packageDurationMinutesSnapshot = 60,
            packageVariantSnapshot = "solo",
            packageStationTypeSnapshot = "ps5",
        )

        assertTrue(
            dao.confirmRecoveredLegacyServerSession(
                localId = action.localId,
                capturedResolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
                reason = "Recover the exact accepted Start and replay its Stop",
                referenceOrderId = null,
                actorUserId = "owner-1",
                receiptId = 101,
                resolvedAtMillis = 60_000,
                authoritative = authoritative,
                disposition = RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP,
            ),
        )

        val recovered = dao.localSessionById(action.localId)!!
        assertEquals(action.localId, recovered.localId)
        assertEquals(authoritative.id, recovered.serverId)
        assertEquals(GamingSessionState.STOP_PENDING, recovered.state)
        assertEquals("stopping", recovered.status)
        assertEquals(42_123L, recovered.endAtMillis)
        assertEquals(10_000L, recovered.legacyOriginalCapturedStartAtMillis)
        assertEquals(42_123L, recovered.legacyOriginalCapturedStopAtMillis)
        assertEquals(authoritative.startAtMillis, recovered.startedAtMillis)
        assertEquals(authoritative.stationId, recovered.stationId)
        assertEquals(authoritative.shiftId, recovered.shiftId)
        assertEquals(authoritative.timerMinutes, recovered.timerMinutes)
        assertEquals(authoritative.amountMinor, recovered.amountMinor)
        assertEquals("owner-1", recovered.legacyResolvedByUserId)
        assertEquals(101L, recovered.legacyResolutionReceiptId)
        assertEquals(GamingLegacyResolutionAttemptState.RESOLVED, recovered.legacyResolutionAttemptState)
        assertEquals(listOf(action.localId), dao.pushableSessions().map { it.localId })
        assertEquals(authoritative, dao.observeSessionCache().first().single())
    }

    @Test
    fun recoveredV27PackageStopClampsReplayToLaterAuthoritativeStart() = runBlocking {
        val action = localRow(
            localId = "66666666-6666-4666-8666-666666666666",
            serverId = null,
            state = GamingSessionState.START_REJECTED,
            stationId = "station-chronology",
        ).copy(
            shiftId = "local-shift-chronology",
            status = "start_failed",
            startedAtMillis = 10_000,
            endAtMillis = 20_000,
            packageId = "base-chronology",
            lastError = LEGACY_PACKAGE_START_REVIEW_ERROR,
        )
        dao.insertLocalSession(action)
        assertEquals(
            1,
            dao.captureLegacyPackageResolution(
                localId = action.localId,
                resolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Owner reviewed response-lost start chronology",
                referenceOrderId = null,
                actorUserId = "owner-1",
                capturedAtMillis = 50_000,
            ),
        )
        val authoritative = GamingSessionCacheEntity(
            id = "server-chronology",
            stationId = action.stationId,
            shiftId = "server-shift-chronology",
            status = "active",
            // v27 omitted started_at, so receipt time can follow local Stop.
            startAtMillis = 30_000,
            timerMinutes = 60,
            amountMinor = 15_000,
            ratePerHourMinor = 15_000,
            billingMode = "package",
            packagePriceMinorSnapshot = 15_000,
            packageDurationMinutesSnapshot = 60,
            packageVariantSnapshot = "solo",
            packageStationTypeSnapshot = "ps5",
        )

        assertTrue(
            dao.confirmRecoveredLegacyServerSession(
                localId = action.localId,
                capturedResolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Owner reviewed response-lost start chronology",
                referenceOrderId = null,
                actorUserId = "owner-1",
                receiptId = 102,
                resolvedAtMillis = 60_000,
                authoritative = authoritative,
                disposition = RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP,
            ),
        )
        val recovered = dao.localSessionById(action.localId)!!
        assertEquals(GamingSessionState.STOP_PENDING, recovered.state)
        assertEquals(30_000L, recovered.endAtMillis)
        assertEquals(10_000L, recovered.legacyOriginalCapturedStartAtMillis)
        assertEquals(20_000L, recovered.legacyOriginalCapturedStopAtMillis)
        assertEquals(30_000L, recovered.startedAtMillis)
        assertEquals(action.localId, dao.pushableSessions().single().localId)
        assertEquals(102L, recovered.legacyResolutionReceiptId)
    }

    @Test
    fun recoveredV27HourlyStartReplaysOnlyAnExactChronologicallyValidStop() = runBlocking {
        val action = localRow(
            localId = "88888888-8888-4888-8888-888888888888",
            serverId = null,
            state = GamingSessionState.START_REJECTED,
            stationId = "station-hourly-exact",
        ).copy(
            shiftId = "shift-hourly-exact",
            status = "start_failed",
            startedAtMillis = 10_000,
            endAtMillis = 42_123,
            ratePerHourMinor = 15_000,
            packageId = null,
            lastError = "Response-lost hourly start",
        )
        dao.insertLocalSession(action)
        dao.captureLegacyPackageResolution(
            localId = action.localId,
            resolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
            reason = "Owner reviewed exact hourly recovery",
            referenceOrderId = null,
            actorUserId = "owner-1",
            capturedAtMillis = 50_000,
        )
        val authoritative = GamingSessionCacheEntity(
            id = "server-hourly-exact",
            stationId = action.stationId,
            shiftId = "server-shift-hourly-exact",
            status = "active",
            startAtMillis = 30_000,
            ratePerHourMinor = 15_000,
            billingMode = "hourly",
        )

        assertTrue(
            dao.confirmRecoveredLegacyServerSession(
                localId = action.localId,
                capturedResolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Owner reviewed exact hourly recovery",
                referenceOrderId = null,
                actorUserId = "owner-1",
                receiptId = 103,
                resolvedAtMillis = 60_000,
                authoritative = authoritative,
                disposition = RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP,
            ),
        )
        val recovered = dao.localSessionById(action.localId)!!
        assertEquals(GamingSessionState.STOP_PENDING, recovered.state)
        assertEquals(42_123L, recovered.endAtMillis)
        assertEquals(10_000L, recovered.legacyOriginalCapturedStartAtMillis)
        assertEquals(42_123L, recovered.legacyOriginalCapturedStopAtMillis)
        assertEquals(30_000L, recovered.startedAtMillis)
        assertEquals(listOf(action.localId), dao.pushableSessions().map { it.localId })
    }

    @Test
    fun recoveredHourlyStopBeforeServerStartRemainsBlockedAcrossRefresh() = runBlocking {
        val action = localRow(
            localId = "99999999-9999-4999-8999-999999999999",
            serverId = null,
            state = GamingSessionState.START_REJECTED,
            stationId = "station-hourly-unsafe",
        ).copy(
            shiftId = "shift-hourly-unsafe",
            status = "start_failed",
            startedAtMillis = 10_000,
            endAtMillis = 20_000,
            ratePerHourMinor = 15_000,
            packageId = null,
            lastError = "Response-lost hourly start",
        )
        dao.insertLocalSession(action)
        dao.captureLegacyPackageResolution(
            localId = action.localId,
            resolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
            reason = "Owner reviewed unsafe hourly chronology",
            referenceOrderId = null,
            actorUserId = "owner-1",
            capturedAtMillis = 50_000,
        )
        val authoritative = GamingSessionCacheEntity(
            id = "server-hourly-unsafe",
            stationId = "station-hourly-current",
            shiftId = "server-shift-hourly-unsafe",
            status = "active",
            startAtMillis = 30_000,
            ratePerHourMinor = 15_000,
            billingMode = "hourly",
        )
        val reviewError =
            "Original Stop predates authoritative Start; ordinary billing remains locked."

        assertFalse(
            dao.confirmRecoveredLegacyServerSession(
                localId = action.localId,
                capturedResolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Owner reviewed unsafe hourly chronology",
                referenceOrderId = null,
                actorUserId = "owner-1",
                receiptId = 104,
                resolvedAtMillis = 60_000,
                authoritative = authoritative,
                disposition = RecoveredLegacyServerDisposition.RESOLVE_LOCAL,
            ),
        )
        assertTrue(
            dao.confirmRecoveredLegacyServerSession(
                localId = action.localId,
                capturedResolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Owner reviewed unsafe hourly chronology",
                referenceOrderId = null,
                actorUserId = "owner-1",
                receiptId = 104,
                resolvedAtMillis = 60_000,
                authoritative = authoritative,
                disposition = RecoveredLegacyServerDisposition.RETAIN_BILLING_REVIEW,
                billingReviewError = reviewError,
            ),
        )

        var retained = dao.localSessionById(action.localId)!!
        assertEquals(authoritative.id, retained.serverId)
        assertEquals(authoritative.stationId, retained.stationId)
        assertEquals(authoritative.startAtMillis, retained.startedAtMillis)
        assertEquals(20_000L, retained.endAtMillis)
        assertEquals(10_000L, retained.legacyOriginalCapturedStartAtMillis)
        assertEquals(20_000L, retained.legacyOriginalCapturedStopAtMillis)
        assertEquals(GamingSessionState.START_REJECTED, retained.state)
        assertEquals("start_failed", retained.status)
        assertEquals(GamingLegacyResolutionAttemptState.RESOLVED, retained.legacyResolutionAttemptState)
        assertEquals(104L, retained.legacyResolutionReceiptId)
        assertEquals(reviewError, retained.lastError)
        assertTrue(dao.pushableSessions().none { it.localId == action.localId })

        // A routine authoritative refresh must not turn this receipt-backed
        // billing review into an ordinary Active card with Stop/Send actions.
        dao.upsertAuthoritativeSession(authoritative.copy(timerMinutes = 90))
        retained = dao.localSessionById(action.localId)!!
        assertEquals(GamingSessionState.START_REJECTED, retained.state)
        assertEquals("start_failed", retained.status)
        assertEquals(20_000L, retained.endAtMillis)
        assertEquals(90, retained.timerMinutes)
        assertTrue(db.outboxSafetyDao().unresolvedGroups().any { it.resource == "gaming_sessions" })
    }

    @Test
    fun verifiedPaidOrderAllowsHourlyPreServerStopToTerminaliseWithoutSecondCharge() = runBlocking {
        val orderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val action = localRow(
            localId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            serverId = null,
            state = GamingSessionState.START_REJECTED,
            stationId = "station-hourly-paid",
        ).copy(
            shiftId = "shift-hourly-paid",
            status = "start_failed",
            startedAtMillis = 10_000,
            endAtMillis = 20_000,
            ratePerHourMinor = 15_000,
            packageId = null,
            lastError = "Response-lost hourly start",
        )
        dao.insertLocalSession(action)
        dao.captureLegacyPackageResolution(
            localId = action.localId,
            resolution = GamingLegacyResolution.MANUAL_BILL_RECORDED,
            reason = "Owner verified the paid hourly POS order",
            referenceOrderId = orderId,
            actorUserId = "owner-1",
            capturedAtMillis = 50_000,
        )
        val authoritative = GamingSessionCacheEntity(
            id = "server-hourly-paid",
            stationId = action.stationId,
            shiftId = "server-shift-hourly-paid",
            status = "active",
            startAtMillis = 30_000,
            ratePerHourMinor = 15_000,
            billingMode = "hourly",
            orderId = orderId,
        )

        assertTrue(
            dao.confirmRecoveredLegacyServerSession(
                localId = action.localId,
                capturedResolution = GamingLegacyResolution.MANUAL_BILL_RECORDED,
                reason = "Owner verified the paid hourly POS order",
                referenceOrderId = orderId,
                actorUserId = "owner-1",
                receiptId = 105,
                resolvedAtMillis = 60_000,
                authoritative = authoritative,
                disposition = RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP,
            ),
        )
        val recovered = dao.localSessionById(action.localId)!!
        assertEquals(GamingSessionState.STOP_PENDING, recovered.state)
        assertEquals(30_000L, recovered.endAtMillis)
        assertEquals(20_000L, recovered.legacyOriginalCapturedStopAtMillis)
        assertEquals(orderId, recovered.orderId)
        assertEquals(action.localId, dao.pushableSessions().single().localId)
    }

    @Test
    fun recoveredPackageStartRejectsPartialOrNonPackageFinancialEvidence() = runBlocking {
        suspend fun attempt(index: Int, authoritative: GamingSessionCacheEntity): Boolean {
            val action = localRow(
                localId = "77777777-7777-4777-8777-${index.toString().padStart(12, '0')}",
                serverId = null,
                state = GamingSessionState.START_REJECTED,
                stationId = "station-unsafe-$index",
            ).copy(
                shiftId = "shift-unsafe-$index",
                status = "start_failed",
                endAtMillis = 20_000,
                packageId = "base-unsafe-$index",
                lastError = LEGACY_PACKAGE_START_REVIEW_ERROR,
            )
            dao.insertLocalSession(action)
            dao.captureLegacyPackageResolution(
                localId = action.localId,
                resolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Owner reviewed unsafe recovery evidence",
                referenceOrderId = null,
                actorUserId = "owner-1",
                capturedAtMillis = 50_000,
            )
            return dao.confirmRecoveredLegacyServerSession(
                localId = action.localId,
                capturedResolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                reason = "Owner reviewed unsafe recovery evidence",
                referenceOrderId = null,
                actorUserId = "owner-1",
                receiptId = 300L + index,
                resolvedAtMillis = 60_000,
                authoritative = authoritative,
                disposition = RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP,
            )
        }

        val base = GamingSessionCacheEntity(
            id = "server-unsafe-base",
            stationId = "station-unsafe",
            shiftId = "shift-unsafe",
            status = "active",
            startAtMillis = 10_000,
            amountMinor = 15_000,
            billingMode = "package",
            packagePriceMinorSnapshot = 15_000,
            packageDurationMinutesSnapshot = 60,
            packageVariantSnapshot = "solo",
            packageStationTypeSnapshot = "ps5",
        )
        assertFalse(attempt(1, base.copy(billingMode = "hourly")))
        assertFalse(attempt(2, base.copy(amountMinor = null)))
        assertFalse(attempt(3, base.copy(packageVariantSnapshot = null)))

        val rows = (1..3).mapNotNull { index ->
            dao.localSessionById("77777777-7777-4777-8777-${index.toString().padStart(12, '0')}")
        }
        assertTrue(rows.all { it.state == GamingSessionState.START_REJECTED })
        assertTrue(rows.all { it.legacyResolutionReceiptId == null })
    }

    @Test
    fun recoveredServerStatesAtomicallyReleaseLegacyBlockerAndPopulateSharedCache() = runBlocking {
        val serverStates = listOf(
            Triple("active", null, null),
            Triple("ended", 72_000L, null),
            Triple("ended", 72_000L, "paid-order-1"),
            Triple("cancelled", 72_000L, null),
        )
        serverStates.forEachIndexed { index, (status, endAt, orderId) ->
            val action = localRow(
                localId = "00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}",
                serverId = null,
                state = GamingSessionState.START_REJECTED,
                stationId = "station-terminal-$index",
            ).copy(
                shiftId = "server-shift-$index",
                status = "start_failed",
                startedAtMillis = 10_000L + index,
                endAtMillis = null,
                packageId = "base-$index",
                lastError = LEGACY_PACKAGE_START_REVIEW_ERROR,
            )
            dao.insertLocalSession(action)
            assertEquals(
                1,
                dao.captureLegacyPackageResolution(
                    localId = action.localId,
                    resolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                    reason = "Owner reviewed server outcome $index",
                    referenceOrderId = null,
                    actorUserId = "owner-1",
                    capturedAtMillis = 50_000L + index,
                ),
            )
            val authoritative = GamingSessionCacheEntity(
                id = "server-terminal-$index",
                stationId = action.stationId,
                shiftId = action.shiftId,
                status = status,
                startAtMillis = action.startedAtMillis,
                endAtMillis = endAt,
                billableMinutes = endAt?.let { 60 },
                amountMinor = 15_000,
                ratePerHourMinor = 15_000,
                packageId = action.packageId,
                billingMode = "package",
                packagePriceMinorSnapshot = 15_000,
                packageDurationMinutesSnapshot = 60,
                packageVariantSnapshot = "solo",
                packageStationTypeSnapshot = "ps5",
                orderId = orderId,
            )

            assertTrue(
                dao.confirmRecoveredLegacyServerSession(
                    localId = action.localId,
                    capturedResolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
                    reason = "Owner reviewed server outcome $index",
                    referenceOrderId = null,
                    actorUserId = "owner-1",
                    receiptId = 200L + index,
                    resolvedAtMillis = 60_000L + index,
                    authoritative = authoritative,
                    disposition = RecoveredLegacyServerDisposition.RESOLVE_LOCAL,
                ),
            )
            val resolved = dao.localSessionById(action.localId)!!
            assertEquals(GamingSessionState.LEGACY_RESOLVED, resolved.state)
            assertNull(resolved.serverId)
            assertEquals(200L + index, resolved.legacyResolutionReceiptId)
        }

        assertEquals(
            setOf("server-terminal-0", "server-terminal-1", "server-terminal-2", "server-terminal-3"),
            dao.observeSessionCache().first().map { it.id }.toSet(),
        )
        assertTrue(db.outboxSafetyDao().unresolvedGroups().none { it.resource == "gaming_sessions" })
    }

    @Test
    fun paidPackageExtensionOutboxKeepsImmutableSnapshotAcrossRecoveryStates() = runBlocking {
        val first = packageExtension(
            actionId = "11111111-1111-4111-8111-111111111111",
            serverSessionId = "session-1",
        )
        val rapidSecond = packageExtension(
            actionId = "22222222-2222-4222-8222-222222222222",
            serverSessionId = "session-1",
        )

        assertTrue(dao.capturePackageExtension(first))
        assertFalse(dao.capturePackageExtension(rapidSecond))
        assertEquals(listOf(first.actionId), dao.packageExtensionsForSync().map { it.actionId })
        assertEquals("gaming_package_extensions", db.outboxSafetyDao().unresolvedGroups().single().resource)
        assertEquals(
            0,
            dao.discardRejectedPackageExtension(first.actionId, "not rejected", resolvedAtMillis = 3_500),
        )

        assertEquals(1, dao.markPackageExtensionAmbiguous(first.actionId, "response unknown"))
        assertEquals(GamingPackageExtensionState.AMBIGUOUS, dao.packageExtensionAction(first.actionId)?.state)
        assertEquals(first.actionId, dao.packageExtensionsForSync().single().actionId)
        assertEquals(
            0,
            dao.discardRejectedPackageExtension(first.actionId, "still ambiguous", resolvedAtMillis = 3_600),
        )

        assertEquals(1, dao.markPackageExtensionConfirmed(first.actionId))
        assertTrue(dao.packageExtensionsForSync().isEmpty())
        assertTrue(dao.capturePackageExtension(rapidSecond))
        assertEquals(1, dao.markPackageExtensionRejected(rapidSecond.actionId, "snapshot changed"))
        assertEquals(
            1,
            dao.markPackageExtensionAmbiguous(
                rapidSecond.actionId,
                "exact verification response lost",
            ),
        )

        val retried = dao.packageExtensionAction(rapidSecond.actionId)!!
        assertEquals(GamingPackageExtensionState.AMBIGUOUS, retried.state)
        assertEquals(7_500L, retried.expectedPackagePriceMinor)
        assertEquals(30, retried.expectedPackageDurationMinutes)
        assertEquals("solo", retried.expectedPackageVariant)
        assertEquals(60, retried.expectedSessionTimerMinutes)
        assertEquals(15_000L, retried.expectedSessionAmountMinor)

        assertEquals(1, dao.markPackageExtensionRejected(rapidSecond.actionId, "snapshot changed again"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.discardRejectedPackageExtension(rapidSecond.actionId, "x", resolvedAtMillis = 4_000)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.discardRejectedPackageExtension(
                    rapidSecond.actionId,
                    "x".repeat(501),
                    resolvedAtMillis = 4_000,
                )
            }
        }
        assertEquals(
            1,
            dao.discardRejectedPackageExtension(
                rapidSecond.actionId,
                "  Staff verified the refreshed session  ",
                resolvedAtMillis = 4_000,
            ),
        )
        val discarded = dao.packageExtensionAction(rapidSecond.actionId)!!
        assertEquals(GamingPackageExtensionState.DISCARDED, discarded.state)
        assertEquals(4_000L, discarded.resolvedAtMillis)
        assertEquals("Staff verified the refreshed session", discarded.resolutionReason)
        assertTrue(dao.observeUnresolvedPackageExtensions().first().isEmpty())
        assertTrue(db.outboxSafetyDao().unresolvedGroups().isEmpty())
        assertEquals(
            0,
            dao.discardRejectedPackageExtension(
                rapidSecond.actionId,
                "Already resolved",
                resolvedAtMillis = 4_001,
            ),
        )

        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            runBlocking {
                dao.insertPackageExtensionAction(
                    rapidSecond.copy(expectedPackagePriceMinor = 999_999),
                )
            }
        }
        Unit
    }

    @Test
    fun orphanedRejectedExtensionRemainsRecoverableByActionIdentity() = runBlocking {
        val action = packageExtension(
            actionId = "77777777-7777-4777-8777-777777777777",
            serverSessionId = "session-already-paid-elsewhere",
        )

        assertTrue(dao.capturePackageExtension(action))
        assertEquals(
            1,
            dao.markPackageExtensionRejected(
                action.actionId,
                "ledger checked; no extension applied",
            ),
        )
        // Cross-device Stop/Send leaves no local or unbilled session card.
        // Recovery therefore owns the durable action identity directly.
        assertTrue(dao.observeActiveLocalSessions().first().isEmpty())
        assertEquals(
            listOf(action.actionId),
            dao.observeUnresolvedPackageExtensions().first().map { it.actionId },
        )
        assertEquals(
            "gaming_package_extensions",
            db.outboxSafetyDao().unresolvedGroups().single().resource,
        )

        assertEquals(
            1,
            dao.discardRejectedPackageExtension(
                action.actionId,
                "Exact replay and scoped session lookup proved no charge",
                resolvedAtMillis = 9_000,
            ),
        )
        val retained = dao.packageExtensionAction(action.actionId)!!
        assertEquals(GamingPackageExtensionState.DISCARDED, retained.state)
        assertEquals(9_000L, retained.resolvedAtMillis)
        assertTrue(db.outboxSafetyDao().unresolvedGroups().isEmpty())
    }

    @Test
    fun disabledStationsRemainVisibleWhenTheyCanCarryOperationalHistory() = runBlocking {
        dao.replaceStations(
            listOf(
                GamingStationEntity("enabled", "A", "Enabled", "ps5", 15_000, true),
                GamingStationEntity("disabled", "B", "Disabled", "ps5", 15_000, false),
            ),
        )

        assertEquals(listOf("Disabled", "Enabled"), dao.observeStations().first().map { it.name })
    }

    @Test
    fun confirmedPosHandoffUpdatesVisibleCacheBeforeAnyRefresh() = runBlocking {
        dao.replaceSessionCache(listOf(serverRow("session-send", "ended", 15_000)))

        dao.markCachedSessionSent("session-send", "order-1", 15_750)

        val cached = dao.observeSessionCache().first().single()
        assertEquals("order-1", cached.orderId)
        assertEquals(15_750L, cached.amountMinor)
    }

    @Test
    fun packageCacheReplacesStaleRowsForOfflineStartSelection() = runBlocking {
        dao.replacePackages(
            listOf(
                GamingPackageCacheEntity(
                    id = "base-60",
                    stationType = "ps5",
                    variant = "solo",
                    kind = "base",
                    name = "Solo 60 min",
                    durationMinutes = 60,
                    priceMinor = 15_000,
                ),
            ),
        )
        dao.replacePackages(
            listOf(
                GamingPackageCacheEntity(
                    id = "extend-30",
                    stationType = "ps5",
                    variant = "solo",
                    kind = "extension",
                    name = "Add 30 min",
                    durationMinutes = 30,
                    priceMinor = 7_500,
                ),
            ),
        )

        val packages = dao.observePackages().first()
        assertEquals(listOf("extend-30"), packages.map { it.id })
    }

    private fun legacyRow(
        localId: String,
        serverId: String?,
        status: String,
        endAtMillis: Long? = null,
        billableMinutes: Int? = null,
        amountMinor: Long? = null,
        orderId: String? = null,
    ) = LocalGamingSessionEntity(
        localId = localId,
        serverId = serverId,
        stationId = "station-$localId",
        shiftId = if (serverId == null) "shift-1" else null,
        startedAtMillis = 1_000,
        state = "rejected",
        status = status,
        endAtMillis = endAtMillis,
        billableMinutes = billableMinutes,
        amountMinor = amountMinor,
        orderId = orderId,
        lastError = "legacy failure",
    )

    private fun localRow(
        localId: String,
        serverId: String?,
        state: String,
        stationId: String = "station-$localId",
    ) = LocalGamingSessionEntity(
        localId = localId,
        serverId = serverId,
        stationId = stationId,
        startedAtMillis = 1_000,
        state = state,
        status = when (state) {
            GamingSessionState.START_PENDING -> "starting"
            GamingSessionState.STOP_PENDING -> "stopping"
            else -> "ended"
        },
        endAtMillis = 2_000L.takeIf { state == GamingSessionState.SEND_REJECTED },
        amountMinor = 10_000L.takeIf { state == GamingSessionState.SEND_REJECTED },
        lastError = "stale local state",
    )

    private fun serverRow(
        id: String,
        status: String,
        amountMinor: Long,
        orderId: String? = null,
    ) = GamingSessionCacheEntity(
        id = id,
        stationId = "station-${id.removePrefix("session-")}",
        status = status,
        startAtMillis = 1_000,
        endAtMillis = 2_000,
        billableMinutes = if (status == "cancelled") 0 else 15,
        amountMinor = amountMinor,
        orderId = orderId,
    )

    private fun packageExtension(
        actionId: String,
        serverSessionId: String,
    ) = LocalGamingPackageExtensionEntity(
        actionId = actionId,
        serverSessionId = serverSessionId,
        localSessionId = "local-$serverSessionId",
        shiftId = "shift-1",
        packageId = "extend-30",
        expectedPackagePriceMinor = 7_500,
        expectedPackageDurationMinutes = 30,
        expectedPackageVariant = "solo",
        expectedSessionTimerMinutes = 60,
        expectedSessionAmountMinor = 15_000,
        createdAtMillis = 3_000,
    )
}
