package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class RemoteHeartbeatLifetimeTest {
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { check(value == expected); value = next }
        override suspend fun cancelNotSent(expected: HandoverIntent) { value = null }
    }
    private class Fixture {
        val store = Store(); val startup = HandoverStartupFence(store)
        var ready: PersistedStartupStateResult? = null
        var profile = MeResponse(id(), "synthetic@example.invalid", "Synthetic", roles = listOf("owner"), companyId = id())
        var cache = CacheScopeLease(CacheScope(profile.userId, profile.companyId, null, null), 1)
        var session = SessionRefreshLease(StoredSession(token(profile), "synthetic-refresh"), 1)
        val manager = HandoverOperations(store, startup, { ready }, { session },
            { old -> session.accessToken.takeIf { old.session.lineage === session.session.lineage } }, { cache }, { profile }, { null })
        suspend fun start() { startup.load(); ready = PersistedStartupStateResult.Ready }
        fun capture() = requireNotNull(manager.captureOriginal(profile, cache))
    }
    private fun request(server: MockWebServer, path: String) = Request.Builder().url(server.url("/api/v1/$path"))
        .post("{}".toRequestBody()).build()
    @Test fun lostHeartbeatIsHandledAsRepeatableRestrictiveEffectButNeverSettlesLostOrdinaryCommand() = runBlocking {
        val f = Fixture(); f.start(); val a = f.capture()
        MockWebServer().use { server ->
            server.start(); repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
            val client = ApiClient.ephemeralAuthorityClient(f.session.accessToken, singleAttempt = true, operationGate = f.manager.dispatchGate)
            val calls = OperationTaggedCallFactory(client)
            val heartbeatClient = ApiClient.ephemeralAuthorityClient(f.session.accessToken, singleAttempt = true, operationGate = f.manager.dispatchGate)
            val heartbeatCalls = OperationTaggedCallFactory(heartbeatClient)
            try {
                assertTrue(runCatching { f.manager.run(a) {
                    calls.newCall(request(server, "remote-assistance/commands/${id()}/result")).execute().close()
                    OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME)
                } }.isFailure)
                val unknownCommand = f.manager.coordinator.state.value.unresolvedOperations
                assertEquals(1, unknownCommand.size)
                assertTrue(runCatching { f.manager.runRepeatableRemoteHeartbeat(a) {
                    heartbeatCalls.newCall(request(server, "remote-assistance/device/heartbeat")).execute().close()
                } }.isFailure)
                assertEquals(unknownCommand, f.manager.coordinator.state.value.unresolvedOperations)
                assertTrue(f.manager.coordinator.state.value.activeOperations.isEmpty())
                assertEquals(2, server.requestCount)
            } finally {
                client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
                heartbeatClient.dispatcher.executorService.shutdown(); heartbeatClient.connectionPool.evictAll()
            }
        }
    }
    @Test fun heartbeatCapabilityCannotEscalateToConsentCommandFrameBootstrapOrChildWork() = runBlocking {
        val f = Fixture(); f.start(); val a = f.capture()
        MockWebServer().use { server ->
            server.start(); repeat(6) { server.enqueue(MockResponse().setBody("{}")) }
            val client = ApiClient.ephemeralAuthorityClient(f.session.accessToken, singleAttempt = true, operationGate = f.manager.dispatchGate)
            val calls = OperationTaggedCallFactory(client)
            try {
                f.manager.runRepeatableRemoteHeartbeat(a) {
                    val permit = requireNotNull(OperationDispatchContext.captured()?.permit)
                    assertTrue(runCatching { f.manager.coordinator.child(permit) }.isFailure)
                    for (path in listOf("auth/login", "remote-assistance/grants/${id()}/decision",
                        "remote-assistance/commands/${id()}/result", "remote-assistance/sessions/${id()}/frames",
                        "remote-assistance/device/%68eartbeat", "remote-assistance/device/heartbeat?alias=1")) {
                        assertTrue(runCatching { calls.newCall(request(server, path)).execute().close() }.isFailure)
                    }
                }
                assertEquals(0, server.requestCount)
                assertTrue(f.manager.coordinator.state.value.unresolvedOperations.isEmpty())
                assertTrue(runCatching { f.manager.run(a) { OperationResult(Unit, OperationOutcome.REPEATABLE_RESTRICTIVE_EFFECT) } }.isFailure)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun staleOriginalCannotBorrowBAndInheritedOnlyProcessFactoryCanServeItsActualBContext() = runBlocking {
        val f = Fixture(); f.start(); val a = f.capture()
        f.profile = f.profile.copy(userId = id())
        f.cache = f.cache.copy(scope = f.cache.scope.copy(userId = f.profile.userId), generation = 2)
        f.session = SessionRefreshLease(StoredSession(token(f.profile), "synthetic-B-refresh"), 2)
        var staleEntered = false
        assertTrue(runCatching { f.manager.runRepeatableRemoteHeartbeat(a) { staleEntered = true } }.isFailure)
        assertFalse(staleEntered)
        val b = f.capture()
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}"))
            val client = ApiClient.ephemeralAuthorityClient(f.session.accessToken, singleAttempt = true, operationGate = f.manager.dispatchGate)
            // Matches the process-wide proof factory: no first-A read binding is cached.
            val processCalls = OperationTaggedCallFactory(client, boundRead = null)
            try {
                f.manager.runRepeatableRemoteHeartbeat(b) {
                    processCalls.newCall(request(server, "remote-assistance/device/heartbeat")).execute().close()
                }
                assertEquals(1, server.requestCount)
                assertEquals("Bearer ${f.session.accessToken}", server.takeRequest().getHeader("Authorization"))
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun cancelledHeartbeatRetainsFullAdmittedLifetimeUntilFinallyAndCannotClearStickyPriorEvidence() = runBlocking {
        val f = Fixture(); f.start(); val a = f.capture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val job = scope.launch { f.manager.runRepeatableRemoteHeartbeat(a) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { entered.complete(Unit); release.await() }
            } }
            entered.await(); job.cancel()
            assertEquals(1, f.manager.coordinator.state.value.activeOperations.size)
            release.complete(Unit); job.join()
            assertTrue(f.manager.coordinator.state.value.activeOperations.isEmpty())
        } finally { release.complete(Unit); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        val retainedAuthority = HandoverAuthority(HandoverScope(id(), id(), id(), id()), 1)
        f.store.value = HandoverIntent(HandoverAction(id(), retainedAuthority, id(), id(), 0), priorMutationUncertainty = true)
        val restarted = HandoverStartupFence(f.store); restarted.load()
        assertTrue(restarted.state.value is HandoverStartupStatus.RecoveryRequired)
        // No endpoint helper is permitted to erase a restored fence or claim its direct writes were settled.
        val restoredManager = HandoverOperations(f.store, restarted, { PersistedStartupStateResult.Ready }, { f.session },
            { old -> f.session.accessToken.takeIf { old.session.lineage === f.session.session.lineage } }, { f.cache }, { f.profile }, { null })
        var enteredAfterRestore = false
        assertTrue(runCatching { restoredManager.runRepeatableRemoteHeartbeat(a) { enteredAfterRestore = true } }.isFailure)
        assertFalse(enteredAfterRestore)
        assertTrue(requireNotNull(f.store.value).priorMutationUncertainty)
    }
    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun token(profile: MeResponse) = "synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"${profile.userId}","company_id":"${profile.companyId}","branch_id":null}""".toByteArray()) + ".synthetic"
    }
}
