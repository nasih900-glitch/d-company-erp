package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.db.ShiftCloseBlockerCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShiftCloseReviewPresentationTest {
    private val empty = ShiftCloseBlockerCounts(0, 0, 0, 0, 0, 0)
    private fun state(blockers: ShiftCloseBlockerCounts? = empty) = ShiftUiState(
        expectedMinor = 0, canClose = true, closeBlockers = blockers,
    )

    @Test fun `unknown and zero cached counts never promise server approval`() {
        assertEquals("Saved work not yet checked · review required", shiftCloseReviewStatus(state(null), true))
        assertEquals("Count cash · server review still required", shiftCloseReviewStatus(state(), true))
        assertNull(state().cachedCloseBlockMessage())
        assertFalse(shiftCloseReviewStatus(state(), true).contains("Ready"))
    }

    @Test fun `known held cache gives count and safe review guidance without assuming cash`() {
        val blocked = state(empty.copy(serverHeldOrderCount = 1))
        assertEquals("1 saved task needs review", shiftCloseReviewStatus(blocked, true))
        assertNotNull(blocked.cachedCloseBlockMessage())
        val message = blocked.cachedCloseBlockMessage()!!
        assertEquals(true, message.contains("1 held POS bill saved on this tablet"))
        assertEquals(true, message.contains("Review POS and sync"))
        assertEquals(0L, blocked.expectedMinor)
        assertNull(blocked.closedResult)
    }

    @Test fun `ordinary pending work retains existing queued close policy`() {
        val pending = state(empty.copy(pendingLocalCount = 2))
        assertEquals("2 saved action(s) sync before close", shiftCloseReviewStatus(pending, true))
        assertNull(pending.cachedCloseBlockMessage())
    }

    @Test fun `permission origin and unavailable accounting retain priority`() {
        val blocked = state(empty.copy(serverHeldOrderCount = 1))
        assertEquals("Review only · close permission required", shiftCloseReviewStatus(blocked, false))
        assertEquals("Close from the opening app", shiftCloseReviewStatus(blocked.copy(closeOriginMessage = "Opening app required"), true))
        assertEquals("Waiting for verified drawer total", shiftCloseReviewStatus(blocked.copy(expectedMinor = null), true))
    }

    @Test fun `stale account terminal or login generation cannot publish review counts`() {
        val scope = CacheScope("user", "company", "branch", "terminal")
        val lease = CacheScopeLease(scope, 1)
        fun project(review: CacheScopeLease?, current: CacheScopeLease? = lease,
            user: String = "user", company: String = "company", branch: String = "branch",
            terminal: String = "terminal") = scopedCloseReviewCounts(empty, review, current, user, company, branch, terminal)
        assertEquals(empty, project(lease))
        assertNull(project(null))
        assertNull(project(lease, null))
        assertNull(project(lease, lease.copy(generation = 2)))
        assertNull(project(lease, user = "other"))
        assertNull(project(lease, company = "other"))
        assertNull(project(lease, branch = "other"))
        assertNull(project(lease, terminal = "other"))
    }
}
