package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.db.GamingPackageExtensionState
import cloud.dcompany.erp.core.db.GamingSessionActionState
import cloud.dcompany.erp.core.db.GamingSessionActionType
import cloud.dcompany.erp.core.db.LocalGamingSessionActionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GamingDurationDialHitTest {
    private val dial = IntSize(1000, 1000)

    @Test
    fun publishedDurationsUseClockPositionsAndNeverShareAnEndpoint() {
        val stops = durationDialStopProgress(listOf(30, 60))
        assertEquals(listOf(180f, 360f), stops)
        assertEquals(90f, DURATION_DIAL_START + stops[0], 0.001f)
        assertEquals(270f, DURATION_DIAL_START + stops[1], 0.001f)
        assertEquals(listOf(90f, 180f), durationDialStopProgress(listOf(15, 30)))
        assertEquals(listOf(60f, 120f), durationDialStopProgress(listOf(10, 20)))
        assertEquals(2, durationDialStopProgress(listOf(60, 120)).distinct().size)
    }

    @Test
    fun tapsAwayFromTheRingCannotChangeTheSelectedPackage() {
        assertNull(durationDialAngleOnRing(Offset(500f, 500f), dial))
        assertNull(durationDialAngleOnRing(Offset(-10f, 300f), dial))
        assertEquals(90f, durationDialAngleOnRing(Offset(500f, 900f), dial)!!, 0.001f)
        assertEquals(270f, durationDialAngleOnRing(Offset(500f, 100f), dial)!!, 0.001f)
    }

    @Test
    fun clockwiseDragFromBottomReachesHourAndReverseReturnsToThirty() {
        val stops = durationDialStopProgress(listOf(30, 60))
        var progress = 180f
        for (angle in 105..270 step 15) progress = durationDialDragProgress(progress, angle - 15f, angle.toFloat())
        assertEquals(360f, progress, 0.001f)
        assertEquals(1, durationDialIndexAtProgress(progress, stops))
        for (angle in 255 downTo 90 step 15) progress = durationDialDragProgress(progress, angle + 15f, angle.toFloat())
        assertEquals(180f, progress, 0.001f)
        assertEquals(0, durationDialIndexAtProgress(progress, stops))
    }

    @Test
    fun draggingThroughZeroPreservesDirectionAndClampsAtBothEnds() {
        assertEquals(226f, durationDialDragProgress(224f, 359f, 1f), 0.001f)
        assertEquals(224f, durationDialDragProgress(226f, 1f, 359f), 0.001f)
        assertEquals(360f, durationDialDragProgress(359f, 269f, 273f), 0.001f)
        assertEquals(0f, durationDialDragProgress(1f, 271f, 267f), 0.001f)
        assertEquals(358f, durationDialDragProgress(360f, 270f, 268f), 0.001f)
    }

    @Test
    fun hourHandleCannotWrapToMinimumFromSmallReleaseJitter() {
        assertEquals(360f, durationDialProgressAtAngle(270f, 360f), 0.001f)
        assertEquals(360f, durationDialProgressAtAngle(269f, 360f), 0.001f)
        assertEquals(180f, durationDialProgressAtAngle(90f, 180f), 0.001f)
    }

    @Test
    fun packageBoundaryHasToleranceRatherThanRepeatedPriceSwitches() {
        val stops = durationDialStopProgress(listOf(30, 60))
        assertEquals(0, durationDialIndexAtProgress(273f, stops, previousIndex = 0))
        assertEquals(1, durationDialIndexAtProgress(276f, stops, previousIndex = 0))
        assertEquals(1, durationDialIndexAtProgress(267f, stops, previousIndex = 1))
        assertEquals(0, durationDialIndexAtProgress(264f, stops, previousIndex = 1))
        assertEquals(0, durationDialIndexAtProgress(360f, listOf(180f), previousIndex = 0))
    }

    @Test
    fun paidExtensionReviewRejectsPriceSessionStatusPendingAndAuthorityDrift() {
        val station = Station("station-1", "PS5-1", "PS5 1", "ps5", 12_000)
        val extension = GamingPackage(
            id = "extend-30", code = "single-extension-30m", stationType = "ps5",
            variant = "single", kind = "extension", name = "Add 30 minutes",
            durationMinutes = 30, priceMinor = 6_000,
        )
        val session = lockedPackageSession()
        val review = PackageExtensionReview(station, session, extension)
        val current = GamingUiState(
            stations = listOf(station), packages = listOf(extension), sessions = listOf(session),
            activeShiftId = "shift-1", busyStationId = null,
        )
        val write = GamingAccess(canManageSessions = true)

        assertNull(packageExtensionReviewError(review, current, write))
        assertNotNull(packageExtensionReviewError(
            review,
            current.copy(packages = listOf(extension.copy(priceMinor = 7_000))),
            write,
        ))
        assertNotNull(packageExtensionReviewError(review, current.copy(packages = emptyList()), write))
        assertNotNull(packageExtensionReviewError(
            review,
            current.copy(sessions = listOf(session.copy(amountMinor = 13_000))),
            write,
        ))
        assertNotNull(packageExtensionReviewError(
            review,
            current.copy(sessions = listOf(session.copy(status = "ended"))),
            write,
        ))
        assertNotNull(packageExtensionReviewError(
            review,
            current.copy(sessions = listOf(session.copy(orderId = "order-1"))),
            write,
        ))
        assertNotNull(packageExtensionReviewError(
            review,
            current.copy(packageExtensionActions = listOf(PackageExtensionActionUi(
                actionId = "pending-extension", serverSessionId = session.id,
                shiftId = "shift-1", state = GamingPackageExtensionState.PENDING,
                lastError = null,
            ))),
            write,
        ))
        assertNotNull(packageExtensionReviewError(
            review,
            current.copy(activeShiftId = "shift-2"),
            write,
        ))
        val zeroPriced = extension.copy(priceMinor = 0L)
        assertEquals(
            "This extension has an invalid duration or price. Refresh Gaming before extending.",
            packageExtensionReviewError(
            review.copy(extension = zeroPriced),
            current.copy(packages = listOf(zeroPriced)),
            write,
            ),
        )
        listOf(
            extension.copy(durationMinutes = 0),
            extension.copy(durationMinutes = 1_441),
            extension.copy(priceMinor = -1L),
            extension.copy(priceMinor = Long.MAX_VALUE),
        ).forEach { invalid ->
            assertNotNull(packageExtensionReviewError(
                review.copy(extension = invalid),
                current.copy(packages = listOf(invalid)),
                write,
            ))
        }
    }

    @Test
    fun paidExtensionReviewRejectsAccessBusyRejectedActionAndDayCeiling() {
        val station = Station("station-1", "PS5-1", "PS5 1", "ps5", 12_000)
        val extension = GamingPackage(
            id = "extend-30", code = "single-extension-30m", stationType = "ps5",
            variant = "single", kind = "extension", name = "Add 30 minutes",
            durationMinutes = 30, priceMinor = 6_000,
        )
        val session = lockedPackageSession()
        val review = PackageExtensionReview(station, session, extension)
        val current = GamingUiState(
            stations = listOf(station), packages = listOf(extension), sessions = listOf(session),
            activeShiftId = "shift-1",
        )
        val write = GamingAccess(canManageSessions = true)

        assertNotNull(packageExtensionReviewError(review, current, GamingAccess()))
        assertNotNull(packageExtensionReviewError(
            review,
            current.copy(busyStationId = "station-2"),
            write,
        ))
        assertNotNull(packageExtensionReviewError(
            review,
            current.copy(sessionActions = listOf(LocalGamingSessionActionEntity(
                actionId = "rejected-action", sessionKey = session.id,
                actionType = GamingSessionActionType.EXTEND, serverSessionId = session.id,
                shiftId = "shift-1", occurredAtMillis = 1L,
                state = GamingSessionActionState.REJECTED,
            ))),
            write,
        ))
        val nearLimit = session.copy(timerMinutes = 1_420)
        assertNotNull(packageExtensionReviewError(
            review.copy(session = nearLimit),
            current.copy(sessions = listOf(nearLimit)),
            write,
        ))
    }

    private fun lockedPackageSession() = GameSession(
        id = "session-1", stationId = "station-1", shiftId = "shift-1",
        status = "active", startAt = "2026-09-30T10:00:00Z",
        timerMinutes = 60, amountMinor = 12_000, billingMode = "package",
        packageId = "base-60", packagePriceMinorSnapshot = 12_000,
        packageDurationMinutesSnapshot = 60, packageVariantSnapshot = "single",
        packageStationTypeSnapshot = "ps5", packagePricingTierSnapshot = "standard",
        effectivePackageId = "base-60", effectivePackagePriceMinor = 12_000,
        effectivePackageDurationMinutes = 60, effectivePackageVariant = "single",
        effectivePackageStationType = "ps5", effectivePackagePricingTier = "standard",
    )
}
