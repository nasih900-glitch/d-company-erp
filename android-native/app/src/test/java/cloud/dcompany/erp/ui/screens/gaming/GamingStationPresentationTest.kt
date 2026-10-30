package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingLegacyResolution
import cloud.dcompany.erp.core.db.GamingCleanupWorkflowStatus
import cloud.dcompany.erp.core.db.LEGACY_PACKAGE_START_REVIEW_ERROR
import cloud.dcompany.erp.core.db.gamingCleanupWorkflowMessageOrNull
import cloud.dcompany.erp.ui.components.VOID_REASON_OTHER_ID
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.UiTone
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingStationPresentationTest {
    private val station = Station(
        id = "station-1",
        code = "PS5-1",
        name = "PS5 Station 1",
        type = "ps5",
        ratePerHourMinor = 15_000,
    )
    private val now = Instant.parse("2026-08-26T18:00:00Z").toEpochMilli()

    @Test
    fun `primary and destructive finishes remain readable in day and night appearances`() {
        val dayPrimary = requireNotNull(gamingActionFinish(ActionIntent.Primary, day = true))
        val nightPrimary = requireNotNull(gamingActionFinish(ActionIntent.Primary, day = false))
        val dayDestructive = requireNotNull(gamingActionFinish(ActionIntent.Destructive, day = true))
        val nightDestructive = requireNotNull(gamingActionFinish(ActionIntent.Destructive, day = false))

        listOf(dayPrimary, nightPrimary, dayDestructive, nightDestructive).forEach { finish ->
            assertTrue(
                "action text must meet WCAG small-text contrast",
                contrastRatio(finish.content, finish.container) >= 4.5,
            )
        }
        assertTrue("night primary must remain the brighter gold action", luminance(nightPrimary.container) > luminance(dayPrimary.container))
        assertEquals(dayDestructive, nightDestructive)
        assertNull(gamingActionFinish(ActionIntent.Secondary, day = false))
    }

    @Test
    fun `station rows use spare board height without stretching or shrinking below safe controls`() {
        assertEquals(88, gamingStationRowHeight(360, 4))
        assertEquals(100, gamingStationRowHeight(900, 4))
        assertEquals(60, gamingStationRowHeight(240, 8))
        assertEquals(60, gamingStationRowHeight(240, 0))
    }

    @Test
    fun `inline station table keeps stable accessible geometry across operational states`() {
        assertEquals(72.dp, gamingStationTableRowHeight(fontScale = 1f))
        assertEquals(84.dp, gamingStationTableRowHeight(fontScale = 1.3f))
        assertEquals(316.dp, gamingInlineExpansionHeight(504.dp, 72.dp, 0, 3))
        assertEquals(280.dp, gamingInlineExpansionHeight(504.dp, 72.dp, 1, 3))
        assertEquals(280.dp, gamingInlineExpansionHeight(504.dp, 72.dp, 2, 3))
        assertEquals(520.dp, gamingInlineExpansionHeight(420.dp, 84.dp, 0, 3))
        assertEquals(244.dp, gamingInlineDialSize(800, 316.dp))
        assertEquals(208.dp, gamingInlineDialSize(800, 280.dp))
        assertEquals(232.dp, gamingInlineDialSize(520, 520.dp))
        assertEquals(196.dp, gamingInlineDialSize(563, 208.dp))
        assertEquals(161.dp, gamingMeasuredInlineDialSize(196.dp, 260.dp, 161.dp))
        assertEquals(196.dp, gamingMeasuredInlineDialSize(196.dp, 260.dp, 240.dp))
        assertEquals(184.dp, gamingMeasuredInlineDialSize(196.dp, 184.dp, Dp.Infinity))
        assertEquals(
            208.dp,
            gamingInlineExpansionHeight(
                boardViewportHeight = 368.dp,
                rowHeight = 60.dp,
                selectedIndex = 1,
                rowCount = 3,
                minimum = 208.dp,
                maximum = 208.dp,
            ),
        )
    }

    @Test
    fun `measured workspace policy preserves master ring and responsive fallbacks`() {
        val master = gamingWorkspaceLayoutPolicy(1_016.dp, 499.dp, 1f)
        assertTrue(master.wide)
        assertTrue(master.shortWide)
        assertEquals(52.dp, master.headlineHeight)
        assertEquals(52.dp, master.rowHeight)
        assertEquals(208.dp, master.expansionMinimum)
        assertEquals(208.dp, master.expansionMaximum)
        assertEquals(0.dp, master.contentVerticalPadding)
        val readyMasterBoardBudget = 499.dp - master.headlineHeight - 48.dp - 24.dp
        val readyMasterRequired = 30.dp + master.rowHeight * 2 +
            master.expansionMinimum + 3.dp
        assertTrue(
            "ready master must fit sticky header, neighbour, selected row, and exact ring workspace",
            readyMasterBoardBudget >= readyMasterRequired,
        )

        val belowFourZoneWidth = gamingWorkspaceLayoutPolicy(963.dp, 480.dp, 1f)
        assertFalse(belowFourZoneWidth.shortWide)
        assertEquals(240.dp, belowFourZoneWidth.expansionMinimum)
        val exactFourZoneWidth = gamingWorkspaceLayoutPolicy(964.dp, 480.dp, 1f)
        assertTrue(exactFourZoneWidth.shortWide)
        assertEquals(208.dp, exactFourZoneWidth.expansionMinimum)

        val constrainedShortWide = gamingWorkspaceLayoutPolicy(960.dp, 480.dp, 1f)
        assertTrue(constrainedShortWide.wide)
        assertFalse(constrainedShortWide.shortWide)
        assertEquals(64.dp, constrainedShortWide.headlineHeight)
        assertEquals(52.dp, constrainedShortWide.rowHeight)
        assertEquals(240.dp, constrainedShortWide.expansionMinimum)
        assertEquals(320.dp, constrainedShortWide.expansionMaximum)
        assertEquals(8.dp, constrainedShortWide.contentVerticalPadding)

        val shortWideWithoutFocalArt = gamingWorkspaceLayoutPolicy(
            availableWidth = 1_016.dp,
            availableHeight = 499.dp,
            fontScale = 1f,
            focalArtworkAvailable = false,
        )
        assertTrue(shortWideWithoutFocalArt.wide)
        assertFalse(shortWideWithoutFocalArt.shortWide)
        assertEquals(240.dp, shortWideWithoutFocalArt.expansionMinimum)

        val tablet = gamingWorkspaceLayoutPolicy(1_016.dp, 736.dp, 1f)
        assertTrue(tablet.wide)
        assertFalse(tablet.shortWide)
        assertEquals(64.dp, tablet.headlineHeight)
        assertEquals(52.dp, tablet.rowHeight)
        assertEquals(240.dp, tablet.expansionMinimum)
        assertEquals(320.dp, tablet.expansionMaximum)
        assertEquals(8.dp, tablet.contentVerticalPadding)

        val compactLargeText = gamingWorkspaceLayoutPolicy(552.dp, 736.dp, 1.3f)
        assertFalse(compactLargeText.wide)
        assertFalse(compactLargeText.shortWide)
        assertEquals(84.dp, compactLargeText.rowHeight)
        assertEquals(520.dp, compactLargeText.expansionMinimum)
    }

    @Test
    fun `wide selected workspace admits focal art only after dial and detail minimums fit`() {
        val admitted = gamingSelectedWorkspaceGeometry(
            availableWidth = 1_040.dp,
            preferredDialSize = 260.dp,
            artworkAvailable = true,
            wide = true,
        )
        assertTrue(admitted.showArtwork)
        assertEquals(212.dp, admitted.artworkWidth)
        assertEquals(200.dp, admitted.identityWidth)
        assertEquals(260.dp, admitted.dialSlotWidth)
        assertEquals(320.dp, admitted.detailMinimumWidth)

        val actualMaster = gamingSelectedWorkspaceGeometry(
            availableWidth = 1_016.dp,
            preferredDialSize = 196.dp,
            artworkAvailable = true,
            wide = true,
        )
        assertTrue(actualMaster.fourZone)
        assertEquals(225.dp, actualMaster.artworkWidth)
        assertEquals(200.dp, actualMaster.identityWidth)

        val actualTablet = gamingSelectedWorkspaceGeometry(
            availableWidth = 1_016.dp,
            preferredDialSize = 248.dp,
            artworkAvailable = true,
            wide = true,
        )
        assertTrue(actualTablet.fourZone)
        assertEquals(200.dp, actualTablet.artworkWidth)

        assertFalse(
            gamingSelectedWorkspaceGeometry(800.dp, 260.dp, artworkAvailable = true, wide = true)
                .showArtwork,
        )
        assertFalse(
            gamingSelectedWorkspaceGeometry(1_040.dp, 260.dp, artworkAvailable = false, wide = true)
                .showArtwork,
        )
        assertFalse(
            gamingSelectedWorkspaceGeometry(1_040.dp, 260.dp, artworkAvailable = true, wide = false)
                .showArtwork,
        )
    }

    @Test
    fun `focal artwork preserves source aspect and bounded requested size`() {
        val ps5 = checkNotNull(gamingStationArtworkSpec("ps5"))
        val racing = checkNotNull(gamingStationArtworkSpec("racing"))
        val vr = checkNotNull(gamingStationArtworkSpec("vr"))
        assertEquals(900, ps5.sourceWidthPx)
        assertEquals(717, ps5.sourceHeightPx)
        assertEquals(749, racing.sourceHeightPx)
        assertEquals(600, vr.sourceHeightPx)

        val full = checkNotNull(gamingFittedStationArtworkSize("ps5", 225.dp, 300.dp))
        assertEquals(225.dp, full.width)
        assertEquals(225f / ps5.aspectRatio, full.height.value, 0.01f)
        val heightBound = checkNotNull(gamingFittedStationArtworkSize("vr", 225.dp, 100.dp))
        assertEquals(100.dp, heightBound.height)
        assertEquals(150.dp, heightBound.width)
        assertNull(gamingFittedStationArtworkSize("unknown", 225.dp, 225.dp))
    }

    @Test
    fun `selected identity exposes name and player count without phone`() {
        val active = session(
            status = "active",
            customerName = " Asha ",
            customerPhone = "9876543210",
            packageVariantSnapshot = "dual",
            extraControllers = 1,
        )
        val label = gamingSelectedSessionIdentityLabel(active, emptyList())
        assertEquals("Asha · 3 players", label)
        assertFalse(label.contains("9876543210"))
    }

    @Test
    fun `collapsed available station uses published package truth and no legacy hourly fallback`() {
        val projection = gamingStationRowProjection(
            station = station,
            session = null,
            packages = listOf(
                GamingPackage(
                    id = "standard-single-30",
                    code = "standard-single-session-30m",
                    stationType = station.type,
                    pricingTier = "standard",
                    variant = "single",
                    kind = "base",
                    name = "Single Mode 30 minutes",
                    durationMinutes = 30,
                    priceMinor = 8_000,
                ),
            ),
            hasActiveAddons = false,
            combinedBillSnapshotMinor = null,
            nowMillis = now,
        )

        assertEquals(StationVisualState.Available, projection.presentation.state)
        assertEquals("Ready", projection.timerValue)
        assertEquals("Fixed packages from ₹80.00", projection.pricingValue)
        assertFalse(projection.pricingValue.contains("₹150.00"))
    }

    @Test
    fun `collapsed bounded active station shows remaining time and locked package total`() {
        val active = session(
            status = "active",
            amountMinor = 8_000,
            packageId = "standard-single-30",
            timerMinutes = 30,
            billingMode = "package",
            packagePriceMinorSnapshot = 8_000,
            packageDurationMinutesSnapshot = 30,
            packageVariantSnapshot = "single",
            packageStationTypeSnapshot = station.type,
            packagePricingTierSnapshot = "standard",
        ).copy(startAt = "2026-08-26T17:50:00Z")

        val projection = gamingStationRowProjection(
            station = station,
            session = active,
            packages = emptyList(),
            hasActiveAddons = false,
            combinedBillSnapshotMinor = null,
            nowMillis = now,
        )

        assertEquals(StationVisualState.Active, projection.presentation.state)
        assertEquals("00:20:00 remaining", projection.timerValue)
        assertEquals("Single · booked ₹80.00", projection.pricingValue)
        assertFalse(projection.pricingValue.contains("total", ignoreCase = true))
    }

    @Test
    fun `collapsed required tariff gap is a warning while station remains available`() {
        val projection = gamingStationRowProjection(
            station = station.copy(type = "racing"),
            session = null,
            packages = emptyList(),
            hasActiveAddons = false,
            combinedBillSnapshotMinor = null,
            nowMillis = now,
        )

        assertEquals(StationVisualState.Available, projection.presentation.state)
        assertEquals("Sync rates", projection.presentation.statusLabel)
        assertEquals(UiTone.Warning, projection.presentation.tone)
        assertEquals("Ready", projection.timerValue)
        assertEquals("Fixed-price tariff not synced", projection.pricingValue)
    }

    @Test
    fun `collapsed station retains failed and unavailable timing truth`() {
        val failed = gamingStationRowProjection(
            station = station,
            session = session(status = "start_failed", lastError = "retained"),
            packages = emptyList(),
            hasActiveAddons = false,
            combinedBillSnapshotMinor = null,
            nowMillis = now,
        )
        val invalid = gamingStationRowProjection(
            station = station,
            session = session(status = "active", timerMinutes = 30).copy(startAt = "not-a-timestamp"),
            packages = emptyList(),
            hasActiveAddons = false,
            combinedBillSnapshotMinor = null,
            nowMillis = now,
        )

        assertEquals(StationVisualState.StartFailed, failed.presentation.state)
        assertEquals("Start needs review", failed.timerValue)
        assertEquals("Time unavailable", invalid.timerValue)
    }

    @Test
    fun `collapsed player count uses booked mode and retained participant attendance`() {
        val dual = session(
            status = "active",
            packageVariantSnapshot = "dual",
            extraControllers = 1,
        )
        val participants = listOf(
            GamingParticipantUi(
                reference = "friend-1",
                participantIdentityId = "friend-1",
                customerId = null,
                name = "Friend one",
                phone = null,
                active = true,
                pending = false,
            ),
            GamingParticipantUi(
                reference = "friend-2",
                participantIdentityId = "friend-2",
                customerId = null,
                name = "Friend two",
                phone = null,
                active = false,
                pending = false,
            ),
        )

        assertEquals(3, gamingStationRowPlayerCount(dual, participants))
        assertEquals(2, gamingStationRowPlayerCount(dual.copy(extraControllers = 0), participants))
    }

    @Test
    fun `available canonical stations advertise fixed packages and never the legacy hourly rate`() {
        val packages = listOf(
            GamingPackage(
                id = "standard-single-30",
                code = "standard-single-session-30m",
                stationType = "ps5",
                pricingTier = "standard",
                variant = "single",
                kind = "base",
                name = "Single Mode 30 minutes",
                durationMinutes = 30,
                priceMinor = 8_000,
            ),
            GamingPackage(
                id = "premium-single-60",
                code = "premium-single-session-60m",
                stationType = "ps5",
                pricingTier = "premium",
                variant = "single",
                kind = "base",
                name = "Premium Single Mode 1 hour",
                durationMinutes = 60,
                priceMinor = 15_000,
            ),
        )

        assertEquals("Fixed packages from ₹80.00", availableStationPricingDescription(station, packages))
        assertEquals("Fixed-price tariff not synced", availableStationPricingDescription(station, emptyList()))
        assertEquals(
            "Fixed packages from ₹70.00",
            availableStationPricingDescription(
                station.copy(type = "simulator", ratePerHourMinor = 40_000),
                listOf(
                    GamingPackage(
                        id = "standard-simdrive-15",
                        code = "standard-simdrive-session-15m",
                        stationType = "simulator",
                        pricingTier = "standard",
                        variant = "simdrive",
                        kind = "base",
                        name = "Simdrive 15 minutes",
                        durationMinutes = 15,
                        priceMinor = 7_000,
                    ),
                ),
            ),
        )
        assertEquals(
            "Fixed packages from ₹80.00",
            availableStationPricingDescription(
                station.copy(type = "vr", ratePerHourMinor = 35_000),
                listOf(
                    GamingPackage(
                        id = "vr-games-15",
                        code = "vr-games-session-15m",
                        stationType = "vr",
                        variant = "vr_games",
                        kind = "base",
                        name = "VR Games · 15 min",
                        durationMinutes = 15,
                        priceMinor = 8_000,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `canonical tariff classification uses exact station aliases`() {
        assertTrue(requiresCanonicalGamingTariff("PS5"))
        assertTrue(requiresCanonicalGamingTariff("PlayStation 5"))
        assertTrue(requiresCanonicalGamingTariff("gaming-console"))
        assertTrue(requiresCanonicalGamingTariff("simulator"))
        assertTrue(requiresCanonicalGamingTariff("Racing Simulator"))
        assertTrue(requiresCanonicalGamingTariff("simdrive"))

        assertFalse(requiresCanonicalGamingTariff("simulation lab"))
        assertFalse(requiresCanonicalGamingTariff("simple booth"))
        assertFalse(requiresCanonicalGamingTariff("console accessories"))
    }

    @Test
    fun `required canonical tariff availability uses package semantics rather than display copy`() {
        val valid = GamingPackage(
            id = "standard-single-30",
            code = "standard-single-session-30m",
            stationType = station.type,
            pricingTier = "standard",
            variant = "single",
            kind = "base",
            name = "Single Mode 30 minutes",
            durationMinutes = 30,
            priceMinor = 8_000,
        )

        assertTrue(isRequiredCanonicalGamingTariffMissing(station, emptyList()))
        assertFalse(isRequiredCanonicalGamingTariffMissing(station, listOf(valid)))
        assertFalse(
            isRequiredCanonicalGamingTariffMissing(
                station.copy(type = "shisha"),
                emptyList(),
            ),
        )
        assertTrue(
            isRequiredCanonicalGamingTariffMissing(
                station,
                listOf(
                    valid.copy(id = "premium", pricingTier = "premium"),
                    valid.copy(id = "blank-code", code = ""),
                ),
            ),
        )
    }

    @Test
    fun `extension-only catalogue does not satisfy the required canonical base tariff`() {
        val extension = GamingPackage(
            id = "single-extension-30",
            code = "single-extension-30m",
            stationType = station.type,
            pricingTier = "standard",
            variant = "single",
            kind = "extension",
            name = "Add 30 minutes",
            durationMinutes = 30,
            priceMinor = 6_000,
        )

        assertTrue(isRequiredCanonicalGamingTariffMissing(station, listOf(extension)))
    }

    @Test
    fun `otherwise valid tariff for another station does not satisfy the requirement`() {
        val otherStationTariff = GamingPackage(
            id = "racing-standard-30",
            code = "racing-standard-30m",
            stationType = "racing",
            pricingTier = "standard",
            variant = "simdrive",
            kind = "base",
            name = "Racing 30 minutes",
            durationMinutes = 30,
            priceMinor = 10_000,
        )

        assertTrue(isRequiredCanonicalGamingTariffMissing(station, listOf(otherStationTariff)))
    }

    @Test
    fun `operational dialogs reserve footer space on compact tablet height`() {
        assertEquals(330.dp, gamingDialogBodyMaxHeight(screenHeightDp = 600))
        assertEquals(440.dp, gamingDialogBodyMaxHeight(screenHeightDp = 800))
        assertEquals(440.dp, gamingDialogBodyMaxHeight(screenHeightDp = 1_200))
    }

    @Test
    fun `only the compact no-addon custom reason uses the short IME safe body`() {
        assertTrue(useCompactVoidCustomLayout(VOID_REASON_OTHER_ID))
        assertFalse(useCompactVoidCustomLayout("guest_changed_mind"))
        assertFalse(useCompactVoidCustomLayout(null))

        assertEquals(120.dp, voidDialogBodyMaxHeight(600, compactCustomLayout = true))
        assertEquals(120.dp, voidDialogBodyMaxHeight(800, compactCustomLayout = true))
        assertEquals(120.dp, voidDialogBodyMaxHeight(1_200, compactCustomLayout = true))
        assertEquals(330.dp, voidDialogBodyMaxHeight(600, compactCustomLayout = false))
        assertEquals(440.dp, voidDialogBodyMaxHeight(800, compactCustomLayout = false))
    }

    @Test
    fun `full width gaming form dialogs expose IME insets to their root layout`() {
        assertFalse(gamingImeAwareDialogProperties.usePlatformDefaultWidth)
        assertFalse(gamingImeAwareDialogProperties.decorFitsSystemWindows)
    }

    @Test
    fun `station command workspace is reserved for genuinely wide landscape tablets`() {
        assertTrue(useGamingCommandWorkspace(widthDp = 1_100, heightDp = 720))
        assertTrue(useGamingCommandWorkspace(widthDp = 980, heightDp = 600))
        assertFalse(useGamingCommandWorkspace(widthDp = 979, heightDp = 720))
        assertFalse(useGamingCommandWorkspace(widthDp = 1_100, heightDp = 599))
    }

    @Test
    fun `station command selection survives refresh but falls back after filtering`() {
        assertEquals(
            "station-2",
            resolveGamingCommandStationId(
                visibleStationIds = listOf("station-1", "station-2"),
                currentStationId = "station-2",
            ),
        )
        assertEquals(
            "station-1",
            resolveGamingCommandStationId(
                visibleStationIds = listOf("station-1"),
                currentStationId = "station-2",
            ),
        )
        assertNull(resolveGamingCommandStationId(emptyList(), "station-2"))
    }

    @Test
    fun `cleanup workflow messages are fixed non-sensitive operator guidance`() {
        GamingCleanupWorkflowStatus.entries.forEach { status ->
            assertEquals(
                status.persistedMessage,
                gamingCleanupWorkflowMessageOrNull(status.persistedMessage),
            )
            assertFalse(status.persistedMessage.contains("session-"))
            assertFalse(status.persistedMessage.contains("customer", ignoreCase = true))
        }
        assertNull(gamingCleanupWorkflowMessageOrNull("server exception with private details"))
    }

    @Test
    fun `payment due card surfaces protected cleanup state before ordinary POS guidance`() {
        val status = GamingCleanupWorkflowStatus.WAITING_FOR_OWNER.persistedMessage
        val stopped = session(
            status = "ended",
            amountMinor = 12_000,
            billableMinutes = 60,
            localState = GamingSessionState.ENDED_UNBILLED,
            lastError = status,
        )

        assertEquals(status, unbilledSessionDetail(StationVisualState.PaymentDue, stopped))
        assertFalse(
            gamingSessionActionsEnabled(
                canWrite = true,
                actionInProgress = false,
                lastError = status,
            ),
        )
        assertTrue(
            gamingSessionActionsEnabled(
                canWrite = true,
                actionInProgress = false,
                lastError = "An ordinary rejected action can still be retried.",
            ),
        )
    }

    @Test
    fun `cross terminal payment guidance distinguishes source shift from closed shift recovery`() {
        assertEquals(
            "This payment belongs to another terminal. Finish it there while its source shift is open. " +
                "After that source shift closes, ask the protected owner to reconcile it into a current POS shift.",
            CROSS_TERMINAL_PAYMENT_GUIDANCE,
        )
    }

    @Test
    fun `retired cleanup acknowledgement remains visible and escalates mismatches`() {
        val pending = gamingCleanupAcknowledgementBannerContent(
            GamingUiState(
                stations = listOf(station),
                cleanupAcknowledgementsPending = listOf(
                    GamingCleanupAcknowledgementUi(
                        stationId = station.id,
                        detail = GamingCleanupWorkflowStatus.ACKNOWLEDGEMENT_PENDING.persistedMessage,
                    ),
                ),
            ),
        )
        assertEquals("PS5 Station 1 cleanup receipt is pending", pending.title)
        assertEquals(
            GamingCleanupWorkflowStatus.ACKNOWLEDGEMENT_PENDING.persistedMessage,
            pending.detail,
        )
        assertFalse(pending.requiresOwnerReview)

        val mismatch = gamingCleanupAcknowledgementBannerContent(
            GamingUiState(
                cleanupAcknowledgementsPending = listOf(
                    GamingCleanupAcknowledgementUi(
                        stationId = station.id,
                        detail = GamingCleanupWorkflowStatus.ACKNOWLEDGEMENT_REVIEW_REQUIRED.persistedMessage,
                    ),
                ),
            ),
        )
        assertEquals("Cleanup acknowledgement needs owner review", mismatch.title)
        assertTrue(mismatch.requiresOwnerReview)
    }

    @Test
    fun `command attention count retains every independent operational item`() {
        assertEquals(
            7,
            gamingCommandAttentionCount(
                canManageSessions = false,
                terminalBlocked = true,
                focusRequested = false,
                hasRefreshError = false,
                orphanedExtensionCount = 2,
                rejectedSessionCount = 0,
                posLinkedReviewCount = 2,
                needsCancellation = true,
                awaitingPayment = false,
                busy = false,
            ),
        )
        assertEquals(
            0,
            gamingCommandAttentionCount(
                canManageSessions = true,
                terminalBlocked = false,
                focusRequested = false,
                hasRefreshError = false,
                orphanedExtensionCount = 0,
                rejectedSessionCount = 0,
                posLinkedReviewCount = 0,
                needsCancellation = false,
                awaitingPayment = false,
                busy = false,
            ),
        )
    }

    @Test
    fun `busy save progress never changes the global attention count`() {
        val idleCount = gamingCommandAttentionCount(
            canManageSessions = true,
            terminalBlocked = false,
            focusRequested = false,
            hasRefreshError = false,
            orphanedExtensionCount = 0,
            rejectedSessionCount = 0,
            posLinkedReviewCount = 0,
            needsCancellation = false,
            awaitingPayment = false,
            busy = false,
        )
        val busyCount = gamingCommandAttentionCount(
            canManageSessions = true,
            terminalBlocked = false,
            focusRequested = false,
            hasRefreshError = false,
            orphanedExtensionCount = 0,
            rejectedSessionCount = 0,
            posLinkedReviewCount = 0,
            needsCancellation = false,
            awaitingPayment = false,
            busy = true,
        )

        assertEquals(0, idleCount)
        assertEquals(idleCount, busyCount)
    }

    @Test
    fun `active session stays active before its authoritative timer end`() {
        val result = stationPresentation(
            station,
            session(status = "active", timerEndsAt = "2026-08-26T18:30:00Z"),
            now,
        )

        assertEquals(StationVisualState.Active, result.state)
        assertEquals("Active", result.statusLabel)
    }

    @Test
    fun `pending local start is operational ticking work that can capture a stop`() {
        val pending = session(
            status = "starting",
            localState = GamingSessionState.START_PENDING,
            timerEndsAt = "2026-08-26T18:30:00Z",
            amountMinor = 18_000,
            packageId = "base-60",
            timerMinutes = 60,
        )
        val result = stationPresentation(station, pending, now)

        assertEquals(StationVisualState.Starting, result.state)
        assertEquals("Pending sync", result.statusLabel)
        assertTrue(pending.canRequestStop())
        assertTrue(hasTickingGamingSession(listOf(pending)))
        assertEquals(1, operationalActiveGamingSessionCount(listOf(pending)))
        assertEquals(18_000L, estimatedCurrentAmountMinor(pending, now))
        assertEquals(
            Instant.parse("2026-08-26T18:00:00Z").toEpochMilli(),
            calculateCapturedTimerEndsAtMillis(
                Instant.parse("2026-08-26T17:00:00Z").toEpochMilli(),
                60,
            ),
        )
    }

    @Test
    fun `offline pending shift blocks gaming start until its server id exists`() {
        assertEquals(
            "Shift is saved offline. Reconnect and let it confirm before starting Gaming.",
            gamingStartShiftBlockMessage("local-shift", activeShiftServerConfirmed = false),
        )
        assertEquals(null, gamingStartShiftBlockMessage("server-shift", activeShiftServerConfirmed = true))
    }

    @Test
    fun `pre-upgrade package start quarantine cannot use ordinary retry or discard flow`() {
        val quarantined = session(
            status = "start_failed",
            localState = GamingSessionState.START_REJECTED,
            lastError = LEGACY_PACKAGE_START_REVIEW_ERROR,
            packageId = "base-legacy",
        )

        assertTrue(quarantined.requiresProtectedStartReview())
        assertTrue(quarantined.canResolveRejectedStart())
        assertEquals("Owner review", stationPresentation(station, quarantined, now).statusLabel)
    }

    @Test
    fun `rejected start with captured stop can only use protected evidence recovery`() {
        val played = session(
            status = "start_failed",
            localState = GamingSessionState.START_REJECTED,
            lastError = "Shift closed before the saved start reached the server",
            endAt = "2026-08-26T17:45:00Z",
        )

        assertTrue(played.requiresProtectedStartReview())
        assertTrue(played.canResolveRejectedStart())
        assertEquals("Owner review", stationPresentation(station, played, now).statusLabel)
    }

    @Test
    fun `legacy resolution requires explicit compatible decision and verified order id`() {
        assertEquals(
            "Enter the valid POS order ID that records the manual bill.",
            legacyResolutionInputError(
                GamingLegacyResolution.MANUAL_BILL_RECORDED,
                "not-an-order-id",
                "Verified receipt",
            ),
        )
        assertEquals(
            null,
            legacyResolutionInputError(
                GamingLegacyResolution.MANUAL_BILL_RECORDED,
                "44444444-4444-4444-8444-444444444444",
                "Verified receipt",
            ),
        )
        assertEquals(
            "Confirmed no play cannot be linked to a POS order.",
            legacyResolutionInputError(
                GamingLegacyResolution.CONFIRMED_NO_PLAY,
                "44444444-4444-4444-8444-444444444444",
                "Verified no play",
            ),
        )
        assertEquals(
            null,
            legacyResolutionInputError(
                GamingLegacyResolution.CONFIRMED_NO_PLAY,
                null,
                "Verified no play",
            ),
        )
        assertEquals(
            "Recover accepted server start cannot be linked to a POS order.",
            legacyResolutionInputError(
                GamingLegacyResolution.SERVER_SESSION_RECOVERED,
                "44444444-4444-4444-8444-444444444444",
                "Recover the exact accepted Start",
            ),
        )
        assertEquals(
            null,
            legacyResolutionInputError(
                GamingLegacyResolution.SERVER_SESSION_RECOVERED,
                null,
                "Recover the exact accepted Start",
            ),
        )
    }

    @Test
    fun `active session becomes overtime only after its authoritative timer end`() {
        val result = stationPresentation(
            station,
            session(status = "active", timerEndsAt = "2026-08-26T17:59:59Z"),
            now,
        )

        assertEquals(StationVisualState.Overtime, result.state)
        assertEquals("Overtime", result.statusLabel)
    }

    @Test
    fun `expired workspace feedback says what remained unchanged and how to recover`() {
        val startMessage = gamingStartWorkspaceUnavailableMessage("PS5 Station 2")

        assertTrue(startMessage.contains("PS5 Station 2"))
        assertTrue(startMessage.contains("Sign in online again"))
        assertTrue(startMessage.contains("No session was started"))
        assertTrue(GAMING_STOP_WORKSPACE_UNAVAILABLE_MESSAGE.contains("Sign in online again"))
        assertTrue(GAMING_STOP_WORKSPACE_UNAVAILABLE_MESSAGE.contains("still running"))
        assertTrue(GAMING_STOP_WORKSPACE_UNAVAILABLE_MESSAGE.contains("time continues"))
    }

    @Test
    fun `structural clock projection keeps active state stable but observes overtime boundary`() {
        val running = session(status = "active", timerEndsAt = "2026-08-26T18:00:00Z")
        val clock = mutableLongStateOf(now - 1_000)
        val presentation = derivedStateOf(structuralEqualityPolicy()) {
            stationPresentation(station, running, clock.longValue)
        }
        val beforeBoundary = presentation.value
        val elapsedBefore = elapsedMillis(running, clock.longValue)

        clock.longValue = now
        assertEquals(beforeBoundary, presentation.value)
        assertEquals(StationVisualState.Active, presentation.value.state)
        assertEquals(elapsedBefore + 1_000, elapsedMillis(running, clock.longValue))

        clock.longValue = now + 1
        assertEquals(StationVisualState.Overtime, presentation.value.state)
        val overtime = presentation.value
        clock.longValue = now + 60_000
        assertEquals(overtime, presentation.value)
        assertEquals(61 * 60_000L, elapsedMillis(running, clock.longValue))
    }

    @Test
    fun `ended billable session is payment due until POS accepts it`() {
        val result = stationPresentation(
            station,
            session(status = "ended", amountMinor = 15_750, billableMinutes = 63),
            now,
        )

        assertEquals(StationVisualState.PaymentDue, result.state)
        assertEquals("Payment due", result.statusLabel)
    }

    @Test
    fun `zero value ended session requires an audited cancellation`() {
        val result = stationPresentation(
            station,
            session(status = "ended", amountMinor = 0),
            now,
        )

        assertEquals(StationVisualState.CancellationRequired, result.state)
        assertEquals("Needs review", result.statusLabel)
    }

    @Test
    fun `zero value ended session with an active item remains payable through POS`() {
        val result = stationPresentation(
            station,
            session(status = "ended", amountMinor = 0),
            now,
            hasActiveAddons = true,
        )

        assertEquals(StationVisualState.PaymentDue, result.state)
        assertEquals("Payment due", result.statusLabel)
    }

    @Test
    fun `missing ended amount fails closed instead of becoming a zero value cancellation`() {
        val result = stationPresentation(
            station,
            session(status = "ended", amountMinor = null),
            now,
        )

        assertEquals(StationVisualState.BillingMissing, result.state)
        assertEquals("Billing missing", result.statusLabel)
    }

    @Test
    fun `legacy ambiguous billing never falls back to hourly semantics and is labelled`() {
        val legacy = session(
            status = "ended",
            amountMinor = 15_750,
            billingMode = "legacy_ambiguous",
        )

        assertTrue(legacy.isPackageBilling())
        assertTrue(legacy.hasUnverifiedLegacyBillingMode())
        assertEquals(
            "Older session · billing mode unverified. The server amount is retained and POS excludes package benefits.",
            unbilledSessionDetail(StationVisualState.PaymentDue, legacy),
        )
    }

    @Test
    fun `local POS handoff states remain visible and distinct`() {
        val pending = stationPresentation(
            station,
            session(status = "ended", amountMinor = 15_750, localState = "send_pending"),
            now,
        )
        val rejected = stationPresentation(
            station,
            session(status = "ended", amountMinor = 15_750, localState = "send_rejected"),
            now,
        )

        assertEquals(StationVisualState.SendPending, pending.state)
        assertEquals(StationVisualState.SendRejected, rejected.state)
    }

    @Test
    fun `rejected stop remains visible with an explicit retry state`() {
        val result = stationPresentation(
            station,
            session(status = "active", localState = "stop_rejected"),
            now,
        )

        assertEquals(StationVisualState.StopFailed, result.state)
        assertEquals("Stop failed", result.statusLabel)
    }

    @Test
    fun `rejected POS handoff explains the actual cause before retry`() {
        val rejected = session(
            status = "ended",
            amountMinor = 15_750,
            localState = "send_rejected",
            lastError = "Open a POS shift on Main Terminal, then retry.",
        )

        assertEquals(
            "Open a POS shift on Main Terminal, then retry.",
            unbilledSessionDetail(StationVisualState.SendRejected, rejected),
        )
        assertEquals(
            "POS refused the handoff. Check the shift and connection, then retry.",
            unbilledSessionDetail(
                StationVisualState.SendRejected,
                rejected.copy(lastError = "  "),
            ),
        )
    }

    @Test
    fun `disabled station never hides a session that still needs operational action`() {
        val disabledStation = station.copy(isActive = false)

        assertEquals(
            StationVisualState.Active,
            stationPresentation(disabledStation, session(status = "active"), now).state,
        )
        assertEquals(
            StationVisualState.PaymentDue,
            stationPresentation(
                disabledStation,
                session(status = "ended", amountMinor = 15_750, billableMinutes = 63),
                now,
            ).state,
        )
    }

    @Test
    fun `customer identity remains available on active cards and payment queue rows`() {
        assertEquals(
            "Asha · 9876543210",
            sessionCustomerLabel(
                session(status = "active", customerName = " Asha ", customerPhone = " 9876543210 "),
            ),
        )
        assertEquals(
            "9876543210",
            sessionCustomerLabel(session(status = "active", customerPhone = "9876543210")),
        )
        assertEquals(null, sessionCustomerLabel(session(status = "active")))
    }

    @Test
    fun `disabled and available stations never expose active actions`() {
        assertEquals(
            StationVisualState.Disabled,
            stationPresentation(station.copy(isActive = false), null, now).state,
        )
        assertEquals(
            StationVisualState.Available,
            stationPresentation(station, null, now).state,
        )
    }

    @Test
    fun `running estimate mirrors backend whole-minute and minor-unit ceilings`() {
        val oneSecond = Instant.parse("2026-08-26T17:00:01Z").toEpochMilli()
        val sixtyMinutesAndOneSecond = Instant.parse("2026-08-26T18:00:01Z").toEpochMilli()
        val hourly = session(status = "active", ratePerHourMinor = 15_000)

        assertEquals(250L, estimatedCurrentAmountMinor(hourly, oneSecond))
        assertEquals(15_250L, estimatedCurrentAmountMinor(hourly, sixtyMinutesAndOneSecond))
    }

    @Test
    fun `running estimate uses locked session rate not edited station rate`() {
        val locked = session(status = "active", ratePerHourMinor = 12_000)

        assertEquals(12_000L, estimatedCurrentAmountMinor(locked, now))
        assertEquals(15_000L, station.ratePerHourMinor)
    }

    @Test
    fun `package amount stays fixed and stopping estimate uses captured tap time`() {
        val packaged = session(
            status = "active",
            packageId = "package-1",
            amountMinor = 18_000,
            ratePerHourMinor = 99_999,
        )
        val stopping = session(
            status = "stopping",
            endAt = "2026-08-26T17:30:00Z",
            ratePerHourMinor = 15_000,
        )

        assertEquals(18_000L, estimatedCurrentAmountMinor(packaged, now))
        assertEquals(7_500L, estimatedCurrentAmountMinor(stopping, now))
        assertEquals(null, estimatedCurrentAmountMinor(session(status = "paused", ratePerHourMinor = 15_000), now))
    }

    @Test
    fun `active package item subtotal requires known base and excludes nonbillable item snapshots`() {
        listOf(
            StationVisualState.Starting,
            StationVisualState.Active,
            StationVisualState.Overtime,
            StationVisualState.Paused,
            StationVisualState.Stopping,
            StationVisualState.StopFailed,
        ).forEach { state ->
            assertEquals(
                "$state must retain the provided package-and-items subtotal",
                13_000L,
                gamingActivePackageItemsSubtotalMinor(
                    state,
                    packageBilling = true,
                    currentAmountMinor = 8_000L,
                    combinedBillSnapshotMinor = 13_000L,
                ),
            )
        }
        assertNull(
            gamingActivePackageItemsSubtotalMinor(
                StationVisualState.Active,
                packageBilling = true,
                currentAmountMinor = null,
                combinedBillSnapshotMinor = 5_000L,
            ),
        )
        assertNull(
            gamingActivePackageItemsSubtotalMinor(
                StationVisualState.PaymentDue,
                packageBilling = true,
                currentAmountMinor = 8_000L,
                combinedBillSnapshotMinor = 13_000L,
            ),
        )
        assertNull(
            gamingActivePackageItemsSubtotalMinor(
                StationVisualState.Active,
                packageBilling = false,
                currentAmountMinor = 8_000L,
                combinedBillSnapshotMinor = 13_000L,
            ),
        )
        // Voided and definitively rejected Adds are already excluded by the
        // provided checked snapshot, so no extra subtotal is presented.
        assertNull(
            gamingActivePackageItemsSubtotalMinor(
                StationVisualState.Active,
                packageBilling = true,
                currentAmountMinor = 8_000L,
                combinedBillSnapshotMinor = 8_000L,
            ),
        )
        // A rejected Void remains billable under the existing add-on policy.
        assertEquals(
            13_000L,
            gamingActivePackageItemsSubtotalMinor(
                StationVisualState.Active,
                packageBilling = true,
                currentAmountMinor = 8_000L,
                combinedBillSnapshotMinor = 13_000L,
            ),
        )
    }

    @Test
    fun `stopping elapsed clock freezes at the captured stop timestamp`() {
        val stopping = session(
            status = "stopping",
            endAt = "2026-08-26T17:30:00Z",
            ratePerHourMinor = 15_000,
        )

        assertEquals(30 * 60_000L, elapsedMillis(stopping, now))
        assertFalse(hasTickingGamingSession(listOf(stopping)))
        assertTrue(hasTickingGamingSession(listOf(stopping, session(status = "active"))))
    }

    @Test
    fun `controller surcharge matches the fixed started-hour rule`() {
        assertEquals(3_000L, extraControllerSurchargeMinor(extraControllers = 1, durationMinutes = 15))
        assertEquals(3_000L, extraControllerSurchargeMinor(extraControllers = 1, durationMinutes = 60))
        assertEquals(6_000L, extraControllerSurchargeMinor(extraControllers = 1, durationMinutes = 61))
        assertEquals(6_000L, extraControllerSurchargeMinor(extraControllers = 1, durationMinutes = 90))
        assertEquals(6_000L, extraControllerSurchargeMinor(extraControllers = 1, durationMinutes = 120))
        assertEquals(9_000L, extraControllerSurchargeMinor(extraControllers = 1, durationMinutes = 121))
        assertEquals(12_000L, extraControllerSurchargeMinor(extraControllers = 2, durationMinutes = 90))
        assertEquals(0L, extraControllerSurchargeMinor(extraControllers = 0, durationMinutes = 90))
    }

    @Test
    fun `package selection label exposes immutable tier mode and player count`() {
        assertEquals(
            "Single",
            gamingPackageSelectionLabel(
                session(
                    status = "active",
                    billingMode = "package",
                    packageVariantSnapshot = "single",
                    packagePricingTierSnapshot = "standard",
                ),
            ),
        )
        assertEquals(
            "Premium · 4 players",
            gamingPackageSelectionLabel(
                session(
                    status = "active",
                    billingMode = "package",
                    packageVariantSnapshot = "dual",
                    packagePricingTierSnapshot = "premium",
                    extraControllers = 2,
                ),
            ),
        )
        assertTrue(requiresCanonicalGamingTariff("ps5"))
        assertTrue(requiresCanonicalGamingTariff("racing simulator"))
        assertTrue(requiresCanonicalGamingTariff("vr"))
        assertEquals("Racing Sim", gamingModeLabel("simdrive"))
        assertEquals("VR Racing Sim", gamingModeLabel("vr_racing"))
        assertEquals("VR Games", gamingModeLabel("vr_games"))
    }

    @Test
    fun `controller extension charges only the new cumulative hour boundary`() {
        assertEquals(
            0L,
            extraControllerExtensionSurchargeMinor(
                extraControllers = 1,
                currentDurationMinutes = 30,
                extensionMinutes = 30,
            ),
        )
        assertEquals(
            3_000L,
            extraControllerExtensionSurchargeMinor(
                extraControllers = 1,
                currentDurationMinutes = 60,
                extensionMinutes = 30,
            ),
        )
        assertEquals(
            0L,
            extraControllerExtensionSurchargeMinor(
                extraControllers = 1,
                currentDurationMinutes = 90,
                extensionMinutes = 30,
            ),
        )
        assertEquals(
            3_000L,
            extraControllerExtensionSurchargeMinor(
                extraControllers = 1,
                currentDurationMinutes = 120,
                extensionMinutes = 30,
            ),
        )
    }

    @Test
    fun `paid extensions match both station type and base package variant`() {
        val base = gamingPackage(id = "base-dual", kind = "base", variant = "dual")
        val matching = gamingPackage(id = "extension-dual", kind = "extension", variant = "dual")
        val wrongTier = gamingPackage(
            id = "extension-premium-dual",
            kind = "extension",
            variant = "dual",
            pricingTier = "premium",
        )
        val wrongVariant = gamingPackage(id = "extension-single", kind = "extension", variant = "single")
        val wrongStation = gamingPackage(
            id = "extension-vr",
            kind = "extension",
            variant = "dual",
            stationType = "vr",
        )

        assertEquals(
            listOf("extension-dual"),
            matchingPackageExtensions(
                session(
                    status = "active",
                    packageId = base.id,
                    billingMode = "package",
                    packagePriceMinorSnapshot = 15_000,
                    packageDurationMinutesSnapshot = 60,
                    packageVariantSnapshot = "dual",
                    packageStationTypeSnapshot = "ps5",
                ),
                station,
                listOf(base, matching, wrongTier, wrongVariant, wrongStation),
            ).map(GamingPackage::id),
        )

        // Code22 snapshots the tier independently. Even if the mutable base
        // package is later retired, Standard must never expose Premium's
        // different extension price (or vice versa).
        assertEquals(
            listOf("extension-dual"),
            matchingPackageExtensions(
                session(
                    status = "active",
                    packageId = null,
                    billingMode = "package",
                    packagePriceMinorSnapshot = 15_000,
                    packageDurationMinutesSnapshot = 60,
                    packageVariantSnapshot = "dual",
                    packageStationTypeSnapshot = "ps5",
                    packagePricingTierSnapshot = "standard",
                ),
                station,
                listOf(matching, wrongTier),
            ).map(GamingPackage::id),
        )

        // The base item may be retired or deleted after Start. Eligibility is
        // derived from the immutable session snapshot, not the live base list.
        assertEquals(
            listOf("extension-dual"),
            matchingPackageExtensions(
                session(
                    status = "active",
                    packageId = null,
                    billingMode = "package",
                    packagePriceMinorSnapshot = 15_000,
                    packageDurationMinutesSnapshot = 60,
                    packageVariantSnapshot = "dual",
                    packageStationTypeSnapshot = "ps5",
                ),
                station,
                listOf(matching, wrongVariant, wrongStation),
            ).map(GamingPackage::id),
        )
    }

    @Test
    fun `legacy package session without locked timer or total cannot be extended`() {
        assertFalse(
            session(status = "active", packageId = "base-dual", amountMinor = null)
                .hasLockedPackageExtensionSnapshot(),
        )
        assertFalse(
            session(status = "active", packageId = "base-dual", amountMinor = 20_000)
                .hasLockedPackageExtensionSnapshot(),
        )
        assertTrue(
            session(
                status = "active",
                packageId = "base-dual",
                amountMinor = 20_000,
                timerMinutes = 60,
                billingMode = "package",
                packagePriceMinorSnapshot = 15_000,
                packageDurationMinutesSnapshot = 60,
                packageVariantSnapshot = "dual",
                packageStationTypeSnapshot = "ps5",
            ).hasLockedPackageExtensionSnapshot(),
        )
    }

    @Test
    fun `terminal-bound authority fails closed when shift ownership is absent or different`() {
        assertEquals(
            GamingSessionAuthority.CURRENT_SHIFT,
            session(status = "active", shiftId = "shift-1").authority("shift-1"),
        )
        assertEquals(
            GamingSessionAuthority.OTHER_SHIFT,
            session(status = "active", shiftId = "shift-2").authority("shift-1"),
        )
        assertEquals(
            GamingSessionAuthority.NO_OPEN_SHIFT,
            session(status = "active", shiftId = "shift-1").authority(null),
        )
        assertEquals(
            GamingSessionAuthority.UNKNOWN,
            session(status = "active", shiftId = null).authority("shift-1"),
        )
    }

    @Test
    fun `legacy missing shift stop uses only a server confirmed current terminal shift`() {
        val exact = session(status = "active", shiftId = "shift-current")
        assertEquals(
            "shift-current",
            exact.resolvedStopShiftId(
                activeShiftId = "shift-current",
                activeShiftServerConfirmed = false,
                online = false,
            ),
        )

        // localState=null identifies a row pulled from the server cache. The
        // synced/rejected states cover pre-fix local rows whose old response
        // erased the shift. All are server-known, but the compatibility
        // fallback is safe only after this terminal's open shift has also been
        // confirmed by the server.
        val oldServerPayload = session(status = "active", shiftId = null, localState = null)
        for (serverKnownState in listOf(
            null,
            GamingSessionState.START_SYNCED,
            GamingSessionState.STOP_REJECTED,
        )) {
            assertEquals(
                "shift-current",
                session(
                    status = "active",
                    shiftId = null,
                    localState = serverKnownState,
                ).resolvedStopShiftId(
                    activeShiftId = "shift-current",
                    activeShiftServerConfirmed = true,
                    online = true,
                ),
            )
        }
        assertNull(
            oldServerPayload.resolvedStopShiftId(
                activeShiftId = "shift-current",
                activeShiftServerConfirmed = false,
                online = true,
            ),
        )
        assertNull(
            oldServerPayload.resolvedStopShiftId(
                activeShiftId = "shift-current",
                activeShiftServerConfirmed = true,
                online = false,
            ),
        )

        // A known different source shift is never reclassified as current,
        // and an unsynced local Start cannot pretend to be server-known.
        assertNull(
            session(status = "active", shiftId = "shift-other")
                .resolvedStopShiftId(
                    "shift-current",
                    activeShiftServerConfirmed = true,
                    online = true,
                ),
        )
        assertNull(
            session(
                status = "starting",
                shiftId = null,
                localState = GamingSessionState.START_PENDING,
            ).resolvedStopShiftId(
                "shift-current",
                activeShiftServerConfirmed = true,
                online = true,
            ),
        )
        assertNull(
            oldServerPayload.resolvedStopShiftId(
                activeShiftId = null,
                activeShiftServerConfirmed = true,
                online = true,
            ),
        )
    }

    private fun session(
        status: String,
        timerEndsAt: String? = null,
        amountMinor: Long? = null,
        billableMinutes: Int? = null,
        localState: String? = null,
        lastError: String? = null,
        customerName: String? = null,
        customerPhone: String? = null,
        shiftId: String? = null,
        ratePerHourMinor: Long? = null,
        packageId: String? = null,
        timerMinutes: Int? = null,
        endAt: String? = null,
        billingMode: String? = null,
        packagePriceMinorSnapshot: Long? = null,
        packageDurationMinutesSnapshot: Int? = null,
        packageVariantSnapshot: String? = null,
        packageStationTypeSnapshot: String? = null,
        packagePricingTierSnapshot: String? = null,
        extraControllers: Int = 0,
    ) = GameSession(
        id = "session-1",
        stationId = station.id,
        shiftId = shiftId,
        status = status,
        startAt = "2026-08-26T17:00:00Z",
        endAt = endAt,
        timerEndsAt = timerEndsAt,
        amountMinor = amountMinor,
        ratePerHourMinor = ratePerHourMinor,
        packageId = packageId,
        billingMode = billingMode,
        packagePriceMinorSnapshot = packagePriceMinorSnapshot,
        packageDurationMinutesSnapshot = packageDurationMinutesSnapshot,
        packageVariantSnapshot = packageVariantSnapshot,
        packageStationTypeSnapshot = packageStationTypeSnapshot,
        packagePricingTierSnapshot = packagePricingTierSnapshot,
        extraControllers = extraControllers,
        timerMinutes = timerMinutes,
        billableMinutes = billableMinutes,
        localState = localState,
        lastError = lastError,
        customerName = customerName,
        customerPhone = customerPhone,
    )

    private fun contrastRatio(first: Color, second: Color): Double {
        val lighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun linearize(component: Float): Double {
            val value = component.toDouble()
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }

    private fun gamingPackage(
        id: String,
        kind: String,
        variant: String,
        stationType: String = station.type,
        pricingTier: String = "standard",
    ) = GamingPackage(
        id = id,
        stationType = stationType,
        pricingTier = pricingTier,
        variant = variant,
        kind = kind,
        name = id,
        durationMinutes = 30,
        priceMinor = 5_000,
    )
}
