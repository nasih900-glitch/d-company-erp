package cloud.dcompany.erp.core.alarm

import cloud.dcompany.erp.core.db.HeldOrderCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldOrderAlarmReconcilerTest {

    @Test
    fun `deadline is exactly fifteen minutes after authoritative held at`() {
        val candidates = heldOrderAlarmCandidates(
            orders = listOf(order(heldAt = "2026-08-26T10:00:00Z")),
            locallyConfirmedOrderIds = emptySet(),
        )

        assertEquals(1, candidates.size)
        assertEquals(
            authoritativeEpochMillis("2026-08-26T10:15:00Z"),
            candidates.single().triggerAtMillis,
        )
        assertEquals("held-order-order-1", candidates.single().tag)
    }

    @Test
    fun `timezone offset is normalized before adding fifteen minutes`() {
        val candidate = heldOrderAlarmCandidates(
            listOf(order(heldAt = "2026-08-26T15:30:00+05:30")),
            emptySet(),
        ).single()

        assertEquals(
            authoritativeEpochMillis("2026-08-26T10:15:00Z"),
            candidate.triggerAtMillis,
        )
    }

    @Test
    fun `legacy null held at falls back to server created at not local time`() {
        val candidate = heldOrderAlarmCandidates(
            listOf(order(heldAt = null, createdAt = "2026-08-26T09:00:00Z")),
            emptySet(),
        ).single()

        assertEquals(
            authoritativeEpochMillis("2026-08-26T09:15:00Z"),
            candidate.triggerAtMillis,
        )
    }

    @Test
    fun `malformed authoritative timestamps fail closed`() {
        assertTrue(
            heldOrderAlarmCandidates(
                listOf(order(heldAt = "26 August sometime", createdAt = "also invalid")),
                emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun `any local payment confirmation suppresses customer collection alarm`() {
        assertTrue(
            heldOrderAlarmCandidates(
                listOf(order()),
                locallyConfirmedOrderIds = setOf("order-1"),
            ).isEmpty(),
        )
    }

    @Test
    fun `overdue boundary is inclusive and ordered by deadline`() {
        val heldAt = authoritativeEpochMillis("2026-08-26T10:00:00Z")!!
        val orders = listOf(
            order(id = "later", heldAt = "2026-08-26T10:01:00Z"),
            order(id = "boundary", heldAt = "2026-08-26T10:00:00Z"),
        )

        assertEquals(
            listOf("boundary"),
            overdueHeldOrderIds(
                orders,
                emptySet(),
                nowMillis = heldAt + HELD_ORDER_ALARM_AGE_MILLIS,
            ),
        )
    }

    @Test
    fun `mute is bounded and new overdue work breaks it immediately`() {
        val muted = listOf("one", "two")
        val fingerprint = overdueHeldOrderFingerprint(muted)

        assertFalse(shouldShowOverdueHeldOrderBanner(muted, fingerprint, 2_000, 1_000))
        assertTrue(
            shouldShowOverdueHeldOrderBanner(
                overdueOrderIds = muted + "three",
                mutedFingerprint = fingerprint,
                mutedUntilMillis = 2_000,
                nowMillis = 1_000,
            ),
        )
        assertTrue(shouldShowOverdueHeldOrderBanner(muted, fingerprint, 2_000, 2_000))
    }

    private fun order(
        id: String = "order-1",
        heldAt: String? = "2026-08-26T10:00:00Z",
        createdAt: String = "2026-08-26T09:55:00Z",
    ) = HeldOrderCacheEntity(
        id = id,
        invoiceNo = null,
        type = "dine_in",
        sourceLabel = "Table 4",
        totalMinor = 1_000,
        paidMinor = 0,
        itemsCount = 2,
        customerName = null,
        createdAt = createdAt,
        heldAt = heldAt,
        checkoutVersion = 1,
    )
}
