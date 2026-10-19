package cloud.dcompany.erp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CanonicalReceiptHistoryDialogUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun receiptSearchImeDispatchesExactQueryAndKeepsCachedLookupSeparateFromRoomRefresh() {
        val queries = mutableListOf<String>()
        var globalRefreshes = 0

        compose.setContent {
            var search by remember { mutableStateOf(ReceiptSearchUiState()) }
            DCompanyTheme {
                CanonicalReceiptHistoryDialog(
                    receipts = emptyList(),
                    hasMore = false,
                    loading = false,
                    error = null,
                    hasLocalReceipt = false,
                    onOpenLastLocalReceipt = {},
                    onRefresh = { globalRefreshes += 1 },
                    onLoadMore = {},
                    onOpenReceipt = {},
                    search = search,
                    onSearch = { filters ->
                        queries += filters.query
                        search = ReceiptSearchUiState(
                            criteria = filters, appliedCriteria = filters,
                            source = ReceiptSearchSource.CACHED,
                            cachedAtMillis = 1_797_000_000_000,
                        )
                    },
                    onLoadMoreSearch = {},
                    onRefundReceipt = null,
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Invoice, suffix, order or session ID")
            .assertIsDisplayed()
            .performTextReplacement("00001")
        compose.onNodeWithText("Search").assertIsDisplayed().performClick()
        compose.onNodeWithText(
            "Offline cached lookup",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "not full history",
            substring = true,
        ).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("00001"), queries)
            assertEquals(0, globalRefreshes)
        }
    }
    @Test
    fun blankReferenceAllowsExactAmountAndShopDateOnlySearch() {
        val requests = mutableListOf<ReceiptHistoryFilters>()
        compose.setContent {
            DCompanyTheme {
                CanonicalReceiptHistoryDialog(
                    receipts=emptyList(),hasMore=false,loading=false,error=null,hasLocalReceipt=false,
                    onOpenLastLocalReceipt={},onRefresh={},onLoadMore={},onOpenReceipt={},
                    search=ReceiptSearchUiState(),onSearch={requests+=it},onLoadMoreSearch={},
                    onRefundReceipt=null,onDismiss={},
                )
            }
        }
        compose.onNodeWithText("More filters").performClick()
        compose.onNodeWithTag("receipt-filter-from").performScrollTo().performTextReplacement("2026-10-17")
        compose.onNodeWithTag("receipt-filter-to").performScrollTo().performTextReplacement("2026-10-18")
        compose.onNodeWithTag("receipt-filter-amount").performScrollTo().performTextReplacement("270.00")
        compose.onNodeWithText("Search").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ReceiptHistoryFilters(dateFrom="2026-10-17",dateTo="2026-10-18",amountMinor=27000)),requests)
        }
    }

}
