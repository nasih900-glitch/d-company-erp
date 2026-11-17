package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.sync.RealtimeEvent
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TillOperatorDisplayTest {
    private val terminal = ValidatedTerminalDisplay("till", "Counter", "branch", TerminalPurpose.HYBRID)
    private val profile = MeResponse("actor-a", "a@example.test", "Cashier A", companyId = "company",
        branchId = "branch", effectivePermissions = listOf(ErpPermission.PosWrite))
    private val lease = CacheScopeLease(CacheScope("actor-a", "company", "branch", "till"), 1)
    private val scope = requireNotNull(tillOperatorScope(profile, terminal, lease)).copy(shiftId = "shift")
    private fun recorded(user: String = "operator-a", name: String = "Operator A", revision: Int = 1) =
        TillOperatorRead("shift", "open", user, name, revision, "recorded")

    @Test
    fun `home launch hint requires exact fresh display and still current online scope`() = runBlocking {
        val owner = Job()
        var online = true
        var current = true
        var loads = 0
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner),
            { online }, { current }) { loads++; recorded() }
        controller.setScope(scope)
        val display = await(controller, TillOperatorStatus.VERIFIED)
        assertEquals("shift", controller.verifiedOpenShiftHint(display))
        assertNull(controller.verifiedOpenShiftHint(display.copy()))
        online = false
        assertNull(controller.verifiedOpenShiftHint(display))
        online = true
        current = false
        assertNull(controller.verifiedOpenShiftHint(display))
        current = true
        assertEquals(1, loads)
        controller.refresh()
        assertNull(controller.verifiedOpenShiftHint(display))
        assertNull(controller.verifiedOpenShiftHint(requireNotNull(controller.display.value)))
        owner.cancel()
    }

    @Test
    fun `home launch hint rejects old generation and no shift but accepts verified untracked shift`() = runBlocking {
        val owner = Job()
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner), { true }, { true }) {
            TillOperatorRead("shift", "open", revision = 0, tracking = "untracked")
        }
        controller.setScope(scope)
        val original = await(controller, TillOperatorStatus.VERIFIED)
        assertEquals("shift", controller.verifiedOpenShiftHint(original))
        controller.setScope(scope.copy(lease = lease.copy(generation = 2)))
        assertNull(controller.verifiedOpenShiftHint(original))
        val incoming = await(controller, TillOperatorStatus.VERIFIED)
        assertEquals("shift", controller.verifiedOpenShiftHint(incoming))
        controller.setScope(scope.copy(shiftId = null))
        assertNull(controller.verifiedOpenShiftHint(requireNotNull(controller.display.value)))
        owner.cancel()
    }

    @Test
    fun `handover updates operator independently from immutable opener`() = runBlocking {
        val opener = cloud.dcompany.erp.ui.screens.shift.ShiftDetail("shift", "branch", status = "open",
            openedAt = "2026-11-07T08:00:00Z", openedByUserId = "operator-a", openedByName = "Operator A")
        var response = recorded()
        val owner = Job()
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner), { true }, { true }) { response }
        controller.setScope(scope)
        await(controller, TillOperatorStatus.VERIFIED)
        assertEquals("Till operator: Operator A", controller.display.value?.operatorLabel)
        response = recorded("operator-b", "Operator B", 2)
        controller.refresh()
        await(controller, TillOperatorStatus.VERIFIED)
        assertEquals("Till operator: Operator B", controller.display.value?.operatorLabel)
        assertEquals("Operator A", opener.openedByName)
        assertTrue(controller.display.value!!.detail.contains("Signed in: Cashier A"))
        owner.cancel()
    }

    @Test
    fun `untracked never invents custody from signed in actor`() = runBlocking {
        val owner = Job()
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner), { true }, { true }) {
            TillOperatorRead("shift", "open", revision = 0, tracking = "untracked")
        }
        controller.setScope(scope)
        val display = await(controller, TillOperatorStatus.VERIFIED)
        assertEquals("Till operator not recorded", display.operatorLabel)
        assertEquals("Handover not yet recorded · Signed in: Cashier A", display.detail)
        assertNull(display.verified?.operatorUserId)
        owner.cancel()
    }

    @Test
    fun `offline retains same scope last verified but changed lease close and signout clear it`() = runBlocking {
        val owner = Job()
        var online = true
        var calls = 0
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner), { online }, { true }) {
            calls++; recorded()
        }
        controller.setScope(scope)
        await(controller, TillOperatorStatus.VERIFIED)
        online = false
        controller.refresh()
        assertEquals(TillOperatorStatus.STALE, controller.display.value?.status)
        assertEquals("Till operator: Operator A", controller.display.value?.operatorLabel)
        assertTrue(controller.display.value!!.detail.contains("Last verified"))
        assertEquals(1, calls)
        controller.setScope(scope.copy(lease = lease.copy(generation = 2)))
        assertEquals(TillOperatorStatus.UNAVAILABLE, controller.display.value?.status)
        assertNull(controller.display.value?.verified)
        controller.setScope(scope.copy(shiftId = null))
        assertEquals("No open till shift", controller.display.value?.operatorLabel)
        controller.setScope(null)
        assertNull(controller.display.value)
        owner.cancel()
    }

    @Test
    fun `failed refresh reports stale and reconnect verifies again`() = runBlocking {
        val owner = Job()
        var failing = false
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner), { true }, { true }) {
            if (failing) throw IOException("offline") else recorded()
        }
        controller.setScope(scope)
        await(controller, TillOperatorStatus.VERIFIED)
        failing = true
        controller.refresh()
        await(controller, TillOperatorStatus.STALE)
        failing = false
        controller.refresh()
        await(controller, TillOperatorStatus.VERIFIED)
        assertTrue(RealtimeEvent.ReconnectedAfterGap.requiresTillOperatorRefresh())
        assertTrue(RealtimeEvent.Changed("shifts").requiresTillOperatorRefresh())
        assertFalse(RealtimeEvent.Changed("orders").requiresTillOperatorRefresh())
        owner.cancel()
    }

    @Test
    fun `late uncancellable response cannot overwrite incoming actor`() = runBlocking {
        val owner = Job()
        val started = CompletableDeferred<Unit>()
        var late: Continuation<TillOperatorRead>? = null
        var calls = 0
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner), { true }, { true }) {
            if (++calls == 1) suspendCoroutine { late = it; started.complete(Unit) }
            else recorded("operator-b", "Operator B", 2)
        }
        controller.setScope(scope)
        withTimeout(1_000) { started.await() }
        controller.setScope(scope.copy(lease = lease.copy(scope = lease.scope.copy(userId = "actor-b"), generation = 2),
            actorName = "Cashier B"))
        await(controller, TillOperatorStatus.VERIFIED)
        requireNotNull(late).resume(recorded())
        yield()
        assertEquals("Till operator: Operator B", controller.display.value?.operatorLabel)
        assertTrue(controller.display.value!!.detail.contains("Cashier B"))
        controller.setScope(null)
        yield()
        assertNull(controller.display.value)
        owner.cancel()
    }

    @Test
    fun `newer refresh supersedes older response within same shift`() = runBlocking {
        val owner = Job()
        val started = CompletableDeferred<Unit>()
        var late: Continuation<TillOperatorRead>? = null
        var calls = 0
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner), { true }, { true }) {
            if (++calls == 1) suspendCoroutine { late = it; started.complete(Unit) }
            else recorded("operator-b", "Operator B", 2)
        }
        controller.setScope(scope)
        withTimeout(1_000) { started.await() }
        controller.refresh()
        await(controller, TillOperatorStatus.VERIFIED)
        requireNotNull(late).resume(recorded())
        yield()
        assertEquals("operator-b", controller.display.value?.verified?.operatorUserId)
        owner.cancel()
    }

    @Test
    fun `permissions actor company and validated terminal gate requests`() {
        assertNotNull(tillOperatorScope(profile, terminal, lease))
        assertNull(tillOperatorScope(profile.copy(effectivePermissions = listOf(ErpPermission.PosRead)), terminal, lease))
        assertNotNull(tillOperatorScope(profile.copy(effectivePermissions = listOf(ErpPermission.PosShiftHandoverRecover)), terminal, lease))
        assertNull(tillOperatorScope(profile.copy(userId = "actor-b"), terminal, lease))
        assertNull(tillOperatorScope(profile.copy(companyId = "other"), terminal, lease))
        assertNull(tillOperatorScope(profile, terminal.copy(terminalId = "other"), lease))
        assertNull(tillOperatorScope(profile, terminal.copy(branchId = "other"), lease))
        assertNull(tillOperatorScope(profile, null, lease))
        assertNull(tillOperatorScope(null, terminal, lease))
    }

    @Test
    fun `revoked scope before request never loads`() = runBlocking {
        val owner = Job()
        var calls = 0
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner), { true }, { false }) {
            calls++; recorded()
        }
        controller.setScope(scope)
        yield()
        assertNull(controller.display.value)
        assertEquals(0, calls)
        owner.cancel()
    }

    @Test
    fun `wrong shift and malformed operator response stay unavailable`() = runBlocking {
        val owner = Job()
        var response = recorded().copy(shiftId = "other")
        val controller = TillOperatorDisplayController(CoroutineScope(coroutineContext + owner), { true }, { true }) { response }
        controller.setScope(scope)
        await(controller, TillOperatorStatus.UNAVAILABLE)
        response = recorded().copy(operatorUserId = null)
        controller.refresh()
        await(controller, TillOperatorStatus.UNAVAILABLE)
        assertNull(controller.display.value?.verified)
        owner.cancel()
    }

    @Test
    fun `display DTO accepts full response without reading opener or receipt`() {
        val response = ApiClient.json.decodeFromString(TillOperatorRead.serializer(), """
            {"shift_id":"shift","shift_status":"open","operator_user_id":"operator-b",
            "operator_name":"Operator B","revision":2,"tracking":"recorded",
            "own_active_checkout_order_ids":[],"latest_handover":{"opened_by_name":"Operator A"},
            "has_running_games":false,"recovery_target":null}
        """.trimIndent())
        assertEquals("operator-b", response.operatorUserId)
        assertEquals("Operator B", response.operatorName)
    }

    private suspend fun await(controller: TillOperatorDisplayController, status: TillOperatorStatus): TillOperatorDisplay =
        withTimeout(1_000) { requireNotNull(controller.display.first { it?.status == status }) }
}
