package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.GamingSessionActionState
import cloud.dcompany.erp.core.db.GamingSessionActionType
import cloud.dcompany.erp.core.db.GamingSessionParticipantCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.LocalGamingSessionActionEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionEntity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingParticipantAmendmentPolicyTest {
    private val startedAt = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli()

    @Test
    fun `anonymous labels follow retained first joins and survive leave rejoin and row order`() {
        val first = GamingParticipantUi(reference = "interval-1", participantIdentityId = "identity-a",
            customerId = null, name = null, phone = null, active = false, pending = false,
            joinedAtMillis = startedAt, leftAtMillis = startedAt + 1_000L)
        val second = first.copy(reference = "interval-2", participantIdentityId = "identity-b",
            active = true, joinedAtMillis = startedAt + 2_000L, leftAtMillis = null)
        val rejoin = first.copy(reference = "interval-3", active = true,
            joinedAtMillis = startedAt + 3_000L, leftAtMillis = null)
        val rows = listOf(second, rejoin, first)
        assertEquals("Added player 1", gamingParticipantDisplayName(rejoin, rows))
        assertEquals("Added player 2", gamingParticipantDisplayName(second, rows.reversed()))
        assertEquals("Retained name", gamingParticipantDisplayName(second.copy(name = "Retained name"), rows))
        assertEquals("Saved customer · name not retained",
            gamingParticipantDisplayName(second.copy(customerId = "customer-1"), rows))
    }

    @Test
    fun `stopped clock describes played and booked time without inventing remaining time`() {
        val active = GameSession(id = "timing", stationId = "station", shiftId = "shift",
            status = "active", startAt = "2026-09-27T10:00:00Z", timerMinutes = 30)
        assertEquals("00:10:00 remaining", gamingSessionTimingDetail(active, 20 * 60_000L))
        assertFalse(gamingSessionHasStopped(active))
        for (status in listOf("ended", "stopped", "completed")) {
            val stopped = active.copy(status = status)
            assertTrue(gamingSessionHasStopped(stopped))
            assertEquals("Booked 00:30:00", gamingSessionTimingDetail(stopped, 20 * 60_000L))
            assertEquals("Booked 00:30:00", gamingSessionTimingDetail(stopped, 65 * 60_000L))
        }
        assertEquals("Open-ended session", gamingSessionTimingDetail(active.copy(timerMinutes = null), 0))
    }

    @Test
    fun `remaining trace is bounded without changing booked duration`() {
        assertEquals(1f, requireNotNull(gamingDurationRemainingFraction(3_600_000L, 0L)), 0.001f)
        assertEquals(0.5f, requireNotNull(gamingDurationRemainingFraction(3_600_000L, 1_800_000L)), 0.001f)
        assertEquals(0f, requireNotNull(gamingDurationRemainingFraction(3_600_000L, 4_000_000L)), 0.001f)
        assertEquals(null, gamingDurationRemainingFraction(0L, 0L))
    }

    @Test
    fun `inline editor and stop leave the workspace when session lifecycle advances`() {
        listOf(
            StationVisualState.Starting,
            StationVisualState.Active,
            StationVisualState.Overtime,
            StationVisualState.Paused,
            StationVisualState.StopFailed,
        ).forEach { state ->
            assertTrue(gamingInlineEditorAvailable(state))
            assertTrue(gamingInlineStopVisible(state, canRequestStop = true))
        }

        listOf(
            StationVisualState.Stopping,
            StationVisualState.PaymentDue,
            StationVisualState.SendPending,
            StationVisualState.SendRejected,
            StationVisualState.CancellationRequired,
            StationVisualState.Available,
        ).forEach { state ->
            assertFalse(gamingInlineEditorAvailable(state))
            assertFalse(gamingInlineStopVisible(state, canRequestStop = true))
        }
        assertFalse(gamingInlineStopVisible(StationVisualState.Active, canRequestStop = false))
    }

    @Test
    fun `phone identifies a new friend while name stays optional`() {
        assertEquals(null, gamingParticipantJoinIdentityError(null, null, "9876543210"))
        assertEquals(null, gamingParticipantJoinIdentityError(null, "Optional name", "9876543210"))
        assertEquals(null, gamingParticipantJoinIdentityError("saved-customer", null, null))
        assertEquals(null, gamingParticipantJoinIdentityError(null, "Name only", null))
        assertTrue(gamingParticipantJoinIdentityError("saved-customer", null, "9876543210")!!.contains("not both"))
    }

    @Test
    fun `guest join never dispatches a stale save-new-customer phone`() {
        assertEquals(null, gamingInlineJoinPhone(registerCustomerMode = false, rawPhone = "9876543210"))
        assertEquals(null, gamingInlineJoinPhone(registerCustomerMode = false, rawPhone = ""))
        assertEquals("9876543210", gamingInlineJoinPhone(registerCustomerMode = true, rawPhone = " 9876543210 "))
        assertEquals(null, gamingInlineJoinPhone(registerCustomerMode = true, rawPhone = "123"))
    }

    @Test
    fun `rejoin intervals use one cumulative started-hour minimum per customer`() {
        val rows = listOf(
            GamingParticipantUi(
                reference = "visit-1", participantIdentityId = "friend-1",
                customerId = "friend-1", name = "Friend", phone = "9876543210",
                active = false, pending = false, joinedPlayElapsedMs = 0L, leftPlayElapsedMs = 1_800_000L,
            ),
            GamingParticipantUi(
                reference = "visit-2", participantIdentityId = "friend-1",
                customerId = "friend-1", name = "Friend", phone = "9876543210",
                active = true, pending = false, joinedPlayElapsedMs = 2_000_000L,
            ),
            GamingParticipantUi(
                reference = "visit-3", participantIdentityId = "friend-2",
                customerId = "friend-2", name = null, phone = "9876543211",
                active = true, pending = false, joinedPlayElapsedMs = 3_000_000L,
            ),
        )

        // Friend 1: 30 + 45 minutes -> two hours. Friend 2: 28 minutes -> one.
        assertEquals(9_000L, estimatedGamingFriendControllerMinor(rows, 4_700_000L))
        assertEquals(
            4_500_000L,
            gamingParticipantCumulativePlayMillis(
                rows.filter { it.participantIdentityId == "friend-1" },
                4_700_000L,
            ),
        )
        assertEquals("1h 15m", formatGamingParticipantDuration(4_500_000L))
        assertEquals("0m", formatGamingParticipantDuration(-1L))
        assertEquals(
            9_000L,
            estimatedGamingFriendControllerMinorAfterJoin(
                participants = rows,
                currentPlayElapsedMs = 4_700_000L,
                customerId = null,
                phone = "+91 98765 43210",
            ),
        )
        assertEquals(
            12_000L,
            estimatedGamingFriendControllerMinorAfterJoin(
                participants = rows,
                currentPlayElapsedMs = 4_700_000L,
                customerId = null,
                phone = "9876543299",
            ),
        )
    }

    @Test
    fun `inline roster keeps one current action per identity without losing distinct people`() {
        val rows = listOf(
            GamingParticipantUi(
                reference = "friend-a-first", participantIdentityId = "friend-a",
                customerId = null, name = "Friend A", phone = null,
                active = false, pending = false,
                joinedAtMillis = startedAt, leftAtMillis = startedAt + 60_000L,
            ),
            GamingParticipantUi(
                reference = "friend-a-latest", participantIdentityId = "friend-a",
                customerId = null, name = "Friend A", phone = null,
                active = false, pending = true,
                joinedAtMillis = startedAt + 120_000L,
                leftAtMillis = startedAt + 180_000L,
            ),
            GamingParticipantUi(
                reference = "friend-b-old", participantIdentityId = "friend-b",
                customerId = null, name = "Friend B", phone = null,
                active = false, pending = false,
                joinedAtMillis = startedAt + 30_000L,
                leftAtMillis = startedAt + 90_000L,
            ),
            GamingParticipantUi(
                reference = "friend-b-active", participantIdentityId = "friend-b",
                customerId = null, name = "Friend B", phone = null,
                active = true, pending = false,
                joinedAtMillis = startedAt + 100_000L,
            ),
        )

        val current = currentGamingParticipantRows(rows)

        assertEquals(listOf("friend-a", "friend-b"), current.map { it.participantIdentityId })
        assertEquals("friend-a-latest", current[0].reference)
        assertEquals("Waiting to sync Leave", gamingParticipantStateLabel(current[0]))
        assertEquals("friend-b-active", current[1].reference)
        assertEquals("Playing now", gamingParticipantStateLabel(current[1]))
    }

    @Test
    fun `late joins fill the remaining station places up to four`() {
        val single = session().copy(
            packageVariantSnapshot = "single",
            effectivePackageVariant = "single",
            extraControllers = 0,
        )
        assertEquals(1, gamingBookedPlayerCount(single))
        assertTrue(gamingLateJoinHasCapacity(single, activeLatePlayers = 2))
        assertFalse(gamingLateJoinHasCapacity(single, activeLatePlayers = 3))

        val dual = session().copy(
            packageVariantSnapshot = "dual",
            effectivePackageVariant = "dual",
            extraControllers = 0,
        )
        assertEquals(2, gamingBookedPlayerCount(dual))
        assertTrue(gamingLateJoinHasCapacity(dual, activeLatePlayers = 1))
        assertFalse(gamingLateJoinHasCapacity(dual, activeLatePlayers = 2))

        val dualWithBookedExtra = dual.copy(extraControllers = 1)
        assertEquals(3, gamingBookedPlayerCount(dualWithBookedExtra))
        assertTrue(gamingLateJoinHasCapacity(dualWithBookedExtra, activeLatePlayers = 0))
        assertFalse(gamingLateJoinHasCapacity(dualWithBookedExtra, activeLatePlayers = 1))
    }

    @Test
    fun `amendment is available through 29 59 but closes at exactly 30 minutes`() {
        val session = session()
        val target = package30("ps5", "single", 8_000)

        assertTrue(canAmendGamingPackageTo30(session, target, startedAt + 1_799_999))
        assertFalse(canAmendGamingPackageTo30(session, target, startedAt + 1_800_000))
        assertFalse(canAmendGamingPackageTo30(session.copy(participantRevision = 1), target, startedAt + 1_000))
        assertFalse(canAmendGamingPackageTo30(session.copy(billingRevision = 1), target, startedAt + 1_000))
    }

    @Test
    fun `offline start has version zero and permits amendment before thirty played minutes`() {
        val local = pendingStart().toGameSession()
        val target = package30("ps5", "single", 8_000)

        assertTrue(local.isLocallyPendingStart())
        assertTrue(local.isLocallyPendingPs5PackageStart())
        assertEquals(0, local.pauseVersion)
        assertEquals(0, local.participantRevision)
        assertEquals(0, local.billingRevision)
        assertTrue(canAmendGamingPackageTo30(local, target, startedAt + 1_799_999))
        assertFalse(canAmendGamingPackageTo30(local, target, startedAt + 1_800_000))
        assertFalse(canAmendGamingPackageTo30(
            pendingStart().copy(state = GamingSessionState.START_REJECTED, status = "start_failed")
                .toGameSession(),
            target,
            startedAt + 1_000,
        ))
    }

    @Test
    fun `offline start amendment and friend attendance project in FIFO without a speculative friend charge`() {
        val local = pendingStart().toGameSession()
        val amend = action(
            id = "amend-1", sequence = 1, type = GamingSessionActionType.AMEND,
            expectedParticipantRevision = 0, expectedBillingRevision = 0,
            packageId = "single-30", expectedPackagePriceMinor = 8_000,
            expectedPackageDurationMinutes = 30, expectedPackageVariant = "single",
            expectedSessionTimerMinutes = 60, expectedSessionAmountMinor = 12_000,
        ).copy(sessionKey = local.id, localSessionId = local.id, serverSessionId = null)
        val join = action(
            id = "join-1", sequence = 2, type = GamingSessionActionType.PARTICIPANT_JOIN,
            expectedParticipantRevision = 0,
        ).copy(
            sessionKey = local.id, localSessionId = local.id, serverSessionId = null,
            customerId = null, customerName = "Amina", customerPhone = "9876543210",
        )
        val leave = action(
            id = "leave-1", sequence = 3, type = GamingSessionActionType.PARTICIPANT_LEAVE,
            expectedParticipantRevision = 1, participantReference = "join-1",
        ).copy(sessionKey = local.id, localSessionId = local.id, serverSessionId = null)

        val afterAmend = projectPendingGamingSessionActions(local, listOf(amend))
        val afterJoin = projectPendingGamingSessionActions(local, listOf(amend, join))
        val afterLeave = projectPendingGamingSessionActions(local, listOf(leave, join, amend))
        val friend = mergeGamingParticipants(local, emptyList(), listOf(join, leave), emptyList()).single()

        assertEquals(30, afterAmend.timerMinutes)
        assertEquals(Instant.ofEpochMilli(startedAt + 1_800_000).toString(), afterAmend.timerEndsAt)
        assertEquals(8_000L, afterAmend.amountMinor)
        assertEquals(1, afterAmend.billingRevision)
        assertEquals(1, afterJoin.participantRevision)
        assertEquals(2, afterLeave.participantRevision)
        assertEquals(8_000L, afterLeave.amountMinor)
        assertEquals("Amina", friend.name)
        assertFalse(friend.active)
        assertTrue(friend.pending)

        val failedStart = pendingStart().copy(
            state = GamingSessionState.START_REJECTED,
            status = "start_failed",
        ).toGameSession()
        assertEquals(failedStart, projectPendingGamingSessionActions(failedStart, listOf(amend, join)))
        assertFalse(mergeGamingParticipants(failedStart, emptyList(), listOf(join), emptyList()).single().active)
    }

    @Test
    fun `offline amendment projects effective tariff without rewriting original snapshot`() {
        val original = session(amountMinor = 18_000, extraControllers = 1)
        val action = action(
            type = GamingSessionActionType.AMEND,
            expectedParticipantRevision = 0,
            expectedBillingRevision = 0,
            packageId = "single-30",
            expectedPackagePriceMinor = 8_000,
            expectedPackageDurationMinutes = 30,
            expectedPackageVariant = "single",
            expectedSessionTimerMinutes = 60,
            expectedSessionAmountMinor = 18_000,
        )

        val projected = projectPendingGamingSessionActions(original, listOf(action))

        assertEquals("single-60", projected.packageId)
        assertEquals(15_000L, projected.packagePriceMinorSnapshot)
        assertEquals(60, projected.packageDurationMinutesSnapshot)
        assertEquals("single-30", projected.effectivePackageId)
        assertEquals(8_000L, projected.effectivePackagePriceMinor)
        assertEquals(30, projected.timerMinutes)
        assertEquals(11_000L, projected.amountMinor)
        assertEquals(1, projected.billingRevision)
    }

    @Test
    fun `pending join then leave overlays roster and increments revision in FIFO order`() {
        val join = action(
            id = "join-1",
            sequence = 1,
            type = GamingSessionActionType.PARTICIPANT_JOIN,
            expectedParticipantRevision = 0,
            customerId = "friend-1",
        )
        val leave = action(
            id = "leave-1",
            sequence = 2,
            type = GamingSessionActionType.PARTICIPANT_LEAVE,
            expectedParticipantRevision = 1,
            participantReference = "join-1",
        )
        val session = session()

        val roster = mergeGamingParticipants(
            session,
            emptyList(),
            listOf(join, leave),
            listOf(GamingCustomerOption("friend-1", "Amina", "9876543210", false)),
        )
        val projected = projectPendingGamingSessionActions(session, listOf(join, leave))

        assertEquals(1, roster.size)
        assertFalse(roster.single().active)
        assertTrue(roster.single().pending)
        assertEquals(2, projected.participantRevision)
    }

    @Test
    fun `confirmed join with saved leave still hides friend and blocks duplicate leave`() {
        val join = action(
            id = "join-1",
            sequence = 1,
            type = GamingSessionActionType.PARTICIPANT_JOIN,
            customerId = "friend-1",
        ).copy(
            state = GamingSessionActionState.CONFIRMED,
            resultParticipantId = "participant-1",
        )
        val leave = action(
            id = "leave-1",
            sequence = 2,
            type = GamingSessionActionType.PARTICIPANT_LEAVE,
            expectedParticipantRevision = 1,
            participantReference = join.actionId,
        )
        val cached = GamingSessionParticipantCacheEntity(
            id = "participant-1",
            gamingSessionId = session().id,
            customerId = "friend-1",
            joinedAtMillis = startedAt + 1_000,
            joinedPlayElapsedMs = 1_000,
            joinRevision = 1,
        )
        val actions = listOf(join, leave)

        val roster = mergeGamingParticipants(session(), listOf(cached), actions, emptyList())

        assertEquals(1, roster.size)
        assertEquals(cached.id, roster.single().reference)
        assertFalse(roster.single().active)
        assertTrue(roster.single().pending)
        assertEquals(leave.occurredAtMillis, roster.single().leftAtMillis)
        assertEquals(leave.playElapsedMs, roster.single().leftPlayElapsedMs)
        assertEquals(3_000L, estimatedGamingFriendControllerMinor(roster, leave.playElapsedMs!! + 60_000L))
        assertTrue(hasSavedGamingParticipantLeave(join.actionId, actions))
        assertTrue(hasSavedGamingParticipantLeave(cached.id, actions))
        assertFalse(hasSavedGamingParticipantLeave("participant-2", actions))
        val board = GamingUiState(
            sessions = listOf(session()),
            participantCache = listOf(cached),
            sessionActions = actions,
        )
        assertFalse(board.participantsFor(session()).single().active)
        assertEquals(listOf(leave.actionId), board.unresolvedSequencedActionsFor(session()).map { it.actionId })

        val rejected = leave.copy(state = GamingSessionActionState.REJECTED, lastError = "Roster changed")
        val rejectedRoster = mergeGamingParticipants(session(), listOf(cached), listOf(join, rejected), emptyList())
        assertTrue(rejectedRoster.single().active)
        assertEquals("Roster changed", rejectedRoster.single().lastError)
    }

    @Test
    fun `server roster and pending leave never remove booked players`() {
        val session = session().copy(packageVariantSnapshot = "dual", effectivePackageVariant = "dual")
        val cached = GamingSessionParticipantCacheEntity(
            id = "participant-1",
            gamingSessionId = session.id,
            customerId = "friend-1",
            joinedAtMillis = startedAt + 1_000,
            joinedPlayElapsedMs = 1_000,
            joinRevision = 1,
        )
        val leave = action(
            type = GamingSessionActionType.PARTICIPANT_LEAVE,
            expectedParticipantRevision = 1,
            participantReference = cached.id,
        )

        val roster = mergeGamingParticipants(session, listOf(cached), listOf(leave), emptyList())

        assertFalse(roster.single().active)
        assertEquals("dual", session.packageVariantSnapshot)
        assertEquals(2, if (session.packageVariantSnapshot == "dual") 2 else 1)
    }

    @Test
    fun `rejected attendance does not alter the effective roster`() {
        val cached = GamingSessionParticipantCacheEntity(
            id = "participant-1",
            gamingSessionId = "session-1",
            customerId = "friend-1",
            joinedAtMillis = startedAt + 1_000,
            joinedPlayElapsedMs = 1_000,
            joinRevision = 1,
        )
        val rejectedLeave = action(
            id = "leave-1",
            type = GamingSessionActionType.PARTICIPANT_LEAVE,
            participantReference = cached.id,
        ).copy(state = GamingSessionActionState.REJECTED, lastError = "Roster changed")
        val rejectedJoin = action(
            id = "join-2",
            sequence = 2,
            type = GamingSessionActionType.PARTICIPANT_JOIN,
            customerId = "friend-2",
        ).copy(state = GamingSessionActionState.REJECTED, lastError = "Customer deleted")

        val roster = mergeGamingParticipants(
            session(),
            listOf(cached),
            listOf(rejectedLeave, rejectedJoin),
            emptyList(),
        )

        assertTrue(roster.first { it.reference == cached.id }.active)
        assertEquals("Roster changed", roster.first { it.reference == cached.id }.lastError)
        assertFalse(roster.first { it.reference == "join-2" }.active)
        assertEquals("Customer deleted", roster.first { it.reference == "join-2" }.lastError)
    }

    @Test
    fun `racing and VR extension selectors use canonical mode and tier`() {
        for ((stationType, variant, price) in listOf(
            Triple("racing", "simdrive", 7_000L),
            Triple("vr", "vr_games", 8_000L),
            Triple("vr", "vr_racing", 10_000L),
        )) {
            val session = session().copy(
                packageStationTypeSnapshot = stationType,
                packageVariantSnapshot = variant,
                effectivePackageStationType = stationType,
                effectivePackageVariant = variant,
            )
            val extension = package30(stationType, variant, price).copy(kind = "extension")
            assertEquals(
                listOf(extension.id),
                matchingPackageExtensions(
                    session,
                    Station("station-1", "S1", "Station", stationType, 0),
                    listOf(extension, extension.copy(id = "wrong", variant = "other")),
                ).map(GamingPackage::id),
            )
        }
    }

    @Test
    fun `pause resume and other direct mutations explain FIFO block`() {
        assertEquals(
            "2 saved session action(s) must sync in order before pausing. " +
                "This protects the captured play clock and billing revisions.",
            sequencedGamingActionBlockMessage(2, "pausing"),
        )
    }

    private fun session(amountMinor: Long = 15_000, extraControllers: Int = 0) = GameSession(
        id = "session-1",
        stationId = "station-1",
        shiftId = "shift-1",
        status = "active",
        startAt = Instant.ofEpochMilli(startedAt).toString(),
        timerMinutes = 60,
        timerEndsAt = Instant.ofEpochMilli(startedAt + 3_600_000).toString(),
        pauseVersion = 0,
        amountMinor = amountMinor,
        billingMode = "package",
        packageId = "single-60",
        packagePriceMinorSnapshot = 15_000,
        packageDurationMinutesSnapshot = 60,
        packageVariantSnapshot = "single",
        packageStationTypeSnapshot = "ps5",
        packagePricingTierSnapshot = "standard",
        extraControllers = extraControllers,
        effectivePackageId = "single-60",
        effectivePackagePriceMinor = 15_000,
        effectivePackageDurationMinutes = 60,
        effectivePackageVariant = "single",
        effectivePackageStationType = "ps5",
        effectivePackagePricingTier = "standard",
    )

    private fun pendingStart() = LocalGamingSessionEntity(
        localId = "local-session-1",
        stationId = "station-1",
        shiftId = "shift-1",
        startedAtMillis = startedAt,
        state = GamingSessionState.START_PENDING,
        status = "starting",
        timerMinutes = 60,
        timerEndsAtMillis = startedAt + 3_600_000,
        amountMinor = 12_000,
        packageId = "single-60",
        packagePriceMinor = 12_000,
        packageDurationMinutes = 60,
        packageVariant = "single",
        packageStationTypeSnapshot = "ps5",
        packagePricingTierSnapshot = "standard",
        billingMode = "package",
    )

    private fun package30(stationType: String, variant: String, priceMinor: Long) = GamingPackage(
        id = "$variant-30",
        code = "$variant-session-30m",
        stationType = stationType,
        pricingTier = "standard",
        variant = variant,
        kind = "base",
        name = "$variant 30 minutes",
        durationMinutes = 30,
        priceMinor = priceMinor,
    )

    private fun action(
        id: String = "action-1",
        sequence: Long = 1,
        type: String,
        expectedParticipantRevision: Int? = null,
        expectedBillingRevision: Int? = null,
        customerId: String? = null,
        participantReference: String? = null,
        packageId: String? = null,
        expectedPackagePriceMinor: Long? = null,
        expectedPackageDurationMinutes: Int? = null,
        expectedPackageVariant: String? = null,
        expectedSessionTimerMinutes: Int? = null,
        expectedSessionAmountMinor: Long? = null,
    ) = LocalGamingSessionActionEntity(
        actionId = id,
        sessionKey = "session-1",
        sequence = sequence,
        actionType = type,
        ownerCompanyId = "company-1",
        ownerUserId = "user-1",
        branchId = "branch-1",
        terminalId = "terminal-1",
        serverSessionId = "session-1",
        shiftId = "shift-1",
        occurredAtMillis = startedAt + sequence,
        playElapsedMs = sequence,
        expectedPauseVersion = 0,
        expectedParticipantRevision = expectedParticipantRevision,
        expectedBillingRevision = expectedBillingRevision,
        customerId = customerId,
        participantReference = participantReference,
        packageId = packageId,
        expectedPackagePriceMinor = expectedPackagePriceMinor,
        expectedPackageDurationMinutes = expectedPackageDurationMinutes,
        expectedPackageVariant = expectedPackageVariant,
        expectedSessionTimerMinutes = expectedSessionTimerMinutes,
        expectedSessionAmountMinor = expectedSessionAmountMinor,
        state = GamingSessionActionState.PENDING,
    )
}
