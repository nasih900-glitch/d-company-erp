package cloud.dcompany.erp.ui.screens.customers

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomersTableUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun leaderboardAt1280UsesServerRankOrderAndMaskedPhones() {
        compose.setContent {
            LeaderboardFixture(width = 1_180, height = 620)
        }

        compose.onNodeWithText("2h 30m").assertIsDisplayed()
        compose.onNodeWithText("Recorded visits").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("Play rewards").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rank 1 by completed playtime").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rank 2 by completed playtime").assertIsDisplayed()
        compose.onAllNodesWithText("Coming soon").assertCountEquals(2)
        compose.onNodeWithText("••••••3210").assertIsDisplayed()
        val firstTop = compose.onNodeWithContentDescription("Rank 1 by completed playtime")
            .fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithContentDescription("Rank 2 by completed playtime")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(firstTop < secondTop)
        compose.onNodeWithText("9876543210").assertDoesNotExist()
        screenshot("customers-table-1280.png")
    }

    @Test
    fun leaderboardAt960KeepsLoadMoreReachable() {
        var loadMore = 0
        compose.setContent {
            LeaderboardFixture(width = 872, height = 400, total = 3, onLoadMore = { loadMore++ })
        }

        compose.onNodeWithText("Load more ranks").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, loadMore) }
        screenshot("customers-table-960.png")
    }

    @Test
    fun leaderboardErrorAndEmptyStatesDoNotInventRanks() {
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(872.dp).height(400.dp)) {
                    CustomerLeaderboardPanel(
                        state = CustomerPlaytimeUiState(error = "Reconnect to load recorded play hours."),
                        onRefresh = {},
                        onLoadMore = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithText("Playtime ranking unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("2h 30m").assertCountEquals(0)
    }

    @Test
    fun emptyServerResultShowsNoCustomersWithoutInventingHours() {
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(872.dp).height(400.dp)) {
                    CustomerLeaderboardPanel(
                        state = CustomerPlaytimeUiState(total = 0),
                        onRefresh = {},
                        onLoadMore = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithText("No customers to rank yet").assertIsDisplayed()
        compose.onAllNodesWithText("Coming soon").assertCountEquals(0)
    }

    @Test
    fun partialFailureKeepsLoadedRanksAndOffersRefresh() {
        var refreshed = 0
        compose.setContent {
            LeaderboardFixture(
                width = 872,
                height = 400,
                total = 3,
                error = "Reconnect to load the remaining ranks.",
                onRefresh = { refreshed++ },
            )
        }
        compose.onNodeWithText("2h 30m").assertIsDisplayed()
        compose.onNodeWithText("Refresh leaderboard").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
    }

    @Test
    fun pendingFilterShowsOnlyUnsyncedCustomerAndKeepsStatusVisible() {
        compose.setContent {
            CustomersFixture(width = 872, height = 400, filter = CustomerListFilter.NeedsSync)
        }

        compose.onAllNodesWithText("Test Customer One").assertCountEquals(0)
        compose.onNodeWithText("Test Customer Two").assertIsDisplayed()
        compose.onNodeWithContentDescription("Status: Waiting to sync").assertIsDisplayed()
    }

    private fun screenshot(filename: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), filename)
        val bitmap = compose.onNodeWithTag("customers-table-fixture").captureToImage().asAndroidBitmap()
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}

@Composable
private fun CustomersFixture(
    width: Int,
    height: Int,
    filter: CustomerListFilter = CustomerListFilter.All,
    onSelect: (Customer) -> Unit = {},
) {
    val first = Customer(
        id = "customer-1", name = "Test Customer One", phone = "+91 9876543210",
        visitCount = 3, totalSpentMinor = 24_000, loyaltyPoints = 12,
    )
    val second = Customer(
        id = "customer-2", name = "Test Customer Two", phone = "+91 9876543211",
        visitCount = 1, totalSpentMinor = 5_000, loyaltyPoints = 2,
        pendingLocalId = "local-2",
    )
    DCompanyTheme {
        Box(Modifier.width(width.dp).height(height.dp).testTag("customers-table-fixture")) {
            CustomerResultsPanel(
                state = CustomersUiState(everSynced = true, rows = listOf(first, second)),
                onSelect = onSelect,
                filter = filter,
            )
        }
    }
}

@Composable
private fun LeaderboardFixture(
    width: Int,
    height: Int,
    total: Int = 2,
    error: String? = null,
    onRefresh: () -> Unit = {},
    onLoadMore: () -> Unit = {},
) {
    val items = listOf(
        PlaytimeLeaderboardItem(1, "customer-1", "Test Customer One", "••••••3210", 150, 120, 0, recordedVisits = 3),
        PlaytimeLeaderboardItem(2, "customer-2", "Test Customer Two", "••••••3211", 60, 60, 0),
    )
    DCompanyTheme {
        Box(Modifier.width(width.dp).height(height.dp).testTag("customers-table-fixture")) {
            CustomerLeaderboardPanel(
                state = CustomerPlaytimeUiState(
                    items = items,
                    total = total,
                    nextPage = 2,
                    error = error,
                    program = PlaytimeProgramDraft(
                        thresholdPaidMinutes = 600,
                        rewardMinutes = 60,
                        messageTemplatePreview = "",
                    ),
                ),
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
