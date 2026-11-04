package cloud.dcompany.erp.ui.screens.gaming

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
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
import org.json.JSONArray
import org.json.JSONObject

/** Real production shell/form with an in-memory command callback; no database writes. */
class GamingInlineShiftWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectedWorkspaceExpansionRecoversFromLowFirstAndResetsForRealConfiguration() {
        val measuredHeight = mutableStateOf(208.dp)
        val stationId = mutableStateOf("ps5-2")
        val screenHeightDp = mutableIntStateOf(800)
        val dropImmediatelyAfterCommittedGrowth = mutableStateOf(false)
        compose.setContent {
            val stableHeight = rememberGamingStableInlineExpansionHeight(
                selectedStationId = stationId.value,
                screenWidthDp = 1_280,
                screenHeightDp = screenHeightDp.intValue,
                orientation = 2,
                fontScale = 1f,
                measuredHeight = measuredHeight.value,
            )
            SideEffect {
                if (dropImmediatelyAfterCommittedGrowth.value && measuredHeight.value == 240.dp) {
                    measuredHeight.value = 208.dp
                }
            }
            Column {
                Box(Modifier.fillMaxWidth().height(stableHeight).testTag("stable-expansion-probe"))
                Box(
                    Modifier.fillMaxWidth()
                        .height(gamingInlineDialSize(screenHeightDp.intValue, stableHeight))
                        .testTag("stable-dial-probe"),
                )
            }
        }
        val probe = compose.onNodeWithTag("stable-expansion-probe")
        val dialProbe = compose.onNodeWithTag("stable-dial-probe")
        probe.assertHeightIsEqualTo(208.dp)
        dialProbe.assertHeightIsEqualTo(196.dp)

        // Recreated/first composed under an IME-sized parent can recover upward.
        compose.runOnIdle { measuredHeight.value = 320.dp }
        probe.assertHeightIsEqualTo(320.dp)
        dialProbe.assertHeightIsEqualTo(248.dp)
        // The same station/configuration cannot shrink again with transient IME height.
        compose.runOnIdle { measuredHeight.value = 208.dp }
        probe.assertHeightIsEqualTo(320.dp)
        dialProbe.assertHeightIsEqualTo(248.dp)

        // A real configuration change resets the responsive maximum even while compact.
        compose.runOnIdle { screenHeightDp.intValue = 563 }
        probe.assertHeightIsEqualTo(208.dp)
        dialProbe.assertHeightIsEqualTo(196.dp)
        // A committed growth followed immediately by a shrink, without an intervening
        // idle boundary, must retain the committed high-water measurement.
        compose.runOnIdle {
            dropImmediatelyAfterCommittedGrowth.value = true
            measuredHeight.value = 240.dp
        }
        probe.assertHeightIsEqualTo(240.dp)
        dialProbe.assertHeightIsEqualTo(196.dp)
        compose.runOnIdle { assertEquals(208.dp, measuredHeight.value) }

