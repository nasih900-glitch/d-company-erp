package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingLegacyResolution
import cloud.dcompany.erp.core.db.LEGACY_PACKAGE_START_REVIEW_ERROR
import cloud.dcompany.erp.ui.components.VOID_REASON_OTHER_ID
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
            "₹350.00/hour",
            availableStationPricingDescription(
                station.copy(type = "vr", ratePerHourMinor = 35_000),
                packages,
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
    fun `operational dialogs reserve footer space on compact tablet height`() {
        assertEquals(330.dp, gamingDialogBodyMaxHeight(screenHeightDp = 600))
        assertEquals(440.dp, gamingDialogBodyMaxHeight(screenHeightDp = 800))
        assertEquals(440.dp, gamingDialogBodyMaxHeight(screenHeightDp = 1_200))
    }

    @Test
    fun `custom void reason always keeps its actions in a bounded IME safe body`() {
        assertTrue(useCompactVoidCustomLayout(VOID_REASON_OTHER_ID))
        assertFalse(useCompactVoidCustomLayout("guest_changed_mind"))
        assertFalse(useCompactVoidCustomLayout(null))

        assertEquals(120.dp, voidDialogBodyMaxHeight(600, VOID_REASON_OTHER_ID))
        assertEquals(120.dp, voidDialogBodyMaxHeight(800, VOID_REASON_OTHER_ID))
        assertEquals(120.dp, voidDialogBodyMaxHeight(1_200, VOID_REASON_OTHER_ID))
        assertEquals(330.dp, voidDialogBodyMaxHeight(600, "guest_changed_mind"))
        assertEquals(330.dp, voidDialogBodyMaxHeight(600, null))
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
    fun `command attention count retains every independent operational item`() {
        assertEquals(
            5,
            gamingCommandAttentionCount(
                canManageSessions = false,
                terminalBlocked = true,
                focusRequested = false,
                hasRefreshError = false,
                orphanedExtensionCount = 2,
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
        assertEquals(6_000L, extraControllerSurchargeMinor(extraControllers = 1, durationMinutes = 90))
        assertEquals(12_000L, extraControllerSurchargeMinor(extraControllers = 2, durationMinutes = 90))
        assertEquals(0L, extraControllerSurchargeMinor(extraControllers = 0, durationMinutes = 90))
    }

    @Test
    fun `package selection label exposes immutable tier mode and player count`() {
        assertEquals(
            "Standard · Single",
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
        assertFalse(requiresCanonicalGamingTariff("vr"))
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
