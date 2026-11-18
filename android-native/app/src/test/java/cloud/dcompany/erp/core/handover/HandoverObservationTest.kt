package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HandoverObservationTest {
    private fun id() = UUID.randomUUID().toString()
    private val authority = HandoverAuthority(HandoverScope(id(), id(), id(), id()), 1)
    private fun intent(uncertain: Boolean = false) = HandoverIntent(
        HandoverAction(id(), authority, id(), id(), 0), priorMutationUncertainty = uncertain)
    private class Store(var value: HandoverIntent?) : HandoverIntentStore {
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { check(value == null); value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { check(value == expected); value = next }
        override suspend fun cancelNotSent(expected: HandoverIntent) { check(value == expected); value = null }
    }
    private fun binding(current: () -> Boolean = { true }) = OriginalOperationBinding(authority,
        CacheScopeLease(CacheScope(authority.scope.actorId, authority.scope.companyId, authority.scope.branchId, authority.scope.terminalId), 1),
        SessionRefreshLease(StoredSession("synthetic-access", "synthetic-refresh"), 1), current)

    @Test fun exactTwoReadPathsUseRealGateWithoutHoldingCoordinatorMutex() = runBlocking {
        val expected = intent(); val c = HandoverCoordinator(Store(expected)); c.initialize(authority)
        MockWebServer().use { server ->
            server.start(); repeat(2) { server.enqueue(MockResponse().setBody("{}")) }
            val gate = CoordinatorOperationDispatchGate(c) { HandoverStartupStatus.RecoveryRequired(expected) }
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .addInterceptor(OperationDispatchInterceptor(gate)).addNetworkInterceptor(OperationDispatchInterceptor(gate, true)).build()
            try {
                withTimeout(3_000) {
                    assertTrue(c.reconcilePriorQuiescenceObserved(authority, expected, { true }) { _, permit ->
                        // This acquires the mutex and must refuse, rather than deadlocking inside the callback.
                        assertTrue(runCatching { c.admit(authority) }.isFailure)
                        withContext(OperationDispatchContext(binding(), permit)) {
                            val factory = OperationTaggedCallFactory(client)
                            for (path in listOf("auth/me", "pos/shifts/${expected.action.shiftId}/operator-state")) {
                                factory.newCall(Request.Builder().url(server.url("/api/v1/$path")).build()).execute().use { assertEquals(200, it.code) }
                            }
                            for ((method, path) in listOf("GET" to "pos/shifts/${id()}/operator-state", "GET" to "pos/orders",
                                "POST" to "auth/login", "POST" to "auth/refresh", "POST" to "pos/orders",
                                "DELETE" to "pos/orders/${id()}/checkout-claim")) {
                                val request = Request.Builder().url(server.url("/api/v1/$path"))
                                    .method(method, if (method == "POST") "{}".toRequestBody() else null).build()
                                assertTrue(runCatching { factory.newCall(request).execute().close() }.exceptionOrNull() is IOException)
                            }
                        }
                        assertTrue(runCatching { c.child(permit) }.isFailure)
                        assertTrue(runCatching { c.beforeMutationDispatch(permit, authority) }.isFailure)
                        PriorQuiescenceResult.VERIFIED
                    })
                }
                assertEquals(2, server.requestCount)
                assertTrue(c.state.value.activeOperations.isEmpty())
                assertFalse(c.state.value.priorQuiescenceUnverified)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun failedOrCancelledReadsRetireOnlyObservationAndKeepPriorBarrier() = runBlocking {
        for (failure in listOf(IOException("Synthetic read failed"), CancellationException("Synthetic cancelled"))) {
            val expected = intent(); val store = Store(expected); val c = HandoverCoordinator(store); c.initialize(authority)
            val error = runCatching {
                c.reconcilePriorQuiescenceObserved(authority, expected, { true }) { _, _ -> throw failure }
            }.exceptionOrNull()
            assertSame(failure, error)
            assertTrue(c.state.value.activeOperations.isEmpty())
            assertTrue(c.state.value.priorQuiescenceUnverified)
            assertEquals(expected, store.value)
            assertTrue(runCatching { c.markSentUnknown(authority, expected.action.key) }.isFailure)
        }
    }
    @Test fun staleAuthorityStorageAndConcurrentLifecycleCannotBecomeVerified() = runBlocking {
        for (drift in listOf("authority", "storage", "lifecycle")) {
            val expected = intent(); val store = Store(expected); val c = HandoverCoordinator(store); c.initialize(authority)
            var current = true
            val failed = runCatching {
                c.reconcilePriorQuiescenceObserved(authority, expected, { current }) { _, _ ->
                    when (drift) {
                        "authority" -> current = false
                        "storage" -> store.value = expected.copy(action = expected.action.copy(incomingUserId = id()))
                        else -> {
                            val other = c.admitCleanup(authority, OperationPurpose.RELEASE_OWN_CHECKOUT, id())
                            c.finish(other, OperationOutcome.NO_WRITE_SENT)
                        }
                    }
                    PriorQuiescenceResult.VERIFIED
                }
            }.isFailure
            assertTrue(drift, failed)
            assertTrue(c.state.value.activeOperations.isEmpty())
            assertTrue(c.state.value.priorQuiescenceUnverified)
        }
    }
    @Test fun cleanReadObservationsNeverClearRestoredMutationUncertainty() = runBlocking {
        val expected = intent(uncertain = true); val store = Store(expected); val c = HandoverCoordinator(store); c.initialize(authority)
        assertTrue(c.reconcilePriorQuiescenceObserved(authority, expected, { true }) { _, _ -> PriorQuiescenceResult.VERIFIED })
        assertEquals(expected, store.value)
        assertTrue(c.state.value.mutationUncertaintySticky)
        assertTrue(c.awaitDrained(expected.action.key, 1) is HandoverDrainResult.Blocked)
        assertTrue(runCatching { c.markSentUnknown(authority, expected.action.key) }.isFailure)
    }
    @Test fun blockedDomainResultKeepsRestoredFenceAndDisallowsDispatch() = runBlocking {
        val expected = intent(); val c = HandoverCoordinator(Store(expected)); c.initialize(authority)
        assertFalse(c.reconcilePriorQuiescenceObserved(authority, expected, { true }) { _, _ -> PriorQuiescenceResult.BLOCKED })
        assertTrue(c.state.value.activeOperations.isEmpty())
        assertTrue(c.state.value.priorQuiescenceUnverified)
        assertTrue(runCatching { c.markSentUnknown(authority, expected.action.key) }.isFailure)
    }
}
