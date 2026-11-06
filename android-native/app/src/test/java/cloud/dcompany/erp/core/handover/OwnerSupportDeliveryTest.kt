package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import cloud.dcompany.erp.ui.screens.settings.ownerSupportDeliveryClient
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OwnerSupportDeliveryTest {
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { check(value == expected); value = next }
        override suspend fun cancelNotSent(expected: HandoverIntent) { error("Support cannot erase a till fence") }
    }
    private class Fixture {
        val owner = OutboxOwnerIdentity(id(), id(), null)
        val store = Store(); val startup = HandoverStartupFence(store)
        var ready: PersistedStartupStateResult? = null
        var session = SessionRefreshLease(StoredSession(token(owner), "synthetic-refresh"), 1)
        val original = session
        val manager = HandoverOperations(store, startup, { ready }, { session },
            { old -> session.accessToken.takeIf { old.session.lineage === session.session.lineage } }, { null }, { null }, { null })
        suspend fun start() { startup.load(); ready = PersistedStartupStateResult.Ready }
    }
    @Test fun noCacheDeliveryIsJoinedThroughOwnedCheckpointAndStructuredChildrenWhileExitClosesAdmission() = runBlocking {
        val f = Fixture(); f.start(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var checkpoint = 0
        val delivery = async { f.manager.runOwnerSupport(f.owner, f.original) { context ->
            launch { kotlinx.coroutines.withContext(NonCancellable) { entered.complete(Unit); release.await(); context.commitOwnedRow { checkpoint++ } } }
        } }
        entered.await()
        val cap = f.manager.coordinator.beginWorkspaceExit(null, WorkspaceExitKind.LOGOUT) { true }
        assertNull(cap.authority); assertFalse(f.manager.coordinator.state.value.initialized)
        assertFalse(f.manager.coordinator.awaitWorkspaceExitDrained(cap, 5))
        var secondBody = false
        assertTrue(runCatching { f.manager.runOwnerSupport(f.owner, f.original) { secondBody = true } }.isFailure)
        assertFalse(secondBody)
        release.complete(Unit); delivery.await()
        assertEquals(1, checkpoint)
        assertTrue(f.manager.coordinator.awaitWorkspaceExitDrained(cap, 100))
        assertTrue(f.manager.coordinator.state.value.activeOperations.isEmpty())
    }
    @Test fun cancellationDoesNotRemoveTheFinitePermitBeforeItsAdmittedStructuredChildSettles() = runBlocking {
        val f = Fixture(); f.start(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val delivery = scope.launch { f.manager.runOwnerSupport(f.owner, f.original) { context ->
                launch { kotlinx.coroutines.withContext(NonCancellable) { entered.complete(Unit); release.await(); context.commitOwnedRow { } } }
            } }
            entered.await(); delivery.cancel()
            val cap = f.manager.coordinator.beginWorkspaceExit(null, WorkspaceExitKind.LOGOUT) { true }
            assertFalse(f.manager.coordinator.awaitWorkspaceExitDrained(cap, 5))
            release.complete(Unit); delivery.join()
            assertTrue(f.manager.coordinator.awaitWorkspaceExitDrained(cap, 100))
            assertTrue(f.manager.coordinator.state.value.activeOperations.isEmpty())
        } finally { release.complete(Unit); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun actualPurposeClientUsesOnlyOriginalOwnerReportEndpointsAndNeverFinancialOrBootstrapAuthority() = runBlocking {
        val f = Fixture(); f.start()
        f.manager.runOwnerSupport(f.owner, f.original) { context ->
            assertTrue(runCatching { OwnerSupportDeliveryContext(context.permit, f.owner.copy(userId = id())) { true } }.isFailure)
            MockWebServer().use { server ->
                server.start(); repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
                val client = ownerSupportDeliveryClient(context, f.original.accessToken)
                try {
                    fun request(path: String, key: Boolean = true) = Request.Builder().url(server.url(path))
                        .apply { if (key) header("Idempotency-Key", id()) }.post("{}".toRequestBody()).build()
                    client.newCall(request("/api/v1/bug-reports")).execute().close()
                    val actual = server.takeRequest()
                    assertEquals("Bearer ${f.original.accessToken}", actual.getHeader("Authorization"))
                    assertNull(actual.getHeader("X-Terminal-Id"))
                    for (path in listOf("/api/v1/finance/expenses", "/api/v1/auth/login", "/api/v1/auth/refresh",
                        "/api/v1/bug-reports/mine", "/api/v1/bug%2Dreports", "/api/v1/bug-reports?other=1")) {
                        assertTrue(runCatching { client.newCall(request(path)).execute().close() }.isFailure)
                    }
                    assertTrue(runCatching { client.newCall(request("/api/v1/bug-reports", false)).execute().close() }.isFailure)
                    val generic = OperationTaggedCallFactory(client).newCall(request("/api/v1/auth/login")).request()
                    assertSame(context, generic.tag(OwnerSupportDeliveryContext::class.java))
                    assertTrue(runCatching { f.manager.dispatchGate.authorize(generic) }.isFailure)
                    assertTrue(runCatching { UnconfiguredOperationDispatchGate.authorize(generic) }.isFailure)
                    f.session = SessionRefreshLease(StoredSession(token(f.owner.copy(userId = id())), "synthetic-B"), 2)
                    assertTrue(runCatching { client.newCall(request("/api/v1/bug-reports")).execute().close() }.isFailure)
                    var wrote = false
                    assertTrue(runCatching { context.commitOwnedRow { wrote = true } }.isFailure)
                    assertFalse(wrote); assertEquals(1, server.requestCount)
                } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            }
        }
        assertTrue(f.manager.coordinator.state.value.activeOperations.isEmpty())
    }
    @Test fun physicalRequestMustMatchPermitOwnerEvenWhenAnUnrelatedBearerIsPassedToTheFactory() = runBlocking {
        val f = Fixture(); f.start()
        f.manager.runOwnerSupport(f.owner, f.original) { context ->
            MockWebServer().use { server ->
                server.start(); server.enqueue(MockResponse().setBody("{}"))
                val wrong = ownerSupportDeliveryClient(context, token(f.owner.copy(userId = id())))
                try {
                    val request = Request.Builder().url(server.url("/api/v1/bug-reports"))
                        .header("Idempotency-Key", id()).post("{}".toRequestBody()).build()
                    assertTrue(runCatching { wrong.newCall(request).execute().close() }.isFailure)
                    assertEquals(0, server.requestCount)
                } finally { wrong.dispatcher.executorService.shutdown(); wrong.connectionPool.evictAll() }
            }
        }
    }
    @Test fun startupUnknownRetainedFenceAndWrongFreshOwnerCannotEnterDelivery() = runBlocking {
        val f = Fixture(); var entered = false
        assertTrue(runCatching { f.manager.runOwnerSupport(f.owner, f.original) { entered = true } }.isFailure)
        f.start()
        assertTrue(runCatching { f.manager.runOwnerSupport(f.owner.copy(userId = id()), f.original) { entered = true } }.isFailure)
        f.store.value = HandoverIntent(HandoverAction(id(), HandoverAuthority(HandoverScope(id(), id(), id(), id()), 1), id(), id(), 0))
        assertTrue(runCatching { f.manager.runOwnerSupport(f.owner, f.original) { entered = true } }.isFailure)
        assertFalse(entered)
    }
    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun token(owner: OutboxOwnerIdentity) = "synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"${owner.userId}","company_id":"${owner.companyId}","branch_id":null}""".toByteArray()) + ".synthetic"
    }
}
