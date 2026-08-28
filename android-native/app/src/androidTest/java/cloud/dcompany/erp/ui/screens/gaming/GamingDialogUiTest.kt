package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingPackageExtensionState
import cloud.dcompany.erp.core.db.GamingLegacyResolution
import cloud.dcompany.erp.core.db.GamingLegacyResolutionAttemptState
import cloud.dcompany.erp.core.db.LEGACY_PACKAGE_START_REVIEW_ERROR
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Composable
private fun rememberedWallClock(epochMillis: Long): MutableLongState =
    remember(epochMillis) { mutableLongStateOf(epochMillis) }

/**
 * Exercises the actual gaming dialogs rather than isolated input primitives.
 *
 * The scroll calls are intentional regression coverage: if either dialog loses
 * its bounded scroll container, Compose cannot bring a keyboard-adjacent field
 * or action into view on compact tablet windows.
 */
class GamingDialogUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun activeCompactCardKeepsRunningAmountAndSessionDetailsVisible() {
        val start = Instant.parse("2026-08-26T17:00:00Z")
        val now = start.plusSeconds(60 * 60).toEpochMilli()
        val station = Station(
            id = "station-1",
            code = "PS5-1",
            name = "PS5 Station 1",
            type = "ps5",
            ratePerHourMinor = 20_000,
            isActive = true,
        )
        val session = GameSession(
            id = "session-1",
            stationId = station.id,
            shiftId = "shift-1",
            status = "active",
            startAt = start.toString(),
            timerMinutes = 90,
            ratePerHourMinor = 20_000,
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(now),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = true,
                        onStart = {},
                        onStop = {},
                        onSend = {},
                        onCancelUnbilled = {},
                        onExtendTimer = {},
                        onExtendPackage = { _, _ -> },
                        onTransfer = {},
                        onReconcile = {},
                        onRepairBilling = {},
                        onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText("01:00:00").assertIsDisplayed()
        compose.onNodeWithText("Estimated now · ₹200.00").assertIsDisplayed()
        compose.onNodeWithText("+30 min").assertIsDisplayed()
        compose.onNodeWithText("Transfer").assertIsDisplayed()
        compose.onNodeWithText("Stop & calculate").assertIsDisplayed()
    }

