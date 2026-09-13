package cloud.dcompany.erp.ui.screens.gaming

import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.Choreographer
import android.view.MotionEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.db.GamingSessionAddonActionState
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingPackageExtensionState
import cloud.dcompany.erp.core.db.GamingLegacyResolution
import cloud.dcompany.erp.core.db.GamingLegacyResolutionAttemptState
import cloud.dcompany.erp.core.db.LEGACY_PACKAGE_START_REVIEW_ERROR
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.time.Instant
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Composable
private fun rememberedWallClock(epochMillis: Long): MutableLongState =
    remember(epochMillis) { mutableLongStateOf(epochMillis) }

private const val PINNED_ACTION_VIEWPORT_TAG = "pinned-gaming-action-viewport"
private val PINNED_ACTIVE_ACTION_LABELS = listOf(
    "Add drinks & snacks",
    "Extend",
    "Transfer",
    "Pause",
    "Stop & calculate",
)

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
    fun globalWriteLockExplainsWhyOtherStationActionsArePaused() {
        compose.setContent {
            DCompanyTheme {
                GamingSavingOverlay(stationName = "PS5 Station 1")
            }
        }

        compose.onNodeWithText("Saving PS5 Station 1")
            .assertIsDisplayed()
        compose.onNodeWithText(
            "Other station actions are paused until this change is safely stored.",
        ).assertIsDisplayed()
    }

    @Test
    fun availablePs5TileShowsFixedTariffInsteadOfLegacyHourlyRate() {
        val station = Station(
            id = "station-fixed-price",
            code = "PS5-FIXED",
            name = "PS5 Station Fixed",
            type = "ps5",
            ratePerHourMinor = 20_000,
        )
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
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationTile(
                        station = station,
                        session = null,
                        sessionAddons = emptyList(),
                        packages = packages,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T18:00:00Z").toEpochMilli(),
                        ),
                        selected = false,
                        focused = false,
                        combinedBillSnapshotMinor = null,
                        onSelect = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Fixed packages from ₹80.00").assertIsDisplayed()
        compose.onAllNodesWithText("₹200.00/hour").assertCountEquals(0)
        compose.onNodeWithContentDescription(
            "PS5 Station Fixed. Available. Ready. Fixed packages from ₹80.00",
        ).assertIsDisplayed()
    }

    @Test
    fun busyStationKeepsProgressOnItsOwnAction() {
        val station = Station(
            id = "station-busy",
            code = "PS5-BUSY",
            name = "PS5 Station Busy",
            type = "ps5",
            ratePerHourMinor = 20_000,
            isActive = true,
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(270.dp)) {
                    GamingStationCard(
                        station = station,
                        session = null,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(Instant.parse("2026-08-26T18:00:00Z").toEpochMilli()),
                        actionInProgress = true,
                        busyHere = true,
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

        compose.onNodeWithText("Start session…")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Start session in progress",
                ),
            )
    }

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
        compose.onNodeWithText("Extend").assertIsDisplayed()
        compose.onNodeWithText("Transfer").assertIsDisplayed()
        compose.onNodeWithText("Stop & calculate").assertIsDisplayed()
    }

    @Test
    fun narrowCardKeepsCompleteActionLabelsAndTouchTargetsWithoutHidingStop() {
        val station = testStation()
        val session = mutableStateOf(
            GameSession(
                id = "session-narrow-actions",
                stationId = station.id,
                shiftId = "shift-1",
                status = "active",
                startAt = "2026-08-26T17:00:00Z",
                ratePerHourMinor = 15_000,
                pauseVersion = 0,
                pauseAvailable = true,
            ),
        )
        val actions = mutableListOf<String>()

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(252.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session.value,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T17:05:00Z").toEpochMilli(),
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
                        onStop = { actions += "stop" },
                        onSend = {},
                        onCancelUnbilled = {},
                        onExtendTimer = { actions += "extend" },
                        onExtendPackage = { _, _ -> },
                        onTransfer = { actions += "transfer" },
                        onReconcile = {},
                        onRepairBilling = {},
                        onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                        onPauseResume = { actions += if (it.status == "paused") "resume" else "pause" },
                    )
                }
            }
        }

        fun assertCompleteAction(label: String) {
            compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$label must expose its rendered text layout", layouts.isNotEmpty())
            val diagnostic = layouts.joinToString { layout ->
                "size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                    "paragraphWidth=${layout.multiParagraph.width}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                    "lines=${layout.lineCount}, lineRight=${layout.getLineRight(0)}, " +
                    "lineBottom=${layout.getLineBottom(0)}, visibleEnd=${layout.getLineEnd(0, visibleEnd = true)}, " +
                    "fontScale=${layout.layoutInput.density.fontScale}"
            }
            // Compose 1.7.5 compares the shrink-wrapped Text size with the
            // paragraph's wider layout constraint for didOverflowWidth. The
            // captured Extend had size 86px, lineRight 86px and 130px allowed
            // width: all glyphs fitted even though that broad flag was true.
            // Check actual visible text and every glyph, not paragraph bounds.
            val fullyVisible = layouts.all { layout ->
                layout.layoutInput.text.text == label && layout.lineCount == 1 &&
                    !layout.didOverflowHeight && !layout.isLineEllipsized(0) &&
                    layout.getLineEnd(0) == label.length &&
                    layout.getLineEnd(0, visibleEnd = true) == label.length &&
                    label.indices.all { offset ->
                        val glyph = layout.getBoundingBox(offset)
                        // At most one physical pixel of integer-layout rounding.
                        glyph.left >= -1f && glyph.top >= -1f &&
                            glyph.right <= layout.size.width + 1f &&
                            glyph.bottom <= layout.size.height + 1f
                    }
            }
            if (!fullyVisible) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val file = File(context.getExternalFilesDir(null), "narrow-gaming-action-overflow.png")
                file.outputStream().use { output ->
                    compose.onRoot().captureToImage().asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            }
            assertTrue(
                "$label must not be clipped at 252dp card width: $diagnostic",
                fullyVisible,
            )
        }

        listOf("Extend", "Transfer", "Pause", "Stop & calculate").forEach { label ->
            assertCompleteAction(label)
            compose.onNodeWithText(label).performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("extend", "transfer", "pause", "stop"), actions)
            session.value = session.value.copy(
                status = "paused",
                pausedAt = "2026-08-26T17:05:00Z",
                pauseVersion = 1,
            )
        }
        assertCompleteAction("Resume")
        compose.onNodeWithText("Resume").performClick()
        compose.onNodeWithText("Stop & calculate").assertIsDisplayed()
        compose.runOnIdle { assertEquals("resume", actions.last()) }
    }

    @Test
    fun commandViewportKeepsCriticalActionsVisibleBeforeSavedItemHistory() {
        val station = testStation()
        val session = GameSession(
            id = "session-command-actions",
            stationId = station.id,
            shiftId = "shift-1",
            status = "active",
            startAt = "2026-08-26T17:00:00Z",
            timerMinutes = 30,
            timerEndsAt = "2026-08-26T17:30:00Z",
            amountMinor = 8_000,
            ratePerHourMinor = 15_000,
            packageId = "standard-single-30",
            billingMode = "package",
            packagePriceMinorSnapshot = 8_000,
            packageDurationMinutesSnapshot = 30,
            packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5",
            packagePricingTierSnapshot = "standard",
            pauseVersion = 0,
            pauseAvailable = true,
        )
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
                id = "standard-single-extension-30",
                code = "standard-single-extension-30m",
                stationType = "ps5",
                pricingTier = "standard",
                variant = "single",
                kind = "extension",
                name = "Single Mode +30 minutes",
                durationMinutes = 30,
                priceMinor = 6_000,
            ),
        )
        val savedAddon = GamingSessionAddonUi(
            id = "addon-cola",
            serverAddonId = "server-addon-cola",
            serverSessionId = session.id,
            clientLineId = "line-cola",
            menuItemId = "item-cola",
            menuItemName = "Cola",
            menuItemType = "product",
            qty = 1,
            unitPriceMinor = 5_000,
            lineTotalMinor = 5_000,
            localState = GamingSessionAddonActionState.CONFIRMED,
        )

        compose.setContent {
            DCompanyTheme {
                // The Tab P12 physical run exposed a 417dp x 267dp Station
                // command viewport. Keep the production scroll boundary in
                // this regression so saved item rows cannot push Stop below
                // the initially visible command surface again.
                Box(
                    Modifier.width(417.dp).height(267.dp)
                        .testTag(PINNED_ACTION_VIEWPORT_TAG),
                ) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        sessionAddons = listOf(savedAddon),
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T17:05:00Z").toEpochMilli(),
                        ),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true,
                        packages = packages,
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
                        pinActiveSessionActions = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        PINNED_ACTIVE_ACTION_LABELS.forEach { label ->
            assertPinnedActionInsideViewport(label, enabled = true)
        }
        compose.onAllNodesWithText("+30 min").assertCountEquals(0)
        compose.onNode(hasScrollAction()).assertExists()
        compose.onNodeWithText("Session items").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cola ×1").performScrollTo().assertIsDisplayed()
        // The saved-item history scroll belongs only to the upper card body.
        // Reassert every command afterwards so a future parent-scroll change
        // cannot move a critical action partly or completely off-screen.
        PINNED_ACTIVE_ACTION_LABELS.forEach { label ->
            assertPinnedActionInsideViewport(label, enabled = true)
        }
    }

    @Test
    fun compactPinnedOfflineWarningKeepsActionsAndRecoveryReasonFullyVisible() {
        val station = testStation()
        val session = GameSession(
            id = "session-command-warning",
            stationId = station.id,
            shiftId = "shift-1",
            status = "active",
            startAt = "2026-08-26T17:00:00Z",
            timerMinutes = 30,
            timerEndsAt = "2026-08-26T17:30:00Z",
            amountMinor = 8_000,
            ratePerHourMinor = 15_000,
            packageId = "standard-single-30",
            billingMode = "package",
            packagePriceMinorSnapshot = 8_000,
            packageDurationMinutesSnapshot = 30,
            packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5",
            packagePricingTierSnapshot = "standard",
            pauseVersion = 0,
            pauseAvailable = true,
        )
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
                id = "standard-single-extension-30",
                code = "standard-single-extension-30m",
                stationType = "ps5",
                pricingTier = "standard",
                variant = "single",
                kind = "extension",
                name = "Single Mode +30 minutes",
                durationMinutes = 30,
                priceMinor = 6_000,
            ),
        )

        compose.setContent {
            DCompanyTheme {
                Box(
                    Modifier.width(417.dp).height(267.dp)
                        .testTag(PINNED_ACTION_VIEWPORT_TAG),
                ) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T17:05:00Z").toEpochMilli(),
                        ),
                        actionInProgress = false,
                        busyHere = false,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true,
                        online = false,
                        packages = packages,
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
                        pinActiveSessionActions = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        PINNED_ACTIVE_ACTION_LABELS.forEach { label ->
            assertPinnedActionInsideViewport(label, enabled = label != "Pause")
        }
        compose.onNodeWithText("Reconnect to pause or resume safely across devices.")
            .assertIsDisplayed()
            .assertFullyInsidePinnedViewport(
                label = "disabled Pause recovery reason",
                requireTouchTarget = false,
            )
    }

    @Test
    fun commandViewportKeepsPaymentDueActionsReachableAfterSavedItems() {
        val station = testStation()
        val session = GameSession(
            id = "session-payment-due-items",
            stationId = station.id,
            shiftId = "shift-1",
            status = "ended",
            startAt = "2026-08-26T17:00:00Z",
            endAt = "2026-08-26T17:30:00Z",
            amountMinor = 8_000,
            ratePerHourMinor = 15_000,
        )
        val savedAddon = GamingSessionAddonUi(
            id = "addon-payment-cola",
            serverAddonId = "server-addon-payment-cola",
            serverSessionId = session.id,
            clientLineId = "line-payment-cola",
            menuItemId = "item-cola",
            menuItemName = "Cola",
            menuItemType = "product",
            qty = 1,
            unitPriceMinor = 5_000,
            lineTotalMinor = 5_000,
            voided = true,
            voidReason = "Customer changed order",
            localState = GamingSessionAddonActionState.CONFIRMED,
        )

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(417.dp).height(267.dp)) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = null,
                        sessionAddons = listOf(savedAddon),
                        wallClock = rememberedWallClock(
                            Instant.parse("2026-08-26T17:30:00Z").toEpochMilli(),
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
                        pinActiveSessionActions = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        compose.onNode(hasScrollAction()).assertExists()
        compose.onNodeWithText("Void").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Send to POS").performScrollTo().assertIsDisplayed().assertIsEnabled()
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
        var submittedPayload: List<String?>? = null
        var submissions = 0
        var dismissals = 0
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
                    onDismiss = { dismissals++ },
                    onConfirm = { resolution, reference, reason, email, password ->
                        submissions++
                        submittedResolution = resolution
                        submittedReference = reference
                        submittedPayload = listOf(resolution, reference, reason, email, password)
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
        assertRecoveryBodyScrollsWithTouchSwipe()
        compose.onNodeWithText("Owner approve & recover")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performAndroidScreenTouch()

        compose.runOnIdle {
            assertEquals(GamingLegacyResolution.SERVER_SESSION_RECOVERED, submittedResolution)
            assertEquals(null, submittedReference)
            assertEquals(1, submissions)
            assertEquals(0, dismissals)
            assertEquals(
                listOf(
                    GamingLegacyResolution.SERVER_SESSION_RECOVERED,
                    null,
                    "Recover the exact accepted Start and replay its Stop",
                    "owner@dcompany.test",
                    "owner-secret",
                ),
                submittedPayload,
            )
        }
    }

    @Test
    fun legacyRecoveryBackdropDismissesWithoutSubmitting() {
        val visible = mutableStateOf(true)
        var dismissals = 0
        var submissions = 0
        compose.setContent {
            DCompanyTheme {
                if (visible.value) LegacyPackageResolutionDialog(
                    session = GameSession(
                        id = "backdrop-recovery", stationId = "station-1", shiftId = "shift-1",
                        status = "start_failed", startAt = "2026-08-26T17:00:00Z",
                        endAt = "2026-08-26T17:30:00Z",
                        localState = GamingSessionState.START_REJECTED,
                    ),
                    stationName = "PS5 Station 1",
                    requiresOwnerStepUp = false,
                    onDismiss = { dismissals++; visible.value = false },
                    onConfirm = { _, _, _, _, _ -> submissions++ },
                )
            }
        }
        val title = compose.onNodeWithText("Resolve rejected gaming start")
        title.assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        val titleNode = title.fetchSemanticsNode()
        // Blank padding above the title belongs to the Surface, not backdrop.
        injectAndroidTap(titleNode.positionOnScreen.x, titleNode.positionOnScreen.y - 8f)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, dismissals)
            assertEquals(0, submissions)
        }
        val root = titleNode.root as ViewRootForTest
        val frame = compose.runOnIdle {
            android.graphics.Rect().also(root.view::getWindowVisibleDisplayFrame)
        }
        injectAndroidTap(frame.left + 2f, frame.top + 2f)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(0, submissions)
        }
        compose.onAllNodesWithText("Resolve rejected gaming start").assertCountEquals(0)
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
                    // Streaming remains hourly. PS5, racing and VR
                    // deliberately fail closed until the canonical fixed
                    // tariff has synced, so they cannot exercise this generic
                    // phone-and-duration path without a package catalogue.
                    station = testStation().copy(
                        code = "STREAM-1",
                        name = "Streaming Booth 1",
                        type = "streaming",
                    ),
                    onDismiss = {},
                    onConfirm = { phone, minutes, _, _ ->
                        submittedPhone = phone
                        submittedMinutes = minutes
                    },
                )
            }
        }

        compose.onNodeWithContentDescription("Customer phone (optional)")
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
                            id = "standard-single-60",
                            code = "standard-single-session-60m",
                            stationType = "ps5",
                            pricingTier = "standard",
                            variant = "single",
                            includedPlayers = 1,
                            maxPlayers = 1,
                            kind = "base",
                            name = "Single Mode 1 hour",
                            durationMinutes = 60,
                            priceMinor = 12_000,
                        ),
                        GamingPackage(
                            id = "standard-dual-60",
                            code = "standard-dual-session-60m",
                            stationType = "ps5",
                            pricingTier = "standard",
                            variant = "dual",
                            includedPlayers = 2,
                            maxPlayers = 4,
                            kind = "base",
                            name = "Dual Mode 1 hour",
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

        compose.onNodeWithText("3 players")
            .bringIntoViewIfNeeded()
            .performClick()
        compose.onNodeWithText("60 min · ₹180.00 total")
            .bringIntoViewIfNeeded()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("Start · ₹180.00")
            .bringIntoViewIfNeeded()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals(null, submittedMinutes)
            assertEquals("standard-dual-60", submittedPackage)
            assertEquals(1, submittedControllers)
        }
    }

    @Test
    fun startSession_simulatorUsesModeSelectorAndNeverOffersStalePremium() {
        var submittedPackage: String? = null
        compose.setContent {
            DCompanyTheme {
                StartSessionDialog(
                    station = testStation().copy(type = "simulator", name = "Racing Simulator 1"),
                    packages = listOf(
                        GamingPackage("simdrive-15", "standard-simdrive-session-15m", "simulator", "standard", "simdrive", 1, 1, "base", "Racing Sim · 15 min", 15, 7_000),
                        GamingPackage("vr-racing-15", "vr-racing-session-15m", "simulator", "standard", "vr_racing", 1, 1, "base", "VR Racing Sim · 15 min", 15, 10_000),
                        GamingPackage("premium-stale", "premium-single-session-60m", "simulator", "premium", "simdrive", 1, 1, "base", "Premium stale", 60, 15_000),
                    ),
                    onDismiss = {},
                    onConfirm = { _, _, packageId, _ -> submittedPackage = packageId },
                )
            }
        }

        compose.onNodeWithText("Premium stale").assertDoesNotExist()
        compose.onNodeWithText("VR Racing Sim").performClick()
        compose.onNodeWithText("15 min · ₹100.00 total").performClick()
        compose.onNodeWithText("Start · ₹100.00").performClick()
        compose.runOnIdle { assertEquals("vr-racing-15", submittedPackage) }
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
        // Custom mode is deliberately bounded on every tablet height. OEM
        // dialog/IME inset dispatch is not reliable enough to keep a separate
        // footer reachable while this editor owns keyboard focus.
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

    private fun assertPinnedActionInsideViewport(label: String, enabled: Boolean) {
        val action = compose.onNodeWithText(label)
            .assertIsDisplayed()
        if (enabled) action.assertIsEnabled() else action.assertIsNotEnabled()
        action.assertFullyInsidePinnedViewport(label = label, requireTouchTarget = true)
    }

    private fun SemanticsNodeInteraction.assertFullyInsidePinnedViewport(
        label: String,
        requireTouchTarget: Boolean,
    ): SemanticsNodeInteraction {
        val viewportNode = compose.onNodeWithTag(
            PINNED_ACTION_VIEWPORT_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val targetNode = fetchSemanticsNode()
        // boundsInRoot/boundsInWindow may already be clipped. Position plus
        // measured size preserves the complete laid-out target and therefore
        // detects an action whose hidden edge only appears to fit.
        val viewportPosition = viewportNode.positionOnScreen
        val targetPosition = targetNode.positionOnScreen
        val viewport = Rect(
            viewportPosition.x,
            viewportPosition.y,
            viewportPosition.x + viewportNode.size.width,
            viewportPosition.y + viewportNode.size.height,
        )
        val target = Rect(
            targetPosition.x,
            targetPosition.y,
            targetPosition.x + targetNode.size.width,
            targetPosition.y + targetNode.size.height,
        )
        val tolerancePx = 1f
        val fullyInside = target.left >= viewport.left - tolerancePx &&
            target.top >= viewport.top - tolerancePx &&
            target.right <= viewport.right + tolerancePx &&
            target.bottom <= viewport.bottom + tolerancePx
        if (requireTouchTarget) {
            val root = targetNode.root as ViewRootForTest
            val minimumTouchPx = 48f * root.view.resources.displayMetrics.density
            assertTrue(
                "$label must expose a complete 48dp touch target: target=$target, " +
                    "minimumPx=$minimumTouchPx",
                target.width >= minimumTouchPx - tolerancePx &&
                    target.height >= minimumTouchPx - tolerancePx,
            )
        }
        assertTrue(
            "$label must be fully inside the tagged 417dp x 267dp viewport: " +
                "target=$target, viewport=$viewport",
            fullyInside,
        )
        return this
    }

    private fun SemanticsNodeInteraction.bringIntoViewIfNeeded(): SemanticsNodeInteraction =
        if (isDisplayed()) this else performScrollTo()

    private data class DialogTouchSnapshot(
        val bounds: Rect,
        val clippedBounds: Rect,
        val windowWidth: Int,
        val windowHeight: Int,
        val imeBottom: Int,
        val usableBounds: Rect,
        val minimumTouchSize: Float,
        val imeVisible: Boolean,
        val windowFocused: Boolean,
        val layoutPending: Boolean,
    ) {
        val ready: Boolean get() = imeVisible && imeBottom > 0 && windowFocused &&
            !layoutPending && bounds.width >= minimumTouchSize && bounds.height >= minimumTouchSize &&
            abs(bounds.left - clippedBounds.left) <= 1 && abs(bounds.top - clippedBounds.top) <= 1 &&
            abs(bounds.right - clippedBounds.right) <= 1 && abs(bounds.bottom - clippedBounds.bottom) <= 1 &&
            bounds.left >= usableBounds.left && bounds.right <= usableBounds.right &&
            bounds.top >= usableBounds.top && bounds.bottom <= usableBounds.bottom
    }

    private fun SemanticsNodeInteraction.dialogTouchSnapshot(): DialogTouchSnapshot {
        val node = fetchSemanticsNode()
        // boundsInWindow is clipped; position + size preserves the WHOLE button.
        val position = node.positionOnScreen
        val bounds = Rect(
            position.x, position.y,
            position.x + node.size.width, position.y + node.size.height,
        )
        val root = node.root as ViewRootForTest
        val clippedInWindow = node.boundsInWindow
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val keyboardBounds = automation.windows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
        return compose.runOnIdle {
            val view = root.view
            val screenOrigin = IntArray(2).also(view::getLocationOnScreen)
            val windowOrigin = IntArray(2).also(view::getLocationInWindow)
            val visibleFrame = android.graphics.Rect().also(view::getWindowVisibleDisplayFrame)
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            DialogTouchSnapshot(
                bounds = bounds,
                clippedBounds = clippedInWindow.translate(
                    (screenOrigin[0] - windowOrigin[0]).toFloat(),
                    (screenOrigin[1] - windowOrigin[1]).toFloat(),
                ),
                windowWidth = view.resources.displayMetrics.widthPixels,
                windowHeight = view.resources.displayMetrics.heightPixels,
                imeBottom = ime.bottom,
                usableBounds = Rect(
                    visibleFrame.left.toFloat(), visibleFrame.top.toFloat(),
                    visibleFrame.right.toFloat(),
                    minOf(visibleFrame.bottom, keyboardBounds?.top ?: 0).toFloat(),
                ),
                minimumTouchSize = 48 * view.resources.displayMetrics.density,
                imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && keyboardBounds != null,
                windowFocused = view.hasWindowFocus(),
                layoutPending = root.hasPendingMeasureOrLayout || view.isLayoutRequested,
            )
        }
    }

    /** Wait for Android's IME/window work, not just Compose's recomposition clock. */
    private fun SemanticsNodeInteraction.awaitImeSettledTouchTarget(): DialogTouchSnapshot {
        // Text replacement changes editor focus and asynchronously resizes the
        // dialog. performClick injects coordinates; a partly visible node can
        // otherwise move after isDisplayed succeeds. Keep the IME OPEN and
        // require the complete target above it, then perform a real touch.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        compose.waitForIdle()
        var previous: DialogTouchSnapshot? = null
        var stableSamples = 0
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                awaitAndroidFrame()
                val snapshot = dialogTouchSnapshot()
                if (snapshot != previous) Log.i("GamingDialogTouch", "IME target: $snapshot")
                stableSamples = if (snapshot.ready && snapshot == previous) stableSamples + 1 else 0
                previous = snapshot
                stableSamples >= 2
            }
        } finally {
            saveImeScreenshot()
        }
        Log.i("GamingDialogTouch", "IME-open real-touch target: $previous")
        return checkNotNull(previous)
    }

    private fun awaitAndroidFrame() {
        val nextFrame = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().postFrameCallback { nextFrame.countDown() }
        }
        check(nextFrame.await(2, TimeUnit.SECONDS)) { "Android did not render another frame" }
    }

    private fun saveImeScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            val file = File(
                instrumentation.targetContext.getExternalFilesDir(null),
                "gaming-recovery-ime-open.png",
            )
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun SemanticsNodeInteraction.performAndroidScreenTouch(): SemanticsNodeInteraction {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val settled = awaitImeSettledTouchTarget()
            val snapshot = dialogTouchSnapshot()
            check(snapshot.ready && snapshot == settled) {
                "Touch target moved after settling: $snapshot"
            }
            injectAndroidTap(snapshot.bounds.center.x, snapshot.bounds.center.y)
            compose.waitForIdle()
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
        return this
    }

    private fun injectAndroidTap(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, x, y, 0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                check(automation.injectInputEvent(event, true)) { "Android rejected touch injection" }
            } finally {
                event.recycle()
            }
        }
    }

    private fun assertRecoveryBodyScrollsWithTouchSwipe() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(500, 5_000)
        compose.waitForIdle()
        val scroll = compose.onNode(hasScrollAction())
        val node = scroll.fetchSemanticsNode()
        val before = node.config[SemanticsProperties.VerticalScrollAxisRange].value()
        check(before > 0) { "Reason input should have scrolled the long recovery form" }
        // Inject through Compose's duration-controlled touch clock on the
        // actual scroll node. The former hand-built UiAutomation gesture began
        // inside Android's left-edge exclusion zone and could be consumed by
        // Gboard/WindowManager during a busy full suite even though every raw
        // event reported success. The separate confirmation-button check below
        // still uses a real Android screen-coordinate tap and proves final hit
        // testing with the IME open.
        scroll.performTouchInput { swipeDown(durationMillis = 500) }
        compose.waitUntil(5_000) {
            scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() < before
        }
        Log.i("GamingDialogTouch", "Touch swipe moved recovery body from $before to " +
            scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
    }

    private fun SemanticsNodeInteraction.assertEditableTextEquals(
        expected: String,
    ): SemanticsNodeInteraction = assert(
        SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString(expected),
        ),
    )
}
