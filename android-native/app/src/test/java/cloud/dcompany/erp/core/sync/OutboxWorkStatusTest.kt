package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.UnresolvedOutboxGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxWorkStatusTest {
    @Test
    fun `summary separates automatic replay human attention and saved drafts`() {
        val result = summarizeOutboxWork(
            listOf(
                group("pos_orders", "pending", 2),
                group("ingredients", "create_attempted"),
                group("gaming_sessions", "ended_unbilled", 3),
                group("refunds", "accepted_cash_due"),
                group("customers", "rejected"),
                group("pos_orders", "draft", 2),
                group("pos_orders", "awaiting_payment"),
            ),
        )

        assertEquals(3, result.retryableCount)
        assertEquals(5, result.actionRequiredCount)
        assertEquals(3, result.savedDraftCount)
        assertEquals(11, result.totalCount)
        assertFalse(result.isClear)
    }

    @Test
    fun `accounting finalization and ambiguous actions remain automatic`() {
        val result = summarizeOutboxWork(
            listOf(
                group("refunds", "cash_handed_over_pending_accounting"),
                group("refunds", "provider_completed_pending_accounting"),
                group("membership_payment_actions", "ambiguous"),
            ),
        )

        assertEquals(3, result.retryableCount)
        assertEquals(0, result.actionRequiredCount)
        assertTrue(result.savedDraftCount == 0)
    }

    @Test
    fun `zero and empty groups do not manufacture work`() {
        val result = summarizeOutboxWork(
            listOf(group("customers", "pending", count = 0)),
        )

        assertTrue(result.isClear)
        assertEquals(0, result.totalCount)
    }

    @Test
    fun `header copy keeps review retry and saved draft meanings distinct`() {
        val status = OutboxWorkStatus(
            retryableCount = 2,
            actionRequiredCount = 3,
            savedDraftCount = 1,
        )

        assertEquals(
            "3 review · 2 waiting · 1 saved",
            outboxWorkVisibleLabel(status, syncing = false, showDetail = true),
        )
        assertEquals(
            "3 review · Syncing 2 · 1 saved",
            outboxWorkVisibleLabel(status, syncing = true, showDetail = true),
        )
        assertEquals("6", outboxWorkVisibleLabel(status, syncing = false, showDetail = false))
        assertEquals(null, outboxWorkVisibleLabel(OutboxWorkStatus(), false, true))
    }

    private fun group(
        resource: String,
        state: String,
        count: Int = 1,
    ) = UnresolvedOutboxGroup(resource, state, count)
}
