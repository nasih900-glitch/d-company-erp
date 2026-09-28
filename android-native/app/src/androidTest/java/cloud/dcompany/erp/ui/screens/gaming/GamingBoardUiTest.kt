package cloud.dcompany.erp.ui.screens.gaming

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GamingBoardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hoveringDurationPriceKeepsBookingUnchangedAndMarkerStillSelects() {
        val selected = mutableStateOf("single-30")
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(420.dp).height(420.dp).testTag("dial-hover-fixture")) {
                    GamingDurationDial(
                        choices = listOf(
                            GamingDurationChoice("single-30", 30, 8_000),
                            GamingDurationChoice("single-60", 60, 12_000),
                        ),
                        selectedPackageId = selected.value,
                        onSelect = { selected.value = it },
                        dialSize = 344.dp,
                        showChoiceChips = false,
                    )
                }
            }
        }

        val sixtyMinutePrice = compose.onNodeWithContentDescription("Choose 60 minutes, ₹120.00")
        sixtyMinutePrice.performMouseInput { enter() }
        compose.runOnIdle { assertEquals("single-30", selected.value) }
        val marker = sixtyMinutePrice.fetchSemanticsNode().boundsInRoot
        val fixture = compose.onNodeWithTag("dial-hover-fixture").fetchSemanticsNode().boundsInRoot
        val hovered = compose.onNodeWithTag("dial-hover-fixture").captureToImage().asAndroidBitmap()
        try {
            val left = (marker.left - fixture.left).roundToInt().coerceIn(0, hovered.width)
            val top = (marker.top - fixture.top).roundToInt().coerceIn(0, hovered.height)
            val right = (marker.right - fixture.left).roundToInt().coerceIn(left, hovered.width)
            val bottom = (marker.bottom - fixture.top).roundToInt().coerceIn(top, hovered.height)
            var nearWhite = 0
            var sampled = 0
            for (y in top until bottom step 2) for (x in left until right step 2) {
                val pixel = hovered.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 225 &&
                    android.graphics.Color.green(pixel) > 225 &&
                    android.graphics.Color.blue(pixel) > 225
                ) nearWhite++
                sampled++
            }
            assertTrue("Duration-price hover must not paint a white rectangular highlight",
                sampled > 0 && nearWhite * 20 < sampled)
        } finally {
            hovered.recycle()
        }
        capture("gaming-duration-price-hover.png", "dial-hover-fixture")
        sixtyMinutePrice.performClick()
        compose.runOnIdle { assertEquals("single-60", selected.value) }
    }

    @Test
    fun publishedDurationRingChangesDisplayedPriceAndSubmittedPackageBothWays() {
        val confirmed = mutableListOf<String?>()
        compose.setContent {
            DCompanyTheme {
                StartSessionDialog(
                    station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000),
                    packages = listOf(
                        GamingPackage(
                            id = "single-30", code = "single-30", stationType = "ps5",
                            variant = "single", kind = "base", name = "Single 30 minutes",
                            durationMinutes = 30, priceMinor = 8_000,
                        ),
                        GamingPackage(
                            id = "single-60", code = "single-60", stationType = "ps5",
                            variant = "single", kind = "base", name = "Single 60 minutes",
                            durationMinutes = 60, priceMinor = 12_000,
                        ),
                    ),
                    onDismiss = {},
                    onConfirm = { _, _, _, _, packageId, _ -> confirmed += packageId },
                )
            }
        }
        val dial = compose.onNodeWithContentDescription("Session duration dial")
        dial.performTouchInput { click(Offset(width * 0.83f, height * 0.31f)) }
        compose.onNodeWithText("Start 60 min · ₹120.00").assertIsDisplayed().performClick()
        dial.performTouchInput { click(Offset(width * 0.17f, height * 0.31f)) }
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("single-60", "single-30"), confirmed) }
    }

    @Test
    fun draggingDurationRingBothWaysSelectsOnlyPublishedPackages() {
        val confirmed = mutableListOf<String?>()
        compose.setContent {
            DCompanyTheme {
                StartSessionDialog(
                    station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000),
                    packages = listOf(
                        GamingPackage(
                            id = "single-30", code = "single-30", stationType = "ps5",
                            variant = "single", kind = "base", name = "Single 30 minutes",
                            durationMinutes = 30, priceMinor = 8_000,
                        ),
                        GamingPackage(
                            id = "single-60", code = "single-60", stationType = "ps5",
                            variant = "single", kind = "base", name = "Single 60 minutes",
                            durationMinutes = 60, priceMinor = 12_000,
                        ),
                    ),
                    onDismiss = {},
                    onConfirm = { _, _, _, _, packageId, _ -> confirmed += packageId },
                )
            }
        }
        val dial = compose.onNodeWithContentDescription("Session duration dial")
        dial.performTouchInput {
            swipe(
                start = Offset(width * 0.17f, height * 0.31f),
                end = Offset(width * 0.83f, height * 0.31f),
                durationMillis = 300,
            )
        }
        compose.onNodeWithText("Start 60 min · ₹120.00").assertIsDisplayed().performClick()
        dial.performTouchInput {
            swipe(
                start = Offset(width * 0.83f, height * 0.31f),
                end = Offset(width * 0.17f, height * 0.31f),
                durationMillis = 300,
            )
        }
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("single-60", "single-30"), confirmed) }
    }

    @Test
    fun startSessionFillsTabletAndKeepsPublishedDialAndActionReachable() {
        val station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000)
        val packages = listOf(
            GamingPackage(
                id = "single-30", code = "single-30", stationType = "ps5",
                variant = "single", kind = "base", name = "Single 30 minutes",
                durationMinutes = 30, priceMinor = 8_000,
            ),
            GamingPackage(
                id = "single-60", code = "single-60", stationType = "ps5",
                variant = "single", kind = "base", name = "Single 60 minutes",
                durationMinutes = 60, priceMinor = 12_000,
            ),
        )
        var confirmedPackageId: String? = null
        compose.setContent {
            DCompanyTheme {
                StartSessionDialog(
                    station = station,
                    packages = packages,
                    onDismiss = {},
                    onConfirm = { _, _, _, _, packageId, _ -> confirmedPackageId = packageId },
                )
            }
        }

        compose.onNodeWithText("START SESSION").assertIsDisplayed()
        compose.onNodeWithContentDescription("Session duration dial").assertIsDisplayed()
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
        val width = compose.activity.resources.configuration.screenWidthDp
        if (width >= 1_100) {
            val search = compose.onNodeWithContentDescription(
                "Search saved customers by name or phone",
            ).fetchSemanticsNode().boundsInRoot
            val action = compose.onNodeWithText("Start 30 min · ₹80.00")
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Saved-customer search should fit above Start", search.bottom <= action.top)
        }
        val filename = if (width >= 1_100) {
            "gaming-start-1280x800.png"
        } else {
            "gaming-start-960x600.png"
        }
        val bitmap = compose.onNodeWithTag("gaming-start-dialog").captureToImage().asAndroidBitmap()
        try {
            val output = File(requireNotNull(compose.activity.getExternalFilesDir(null)), filename)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        compose.onNodeWithText("Start 30 min · ₹80.00").performClick()
        compose.runOnIdle { assertEquals("single-30", confirmedPackageId) }
    }

    @Test
    fun stationBoardAndPublishedDurationControlsStayOnSameScreen() {
        val stations = listOf(
            Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000),
            Station("ps5-2", "PS5-2", "PS5 Station 2", "ps5", 12_000),
            Station("ps5-3", "PS5-3", "PS5 Station 3", "ps5", 12_000),
            Station("ps5-4", "PS5-4", "PS5 Station 4", "ps5", 12_000),
            Station("racing-1", "RACE-1", "Racing Simulator 1", "racing", 15_000),
            Station("vr-1", "VR-1", "VR Pod 1", "vr", 12_000),
            Station("shisha-1", "SHISHA-1", "Shisha Table 1", "shisha", 10_000),
            Station("streaming-1", "STREAM-1", "Streaming Booth 1", "streaming", 10_000),
        )
        val packages = listOf(
            GamingPackage(
                id = "single-30", code = "single-30", stationType = "ps5", variant = "single",
                kind = "base", name = "Single 30", durationMinutes = 30, priceMinor = 8_000,
            ),
            GamingPackage(
                id = "single-60", code = "single-60", stationType = "ps5", variant = "single",
                kind = "base", name = "Single 60", durationMinutes = 60, priceMinor = 12_000,
            ),
            GamingPackage(
                id = "dual-30", code = "dual-30", stationType = "ps5", variant = "dual",
                includedPlayers = 2, maxPlayers = 4, kind = "base", name = "Dual 30",
                durationMinutes = 30, priceMinor = 10_000,
            ),
            GamingPackage(
                id = "dual-60", code = "dual-60", stationType = "ps5", variant = "dual",
                includedPlayers = 2, maxPlayers = 4, kind = "base", name = "Dual 60",
                durationMinutes = 60, priceMinor = 15_000,
            ),
            GamingPackage(
                id = "racing-15", code = "racing-15", stationType = "racing",
                variant = "simdrive", kind = "base", name = "Racing 15",
                durationMinutes = 15, priceMinor = 7_000,
            ),
            GamingPackage(
                id = "racing-30", code = "racing-30", stationType = "racing",
                variant = "simdrive", kind = "base", name = "Racing 30",
                durationMinutes = 30, priceMinor = 10_000,
            ),
        )
        val state = mutableStateOf(GamingUiState(
            stations = stations, packages = packages, everSynced = true,
            refreshing = false, activeShiftId = "fixture-shift",
            activeShiftServerConfirmed = true, online = true,
        ))
        val visibleStations = mutableStateOf(stations)
        val selectedId = mutableStateOf("ps5-1")
        var confirmed: Pair<String, String?>? = null
        var confirmedExtraControllers = -1
        var customerPrepareCalls = 0
        compose.setContent {
            DCompanyTheme {
                Surface(Modifier.fillMaxSize(), color = Brand.Background) {
                    Box(Modifier.fillMaxSize().testTag("gaming-fixture-root")) {
                        GamingCommandWorkspace(
                            state = state.value,
                            access = GamingAccess(canManageSessions = true),
                            filters = listOf(
                                StationFilter("all", "All"), StationFilter("ps5", "PS5"),
                                StationFilter("racing", "Racing"), StationFilter("vr", "VR"),
                                StationFilter("streaming", "Streaming"),
                                StationFilter("shisha", "Shisha"),
                            ),
                            selectedFilter = "all", visibleStations = visibleStations.value,
                            selectedStationId = selectedId.value, showDetail = false,
                            focusStationId = null, focusRequested = false,
                            startTerminalBlockMessage = null,
                            wallClock = remember { mutableLongStateOf(1_779_989_200_000L) },
                            attentionCount = 0,
                            onSelectFilter = {}, onSelectStation = { selectedId.value = it },
                            onBackToBoard = {}, onOpenAttention = {}, onRefresh = {},
                            onStart = {},
                            onConfirmStart = { station, _, _, _, _, packageId, extraControllers ->
                                confirmed = station.id to packageId
                                confirmedExtraControllers = extraControllers
                            },
                            onPrepareStart = { customerPrepareCalls++ },
                            onStop = { _, _ -> }, onSend = {}, onCancelUnbilled = {},
                            onExtendTimer = {}, onExtendPackage = { _, _ -> },
                            onTransfer = {}, onPauseResume = {}, onReconcile = {},
                            onRepairBilling = {}, onResolveLegacyStart = {},
                            onDiscardPackageExtension = {}, onAddItems = {},
                            onVoidAddon = { _, _ -> }, onReviewRejectedAddon = {},
                            onManageParticipants = {},
                        )
                    }
                }
            }
        }

        val width = compose.activity.resources.configuration.screenWidthDp
        val first = compose.onNodeWithTag("gaming-station-ps5-1").fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithTag("gaming-station-ps5-4").fetchSemanticsNode().boundsInRoot
        val eighth = compose.onNodeWithTag("gaming-station-streaming-1").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("gaming-station-board").fetchSemanticsNode().boundsInRoot
        if (width >= 900) {
            val pane = compose.onNodeWithTag("gaming-control-pane").fetchSemanticsNode().boundsInRoot
            assertEquals(first.top, fourth.top, 1f)
            assertTrue("Fourth station must be alongside first", fourth.left > first.right)
            assertTrue("All eight stations should fit above the fold", eighth.bottom <= board.bottom + 1f)
            assertTrue("Board and slider should be side by side", board.right < pane.left)
            assertTrue("The dial pane must be wider than the station board",
                pane.width > board.width)
        } else {
            compose.onNodeWithTag("gaming-station-streaming-1").performScrollTo().assertIsDisplayed()
            capture("gaming-same-screen-960x600-board.png")
            compose.onNodeWithTag("gaming-board-and-controls").performScrollToIndex(0)
            compose.onNodeWithTag("gaming-station-ps5-2").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Session duration dial").assertIsDisplayed()
            compose.onNodeWithTag("gaming-board-and-controls").performScrollToIndex(1)
            compose.onNodeWithTag("gaming-control-pane").assertIsDisplayed()
            compose.onNodeWithContentDescription("Session duration dial").assertIsDisplayed()
            compose.onNodeWithText("Start 30 min · ₹80.00")
                .performScrollTo().assertIsDisplayed()
            capture("gaming-same-screen-960x600-control.png")
            compose.onNodeWithContentDescription("Search saved customers by name or phone")
                .performScrollTo().assertIsDisplayed()
                .performClick()
            compose.onNodeWithContentDescription("Search saved customers by name or phone")
                .performTextInput("Ada")
            compose.onNodeWithText("Start 30 min · ₹80.00")
                .performScrollTo().assertIsDisplayed()
            capture("gaming-same-screen-960x600-search.png")
            return
        }
        compose.onNodeWithContentDescription("Session duration dial").assertIsDisplayed()
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
        capture(if (width >= 1_100) "gaming-same-screen-1280x800-content.png"
            else "gaming-same-screen-960x600-content.png")

        if (width < 1_100) {
            compose.onNodeWithTag("gaming-station-ps5-2").performClick()
            compose.onNodeWithTag("gaming-station-board").assertIsDisplayed()
            compose.onNodeWithContentDescription("Session duration dial").assertIsDisplayed()
            compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
            compose.onNodeWithContentDescription("Search saved customers by name or phone")
                .performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Search saved customers by name or phone")
                .performTextInput("Ada")
            compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
            return
        }

        // The production board has eight stations today. A third compact row
        // reserves room for two additions without hiding controls or stations.
        val expandedStations = stations + listOf(
            Station("ps5-5", "PS5-5", "PS5 Station 5", "ps5", 12_000),
            Station("ps5-6", "PS5-6", "PS5 Station 6", "ps5", 12_000),
        )
        compose.runOnIdle {
            visibleStations.value = expandedStations
            state.value = state.value.copy(stations = expandedStations)
        }
        val tenth = compose.onNodeWithTag("gaming-station-ps5-6").fetchSemanticsNode().boundsInRoot
        val expandedPane = compose.onNodeWithTag("gaming-control-pane").fetchSemanticsNode().boundsInRoot
        assertTrue("Ten stations should fit beside the controls", tenth.bottom <= expandedPane.bottom)
        compose.runOnIdle {
            visibleStations.value = stations
            state.value = state.value.copy(stations = stations)
        }

        compose.onNodeWithTag("gaming-station-ps5-2").performClick()
        compose.runOnIdle { assertEquals(1, customerPrepareCalls) }
        compose.onNodeWithTag("gaming-station-board").assertIsDisplayed()
        compose.onNodeWithContentDescription("Session duration dial").assertIsDisplayed()
        compose.onNodeWithText("Dual · 2+ players").performScrollTo().performClick()
        compose.onNodeWithText("Start 30 min · ₹100.00").assertIsDisplayed()
        compose.onNodeWithText("Start 30 min · ₹100.00").performClick()
        compose.runOnIdle { assertEquals("ps5-2" to "dual-30", confirmed) }
        compose.onNodeWithText("Add player").performScrollTo().performClick()
        compose.onNodeWithText("Start 30 min · ₹130.00").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("ps5-2" to "dual-30", confirmed)
            assertEquals(1, confirmedExtraControllers)
        }

        compose.onNodeWithTag("gaming-station-racing-1").performScrollTo().performClick()
        compose.onNodeWithTag("gaming-station-board").assertIsDisplayed()
        compose.onNodeWithText("Start 15 min · ₹70.00").assertIsDisplayed()
        compose.onNodeWithText("Start 15 min · ₹70.00").performClick()
        compose.runOnIdle { assertEquals("racing-1" to "racing-15", confirmed) }

        // An unsent mode or customer choice belongs to the station where it
        // was entered; switching stations must restore that station's defaults.
        compose.onNodeWithTag("gaming-station-ps5-2").performScrollTo().performClick()
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()

        compose.runOnIdle {
            selectedId.value = "ps5-1"
            state.value = state.value.copy(sessions = listOf(GameSession(
                id = "session-1", stationId = "ps5-1", shiftId = "fixture-shift",
                status = "active", startAt = "2026-09-27T10:00:00Z",
                ratePerHourMinor = 12_000,
            )))
        }
        compose.onNodeWithTag("gaming-station-board").assertIsDisplayed()
        capture("gaming-same-screen-1280x800-active.png")
        compose.onNodeWithText("ELAPSED").assertIsDisplayed()
        compose.onNodeWithText("Stop & calculate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gaming-control-pane").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(sessions = listOf(GameSession(
                id = "session-1", stationId = "ps5-1", shiftId = "fixture-shift",
                status = "ended", startAt = "2026-09-27T10:00:00Z",
                endAt = "2026-09-27T11:00:00Z", amountMinor = 12_000,
                ratePerHourMinor = 12_000,
            )))
        }
        compose.onNodeWithTag("gaming-station-board").assertIsDisplayed()
        capture("gaming-same-screen-1280x800-payment-due.png")
        compose.onNodeWithText("Awaiting POS payment").assertIsDisplayed()
        compose.onNodeWithText("Send to POS").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fourPublishedDurationsRemainVisibleAndSelectable() {
        val station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000)
        val packages = listOf(15 to 6_000L, 30 to 8_000L, 60 to 12_000L, 90 to 18_000L)
            .map { (minutes, price) ->
                GamingPackage(
                    id = "single-$minutes", code = "single-$minutes", stationType = "ps5",
                    variant = "single", kind = "base", name = "Single $minutes",
                    durationMinutes = minutes, priceMinor = price,
                )
            }
        var submitted: String? = null
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(460.dp).height(590.dp)) {
                    StartSessionEditor(
                        station = station, packages = packages, embedded = true,
                        onDismiss = {},
                        onConfirm = { _, _, _, _, packageId, _ -> submitted = packageId },
                    )
                }
            }
        }
        compose.onNodeWithText("90 min · ₹180.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("90 min · ₹180.00").performClick()
        compose.onNodeWithText("Start 90 min · ₹180.00").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("single-90", submitted) }
    }

    @Test
    fun fullTabletWorkspaceShowsBoardDialCustomerAndStartTogether() {
        val stations = (1..4).map { number ->
            Station("ps5-$number", "PS5-$number", "PS5 Station $number", "ps5", 12_000)
        } + listOf(
            Station("racing-1", "RACE-1", "Racing Simulator 1", "racing", 10_000),
            Station("vr-1", "VR-1", "VR Pod 1", "vr", 12_000),
            Station("shisha-1", "SHISHA-1", "Shisha Table 1", "shisha", 10_000),
            Station("streaming-1", "STREAM-1", "Streaming Booth 1", "streaming", 10_000),
        )
        val state = mutableStateOf(GamingUiState(
            stations = stations,
            packages = listOf(
                GamingPackage(id = "single-30", code = "single-30", stationType = "ps5",
                    variant = "single", kind = "base", name = "Single 30",
                    durationMinutes = 30, priceMinor = 8_000),
                GamingPackage(id = "single-60", code = "single-60", stationType = "ps5",
                    variant = "single", kind = "base", name = "Single 60",
                    durationMinutes = 60, priceMinor = 12_000),
                GamingPackage(id = "dual-30", code = "dual-30", stationType = "ps5",
                    variant = "dual", kind = "base", name = "Dual 30",
                    durationMinutes = 30, priceMinor = 10_000,
                    includedPlayers = 2, maxPlayers = 4),
            ),
            everSynced = true, refreshing = false, activeShiftId = "fixture-shift",
            activeShiftServerConfirmed = true, online = true,
        ))
        val visibleStations = mutableStateOf(stations)
        var submittedPackageId: String? = null
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.fillMaxSize().testTag("gaming-full-workspace")) {
                    WorkspaceScaffold(
                        destinations = listOf(Destination.Gaming, Destination.Pos,
                            Destination.Shift, Destination.Customers),
                        currentDestination = Destination.Gaming,
                        employeeName = "Rafi", locationLabel = "Gaming Centre",
                        connectivityProblem = SyncAvailabilityProblem.NONE,
                        outboxWorkStatus = OutboxWorkStatus(), syncing = false,
                        canChangeTill = false,
                        onOpenSupport = {}, onChangeTill = {}, onSignOut = {},
                    ) { _, _ ->
                        GamingCommandWorkspace(
                            state = state.value, access = GamingAccess(canManageSessions = true),
                            filters = listOf(
                                StationFilter("all", "All"), StationFilter("ps5", "PS5"),
                                StationFilter("racing", "Racing"), StationFilter("vr", "VR"),
                                StationFilter("streaming", "Streaming"),
                                StationFilter("shisha", "Shisha"),
                            ), selectedFilter = "all",
                            visibleStations = visibleStations.value, selectedStationId = "ps5-1",
                            showDetail = false, focusStationId = null, focusRequested = false,
                            startTerminalBlockMessage = null,
                            wallClock = remember { mutableLongStateOf(1_779_989_200_000L) },
                            attentionCount = 0, onSelectFilter = {}, onSelectStation = {},
                            onBackToBoard = {}, onOpenAttention = {}, onRefresh = {},
                            onStart = {}, onConfirmStart = { _, _, _, _, _, packageId, _ ->
                                submittedPackageId = packageId
                            },
                            onStop = { _, _ -> }, onSend = {}, onCancelUnbilled = {},
                            onExtendTimer = {}, onExtendPackage = { _, _ -> },
                            onTransfer = {}, onPauseResume = {}, onReconcile = {},
                            onRepairBilling = {}, onResolveLegacyStart = {},
                            onDiscardPackageExtension = {}, onAddItems = {},
                            onVoidAddon = { _, _ -> }, onReviewRejectedAddon = {},
                            onManageParticipants = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("gaming-filter-ps5").assertIsDisplayed()
        compose.onNodeWithTag("gaming-filter-racing").assertIsDisplayed()
        compose.onNodeWithTag("gaming-filter-vr").assertIsDisplayed()
        val firstTile = compose.onNodeWithTag("gaming-station-ps5-1")
            .getUnclippedBoundsInRoot()
        val racingTile = compose.onNodeWithTag("gaming-station-racing-1")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "Long station names and missing-rate warnings must not stretch the board: " +
                "first=${firstTile.bottom - firstTile.top}, racing=${racingTile.bottom - racingTile.top}",
            abs((firstTile.bottom - firstTile.top).value -
                (racingTile.bottom - racingTile.top).value) <= 1f,
        )
        if (compose.activity.resources.configuration.screenWidthDp >= 1_100) {
            val pane = compose.onNodeWithTag("gaming-control-pane").getUnclippedBoundsInRoot()
            val lastStation = compose.onNodeWithTag("gaming-station-streaming-1")
                .getUnclippedBoundsInRoot()
            assertTrue("Station board fits in the full workspace", lastStation.bottom <= pane.bottom)
            compose.onNodeWithContentDescription("Session duration dial").assertIsDisplayed()
            compose.onNodeWithContentDescription("Search saved customers by name or phone")
                .assertIsDisplayed()
            compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
            capture("gaming-full-workspace-1280x800.png", "gaming-full-workspace")

            val tenStations = stations + listOf(
                Station("ps5-5", "PS5-5", "PS5 Station 5", "ps5", 12_000),
                Station("ps5-6", "PS5-6", "PS5 Station 6", "ps5", 12_000),
            )
            compose.runOnIdle {
                visibleStations.value = tenStations
                state.value = state.value.copy(stations = tenStations)
            }
            val tenth = compose.onNodeWithTag("gaming-station-ps5-6")
                .getUnclippedBoundsInRoot()
            val expandedPane = compose.onNodeWithTag("gaming-control-pane")
                .getUnclippedBoundsInRoot()
            assertTrue("Ten stations fit in the full workspace", tenth.bottom <= expandedPane.bottom)
            capture("gaming-full-workspace-10-stations-1280x800.png", "gaming-full-workspace")
        } else {
            val board = compose.onNodeWithTag("gaming-station-board")
                .getUnclippedBoundsInRoot()
            val pane = compose.onNodeWithTag("gaming-control-pane")
                .getUnclippedBoundsInRoot()
            val eighth = compose.onNodeWithTag("gaming-station-streaming-1")
                .getUnclippedBoundsInRoot()
            assertTrue("960dp board and dial share the viewport", board.right < pane.left)
            assertTrue("All eight cards remain above the bottom navigation", eighth.bottom <= pane.bottom)
            compose.onNodeWithTag("gaming-station-streaming-1").assertIsDisplayed()
            val dial = compose.onNodeWithContentDescription("Session duration dial")
                .assertIsDisplayed()
            compose.onNodeWithText("₹80.00").assertIsDisplayed()
            compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
            val dialBounds = dial.getUnclippedBoundsInRoot()
            val search = compose.onNodeWithContentDescription("Search saved customers by name or phone")
                .getUnclippedBoundsInRoot()
            val start = compose.onNodeWithText("Start 30 min · ₹80.00")
                .getUnclippedBoundsInRoot()
            assertTrue("Duration dial must not overlap customer controls", dialBounds.right <= search.left)
            assertTrue("Customer lookup stays above the Start action", search.bottom <= start.top)
            capture("gaming-full-workspace-960x600-board-and-dial.png", "gaming-full-workspace")
            dial.performTouchInput {
                swipe(Offset(width * 0.17f, height * 0.31f),
                    Offset(width * 0.83f, height * 0.31f), 300)
            }
            compose.onNodeWithText("Start 60 min · ₹120.00").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("single-60", submittedPackageId) }
            dial.performTouchInput {
                swipe(Offset(width * 0.83f, height * 0.31f),
                    Offset(width * 0.17f, height * 0.31f), 300)
            }
            compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("single-30", submittedPackageId) }
            compose.onNodeWithContentDescription("Search saved customers by name or phone")
                .performScrollTo().assertIsDisplayed()
                .performClick()
            compose.onNodeWithContentDescription("Search saved customers by name or phone")
                .performTextInput("Ada")
            compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
            capture("gaming-full-workspace-960x600-action.png", "gaming-full-workspace")

            compose.runOnIdle {
                state.value = state.value.copy(sessions = listOf(GameSession(
                    id = "compact-session", stationId = "ps5-1", shiftId = "fixture-shift",
                    status = "active", startAt = "2026-09-27T10:00:00Z",
                    ratePerHourMinor = 12_000,
                )))
            }
            compose.onNodeWithText("Stop & calculate").performScrollTo().assertIsDisplayed()
            capture("gaming-full-workspace-960x600-active-action.png", "gaming-full-workspace")
            compose.runOnIdle {
                state.value = state.value.copy(sessions = listOf(GameSession(
                    id = "compact-session", stationId = "ps5-1", shiftId = "fixture-shift",
                    status = "ended", startAt = "2026-09-27T10:00:00Z",
                    endAt = "2026-09-27T11:00:00Z", amountMinor = 12_000,
                    ratePerHourMinor = 12_000,
                )))
            }
            compose.onNodeWithText("Awaiting POS payment").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Send to POS").performScrollTo().assertIsDisplayed()
            capture("gaming-full-workspace-960x600-payment-action.png", "gaming-full-workspace")
        }
    }

    @Test
    fun missingRacingTariffDisablesSameScreenStartAndExplainsWhy() {
        val station = Station("racing-1", "RACE-1", "Racing Simulator 1", "racing", 15_000)
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(1_280.dp).height(616.dp)) {
                    GamingCommandWorkspace(
                        state = GamingUiState(stations = listOf(station), everSynced = true,
                            activeShiftId = "fixture-shift", activeShiftServerConfirmed = true),
                        access = GamingAccess(canManageSessions = true),
                        filters = listOf(StationFilter("all", "All")), selectedFilter = "all",
                        visibleStations = listOf(station), selectedStationId = station.id,
                        showDetail = false, focusStationId = null, focusRequested = false,
                        startTerminalBlockMessage = null,
                        wallClock = remember { mutableLongStateOf(1_779_989_200_000L) },
                        attentionCount = 0, onSelectFilter = {}, onSelectStation = {},
                        onBackToBoard = {}, onOpenAttention = {}, onRefresh = {},
                        onStart = {}, onConfirmStart = { _, _, _, _, _, _, _ ->
                            throw AssertionError("Missing tariff must never start")
                        },
                        onStop = { _, _ -> }, onSend = {}, onCancelUnbilled = {},
                        onExtendTimer = {}, onExtendPackage = { _, _ -> },
                        onTransfer = {}, onPauseResume = {}, onReconcile = {},
                        onRepairBilling = {}, onResolveLegacyStart = {},
                        onDiscardPackageExtension = {}, onAddItems = {},
                        onVoidAddon = { _, _ -> }, onReviewRejectedAddon = {},
                        onManageParticipants = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("gaming-station-racing-1").assertIsDisplayed()
        compose.onNodeWithText("Sync", substring = true).assertIsDisplayed()
        compose.onNodeWithText("The fixed-price tariff has not synced", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start session").assertExists()
        compose.onNodeWithText("Start session").assertIsNotEnabled()
    }

    @Test
    fun simultaneousWarningsKeepCompactBoardDialAndRecoveryActionReachable() {
        val station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000)
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(960.dp).height(470.dp).testTag("warning-workspace")) {
                    GamingCommandWorkspace(
                        state = GamingUiState(
                            stations = listOf(station),
                            packages = listOf(
                                GamingPackage(
                                    id = "single-30", code = "single-30", stationType = "ps5",
                                    variant = "single", kind = "base", name = "Single 30",
                                    durationMinutes = 30, priceMinor = 8_000,
                                ),
                                GamingPackage(
                                    id = "single-60", code = "single-60", stationType = "ps5",
                                    variant = "single", kind = "base", name = "Single 60",
                                    durationMinutes = 60, priceMinor = 12_000,
                                ),
                            ),
                            cleanupAcknowledgementsPending = listOf(
                                GamingCleanupAcknowledgementUi(
                                    stationId = station.id,
                                    detail = "The saved cleanup acknowledgement needs review before " +
                                        "this station is used again.",
                                ),
                            ),
                            everSynced = true, refreshing = false, activeShiftId = "fixture-shift",
                            activeShiftServerConfirmed = true, online = true,
                        ),
                        access = GamingAccess(canManageSessions = true),
                        filters = listOf(StationFilter("all", "All")),
                        selectedFilter = "all", visibleStations = listOf(station),
                        selectedStationId = station.id, showDetail = false,
                        focusStationId = null, focusRequested = false,
                        startTerminalBlockMessage = null,
                        wallClock = remember { mutableLongStateOf(1_779_989_200_000L) },
                        attentionCount = 1,
                        onSelectFilter = {}, onSelectStation = {}, onBackToBoard = {},
                        onOpenAttention = {}, onRefresh = {}, onStart = {},
                        onConfirmStart = { _, _, _, _, _, _, _ -> },
                        onStop = { _, _ -> }, onSend = {}, onCancelUnbilled = {},
                        onExtendTimer = {}, onExtendPackage = { _, _ -> },
                        onTransfer = {}, onPauseResume = {}, onReconcile = {},
                        onRepairBilling = {}, onResolveLegacyStart = {},
                        onDiscardPackageExtension = {}, onAddItems = {},
                        onVoidAddon = { _, _ -> }, onReviewRejectedAddon = {},
                        onManageParticipants = {},
                    )
                }
            }
        }

        val root = compose.onNodeWithTag("warning-workspace").fetchSemanticsNode().boundsInRoot
        val rail = compose.onNodeWithTag("gaming-notice-rail").fetchSemanticsNode().boundsInRoot
        val pane = compose.onNodeWithTag("gaming-control-pane").fetchSemanticsNode().boundsInRoot
        assertTrue("Warnings must use a bounded rail", rail.height <= root.height * 0.24f)
        assertTrue("The control pane must retain useful height", pane.height >= root.height * 0.35f)
        compose.onNodeWithTag("gaming-station-ps5-1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Session duration dial")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry acknowledgement").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
    }

    private fun capture(filename: String, rootTag: String = "gaming-fixture-root") {
        val output = File(requireNotNull(compose.activity.getExternalFilesDir(null)), filename)
        val bitmap = compose.onNodeWithTag(rootTag).captureToImage().asAndroidBitmap()
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
