package cloud.dcompany.erp.ui.screens.customers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerPlaytimePagingTest {
    private val program = PlaytimeProgramDraft(
        thresholdPaidMinutes = 600,
        rewardMinutes = 60,
        messageTemplatePreview = "",
    )

    @Test fun appendsServerRanksOneBoundedPageAtATime() {
        val first = appendPlaytimePage(CustomerPlaytimeUiState(), 1, page(1, 30, 1..25))
        assertEquals((1..25).toList(), first.items.map(PlaytimeLeaderboardItem::rank))
        assertEquals(2, first.nextPage)
        assertTrue(first.canLoadMore)

        val complete = appendPlaytimePage(first, 2, page(2, 30, 26..30))
        assertEquals((1..30).toList(), complete.items.map(PlaytimeLeaderboardItem::rank))
        assertFalse(complete.canLoadMore)
    }

    @Test fun acceptsAnEmptyAuthoritativeLeaderboard() {
        val empty = appendPlaytimePage(CustomerPlaytimeUiState(), 1, page(1, 0, IntRange.EMPTY))
        assertTrue(empty.items.isEmpty())
        assertEquals(0, empty.total)
        assertFalse(empty.canLoadMore)
    }

    @Test fun refusesDuplicateOrReorderedRanksAfterAChangeBetweenPages() {
        val first = appendPlaytimePage(CustomerPlaytimeUiState(), 1, page(1, 30, 1..25))
        val duplicate = page(2, 30, 25..29)
        assertTrue(runCatching { appendPlaytimePage(first, 2, duplicate) }.isFailure)
    }

    @Test fun refusesWrongPageOrAnEmptyPageBeforeTotalIsReached() {
        val first = appendPlaytimePage(CustomerPlaytimeUiState(), 1, page(1, 30, 1..25))
        assertTrue(runCatching { appendPlaytimePage(first, 2, page(3, 30, 26..30)) }.isFailure)
        assertTrue(runCatching { appendPlaytimePage(first, 2, page(2, 30, IntRange.EMPTY)) }.isFailure)
        assertTrue(runCatching { appendPlaytimePage(first, 2, page(2, 31, 26..30)) }.isFailure)
    }

    @Test fun recordedVisitsIsOptionalForOlderServerResponses() {
        val oldResponse = """{"rank":1,"customer_id":"c-1","name":"Player","masked_phone":"******0010","total_played_minutes":60,"qualifying_paid_minutes":60,"draft_estimated_reward_minutes":0}"""
        assertEquals(null, Json.decodeFromString<PlaytimeLeaderboardItem>(oldResponse).recordedVisits)
        val newResponse = oldResponse.dropLast(1) + ",\"recorded_visits\":3}"
        assertEquals(3, Json.decodeFromString<PlaytimeLeaderboardItem>(newResponse).recordedVisits)
    }

    private fun page(page: Int, total: Int, ranks: IntRange) = PlaytimeLeaderboard(
        items = ranks.map { rank ->
            PlaytimeLeaderboardItem(
                rank = rank,
                customerId = "customer-$rank",
                name = "Player $rank",
                maskedPhone = "+91 98******10",
                totalPlayedMinutes = rank * 30,
                qualifyingPaidMinutes = rank * 30,
                draftEstimatedRewardMinutes = 0,
            )
        },
        total = total,
        page = page,
        limit = PLAYTIME_PAGE_SIZE,
        program = program,
    )
}
