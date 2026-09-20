package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GamingRejectedSessionAttentionUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rejectedSessionsDriveDangerSummaryAndClearingRestoresReadyState() {
        val failedStart = session("start", "station-start", GamingSessionState.START_REJECTED, "start_failed")
        val failedStop = session("stop", "station-stop", GamingSessionState.STOP_REJECTED, "active")
        val current = mutableStateOf(GamingUiState(sessions = listOf(failedStart, failedStop), online = true, refreshing = false))
        var reviewOpened = false

        compose.setContent {
            DCompanyTheme {
                val state = current.value
                GamingCommandAttentionBar(
                    state = state,
                    access = GamingAccess(canManageSessions = true),
                    terminalBlocked = false,
                    focusRequested = false,
                    orphanedExtensionCount = 0,
                    attentionCount = gamingCommandAttentionCount(
                        canManageSessions = true,
                        terminalBlocked = false,
                        focusRequested = false,
                        hasRefreshError = false,
                        orphanedExtensionCount = 0,
                        rejectedSessionCount = state.rejectedSessionsForReview.size,
                        needsCancellation = false,
                        awaitingPayment = false,
                        busy = false,
                    ),
                    onOpen = { reviewOpened = true },
                    onRefresh = {},
                )
            }
        }

        compose.onNodeWithText("Action centre · 2 to review").assertIsDisplayed()
        compose.onNodeWithText("2 rejected sessions").assertIsDisplayed()
        compose.onNodeWithText("Review").performClick()
        compose.runOnIdle {
            assertTrue(reviewOpened)
            current.value = GamingUiState(online = true, refreshing = false)
        }
        compose.onNodeWithText("Gaming board ready").assertIsDisplayed()
        compose.onNodeWithText("Live data is connected · no action needs attention").assertIsDisplayed()
    }

    @Test
    fun rejectedStartAndStopAreListedAndReviewOnlySelectsTheirStation() {
        val failedStart = session("start", "station-start", GamingSessionState.START_REJECTED, "start_failed")
        val failedStop = session("stop", "station-stop", GamingSessionState.STOP_REJECTED, "active")
        var reviewedSessionId: String? = null

        compose.setContent {
            DCompanyTheme {
                GamingCommandAttentionDialog(
                    state = GamingUiState(
                        stations = listOf(
                            Station("station-start", "PS5-1", "PS5 Station 1", "ps5", 15_000),
                            Station("station-stop", "PS5-2", "PS5 Station 2", "ps5", 15_000),
                        ),
                        sessions = listOf(failedStart, failedStop),
                    ),
                    access = GamingAccess(canManageSessions = true),
                    activeTerminalPurpose = null,
                    startTerminalBlockMessage = null,
                    focusSessionId = null,
                    focusStationId = null,
                    orphanedExtensionActions = emptyList(),
                    onDismiss = {},
                    onDismissFocus = {},
                    onRefresh = {},
                    onReviewOrphan = {},
                    onReviewRejectedSession = { reviewedSessionId = it.id },
                    onReviewCancellations = {},
                    onReviewPayments = {},
                )
            }
        }

        compose.onNodeWithText("Start rejected on PS5 Station 1").assertIsDisplayed()
        compose.onNodeWithText("Stop rejected on PS5 Station 2").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Review station")[1].performScrollTo().performClick()
        compose.runOnIdle { assertEquals("stop", reviewedSessionId) }
    }

    private fun session(id: String, stationId: String, state: String, status: String) = GameSession(
        id = id,
        stationId = stationId,
        shiftId = "shift-1",
        status = status,
        startAt = "2026-09-14T09:00:00Z",
        localState = state,
        lastError = "Saved action rejected",
    )
}
