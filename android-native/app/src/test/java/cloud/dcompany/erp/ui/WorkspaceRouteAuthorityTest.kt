package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.alarm.OperationalNotificationTarget
import cloud.dcompany.erp.retainedGamingBillTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRouteAuthorityTest {
    private val allowed = mutableListOf(
        Destination.Dashboard,
        Destination.Gaming,
        Destination.Pos,
    )
    private var destination = Destination.Dashboard
    private val authority = MutableWorkspaceRouteAuthority(
        readDestination = { destination },
        writeDestination = { destination = it },
        allowedDestinations = { allowed },
    )

    @Test
    fun `exact request accepts once and advances one revision`() {
        val result = authority.request(Destination.Gaming, authority.snapshot())

        assertEquals(
            WorkspaceRouteRequestResult.Accepted(WorkspaceRouteSnapshot(Destination.Gaming, 1L)),
            result,
        )
        assertEquals(Destination.Gaming, destination)
        assertEquals(WorkspaceRouteSnapshot(Destination.Gaming, 1L), authority.snapshot())
    }

    @Test
    fun `stale expected revision rejects without a write`() {
        val initial = authority.snapshot()
        authority.replace(Destination.Gaming)
        val events = mutableListOf<String>()

        val result = authority.request(
            target = Destination.Pos,
            expected = initial,
            beforeCommit = { events += "before" },
            afterCommit = { events += "after" },
        )

        assertEquals(
            WorkspaceRouteRequestResult.Rejected(
                WorkspaceRouteRejection.STALE_REVISION,
                WorkspaceRouteSnapshot(Destination.Gaming, 1L),
            ),
            result,
        )
        assertEquals(Destination.Gaming, destination)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `target outside current scope rejects without a write`() {
        val result = authority.request(Destination.Settings, authority.snapshot())

        assertEquals(
            WorkspaceRouteRequestResult.Rejected(
                WorkspaceRouteRejection.OUTSIDE_CURRENT_SCOPE,
                WorkspaceRouteSnapshot(Destination.Dashboard, 0L),
            ),
            result,
        )
        assertEquals(Destination.Dashboard, destination)
    }

    @Test
    fun `retained Gaming bill target requires POS access and a nonblank identity`() {
        assertEquals(
            OperationalNotificationTarget.HeldOrder("retained-order-1"),
            retainedGamingBillTarget("retained-order-1", allowed),
        )
        assertNull(retainedGamingBillTarget("retained-order-1", allowed - Destination.Pos))
        assertNull(retainedGamingBillTarget("", allowed))
    }

    @Test
    fun `retained Gaming bill focus is not committed when POS route is rejected`() {
        allowed.remove(Destination.Pos)
        var focus: OperationalNotificationTarget? = null
        val target = OperationalNotificationTarget.HeldOrder("retained-order-1")

        val result = authority.replace(
            target = Destination.Pos,
            beforeCommit = { focus = target },
        )

        assertEquals(
            WorkspaceRouteRejection.OUTSIDE_CURRENT_SCOPE,
            (result as WorkspaceRouteRequestResult.Rejected).reason,
        )
        assertNull(focus)
        assertEquals(Destination.Dashboard, destination)
    }

    @Test
    fun `same destination is a no-op without revision inflation`() {
        val result = authority.request(Destination.Dashboard, authority.snapshot())

        assertEquals(
            WorkspaceRouteRequestResult.Rejected(
                WorkspaceRouteRejection.SAME_DESTINATION,
                WorkspaceRouteSnapshot(Destination.Dashboard, 0L),
            ),
            result,
        )
        assertEquals(WorkspaceRouteSnapshot(Destination.Dashboard, 0L), authority.snapshot())
    }

    @Test
    fun `permission normalization writes resolved fallback and advances revision`() {
        destination = Destination.Gaming
        allowed.remove(Destination.Gaming)
        assertEquals(WorkspaceRouteSnapshot(Destination.Dashboard, 0L), authority.snapshot())

        val result = authority.replace(Destination.Dashboard)

        assertEquals(
            WorkspaceRouteRequestResult.Accepted(WorkspaceRouteSnapshot(Destination.Dashboard, 1L)),
            result,
        )
        assertEquals(Destination.Dashboard, destination)
    }

    @Test
    fun `destination-equal external ABA invalidates the held expected revision`() {
        val acceptedGaming = authority.request(Destination.Gaming, authority.snapshot())
            as WorkspaceRouteRequestResult.Accepted
        authority.replace(Destination.Dashboard)
        authority.replace(Destination.Gaming)

        val result = authority.request(Destination.Pos, acceptedGaming.snapshot)

        assertEquals(
            WorkspaceRouteRequestResult.Rejected(
                WorkspaceRouteRejection.STALE_REVISION,
                WorkspaceRouteSnapshot(Destination.Gaming, 3L),
            ),
            result,
        )
        assertEquals(Destination.Gaming, destination)
    }

    @Test
    fun `proposal cannot advance local acknowledgement without exact owner acceptance`() {
        val policy = ExecutiveRailScrubPolicy(
            boundaryHysteresisPx = 4f,
            edgeZonePx = 30f,
            maximumEdgeStepPx = 8f,
        )
        var state = claimExecutiveRailScrub(
            beginExecutiveRailPress(
                origin = Destination.Dashboard,
                authoritySnapshot = authority.snapshot(),
                modelKey = allowed,
            ),
        )
        state = updateExecutiveRailScrubTarget(state, Destination.Gaming, 1_000L, policy).state
        val proposal = updateExecutiveRailScrubTarget(state, Destination.Gaming, 1_190L, policy)

        assertEquals(Destination.Gaming, proposal.navigateTo)
        assertEquals(WorkspaceRouteSnapshot(Destination.Dashboard, 0L), proposal.state.lastAcknowledged)
        assertNull(
            acknowledgeExecutiveRailScrub(
                state = proposal.state,
                proposedTarget = Destination.Gaming,
                accepted = WorkspaceRouteSnapshot(Destination.Gaming, 2L),
                nowMillis = 1_190L,
            ),
        )
        assertTrue(authority.snapshot().revision == 0L)
    }

    @Test
    fun `guarded request preserves admission write and completion ordering`() {
        val events = mutableListOf<String>()
        var routed = Destination.Dashboard
        val orderedAuthority = MutableWorkspaceRouteAuthority(
            readDestination = { routed },
            writeDestination = {
                events += "write:$it"
                routed = it
            },
            allowedDestinations = { allowed },
        )

        val result = orderedAuthority.request(
            target = Destination.Gaming,
            expected = orderedAuthority.snapshot(),
            beforeCommit = { events += "before:${orderedAuthority.snapshot()}" },
            afterCommit = { events += "after:${orderedAuthority.snapshot()}" },
        )

        assertTrue(result is WorkspaceRouteRequestResult.Accepted)
        assertEquals(
            listOf(
                "before:WorkspaceRouteSnapshot(destination=Dashboard, revision=0)",
                "write:Gaming",
                "after:WorkspaceRouteSnapshot(destination=Gaming, revision=1)",
            ),
            events,
        )
    }

    @Test
    fun `external same-route effects retain baseline ordering without revision inflation`() {
        val events = mutableListOf<String>()
        var routed = Destination.Dashboard
        var writeCount = 0
        val orderedAuthority = MutableWorkspaceRouteAuthority(
            readDestination = { routed },
            writeDestination = {
                writeCount += 1
                routed = it
            },
            allowedDestinations = { allowed },
        )

        val result = orderedAuthority.replace(
            target = Destination.Dashboard,
            beforeCommit = { events += "before" },
            afterCommit = { events += "after" },
        )

        assertEquals(
            WorkspaceRouteRequestResult.Rejected(
                WorkspaceRouteRejection.SAME_DESTINATION,
                WorkspaceRouteSnapshot(Destination.Dashboard, 0L),
            ),
            result,
        )
        assertEquals(listOf("before", "after"), events)
        assertEquals(0, writeCount)
        assertEquals(WorkspaceRouteSnapshot(Destination.Dashboard, 0L), orderedAuthority.snapshot())
    }

    @Test
    fun `rejected target has no pre-write or post-write effects`() {
        val events = mutableListOf<String>()

        val result = authority.replace(
            target = Destination.Settings,
            beforeCommit = { events += "before" },
            afterCommit = { events += "after" },
        )

        assertTrue(result is WorkspaceRouteRequestResult.Rejected)
        assertTrue(events.isEmpty())
        assertEquals(WorkspaceRouteSnapshot(Destination.Dashboard, 0L), authority.snapshot())
    }

    @Test
    fun `scope list replacement preserves owner revision and rejects ABA plus stale target`() {
        var scope = listOf(
            Destination.Dashboard,
            Destination.Gaming,
            Destination.Pos,
            Destination.Help,
        )
        var routed = Destination.Dashboard
        val stableAuthority = MutableWorkspaceRouteAuthority(
            readDestination = { routed },
            writeDestination = { routed = it },
            allowedDestinations = { scope },
        )
        val acceptedGaming = stableAuthority.request(Destination.Gaming, stableAuthority.snapshot())
            as WorkspaceRouteRequestResult.Accepted

        // Replace the permission-list instance while retaining every direct rail target.
        scope = listOf(
            Destination.Dashboard,
            Destination.Gaming,
            Destination.Pos,
            Destination.AuditLog,
        )
        stableAuthority.replace(Destination.Dashboard)
        stableAuthority.replace(Destination.Gaming)
        val staleAfterAba = stableAuthority.request(Destination.Pos, acceptedGaming.snapshot)

        assertEquals(
            WorkspaceRouteRequestResult.Rejected(
                WorkspaceRouteRejection.STALE_REVISION,
                WorkspaceRouteSnapshot(Destination.Gaming, 3L),
            ),
            staleAfterAba,
        )

        scope = listOf(Destination.Dashboard, Destination.Gaming, Destination.AuditLog)
        val deniedAfterScopeChange = stableAuthority.request(
            Destination.Pos,
            stableAuthority.snapshot(),
        )
        assertEquals(
            WorkspaceRouteRequestResult.Rejected(
                WorkspaceRouteRejection.OUTSIDE_CURRENT_SCOPE,
                WorkspaceRouteSnapshot(Destination.Gaming, 3L),
            ),
            deniedAfterScopeChange,
        )
        assertEquals(Destination.Gaming, routed)
    }
}
