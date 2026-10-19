package cloud.dcompany.erp.ui.screens.gaming

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

/** True Activity recreation, with a fresh initially empty catalogue before Room projection arrives. */
class GamingDraftRecreationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val me = MeResponse("owner", "owner@example.test", "Owner", listOf("owner"),
        companyId = "company", branchId = null, effectivePermissions = listOf("gaming.sessions.manage"))
    private fun authority(generation: Long = 1, profile: MeResponse = me, terminal: String = "till") =
        gamingDraftAuthority(profile, CacheScopeLease(CacheScope(profile.userId, profile.companyId,
            profile.branchId, terminal), generation), terminal)
    private val currentAuthority = mutableStateOf(authority())
    private val stations = (1..2).map { Station("ps5-$it", "PS5-$it", "PS5 Station $it", "ps5", 12_000) }
    private val loaded = GamingUiState(
        catalogueLoaded = true, stations = stations, everSynced = true, refreshing = false,
        activeShiftId = "fixture-shift", activeShiftServerConfirmed = true,
        packages = listOf(
            GamingPackage(id = "single-30", code = "single-30", stationType = "ps5", variant = "single",
                kind = "base", name = "Single30", durationMinutes = 30, priceMinor = 8_000),
            GamingPackage(id = "dual-30", code = "dual-30", stationType = "ps5", variant = "dual",
                kind = "base", name = "Dual30", durationMinutes = 30, priceMinor = 10_000,
                includedPlayers = 2, maxPlayers = 4),
            GamingPackage(id = "dual-60", code = "dual-60", stationType = "ps5", variant = "dual",
                kind = "base", name = "Dual60", durationMinutes = 60, priceMinor = 15_000,
                includedPlayers = 2, maxPlayers = 4),
        ),
    )
    private val projection = mutableStateOf(loaded)
    private var selectedId: String? = null

    @Composable private fun Fixture() {
        DCompanyTheme {
            GamingDraftScope(currentAuthority.value) {
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                val state = projection.value
                LaunchedEffect(state.catalogueLoaded, state.stations, selected) {
                    selected = resolveGamingCommandStationId(state.stations.map(Station::id), selected, state.catalogueLoaded)
                }
                selectedId = selected
                if (!state.catalogueLoaded) Text("Loading scoped catalogue")
                else Box(Modifier.width(1280.dp).height(744.dp)) {
                    GamingCommandWorkspace(
                        state = state, access = GamingAccess(canManageSessions = true),
                        filters = listOf(StationFilter("all", "All")), selectedFilter = "all",
                        visibleStations = state.stations, selectedStationId = selected,
                        showDetail = false, focusStationId = null, focusRequested = false,
                        startTerminalBlockMessage = null,
                        wallClock = remember { mutableLongStateOf(1_779_989_200_000L) }, attentionCount = 0,
                        onSelectFilter = {}, onSelectStation = { selected = it }, onBackToBoard = {},
                        onOpenAttention = {}, onRefresh = {}, onStart = {},
                        onConfirmStart = { _, _, _, _, _, _, _, _ -> error("Read-only fixture must not start") },
                        onStop = { _, _ -> }, onSend = {}, onCancelUnbilled = {}, onExtendTimer = {},
                        onExtendPackage = { _, _ -> }, onTransfer = {}, onPauseResume = {}, onReconcile = {},
                        onRepairBilling = {}, onResolveLegacyStart = {}, onDiscardPackageExtension = {},
                        onAddItems = {}, onVoidAddon = { _, _ -> }, onReviewRejectedAddon = {}, onManageParticipants = {},
                    )
                }
            }
        }
    }

    private fun chooseDualFourSixty() {
        compose.onNodeWithTag("gaming-station-ps5-2").performClick()
        compose.onNodeWithText("Dual", substring = true).performScrollTo().performClick()
        repeat(2) {
            val icon = compose.onNodeWithContentDescription("Add player")
            if (runCatching { icon.fetchSemanticsNode() }.isSuccess) icon.performScrollTo().performClick()
            else compose.onNodeWithText("Add player").performScrollTo().performClick()
        }
        compose.onNodeWithContentDescription("Choose 60 minutes, ₹210.00").performClick()
        compose.onNodeWithText("Start 60 min · ₹210.00").assertIsDisplayed()
        compose.runOnIdle { assertEquals("ps5-2", selectedId) }
    }

    // Use the same composition ancestry on both Activity instances, as onCreate does.
    // Different setContent lambda callsites have different implicit rememberSaveable keys.
    private fun installFixtureContent(activity: ComponentActivity) {
        activity.setContent { Fixture() }
    }

    private fun recreateWithInitialEmpty(authority: GamingDraftAuthority?) {
        val previousActivity = compose.activity
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            assertNotSame(previousActivity, activity)
            currentAuthority.value = authority
            projection.value = GamingUiState()
            installFixtureContent(activity)
        }
    }

    @Test fun realActivityRecreationRestoresStationAndTariffAfterInitialEmptyCatalogue() {
        compose.activityRule.scenario.onActivity(::installFixtureContent)
        chooseDualFourSixty()
        recreateWithInitialEmpty(authority())
        compose.onNodeWithText("Loading scoped catalogue").assertIsDisplayed()
        compose.onNodeWithTag("gaming-start-action").assertDoesNotExist()
        compose.runOnIdle { assertEquals("ps5-2", selectedId); projection.value = loaded }
        compose.onNodeWithText("Start 60 min · ₹210.00").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("ps5-2", selectedId)
            projection.value = loaded.copy(stations = listOf(stations.first()))
        }
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
        compose.runOnIdle { assertEquals("ps5-1", selectedId) }
    }

    @Test fun recreationWithNewLoginGenerationCannotRestoreOldStationOrTariff() {
        compose.activityRule.scenario.onActivity(::installFixtureContent); chooseDualFourSixty()
        recreateWithInitialEmpty(authority(generation = 2))
        compose.runOnIdle { projection.value = loaded }
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
        compose.runOnIdle { assertEquals("ps5-1", selectedId) }
    }

    @Test fun changedProfileAndTerminalResetWhileMissingLeaseExposesNoDraftAction() {
        compose.activityRule.scenario.onActivity(::installFixtureContent); chooseDualFourSixty()
        recreateWithInitialEmpty(authority(profile = me.copy(effectivePermissions = emptyList()), terminal = "other-till"))
        compose.runOnIdle { projection.value = loaded }
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
        compose.runOnIdle { assertEquals("ps5-1", selectedId); currentAuthority.value = null }
        compose.onNodeWithText("Verifying Gaming workspace. Draft actions are unavailable.").assertIsDisplayed()
        compose.onNodeWithTag("gaming-start-action").assertDoesNotExist()
    }

    @Test fun endedStationHeaderUsesReadablePaymentDuePresentation() {
        projection.value = loaded.copy(sessions = listOf(GameSession(
            id = "ended", stationId = "ps5-1", shiftId = "fixture-shift", status = "ended",
            startAt = "2026-10-19T00:00:00Z", endAt = "2026-10-19T00:30:00Z", amountMinor = 8_000,
        )))
        compose.activityRule.scenario.onActivity(::installFixtureContent)
        compose.onNodeWithText("Paymentdue").assertDoesNotExist()
        compose.onNodeWithTag("gaming-selected-status").assertIsDisplayed().assertTextEquals("Payment due")
    }
}
