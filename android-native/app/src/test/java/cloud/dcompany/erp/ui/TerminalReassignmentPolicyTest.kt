package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.ui.screens.shift.ShiftDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalReassignmentPolicyTest {

    @Test
    fun `only a protected owner can reassign a till`() {
        assertBlocked(allowedFacts().copy(protectedOwner = false), "protected owner")
    }

    @Test
    fun `changed token lineage fails closed`() {
        assertBlocked(allowedFacts().copy(tokenLineageCurrent = false), "sign-in changed")
    }

    @Test
    fun `offline reassignment explains that live verification is required`() {
        assertBlocked(allowedFacts().copy(online = false), "live server check")
    }

    @Test
    fun `missing validated terminal metadata cannot use a stale id`() {
        assertBlocked(allowedFacts().copy(activeTerminalExact = false), "verified exactly")
    }

    @Test
    fun `server removed current till preserves the old assignment`() {
        assertBlocked(allowedFacts().copy(currentTerminalStillAvailable = false), "removed")
    }

    @Test
    fun `active or close pending local shift blocks reassignment`() {
        assertBlocked(allowedFacts().copy(localShiftWorkflowCount = 1), "active or closing shift")
    }

    @Test
    fun `active local gaming lifecycle blocks reassignment`() {
        assertBlocked(allowedFacts().copy(localGamingWorkflowCount = 1), "gaming session")
    }

    @Test
    fun `any unresolved outbox work blocks reassignment`() {
        assertBlocked(allowedFacts().copy(unresolvedOutboxCount = 3), "3 saved change")
    }

    @Test
    fun `server open shift on the exact current till blocks reassignment`() {
        assertBlocked(allowedFacts().copy(serverOpenShiftCount = 1), "open shift on the server")
    }

    @Test
    fun `branch wide active or unbilled gaming work blocks reassignment`() {
        assertBlocked(allowedFacts().copy(serverGamingBlockerCount = 2), "stopped-unbilled")
    }

    @Test
    fun `a branch with no alternative till gives a setup action`() {
        assertBlocked(allowedFacts().copy(alternativeTillCount = 0), "Create another till")
    }

    @Test
    fun `all checks passing permits explicit selection`() {
        assertEquals(TerminalReassignmentDecision.Allowed, terminalReassignmentDecision(allowedFacts()))
    }

    @Test
    fun `rapid taps start only one guard run and a completed run can retry`() {
        val gate = TerminalReassignmentRequestGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
    }

    @Test
    fun `gaming blocker excludes cancelled and already billed sessions`() {
        assertTrue(gamingBlocksTillReassignment("active", null))
        assertTrue(gamingBlocksTillReassignment("paused", null))
        assertTrue(gamingBlocksTillReassignment("ended", null))
        assertFalse(gamingBlocksTillReassignment("ended", "order-1"))
        assertFalse(gamingBlocksTillReassignment("cancelled", null))
    }

    @Test
    fun `hybrid workspace hides the internal terminal from staff`() {
        val display = ValidatedTerminalDisplay("till-1", "Front", "branch-a", TerminalPurpose.HYBRID)

        assertEquals(
            "Main Cafe",
            workspaceLocationLabel(
                branchId = "branch-a",
                branchName = " Main Cafe ",
                requiresTill = true,
                activeTerminal = display,
            ),
        )
    }

    @Test
    fun `hybrid legacy profile hides internal branch and terminal ids`() {
        val display = ValidatedTerminalDisplay("till-1", "Front", "branch-a", TerminalPurpose.HYBRID)

        assertEquals(
            "Assigned branch",
            workspaceLocationLabel(
                branchId = "branch-a",
                branchName = null,
                requiresTill = true,
                activeTerminal = display,
            ),
        )
    }

    @Test
    fun `non operational role shows branch without implying a till is required`() {
        assertEquals(
            "Main Cafe",
            workspaceLocationLabel(
                branchId = "branch-a",
                branchName = "Main Cafe",
                requiresTill = false,
                activeTerminal = null,
            ),
        )
    }

    @Test
    fun `explicit multi-terminal workspace keeps exact terminal visible`() {
        val display = ValidatedTerminalDisplay("till-1", "Gaming Area", "branch-a", TerminalPurpose.GAMING)

        assertEquals(
            "Main Cafe · Till Gaming Area",
            workspaceLocationLabel(
                branchId = "branch-a",
                branchName = "Main Cafe",
                requiresTill = true,
                activeTerminal = display,
            ),
        )
        assertTrue(usesAdvancedTerminalWorkflow(display))
        assertFalse(
            usesAdvancedTerminalWorkflow(
                display.copy(purpose = TerminalPurpose.HYBRID),
            ),
        )
    }

    @Test
    fun `workspace label ignores detached name when branch is unassigned`() {
        val display = ValidatedTerminalDisplay("till-1", "Front", "branch-a", TerminalPurpose.HYBRID)

        assertEquals(
            "Branch not assigned · Till name pending verification",
            workspaceLocationLabel(
                branchId = null,
                branchName = "Other Company",
                requiresTill = true,
                activeTerminal = display,
            ),
        )
    }

    @Test
    fun `workspace label never displays terminal metadata from another branch`() {
        val display = ValidatedTerminalDisplay("till-1", "Other shop", "branch-b", TerminalPurpose.HYBRID)

        assertEquals(
            "Main Cafe · Till name pending verification",
            workspaceLocationLabel(
                branchId = "branch-a",
                branchName = "Main Cafe",
                requiresTill = true,
                activeTerminal = display,
            ),
        )
    }

    private fun assertBlocked(facts: TerminalReassignmentFacts, messagePart: String) {
        val decision = terminalReassignmentDecision(facts)
        assertTrue(decision is TerminalReassignmentDecision.Blocked)
        assertTrue(
            (decision as TerminalReassignmentDecision.Blocked).message.contains(
                messagePart,
                ignoreCase = true,
            ),
        )
    }

    private fun allowedFacts() = TerminalReassignmentFacts(
        protectedOwner = true,
        tokenLineageCurrent = true,
        online = true,
        activeTerminalExact = true,
        currentTerminalStillAvailable = true,
        unresolvedOutboxCount = 0,
        localShiftWorkflowCount = 0,
        localGamingWorkflowCount = 0,
        serverOpenShiftCount = 0,
        serverGamingBlockerCount = 0,
        alternativeTillCount = 1,
    )
    @Test fun `saturated other till page cannot hide older exact or legacy null shift`() {
        for (hiddenTerminal in listOf("original-till", null)) {
            val recentOthers = (1..200).map { ShiftDetail("shift-$it", "branch", "other-$it", "open", "2026-11-05T00:00:00Z") }
            val older = ShiftDetail("older", "branch", hiddenTerminal, "open", "2026-11-04T00:00:00Z")
            val actualBoundedPage = (recentOthers + older).take(200)
            // Exact pre-fix production predicate: the hidden old shift made this false-clean.
            val priorCount = actualBoundedPage.count { it.branchId != "branch" || it.status != "open" || it.terminalId == null || it.terminalId == "original-till" }
            assertEquals(0, priorCount)
            assertEquals(TerminalReassignmentDecision.Allowed, terminalReassignmentDecision(allowedFacts().copy(serverOpenShiftCount = priorCount)))
            val safeCount = terminalReassignmentShiftBlockers(actualBoundedPage, "branch", "original-till")
            assertNull("An incomplete response must not be used as a real zero or nonzero shift count", safeCount)
            assertTrue(TERMINAL_SHIFT_RESPONSE_LIMIT_MESSAGE.contains("response reached its limit"))
            assertTrue(TERMINAL_SHIFT_RESPONSE_LIMIT_MESSAGE.contains("cannot be verified"))
        }
    }
    @Test fun `complete shorter shift page preserves exact scope null and malformed row blockers`() {
        val others = (1..199).map { ShiftDetail("shift-$it", "branch", "other-$it", "open", "2026-11-05T00:00:00Z") }
        assertEquals(0, terminalReassignmentShiftBlockers(others, "branch", "original-till"))
        assertEquals(0, terminalReassignmentShiftBlockers(emptyList(), "branch", "original-till"))
        for (row in listOf(others.first().copy(terminalId = null), others.first().copy(terminalId = "original-till"),
            others.first().copy(branchId = "foreign"), others.first().copy(status = "closed"))) {
            assertEquals(1, terminalReassignmentShiftBlockers(listOf(row), "branch", "original-till"))
        }
    }

}
