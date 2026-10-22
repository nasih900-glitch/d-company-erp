package cloud.dcompany.erp.ui.screens.gaming

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.auth.ShiftAccess
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.screens.shift.InlineShiftOpeningContent
import cloud.dcompany.erp.ui.screens.shift.ShiftUiState
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real production shell/form with an in-memory command callback; no database writes. */
class GamingInlineShiftWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun inlineShiftOwnsScrollableBodyAndReturnsExactDualFourSixtyMinuteDraft() {
        val stations = (1..4).map {
            Station("ps5-$it", "PS5-$it", "PS5 Station $it", "ps5", 12_000)
        } + listOf(
            Station("racing", "RACE", "Racing Simulator", "racing", 10_000),
            Station("vr-1", "VR-1", "VR Pod 1", "vr", 12_000),
            Station("vr-2", "VR-2", "VR Pod 2", "vr", 12_000),
            Station("streaming", "STREAM", "Streaming Booth", "streaming", 10_000),
            Station("shisha", "SHISHA", "Shisha Table", "shisha", 10_000),
        )
        val state = GamingUiState(
            stations = stations,
            packages = listOf(
                GamingPackage(id = "single-30", code = "single-30", stationType = "ps5",
                    variant = "single", kind = "base", name = "Single 30",
                    durationMinutes = 30, priceMinor = 8_000),
                GamingPackage(id = "dual-30", code = "dual-30", stationType = "ps5",
                    variant = "dual", kind = "base", name = "Dual 30",
                    durationMinutes = 30, priceMinor = 10_000, includedPlayers = 2, maxPlayers = 4),
                GamingPackage(id = "dual-60", code = "dual-60", stationType = "ps5",
                    variant = "dual", kind = "base", name = "Dual 60",
                    durationMinutes = 60, priceMinor = 15_000, includedPlayers = 2, maxPlayers = 4),
            ),
            customers = listOf(GamingCustomerOption("customer-fixture", "Retained Customer", "0501234567", false)),
            everSynced = true, refreshing = false, online = true,
            activeShiftId = null, activeShiftServerConfirmed = false,
        )
        val viewportHeight = mutableStateOf(744.dp)
        var submittedFloat: Long? = null
        var starts = 0
        lateinit var focus: FocusManager
        var keyboard: SoftwareKeyboardController? = null
        compose.setContent {
            DCompanyTheme {
                focus = LocalFocusManager.current
                keyboard = LocalSoftwareKeyboardController.current
                // MainActivity's 1280x800/160dpi viewport: status 24 + navigation 32.
                // GamingCommandWorkspace supplies the real alarm/readiness notice rail.
                Box(Modifier.fillMaxWidth().height(viewportHeight.value)) {
                    WorkspaceScaffold(
                        destinations = listOf(Destination.Gaming, Destination.Pos, Destination.Shift),
                        currentDestination = Destination.Gaming,
                        employeeName = "Fixture owner", locationLabel = "Gaming Centre",
                        connectivityProblem = SyncAvailabilityProblem.NONE,
                        outboxWorkStatus = OutboxWorkStatus(), syncing = false, canChangeTill = false,
                        onOpenSupport = {}, onChangeTill = {}, onSignOut = {},
                    ) { _, _ ->
                        GamingCommandWorkspace(
                            state = state, access = GamingAccess(canManageSessions = true),
                            filters = listOf(StationFilter("all", "All"), StationFilter("ps5", "PS5")),
                            selectedFilter = "all", visibleStations = stations, selectedStationId = "ps5-2",
                            showDetail = false, focusStationId = null, focusRequested = false,
                            startTerminalBlockMessage = null,
                            wallClock = remember { mutableLongStateOf(1_779_989_200_000L) }, attentionCount = 0,
                            onSelectFilter = {}, onSelectStation = {}, onBackToBoard = {}, onOpenAttention = {},
                            onRefresh = {}, onStart = {}, onConfirmStart = { _, _, _, _, _, _, _, _ -> starts++ },
                            onStop = { _, _ -> }, onSend = {}, onCancelUnbilled = {}, onExtendTimer = {},
                            onExtendPackage = { _, _ -> }, onTransfer = {}, onPauseResume = {}, onReconcile = {},
                            onRepairBilling = {}, onResolveLegacyStart = {}, onDiscardPackageExtension = {},
                            onAddItems = {}, onVoidAddon = { _, _ -> }, onReviewRejectedAddon = {},
                            onManageParticipants = {}, shiftAccess = ShiftAccess(canOpen = true),
                            inlineShiftContent = { access, onReturn ->
                                InlineShiftOpeningContent(
                                    ShiftUiState(currentShiftLoaded = true, online = true), access,
                                    onOpen = { submittedFloat = it }, onSync = {}, onReturn = onReturn,
                                )
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("gaming-notice-rail").assertIsDisplayed()
        compose.onNodeWithText("Dual", substring = true).performScrollTo().performClick()
        repeat(2) {
            val icon = compose.onNodeWithContentDescription("Add player")
            if (runCatching { icon.fetchSemanticsNode() }.isSuccess) icon.performScrollTo().performClick()
            else compose.onNodeWithText("Add player").performScrollTo().performClick()
        }
        // The hour is revealed only after clockwise travel beyond thirty.
        compose.onNodeWithContentDescription("Choose 60 minutes, ₹210.00").assertDoesNotExist()
        compose.onNodeWithContentDescription("Session duration dial").performTouchInput {
            down(Offset(width * 0.50f, height * 0.90f))
            repeat(12) { step ->
                val angle = Math.toRadians((90f + (step + 1) * 15f).toDouble())
                moveTo(Offset(width * (0.5f + 0.4f * kotlin.math.cos(angle).toFloat()),
                    height * (0.5f + 0.4f * kotlin.math.sin(angle).toFloat())), delayMillis = 16)
            }
            up()
        }
        compose.onNodeWithContentDescription("Choose 60 minutes, ₹210.00").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search saved customers by name or phone")
            .performScrollTo().performTextInput("Retained")
        compose.onNodeWithText("Retained Customer").performScrollTo().performClick()
        compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() }
        compose.onNodeWithText("Start 60 min · ₹210.00").assertIsDisplayed().assertIsNotEnabled()
        val ring = compose.onNodeWithTag("gaming-control-ring").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("gaming-primary-action-host").getUnclippedBoundsInRoot()
        assertEquals(320f, (ring.right - ring.left).value, 1f)
        compose.onNodeWithTag("open-shift-here").performClick()
        compose.onNodeWithText("Shift · same workspace").assertIsDisplayed()
        // Fails on the old appended form: the customer/options still occupy its bounded pane.
        compose.onNodeWithText("Retained Customer").assertDoesNotExist()
        val openingTitle = compose.onNodeWithText("Open shift").performScrollTo()
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Open shift must remain a readable horizontal label: $openingTitle",
            (openingTitle.right - openingTitle.left).value >= 64f &&
                (openingTitle.bottom - openingTitle.top).value <= 32f)
        compose.onNodeWithContentDescription("Search saved customers by name or phone").assertDoesNotExist()
        assertEquals(ring, compose.onNodeWithTag("gaming-control-ring").getUnclippedBoundsInRoot())
        assertEquals(footer, compose.onNodeWithTag("gaming-primary-action-host").getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("Opening float (₹), zero")
            .performScrollTo().assertIsDisplayed().performClick().performTextInput("25")
        // Also constrain the shell to an IME-sized viewport, independently of emulator IME choice.
        compose.runOnIdle { viewportHeight.value = 480.dp }
        compose.onNodeWithContentDescription("Opening float (₹), 25")
            .performScrollTo().assertIsDisplayed().performImeAction()
        compose.runOnIdle { keyboard?.hide(); viewportHeight.value = 744.dp }
        compose.onNodeWithText("Open shift with ₹25.00").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2_500L, submittedFloat); assertEquals(0, starts) }
        compose.onNodeWithText("Open shift confirmed", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Return to work").performScrollTo().performClick()
        compose.onNodeWithText("Retained Customer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dual 4").performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("Add player").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("2 extra controllers · ₹60.00 surcharge").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start 60 min · ₹210.00").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(ring, compose.onNodeWithTag("gaming-control-ring").getUnclippedBoundsInRoot())
        assertEquals(footer, compose.onNodeWithTag("gaming-primary-action-host").getUnclippedBoundsInRoot())
    }
}
