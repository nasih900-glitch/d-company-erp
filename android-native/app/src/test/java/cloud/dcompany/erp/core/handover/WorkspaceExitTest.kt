package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WorkspaceExitTest {
    private class Store : HandoverIntentStore {
        var retained: HandoverIntent? = null
        var failRead = false
        override suspend fun read(): HandoverIntent? { check(!failRead); return retained }
        override suspend fun begin(intent: HandoverIntent) { retained = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { check(retained == expected); retained = next }
        override suspend fun cancelNotSent(expected: HandoverIntent) { error("Exit cannot erase handover evidence") }
    }
    private class Fixture(val operational: Boolean = true) {
        val store = Store(); val coordinator = HandoverCoordinator(store)
        val scope = HandoverScope(id(), id(), null, null)
        val actor = HandoverAuthority(scope, 1)
        var generation = 1L
        var cache: CacheScopeLease? = if (operational) CacheScopeLease(CacheScope(scope.actorId, scope.companyId, null, null), generation) else null
        var login: SessionRefreshLease? = SessionRefreshLease(StoredSession("synthetic-A", "synthetic-refresh"), 1)
        val originalLogin = requireNotNull(login)
        val originalCache = cache
        val binding = cache?.let { original -> OriginalOperationBinding(actor, original, originalLogin) {
            cache == original && login?.session?.lineage === originalLogin.session.lineage
        } }
        val authority = WorkspaceExitAuthority(binding, originalLogin, originalCache, generation,
            { login }, { lease -> login?.accessToken?.takeIf { login?.session?.lineage === lease.session.lineage } }, { cache }, { generation })
        suspend fun start() { if (operational) coordinator.initialize(actor) }
        suspend fun begin(kind: WorkspaceExitKind = WorkspaceExitKind.LOGOUT) =
            coordinator.beginWorkspaceExit(binding?.authority, kind, authority::beforeDeactivation)
        suspend fun deactivate(cap: HandoverCoordinator.WorkspaceExit) = coordinator.finalizeWorkspaceExit(cap, authority::beforeDeactivation) {
            cache = null; generation++; true
        }
    }
    @Test fun fullOldAChildMustFinishAndBlockedGateNeverRevokesTheSession() = runBlocking {
        val f = Fixture(); f.start(); val parent = f.coordinator.admit(f.actor)
        val cap = f.begin(); val child = f.coordinator.child(parent)
        assertTrue(runCatching { f.coordinator.admit(f.actor) }.isFailure)
        assertFalse(f.coordinator.awaitWorkspaceExitDrained(cap, 5))
        f.coordinator.finish(child, OperationOutcome.NO_WRITE_SENT)
        f.coordinator.finish(parent, OperationOutcome.NO_WRITE_SENT)
        assertTrue(f.coordinator.awaitWorkspaceExitDrained(cap, 5))
        assertFalse(f.coordinator.finalizeWorkspaceExit(cap, f.authority::beforeDeactivation) { false })
        assertFalse(cap.deactivated); assertNotNull(f.login); assertNotNull(f.cache)
        f.coordinator.abortWorkspaceExit(cap, f.authority::beforeDeactivation)
        f.coordinator.finish(f.coordinator.admit(f.actor), OperationOutcome.NO_WRITE_SENT)
    }
    @Test fun finalCacheGateHasNoReopenWindowAndCleanupFailureStaysClosed() = runBlocking {
        val f = Fixture(); f.start(); val cap = f.begin()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val ending = async { f.coordinator.finalizeWorkspaceExit(cap, f.authority::beforeDeactivation) {
            entered.complete(Unit); release.await(); f.cache = null; f.generation++; true
        } }
        entered.await(); val late = async { runCatching { f.coordinator.admit(f.actor) }.isFailure }
        assertFalse(late.isCompleted); release.complete(Unit)
        assertTrue(ending.await()); assertTrue(late.await())
        assertTrue(runCatching { f.coordinator.abortWorkspaceExit(cap, f.authority::beforeDeactivation) }.isFailure)
        assertTrue(runCatching { f.coordinator.completeWorkspaceExit(cap) { false } }.isFailure)
        assertEquals(cap.key, f.coordinator.state.value.workspaceExitKey)
        f.login = null
        f.coordinator.completeWorkspaceExit(cap, f.authority::localSessionRemoved)
        assertNull(f.coordinator.state.value.workspaceExitKey)
    }
    @Test fun unadoptedCacheIsNeverInventedOrPurgedAndRetainedHandoverPreventsExit() = runBlocking {
        val f = Fixture(false); f.start(); val cap = f.begin()
        assertNull(cap.authority); assertFalse(f.coordinator.state.value.initialized)
        assertTrue(f.deactivate(cap)); assertNull(f.cache); assertNull(f.store.retained)
        f.login = null; f.coordinator.completeWorkspaceExit(cap, f.authority::localSessionRemoved)
        val held = Fixture(false)
        held.store.retained = HandoverIntent(HandoverAction(id(), HandoverAuthority(HandoverScope(id(), id(), id(), id()), 1), id(), id(), 0))
        assertTrue(runCatching { held.begin() }.isFailure); assertNotNull(held.store.retained)
    }
    @Test fun unresolvedWorkAndStoreFailureNeverBecomeACleanExit() = runBlocking {
        val f = Fixture(); f.start(); val old = f.coordinator.admit(f.actor)
        f.coordinator.beforeMutationDispatch(old, f.actor); f.coordinator.finish(old, OperationOutcome.UNRESOLVED)
        val cap = f.begin(); assertFalse(f.coordinator.awaitWorkspaceExitDrained(cap, 5))
        assertTrue(runCatching { f.deactivate(cap) }.isFailure)
        f.coordinator.abortWorkspaceExit(cap, f.authority::beforeDeactivation)
        assertTrue(f.coordinator.state.value.unresolvedOperations.contains(old.id))
        val broken = Fixture(); broken.start(); broken.store.failRead = true
        assertTrue(runCatching { broken.begin() }.isFailure)
        assertTrue(broken.coordinator.state.value.storageFault); assertNotNull(broken.cache); assertNotNull(broken.login)
    }
    @Test fun exactExitReadRunsOutsideLocksButCannotDispatchMutationOrBootstrapLogin() = runBlocking {
        val f = Fixture(); f.start(); val cap = f.begin(WorkspaceExitKind.TERMINAL_CHANGE)
        val gate = CoordinatorOperationDispatchGate(f.coordinator) { HandoverStartupStatus.Preparing }
        f.coordinator.observeWorkspaceExit(cap, f.authority::beforeDeactivation) { permit ->
            assertTrue(runCatching { f.coordinator.child(permit) }.isFailure)
            val context = OperationDispatchContext(requireNotNull(f.binding), permit)
            fun request(path: String, post: Boolean = false) = Request.Builder().url("http://localhost/api/v1/$path")
                .tag(OperationDispatchContext::class.java, context).apply { if (post) post("{}".toRequestBody()) }.build()
            gate.authorize(request("pos/shifts?only_open=true&limit=200"))
            gate.authorize(request("gaming/sessions?unbilled_only=true&limit=500"))
            assertTrue(runCatching { gate.authorize(request("auth/login", true)) }.isFailure)
            assertTrue(runCatching { gate.authorize(request("pos/shifts/open", true)) }.isFailure)
            assertTrue(runCatching { gate.authorize(request("finance/expenses")) }.isFailure)
        }
        assertTrue(f.coordinator.state.value.activeOperations.isEmpty())
        assertTrue(f.deactivate(cap))
    }
    @Test fun logoutUsesActualSingleAttemptFactoryOnlyAfterDeactivationAndNeverBorrowsB() = runBlocking {
        for (drift in listOf("none", "login", "cache")) {
            val f = Fixture(); f.start(); val cap = f.begin()
            MockWebServer().use { server ->
                server.start(); repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
                val client = workspaceLogoutClient(f.authority, cap, f.coordinator)
                try {
                    val request = Request.Builder().url(server.url("/api/v1/auth/logout")).post("{}".toRequestBody()).build()
                    assertTrue(runCatching { client.newCall(request).execute().close() }.isFailure)
                    assertEquals(0, server.requestCount)
                    f.deactivate(cap)
                    if (drift == "login") f.login = SessionRefreshLease(StoredSession("synthetic-B", "new-lineage"), 2)
                    if (drift == "cache") { f.generation++; f.cache = f.originalCache?.copy(generation = f.generation) }
                    val result = runCatching { client.newCall(request).execute().close() }
                    if (drift == "none") {
                        result.getOrThrow(); assertEquals(1, server.requestCount)
                        val recorded = requireNotNull(server.takeRequest())
                        assertEquals("Bearer synthetic-A", recorded.getHeader("Authorization"))
                        assertNull(recorded.getHeader("X-Terminal-Id"))
                        assertTrue(runCatching { client.newCall(request).execute().close() }.isFailure)
                        assertEquals(1, server.requestCount)
                    } else { assertTrue(result.isFailure); assertEquals(0, server.requestCount) }
                } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            }
        }
    }
    companion object { private fun id() = UUID.randomUUID().toString() }
}
