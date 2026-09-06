package cloud.dcompany.erp.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftCloseSafetyPolicyTest {

    @Test
    fun `capture permits ordinary pending writes but rejects attention and workflows`() {
        val pendingOnly = ShiftCloseBlockerCounts(
            pendingLocalCount = 2,
            attentionLocalCount = 0,
            serverWorkflowCount = 0,
            serverGamingSessionCount = 0,
            serverHeldOrderCount = 0,
            unscopedAttentionCount = 0,
        )
        val attention = pendingOnly.copy(attentionLocalCount = 1)

        assertNull(pendingOnly.captureMessage())
        assertTrue(pendingOnly.serverPostMessage()!!.contains("2 saved action(s)"))
        assertTrue(attention.captureMessage()!!.contains("1 saved action(s) need recovery"))
    }

    @Test
    fun `final post counts pending and every recovery blocker`() {
        val counts = ShiftCloseBlockerCounts(
            pendingLocalCount = 2,
            attentionLocalCount = 3,
            serverWorkflowCount = 4,
            serverGamingSessionCount = 0,
            serverHeldOrderCount = 0,
            unscopedAttentionCount = 5,
        )

        assertEquals(12, counts.captureBlockerCount)
        assertEquals(14, counts.serverPostBlockerCount)
        val message = counts.serverPostMessage().orEmpty()
        assertTrue(message.contains("paused before contacting the server"))
        assertTrue(message.contains("Continue the shift"))
        assertTrue(message.contains("retry the drawer count"))
    }

    @Test
    fun `server cache blockers are counted and explain the recovery workflow`() {
        val counts = ShiftCloseBlockerCounts(
            pendingLocalCount = 0,
            attentionLocalCount = 0,
            serverWorkflowCount = 1,
            serverGamingSessionCount = 2,
            serverHeldOrderCount = 3,
            unscopedAttentionCount = 0,
        )

        assertEquals(6, counts.serverConfirmedBlockerCount)
        assertEquals(6, counts.captureBlockerCount)
        assertEquals(6, counts.serverPostBlockerCount)
        val capture = counts.captureMessage().orEmpty()
        assertTrue(capture.contains("2 active or stopped-but-unbilled Gaming session(s)"))
        assertTrue(capture.contains("3 held POS order(s)"))
        assertTrue(capture.contains("End active Gaming sessions and send stopped sessions to POS"))
        assertTrue(capture.contains("void them with an audit reason"))
        val post = counts.serverPostMessage().orEmpty()
        assertTrue(post.contains("2 Gaming session(s)"))
        assertTrue(post.contains("3 held POS order(s)"))
    }

    @Test
    fun `sqlite guard error gets a clear operational instruction`() {
        val wrapped = IllegalStateException(
            "outer",
            IllegalArgumentException("constraint: $SHIFT_CLOSING_WRITE_GUARD"),
        )

        val message = wrapped.shiftClosingMessageOr("fallback")

        assertTrue(message.contains("shift started closing"))
        assertTrue(message.contains("No new work was recorded"))
        assertTrue(message.contains("continue the shift"))
        assertEquals("fallback", IllegalArgumentException("other").shiftClosingMessageOr("fallback"))
    }
}