        // Selecting another station starts from its actual current measurement.
        compose.runOnIdle { stationId.value = "ps5-3" }
        probe.assertHeightIsEqualTo(208.dp)
        dialProbe.assertHeightIsEqualTo(196.dp)
    }

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
        assertPermissionNoticeMatchesActualState()
        val draftCheckpoints = JSONArray()
        recordDraftCheckpoint(draftCheckpoints, "initial")
        val dual = compose.onNode(
            hasText("Dual") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-control-pane")),
        )
        revealShiftDetailTarget(dual, "Dual package mode")
        dual.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.waitForIdle()
        recordDraftCheckpoint(draftCheckpoints, "after_dual")
        compose.onNodeWithContentDescription("Choose 30 minutes, 100.00")
            .assertIsDisplayed().assertIsSelected()
        repeat(2) { index ->
            val addPlayer = compose.onNode(
                hasContentDescription("Add player") and hasClickAction() and
                    hasAnyAncestor(hasTestTag("gaming-control-pane")),
            )
            revealShiftDetailTarget(addPlayer, "Add player ${index + 1}")
            addPlayer.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
            compose.waitForIdle()
            recordDraftCheckpoint(draftCheckpoints, "after_add_${index + 1}")
            compose.onNode(
                hasText("Dual ${index + 3}") and hasClickAction() and
                    hasAnyAncestor(hasTestTag("gaming-control-pane")),
            ).assertIsDisplayed().assertIsSelected()
            compose.onNodeWithContentDescription(
                if (index == 0) "Choose 30 minutes, 130.00"
                else "Choose 30 minutes, 160.00",
            ).assertIsDisplayed().assertIsSelected()
        }
        compose.onNode(
            hasContentDescription("Add player") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-control-pane")),
        ).assertIsDisplayed().assertIsNotEnabled()
        // The hour is revealed only after clockwise travel beyond thirty.
        compose.onNodeWithContentDescription("Choose 60 minutes, 210.00").assertDoesNotExist()
        compose.onNodeWithTag("gaming-station-board")
            .performScrollToNode(hasTestTag("gaming-inline-expansion-ps5-2"))
        compose.waitForIdle()
        recordDraftCheckpoint(draftCheckpoints, "after_board_rematerialization")
        compose.onNode(
            hasText("Dual 4") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-control-pane")),
        ).assertIsDisplayed().assertIsSelected()
        compose.onAllNodesWithTag("gaming-control-ring").assertCountEquals(1)
        val boardNode = compose.onNodeWithTag("gaming-station-board").assertIsDisplayed()
        val ringNode = compose.onNodeWithTag("gaming-control-ring")
        revealShiftRingWithOuterBoard(boardNode, ringNode)
        ringNode.assertIsDisplayed()
        compose.waitForIdle()
        recordDraftCheckpoint(draftCheckpoints, "before_gesture")
        val visibleRoot = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val visibleBoard = compose.onNodeWithTag("gaming-station-board").fetchSemanticsNode().boundsInRoot
        val visibleExpansion = compose.onNodeWithTag("gaming-inline-expansion-ps5-2")
            .fetchSemanticsNode().boundsInRoot
        val visiblePane = compose.onNodeWithTag("gaming-control-pane")
            .fetchSemanticsNode().boundsInRoot
        val permanentRingNodeId = ringNode.fetchSemanticsNode().id
        val ring = ringNode.getUnclippedBoundsInRoot()
        val visibleRing = ringNode.fetchSemanticsNode().boundsInRoot
        val rootDp = compose.onRoot().getUnclippedBoundsInRoot()
        val density = visibleRoot.width / (rootDp.right - rootDp.left).value
        val unclippedRingPx = listOf(
            ring.left.value * density,
            ring.top.value * density,
            ring.right.value * density,
            ring.bottom.value * density,
        )
        listOf(visibleRing.left, visibleRing.top, visibleRing.right, visibleRing.bottom)
            .zip(unclippedRingPx)
            .forEachIndexed { index, (visible, unclipped) ->
                assertTrue(
                    "Ring edge $index must be fully unclipped after its explicit reveal: visible=$visible unclipped=$unclipped",
                    kotlin.math.abs(visible - unclipped) <= 1f,
                )
            }
        assertTrue("Ring must be a non-empty square before the physical gesture: $visibleRing",
            visibleRing.width > 0f && kotlin.math.abs(visibleRing.width - visibleRing.height) <= 1f)
        val visibleGeometry = compose.onNodeWithTag("gaming-control-ring-geometry")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        listOf(visibleGeometry.left, visibleGeometry.top, visibleGeometry.right, visibleGeometry.bottom)
            .zip(listOf(visibleRing.left, visibleRing.top, visibleRing.right, visibleRing.bottom))
            .forEachIndexed { index, (canvasEdge, wrapperEdge) ->
                assertTrue(
                    "Ring Canvas edge $index must match the measured wrapper: canvas=$visibleGeometry wrapper=$visibleRing",
                    kotlin.math.abs(canvasEdge - wrapperEdge) <= 1f,
                )
            }
        listOf(visibleRoot, visibleBoard, visibleExpansion, visiblePane).forEach { viewport ->
            assertTrue("Ring must be fully exposed before the physical gesture: ring=$visibleRing viewport=$viewport",
                visibleRing.left >= viewport.left - 1f && visibleRing.top >= viewport.top - 1f &&
                    visibleRing.right <= viewport.right + 1f && visibleRing.bottom <= viewport.bottom + 1f)
        }
        val ringHost = compose.onNodeWithTag("gaming-ring-host")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val ringHostUnclipped = compose.onNodeWithTag("gaming-ring-host")
            .getUnclippedBoundsInRoot()
        val initialRingOffsetInHost = Offset(
            (ring.left - ringHostUnclipped.left).value,
            (ring.top - ringHostUnclipped.top).value,
        )
        fun assertPermanentRingGeometry(requireFullContainment: Boolean) {
            compose.onAllNodesWithTag("gaming-control-ring").assertCountEquals(1)
            val currentRing = compose.onNodeWithTag("gaming-control-ring")
            val currentNode = currentRing.fetchSemanticsNode()
            assertEquals(
                "The permanent station-keyed dial must retain the same semantics node across modes and resize",
                permanentRingNodeId,
                currentNode.id,
            )
            val currentBounds = currentRing.getUnclippedBoundsInRoot()
            assertEquals((ring.right - ring.left).value, (currentBounds.right - currentBounds.left).value, 1f)
            assertEquals((ring.bottom - ring.top).value, (currentBounds.bottom - currentBounds.top).value, 1f)
            assertEquals(
                (currentBounds.right - currentBounds.left).value,
                (currentBounds.bottom - currentBounds.top).value,
                1f,
            )
            val currentCanvas = compose.onNodeWithTag("gaming-control-ring-geometry")
                .getUnclippedBoundsInRoot()
            listOf(currentCanvas.left, currentCanvas.top, currentCanvas.right, currentCanvas.bottom)
                .zip(listOf(currentBounds.left, currentBounds.top, currentBounds.right, currentBounds.bottom))
                .forEachIndexed { index, (canvasEdge, ringEdge) ->
                    assertEquals("Ring Canvas edge $index must follow its responsive wrapper", ringEdge.value, canvasEdge.value, 1f)
                }
            val currentHost = compose.onNodeWithTag("gaming-ring-host").getUnclippedBoundsInRoot()
            assertEquals(
                initialRingOffsetInHost.x,
                (currentBounds.left - currentHost.left).value,
                1f,
            )
            assertEquals(
                initialRingOffsetInHost.y,
                (currentBounds.top - currentHost.top).value,
                1f,
            )
            if (requireFullContainment) {
                assertTrue(
                    "The restored permanent ring must be fully contained after explicit reveal: " +
                        shiftRingGeometry(currentRing),
                    shiftRingGeometry(currentRing).inside,
                )
            }
        }
        val forecast = compose.onNodeWithContentDescription(
            "Expected finish if started now",
            substring = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val identityZone = compose.onNodeWithTag(
            "gaming-selected-identity-zone",
            useUnmergedTree = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot(useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected finish must stay inside the selected identity zone: " +
                "forecast=$forecast identity=$identityZone",
            forecast.left >= identityZone.left - 1f && forecast.top >= identityZone.top - 1f &&
                forecast.right <= identityZone.right + 1f &&
                forecast.bottom <= identityZone.bottom + 1f,
        )
        assertTrue(
            "The selected identity zone must stay inside the real root: " +
                "identity=$identityZone root=$rootBounds",
            identityZone.left >= rootBounds.left - 1f && identityZone.top >= rootBounds.top - 1f &&
                identityZone.right <= rootBounds.right + 1f &&
                identityZone.bottom <= rootBounds.bottom + 1f,
        )
        assertTrue(
            "Expected finish in the identity zone must not overlap the permanent ring: " +
                "forecast=$forecast ring=$visibleRing",
            forecast.right <= visibleRing.left + 1f || forecast.left >= visibleRing.right - 1f ||
                forecast.bottom <= visibleRing.top + 1f || forecast.top >= visibleRing.bottom - 1f,
        )
        assertPermanentRingGeometry(requireFullContainment = true)
        var touchInputWidth = 0
        var touchInputHeight = 0
        compose.onNodeWithContentDescription("Session duration dial").performTouchInput {
            touchInputWidth = width
            touchInputHeight = height
            down(Offset(width * 0.50f, height * 0.90f))
            repeat(12) { step ->
                val angle = Math.toRadians((90f + (step + 1) * 15f).toDouble())
                moveTo(Offset(width * (0.5f + 0.4f * kotlin.math.cos(angle).toFloat()),
                    height * (0.5f + 0.4f * kotlin.math.sin(angle).toFloat())), delayMillis = 16)
            }
            up()
        }
        compose.waitForIdle()
        recordDraftCheckpoint(draftCheckpoints, "after_gesture")
        capturePostGestureDiagnostic(touchInputWidth, touchInputHeight, draftCheckpoints)
        compose.onNodeWithContentDescription("Choose 60 minutes, 210.00")
            .assertIsDisplayed().assertIsSelected()
        val customerSearch = compose.onNodeWithContentDescription(
            "Search saved customers by name or phone",
        )
        revealShiftDetailTarget(customerSearch, "Saved-customer search")
        customerSearch.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performTextInput("Retained")
        val retainedCustomer = compose.onNode(
            hasText("Retained Customer") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-control-pane")),
        )
        revealShiftDetailTarget(retainedCustomer, "Retained Customer result")
        retainedCustomer.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() }
        val start = compose.onNodeWithTag("gaming-start-action")
        revealShiftDetailTarget(start, "Blocked Start")
        start.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            .assertIsNotEnabled().assertTextEquals("Start 60 min · 210.00")
        val initialActionHost = compose.onNodeWithTag("gaming-primary-action-host")
            .getUnclippedBoundsInRoot()
        assertTrue("Start must be contained in its visible action host: start=${start.getUnclippedBoundsInRoot()} host=$initialActionHost",
            start.getUnclippedBoundsInRoot().let { target ->
                target.left >= initialActionHost.left - 1.dp && target.top >= initialActionHost.top - 1.dp &&
                    target.right <= initialActionHost.right + 1.dp &&
                    target.bottom <= initialActionHost.bottom + 1.dp
            })
        val openShiftHere = compose.onNodeWithTag("open-shift-here")
        revealShiftDetailTarget(openShiftHere, "Open shift here")
        openShiftHere.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Shift · same workspace").assertIsDisplayed()
        // Fails on the old appended form: the customer/options still occupy its bounded pane.
        compose.onNodeWithText("Retained Customer").assertDoesNotExist()
        val openingTitleNode = compose.onNodeWithText("Open shift")
        revealShiftDetailTarget(openingTitleNode, "Open shift title")
        val openingTitle = openingTitleNode.assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Open shift must remain a readable horizontal label: $openingTitle",
            (openingTitle.right - openingTitle.left).value >= 64f &&
                (openingTitle.bottom - openingTitle.top).value <= 32f)
        compose.onNodeWithContentDescription("Search saved customers by name or phone").assertDoesNotExist()
        assertPermanentRingGeometry(requireFullContainment = false)
        compose.runOnIdle { assertEquals(0, starts) }
        val openingFloat = compose.onNodeWithContentDescription("Opening float, zero")
        revealShiftDetailTarget(openingFloat, "Opening float")
        openingFloat.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .performClick().performTextInput("25")
        // Also constrain the shell to an IME-sized viewport, independently of emulator IME choice.
        compose.runOnIdle { viewportHeight.value = 480.dp }
        val populatedFloat = compose.onNodeWithContentDescription("Opening float, 25")
        revealShiftDetailTarget(populatedFloat, "Populated opening float")
        populatedFloat.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performImeAction()
        assertPermanentRingGeometry(requireFullContainment = false)
        compose.runOnIdle { keyboard?.hide(); viewportHeight.value = 744.dp }
        val submitShift = compose.onNode(
            hasText("Open shift with 25.00") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-control-pane")),
        )
        revealShiftDetailTarget(submitShift, "Open shift submit")
        submitShift.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(2_500L, submittedFloat); assertEquals(0, starts) }
        compose.onNodeWithText("Open shift confirmed", substring = true).assertDoesNotExist()
        val returnToWork = compose.onNode(
            hasText("Return to work") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-control-pane")),
        )
        revealShiftDetailTarget(returnToWork, "Return to work")
        returnToWork.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        val returnedRing = compose.onNodeWithTag("gaming-control-ring")
        revealShiftRingWithOuterBoard(boardNode, returnedRing)
        assertPermanentRingGeometry(requireFullContainment = true)
        compose.onNodeWithContentDescription("Choose 60 minutes, 210.00")
            .assertIsDisplayed().assertIsSelected()
        val retainedSelection = compose.onNodeWithText("Retained Customer")
        revealShiftDetailTarget(retainedSelection, "Retained customer selection")
        retainedSelection.assertIsDisplayed()
        val retainedDual = compose.onNode(
            hasText("Dual 4") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-control-pane")),
        )
        revealShiftDetailTarget(retainedDual, "Retained Dual 4")
        retainedDual.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertIsSelected()
        val retainedAdd = compose.onNodeWithContentDescription("Add player")
        revealShiftDetailTarget(retainedAdd, "Retained disabled Add player")
        retainedAdd.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
        val retainedSurcharge = compose.onNodeWithText("2 extra controllers · 60.00 surcharge")
        revealShiftDetailTarget(retainedSurcharge, "Retained controller surcharge")
        retainedSurcharge.assertIsDisplayed()
        val retainedStart = compose.onNodeWithTag("gaming-start-action")
        revealShiftDetailTarget(retainedStart, "Retained blocked Start")
        retainedStart.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            .assertIsNotEnabled().assertTextEquals("Start 60 min · 210.00")
        val returnedActionHost = compose.onNodeWithTag("gaming-primary-action-host")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "Returned Start must be contained in its current natural-flow action host: " +
                "start=${retainedStart.getUnclippedBoundsInRoot()} host=$returnedActionHost",
            retainedStart.getUnclippedBoundsInRoot().let { target ->
                target.left >= returnedActionHost.left - 1.dp &&
                    target.top >= returnedActionHost.top - 1.dp &&
                    target.right <= returnedActionHost.right + 1.dp &&
                    target.bottom <= returnedActionHost.bottom + 1.dp
            },
        )
        assertPermanentRingGeometry(requireFullContainment = false)
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test
    fun nonScrollingPinnedDetailUsesOuterBoardToRevealAndActivateClippedTarget() {
        var activated = false
        compose.setContent {
            DCompanyTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val top = (maxHeight - 160.dp).coerceAtLeast(0.dp)
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(top))
                        LazyColumn(
                            Modifier.fillMaxWidth().requiredHeight(400.dp)
                                .testTag("gaming-station-board"),
                        ) {
                            item {
                                Box(Modifier.fillMaxWidth().height(900.dp)
                                    .testTag("gaming-inline-expansion-ps5-2")) {
                                    Box(Modifier.fillMaxSize().testTag("gaming-control-pane")) {
                                        Box(Modifier.fillMaxSize().testTag("gaming-detail-viewport")) {
                                            Column(Modifier.fillMaxSize()
                                                .testTag("gaming-detail-scroll-owner")) {
                                                Spacer(Modifier.height(480.dp))
                                                Button(onClick = { activated = true }, modifier = Modifier.height(48.dp)) {
                                                    Text("Clipped customer result")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val detail = compose.onNodeWithTag("gaming-detail-scroll-owner")
        assertEquals(null, detail.fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
        val target = compose.onNodeWithText("Clipped customer result")
        target.performScrollTo()
        compose.waitForIdle()
        val rootPx = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val rootDp = compose.onRoot().getUnclippedBoundsInRoot()
        val density = rootPx.width / (rootDp.right - rootDp.left).value
        val initiallyClipped = target.getUnclippedBoundsInRoot()
        assertTrue("Nearest-owner scrolling must leave this target clipped by the root",
            initiallyClipped.bottom.value * density > rootPx.bottom + 1f ||
                initiallyClipped.top.value * density < rootPx.top - 1f)
        val boardBefore = compose.onNodeWithTag("gaming-station-board").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        revealShiftDetailTarget(target, "Non-scrolling pinned detail target")
        target.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertTrue(activated) }
        assertTrue(compose.onNodeWithTag("gaming-station-board").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > boardBefore)
    }

    @Test
    fun topAndLeftClippedBoardReceivesVisibleSwipeAndActuallyScrolls() {
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    LazyColumn(Modifier.offset(x = (-160).dp, y = (-200).dp)
                        .width(320.dp).height(400.dp).testTag("clipped-board")) {
                        items(30) { index -> Text("Row $index", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        val board = compose.onNodeWithTag("clipped-board")
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val unclipped = board.getUnclippedBoundsInRoot()
        assertTrue("The regression must clip both the top and left", unclipped.top < 0.dp && unclipped.left < 0.dp)
        val range = board.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val before = range.value()
        swipeVisibleBoard(board, root, moveContentUp = true, diagnostic = "Top/left-clipped board")
        compose.waitUntil(5_000) {
            board.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > before
        }
    }

    private fun swipeVisibleBoard(
        board: SemanticsNodeInteraction,
        rootPx: Rect,
        moveContentUp: Boolean,
        diagnostic: String,
    ) {
        val boardBounds = board.fetchSemanticsNode().boundsInRoot
        val gestureBounds = Rect(
            maxOf(rootPx.left, boardBounds.left),
            maxOf(rootPx.top, boardBounds.top),
            minOf(rootPx.right, boardBounds.right),
            minOf(rootPx.bottom, boardBounds.bottom),
        )
        assertTrue("$diagnostic requires a visible board gesture viewport: $gestureBounds",
            gestureBounds.width > 0f && gestureBounds.height > 0f)
        // Compose 1.7.5 injects local coordinates from the clipped semantics
        // origin. An unclipped origin misroutes touches on top/left clipping.
        val gestureX = gestureBounds.left + gestureBounds.width * 0.06f - boardBounds.left
        val gestureTop = gestureBounds.top - boardBounds.top
        val gestureHeight = gestureBounds.height
        board.performTouchInput {
            swipe(
                start = Offset(gestureX, gestureTop + gestureHeight *
                    if (moveContentUp) 0.86f else 0.14f),
                end = Offset(gestureX, gestureTop + gestureHeight *
                    if (moveContentUp) 0.14f else 0.86f),
                durationMillis = 420,
            )
        }
    }

    private fun revealShiftDetailTarget(
        target: SemanticsNodeInteraction,
        diagnostic: String,
    ) {
        // The detail owner is the first scroll ancestor for every draft and
        // inline-shift control. Ask it to reveal the exact clickable owner,
        // then move the outer board only if the whole detail viewport remains
        // clipped by the station list.
        target.performScrollTo()
        compose.waitForIdle()
        repeat(4) {
            val root = compose.onRoot()
            val rootPx = root.fetchSemanticsNode().boundsInRoot
            val rootDp = root.getUnclippedBoundsInRoot()
            val density = rootPx.width / (rootDp.right - rootDp.left).value
            val targetDp = target.getUnclippedBoundsInRoot()
            val targetPx = Rect(
                targetDp.left.value * density,
                targetDp.top.value * density,
                targetDp.right.value * density,
                targetDp.bottom.value * density,
            )
            val detail = compose.onNodeWithTag("gaming-detail-scroll-owner")
            val detailBounds = detail.fetchSemanticsNode().boundsInRoot
            val visible = listOf(
                rootPx,
                compose.onNodeWithTag("gaming-station-board").fetchSemanticsNode().boundsInRoot,
                compose.onNodeWithTag("gaming-inline-expansion-ps5-2")
                    .fetchSemanticsNode().boundsInRoot,
                compose.onNodeWithTag("gaming-control-pane").fetchSemanticsNode().boundsInRoot,
                compose.onNodeWithTag("gaming-detail-viewport").fetchSemanticsNode().boundsInRoot,
                detailBounds,
            ).reduce { left, right -> Rect(
                maxOf(left.left, right.left),
                maxOf(left.top, right.top),
                minOf(left.right, right.right),
                minOf(left.bottom, right.bottom),
            ) }
            val inside = targetPx.width > 0f && targetPx.height > 0f &&
                targetPx.left >= visible.left - 1f && targetPx.top >= visible.top - 1f &&
                targetPx.right <= visible.right + 1f && targetPx.bottom <= visible.bottom + 1f
            if (inside) return

            // Pinned-action layouts deliberately leave this container non-scrollable.
            // Its outer board still has to reveal the fully clipped target.
            val detailRange = detail.fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            val detailBefore = detailRange?.value() ?: 0f
            val belowDetail = targetPx.bottom > detailBounds.bottom + 1f
            val aboveDetail = targetPx.top < detailBounds.top - 1f
            val detailCanMove = detailRange?.let { range ->
                if (belowDetail) range.maxValue() > detailBefore
                else aboveDetail && detailBefore > 0f
            } ?: false
            if (detailCanMove) {
                detail.performTouchInput {
                    swipe(
                        start = Offset(width * 0.82f, height * if (belowDetail) 0.86f else 0.14f),
                        end = Offset(width * 0.82f, height * if (belowDetail) 0.14f else 0.86f),
                        durationMillis = 420,
                    )
                }
                compose.waitUntil(5_000) {
                    val after = detail.fetchSemanticsNode()
                        .config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value()
                    after != null && if (belowDetail) after > detailBefore else after < detailBefore
                }
                return@repeat
            }

            val board = compose.onNodeWithTag("gaming-station-board")
            val boardRange = board.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            val boardBefore = boardRange.value()
            val moveContentUp = targetPx.bottom > visible.bottom + 1f
            val moveContentDown = targetPx.top < visible.top - 1f
            val boardCanMove = if (moveContentUp) {
                boardRange.maxValue() > boardBefore
            } else if (moveContentDown) {
                boardBefore > 0f
            } else false
            if (!boardCanMove) {
                captureShiftRevealFailure(ShiftRingGeometry(targetPx, visible, false), boardRange)
                error("$diagnostic cannot move a required scroll owner: target=$targetPx visible=$visible " +
                    "detail=$detailRange board=$boardRange")
            }
            swipeVisibleBoard(board, rootPx, moveContentUp, diagnostic)
            compose.waitUntil(5_000) {
                val after = board.fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()
                if (moveContentUp) after > boardBefore else after < boardBefore
            }
        }
        error("$diagnostic remained clipped after bounded detail and board movement: " +
            "target=${target.getUnclippedBoundsInRoot()} root=${compose.onRoot().getUnclippedBoundsInRoot()}")
    }

    private fun revealShiftRingWithOuterBoard(
        board: androidx.compose.ui.test.SemanticsNodeInteraction,
        ring: androidx.compose.ui.test.SemanticsNodeInteraction,
    ) {
        repeat(4) {
            val geometry = shiftRingGeometry(ring)
            if (geometry.inside) return
            val range = board.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val before = range.value()
            val moveContentUp = geometry.target.bottom > geometry.visible.bottom + 1f
            val moveContentDown = geometry.target.top < geometry.visible.top - 1f
            if (!moveContentUp && !moveContentDown) {
                captureShiftRevealFailure(geometry, range)
                error("Shift ring has no valid reveal direction: $geometry range=$range")
            }
            val canMove = if (moveContentUp) range.maxValue() > before else before > 0f
            if (!canMove) {
                captureShiftRevealFailure(geometry, range)
                error("Shift ring outer station-board owner cannot move: $geometry range=$range")
            }
            val missingPx = if (moveContentUp) {
                geometry.target.bottom - geometry.visible.bottom
            } else {
                geometry.visible.top - geometry.target.top
            }
            val touchSlopPx = ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
            board.performTouchInput {
                // Move only the missing distance plus touch slop, then hold at
                // the end so velocity tracking cannot fling past the narrow
                // fully-visible window. Stay in the station-label gutter so
                // the right detail scroll owner cannot consume this gesture.
                val distance = (missingPx + touchSlopPx + 2f)
                    .coerceAtLeast(touchSlopPx + 2f)
                    .coerceAtMost(height * 0.68f)
                val start = Offset(
                    x = width * 0.06f,
                    y = height * if (moveContentUp) 0.84f else 0.16f,
                )
                val end = Offset(
                    x = start.x,
                    y = start.y + if (moveContentUp) -distance else distance,
                )
                down(start)
                moveTo(end, delayMillis = 240)
                advanceEventTime(160L)
                up()
            }
            compose.waitUntil(5_000) {
                val after = board.fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()
                if (moveContentUp) after > before else after < before
            }
            val after = board.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(
                "Shift ring must move the outer station-board owner in the required direction: " +
                    "up=$moveContentUp before=$before after=$after",
                if (moveContentUp) after > before else after < before,
            )
        }
        val geometry = shiftRingGeometry(ring)
        if (!geometry.inside) {
            val range = board.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            captureShiftRevealFailure(geometry, range)
            error("Shift ring remained clipped after bounded outer-owner movement: $geometry range=$range")
        }
    }

    private fun shiftRingGeometry(
        ring: androidx.compose.ui.test.SemanticsNodeInteraction,
    ): ShiftRingGeometry {
        val rootNode = compose.onRoot()
        val rootPx = rootNode.fetchSemanticsNode().boundsInRoot
        val rootDp = rootNode.getUnclippedBoundsInRoot()
        val density = rootPx.width / (rootDp.right - rootDp.left).value
        val ringDp = ring.getUnclippedBoundsInRoot()
        val target = Rect(
            ringDp.left.value * density,
            ringDp.top.value * density,
            ringDp.right.value * density,
            ringDp.bottom.value * density,
        )
        val visible = listOf(
            rootPx,
            compose.onNodeWithTag("gaming-station-board").fetchSemanticsNode().boundsInRoot,
            compose.onNodeWithTag("gaming-inline-expansion-ps5-2").fetchSemanticsNode().boundsInRoot,
            compose.onNodeWithTag("gaming-control-pane").fetchSemanticsNode().boundsInRoot,
        ).reduce { left, right -> Rect(
            maxOf(left.left, right.left), maxOf(left.top, right.top),
            minOf(left.right, right.right), minOf(left.bottom, right.bottom),
        ) }
        val inside = target.width > 0f && target.height > 0f &&
            target.left >= visible.left - 1f && target.top >= visible.top - 1f &&
            target.right <= visible.right + 1f && target.bottom <= visible.bottom + 1f
        return ShiftRingGeometry(target, visible, inside)
    }

    private fun assertPermissionNoticeMatchesActualState() {
        val bannerNodes = compose.onAllNodesWithTag(
            "gaming-alarm-permission-banner",
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val railNodes = compose.onAllNodesWithTag(
            "gaming-notice-rail",
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        if (bannerNodes.isEmpty()) {
            assertEquals("ready alarm state must not retain an empty notice rail", 0, railNodes.size)
            return
        }
        assertEquals("degraded alarm state must expose one banner", 1, bannerNodes.size)
        assertEquals(
            "a lone permission warning must use its actual banner owner, not a grouped rail",
            0,
            railNodes.size,
        )
        val banner = compose.onNodeWithTag(
            "gaming-alarm-permission-banner",
            useUnmergedTree = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "real alarm banner must remain inside the root: banner=$banner root=$root",
            banner.width > 0f && banner.height > 0f &&
                banner.left >= root.left - 1f && banner.top >= root.top - 1f &&
                banner.right <= root.right + 1f && banner.bottom <= root.bottom + 1f,
        )
        assertTrue(
            "single permission warning must retain the 19% height cap: banner=$banner root=$root",
            banner.height <= root.height * 0.19f + 1f,
        )
        var recoveryActionCount = 0
        listOf(
            "Allow notifications",
            "Try notifications again",
            "Notification settings",
            "Precise alarms",
        ).forEach { label ->
            val matches = compose.onAllNodesWithText(label).fetchSemanticsNodes()
            if (matches.isNotEmpty()) {
                assertEquals("degraded alarm state must expose one $label action", 1, matches.size)
                val action = compose.onNodeWithText(label)
                    .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "$label must remain inside the real banner: action=$action banner=$banner",
                    action.left >= banner.left - 1f && action.top >= banner.top - 1f &&
                        action.right <= banner.right + 1f && action.bottom <= banner.bottom + 1f,
                )
                recoveryActionCount++
            }
        }
        assertTrue("degraded alarm banner must expose recovery actions", recoveryActionCount > 0)
    }

    private fun captureShiftRevealFailure(
        geometry: ShiftRingGeometry,
        range: androidx.compose.ui.semantics.ScrollAxisRange,
    ) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("phase-b2-gaming-shift-diagnostics-v1")
            .resolve(Process.myPid().toString())
            .apply { check(isDirectory || mkdirs()) }
        val png = directory.resolve("gaming-r7-9-ring-reveal-failure.png")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val json = directory.resolve("gaming-r7-9-ring-reveal-failure.json")
        json.writeText(
            """{"target":"${geometry.target}","visible":"${geometry.visible}","range_value":${range.value()},"range_max":${range.maxValue()},"png":"${png.name}"}""" + "\n",
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("dcompany.gaming.shift.diagnostic", json.absolutePath)
                putString("dcompany.gaming.shift.diagnostic.png", png.absolutePath)
            },
        )
    }

    private fun capturePostGestureDiagnostic(
        touchInputWidth: Int,
        touchInputHeight: Int,
        draftCheckpoints: JSONArray,
    ) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("phase-b2-gaming-shift-diagnostics-v2")
            .resolve(Process.myPid().toString())
            .apply { check(isDirectory || mkdirs()) }
        val png = directory.resolve("gaming-r7-11-post-gesture.png")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }

        fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        fun unclipped(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .getUnclippedBoundsInRoot().toString()
        fun scroll(tag: String): JSONObject {
            val config = compose.onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode().config
            val range = if (config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
                config[SemanticsProperties.VerticalScrollAxisRange]
            } else null
            return JSONObject().apply {
                put("value", range?.value())
                put("max", range?.maxValue())
                put("reverse", range?.reverseScrolling)
            }
        }
        fun markers(description: String): JSONArray = JSONArray().apply {
            compose.onAllNodesWithContentDescription(description, useUnmergedTree = true)
                .fetchSemanticsNodes().forEach { node ->
                    put(JSONObject().apply {
                        put("bounds", node.boundsInRoot.toString())
                        put(
                            "selected",
                            if (node.config.contains(SemanticsProperties.Selected)) {
                                node.config[SemanticsProperties.Selected]
                            } else JSONObject.NULL,
                        )
                    })
                }
        }

        val ringNode = compose.onNodeWithContentDescription("Session duration dial")
            .fetchSemanticsNode()
        val json = directory.resolve("gaming-r7-11-post-gesture.json")
        json.writeText(
            JSONObject().apply {
                put("png", png.name)
                put("touch_input_width_px", touchInputWidth)
                put("touch_input_height_px", touchInputHeight)
                put("root_bounds", compose.onRoot().fetchSemanticsNode().boundsInRoot.toString())
                put("board_bounds", bounds("gaming-station-board").toString())
                put("expansion_bounds", bounds("gaming-inline-expansion-ps5-2").toString())
                put("pane_bounds", bounds("gaming-control-pane").toString())
                put("detail_bounds", bounds("gaming-detail-viewport").toString())
                put("ring_bounds", bounds("gaming-control-ring").toString())
                put("ring_unclipped_bounds_dp", unclipped("gaming-control-ring"))
                put("canvas_bounds", bounds("gaming-control-ring-geometry").toString())
                put("canvas_unclipped_bounds_dp", unclipped("gaming-control-ring-geometry"))
                put(
                    "ring_state_description",
                    if (ringNode.config.contains(SemanticsProperties.StateDescription)) {
                        ringNode.config[SemanticsProperties.StateDescription]
                    } else JSONObject.NULL,
                )
                put("board_scroll", scroll("gaming-station-board"))
                put("pane_scroll", scroll("gaming-control-pane"))
                put("detail_scroll", scroll("gaming-detail-scroll-owner"))
                put("choose_30", markers("Choose 30 minutes, 160.00"))
                put("choose_60", markers("Choose 60 minutes, 210.00"))
                put("draft_checkpoints", draftCheckpoints)
            }.toString(2) + "\n",
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("dcompany.gaming.shift.postgesture", json.absolutePath)
                putString("dcompany.gaming.shift.postgesture.png", png.absolutePath)
            },
        )
    }

    private fun recordDraftCheckpoint(
        checkpoints: JSONArray,
        stage: String,
    ) {
        fun nodeJson(node: androidx.compose.ui.semantics.SemanticsNode): JSONObject =
            JSONObject().apply {
                put("bounds", node.boundsInRoot.toString())
                put(
                    "text",
                    if (node.config.contains(SemanticsProperties.Text)) {
                        node.config[SemanticsProperties.Text].joinToString(" | ") { it.text }
                    } else JSONObject.NULL,
                )
                put(
                    "content_description",
                    if (node.config.contains(SemanticsProperties.ContentDescription)) {
                        node.config[SemanticsProperties.ContentDescription].joinToString(" | ")
                    } else JSONObject.NULL,
                )
                put(
                    "selected",
                    if (node.config.contains(SemanticsProperties.Selected)) {
                        node.config[SemanticsProperties.Selected]
                    } else JSONObject.NULL,
                )
                put("enabled", !node.config.contains(SemanticsProperties.Disabled))
            }
        fun nodesJson(nodes: List<androidx.compose.ui.semantics.SemanticsNode>): JSONArray =
            JSONArray().apply { nodes.forEach { put(nodeJson(it)) } }
        fun scrollJson(tag: String): JSONObject {
            val matches = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            if (matches.isEmpty()) return JSONObject().put("present", false)
            val config = matches.single().config
            val range = if (config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
                config[SemanticsProperties.VerticalScrollAxisRange]
            } else null
            return JSONObject().apply {
                put("present", true)
                put("value", range?.value())
                put("max", range?.maxValue())
            }
        }
        val ringNodes = compose.onAllNodesWithContentDescription(
            "Session duration dial",
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val checkpoint = JSONObject().apply {
            put("stage", stage)
            put(
                "single",
                nodesJson(compose.onAllNodesWithText("Single", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()),
            )
            put(
                "dual",
                nodesJson(compose.onAllNodesWithText("Dual", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()),
            )
            put(
                "add_player",
                nodesJson(compose.onAllNodesWithContentDescription("Add player", useUnmergedTree = true)
                    .fetchSemanticsNodes()),
            )
            put(
                "duration_markers",
                nodesJson(compose.onAllNodesWithContentDescription(
                    "Choose ",
                    substring = true,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()),
            )
            put("ring", nodesJson(ringNodes))
            put("board_scroll", scrollJson("gaming-station-board"))
            put("pane_scroll", scrollJson("gaming-control-pane"))
            put("detail_scroll", scrollJson("gaming-detail-scroll-owner"))
            put(
                "selected_row",
                nodesJson(compose.onAllNodesWithTag("gaming-station-ps5-2", useUnmergedTree = true)
                    .fetchSemanticsNodes()),
            )
            put(
                "expansion",
                nodesJson(compose.onAllNodesWithTag("gaming-inline-expansion-ps5-2", useUnmergedTree = true)
                    .fetchSemanticsNodes()),
            )
        }
        checkpoints.put(checkpoint)
    }

    private data class ShiftRingGeometry(
        val target: Rect,
        val visible: Rect,
        val inside: Boolean,
    )
}
