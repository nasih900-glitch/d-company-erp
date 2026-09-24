package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.GamingSessionActionState
import cloud.dcompany.erp.core.db.GamingSessionActionType
import cloud.dcompany.erp.core.db.GamingSessionParticipantCacheEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionActionEntity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingParticipantAmendmentPolicyTest {
    private val startedAt = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli()

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