    @Test
    fun pendingOfflinePackageStartTicksAndCanCaptureStopWithoutEnablingConfirmedOnlyActions() {
        var stopRequested = false
        val station = testStation()
        val session = GameSession(
            id = "local-session-1",
            stationId = station.id,
            shiftId = "shift-1",
            status = "starting",
            startAt = "2026-08-26T17:00:00Z",
            timerMinutes = 60,
            timerEndsAt = "2026-08-26T18:00:00Z",
            amountMinor = 18_000,
            ratePerHourMinor = 15_000,
            packageId = "package-60",
            billingMode = "package",
            packagePriceMinorSnapshot = 15_000,
            packageDurationMinutesSnapshot = 60,
            packageVariantSnapshot = "solo",
            packageStationTypeSnapshot = "ps5",
            extraControllers = 1,
            localState = GamingSessionState.START_PENDING,
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T17:15:00Z").toEpochMilli(),
                        ),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = true,
                        onStart = {},
                        onStop = { stopRequested = true },
                        onSend = {},
                        onCancelUnbilled = {},
                        onExtendTimer = {},
                        onExtendPackage = { _, _ -> },
                        onTransfer = {},
                        onReconcile = {},
                        onRepairBilling = {},
                        onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText("PENDING SYNC").assertIsDisplayed()
        compose.onNodeWithText("00:15:00").assertIsDisplayed()
        compose.onNodeWithText("Package total · ₹180.00").assertIsDisplayed()
        compose.onAllNodesWithText("+30 min").assertCountEquals(0)
        compose.onAllNodesWithText("Transfer").assertCountEquals(0)
        compose.onNodeWithText("Stop & save end").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, stopRequested) }
    }

    @Test
    fun recoveredStopShowsAuthoritativeClockAndDisclosesRetainedChronologyAdjustment() {
        val station = testStation()
        val session = GameSession(
            id = "server-recovered-stop",
            stationId = station.id,
            shiftId = "server-shift-1",
            status = "stopping",
            startAt = "2026-08-26T17:30:00Z",
            endAt = "2026-08-26T17:30:00Z",
            timerMinutes = 60,
            amountMinor = 15_000,
            packageId = "retired-package",
            billingMode = "package",
            localState = GamingSessionState.STOP_PENDING,
            legacyOriginalCapturedStartAt = "2026-08-26T17:00:00Z",
            legacyOriginalCapturedStopAt = "2026-08-26T17:15:00Z",
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T18:00:00Z").toEpochMilli(),
                        ),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "server-shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = false,
                        onStart = {}, onStop = {}, onSend = {}, onCancelUnbilled = {},
                        onExtendTimer = {}, onExtendPackage = { _, _ -> }, onTransfer = {},
                        onReconcile = {}, onRepairBilling = {}, onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText("00:00:00").assertIsDisplayed()
        compose.onNodeWithText(
            "Original offline Stop is retained in the owner audit. " +
                "Replay was adjusted to the authoritative server Start time.",
        ).assertIsDisplayed()
    }

    @Test
    fun rejectedRecoveredStopKeepsChronologyAdjustmentVisibleAfterRestart() {
        val station = testStation()
        val session = GameSession(
            id = "server-recovered-stop-rejected",
            stationId = station.id,
            shiftId = "server-shift-1",
            status = "active",
            startAt = "2026-08-26T17:30:00Z",
            endAt = "2026-08-26T17:30:00Z",
            amountMinor = 15_000,
            billingMode = "package",
            localState = GamingSessionState.STOP_REJECTED,
            lastError = "Connection returned before Stop was confirmed.",
            legacyOriginalCapturedStartAt = "2026-08-26T17:00:00Z",
            legacyOriginalCapturedStopAt = "2026-08-26T17:15:00Z",
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T18:00:00Z").toEpochMilli(),
                        ),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "server-shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = false,
                        onStart = {}, onStop = {}, onSend = {}, onCancelUnbilled = {},
                        onExtendTimer = {}, onExtendPackage = { _, _ -> }, onTransfer = {},
                        onReconcile = {}, onRepairBilling = {}, onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText(
            "Original offline Stop remains in the owner audit",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("Retry stop").assertIsEnabled()
    }

    @Test
    fun recoveredOrderLinkedActivePackageLocksExtraChargesButStillAllowsStop() {
        val station = testStation()
        val session = GameSession(
            id = "server-paid-active",
            stationId = station.id,
            shiftId = "server-shift-1",
            status = "active",
            startAt = "2026-08-26T17:00:00Z",
            timerMinutes = 60,
            amountMinor = 15_000,
            packageId = "package-60",
            billingMode = "package",
            packagePriceMinorSnapshot = 15_000,
            packageDurationMinutesSnapshot = 60,
            packageVariantSnapshot = "solo",
            packageStationTypeSnapshot = "ps5",
            orderId = "paid-order-1",
            localState = GamingSessionState.START_SYNCED,
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T17:30:00Z").toEpochMilli(),
                        ),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "server-shift-1",
                        activeShiftServerConfirmed = true,
                        packages = listOf(
                            GamingPackage(
                                id = "extension-30",
                                stationType = "ps5",
                                variant = "solo",
                                kind = "extension",
                                name = "Solo +30 min",
                                durationMinutes = 30,
                                priceMinor = 7_500,
                            ),
                        ),
                        hasTransferTarget = true,
                        onStart = {}, onStop = {}, onSend = {}, onCancelUnbilled = {},
                        onExtendTimer = {}, onExtendPackage = { _, _ -> }, onTransfer = {},
                        onReconcile = {}, onRepairBilling = {}, onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText(
            "This session is already linked to a paid POS order. Extra paid time is locked; " +
                "Stop still records the final play time without creating another bill.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Extend").assertIsNotEnabled()
        compose.onNodeWithText("Stop & calculate").assertIsEnabled()
    }

    @Test
    fun rejectedPaidExtensionBlocksCompetingActionsAndOffersExactVerification() {
        var reviewed = false
        val station = testStation()
        val session = GameSession(
            id = "session-1",
            stationId = station.id,
            shiftId = "shift-1",
            status = "active",
            startAt = "2026-08-26T17:00:00Z",
            timerMinutes = 60,
            amountMinor = 15_000,
            ratePerHourMinor = 15_000,
            packageId = "package-60",
        )
        val action = PackageExtensionActionUi(
            actionId = "action-1",
            serverSessionId = session.id,
            state = "rejected",
            lastError = "Package price changed. Refresh Gaming.",
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = action,
                        wallClock = rememberedWallClock(Instant.parse("2026-08-26T18:00:00Z").toEpochMilli()),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = true,
                        onStart = {}, onStop = {},
                        onSend = {}, onCancelUnbilled = {}, onExtendTimer = {},
                        onExtendPackage = { _, _ -> }, onTransfer = {}, onReconcile = {},
                        onRepairBilling = {},
                        onResolveLegacyStart = {},
                        onDiscardPackageExtension = { reviewed = true },
                    )
                }
            }
        }

        compose.onNodeWithText("Verify rejected extension").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("Stop & calculate").assertCountEquals(0)
        compose.runOnIdle { assertEquals(true, reviewed) }
    }

    @Test
    fun missingBillingOffersOnlyProtectedOwnerRepair() {
        val station = testStation()
        val session = GameSession(
            id = "session-1",
            stationId = station.id,
            shiftId = "shift-1",
            status = "ended",
            startAt = "2026-08-26T17:00:00Z",
            endAt = "2026-08-26T18:00:00Z",
            amountMinor = null,
            ratePerHourMinor = 15_000,
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(Instant.parse("2026-08-26T18:00:00Z").toEpochMilli()),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = true,
                        activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = false,
                        onStart = {}, onStop = {},
                        onSend = {}, onCancelUnbilled = {}, onExtendTimer = {},
                        onExtendPackage = { _, _ -> }, onTransfer = {}, onReconcile = {},
                        onRepairBilling = {}, onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Owner repair billing").assertIsDisplayed()
        compose.onAllNodesWithText("Void").assertCountEquals(0)
        compose.onAllNodesWithText("Send to POS").assertCountEquals(0)
    }

    @Test
    fun stoppingCardFreezesElapsedTimeAtCapturedStop() {
        val station = testStation()
        val session = GameSession(
            id = "session-1",
            stationId = station.id,
            shiftId = "shift-1",
            status = "stopping",
            startAt = "2026-08-26T17:00:00Z",
            endAt = "2026-08-26T17:30:00Z",
            ratePerHourMinor = 15_000,
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(Instant.parse("2026-08-26T18:00:00Z").toEpochMilli()),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = false,
                        onStart = {}, onStop = {},
                        onSend = {}, onCancelUnbilled = {}, onExtendTimer = {},
                        onExtendPackage = { _, _ -> }, onTransfer = {}, onReconcile = {},
                        onRepairBilling = {}, onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText("00:30:00").assertIsDisplayed()
        compose.onAllNodesWithText("01:00:00").assertCountEquals(0)
    }

    @Test
    fun quarantinedPlayedPackageOffersOnlyProtectedAuditRecovery() {
        var ownerReviewOpened = false
        val station = testStation()
        val session = GameSession(
            id = "11111111-1111-4111-8111-111111111111",
            stationId = station.id,
            shiftId = "shift-1",
            status = "start_failed",
            startAt = "2026-08-26T17:00:00Z",
            endAt = "2026-08-26T17:45:00Z",
            packageId = "package-60",
            localState = GamingSessionState.START_REJECTED,
            lastError = LEGACY_PACKAGE_START_REVIEW_ERROR,
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T18:00:00Z").toEpochMilli(),
                        ),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = true,
                        activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = false,
                        onStart = {}, onStop = {},
                        onSend = {}, onCancelUnbilled = {}, onExtendTimer = {},
                        onExtendPackage = { _, _ -> }, onTransfer = {}, onReconcile = {},
                        onRepairBilling = {},
                        onResolveLegacyStart = { ownerReviewOpened = true },
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Owner resolve captured play")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
        compose.onAllNodesWithText("Discard").assertCountEquals(0)
        compose.runOnIdle { assertTrue(ownerReviewOpened) }
    }

    @Test
    fun genericRejectedStartRequiresOwnerStepUpWithoutRetryOrDiscard() {
        var approvalOpened = false
        val station = testStation()
        val session = GameSession(
            id = "55555555-5555-4555-8555-555555555555",
            stationId = station.id,
            shiftId = "shift-1",
            status = "start_failed",
            startAt = "2026-08-26T17:00:00Z",
            localState = GamingSessionState.START_REJECTED,
            lastError = "The shift closed before the saved start reached the server.",
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T18:00:00Z").toEpochMilli(),
                        ),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = false,
                        onStart = {}, onStop = {},
                        onSend = {}, onCancelUnbilled = {}, onExtendTimer = {},
                        onExtendPackage = { _, _ -> }, onTransfer = {}, onReconcile = {},
                        onRepairBilling = {},
                        onResolveLegacyStart = { approvalOpened = true },
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Request owner approval")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onAllNodesWithText("Retry start").assertCountEquals(0)
        compose.onAllNodesWithText("Discard").assertCountEquals(0)
        compose.runOnIdle { assertTrue(approvalOpened) }
    }

    @Test
    fun delegatedOwnerResolutionRequiresCredentialsAndClearsPasswordOnSubmit() {
        var submittedEmail: String? = null
        var submittedPassword: String? = null
        var submittedResolution: String? = null
        val session = GameSession(
            id = "66666666-6666-4666-8666-666666666666",
            stationId = "station-1",
            shiftId = "shift-1",
            status = "start_failed",
            startAt = "2026-08-26T17:00:00Z",
            localState = GamingSessionState.START_REJECTED,
            lastError = LEGACY_PACKAGE_START_REVIEW_ERROR,
        )

        compose.setContent {
            DCompanyTheme {
                LegacyPackageResolutionDialog(
                    session = session,
                    stationName = "PS5 Station 1",
                    requiresOwnerStepUp = true,
                    onDismiss = {},
                    onConfirm = { resolution, _, _, email, password ->
                        submittedResolution = resolution
                        submittedEmail = email
                        submittedPassword = password
                    },
                )
            }
        }

        compose.onNodeWithText("Owner approve & record").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Protected owner approval email")
            .performScrollTo()
            .performTextReplacement(" OWNER@DCompany.test ")
        compose.onNodeWithContentDescription("Protected owner approval password")
            .performScrollTo()
            .performTextReplacement("owner-secret")
        compose.onNodeWithText("Confirmed no play")
            .performScrollTo()
            .performClick()
        compose.onNodeWithContentDescription("Legacy package resolution reason")
            .performScrollTo()
            .performTextReplacement("Owner verified that play never began")
        compose.onNodeWithText("Owner approve & record")
            .bringIntoViewIfNeeded()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals(GamingLegacyResolution.CONFIRMED_NO_PLAY, submittedResolution)
            assertEquals("owner@dcompany.test", submittedEmail)
            assertEquals("owner-secret", submittedPassword)
        }
        compose.onNodeWithContentDescription("Protected owner approval password")
            .assertEditableTextEquals("")
    }

    @Test
    fun delegatedOwnerPasswordIsNotRestoredFromSavedInstanceState() {
        val restoration = StateRestorationTester(compose)
        val session = GameSession(
            id = "77777777-7777-4777-8777-777777777777",
            stationId = "station-1",
            shiftId = "shift-1",
            status = "start_failed",
            startAt = "2026-08-26T17:00:00Z",
            localState = GamingSessionState.START_REJECTED,
            lastError = LEGACY_PACKAGE_START_REVIEW_ERROR,
        )

        restoration.setContent {
            DCompanyTheme {
                LegacyPackageResolutionDialog(
                    session = session,
                    stationName = "PS5 Station 1",
                    requiresOwnerStepUp = true,
                    onDismiss = {},
                    onConfirm = { _, _, _, _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Protected owner approval email")
            .performScrollTo()
            .performTextReplacement("owner@dcompany.test")
        compose.onNodeWithContentDescription("Protected owner approval password")
            .performScrollTo()
            .performTextReplacement("must-not-enter-bundle")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithContentDescription("Protected owner approval email")
            .assertEditableTextEquals("owner@dcompany.test")
        compose.onNodeWithContentDescription("Protected owner approval password")
            .assertEditableTextEquals("")
    }

    @Test
    fun capturedStopDefaultsToAuthoritativeAcceptedStartRecovery() {
        var submittedResolution: String? = null
        var submittedReference: String? = "unexpected"
        val session = GameSession(
            id = "99999999-9999-4999-8999-999999999999",
            stationId = "station-1",
            shiftId = "shift-1",
            status = "start_failed",
            startAt = "2026-08-26T17:00:00Z",
            endAt = "2026-08-26T17:30:00Z",
            localState = GamingSessionState.START_REJECTED,
            lastError = "Server rejected the retained start",
        )

        compose.setContent {
            DCompanyTheme {
                LegacyPackageResolutionDialog(
                    session = session,
                    stationName = "PS5 Station 1",
                    requiresOwnerStepUp = true,
                    onDismiss = {},
                    onConfirm = { resolution, reference, _, _, _ ->
                        submittedResolution = resolution
                        submittedReference = reference
                    },
                )
            }
        }

        compose.onNodeWithText("Recover accepted server start")
            .performScrollTo()
            .assertIsSelected()
        compose.onAllNodesWithContentDescription("Legacy resolution POS order ID")
            .assertCountEquals(0)
        compose.onNodeWithContentDescription("Protected owner approval email")
            .performScrollTo()
            .performTextReplacement("owner@dcompany.test")
        compose.onNodeWithContentDescription("Protected owner approval password")
            .performScrollTo()
            .performTextReplacement("owner-secret")
        compose.onNodeWithContentDescription("Legacy package resolution reason")
            .performScrollTo()
            .performTextReplacement("Recover the exact accepted Start and replay its Stop")
        compose.onNodeWithText("Owner approve & recover")
            .bringIntoViewIfNeeded()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals(GamingLegacyResolution.SERVER_SESSION_RECOVERED, submittedResolution)
            assertEquals(null, submittedReference)
        }
    }

    @Test
    fun ambiguousLegacyResolutionExplainsSameApproverReplayRequirement() {
        val session = GameSession(
            id = "88888888-8888-4888-8888-888888888888",
            stationId = "station-1",
            shiftId = "shift-1",
            status = "start_failed",
            startAt = "2026-08-26T17:00:00Z",
            localState = GamingSessionState.START_REJECTED,
            legacyResolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
            legacyResolutionReason = "Recover the exact accepted Start and replay its Stop",
            legacyResolutionAttemptState = GamingLegacyResolutionAttemptState.AMBIGUOUS,
            legacyResolutionError = "response lost",
        )

        compose.setContent {
            DCompanyTheme {
                LegacyPackageResolutionDialog(
                    session = session,
                    stationName = "PS5 Station 1",
                    requiresOwnerStepUp = true,
                    onDismiss = {},
                    onConfirm = { _, _, _, _, _ -> },
                )
            }
        }

        compose.onNodeWithText(
            "The exact saved decision is locked because the previous response may have committed. " +
                "Retrying uses the same request and cannot create a second receipt. " +
                "The same protected owner who made the first attempt must approve this retry.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Recover accepted server start")
            .performScrollTo()
            .assertIsSelected()
            .assertIsNotEnabled()
        compose.onNodeWithText("Owner approve & retry").assertIsNotEnabled()
    }

    @Test
    fun recoveredUnsafeHourlyChronologyCannotBecomeOrdinaryStopOrPosFlow() {
        val station = testStation()
        val review =
            "The server Start was recovered, but the retained offline Stop cannot be replayed without changing the bill. " +
                "Ordinary Stop and Send to POS remain locked. Keep this owner audit receipt and contact support for audited billing resolution."
        val session = GameSession(
            id = "server-hourly-review",
            stationId = station.id,
            shiftId = "server-shift-1",
            status = "start_failed",
            startAt = "2026-08-26T17:30:00Z",
            endAt = "2026-08-26T17:15:00Z",
            ratePerHourMinor = 15_000,
            billingMode = "hourly",
            localState = GamingSessionState.START_REJECTED,
            lastError = review,
            legacyOriginalCapturedStartAt = "2026-08-26T17:00:00Z",
            legacyOriginalCapturedStopAt = "2026-08-26T17:15:00Z",
            legacyResolution = GamingLegacyResolution.CONFIRMED_NO_PLAY,
            legacyResolutionAttemptState = GamingLegacyResolutionAttemptState.RESOLVED,
            legacyResolutionError = review,
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T18:00:00Z").toEpochMilli(),
                        ),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = true,
                        activeShiftId = "server-shift-1",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = true,
                        onStart = {}, onStop = { error("Unsafe Stop must remain unreachable") },
                        onSend = { error("Unsafe POS handoff must remain unreachable") },
                        onCancelUnbilled = {}, onExtendTimer = {},
                        onExtendPackage = { _, _ -> }, onTransfer = {}, onReconcile = {},
                        onRepairBilling = {}, onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }

        compose.onNodeWithText(review).assertIsDisplayed()
        compose.onNodeWithText("Billing review retained").assertIsNotEnabled()
        compose.onAllNodesWithText("Stop & calculate").assertCountEquals(0)
        compose.onAllNodesWithText("Send to POS").assertCountEquals(0)
    }

    @Test
    fun orphanRejectedExtensionStaysReachableWithoutRenderedSession() {
        var reviewed = false
        val action = PackageExtensionActionUi(
            actionId = "action-orphan",
            serverSessionId = "session-paid-12345678",
            shiftId = "shift-1",
            state = GamingPackageExtensionState.REJECTED,
            lastError = "ledger checked; no extension applied",
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(700.dp)) {
                    OrphanPackageExtensionBanner(
                        action = action,
                        activeShiftId = "shift-1",
                        canWrite = true,
                        busy = false,
                        onReview = { reviewed = true },
                    )
                }
            }
        }

        compose.onNodeWithText("Rejected paid extension needs verification").assertIsDisplayed()
        compose.onNodeWithText("Verify original attempt")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertTrue(reviewed) }
    }

    @Test
    fun closedSourceShiftOrphanRemainsRecoverableByOriginatingWriter() {
        var reviewed = false
        val action = PackageExtensionActionUi(
            actionId = "action-closed-shift",
            serverSessionId = "session-paid-87654321",
            shiftId = "closed-shift",
            state = GamingPackageExtensionState.REJECTED,
            lastError = "ledger checked; no extension applied",
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(700.dp)) {
                    OrphanPackageExtensionBanner(
                        action = action,
                        activeShiftId = null,
                        canWrite = true,
                        busy = false,
                        onReview = { reviewed = true },
                    )
                }
            }
        }

        compose.onNodeWithText("Verify saved attempt")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertTrue(reviewed) }
    }

    @Test
    fun unscopedRejectedExtensionIsRetainedWithHonestSupportCopy() {
        val action = PackageExtensionActionUi(
            actionId = "action-unscoped",
            serverSessionId = "session-paid-11223344",
            shiftId = null,
            state = GamingPackageExtensionState.REJECTED,
            lastError = "legacy action has no shift provenance",
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(700.dp)) {
                    OrphanPackageExtensionBanner(
                        action = action,
                        activeShiftId = null,
                        canWrite = true,
                        busy = false,
                        onReview = { error("Unscoped action must remain blocked") },
                    )
                }
            }
        }

        compose.onNodeWithText("Exact shift missing")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        compose.onNodeWithText(
            "This retained extension is missing exact shift provenance and cannot be replayed safely. " +
                "Keep this tablet signed in and contact support; the possible charge was not removed.",
        ).assertIsDisplayed()
    }

    @Test
    fun startSession_phoneInputAndPrimaryActionRemainReachable() {
        var submittedPhone: String? = null
        var submittedMinutes: Int? = null

        compose.setContent {
            DCompanyTheme {
                StartSessionDialog(
                    station = testStation(),
                    onDismiss = {},
                    onConfirm = { phone, minutes, _, _ ->
                        submittedPhone = phone
                        submittedMinutes = minutes
                    },
                )
            }
        }

        compose.onNodeWithContentDescription("Member phone (optional)")
            .bringIntoViewIfNeeded()
            .performClick()
            .assertIsFocused()
            .performTextReplacement("+91 98765 43210")

        compose.onNodeWithText("Start session")
            .bringIntoViewIfNeeded()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals("919876543210", submittedPhone)
            assertEquals(60, submittedMinutes)
        }
    }

    @Test
    fun startSession_packageAndControllerSelectionReachConfirmation() {
        var submittedPackage: String? = null
        var submittedControllers = -1
        var submittedMinutes: Int? = -1

        compose.setContent {
            DCompanyTheme {
                StartSessionDialog(
                    station = testStation(),
                    packages = listOf(
                        GamingPackage(
                            id = "package-60",
                            stationType = "ps5",
                            variant = "solo",
                            kind = "base",
                            name = "Solo 60 min",
                            durationMinutes = 60,
                            priceMinor = 15_000,
                        ),
                    ),
                    onDismiss = {},
                    onConfirm = { _, minutes, packageId, controllers ->
                        submittedMinutes = minutes
                        submittedPackage = packageId
                        submittedControllers = controllers
                    },
                )
            }
        }

        compose.onNodeWithText("Solo 60 min · ₹150.00").performClick()
        compose.onNodeWithContentDescription("Increase extra controllers")
            .bringIntoViewIfNeeded()
            .performClick()
        compose.onNodeWithText("Start · ₹180.00")
            .bringIntoViewIfNeeded()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals(null, submittedMinutes)
            assertEquals("package-60", submittedPackage)
            assertEquals(1, submittedControllers)
        }
    }

    @Test
    fun cancelSession_customReasonAndConfirmationRemainReachable() {
        var submittedReason: String? = null

        compose.setContent {
            DCompanyTheme {
                CancelUnbilledSessionDialog(
                    stationName = "PS5 Station 1",
                    amountMinor = 15_750,
                    onDismiss = {},
                    onConfirm = { submittedReason = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Cancellation reason: Other or add details")
            .bringIntoViewIfNeeded()
            .performClick()
        compose.onAllNodesWithContentDescription("Cancellation reason: Guest changed mind")
            .assertCountEquals(0)
        compose.onNodeWithText("Presets").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show keyboard for custom cancellation reason")
            .bringIntoViewIfNeeded()
            .assertIsDisplayed()
        val customReason = compose.onNodeWithContentDescription("Custom cancellation reason")
        customReason
            .bringIntoViewIfNeeded()
            .assertIsFocused()
            .performTextReplacement("Customer changed to another station")
        customReason.performImeAction()

        compose.onAllNodesWithText("Keep session").assertCountEquals(1)
        compose.onNodeWithText("Keep session").assertIsDisplayed()
        compose.onAllNodesWithText("Void session").assertCountEquals(1)
        compose.onNodeWithText("Void session")
            .bringIntoViewIfNeeded()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals("Customer changed to another station", submittedReason)
        }
    }

    @Test
    fun cancelSession_presetReasonKeepsFooterActionsReachable() {
        var submittedReason: String? = null

        compose.setContent {
            DCompanyTheme {
                CancelUnbilledSessionDialog(
                    stationName = "PS5 Station 1",
                    amountMinor = 15_750,
                    onDismiss = {},
                    onConfirm = { submittedReason = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Cancellation reason: Guest changed mind")
            .performClick()
        compose.onNodeWithText("Keep session").assertIsDisplayed()
        compose.onNodeWithText("Void session")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals("Guest changed mind", submittedReason)
        }
    }

    private fun testStation() = Station(
        id = "station-1",
        code = "PS5-1",
        name = "PS5 Station 1",
        type = "ps5",
        ratePerHourMinor = 15_000,
    )

    private fun SemanticsNodeInteraction.bringIntoViewIfNeeded(): SemanticsNodeInteraction =
        if (isDisplayed()) this else performScrollTo()

    private fun SemanticsNodeInteraction.assertEditableTextEquals(
        expected: String,
    ): SemanticsNodeInteraction = assert(
        SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString(expected),
        ),
    )
}
