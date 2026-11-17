package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.*
import cloud.dcompany.erp.core.db.*
import cloud.dcompany.erp.core.handover.*
import cloud.dcompany.erp.core.net.MeResponse
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CashierSignOutGuardTest {
    private class Store : HandoverIntentStore {
        override suspend fun read(): HandoverIntent? = null
        override suspend fun begin(intent: HandoverIntent) = error("No handover is created by sign-out")
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) = error("No handover mutation")
        override suspend fun cancelNotSent(expected: HandoverIntent) = error("No handover mutation")
    }
    private class Marker(var scope: CacheScope?) : CacheScopeMarker {
        override fun current() = scope
        override fun remember(scope: CacheScope): Boolean { this.scope = scope; return true }
        override fun clear(): Boolean { scope = null; return true }
    }
    private class Fixture {
        val scope = CacheScope(id(), id(), id(), id())
        val cache = CacheIsolationCoordinator(object : ScopeDataPurger {
            override suspend fun hasUnresolvedWork() = false
            override suspend fun purgeIfClean() = true
        }, Marker(scope))
        val coordinator = HandoverCoordinator(Store())
        var login = SessionRefreshLease(StoredSession("synthetic-access", "synthetic-refresh"), 1)
        val profile = MeResponse(scope.userId, "actor@example.test", "Actor", listOf("store_staff"), companyId = scope.companyId,
            branchId = scope.branchId, effectivePermissions = listOf("pos.write", "pos.shift.open", "pos.shift.close"))
        val signedIn = AuthState.SignedIn(profile)
        var state: AuthState = signedIn
        var terminal = ValidatedTerminalDisplay(requireNotNull(scope.terminalId), "Shop till", requireNotNull(scope.branchId), "hybrid")
        var accessAvailable = true
        val local = MutableStateFlow<LocalShiftEntity?>(null)
        val server = MutableStateFlow<ServerOpenShiftEntity?>(null)
        var reads = 0
        lateinit var originalLease: CacheScopeLease
        lateinit var actor: HandoverAuthority
        lateinit var authority: WorkspaceExitAuthority
        lateinit var captured: CashierSignOutScope
        suspend fun start() {
            cache.activateValidated(scope)
            originalLease = requireNotNull(cache.currentLease())
            actor = HandoverAuthority(HandoverScope(scope.userId, scope.companyId, scope.branchId, scope.terminalId), 1)
            coordinator.initialize(actor)
            val originalLogin = login
            val original = OriginalOperationBinding(actor, originalLease, originalLogin) {
                cache.currentLease() == originalLease && login.session.lineage === originalLogin.session.lineage
            }
            authority = WorkspaceExitAuthority(original, originalLogin, originalLease, cache.currentGeneration(), { login },
                { expected -> login.accessToken.takeIf { accessAvailable && login.session.lineage === expected.session.lineage } },
                cache::currentLease, cache::currentGeneration)
            captured = requireNotNull(CashierSignOutScope.capture(signedIn, login, originalLease, cache.currentGeneration(), terminal))
        }
        fun current() = captured.isCurrent(state, login, cache.currentLease(), cache.currentGeneration(), terminal, accessAvailable) && authority.beforeDeactivation()
        fun guard(timeout: Long = 1000, read: (suspend () -> ResolvedOpenShift?)? = null) = CashierSignOutGuard(::current, {
            reads++
            if (read != null) read() else combine(local, server) { l, s -> ShiftResolutionPolicy.resolve(l, s, includeClosingIntent = true) }.first()
        }, timeout)
        fun localShift(status: String) = LocalShiftEntity(id(), terminalId = scope.terminalId, branchId = scope.branchId,
            openingFloatMinor = 0, openedAtMillis = 1, state = status)
        suspend fun finalize(cap: HandoverCoordinator.WorkspaceExit, guard: CashierSignOutGuard, outbox: suspend () -> OutboxGateResult): Boolean =
            coordinator.finalizeWorkspaceExit(cap, authority::beforeDeactivation) {
                cache.deactivateOriginalAfterOutboxGate(originalLease, originalLease.generation, authority::beforeDeactivation,
                    { commit -> if (authority.beforeDeactivation()) { commit(); true } else false }) {
                    val shift = guard.check()
                    if (shift is OutboxGateResult.Blocked) shift else outbox()
                } is OutboxGateResult.Allowed
            }
    }

    @Test fun `cashier duty excludes readers and service accounts without shift controls`() {
        fun profile(vararg permission: String) = MeResponse("actor", "a@test", "Actor", listOf("auditor"), companyId = "company", effectivePermissions = permission.toList())
        assertFalse(hasCashierDuty(profile("pos.read")))
        assertFalse(hasCashierDuty(profile("pos.write", "staff.attendance.write")))
        assertFalse(hasCashierDuty(profile("kitchen.write")))
        assertTrue(hasCashierDuty(profile("pos.write", "pos.shift.open")))
        assertTrue(hasCashierDuty(profile("pos.write", "pos.shift.close")))
    }

    @Test fun `preflight refuses every live local lifecycle and server-open cache before exit admission`() = runBlocking {
        for (status in listOf(ShiftState.OPEN_PENDING, ShiftState.OPEN_SYNCED, ShiftState.CLOSE_PENDING, ShiftState.CLOSE_REJECTED, "server")) {
            val f = Fixture(); f.start()
            if (status == "server") f.server.value = ServerOpenShiftEntity(f.terminal.terminalId, id(), f.terminal.branchId, "open", 0, openedAtMillis = 1, verifiedAtMillis = 1)
            else f.local.value = f.localShift(status)
            var logoutMutations = 0
            if (f.guard().check() is OutboxGateResult.Allowed) logoutMutations++
            assertEquals(0, logoutMutations)
            assertNull(f.coordinator.state.value.workspaceExitKey)
            assertEquals(f.originalLease, f.cache.currentLease())
            assertTrue(f.current())
        }
    }

    @Test fun `read failure timeout and cancellation never become a clean sign-out`() = runBlocking {
        val f = Fixture(); f.start()
        assertTrue(f.guard(read = { throw IOException("unavailable") }).check() is OutboxGateResult.Blocked)
        assertTrue(f.guard(timeout = 5, read = { awaitCancellation() }).check() is OutboxGateResult.Blocked)
        val entered = CompletableDeferred<Unit>()
        val attempt = async { f.guard(read = { entered.complete(Unit); awaitCancellation() }).check() }
        entered.await(); attempt.cancelAndJoin()
        assertTrue(attempt.isCancelled)
        assertNull(f.coordinator.state.value.workspaceExitKey)
        assertEquals(f.originalLease, f.cache.currentLease())
    }

    @Test fun `late reads cannot authorize logout or publish an old actor notice after any scope drift`() = runBlocking {
        for (drift in listOf("state", "login", "lease", "terminal", "branch", "access")) {
            val f = Fixture(); f.start()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val guard = f.guard(read = { entered.complete(Unit); release.await(); null })
            val result = async { guard.check() }
            entered.await()
            when (drift) {
                "state" -> f.state = AuthState.SignedIn(f.profile)
                "login" -> f.login = SessionRefreshLease(StoredSession("synthetic-new", "synthetic-new-refresh"), 2)
                "lease" -> f.cache.activateValidated(f.scope.copy(terminalId = id()))
                "terminal" -> f.terminal = f.terminal.copy(terminalId = id())
                "branch" -> f.terminal = f.terminal.copy(branchId = id())
                "access" -> f.accessAvailable = false
            }
            release.complete(Unit)
            assertTrue(result.await() is OutboxGateResult.Blocked)
            assertFalse(guard.current())
            assertNull(f.coordinator.state.value.workspaceExitKey)
        }
    }

    @Test fun `a shift opened by an admitted operation during drain is caught by the final cache gate`() = runBlocking {
        val f = Fixture(); f.start()
        val opening = f.coordinator.admit(f.actor)
        val guard = f.guard()
        assertTrue(guard.check() is OutboxGateResult.Allowed)
        val cap = f.coordinator.beginWorkspaceExit(f.actor, WorkspaceExitKind.LOGOUT, f.authority::beforeDeactivation)
        assertTrue(runCatching { f.coordinator.admit(f.actor) }.isFailure)
        val drained = async { f.coordinator.awaitWorkspaceExitDrained(cap, 1000) }
        yield(); assertFalse(drained.isCompleted)
        f.local.value = f.localShift(ShiftState.OPEN_PENDING)
        f.coordinator.finish(opening, OperationOutcome.DURABLE_OUTCOME)
        assertTrue(drained.await())
        var outboxChecks = 0
        assertFalse(f.finalize(cap, guard) { outboxChecks++; OutboxGateResult.Allowed })
        assertEquals(2, f.reads)
        assertEquals(0, outboxChecks)
        assertFalse(cap.deactivated)
        assertEquals(f.originalLease, f.cache.currentLease())
        f.coordinator.abortWorkspaceExit(cap, f.authority::beforeDeactivation)
        f.coordinator.finish(f.coordinator.admit(f.actor), OperationOutcome.NO_WRITE_SENT)
    }

    @Test fun `closed shift still requires the existing outbox gate and preserves workspace on refusal`() = runBlocking {
        val f = Fixture(); f.start(); val guard = f.guard()
        assertTrue(guard.check() is OutboxGateResult.Allowed)
        val cap = f.coordinator.beginWorkspaceExit(f.actor, WorkspaceExitKind.LOGOUT, f.authority::beforeDeactivation)
        assertTrue(f.coordinator.awaitWorkspaceExitDrained(cap, 1000))
        var outboxChecks = 0
        assertFalse(f.finalize(cap, guard) { outboxChecks++; OutboxGateResult.Blocked("Saved bill") })
        assertEquals(1, outboxChecks)
        assertEquals(f.originalLease, f.cache.currentLease())
    }

    @Test fun `branchless validated Owner scope and token refresh lineage remain valid`() = runBlocking {
        val f = Fixture(); f.start()
        val rotated = SessionRefreshLease(StoredSession("rotated-access", "rotated-refresh", f.login.session.lineage), 2)
        assertTrue(f.captured.isCurrent(f.signedIn, rotated, f.originalLease, f.originalLease.generation, f.terminal, true))
        val owner = AuthState.SignedIn(f.profile.copy(roles = listOf("owner"), branchId = null))
        val lease = f.originalLease.copy(scope = f.scope.copy(branchId = null))
        assertNotNull(CashierSignOutScope.capture(owner, f.login, lease, lease.generation, f.terminal))
        assertNull(CashierSignOutScope.capture(owner, f.login, null, lease.generation, f.terminal))
        assertNull(CashierSignOutScope.capture(owner, f.login, lease, lease.generation, null))
    }

    @Test fun `stale retained pre-admission authority never cancels newer actor work even without a cashier guard`() = runBlocking {
        val f = Fixture(); f.start()
        val pendingOriginalAuthority = f.authority
        f.login = SessionRefreshLease(StoredSession("actor-B", "refresh-B"), 2)
        var cancellationEffects = 0
        val result = withCurrentLogoutIntent(false, pendingOriginalAuthority::beforeDeactivation, null) { cancellationEffects++ }
        assertNull(result)
        assertEquals(0, cancellationEffects)
        assertTrue(mayForgetUnstartedLogoutMetadata(false, f.coordinator.state.value))
        assertFalse(mayForgetUnstartedLogoutMetadata(false, f.coordinator.state.value.copy(storageFault = true)))
        assertFalse(mayForgetUnstartedLogoutMetadata(false, f.coordinator.state.value.copy(workspaceExitKey = "retained-exit")))
        assertEquals("actor-B", f.login.accessToken)
        assertEquals(f.originalLease, f.cache.currentLease())
    }

    @Test fun `registered retry fences stale profile before effects while valid deactivated cleanup remains possible`() = runBlocking {
        val f = Fixture(); f.start(); val guard = f.guard()
        assertTrue(guard.check() is OutboxGateResult.Allowed)
        val cap = f.coordinator.beginWorkspaceExit(f.actor, WorkspaceExitKind.LOGOUT, f.authority::beforeDeactivation)
        assertFalse(mayForgetUnstartedLogoutMetadata(true, f.coordinator.state.value))
        f.state = AuthState.SignedIn(f.profile)
        var effects = 0
        assertNull(withCurrentLogoutIntent(false, f.authority::beforeDeactivation, guard) { effects++ })
        assertEquals(0, effects)
        f.state = f.signedIn
        assertTrue(f.finalize(cap, guard) { OutboxGateResult.Allowed })
        f.state = AuthState.SignOutFailed("Synthetic cleanup retry")
        assertFalse(guard.current())
        assertNotNull(withCurrentLogoutIntent(true, { f.authority.afterDeactivation() || f.authority.localSessionRemoved() }, guard) { effects++; true })
        assertEquals(1, effects)
        f.login = SessionRefreshLease(StoredSession("actor-B", "refresh-B"), 2)
        assertNull(withCurrentLogoutIntent(true, { f.authority.afterDeactivation() || f.authority.localSessionRemoved() }, guard) { effects++ })
        assertEquals(1, effects)
    }

    companion object { private fun id() = UUID.randomUUID().toString() }
}
