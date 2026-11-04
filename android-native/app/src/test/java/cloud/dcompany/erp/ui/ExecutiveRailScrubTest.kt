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
    fun `dwell follows deliberate targets before contact ends without duplicates`() {
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Dashboard, snapshot(Destination.Dashboard), targets),
        )

        var update = updateExecutiveRailScrubTarget(state, Destination.Gaming, 1_000L, policy)
        assertNull(update.navigateTo)
        state = update.state
        update = updateExecutiveRailScrubTarget(state, Destination.Gaming, 1_189L, policy)
        assertNull(update.navigateTo)
        update = updateExecutiveRailScrubTarget(update.state, Destination.Gaming, 1_190L, policy)
        assertEquals(Destination.Gaming, update.navigateTo)

        state = acknowledgeExecutiveRailScrub(
            update.state,
            Destination.Gaming,
            snapshot(Destination.Gaming, 1L),
            1_190L,
        )!!
        update = updateExecutiveRailScrubTarget(state, Destination.Gaming, 1_900L, policy)
        assertNull("Holding the committed row must not navigate twice", update.navigateTo)
        state = update.state
        update = updateExecutiveRailScrubTarget(state, Destination.Pos, 2_000L, policy)
        update = updateExecutiveRailScrubTarget(update.state, Destination.Pos, 2_190L, policy)
        assertEquals(Destination.Pos, update.navigateTo)

        state = acknowledgeExecutiveRailScrub(
            update.state,
            Destination.Pos,
            snapshot(Destination.Pos, 2L),
            2_190L,
        )!!

        val ended = cancelExecutiveRailScrub(state)
        assertEquals(ExecutiveRailScrubPhase.Ended, ended.phase)
        assertNull("Ending contact adds no final navigation", ended.candidate)
    }

    @Test
    fun `owner snapshot requires exact destination and revision without composition grace`() {
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Dashboard, snapshot(Destination.Dashboard), targets),
        )
        assertTrue(executiveRailObservedSnapshotBelongsToStream(state, snapshot(Destination.Dashboard)))

        state = updateExecutiveRailScrubTarget(state, Destination.Gaming, 100L, policy).state
        val proposal = updateExecutiveRailScrubTarget(state, Destination.Gaming, 290L, policy)
        state = acknowledgeExecutiveRailScrub(
            proposal.state,
            Destination.Gaming,
            snapshot(Destination.Gaming, 1L),
            290L,
        )!!
        assertEquals(Destination.Gaming, state.lastCommitted)
        assertFalse(
            executiveRailObservedSnapshotBelongsToStream(state, snapshot(Destination.Dashboard, 0L)),
        )
        assertTrue(executiveRailObservedSnapshotBelongsToStream(state, snapshot(Destination.Gaming, 1L)))
        assertFalse(
            "Destination-equal ABA must be visible through its newer owner revision",
            executiveRailObservedSnapshotBelongsToStream(state, snapshot(Destination.Gaming, 3L)),
        )
    }

    @Test
    fun `fast crossing never constructs an intermediate destination`() {
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Dashboard, snapshot(Destination.Dashboard), targets),
        )
        state = updateExecutiveRailScrubTarget(state, Destination.Gaming, 100L, policy).state
        val crossed = updateExecutiveRailScrubTarget(state, Destination.Pos, 180L, policy)
        assertNull(crossed.navigateTo)
        val committed = updateExecutiveRailScrubTarget(crossed.state, Destination.Pos, 370L, policy)
        assertEquals(Destination.Pos, committed.navigateTo)
    }

    @Test
    fun `origin requires leaving before it can become a live target`() {
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Gaming, snapshot(Destination.Dashboard), targets),
        )
        var update = updateExecutiveRailScrubTarget(state, Destination.Gaming, 100L, policy)
        update = updateExecutiveRailScrubTarget(update.state, Destination.Gaming, 500L, policy)
        assertNull(update.navigateTo)

        state = updateExecutiveRailScrubTarget(update.state, Destination.Pos, 600L, policy).state
        val posProposal = updateExecutiveRailScrubTarget(state, Destination.Pos, 790L, policy)
        state = acknowledgeExecutiveRailScrub(
            posProposal.state,
            Destination.Pos,
            snapshot(Destination.Pos, 1L),
            790L,
        )!!
        update = updateExecutiveRailScrubTarget(state, Destination.Gaming, 900L, policy)
        update = updateExecutiveRailScrubTarget(update.state, Destination.Gaming, 1_090L, policy)
        assertEquals(Destination.Gaming, update.navigateTo)
    }

    @Test
    fun `model filters targets and cancellation cannot emit navigation`() {
        val restricted = listOf(Destination.Dashboard, Destination.Pos)
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Dashboard, snapshot(Destination.Dashboard), restricted),
        )
        var update = updateExecutiveRailScrubTarget(state, Destination.Gaming, 100L, policy)
        update = updateExecutiveRailScrubTarget(update.state, Destination.Gaming, 500L, policy)
        assertNull(update.navigateTo)
        state = cancelExecutiveRailScrub(update.state)
        update = updateExecutiveRailScrubTarget(state, Destination.Pos, 1_000L, policy)
        assertNull(update.navigateTo)
        assertEquals(ExecutiveRailScrubPhase.Ended, update.state.phase)
    }

    @Test
    fun `real auto scroll resets dwell and requires a stable post scroll target`() {
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(Destination.Dashboard, snapshot(Destination.Dashboard), targets),
        )
        state = updateExecutiveRailScrubTarget(state, Destination.Settings, 100L, policy).state
        val moved = updateExecutiveRailScrubTarget(
            state,
            Destination.Settings,
            400L,
            policy,
            autoScrollMoved = true,
        )
        assertNull(moved.navigateTo)
        var stable = updateExecutiveRailScrubTarget(moved.state, Destination.Settings, 500L, policy)
        assertNull(stable.navigateTo)
        stable = updateExecutiveRailScrubTarget(stable.state, Destination.Settings, 689L, policy)
        assertNull(stable.navigateTo)
        stable = updateExecutiveRailScrubTarget(stable.state, Destination.Settings, 690L, policy)
        assertEquals(Destination.Settings, stable.navigateTo)
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
