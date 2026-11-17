package cloud.dcompany.erp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutiveRailScrubTest {
    private val targets = listOf(
        Destination.Dashboard,
        Destination.Gaming,
        Destination.Pos,
        Destination.Settings,
    )
    private val policy = ExecutiveRailScrubPolicy(
        boundaryHysteresisPx = 6f,
        edgeZonePx = 32f,
        maximumEdgeStepPx = 10f,
    )
    private fun snapshot(destination: Destination, revision: Long = 0L) =
        WorkspaceRouteSnapshot(destination, revision)

    @Test
    fun `vertical slide claims on slop in either direction before long press`() {
        for (vertical in listOf(-9f, 9f)) {
            assertEquals(
                ExecutiveRailPressDecision.Claim,
                executiveRailPressDecision(0f, vertical, 8f, 32L, 500L, true),
            )
        }
        assertEquals(
            ExecutiveRailPressDecision.Claim,
            executiveRailPressDecision(10f, 12f, 8f, 32L, 500L, true),
        )
    }

    @Test
    fun `tap jitter remains unclaimed and stationary long press remains compatible`() {
        assertEquals(
            ExecutiveRailPressDecision.Pending,
            executiveRailPressDecision(8f, -8f, 8f, 499L, 500L, true),
        )
        assertEquals(
            ExecutiveRailPressDecision.Claim,
            executiveRailPressDecision(1f, -1f, 8f, 500L, 500L, true),
        )
    }

    @Test
    fun `horizontal and ambiguous movement never claim even after long press timeout`() {
        for ((horizontal, vertical) in listOf(-30f to 0f, 30f to 0f, 20f to 20f, 10f to 11.9f)) {
            for (elapsed in listOf(32L, 600L)) {
                assertEquals(
                    ExecutiveRailPressDecision.Reject,
                    executiveRailPressDecision(horizontal, vertical, 8f, elapsed, 500L, true),
                )
            }
        }
    }

    @Test
    fun `claim requires pointer inside current viewport for slide and long press`() {
        assertEquals(
            ExecutiveRailPressDecision.Reject,
            executiveRailPressDecision(0f, 30f, 8f, 32L, 500L, false),
        )
        assertEquals(
            ExecutiveRailPressDecision.Reject,
            executiveRailPressDecision(0f, 0f, 8f, 600L, 500L, false),
        )
    }

    @Test
    fun `each entered target is proposed before contact ends without duplicates`() {
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Dashboard, snapshot(Destination.Dashboard), targets),
        )
        val gaming = updateExecutiveRailScrubTarget(state, Destination.Gaming, enteredByPointer = true)
        assertEquals(Destination.Gaming, gaming.navigateTo)
        state = acknowledgeExecutiveRailScrub(gaming.state, Destination.Gaming, snapshot(Destination.Gaming, 1L))!!
        assertNull(updateExecutiveRailScrubTarget(state, Destination.Gaming, enteredByPointer = false).navigateTo)
        assertNull(updateExecutiveRailScrubTarget(state, Destination.Gaming, enteredByPointer = true).navigateTo)

        val pos = updateExecutiveRailScrubTarget(state, Destination.Pos, enteredByPointer = true)
        assertEquals(Destination.Pos, pos.navigateTo)
        state = acknowledgeExecutiveRailScrub(pos.state, Destination.Pos, snapshot(Destination.Pos, 2L))!!

        val ended = cancelExecutiveRailScrub(state)
        assertEquals(ExecutiveRailScrubPhase.Ended, ended.phase)
        assertNull(ended.candidate)
    }

    @Test
    fun `owner snapshot requires exact destination and revision without composition grace`() {
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Dashboard, snapshot(Destination.Dashboard), targets),
        )
        assertTrue(executiveRailObservedSnapshotBelongsToStream(state, snapshot(Destination.Dashboard)))

        val proposal = updateExecutiveRailScrubTarget(state, Destination.Gaming, enteredByPointer = true)
        state = acknowledgeExecutiveRailScrub(proposal.state, Destination.Gaming, snapshot(Destination.Gaming, 1L))!!
        assertEquals(Destination.Gaming, state.lastCommitted)
        assertFalse(executiveRailObservedSnapshotBelongsToStream(state, snapshot(Destination.Dashboard, 0L)))
        assertTrue(executiveRailObservedSnapshotBelongsToStream(state, snapshot(Destination.Gaming, 1L)))
        assertFalse(
            "Destination-equal ABA must be visible through its newer owner revision",
            executiveRailObservedSnapshotBelongsToStream(state, snapshot(Destination.Gaming, 3L)),
        )
    }

    @Test
    fun `origin requires leaving before it can become a live target`() {
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Gaming, snapshot(Destination.Dashboard), targets),
        )
        val origin = updateExecutiveRailScrubTarget(state, Destination.Gaming, enteredByPointer = true)
        assertNull(origin.navigateTo)

        val pos = updateExecutiveRailScrubTarget(origin.state, Destination.Pos, enteredByPointer = true)
        state = acknowledgeExecutiveRailScrub(pos.state, Destination.Pos, snapshot(Destination.Pos, 1L))!!
        assertEquals(
            Destination.Gaming,
            updateExecutiveRailScrubTarget(state, Destination.Gaming, enteredByPointer = true).navigateTo,
        )
    }

    @Test
    fun `model filters targets and cancellation cannot emit navigation`() {
        val restricted = listOf(Destination.Dashboard, Destination.Pos)
        val state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Dashboard, snapshot(Destination.Dashboard), restricted),
        )
        val blocked = updateExecutiveRailScrubTarget(state, Destination.Gaming, enteredByPointer = true)
        assertNull(blocked.navigateTo)
        val ended = cancelExecutiveRailScrub(blocked.state)
        val update = updateExecutiveRailScrubTarget(ended, Destination.Pos, enteredByPointer = true)
        assertNull(update.navigateTo)
        assertEquals(ExecutiveRailScrubPhase.Ended, update.state.phase)
    }

    @Test
    fun `moving rail bounds beneath a stationary pointer cannot navigate`() {
        val state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Dashboard, snapshot(Destination.Dashboard), targets),
        )
        val shifted = updateExecutiveRailScrubTarget(state, Destination.Gaming, enteredByPointer = false)
        assertNull(shifted.navigateTo)
        val scrolled = updateExecutiveRailScrubTarget(shifted.state, Destination.Settings, enteredByPointer = false)
        assertNull(scrolled.navigateTo)
        assertEquals(
            Destination.Settings,
            updateExecutiveRailScrubTarget(scrolled.state, Destination.Settings, enteredByPointer = true).navigateTo,
        )
    }

    @Test
    fun `boundary hysteresis holds the current candidate until a new stable interior`() {
        val bounds = listOf(
            ExecutiveRailTargetBounds(Destination.Dashboard, 0f, 0f, 100f, 48f),
            ExecutiveRailTargetBounds(Destination.Gaming, 0f, 50f, 100f, 98f),
        )
        assertEquals(
            Destination.Dashboard,
            executiveRailTargetAt(50f, 51f, bounds, Destination.Dashboard, 6f),
        )
        assertNull(executiveRailTargetAt(50f, 53f, bounds, null, 6f))
        assertEquals(
            Destination.Gaming,
            executiveRailTargetAt(50f, 56f, bounds, null, 6f),
        )
    }

    @Test
    fun `edge scroll is directional bounded and only active when scrolling is possible`() {
        assertEquals(-10f, executiveRailEdgeScrollDelta(0f, 0f, 300f, true, true, policy), 0.01f)
        assertEquals(10f, executiveRailEdgeScrollDelta(300f, 0f, 300f, true, true, policy), 0.01f)
        assertEquals(0f, executiveRailEdgeScrollDelta(0f, 0f, 300f, false, true, policy), 0.01f)
        assertEquals(0f, executiveRailEdgeScrollDelta(150f, 0f, 300f, true, true, policy), 0.01f)
    }

    @Test
    fun `touch exploration disables scrub while ordinary semantic activation remains separate`() {
        assertFalse(executiveRailScrubEnabled(touchExplorationEnabled = true))
        assertTrue(executiveRailScrubEnabled(touchExplorationEnabled = false))
    }
}
