package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import cloud.dcompany.erp.ui.screens.gaming.projectPendingGamingSessionActions
import cloud.dcompany.erp.ui.screens.gaming.toGameSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamingSessionActionDaoTest {
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
    fun tearDown() = db.close()

    @Test
    fun perSessionSequenceIsMonotonicAndIndependentAcrossSessions() = runBlocking {
        val join = dao.captureSessionAction(action("join", "session-a", GamingSessionActionType.PARTICIPANT_JOIN))
        val leave = dao.captureSessionAction(action("leave", "session-a", GamingSessionActionType.PARTICIPANT_LEAVE))
        val other = dao.captureSessionAction(action("amend", "session-b", GamingSessionActionType.AMEND))

        assertEquals(1L, join.sequence)
        assertEquals(2L, leave.sequence)
        assertEquals(1L, other.sequence)
        assertEquals(listOf("join", "leave", "amend"), dao.sessionActionsForSync().map { it.actionId })
    }

    @Test
    fun cacheOnlyJoinThenImmediateStopUsesLedgerRevisionBeforeAnyBoardEmission() = runBlocking {
        val serverId = "server-session-1"
        dao.upsertSessionCache(listOf(GamingSessionCacheEntity(
            id = serverId, stationId = "station-1", shiftId = "shift-1",
            status = "active", startAtMillis = 1_000,
            participantRevision = 0, billingRevision = 0,
        )))
        val staleUi = dao.sessionCacheById(serverId)!!.toGameSession()
        dao.captureSessionAction(action("join-fast", serverId, GamingSessionActionType.PARTICIPANT_JOIN).copy(
            occurredAtMillis = 2_000, playElapsedMs = 1_000,
            customerName = "Amina", customerPhone = "9876543210",
        ))

        db.withTransaction {
            val latestCache = dao.sessionCacheById(serverId)!!.toGameSession()
            val projected = projectPendingGamingSessionActions(latestCache, dao.sessionActionsForKey(serverId))
            dao.insertLocalSession(LocalGamingSessionEntity(
                localId = "local-stop-1", serverId = serverId,
                stationId = "station-1", shiftId = "shift-1", startedAtMillis = 1_000,
                state = GamingSessionState.STOP_PENDING, status = "stopping", endAtMillis = 3_000,
            ))
            dao.captureSessionAction(action("stop-fast", serverId, GamingSessionActionType.STOP).copy(
                localSessionId = "local-stop-1", occurredAtMillis = 3_000,
                playElapsedMs = null,
                expectedParticipantRevision = projected.participantRevision,
                expectedBillingRevision = projected.billingRevision,
            ))
        }

        assertEquals(0, staleUi.participantRevision)
        assertEquals(listOf("join-fast", "stop-fast"), dao.sessionActionsForSync().map { it.actionId })
        assertEquals(listOf(1L, 2L), dao.sessionActionsForKey(serverId).map { it.sequence })
        assertEquals(1, dao.sessionAction("stop-fast")?.expectedParticipantRevision)
        assertEquals(0, dao.sessionAction("stop-fast")?.expectedBillingRevision)
    }

    @Test
    fun actionEvidenceCannotBeReplacedAndJoinResultResolvesLaterLeave() = runBlocking {
        dao.captureSessionAction(action("join", "session-a", GamingSessionActionType.PARTICIPANT_JOIN))
        dao.captureSessionAction(
            action("leave", "session-a", GamingSessionActionType.PARTICIPANT_LEAVE)
                .copy(participantReference = "join"),
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                dao.insertSessionAction(action("join", "session-a", GamingSessionActionType.AMEND).copy(sequence = 9))
            }
        }
        assertEquals(1, dao.markSessionActionConfirmed("join", 2_000, "participant-server-1"))
        assertEquals("participant-server-1", dao.sessionAction("join")?.resultParticipantId)
        assertEquals("join", dao.sessionAction("leave")?.participantReference)
        assertEquals(listOf("join", "leave"), dao.observeSessionActionsForBoard().first().map { it.actionId })
        assertEquals(listOf("leave"), dao.observeUnresolvedSessionActions().first().map { it.actionId })
        assertEquals(1, dao.markSessionActionConfirmed("leave", 2_001))
        assertEquals(emptyList<LocalGamingSessionActionEntity>(), dao.observeSessionActionsForBoard().first())
    }

    @Test
    fun definitivelyRejectedActionCanBeRetainedAsDiscardedEvidence() = runBlocking {
        dao.captureSessionAction(action("join", "session-a", GamingSessionActionType.PARTICIPANT_JOIN))
        assertEquals(1, dao.markSessionActionRejected("join", "Customer was deleted"))
        assertEquals(1, dao.discardRejectedSessionAction("join", 2_000))

        assertEquals(GamingSessionActionState.DISCARDED, dao.sessionAction("join")?.state)
        assertEquals(emptyList<LocalGamingSessionActionEntity>(), dao.sessionActionsForSync())
    }

    @Test
    fun amendmentJoinLeaveExtensionAndStopRemainInOneFifoAcrossRestart() = runBlocking {
        db.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "gaming-action-restart-${System.nanoTime()}"
        context.deleteDatabase(name)
        db = Room.databaseBuilder(context, ErpDatabase::class.java, name).build()
        dao = db.gamingDao()

        listOf(
            "amend" to GamingSessionActionType.AMEND,
            "join" to GamingSessionActionType.PARTICIPANT_JOIN,
            "leave" to GamingSessionActionType.PARTICIPANT_LEAVE,
            "extend" to GamingSessionActionType.EXTEND,
            "stop" to GamingSessionActionType.STOP,
        ).forEach { (id, type) -> dao.captureSessionAction(action(id, "session-a", type)) }
        db.close()

        db = Room.databaseBuilder(context, ErpDatabase::class.java, name).build()
        dao = db.gamingDao()
        assertEquals(
            listOf("amend", "join", "leave", "extend", "stop"),
            dao.sessionActionsForSync().map { it.actionId },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), dao.sessionActionsForSync().map { it.sequence })

        assertEquals(1, dao.markSessionActionConfirmed("amend", 2_000))
        assertEquals(0, dao.markSessionActionConfirmed("amend", 2_001))
        assertNotNull(dao.sessionAction("amend"))
        assertEquals(
            listOf("join", "leave", "extend", "stop"),
            dao.sessionActionsForSync().map { it.actionId },
        )
        db.close()
        context.deleteDatabase(name)
        db = Room.inMemoryDatabaseBuilder(context, ErpDatabase::class.java).build()
        dao = db.gamingDao()
    }

    @Test
    fun offlineStartAmendFriendJoinLeaveAndStopKeepExactEvidenceUntilServerIdResolves() = runBlocking {
        db.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "gaming-offline-start-chain-${System.nanoTime()}"
        context.deleteDatabase(name)
        db = Room.databaseBuilder(context, ErpDatabase::class.java, name).build()
        dao = db.gamingDao()
        val localId = "local-session-1"
        val startedAt = 1_000_000L
        val start = LocalGamingSessionEntity(
            localId = localId,
            stationId = "station-1",
            shiftId = "shift-1",
            startedAtMillis = startedAt,
            state = GamingSessionState.START_PENDING,
            status = "starting",
            timerMinutes = 60,
            timerEndsAtMillis = startedAt + 3_600_000,
            amountMinor = 12_000,
            ratePerHourMinor = 12_000,
            packageId = "single-60",
            packagePriceMinor = 12_000,
            packageDurationMinutes = 60,
            packageVariant = "single",
            packageStationTypeSnapshot = "ps5",
            packagePricingTierSnapshot = "standard",
            billingMode = "package",
        )
        assertEquals(true, dao.insertStartIfStationAvailable(start))
        val actions = listOf(
            action("amend", localId, GamingSessionActionType.AMEND).copy(
                serverSessionId = null, localSessionId = localId,
                occurredAtMillis = startedAt + 60_000, playElapsedMs = 60_000,
                packageId = "single-30", expectedPackagePriceMinor = 8_000,
                expectedPackageDurationMinutes = 30, expectedPackageVariant = "single",
                expectedSessionTimerMinutes = 60, expectedSessionAmountMinor = 12_000,
            ),
            action("join", localId, GamingSessionActionType.PARTICIPANT_JOIN).copy(
                serverSessionId = null, localSessionId = localId,
                occurredAtMillis = startedAt + 120_000, playElapsedMs = 120_000,
                customerName = "Amina", customerPhone = "9876543210",
            ),
            action("leave", localId, GamingSessionActionType.PARTICIPANT_LEAVE).copy(
                serverSessionId = null, localSessionId = localId,
                occurredAtMillis = startedAt + 180_000, playElapsedMs = 180_000,
                expectedParticipantRevision = 1, participantReference = "join",
            ),
        )
        actions.forEach { dao.captureSessionAction(it) }
        assertEquals(1, dao.requestSessionStop(localId, startedAt + 240_000, "shift-1"))
        dao.captureSessionAction(action("stop", localId, GamingSessionActionType.STOP).copy(
            serverSessionId = null, localSessionId = localId,
            occurredAtMillis = startedAt + 240_000,
            playElapsedMs = null,
            expectedParticipantRevision = 2,
            expectedBillingRevision = 1,
        ))
        db.close()

        db = Room.databaseBuilder(context, ErpDatabase::class.java, name).build()
        dao = db.gamingDao()
        assertEquals(GamingSessionState.STOP_PENDING, dao.localSessionById(localId)?.state)
        assertEquals(null, dao.localSessionById(localId)?.serverId)
        assertEquals(listOf("amend", "join", "leave", "stop"),
            dao.sessionActionsForKey(localId).map { it.actionId })
        assertEquals(listOf(1L, 2L, 3L, 4L),
            dao.sessionActionsForKey(localId).map { it.sequence })
        assertEquals(listOf(60_000L, 120_000L, 180_000L, null),
            dao.sessionActionsForKey(localId).map { it.playElapsedMs })

        // Simulate the Start acknowledgement after reconnect. Replay still uses
        // the original action timestamps and the same one-session FIFO.
        dao.setSessionStarted(
            localId = localId, serverId = "server-session-1", status = "active",
            shiftId = "shift-1", startedAtMillis = startedAt,
            timerMinutes = 60, timerEndsAtMillis = startedAt + 3_600_000,
            amountMinor = 12_000, ratePerHourMinor = 12_000,
            packageId = "single-60", billingMode = "package",
            packagePriceMinor = 12_000, packageDurationMinutes = 60,
            packageVariant = "single", packageStationTypeSnapshot = "ps5",
            packagePricingTierSnapshot = "standard", extraControllers = 0,
        )
        assertEquals(4, dao.resolveSessionActionServerId(localId, "server-session-1"))
        assertEquals(GamingSessionState.STOP_PENDING, dao.localSessionById(localId)?.state)
        assertEquals(listOf("server-session-1", "server-session-1", "server-session-1", "server-session-1"),
            dao.sessionActionsForSync().map { it.serverSessionId })
        assertEquals(1, dao.markSessionActionConfirmed("amend", startedAt + 300_000))
        assertEquals(1, dao.markSessionActionConfirmed("join", startedAt + 300_001, "participant-1"))
        assertEquals("participant-1", dao.sessionAction("join")?.resultParticipantId)
        assertEquals("join", dao.sessionAction("leave")?.participantReference)
        assertEquals(listOf("leave", "stop"), dao.sessionActionsForSync().map { it.actionId })

        db.close()
        context.deleteDatabase(name)
        db = Room.inMemoryDatabaseBuilder(context, ErpDatabase::class.java).build()
        dao = db.gamingDao()
    }

    @Test
    fun rejectedOfflineStartKeepsDependentActionsWithoutInventingAServerSession() = runBlocking {
        val localId = "rejected-start"
        dao.insertLocalSession(LocalGamingSessionEntity(
            localId = localId, stationId = "station-1", shiftId = "shift-1",
            startedAtMillis = 1_000, state = GamingSessionState.START_PENDING,
            status = "starting",
        ))
        dao.captureSessionAction(action("amend", localId, GamingSessionActionType.AMEND).copy(
            serverSessionId = null, localSessionId = localId,
        ))

        dao.markSessionRejected(localId, GamingSessionState.START_REJECTED, "Start needs review")

        assertEquals(GamingSessionState.START_REJECTED, dao.localSessionById(localId)?.state)
        assertEquals(null, dao.localSessionById(localId)?.serverId)
        assertEquals(null, dao.sessionAction("amend")?.serverSessionId)
        assertEquals(GamingSessionActionState.PENDING, dao.sessionAction("amend")?.state)
        assertEquals(listOf("amend"), dao.sessionActionsForSync().map { it.actionId })
    }

    @Test
    fun auditedRejectedStartResolutionAtomicallyRetiresDependentCommandsAndClearsShiftGate() = runBlocking {
        for ((suffix, resolution, referenceOrderId) in listOf(
            Triple("no-play", GamingLegacyResolution.CONFIRMED_NO_PLAY, null),
        )) {
            val localId = "rejected-$suffix"
            val reason = "Owner verified $suffix outcome"
            dao.insertLocalSession(LocalGamingSessionEntity(
                localId = localId,
                stationId = "station-$suffix",
                shiftId = "shift-1",
                startedAtMillis = 1_000,
                state = GamingSessionState.START_REJECTED,
                status = "start_failed",
            ))
            val ids = listOf(
                "amend" to GamingSessionActionType.AMEND,
                "join" to GamingSessionActionType.PARTICIPANT_JOIN,
                "leave" to GamingSessionActionType.PARTICIPANT_LEAVE,
                "stop" to GamingSessionActionType.STOP,
            ).map { (label, type) ->
                "$suffix-$label".also { id ->
                    dao.captureSessionAction(action(id, localId, type).copy(
                        localSessionId = localId,
                        serverSessionId = null,
                        participantReference = "${suffix}-join".takeIf {
                            type == GamingSessionActionType.PARTICIPANT_LEAVE
                        },
                    ))
                }
            }
            assertEquals(1, dao.captureLegacyPackageResolution(
                localId = localId,
                resolution = resolution,
                reason = reason,
                referenceOrderId = referenceOrderId,
                actorUserId = "owner-1",
                capturedAtMillis = 2_000,
            ))
            val before = db.shiftCloseSafetyDao().blockersForExactShift("shift-1", null, "terminal-1")
            assertEquals(3, before.pendingLocalCount)
            assertEquals(1, before.attentionLocalCount)
            assertEquals(0, dao.confirmNoServerLegacyResolutionAndReconcileDependents(
                localId, resolution, reason, referenceOrderId, "wrong-owner", 91, 3_000,
            ))
            assertEquals(ids, dao.sessionActionsForSync().map { it.actionId })

            assertEquals(1, dao.confirmNoServerLegacyResolutionAndReconcileDependents(
                localId, resolution, reason, referenceOrderId, "owner-1", 91, 3_000,
            ))

            assertEquals(GamingSessionState.LEGACY_RESOLVED, dao.localSessionById(localId)?.state)
            assertEquals(91L, dao.localSessionById(localId)?.legacyResolutionReceiptId)
            assertEquals(emptyList<LocalGamingSessionActionEntity>(), dao.sessionActionsForSync())
            for (id in ids) {
                val retained = dao.sessionAction(id)!!
                assertEquals(GamingSessionActionState.DISCARDED, retained.state)
                assertEquals(3_000L, retained.resolvedAtMillis)
                assertEquals(true, retained.lastError?.contains("audit receipt #91"))
                assertEquals(null, retained.serverSessionId)
            }
            val after = db.shiftCloseSafetyDao().blockersForExactShift("shift-1", null, "terminal-1")
            assertEquals(0, after.pendingLocalCount)
            assertEquals(0, after.attentionLocalCount)
            assertEquals(null, after.serverPostMessage())
        }
    }

    @Test
    fun manualBaseBillWithSavedFriendDoesNotSilentlyRetireUnbilledAttendance() = runBlocking {
        val localId = "rejected-manual-base-bill"
        val reason = "Owner verified base POS order"
        val orderId = "33333333-3333-4333-8333-333333333333"
        dao.insertLocalSession(LocalGamingSessionEntity(
            localId = localId, stationId = "station-1", shiftId = "shift-1",
            startedAtMillis = 1_000, state = GamingSessionState.START_REJECTED,
            status = "start_failed",
        ))
        dao.captureSessionAction(action("manual-join", localId, GamingSessionActionType.PARTICIPANT_JOIN).copy(
            localSessionId = localId, serverSessionId = null,
            customerName = "Amina", customerPhone = "9876543210",
        ))
        assertEquals(1, dao.captureLegacyPackageResolution(
            localId, GamingLegacyResolution.MANUAL_BILL_RECORDED, reason, orderId, "owner-1", 2_000,
        ))

        assertEquals(1, dao.confirmNoServerLegacyResolutionAndReconcileDependents(
            localId, GamingLegacyResolution.MANUAL_BILL_RECORDED, reason, orderId,
            "owner-1", 94, 3_000,
        ))

        val retained = dao.localSessionById(localId)!!
        assertEquals(GamingSessionState.START_REJECTED, retained.state)
        assertEquals(94L, retained.legacyResolutionReceiptId)
        assertEquals(true, retained.lastError?.contains("base session only"))
        assertEquals(GamingSessionActionState.PENDING, dao.sessionAction("manual-join")?.state)
        assertEquals(listOf("manual-join"), dao.sessionActionsForSync().map { it.actionId })
        assertEquals(1, db.shiftCloseSafetyDao().blockersForExactShift(
            "shift-1", null, "terminal-1",
        ).attentionLocalCount)
    }

    @Test
    fun recoveredActiveStartRetainsOfflineActionsAndAuditBlockerInsteadOfDiscardingCharges() = runBlocking {
        val localId = "recovered-active-start"
        val reason = "Owner verified server Start receipt"
        dao.insertLocalSession(LocalGamingSessionEntity(
            localId = localId,
            stationId = "station-1",
            shiftId = "shift-1",
            startedAtMillis = 1_000,
            state = GamingSessionState.START_REJECTED,
            status = "start_failed",
            packageId = "single-60",
            packagePriceMinor = 12_000,
            packageDurationMinutes = 60,
            packageVariant = "single",
            packageStationTypeSnapshot = "ps5",
            billingMode = "package",
        ))
        val amend = action("amend", localId, GamingSessionActionType.AMEND).copy(
            serverSessionId = null, localSessionId = localId,
            occurredAtMillis = 2_000, playElapsedMs = 1_000,
        )
        val join = action("join", localId, GamingSessionActionType.PARTICIPANT_JOIN).copy(
            serverSessionId = null, localSessionId = localId,
            occurredAtMillis = 3_000, playElapsedMs = 2_000,
        )
        dao.captureSessionAction(amend)
        dao.captureSessionAction(join)
        assertEquals(1, dao.captureLegacyPackageResolution(
            localId = localId,
            resolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
            reason = reason,
            referenceOrderId = null,
            actorUserId = "owner-1",
            capturedAtMillis = 4_000,
        ))
        val authoritative = GamingSessionCacheEntity(
            id = "server-session-1",
            stationId = "station-1",
            shiftId = "shift-1",
            status = "active",
            startAtMillis = 5_000, // Later than both offline actions: replay would be unsafe.
            timerMinutes = 60,
            amountMinor = 12_000,
            packageId = "single-60",
            billingMode = "package",
            packagePriceMinorSnapshot = 12_000,
            packageDurationMinutesSnapshot = 60,
            packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5",
        )

        assertEquals(true, dao.confirmRecoveredLegacyServerSession(
            localId = localId,
            capturedResolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
            reason = reason,
            referenceOrderId = null,
            actorUserId = "owner-1",
            receiptId = 92,
            resolvedAtMillis = 6_000,
            authoritative = authoritative,
            disposition = RecoveredLegacyServerDisposition.RESOLVE_LOCAL,
        ))

        val retained = dao.localSessionById(localId)!!
        assertEquals(GamingSessionState.START_REJECTED, retained.state)
        assertEquals(authoritative.id, retained.serverId)
        assertEquals(92L, retained.legacyResolutionReceiptId)
        assertEquals(true, retained.lastError?.contains("saved offline session actions"))
        assertEquals(listOf("amend", "join"), dao.sessionActionsForSync().map { it.actionId })
        assertEquals(listOf(2_000L, 3_000L), dao.sessionActionsForKey(localId).map { it.occurredAtMillis })
        assertEquals(listOf(null, null), dao.sessionActionsForKey(localId).map { it.serverSessionId })
        assertEquals(1, db.shiftCloseSafetyDao().blockersForExactShift(
            "shift-1", null, "terminal-1",
        ).attentionLocalCount)
    }

    @Test
    fun recoveredLaterServerStartKeepsCapturedJoinAndStopBlockedForAudit() = runBlocking {
        val localId = "recovered-joined-then-stopped"
        val reason = "Owner verified server Start receipt"
        dao.insertLocalSession(LocalGamingSessionEntity(
            localId = localId,
            stationId = "station-1",
            shiftId = "shift-1",
            startedAtMillis = 1_000,
            state = GamingSessionState.START_REJECTED,
            status = "start_failed",
            endAtMillis = 4_000,
            packageId = "single-60",
            packagePriceMinor = 12_000,
            packageDurationMinutes = 60,
            packageVariant = "single",
            packageStationTypeSnapshot = "ps5",
            billingMode = "package",
        ))
        dao.captureSessionAction(action("join", localId, GamingSessionActionType.PARTICIPANT_JOIN).copy(
            localSessionId = localId, serverSessionId = null,
            occurredAtMillis = 2_000, playElapsedMs = 1_000,
            customerName = "Amina", customerPhone = "9876543210",
        ))
        dao.captureSessionAction(action("stop", localId, GamingSessionActionType.STOP).copy(
            localSessionId = localId, serverSessionId = null,
            occurredAtMillis = 4_000, playElapsedMs = null,
            expectedParticipantRevision = 1,
        ))
        assertEquals(1, dao.captureLegacyPackageResolution(
            localId = localId,
            resolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
            reason = reason,
            referenceOrderId = null,
            actorUserId = "owner-1",
            capturedAtMillis = 4_500,
        ))
        val authoritative = GamingSessionCacheEntity(
            id = "server-session-2",
            stationId = "station-1",
            shiftId = "shift-1",
            status = "active",
            startAtMillis = 5_000, // Join and Stop predate the server's Start.
            timerMinutes = 60,
            amountMinor = 12_000,
            packageId = "single-60",
            billingMode = "package",
            packagePriceMinorSnapshot = 12_000,
            packageDurationMinutesSnapshot = 60,
            packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5",
        )

        assertEquals(true, dao.confirmRecoveredLegacyServerSession(
            localId = localId,
            capturedResolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
            reason = reason,
            referenceOrderId = null,
            actorUserId = "owner-1",
            receiptId = 93,
            resolvedAtMillis = 6_000,
            authoritative = authoritative,
            disposition = RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP,
        ))

        val retained = dao.localSessionById(localId)!!
        assertEquals(GamingSessionState.START_REJECTED, retained.state)
        assertEquals(93L, retained.legacyResolutionReceiptId)
        assertEquals(authoritative.id, retained.serverId)
        assertEquals(true, retained.lastError?.contains("saved offline session actions"))
        assertEquals(listOf("join", "stop"), dao.sessionActionsForSync().map { it.actionId })
        assertEquals(listOf(null, null), dao.sessionActionsForKey(localId).map { it.serverSessionId })
        assertEquals(1, db.shiftCloseSafetyDao().blockersForExactShift(
            "shift-1", null, "terminal-1",
        ).attentionLocalCount)
    }

    @Test
    fun recoveredExactStartResolvesCleanJoinThenStopForOrderedReplay() = runBlocking {
        val localId = "recovered-exact-chain"
        val reason = "Owner verified exact server Start receipt"
        dao.insertLocalSession(LocalGamingSessionEntity(
            localId = localId,
            stationId = "station-1",
            shiftId = "shift-1",
            startedAtMillis = 1_000,
            state = GamingSessionState.START_REJECTED,
            status = "start_failed",
            endAtMillis = 4_000,
            packageId = "single-60",
            packagePriceMinor = 12_000,
            packageDurationMinutes = 60,
            packageVariant = "single",
            packageStationTypeSnapshot = "ps5",
            billingMode = "package",
        ))
        dao.captureSessionAction(action("exact-join", localId, GamingSessionActionType.PARTICIPANT_JOIN).copy(
            localSessionId = localId, serverSessionId = null,
            occurredAtMillis = 2_000, playElapsedMs = 1_000,
            customerName = "Amina", customerPhone = "9876543210",
        ))
        dao.captureSessionAction(action("exact-stop", localId, GamingSessionActionType.STOP).copy(
            localSessionId = localId, serverSessionId = null,
            occurredAtMillis = 4_000, playElapsedMs = null,
            expectedParticipantRevision = 1,
        ))
        assertEquals(1, dao.captureLegacyPackageResolution(
            localId, GamingLegacyResolution.SERVER_SESSION_RECOVERED,
            reason, null, "owner-1", 4_500,
        ))
        val authoritative = GamingSessionCacheEntity(
            id = "server-exact-1",
            stationId = "station-1",
            shiftId = "shift-1",
            status = "active",
            startAtMillis = 1_000,
            timerMinutes = 60,
            amountMinor = 12_000,
            pauseVersion = 0,
            participantRevision = 0,
            billingRevision = 0,
            packageId = "single-60",
            billingMode = "package",
            packagePriceMinorSnapshot = 12_000,
            packageDurationMinutesSnapshot = 60,
            packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5",
        )

        assertEquals(true, dao.confirmRecoveredLegacyServerSession(
            localId = localId,
            capturedResolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
            reason = reason,
            referenceOrderId = null,
            actorUserId = "owner-1",
            receiptId = 95,
            resolvedAtMillis = 6_000,
            authoritative = authoritative,
            disposition = RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP,
        ))

        assertEquals(GamingSessionState.STOP_PENDING, dao.localSessionById(localId)?.state)
        assertEquals(listOf("exact-join", "exact-stop"), dao.sessionActionsForSync().map { it.actionId })
        assertEquals(listOf(authoritative.id, authoritative.id),
            dao.sessionActionsForSync().map { it.serverSessionId })
    }

    private fun action(id: String, session: String, type: String) = LocalGamingSessionActionEntity(
        actionId = id,
        sessionKey = session,
        actionType = type,
        ownerCompanyId = "company-1",
        ownerUserId = "user-1",
        branchId = "branch-1",
        terminalId = "terminal-1",
        serverSessionId = session,
        shiftId = "shift-1",
        occurredAtMillis = 1_000,
        playElapsedMs = 0,
        expectedPauseVersion = 0,
        expectedParticipantRevision = 0,
        expectedBillingRevision = 0,
    )
}
