package cloud.dcompany.erp.ui.screens.gaming

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.auth.ShiftAccess
import cloud.dcompany.erp.core.db.GamingSessionParticipantCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.MenuVariantEntity
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GamingBoardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inlineDrinksSearchCategoryQuantityAndCustomizationStayReachableAtTabletSizes() {
        val viewport = mutableStateOf(1_024 to 640)
        val added = mutableListOf<Pair<String, Int>>()
        val configured = mutableListOf<String>()
        val session = GameSession(
            id = "drinks-session",
            stationId = "ps5-1",
            shiftId = "shift-1",
            status = "active",
            startAt = "2026-09-27T10:00:00Z",
            timerMinutes = 60,
            packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5",
        )
        compose.setContent {
            DCompanyTheme {
                val size = viewport.value
                val tabletConfiguration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = size.first
                    screenHeightDp = size.second
                }
                CompositionLocalProvider(LocalConfiguration provides tabletConfiguration) {
                    Box(
                        Modifier.width(size.first.dp).height(size.second.dp)
                            .testTag("drinks-viewport"),
                    ) {
                        GamingInlineDrinkEditor(
                            session = session,
                            items = gamingDrinkFixtureItems(),
                            configurableItemIds = setOf("custom-shake"),
                            busy = false,
                            onConfirm = { item, quantity -> added += item.id to quantity },
                            onConfigure = { configured += it.id },
                            onClose = {},
                            modifier = Modifier.fillMaxSize(),
                            pinActions = LocalConfiguration.current.screenWidthDp >= 900,
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Snack").assertIsDisplayed().performClick()
        compose.onNodeWithText("Nachos").assertIsDisplayed()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithTag("gaming-drinks-search").performTextInput("Mango")
        compose.onNodeWithText("Mango drink").assertIsDisplayed()
        compose.onNodeWithText("Select").assertIsDisplayed().performClick()
        compose.onNodeWithText("+").assertIsDisplayed().performClick().performClick()
        assertDrinksFooterInsideViewport()

        compose.runOnIdle { viewport.value = 1_280 to 800 }
        compose.waitForIdle()
        assertDrinksFooterInsideViewport()
        compose.onNodeWithText("Confirm add · ₹150.00").performClick()
        compose.runOnIdle { assertEquals(listOf("mango" to 3), added) }

        compose.onNodeWithTag("gaming-drinks-search").performTextReplacement("Custom")
        compose.onNodeWithText("Custom shake").assertIsDisplayed()
        compose.onNodeWithText("Customise").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("custom-shake"), configured)
            assertEquals(listOf("mango" to 3), added)
        }
    }

    @Test
    fun reviewedDrinkNeverSilentlyAdoptsRefreshedCataloguePriceOrName() {
        val original = gamingDrinkFixtureItems().first { it.id == "mango" }
        val catalogue = mutableStateOf(listOf(original))
        val added = mutableListOf<Pair<MenuItemEntity, Int>>()
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(700.dp).height(600.dp)) {
                    GamingInlineDrinkEditor(
                        session = GameSession(id = "review-session", stationId = "ps5-1",
                            shiftId = "shift-1", status = "active", startAt = "2026-09-27T10:00:00Z"),
                        items = catalogue.value, configurableItemIds = emptySet(), busy = false,
                        onConfirm = { item, quantity -> added += item to quantity },
                        onConfigure = {}, onClose = {}, pinActions = true,
                    )
                }
            }
        }
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("+").performClick()
        compose.runOnIdle { assertTrue(added.isEmpty()) }
        val replacement = original.copy(name = "Mango premium drink", basePriceMinor = 9_000)
        compose.runOnIdle { catalogue.value = listOf(replacement) }
        compose.onNodeWithText("Review Mango drink").assertIsDisplayed()
        compose.onNodeWithText("Confirm add · ₹100.00").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("gaming-drinks-review-stale").assertIsDisplayed()
        compose.runOnIdle { assertTrue(added.isEmpty()) }
        compose.onNodeWithText("Choose another item").performClick()
        compose.onNodeWithText("Mango premium drink").assertIsDisplayed()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Confirm add · ₹90.00").performClick()
        compose.runOnIdle { assertEquals(listOf(replacement to 1), added) }
        compose.onNodeWithText("Select").performClick()
        compose.runOnIdle { catalogue.value = emptyList() }
        compose.onNodeWithTag("gaming-drinks-inline-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("gaming-drinks-review-stale").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, added.size) }
    }

    @Test
    fun drinkReviewSurvivesRealWorkspaceTabRoundTripAndRejectsCatalogueChangedWhileAway() {
        val original = gamingDrinkFixtureItems().first { it.id == "mango" }
        val catalogue = mutableStateOf(listOf(original))
        val destination = mutableStateOf(Destination.Gaming)
        val confirmed = mutableListOf<Pair<MenuItemEntity, Int>>()
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(1_024.dp).height(640.dp)) {
                    WorkspaceScaffold(
                        destinations = listOf(Destination.Gaming, Destination.Pos),
                        currentDestination = destination.value,
                        employeeName = "Test cashier", locationLabel = "Test shop",
                        connectivityProblem = SyncAvailabilityProblem.NONE,
                        outboxWorkStatus = OutboxWorkStatus(), syncing = false,
                        canChangeTill = false, onOpenSupport = {}, onChangeTill = {}, onSignOut = {},
                        onDestinationChanged = { destination.value = it },
                    ) { current, _ ->
                        if (current == Destination.Gaming) GamingInlineDrinkEditor(
                            session = GameSession(id = "retained-drink-review", stationId = "ps5-1",
                                shiftId = "shift-1", status = "active", startAt = "2026-09-27T10:00:00Z"),
                            items = catalogue.value, configurableItemIds = emptySet(), busy = false,
                            onConfirm = { item, quantity -> confirmed += item to quantity },
                            onConfigure = {}, onClose = {}, modifier = Modifier.fillMaxSize(), pinActions = true,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithContentDescription("POS. Take orders and payments").performClick()
        compose.onNodeWithContentDescription("Gaming. Manage stations and sessions").performClick()
        compose.onNodeWithText("Review Mango drink").assertIsDisplayed()
        compose.onNodeWithText("Confirm add · ₹100.00").assertIsDisplayed()
        compose.runOnIdle { assertTrue(confirmed.isEmpty()) }
        compose.onNodeWithContentDescription("POS. Take orders and payments").performClick()
        compose.runOnIdle { catalogue.value = listOf(original.copy(name = "Fresh mango drink", basePriceMinor = 9_000)) }
        compose.onNodeWithContentDescription("Gaming. Manage stations and sessions").performClick()
        compose.onNodeWithText("Review Mango drink").assertIsDisplayed()
        compose.onNodeWithText("Confirm add · ₹100.00").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("gaming-drinks-review-stale").assertIsDisplayed()
        compose.runOnIdle { assertTrue(confirmed.isEmpty()) }
    }

    @Test
    fun drinkReviewNeverMovesToAReplacementSessionAtTheSameStationAfterTabReturn() {
        val original = gamingDrinkFixtureItems().first { it.id == "mango" }
        val destination = mutableStateOf(Destination.Gaming)
        val session = mutableStateOf(GameSession(
            id = "session-a", stationId = "ps5-1", shiftId = "shift-1",
            status = "active", startAt = "2026-09-27T10:00:00Z",
        ))
        val confirmed = mutableListOf<Triple<String, MenuItemEntity, Int>>()
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(1_024.dp).height(640.dp)) {
                    WorkspaceScaffold(
                        destinations = listOf(Destination.Gaming, Destination.Pos),
                        currentDestination = destination.value,
                        employeeName = "Test cashier", locationLabel = "Test shop",
                        connectivityProblem = SyncAvailabilityProblem.NONE,
                        outboxWorkStatus = OutboxWorkStatus(), syncing = false,
                        canChangeTill = false, onOpenSupport = {}, onChangeTill = {}, onSignOut = {},
                        onDestinationChanged = { destination.value = it },
                    ) { current, _ ->
                        if (current == Destination.Gaming) {
                            val reviewedSession = session.value
                            GamingInlineDrinkEditor(
                                session = reviewedSession, items = listOf(original),
                                configurableItemIds = emptySet(), busy = false,
                                onConfirm = { item, quantity ->
                                    confirmed += Triple(reviewedSession.id, item, quantity)
                                },
                                onConfigure = {}, onClose = {},
                                modifier = Modifier.fillMaxSize(), pinActions = true,
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("+").performClick().performClick()
        compose.onNodeWithText("Confirm add · ₹150.00").assertIsDisplayed()
        compose.onNodeWithContentDescription("POS. Take orders and payments").performClick()
        compose.runOnIdle { session.value = session.value.copy(id = "session-b") }
        compose.onNodeWithContentDescription("Gaming. Manage stations and sessions").performClick()
        compose.onNodeWithText("Review Mango drink").assertDoesNotExist()
        compose.onNodeWithTag("gaming-drinks-inline-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(confirmed.isEmpty()) }
        compose.onNodeWithText("Select").assertIsDisplayed().performClick()
        compose.onNodeWithText("Confirm add · ₹50.00").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("session-b", original, 1)), confirmed) }
    }

    @Test
    fun compactUnpinnedDrinksReviewCanLiveInsideTheWorkspaceScrollOwner() {
        val added = mutableListOf<Pair<String, Int>>()
        val session = GameSession(
            id = "compact-drinks-session",
            stationId = "ps5-1",
            shiftId = "shift-1",
            status = "active",
            startAt = "2026-09-27T10:00:00Z",
            timerMinutes = 60,
            packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5",
        )
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(360.dp).height(500.dp)) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    ) {
                        GamingInlineDrinkEditor(
                            session = session,
                            items = gamingDrinkFixtureItems(),
                            configurableItemIds = emptySet(),
                            busy = false,
                            onConfirm = { item, quantity -> added += item.id to quantity },
                            onConfigure = {},
                            onClose = {},
                            pinActions = false,
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("gaming-drinks-search").performTextInput("Mango")
        compose.onNodeWithText("Mango drink").assertIsDisplayed()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Review Mango drink").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+").performClick().performClick()
        compose.runOnIdle { assertTrue(added.isEmpty()) }
        compose.onNodeWithTag("gaming-drinks-inline-confirm")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("mango" to 3), added) }
    }

    @Test
    fun playerRegistrationDraftNeverMovesToReplacementSessionAfterTabReturn() {
        val destination = mutableStateOf(Destination.Gaming)
        val session = mutableStateOf(GameSession(
            id = "player-session-a", stationId = "ps5-1", shiftId = "shift-1",
            status = "active", startAt = "2026-09-27T10:00:00Z",
        ))
        val joins = mutableListOf<List<String?>>()
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(1_024.dp).height(640.dp)) {
                    WorkspaceScaffold(
                        destinations = listOf(Destination.Gaming, Destination.Pos),
                        currentDestination = destination.value,
                        employeeName = "Test cashier", locationLabel = "Test shop",
                        connectivityProblem = SyncAvailabilityProblem.NONE,
                        outboxWorkStatus = OutboxWorkStatus(), syncing = false,
                        canChangeTill = false, onOpenSupport = {}, onChangeTill = {}, onSignOut = {},
                        onDestinationChanged = { destination.value = it },
                    ) { current, _ ->
                        if (current == Destination.Gaming) {
                            val reviewedSession = session.value
                            GamingInlineParticipantEditor(
                                session = reviewedSession, participants = emptyList(), customers = emptyList(),
                                wallClock = remember { mutableLongStateOf(1_779_989_200_000L) }, busy = false,
                                onJoin = { customerId, guestName, phone, identity ->
                                    joins += listOf(reviewedSession.id, customerId, guestName, phone, identity)
                                },
                                onLeave = {}, onClose = {}, modifier = Modifier.fillMaxSize(),
                                pinConfirmAction = true,
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Guest name (optional · no points)").performTextReplacement("Nora")
        compose.onNodeWithText("Save new customer").performClick()
        compose.onNodeWithText("Phone (required)").performTextReplacement("0501234567")
        compose.onNodeWithContentDescription("POS. Take orders and payments").performClick()
        compose.runOnIdle { session.value = session.value.copy(id = "player-session-b") }
        compose.onNodeWithContentDescription("Gaming. Manage stations and sessions").performClick()
        compose.onNodeWithText("Phone (required)").assertDoesNotExist()
        compose.onNodeWithText("Guest name (optional · no points)").assertIsDisplayed()
        compose.runOnIdle { assertTrue(joins.isEmpty()) }
        compose.onNodeWithTag("gaming-player-inline-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(listOf("player-session-b", null, null, null, null)), joins) }
    }

    private fun pendingOfflinePlayerSession() = GameSession(
        id = "offline-pending-session", stationId = "ps5-1", shiftId = "shift-1",
        status = "starting", startAt = "2026-09-27T10:00:00Z",
        timerMinutes = 30, amountMinor = 8_000, billingMode = "package",
        packageId = "single-30", packagePriceMinorSnapshot = 8_000,
        packageDurationMinutesSnapshot = 30, packageVariantSnapshot = "single",
        packageStationTypeSnapshot = "ps5", packagePricingTierSnapshot = "standard",
        pauseVersion = 0, localState = GamingSessionState.START_PENDING,
    )

    @Test
    fun pendingOfflineStartOpensInlineAnonymousJoinAndKeepsOrderedStopReachable() {
        val station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000)
        val pending = pendingOfflinePlayerSession()
        val state = mutableStateOf(GamingUiState(
            catalogueLoaded = true, stations = listOf(station), sessions = listOf(pending),
            packages = listOf(GamingPackage(id = "single-30", code = "single-30", stationType = "ps5",
                variant = "single", kind = "base", name = "Single 30", durationMinutes = 30, priceMinor = 8_000)),
            everSynced = true, refreshing = false, online = false,
            activeShiftId = "shift-1", activeShiftServerConfirmed = true,
        ))
        val calls = mutableListOf<String>()
        val joins = mutableListOf<List<String?>>()
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(1_280.dp).height(744.dp)) {
                    GamingCommandWorkspace(
                        state = state.value, access = GamingAccess(canManageSessions = true),
                        filters = listOf(StationFilter("all", "All")), selectedFilter = "all",
                        visibleStations = listOf(station), selectedStationId = station.id,
                        showDetail = false, focusStationId = null, focusRequested = false,
                        startTerminalBlockMessage = null,
                        wallClock = remember { mutableLongStateOf(1_790_503_500_000L) }, attentionCount = 0,
                        onSelectFilter = {}, onSelectStation = {}, onBackToBoard = {},
                        onOpenAttention = {}, onRefresh = {}, onStart = {},
                        onConfirmStart = { _, _, _, _, _, _, _, _ -> },
                        onStop = { _, stopped -> calls += "stop:${stopped.id}" },
                        onSend = {}, onCancelUnbilled = {}, onExtendTimer = {},
                        onExtendPackage = { _, _ -> }, onTransfer = {}, onPauseResume = {},
                        onReconcile = {}, onRepairBilling = {}, onResolveLegacyStart = {},
                        onDiscardPackageExtension = {}, onAddItems = {}, onVoidAddon = { _, _ -> },
                        onReviewRejectedAddon = {}, onManageParticipants = {},
                        onJoinParticipant = { joinedSession, customer, name, phone, identity ->
                            calls += "join:${joinedSession.id}"
                            joins += listOf(customer, name, phone, identity)
                            state.value = state.value.copy(busyStationId = station.id)
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("Players").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("gaming-player-pending-start-guidance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resume the session before adding a player.").assertDoesNotExist()
        compose.onNodeWithTag("gaming-player-inline-confirm").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(listOf<String?>(null, null, null, null)), joins)
            assertEquals(listOf("join:${pending.id}"), calls)
        }
        compose.onNodeWithTag("gaming-player-inline-confirm").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busyStationId = null) }
        compose.onNodeWithTag("gaming-primary-action-host").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Stop & calculate").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("join:${pending.id}", "stop:${pending.id}"), calls) }
    }

    @Test
    fun pendingInlineJoinRetainsCapacityAndBlocksPausedRejectedOrUnverifiedStarts() {
        val pending = pendingOfflinePlayerSession()
        val session = mutableStateOf(pending)
        val participants = mutableStateOf(emptyList<GamingParticipantUi>())
        var joins = 0
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(720.dp).height(600.dp)) {
                    GamingInlineParticipantEditor(
                        session.value, participants.value, emptyList(),
                        remember { mutableLongStateOf(1_790_503_500_000L) }, false,
                        onJoin = { _, _, _, _ -> joins++ }, onLeave = {}, onClose = {},
                        modifier = Modifier.fillMaxSize(), pinConfirmAction = true,
                    )
                }
            }
        }
        compose.onNodeWithTag("gaming-player-inline-confirm").assertIsEnabled()
        compose.runOnIdle {
            participants.value = (1..3).map { index -> GamingParticipantUi(
                reference = "friend-$index", customerId = null, name = null, phone = null,
                active = true, pending = true,
            ) }
        }
        compose.onNodeWithTag("gaming-player-inline-confirm").assertDoesNotExist()
        compose.onNodeWithText("All four player places are assigned. A player must leave before another can join.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { participants.value = emptyList(); session.value = pending.copy(status = "paused") }
        compose.onNodeWithTag("gaming-player-inline-confirm").assertDoesNotExist()
        compose.onNodeWithText("Resume the session before adding a player.").assertIsDisplayed()
        compose.runOnIdle { session.value = pending.copy(status = "start_failed", localState = GamingSessionState.START_REJECTED) }
        compose.onNodeWithTag("gaming-player-inline-confirm").assertDoesNotExist()
        compose.onNodeWithText("Resolve this session's saved Start or Stop before adding a player.").assertIsDisplayed()
        compose.runOnIdle { session.value = pending.copy(packagePricingTierSnapshot = null) }
        compose.onNodeWithTag("gaming-player-inline-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, joins) }
    }

    @Test
    fun guestJoinNeverForwardsPhoneIncludingAfterLeavingSaveCustomerMode() {
        val joins = mutableListOf<List<String?>>()
        val session = GameSession(
            id = "participant-session",
            stationId = "ps5-1",
            shiftId = "shift-1",
            status = "active",
            startAt = "2026-09-27T10:00:00Z",
            timerMinutes = 60,
            packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5",
        )
        compose.setContent {
            DCompanyTheme {
                GamingInlineParticipantEditor(
                    session = session,
                    participants = emptyList(),
                    customers = emptyList(),
                    wallClock = remember { mutableLongStateOf(1_779_989_200_000L) },
                    busy = false,
                    onJoin = { customerId, guestName, phone, participantReference ->
                        joins += listOf(customerId, guestName, phone, participantReference)
                    },
                    onLeave = {},
                    onClose = {},
                    modifier = Modifier.width(720.dp),
                )
            }
        }

        compose.onNodeWithText("Phone (required)").assertDoesNotExist()
        compose.onNodeWithTag("gaming-player-inline-confirm").performClick()
        compose.onNodeWithText("Guest name (optional · no points)").performTextReplacement("Nora")
        compose.onNodeWithTag("gaming-player-inline-confirm").performClick()
        compose.onNodeWithText("Save new customer").performClick()
        compose.onNodeWithText("Phone (required)").performTextReplacement("0501234567")
        compose.onNodeWithText("Guest").performClick()
        compose.onNodeWithText("Phone (required)").assertDoesNotExist()
        compose.onNodeWithTag("gaming-player-inline-confirm").performClick()

        compose.runOnIdle {
            assertEquals(3, joins.size)
            assertEquals(listOf(null, null, null, null), joins[0])
            assertEquals(listOf(null, "Nora", null, null), joins[1])
            assertEquals(listOf(null, "Nora", null, null), joins[2])
        }
    }

    private fun assertDrinksFooterInsideViewport() {
        val footer = compose.onNodeWithTag("gaming-drinks-inline-confirm")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("drinks-viewport").getUnclippedBoundsInRoot()
        assertTrue(
            "Confirm add must remain inside the tablet viewport: footer=$footer viewport=$viewport",
            footer.top >= viewport.top && footer.bottom <= viewport.bottom,
        )
    }

    private fun assertIntegratedDrinksFooterInsideControlPane() {
        val footer = compose.onNodeWithTag("gaming-drinks-inline-confirm")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val editor = compose.onNodeWithTag("gaming-drinks-inline").getUnclippedBoundsInRoot()
        val pane = compose.onNodeWithTag("gaming-control-pane").getUnclippedBoundsInRoot()
        assertTrue(
            "Confirm add must remain inside both the inline editor and control pane: " +
                "footer=$footer editor=$editor pane=$pane",
            footer.top >= editor.top && footer.bottom <= editor.bottom &&
                footer.top >= pane.top && footer.bottom <= pane.bottom,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hoveringDurationPriceKeepsBookingUnchangedAndMarkerStillSelects() {
        val selected = mutableStateOf("single-60")
        compose.setContent {
            DCompanyTheme(preference = AppearancePreference.Night) {
                Surface(color = MaterialTheme.colorScheme.background) {
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
        }

        val sixtyMinutePrice = compose.onNodeWithContentDescription("Choose 60 minutes, ₹120.00")
        capture("gaming-duration-price-before-hover.png", "dial-hover-fixture")
        sixtyMinutePrice.performMouseInput { enter() }
        capture("gaming-duration-price-hover.png", "dial-hover-fixture")
        compose.runOnIdle { assertEquals("single-60", selected.value) }
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
        sixtyMinutePrice.performClick()
        compose.runOnIdle { assertEquals("single-60", selected.value) }
    }

    @Test
    fun durationEndpointPriceStaysCompleteAtEnlargedTabletText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.3f),
            ) {
                DCompanyTheme {
                    Box(Modifier.width(800.dp).height(1_280.dp)) {
                        GamingDurationDial(
                            choices = listOf(
                                GamingDurationChoice("single-30", 30, 8_000),
                                GamingDurationChoice("single-60", 60, 12_000),
                            ),
                            selectedPackageId = "single-60",
                            onSelect = {},
                            dialSize = 280.dp,
                            showChoiceChips = false,
                        )
                    }
                }
            }
        }

        val expected = "60"
        val endpoint = compose.onNodeWithText(expected, useUnmergedTree = true)
            .assertIsDisplayed()
        val ring = compose.onNodeWithTag("gaming-control-ring")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val endpointBounds = endpoint.getUnclippedBoundsInRoot()
        assertTrue(
            "Endpoint sizing must not resize or escape the 280dp ring: " +
                "ring=$ring endpoint=$endpointBounds",
            abs((ring.right - ring.left).value - 280f) <= 1f &&
                abs((ring.bottom - ring.top).value - 280f) <= 1f &&
                endpointBounds.left >= ring.left && endpointBounds.right <= ring.right &&
                endpointBounds.top >= ring.top && endpointBounds.bottom <= ring.bottom,
        )
        val layouts = mutableListOf<TextLayoutResult>()
        endpoint
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertTrue(
            "The enlarged endpoint must render a complete unclipped clock label: " +
                "size=${layout.size} lines=${layout.lineCount} " +
                "visibleEnd=${layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)}",
            layout.lineCount == 1 &&
                !layout.didOverflowHeight &&
                !layout.isLineEllipsized(0) &&
                layout.getLineEnd(0) == expected.length &&
                layout.getLineEnd(0, visibleEnd = true) == expected.length,
        )
    }

    @Test
    fun compactOvertimeWarningFitsTwoLinesWithoutEllipsis() {
        val station = Station(
            id = "ps5-overtime",
            code = "PS5-OT",
            name = "PS5 Overtime",
            type = "ps5",
            ratePerHourMinor = 12_000,
        )
        val start = Instant.parse("2026-10-19T15:35:00Z")
        val warning = "Time is up. Add a paid extension or stop. Package price stays fixed."
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(284.dp)) {
                    GamingStationCard(
                        station = station,
                        session = GameSession(
                            id = "session-overtime",
                            stationId = station.id,
                            shiftId = "shift-1",
                            status = "active",
                            startAt = start.toString(),
                            timerMinutes = 30,
                            timerEndsAt = start.plusSeconds(30 * 60).toString(),
                            packageId = "single-30",
                            packagePriceMinorSnapshot = 8_000,
                            packageDurationMinutesSnapshot = 30,
                            packageVariantSnapshot = "single",
                            packageStationTypeSnapshot = "ps5",
                        ),
                        packageExtensionAction = null,
                        wallClock = remember {
                            mutableLongStateOf(start.plusSeconds(40 * 60).toEpochMilli())
                        },
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
                        compactDetail = true,
                    )
                }
            }
        }

        val warningNode = compose.onNodeWithText(warning, useUnmergedTree = true)
            .assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        warningNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(
            "The 284dp overtime warning must render completely in two lines: " +
                "lines=${layout.lineCount} visibleEnd=" +
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true),
            layout.lineCount <= 2 && !layout.didOverflowHeight &&
                !layout.isLineEllipsized(layout.lineCount - 1) &&
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) == warning.length,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun durationHapticTicksOnlyForDifferentUserPublishedSelections() {
        val selected = mutableStateOf("single-30")
        val selections = mutableListOf<String>()
        var ticks = 0
        compose.setContent {
            DCompanyTheme {
                GamingDurationDial(
                    choices = listOf(
                        GamingDurationChoice("single-30", 30, 8_000),
                        GamingDurationChoice("single-60", 60, 12_000),
                    ),
                    selectedPackageId = selected.value,
                    onSelect = { selected.value = it; selections += it },
                    segmentTick = { ticks++ },
                    showChoiceChips = false,
                )
            }
        }
        val dial = compose.onNodeWithContentDescription("Session duration dial")
        dial.performTouchInput {
            down(ringPoint(width, height, 90f))
            repeat(12) { step -> moveTo(ringPoint(width, height, 90f + (step + 1) * 15f), delayMillis = 16) }
            up()
        }
        compose.onNodeWithContentDescription("Choose 60 minutes, ₹120.00").performClick()
        dial.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
        compose.runOnIdle { assertEquals(1, ticks) }
        compose.onNodeWithContentDescription("Choose 30 minutes, ₹80.00").performClick()
        compose.runOnIdle { assertEquals(2, ticks); assertEquals("single-30", selected.value) }
    }

    @Test
    fun durationHapticDoesNotTickWhenFirstStopNormalizesMissingSelection() {
        val selections = mutableListOf<String>()
        var ticks = 0
        compose.setContent {
            DCompanyTheme {
                GamingDurationDial(
                    choices = listOf(GamingDurationChoice("single-30", 30, 8_000), GamingDurationChoice("single-60", 60, 12_000)),
                    selectedPackageId = null, onSelect = selections::add,
                    segmentTick = { ticks++ }, showChoiceChips = false,
                )
            }
        }
        val dial = compose.onNodeWithContentDescription("Session duration dial")
        repeat(2) { dial.performTouchInput { click(ringPoint(width, height, 95f)) } }
        compose.runOnIdle { assertEquals(0, ticks); assertEquals(listOf("single-30", "single-30"), selections) }
    }

    @Test
    fun durationHapticFullCircleDragTracksBothDirectionsWithoutDuplicateStops() {
        val selected = mutableStateOf("single-30")
        val selections = mutableListOf<String>()
        var ticks = 0
        compose.setContent {
            DCompanyTheme {
                GamingDurationDial(
                    choices = listOf(
                        GamingDurationChoice("single-30", 30, 8_000),
                        GamingDurationChoice("single-60", 60, 12_000),
                    ),
                    selectedPackageId = selected.value,
                    onSelect = { selected.value = it; selections += it },
                    segmentTick = { ticks++ },
                    showChoiceChips = false,
                )
            }
        }
        val dial = compose.onNodeWithContentDescription("Session duration dial")
        dial.performTouchInput {
            down(ringPoint(width, height, 90f))
            repeat(12) { step -> moveTo(ringPoint(width, height, 90f + (step + 1) * 15f), delayMillis = 16) }
            up()
        }
        dial.performTouchInput {
            down(ringPoint(width, height, 270f))
            repeat(12) { step -> moveTo(ringPoint(width, height, 270f - (step + 1) * 15f), delayMillis = 16) }
            up()
        }
        compose.runOnIdle { assertEquals(2, ticks); assertEquals(listOf("single-60", "single-30"), selections) }
    }

    @Test
    fun cancelledDurationDragSnapsToPublishedStopAndAcceptsNextGesture() {
        val selected = mutableStateOf("single-30")
        val selections = mutableListOf<String>()
        var ticks = 0
        compose.setContent {
            DCompanyTheme {
                GamingDurationDial(
                    choices = listOf(
                        GamingDurationChoice("single-30", 30, 8_000),
                        GamingDurationChoice("single-60", 60, 12_000),
                    ),
                    selectedPackageId = selected.value,
                    onSelect = { selected.value = it; selections += it },
                    segmentTick = { ticks++ },
                    showChoiceChips = false,
                )
            }
        }
        val dial = compose.onNodeWithContentDescription("Session duration dial")
        dial.performTouchInput {
            down(ringPoint(width, height, 90f))
            moveTo(ringPoint(width, height, 125f))
            cancel()
        }
        compose.runOnIdle { assertEquals("single-30", selected.value); assertEquals(0, ticks) }
        dial.performTouchInput {
            down(ringPoint(width, height, 90f))
            repeat(12) { step -> moveTo(ringPoint(width, height, 90f + (step + 1) * 15f), delayMillis = 16) }
            up()
        }
        compose.runOnIdle { assertEquals("single-60", selected.value); assertEquals(1, ticks) }
    }

    @Test
    fun externalSelectionDuringHeldDragStopsOldGestureBeforeItCanRepublish() {
        val selected = mutableStateOf("single-30")
        val selections = mutableListOf<String>()
        var ticks = 0
        compose.setContent {
            DCompanyTheme {
                GamingDurationDial(
                    choices = listOf(
                        GamingDurationChoice("single-30", 30, 8_000),
                        GamingDurationChoice("single-60", 60, 12_000),
                    ),
                    selectedPackageId = selected.value,
                    onSelect = { selected.value = it; selections += it },
                    segmentTick = { ticks++ },
                    showChoiceChips = false,
                )
            }
        }
        val dial = compose.onNodeWithContentDescription("Session duration dial")
        dial.performTouchInput {
            down(ringPoint(width, height, 90f))
            moveTo(ringPoint(width, height, 120f))
        }
        compose.runOnIdle { selected.value = "single-60" }
        compose.waitForIdle()
        dial.performTouchInput { moveTo(ringPoint(width, height, 90f)); up() }
        compose.runOnIdle { assertEquals("single-60", selected.value); assertTrue(selections.isEmpty()) }
        compose.onNodeWithContentDescription("Choose 30 minutes, ₹80.00").performClick()
        compose.runOnIdle { assertEquals("single-30", selected.value); assertEquals(1, ticks) }
    }

    @Test
    fun centreVerticalGestureScrollsParentWithoutPublishingDuration() {
        val selections = mutableListOf<String>()
        lateinit var parentScroll: ScrollState
        compose.setContent {
            DCompanyTheme {
                parentScroll = rememberScrollState()
                Column(Modifier.height(250.dp).verticalScroll(parentScroll)) {
                    GamingDurationDial(
                        choices = listOf(
                            GamingDurationChoice("single-30", 30, 8_000),
                            GamingDurationChoice("single-60", 60, 12_000),
                        ),
                        selectedPackageId = "single-30",
                        onSelect = selections::add,
                    )
                    Spacer(Modifier.height(400.dp))
                }
            }
        }

        val dial = compose.onNodeWithContentDescription("Session duration dial")
        dial.performTouchInput {
            swipe(
                start = Offset(width * 0.50f, height * 0.50f),
                end = Offset(width * 0.50f, height * 0.15f),
                durationMillis = 300,
            )
        }
        compose.runOnIdle {
            assertTrue(parentScroll.value > 0)
            assertTrue(selections.isEmpty())
        }

        runBlocking { parentScroll.scrollTo(0) }
        compose.waitForIdle()
        val ringDragScrollBaseline = parentScroll.value
        dial.performTouchInput {
            down(ringPoint(width, height, 90f))
            repeat(12) { step -> moveTo(ringPoint(width, height, 90f + (step + 1) * 15f), delayMillis = 16) }
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf("single-60"), selections)
            assertEquals(ringDragScrollBaseline, parentScroll.value)
        }
    }

    @Test
    fun clockShowsThirtyAtBottomRevealsHourAfterPassingAndIgnoresReleaseJitter() {
        val selected = mutableStateOf("single-30")
        val selections = mutableListOf<String>()
        var ticks = 0
        compose.setContent {
            DCompanyTheme {
                GamingDurationDial(
                    choices = listOf(
                        GamingDurationChoice("single-30", 30, 8_000),
                        GamingDurationChoice("single-60", 60, 12_000),
                    ),
                    selectedPackageId = selected.value,
                    onSelect = { selected.value = it; selections += it },
                    segmentTick = { ticks++ },
                    showChoiceChips = false,
                )
            }
        }
        val dial = compose.onNodeWithContentDescription("Session duration dial")
        compose.onNodeWithContentDescription("Choose 60 minutes, ₹120.00").assertDoesNotExist()
        val ring = compose.onNodeWithTag("gaming-control-ring").getUnclippedBoundsInRoot()
        val thirty = compose.onNodeWithContentDescription("Choose 30 minutes, ₹80.00").getUnclippedBoundsInRoot()
        assertTrue((thirty.top + thirty.bottom) / 2 > (ring.top + ring.bottom) / 2)
        dial.performTouchInput {
            down(ringPoint(width, height, 90f))
            moveTo(ringPoint(width, height, 105f))
        }
        compose.onNodeWithContentDescription("Choose 60 minutes, ₹120.00").assertIsDisplayed()
        compose.runOnIdle { assertEquals("single-30", selected.value) }
        dial.performTouchInput {
            repeat(11) { step -> moveTo(ringPoint(width, height, 120f + step * 15f), delayMillis = 16) }
            moveTo(ringPoint(width, height, 269f))
            up()
        }
        compose.runOnIdle { assertEquals("single-60", selected.value); assertEquals(1, ticks) }
        compose.onNodeWithContentDescription("Choose 30 minutes, ₹80.00").performClick()
        repeat(8) { dial.performTouchInput { click(ringPoint(width, height, 91f)) } }
        compose.runOnIdle { assertEquals("single-30", selected.value); assertEquals(2, ticks) }
    }

    @Test
    fun activeSessionCancelStaysInlineAndRequiresAnAuditReason() {
        val selected = mutableStateOf<GameSession?>(null)
        var confirmed: String? = null
        val station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000)
        val session = GameSession("active-cancel", "ps5-1", "shift-1", "active", "2026-10-21T10:00:00Z")
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(600.dp).height(700.dp)) {
                    GamingStationCard(
                        station = station, session = session, packageExtensionAction = null,
                        wallClock = remember { mutableLongStateOf(1_792_576_800_000L) },
                        actionInProgress = false, busyHere = false, focused = true,
                        canWrite = true, canReconcileLegacy = false, activeShiftId = "shift-1",
                        activeShiftServerConfirmed = true, online = true, packages = emptyList(),
                        hasTransferTarget = false, onStart = {}, onStop = {}, onSend = {},
                        onCancelUnbilled = { selected.value = it }, onExtendTimer = {},
                        onExtendPackage = { _, _ -> }, onTransfer = {}, onReconcile = {},
                        onRepairBilling = {}, onResolveLegacyStart = {}, onDiscardPackageExtension = {},
                    )
                }
                if (selected.value != null) CancelUnbilledSessionDialog(
                    stationName = station.name, amountMinor = 0, activeSession = true,
                    onDismiss = { selected.value = null }, onConfirm = { confirmed = it; selected.value = null },
                )
            }
        }
        compose.onNodeWithTag("gaming-cancel-active-session").assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel session").assertIsNotEnabled()
        compose.onNodeWithText("Entered by mistake").performClick()
        compose.onNodeWithText("Cancel session").performClick()
        compose.runOnIdle { assertEquals("Entered by mistake", confirmed) }
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
        dial.performTouchInput {
            down(ringPoint(width, height, 90f))
            repeat(12) { step -> moveTo(ringPoint(width, height, 90f + (step + 1) * 15f), delayMillis = 16) }
            up()
        }
        compose.onNodeWithText("Start 60 min · ₹120.00").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Choose 30 minutes, ₹80.00").performClick()
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
            down(Offset(width * 0.50f, height * 0.90f))
                repeat(12) { step ->
                    val radians = Math.toRadians((90f + (step + 1) * 15f).toDouble())
                    moveTo(Offset(width * (0.5f + 0.4f * kotlin.math.cos(radians).toFloat()),
                        height * (0.5f + 0.4f * kotlin.math.sin(radians).toFloat())), delayMillis = 16)
                }
                up()
        }
        compose.onNodeWithText("Start 60 min · ₹120.00").assertIsDisplayed().performClick()
        dial.performTouchInput {
            down(Offset(width * 0.50f, height * 0.10f))
                repeat(12) { step ->
                    val radians = Math.toRadians((270f - (step + 1) * 15f).toDouble())
                    moveTo(Offset(width * (0.5f + 0.4f * kotlin.math.cos(radians).toFloat()),
                        height * (0.5f + 0.4f * kotlin.math.sin(radians).toFloat())), delayMillis = 16)
                }
                up()
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
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                            onConfirmStart = { station, _, _, _, _, packageId, extraControllers, _ ->
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
        val second = compose.onNodeWithTag("gaming-station-ps5-2").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("gaming-station-ps5-3").fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithTag("gaming-station-ps5-4").fetchSemanticsNode().boundsInRoot
        val eighth = compose.onNodeWithTag("gaming-station-streaming-1").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("gaming-station-board").fetchSemanticsNode().boundsInRoot
        if (width >= 900) {
            val pane = compose.onNodeWithTag("gaming-control-pane").fetchSemanticsNode().boundsInRoot
            assertEquals(first.top, second.top, 1f)
            assertTrue("Second station must be alongside first", second.left > first.right)
            assertEquals(third.top, fourth.top, 1f)
            assertTrue("The second station row must follow the first", third.top > first.bottom)
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

        // An unsent choice belongs to its station. Returning must retain the
        // exact Dual tariff and added player without changing another station.
        compose.onNodeWithTag("gaming-station-ps5-2").performScrollTo().performClick()
        compose.onNodeWithText("Start 30 min · ₹130.00").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("ps5-2" to "dual-30", confirmed)
            assertEquals(1, confirmedExtraControllers)
        }

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
        // Hourly/open-ended sessions still retain their truthful live timer;
        // only the package-duration face uses the permanent published dial.
        compose.onNodeWithText("ELAPSED TIME").assertIsDisplayed()
        compose.onNodeWithText("Open-ended session").assertIsDisplayed()
        compose.onNodeWithText("Stop & calculate").assertIsDisplayed()
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
        compose.onNodeWithText("Send to POS or Void this unpaid session here before starting again.")
            .performScrollTo().assertIsDisplayed()
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
    fun vrModeDialSubmitsExactPublishedBasePackage() {
        var submitted: String? = null
        compose.setContent {
            DCompanyTheme {
                StartSessionEditor(
                    station = Station("vr-1", "VR-1", "VR Pod 1", "vr", 12_000),
                    packages = listOf(
                        GamingPackage("games-15", "vr-games-15m", "vr", "standard", "vr_games", 1, 1, "base", "VR Games 15", 15, 8_000),
                        GamingPackage("games-30", "vr-games-30m", "vr", "standard", "vr_games", 1, 1, "base", "VR Games 30", 30, 12_000),
                        GamingPackage("racing-15", "vr-racing-15m", "vr", "standard", "vr_racing", 1, 1, "base", "VR Racing 15", 15, 10_000),
                        GamingPackage("racing-30", "vr-racing-30m", "vr", "standard", "vr_racing", 1, 1, "base", "VR Racing 30", 30, 14_000),
                    ),
                    onDismiss = {},
                    onConfirm = { _, _, _, _, packageId, _ -> submitted = packageId },
                )
            }
        }

        compose.onNodeWithText("VR Racing Sim").performClick()
        compose.onNodeWithContentDescription("Session duration dial")
            .performTouchInput { click(Offset(width * 0.50f, height * 0.90f)) }
        compose.onNodeWithText("Start 30 min · ₹140.00")
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("racing-30", submitted) }
    }

    @Test
    fun inlinePackageExtensionChargesOnlyOnOneExplicitConfirm() {
        val station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000)
        val session = GameSession(
            id = "session-1", stationId = station.id, shiftId = "shift-1",
            status = "active", startAt = "2026-09-30T10:00:00Z",
            timerMinutes = 60, amountMinor = 12_000, billingMode = "package",
            packageId = "single-60", packagePriceMinorSnapshot = 12_000,
            packageDurationMinutesSnapshot = 60, packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5", packagePricingTierSnapshot = "standard",
            effectivePackageId = "single-60", effectivePackagePriceMinor = 12_000,
            effectivePackageDurationMinutes = 60, effectivePackageVariant = "single",
            effectivePackageStationType = "ps5", effectivePackagePricingTier = "standard",
        )
        val extensions = listOf(
            GamingPackage("extend-30", "single-extension-30m", "ps5", "standard", "single", 1, 1, "extension", "Add 30 minutes", 30, 6_000),
            GamingPackage("extend-60", "single-extension-60m", "ps5", "standard", "single", 1, 1, "extension", "Add 60 minutes", 60, 10_000),
        )
        val state = mutableStateOf(GamingUiState(
            stations = listOf(station), packages = extensions, sessions = listOf(session),
            activeShiftId = "shift-1", activeShiftServerConfirmed = true,
            everSynced = true, refreshing = false, online = true,
        ))
        var confirms = 0
        var confirmedPackageId: String? = null
        var fallbackDialogs = 0
        compose.setContent {
            DCompanyTheme {
                val compactConfiguration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 960
                    screenHeightDp = 600
                }
                CompositionLocalProvider(LocalConfiguration provides compactConfiguration) {
                Box(Modifier.width(960.dp).height(600.dp).testTag("extension-compact-viewport")) {
                GamingCommandWorkspace(
                    state = state.value,
                    access = GamingAccess(canManageSessions = true),
                    filters = listOf(StationFilter("all", "All")),
                    selectedFilter = "all",
                    visibleStations = listOf(station),
                    selectedStationId = station.id,
                    showDetail = false,
                    focusStationId = null,
                    focusRequested = false,
                    startTerminalBlockMessage = null,
                    wallClock = remember { mutableLongStateOf(1_780_134_000_000L) },
                    attentionCount = 0,
                    onSelectFilter = {}, onSelectStation = {}, onBackToBoard = {},
                    onOpenAttention = {}, onRefresh = {}, onStart = {},
                    onConfirmStart = { _, _, _, _, _, _, _, _ -> },
                    onStop = { _, _ -> }, onSend = {}, onCancelUnbilled = {},
                    onExtendTimer = {}, onExtendPackage = { _, _ -> fallbackDialogs++ },
                    onTransfer = {}, onPauseResume = {}, onReconcile = {},
                    onRepairBilling = {}, onResolveLegacyStart = {},
                    onDiscardPackageExtension = {}, onAddItems = {},
                    onVoidAddon = { _, _ -> }, onReviewRejectedAddon = {},
                    onManageParticipants = {},
                    onConfirmExtension = { review ->
                        confirms++
                        confirmedPackageId = review.extension.id
                        null
                    },
                )
                }
                }
            }
        }

        val runningRingBounds = compose.onNodeWithTag("gaming-control-ring")
            .fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Extend").assertIsDisplayed().performClick()
        compose.onNodeWithTag("gaming-extension-preview").assertIsDisplayed()
        compose.onNodeWithText("ELAPSED TIME").assertIsDisplayed()
        assertEquals(
            runningRingBounds,
            compose.onNodeWithTag("gaming-control-ring").fetchSemanticsNode().boundsInRoot,
        )
        compose.onNodeWithText("Add 60 min · ₹100.00").assertIsDisplayed().performClick()
        compose.onNodeWithText("Resulting booked total · ₹220.00")
            .assertIsDisplayed()
        val extensionActions = compose.onNodeWithTag("gaming-extension-actions")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val extensionViewport = compose.onNodeWithTag("extension-compact-viewport")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "Extension confirmation actions must remain in the 960x600 workspace: " +
                "actions=$extensionActions viewport=$extensionViewport",
            extensionActions.top >= extensionViewport.top &&
                extensionActions.bottom <= extensionViewport.bottom,
        )
        compose.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(0, fallbackDialogs)
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, confirms) }

        compose.onNodeWithText("Extend").assertIsDisplayed().performClick()
        compose.onNodeWithText("Add 60 min · ₹100.00").assertIsDisplayed().performClick()
        compose.runOnIdle {
            state.value = state.value.copy(
                packages = extensions.map {
                    if (it.id == "extend-60") it.copy(priceMinor = 11_000) else it
                },
            )
        }
        compose.onNodeWithText(
            "The extension price or duration changed. Reopen Extend and review the new total.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Confirm extension").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, confirms) }
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { state.value = state.value.copy(packages = extensions) }

        compose.onNodeWithText("Extend").assertIsDisplayed().performClick()
        compose.onNodeWithText("Add 60 min · ₹100.00").assertIsDisplayed().performClick()
        compose.onNodeWithText("Confirm extension").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, confirms)
            assertEquals("extend-60", confirmedPackageId)
            assertEquals(0, fallbackDialogs)
        }
    }

    @Test
    fun compactRunningSessionControlPaneMeasuresAt800By640() {
        val station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000)
        val session = GameSession(
            id = "compact-session", stationId = station.id, shiftId = "shift-1",
            status = "active", startAt = "2026-09-30T10:00:00Z",
            timerMinutes = 60, amountMinor = 12_000, billingMode = "package",
            packageId = "single-60", packagePriceMinorSnapshot = 12_000,
            packageDurationMinutesSnapshot = 60, packageVariantSnapshot = "single",
            packageStationTypeSnapshot = "ps5", packagePricingTierSnapshot = "standard",
            effectivePackageId = "single-60", effectivePackagePriceMinor = 12_000,
            effectivePackageDurationMinutes = 60, effectivePackageVariant = "single",
            effectivePackageStationType = "ps5", effectivePackagePricingTier = "standard",
        )
        val state = GamingUiState(
            stations = listOf(station), sessions = listOf(session),
            activeShiftId = "shift-1", activeShiftServerConfirmed = true,
            everSynced = true, refreshing = false, online = true,
        )
        compose.setContent {
            DCompanyTheme {
                val compactConfiguration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 800
                    screenHeightDp = 640
                }
                CompositionLocalProvider(LocalConfiguration provides compactConfiguration) {
                    Box(Modifier.width(800.dp).height(640.dp)) {
                        GamingCommandWorkspace(
                            state = state,
                            access = GamingAccess(canManageSessions = true),
                            filters = listOf(StationFilter("all", "All")),
                            selectedFilter = "all",
                            visibleStations = listOf(station),
                            selectedStationId = station.id,
                            showDetail = false,
                            focusStationId = null,
                            focusRequested = false,
                            startTerminalBlockMessage = null,
                            wallClock = remember { mutableLongStateOf(1_780_134_000_000L) },
                            attentionCount = 0,
                            onSelectFilter = {}, onSelectStation = {}, onBackToBoard = {},
                            onOpenAttention = {}, onRefresh = {}, onStart = {},
                            onConfirmStart = { _, _, _, _, _, _, _, _ -> },
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

        compose.onNodeWithTag("gaming-board-and-controls").performScrollToIndex(1)
        compose.onNodeWithTag("gaming-control-pane").assertIsDisplayed()
    }

    @Test
    fun compactAvailablePs5StartEditorRecoversAfterInitialStationSyncAt800By640() {
        val station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000)
        val packages = listOf(
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
            GamingPackage(
                id = "dual-30", code = "dual-30", stationType = "ps5",
                variant = "dual", includedPlayers = 2, maxPlayers = 4,
                kind = "base", name = "Dual 30", durationMinutes = 30,
                priceMinor = 10_000,
            ),
        )
        val state = mutableStateOf(GamingUiState())
        val visibleStations = mutableStateOf(emptyList<Station>())
        val selectedStationId = mutableStateOf<String?>(null)
        var submittedPackageId: String? = null
        compose.setContent {
            DCompanyTheme {
                val compactConfiguration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 800
                    screenHeightDp = 640
                }
                CompositionLocalProvider(LocalConfiguration provides compactConfiguration) {
                    Box(Modifier.width(800.dp).height(640.dp)) {
                        GamingCommandWorkspace(
                            state = state.value,
                            access = GamingAccess(canManageSessions = true),
                            filters = listOf(StationFilter("all", "All")),
                            selectedFilter = "all",
                            visibleStations = visibleStations.value,
                            selectedStationId = selectedStationId.value,
                            showDetail = false,
                            focusStationId = null,
                            focusRequested = false,
                            startTerminalBlockMessage = null,
                            wallClock = remember { mutableLongStateOf(1_780_134_000_000L) },
                            attentionCount = 0,
                            onSelectFilter = {}, onSelectStation = {}, onBackToBoard = {},
                            onOpenAttention = {}, onRefresh = {}, onStart = {},
                            onConfirmStart = { _, _, _, _, _, packageId, _, _ ->
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

        compose.runOnIdle {
            state.value = GamingUiState(
                refreshing = false,
                refreshError = "The first station sync timed out.",
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            state.value = GamingUiState(
                stations = listOf(station), packages = packages,
                everSynced = true, refreshing = false, activeShiftId = "fixture-shift",
                activeShiftServerConfirmed = true, online = true,
            )
            visibleStations.value = listOf(station)
            selectedStationId.value = station.id
        }

        compose.onNodeWithTag("gaming-board-and-controls").performScrollToIndex(1)
        compose.onNodeWithTag("gaming-control-pane").assertIsDisplayed()
        compose.onNodeWithTag("gaming-start-inline").assertIsDisplayed()
        compose.onNodeWithText("Single").assertIsDisplayed()
        compose.onNodeWithText("Dual").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add player").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search saved customers by name or phone")
            .assertIsDisplayed()
        compose.onNodeWithTag("gaming-start-action").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("single-30", submittedPackageId) }
    }

    @Test
    fun portraitAvailableRacingStartEditorMeasuresAt800By1280() {
        val station = Station("racing-1", "RACE-1", "Racing Simulator 1", "racing", 10_000)
        val packages = listOf(
            GamingPackage(
                id = "racing-30", code = "racing-30", stationType = "racing",
                variant = "simdrive", kind = "base", name = "Racing 30",
                durationMinutes = 30, priceMinor = 7_000,
            ),
            GamingPackage(
                id = "racing-60", code = "racing-60", stationType = "racing",
                variant = "simdrive", kind = "base", name = "Racing 60",
                durationMinutes = 60, priceMinor = 10_000,
            ),
        )
        var submittedPackageId: String? = null
        compose.setContent {
            DCompanyTheme {
                val portraitConfiguration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 800
                    screenHeightDp = 1_280
                }
                CompositionLocalProvider(LocalConfiguration provides portraitConfiguration) {
                    Box(Modifier.width(800.dp).height(1_280.dp)) {
                        GamingCommandWorkspace(
                            state = GamingUiState(
                                stations = listOf(station), packages = packages,
                                everSynced = true, refreshing = false,
                                activeShiftId = "fixture-shift",
                                activeShiftServerConfirmed = true, online = true,
                            ),
                            access = GamingAccess(canManageSessions = true),
                            filters = listOf(StationFilter("all", "All")),
                            selectedFilter = "all",
                            visibleStations = listOf(station),
                            selectedStationId = station.id,
                            showDetail = false,
                            focusStationId = null,
                            focusRequested = false,
                            startTerminalBlockMessage = null,
                            wallClock = remember { mutableLongStateOf(1_780_134_000_000L) },
                            attentionCount = 0,
                            onSelectFilter = {}, onSelectStation = {}, onBackToBoard = {},
                            onOpenAttention = {}, onRefresh = {}, onStart = {},
                            onConfirmStart = { _, _, _, _, _, packageId, _, _ ->
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

        compose.onNodeWithTag("gaming-board-and-controls").performScrollToIndex(1)
        compose.onNodeWithTag("gaming-start-inline").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search saved customers by name or phone")
            .assertIsDisplayed()
        compose.onNodeWithTag("gaming-start-action").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("racing-30", submittedPackageId) }
    }

    @Test
    fun bookedPackageSubtotalNeverClaimsToIncludeThreeAddedPlayers() {
        val station = Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000)
        val start = Instant.parse("2026-10-22T10:00:00Z")
        val running = GameSession(
            id = "booked-subtotal-session", stationId = station.id, shiftId = "shift-1",
            status = "active", startAt = start.toString(),
            timerMinutes = 30, timerEndsAt = start.plusSeconds(1_800).toString(),
            amountMinor = 8_000, billingMode = "package", packageId = "single-30",
            packagePriceMinorSnapshot = 8_000, packageDurationMinutesSnapshot = 30,
            packageVariantSnapshot = "single", packageStationTypeSnapshot = "ps5",
            packagePricingTierSnapshot = "standard", participantRevision = 3,
        )
        val state = mutableStateOf(GamingUiState(
            stations = listOf(station), sessions = listOf(running),
            participantCache = (1..3).map { index ->
                GamingSessionParticipantCacheEntity(
                    id = "joined-$index", gamingSessionId = running.id,
                    participantIdentityId = "guest-$index",
                    joinedAtMillis = start.toEpochMilli(), joinedPlayElapsedMs = 0,
                    joinRevision = index,
                )
            },
            everSynced = true, refreshing = false, online = true,
            activeShiftId = "shift-1", activeShiftServerConfirmed = true,
        ))
        val drink = GamingSessionAddonUi(
            id = "confirmed-cola", serverAddonId = "confirmed-cola",
            serverSessionId = running.id, clientLineId = "cola-line",
            menuItemId = "cola", menuItemName = "Cola", menuItemType = "beverage",
            qty = 1, unitPriceMinor = 5_000, lineTotalMinor = 5_000,
        )
        compose.setContent {
            DCompanyTheme {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 1_280
                    screenHeightDp = 800
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    Box(Modifier.width(1_280.dp).height(744.dp)) {
                        GamingCommandWorkspace(
                            state = state.value, access = GamingAccess(canManageSessions = true),
                            filters = listOf(StationFilter("all", "All")), selectedFilter = "all",
                            visibleStations = listOf(station), selectedStationId = station.id,
                            showDetail = false, focusStationId = null, focusRequested = false,
                            startTerminalBlockMessage = null,
                            wallClock = remember { mutableLongStateOf(start.plusSeconds(600).toEpochMilli()) },
                            attentionCount = 0, onSelectFilter = {}, onSelectStation = {},
                            onBackToBoard = {}, onOpenAttention = {}, onRefresh = {}, onStart = {},
                            onConfirmStart = { _, _, _, _, _, _, _, _ -> },
                            onStop = { _, _ -> }, onSend = {}, onCancelUnbilled = {},
                            onExtendTimer = {}, onExtendPackage = { _, _ -> }, onTransfer = {},
                            onPauseResume = {}, onReconcile = {}, onRepairBilling = {},
                            onResolveLegacyStart = {}, onDiscardPackageExtension = {},
                            onAddItems = {}, onVoidAddon = { _, _ -> },
                            onReviewRejectedAddon = {}, onManageParticipants = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Booked session ₹80.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Booked package · ₹80.00").assertIsDisplayed()
        compose.onNodeWithText("Booked package price is locked; added players and drinks are extra.")
            .assertIsDisplayed()
        compose.onNodeWithText("Estimated ₹80.00").assertDoesNotExist()
        compose.onNodeWithText("Package total", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Players").assertIsDisplayed().performClick()
        compose.onNodeWithText("Players · 4/4").assertIsDisplayed()
        compose.onNodeWithText("3 added players playing now · 1 booked").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(9_000L, estimatedGamingFriendControllerMinor(
                state.value.participantsFor(running), 600_000L,
            ))
        }
        compose.onNodeWithTag("gaming-player-inline-close").performClick()
        compose.runOnIdle { state.value = state.value.copy(sessionAddons = listOf(drink)) }
        compose.onNodeWithText("Booked + drinks ₹130.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Booked package · ₹80.00").assertIsDisplayed()
        compose.onNodeWithText("Estimated ₹130.00").assertDoesNotExist()
        compose.runOnIdle {
            // A server-voided drink remains in history but contributes no money.
            state.value = state.value.copy(sessionAddons = listOf(drink.copy(
                voided = true, voidReason = "Entered in error",
            )))
        }
        compose.onNodeWithText("Booked session ₹80.00", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(sessions = listOf(running.copy(
                status = "ended", endAt = start.plusSeconds(600).toString(),
                amountMinor = 17_000, billableMinutes = 10,
            )))
        }
        compose.onNode(
            hasText("₹170.00") and hasAnyAncestor(hasTestTag("gaming-station-ps5-1")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("Booked session ₹80.00").assertDoesNotExist()
        compose.onNodeWithText("₹260.00").assertDoesNotExist()
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
            addonCatalog = gamingDrinkFixtureItems(),
            addonVariants = listOf(
                MenuVariantEntity(
                    id = "custom-shake-large",
                    menuItemId = "custom-shake",
                    name = "Large",
                    priceDeltaMinor = 2_000L,
                    sortOrder = 1,
                    isActive = true,
                ),
            ),
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
        val attentionCount = mutableStateOf(0)
        var submittedPackageId: String? = null
        val quickAdds = mutableListOf<Pair<String, Int>>()
        compose.setContent {
            DCompanyTheme {
                val productionViewport = if (
                    LocalConfiguration.current.screenWidthDp >= 1_100
                ) {
                    // The target 1280x800/160dpi tablet reserves 24dp for the
                    // status bar and 32dp for system navigation. MainActivity
                    // therefore gives the app a 744dp content viewport.
                    Modifier.fillMaxWidth().height(744.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                Box(productionViewport.testTag("gaming-full-workspace")) {
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
                            attentionCount = attentionCount.value,
                            onSelectFilter = {}, onSelectStation = {},
                            onBackToBoard = {}, onOpenAttention = {}, onRefresh = {},
                            onStart = {}, onConfirmStart = { _, _, _, _, _, packageId, _, _ ->
                                submittedPackageId = packageId
                            },
                            onStop = { _, _ -> }, onSend = {}, onCancelUnbilled = {},
                            onExtendTimer = {}, onExtendPackage = { _, _ -> },
                            onTransfer = {}, onPauseResume = {}, onReconcile = {},
                            onRepairBilling = {}, onResolveLegacyStart = {},
                            onDiscardPackageExtension = {}, onAddItems = {},
                            onQuickAddItem = { _, item, quantity ->
                                quickAdds += item.id to quantity
                            },
                            onVoidAddon = { _, _ -> }, onReviewRejectedAddon = {},
                            onManageParticipants = {},
                            shiftAccess = ShiftAccess(canOpen = true, canClose = true),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("gaming-filter-ps5").assertIsDisplayed()
        compose.onNodeWithTag("gaming-filter-racing").assertIsDisplayed()
        compose.onNodeWithTag("gaming-filter-vr").assertIsDisplayed()
        compose.onNodeWithText("Gaming board ready").assertDoesNotExist()
        compose.onNodeWithTag("gaming-attention-bar").assertDoesNotExist()
        val readyHeaderBounds = compose.onNodeWithTag("gaming-board-header")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.runOnIdle { state.value = state.value.copy(refreshing = true) }
        compose.onNodeWithText("Refreshing…").assertIsDisplayed()
        assertEquals(
            "Refreshing must not move the station header or controls",
            readyHeaderBounds,
            compose.onNodeWithTag("gaming-board-header").getUnclippedBoundsInRoot(),
        )
        compose.runOnIdle { state.value = state.value.copy(refreshing = false) }
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
            assertTrue(
                "Station board fits in the full workspace: last=$lastStation pane=$pane",
                lastStation.bottom <= pane.bottom,
            )
            compose.onNodeWithContentDescription("Session duration dial").assertIsDisplayed()
            compose.onNodeWithContentDescription("Search saved customers by name or phone")
                .assertIsDisplayed()
            compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
            val wideDraftRingBounds = compose.onNodeWithTag("gaming-control-ring")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            val wideDraftPrimaryBounds = compose.onNodeWithTag("gaming-primary-action-host")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            capture("gaming-full-workspace-1280x800.png", "gaming-full-workspace")

            compose.runOnIdle {
                state.value = state.value.copy(
                    activeShiftId = null,
                    activeShiftServerConfirmed = false,
                )
            }
            compose.onNodeWithText("Open shift here").assertIsDisplayed()
            compose.onNodeWithText("Sync shift state").assertIsDisplayed()
            compose.onNodeWithContentDescription("Add new customer").assertIsDisplayed()
            val blockedSearch = compose.onNodeWithContentDescription(
                "Search saved customers by name or phone",
            ).fetchSemanticsNode()
            val blockedSearchBounds = blockedSearch.boundsInRoot
            val minimumTouchHeightPx = 48f * compose.activity.resources.displayMetrics.density
            assertTrue(
                "Closed-shift recovery must leave the full customer lookup visible: " +
                    "bounds=$blockedSearchBounds measured=${blockedSearch.size}",
                blockedSearchBounds.height >= minimumTouchHeightPx - 1f &&
                    abs(blockedSearchBounds.height - blockedSearch.size.height) <= 1f,
            )
            assertEquals(
                "Closed-shift recovery controls must not move the duration dial",
                wideDraftRingBounds,
                compose.onNodeWithTag("gaming-control-ring")
                    .assertIsDisplayed().getUnclippedBoundsInRoot(),
            )
            compose.onNodeWithTag("gaming-start-action").assertIsNotEnabled()

            compose.runOnIdle {
                state.value = state.value.copy(
                    activeShiftId = "fixture-shift",
                    activeShiftServerConfirmed = true,
                )
            }

            compose.runOnIdle {
                state.value = state.value.copy(sessions = listOf(GameSession(
                    id = "wide-session", stationId = "ps5-1", shiftId = "fixture-shift",
                    status = "starting", startAt = "2026-09-27T10:00:00Z",
                    timerMinutes = 30, timerEndsAt = "2026-09-27T10:30:00Z",
                    ratePerHourMinor = 12_000,
                    packageId = "single-30", packagePriceMinorSnapshot = 8_000,
                    packageDurationMinutesSnapshot = 30, packageVariantSnapshot = "single",
                    packageStationTypeSnapshot = "ps5", localState = GamingSessionState.START_PENDING,
                )))
            }
            val wideStartingRingBounds = compose.onNodeWithTag("gaming-control-ring")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            val wideStartingPrimaryBounds = compose.onNodeWithTag("gaming-primary-action-host")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(
                "1280dp draft and pending-start dial must keep identical geometry: " +
                    "draft=$wideDraftRingBounds starting=$wideStartingRingBounds",
                abs(wideDraftRingBounds.left.value - wideStartingRingBounds.left.value) <= 1f &&
                    abs(wideDraftRingBounds.top.value - wideStartingRingBounds.top.value) <= 1f &&
                    abs((wideDraftRingBounds.right - wideDraftRingBounds.left).value -
                        (wideStartingRingBounds.right - wideStartingRingBounds.left).value) <= 1f &&
                    abs((wideDraftRingBounds.bottom - wideDraftRingBounds.top).value -
                        (wideStartingRingBounds.bottom - wideStartingRingBounds.top).value) <= 1f,
            )
            assertEquals(
                "Start and pending-start primary actions must keep identical bounds",
                wideDraftPrimaryBounds,
                wideStartingPrimaryBounds,
            )
            capture("gaming-full-workspace-1280x800-starting.png", "gaming-full-workspace")

            compose.runOnIdle {
                state.value = state.value.copy(sessions = listOf(GameSession(
                    id = "wide-session", stationId = "ps5-1", shiftId = "fixture-shift",
                    status = "active", startAt = "2026-09-27T10:00:00Z",
                    timerMinutes = 30, timerEndsAt = "2026-09-27T10:30:00Z",
                    ratePerHourMinor = 12_000,
                    packageVariantSnapshot = "single", packageStationTypeSnapshot = "ps5",
                )))
            }
            val wideActiveRingBounds = compose.onNodeWithTag("gaming-control-ring")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            val wideActivePrimaryBounds = compose.onNodeWithTag("gaming-primary-action-host")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(
                "1280dp pending-start and active timer must keep identical geometry: " +
                    "starting=$wideStartingRingBounds active=$wideActiveRingBounds",
                abs(wideStartingRingBounds.left.value - wideActiveRingBounds.left.value) <= 1f &&
                    abs(wideStartingRingBounds.top.value - wideActiveRingBounds.top.value) <= 1f &&
                    abs((wideStartingRingBounds.right - wideStartingRingBounds.left).value -
                        (wideActiveRingBounds.right - wideActiveRingBounds.left).value) <= 1f &&
                    abs((wideStartingRingBounds.bottom - wideStartingRingBounds.top).value -
                        (wideActiveRingBounds.bottom - wideActiveRingBounds.top).value) <= 1f,
            )
            assertEquals(
                "Start and active Stop actions must keep identical bounds",
                wideDraftPrimaryBounds,
                wideActivePrimaryBounds,
            )
            compose.onNodeWithText("Stop & calculate").assertIsDisplayed()
            compose.runOnIdle {
                state.value = state.value.copy(
                    participantCache = listOf(
                        GamingSessionParticipantCacheEntity(
                            id = "participant-one",
                            gamingSessionId = "wide-session",
                            participantIdentityId = "guest-one",
                            guestName = "Guest one",
                            joinedAtMillis = 1_779_989_200_000L,
                            joinedPlayElapsedMs = 1_000L,
                            joinRevision = 1,
                        ),
                        GamingSessionParticipantCacheEntity(
                            id = "participant-two",
                            gamingSessionId = "wide-session",
                            participantIdentityId = "guest-two",
                            guestName = "Guest two",
                            joinedAtMillis = 1_779_989_201_000L,
                            joinedPlayElapsedMs = 2_000L,
                            joinRevision = 2,
                        ),
                    ),
                )
            }
            compose.onNodeWithText("Players").assertIsDisplayed().performClick()
            compose.onNodeWithTag("gaming-player-inline").assertIsDisplayed()
            assertEquals(
                "Opening Players must not move the primary Stop action",
                wideActivePrimaryBounds,
                compose.onNodeWithTag("gaming-primary-action-host")
                    .assertIsDisplayed().getUnclippedBoundsInRoot(),
            )
            val pinnedConfirm = compose.onNodeWithTag("gaming-player-inline-confirm")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            val playerEditor = compose.onNodeWithTag("gaming-player-inline")
                .getUnclippedBoundsInRoot()
            assertTrue(
                "The add-player confirmation must stay pinned after two participant rows: " +
                    "confirm=$pinnedConfirm editor=$playerEditor",
                pinnedConfirm.top >= playerEditor.top && pinnedConfirm.bottom <= playerEditor.bottom,
            )
            compose.onNodeWithTag("gaming-player-inline-close")
                .assertIsDisplayed().performClick()
            capture(
                "gaming-full-workspace-1280x800-post-player-close.png",
                "gaming-full-workspace",
            )
            compose.onRoot(useUnmergedTree = true).printToLog("GamingPostPlayerClose")
            compose.onNodeWithText("Add drinks").assertIsDisplayed().performClick()
            compose.onNodeWithTag("gaming-drinks-inline").assertIsDisplayed()
            compose.onNodeWithTag("gaming-drinks-search").assertIsDisplayed()
            compose.onNodeWithTag("gaming-drinks-catalogue").assertIsDisplayed()
            assertEquals(
                "Opening Add drinks must not move the primary Stop action",
                wideActivePrimaryBounds,
                compose.onNodeWithTag("gaming-primary-action-host")
                    .assertIsDisplayed().getUnclippedBoundsInRoot(),
            )
            compose.onNodeWithTag("gaming-drinks-search").performTextInput("Mango")
            compose.onNodeWithTag("gaming-drinks-catalogue").performScrollToIndex(2)
            compose.onNodeWithText("Select").assertIsDisplayed().performClick()
            assertIntegratedDrinksFooterInsideControlPane()
            compose.onNodeWithTag("gaming-drinks-inline-confirm").performClick()
            compose.runOnIdle { assertEquals(listOf("mango" to 1), quickAdds) }
            compose.runOnIdle {
                state.value = state.value.copy(sessions = state.value.sessions.map {
                    it.copy(status = "paused", pausedAt = "2026-09-27T10:10:00Z", pauseVersion = 1)
                })
            }
            compose.onNodeWithText("PAUSED").assertIsDisplayed()
            assertEquals("Pausing must preserve the permanent dial bounds", wideActiveRingBounds,
                compose.onNodeWithTag("gaming-control-ring").assertIsDisplayed().getUnclippedBoundsInRoot())
            assertEquals("Pausing must preserve the primary action bounds", wideActivePrimaryBounds,
                compose.onNodeWithTag("gaming-primary-action-host").assertIsDisplayed().getUnclippedBoundsInRoot())
            compose.runOnIdle {
                state.value = state.value.copy(sessions = state.value.sessions.map {
                    it.copy(status = "active", pausedAt = null, pauseVersion = 2)
                })
            }
            capture("gaming-full-workspace-1280x800-active.png", "gaming-full-workspace")

            compose.runOnIdle {
                attentionCount.value = 1
                state.value = state.value.copy(sessions = listOf(GameSession(
                    id = "wide-session", stationId = "ps5-1", shiftId = "fixture-shift",
                    status = "ended", startAt = "2026-09-27T10:00:00Z",
                    endAt = "2026-09-27T11:00:00Z", amountMinor = 12_000,
                    ratePerHourMinor = 12_000,
                )))
            }
            compose.onNodeWithTag("gaming-attention-bar").assertIsDisplayed()
            assertEquals(
                "Payment attention must share the existing header height",
                readyHeaderBounds,
                compose.onNodeWithTag("gaming-board-header").getUnclippedBoundsInRoot(),
            )
            assertEquals(
                "Payment attention must update the status rail without moving the dial",
                wideActiveRingBounds,
                compose.onNodeWithTag("gaming-control-ring")
                    .assertIsDisplayed().getUnclippedBoundsInRoot(),
            )
            val paymentPane = compose.onNodeWithTag("gaming-control-pane")
                .getUnclippedBoundsInRoot()
            val paymentLastStation = compose.onNodeWithTag("gaming-station-streaming-1")
                .getUnclippedBoundsInRoot()
            assertTrue(
                "Payment attention must keep every station visible: " +
                    "last=$paymentLastStation pane=$paymentPane",
                paymentLastStation.bottom <= paymentPane.bottom,
            )

            compose.runOnIdle {
                attentionCount.value = 0
                state.value = state.value.copy(sessions = emptyList())
            }

            val tenStations = stations + listOf(
                Station("ps5-5", "PS5-5", "PS5 Station 5", "ps5", 12_000),
                Station("ps5-6", "PS5-6", "PS5 Station 6", "ps5", 12_000),
            )
            compose.runOnIdle {
                visibleStations.value = tenStations
                state.value = state.value.copy(stations = tenStations)
            }
            compose.onNodeWithTag("gaming-station-ps5-6")
                .performScrollTo().assertIsDisplayed()
            val tenth = compose.onNodeWithTag("gaming-station-ps5-6")
                .getUnclippedBoundsInRoot()
            val expandedPane = compose.onNodeWithTag("gaming-control-pane")
                .getUnclippedBoundsInRoot()
            assertTrue(
                "Stations beyond the approved 2x4 board remain reachable by scrolling: " +
                    "tenth=$tenth pane=$expandedPane",
                tenth.top >= expandedPane.top && tenth.bottom <= expandedPane.bottom,
            )
            capture("gaming-full-workspace-10-stations-1280x800.png", "gaming-full-workspace")
        } else {
            val board = compose.onNodeWithTag("gaming-station-board")
                .getUnclippedBoundsInRoot()
            val pane = compose.onNodeWithTag("gaming-control-pane")
                .getUnclippedBoundsInRoot()
            val eighth = compose.onNodeWithTag("gaming-station-streaming-1")
                .getUnclippedBoundsInRoot()
            assertTrue("960dp board and dial share the viewport", board.right < pane.left)
            assertTrue(
                "All eight cards remain above the bottom navigation: eighth=$eighth pane=$pane",
                eighth.bottom <= pane.bottom,
            )
            compose.onNodeWithTag("gaming-station-streaming-1").assertIsDisplayed()
            val dial = compose.onNodeWithContentDescription("Session duration dial")
                .assertIsDisplayed()
            val draftRingBounds = compose.onNodeWithTag("gaming-control-ring")
                .getUnclippedBoundsInRoot()
            val draftRingFace = bitmapFixedRingPixels(
                compose.onNodeWithTag("gaming-control-ring-geometry", useUnmergedTree = true)
                    .captureToImage().asAndroidBitmap(),
            )
            compose.onNodeWithTag("gaming-control-ring-remaining", useUnmergedTree = true)
                .assertDoesNotExist()
            compose.onNodeWithContentDescription(
                "Gaming duration ring. The booking marker stays fixed while the inner trace shows remaining time.",
            ).assertExists()
            compose.onNodeWithText("₹80.00").assertIsDisplayed()
            compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
            compose.onNodeWithText("Est. finish ·", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription(
                "Expected finish if started now, about",
                substring = true,
            ).assertIsDisplayed()
            val dialNode = dial.fetchSemanticsNode()
            val searchNode = compose.onNodeWithContentDescription(
                "Search saved customers by name or phone",
            ).fetchSemanticsNode()
            val clippedSearchBounds = searchNode.boundsInRoot
            val startNode = compose.onNodeWithText("Start 30 min · ₹80.00")
                .fetchSemanticsNode()
            val paneNode = compose.onNodeWithTag("gaming-control-pane").fetchSemanticsNode()
            val dialPosition = dialNode.positionOnScreen
            val searchPosition = searchNode.positionOnScreen
            val startPosition = startNode.positionOnScreen
            val panePosition = paneNode.positionOnScreen
            val dialBounds = Rect(
                dialPosition.x, dialPosition.y,
                dialPosition.x + dialNode.size.width, dialPosition.y + dialNode.size.height,
            )
            val searchBounds = Rect(
                searchPosition.x, searchPosition.y,
                searchPosition.x + searchNode.size.width,
                searchPosition.y + searchNode.size.height,
            )
            val startBounds = Rect(
                startPosition.x, startPosition.y,
                startPosition.x + startNode.size.width,
                startPosition.y + startNode.size.height,
            )
            val paneBounds = Rect(
                panePosition.x, panePosition.y,
                panePosition.x + paneNode.size.width, panePosition.y + paneNode.size.height,
            )
            val tolerancePx = 1f
            assertTrue(
                "Duration dial must not overlap customer controls: dial=$dialBounds, " +
                    "search=$searchBounds",
                dialBounds.right <= searchBounds.left + tolerancePx,
            )
            assertTrue(
                "The complete customer lookup must fit inside the control pane above the " +
                    "Start footer: search=$searchBounds, start=$startBounds, pane=$paneBounds",
                searchBounds.left >= paneBounds.left - tolerancePx &&
                    searchBounds.top >= paneBounds.top - tolerancePx &&
                    searchBounds.right <= paneBounds.right + tolerancePx &&
                    searchBounds.bottom <= startBounds.top + tolerancePx &&
                    startBounds.bottom <= paneBounds.bottom + tolerancePx,
            )
            assertTrue(
                "The customer lookup must not be clipped by the form viewport: " +
                    "clipped=$clippedSearchBounds, measured=$searchBounds",
                abs(clippedSearchBounds.height - searchNode.size.height) <= tolerancePx,
            )
            capture("gaming-full-workspace-960x600-board-and-dial.png", "gaming-full-workspace")
            dial.performTouchInput {
                down(Offset(width * 0.50f, height * 0.90f))
                repeat(12) { step ->
                    val radians = Math.toRadians((90f + (step + 1) * 15f).toDouble())
                    moveTo(Offset(width * (0.5f + 0.4f * kotlin.math.cos(radians).toFloat()),
                        height * (0.5f + 0.4f * kotlin.math.sin(radians).toFloat())), delayMillis = 16)
                }
                up()
            }
            compose.onNodeWithText("Start 60 min · ₹120.00").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("single-60", submittedPackageId) }
            dial.performTouchInput {
                down(Offset(width * 0.50f, height * 0.10f))
                repeat(12) { step ->
                    val radians = Math.toRadians((270f - (step + 1) * 15f).toDouble())
                    moveTo(Offset(width * (0.5f + 0.4f * kotlin.math.cos(radians).toFloat()),
                        height * (0.5f + 0.4f * kotlin.math.sin(radians).toFloat())), delayMillis = 16)
                }
                up()
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
                    status = "starting", startAt = "2026-09-27T10:00:00Z",
                    timerMinutes = 30, timerEndsAt = "2026-09-27T10:30:00Z",
                    ratePerHourMinor = 12_000,
                    packageId = "single-30", packagePriceMinorSnapshot = 8_000,
                    packageDurationMinutesSnapshot = 30, packageVariantSnapshot = "single",
                    packageStationTypeSnapshot = "ps5", localState = GamingSessionState.START_PENDING,
                )))
            }
            val startingRingBounds = compose.onNodeWithTag("gaming-control-ring")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(
                "Draft and pending-start dial must keep identical geometry: " +
                    "draft=$draftRingBounds starting=$startingRingBounds",
                abs(draftRingBounds.left.value - startingRingBounds.left.value) <= 1f &&
                    abs(draftRingBounds.top.value - startingRingBounds.top.value) <= 1f &&
                    abs((draftRingBounds.right - draftRingBounds.left).value -
                        (startingRingBounds.right - startingRingBounds.left).value) <= 1f &&
                    abs((draftRingBounds.bottom - draftRingBounds.top).value -
                        (startingRingBounds.bottom - startingRingBounds.top).value) <= 1f,
            )
            capture("gaming-full-workspace-960x600-starting.png", "gaming-full-workspace")

            compose.runOnIdle {
                // A server acknowledgement advances the same locked package
                // session. Keep its billing and station snapshots so this
                // compact integration path exercises the real PS5 actions
                // rather than an impossible replacement session.
                state.value = state.value.copy(sessions = listOf(
                    state.value.sessions.single().copy(
                        status = "active",
                        localState = null,
                    ),
                ))
            }
            val activeRing = compose.onNodeWithTag("gaming-control-ring").assertIsDisplayed()
            compose.onNodeWithTag("gaming-control-ring-remaining", useUnmergedTree = true)
                .assertIsDisplayed()
            val activeRingBounds = activeRing.getUnclippedBoundsInRoot()
            assertFalse(
                "Published duration markers become read-only after Start",
                compose.onNodeWithText("60 min", useUnmergedTree = true)
                    .fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
            )
            assertTrue(
                "Draft dial and authoritative timer must keep identical geometry: " +
                    "draft=$draftRingBounds active=$activeRingBounds",
                abs(startingRingBounds.left.value - activeRingBounds.left.value) <= 1f &&
                    abs(startingRingBounds.top.value - activeRingBounds.top.value) <= 1f &&
                    abs((draftRingBounds.right - draftRingBounds.left).value -
                        (activeRingBounds.right - activeRingBounds.left).value) <= 1f &&
                    abs((draftRingBounds.bottom - draftRingBounds.top).value -
                        (activeRingBounds.bottom - activeRingBounds.top).value) <= 1f,
            )
            val activeRingNode = activeRing.fetchSemanticsNode()
            val activeRingPosition = activeRingNode.positionOnScreen
            val activeRingScreenBounds = Rect(
                activeRingPosition.x,
                activeRingPosition.y,
                activeRingPosition.x + activeRingNode.size.width,
                activeRingPosition.y + activeRingNode.size.height,
            )
            val stopNode = compose.onNodeWithText("Stop & calculate")
                .assertIsDisplayed().fetchSemanticsNode()
            val stopPosition = stopNode.positionOnScreen
            val stopBounds = Rect(
                stopPosition.x,
                stopPosition.y,
                stopPosition.x + stopNode.size.width,
                stopPosition.y + stopNode.size.height,
            )
            assertTrue(
                "The authoritative timer and Stop action must be visible together without scrolling: " +
                    "timer=$activeRingScreenBounds stop=$stopBounds pane=$paneBounds",
                activeRingScreenBounds.top >= paneBounds.top &&
                    activeRingScreenBounds.bottom <= paneBounds.bottom &&
                    stopBounds.top >= paneBounds.top && stopBounds.bottom <= paneBounds.bottom,
            )
            assertTrue(
                "Starting must retain the outer booking ring, arc and marker geometry",
                draftRingFace.contentEquals(
                    bitmapFixedRingPixels(
                        compose.onNodeWithTag("gaming-control-ring-geometry", useUnmergedTree = true)
                            .captureToImage().asAndroidBitmap(),
                    ),
                ),
            )
            compose.onNodeWithText("Add drinks").assertIsDisplayed().performClick()
            compose.onNodeWithTag("gaming-drinks-search").assertIsDisplayed()
                .performTextInput("Mango")
            compose.onNodeWithTag("gaming-drinks-catalogue").performScrollToIndex(2)
            compose.onNodeWithText("Select").assertIsDisplayed().performClick()
            assertIntegratedDrinksFooterInsideControlPane()
            compose.onNodeWithTag("gaming-drinks-inline-confirm").performClick()
            compose.runOnIdle { assertEquals(listOf("mango" to 1), quickAdds) }
            capture("gaming-full-workspace-960x600-active-action.png", "gaming-full-workspace")
            compose.runOnIdle {
                state.value = state.value.copy(sessions = listOf(
                    state.value.sessions.single().copy(
                        status = "paused",
                        pausedAt = "2026-09-27T10:10:00Z",
                    ),
                ))
            }
            val pausedRing = compose.onNodeWithTag("gaming-control-ring").assertIsDisplayed()
            assertEquals(activeRingBounds, pausedRing.getUnclippedBoundsInRoot())
            assertTrue(
                "Pausing must retain the outer booking ring, arc and marker geometry",
                draftRingFace.contentEquals(
                    bitmapFixedRingPixels(
                        compose.onNodeWithTag("gaming-control-ring-geometry", useUnmergedTree = true)
                            .captureToImage().asAndroidBitmap(),
                    ),
                ),
            )
            compose.runOnIdle {
                attentionCount.value = 1
                state.value = state.value.copy(sessions = listOf(
                    state.value.sessions.single().copy(
                        status = "ended",
                        endAt = "2026-09-27T11:00:00Z",
                        pausedAt = null,
                        billableMinutes = 60,
                        amountMinor = 8_000,
                    ),
                ))
            }
            compose.onNodeWithTag("gaming-attention-bar").assertIsDisplayed()
            compose.onNodeWithText("Send to POS or Void this unpaid session here before starting again.")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Send to POS").performScrollTo().assertIsDisplayed()
            val stoppedRing = compose.onNodeWithTag("gaming-control-ring").assertIsDisplayed()
            assertEquals(activeRingBounds, stoppedRing.getUnclippedBoundsInRoot())
            val attentionLastStation = compose.onNodeWithTag("gaming-station-streaming-1")
                .getUnclippedBoundsInRoot()
            val attentionPane = compose.onNodeWithTag("gaming-control-pane")
                .getUnclippedBoundsInRoot()
            assertTrue(
                "Payment attention must keep all eight compact cards visible: " +
                    "last=$attentionLastStation pane=$attentionPane",
                attentionLastStation.bottom <= attentionPane.bottom,
            )
            assertTrue(
                "Stopping must retain the outer booking ring, arc and marker geometry",
                draftRingFace.contentEquals(
                    bitmapFixedRingPixels(
                        compose.onNodeWithTag("gaming-control-ring-geometry", useUnmergedTree = true)
                            .captureToImage().asAndroidBitmap(),
                    ),
                ),
            )
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
                        onStart = {}, onConfirmStart = { _, _, _, _, _, _, _, _ ->
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
        var startRequests = 0
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
                        onConfirmStart = { _, _, _, _, _, _, _, _ -> startRequests++ },
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
        compose.onNodeWithContentDescription("Session duration dial").assertIsDisplayed()
        compose.onNodeWithText("Retry acknowledgement").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start 30 min · ₹80.00")
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, startRequests) }
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

private fun gamingDrinkFixtureItems(): List<MenuItemEntity> = listOf(
    MenuItemEntity("water", "drinks", "WATER", "Water", "drink", 2_000, 0.0, null, true, true, null),
    MenuItemEntity("mango", "drinks", "MANGO", "Mango drink", "drink", 5_000, 0.0, null, true, true, null),
    MenuItemEntity("lime", "drinks", "LIME", "Lime soda", "drink", 4_000, 0.0, null, true, true, null),
    MenuItemEntity("coffee", "drinks", "COFFEE", "Cold coffee", "drink", 7_000, 0.0, null, true, true, null),
    MenuItemEntity("nachos", "snacks", "NACHOS", "Nachos", "snack", 8_000, 0.0, null, true, true, null),
    MenuItemEntity("fries", "snacks", "FRIES", "Fries", "snack", 6_000, 0.0, null, true, true, null),
    MenuItemEntity("custom-shake", "drinks", "SHAKE", "Custom shake", "drink", 9_000, 0.0, null, true, true, null),
)

private fun ringPoint(width: Int, height: Int, angleDegrees: Float): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val widthPx = width.toFloat()
    val heightPx = height.toFloat()
    val radius = minOf(widthPx, heightPx) * 0.40f
    return Offset(
        x = widthPx / 2f + radius * cos(radians).toFloat(),
        y = heightPx / 2f + radius * sin(radians).toFloat(),
    )
}

/** Captures the Canvas only; centre text is a separate sibling and may change by state. */
private fun bitmapPixels(bitmap: Bitmap): IntArray {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    bitmap.recycle()
    return pixels
}

/**
 * Samples the immutable booking track, selected arc and marker while excluding
 * the centre readout and the deliberately dynamic inner remaining-time trace.
 */
private fun bitmapFixedRingPixels(bitmap: Bitmap): IntArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val centerX = (width - 1) / 2f
    val centerY = (height - 1) / 2f
    val minimumDimension = minOf(width, height).toFloat()
    val bookingRingInner = minimumDimension * 0.38f
    pixels.indices.forEach { index ->
        val x = (index % width) - centerX
        val y = (index / width) - centerY
        val distance = kotlin.math.sqrt(x * x + y * y)
        if (distance < bookingRingInner) pixels[index] = 0
    }
    bitmap.recycle()
    return pixels
}
