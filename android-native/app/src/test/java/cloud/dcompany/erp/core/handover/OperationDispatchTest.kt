package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OperationDispatchTest {
    private val scope = HandoverScope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString())
    private val authority = HandoverAuthority(scope, 1)
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { check(value == null); value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { check(value == expected); validateHandoverTransition(expected, next); value = next }
        override suspend fun cancelNotSent(expected: HandoverIntent) { check(value == expected && expected.stage == HandoverStage.NOT_SENT); value = null }
    }
    private fun binding(current: () -> Boolean = { true }) = OriginalOperationBinding(authority,
        CacheScopeLease(CacheScope(scope.actorId, scope.companyId, scope.branchId, scope.terminalId), 1),
        SessionRefreshLease(StoredSession("synthetic-A-access", "synthetic-A-refresh"), 1), current)
    private fun client(gate: OperationDispatchGate) = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .addInterceptor(OperationDispatchInterceptor(gate)).addNetworkInterceptor(OperationDispatchInterceptor(gate, physicalAttempt = true)).build()
    private fun close(client: OkHttpClient) { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    @Test fun unconfiguredColdDispatcherRejectsMoneyAndOwnerAuthorityMutationsBeforeNetwork() {
        MockWebServer().use { server ->
            server.start(); val client = client(UnconfiguredOperationDispatchGate)
            try {
                for (path in listOf("pos/orders", "admin/audit/unlock", "auth/register/confirm", "settings/password/change", "pricing/unlock")) {
                    val request = Request.Builder().url(server.url("/api/v1/$path")).post("{}".toRequestBody()).build()
                    assertThrows(java.io.IOException::class.java) { client.newCall(request).execute().close() }
                }
                assertEquals(0, server.requestCount)
            } finally { close(client) }
        }
    }
    @Test fun exactBootstrapDoesNotGrantOperationalReadinessOrPrefixExemptions() {
        val base = "http://127.0.0.1/"
        for ((method, path) in listOf("GET" to "auth/me", "POST" to "auth/login", "POST" to "auth/refresh", "GET" to "settings/terminals", "GET" to "public/client-compatibility")) {
            val request = Request.Builder().url(base + "api/v1/" + path).method(method, "{}".toRequestBody().takeUnless { method == "GET" }).build()
            UnconfiguredOperationDispatchGate.beforeAttempt(request)
        }
        assertFalse(isHandoverBootstrapRequest(Request.Builder().url(base + "api/v1/auth/registration/confirm").post("{}".toRequestBody()).build()))
        assertFalse(isHandoverBootstrapRequest(Request.Builder().url(base + "api/v1/settings/terminals").delete().build()))
        assertFalse(isHandoverBootstrapRequest(Request.Builder().url(base + "api/v1/settings/terminals/other").build()))
    }
    @Test fun contextIsCapturedAtCallCreationAndRetainedAcrossCoroutineThreadChange() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}"))
            val c = HandoverCoordinator(Store()); c.initialize(authority); val p = c.admit(authority)
            val original = binding(); val gate = CoordinatorOperationDispatchGate(c) { HandoverStartupStatus.Open }
            val client = client(gate)
            try {
                val factory = OperationTaggedCallFactory(client)
                val call = withContext(OperationDispatchContext(original, p).asCoroutineContext() + Dispatchers.Default) {
                    factory.newCall(Request.Builder().url(server.url("/api/v1/pos/orders")).header("Authorization", "Bearer synthetic-A-access").post("{}".toRequestBody()).build())
                }
                assertNull(OperationDispatchContext.captured())
                withContext(Dispatchers.IO) { call.execute().use { assertTrue(it.isSuccessful) } }
                assertEquals("Bearer synthetic-A-access", server.takeRequest().getHeader("Authorization"))
                c.finish(p, OperationOutcome.DURABLE_OUTCOME)
            } finally { close(client) }
        }
    }
    @Test fun queuedOldARequestCannotAcquireBPermitOrReachNetworkAfterBindingChanges() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val c = HandoverCoordinator(Store()); c.initialize(authority); val p = c.admit(authority)
            val current = AtomicBoolean(true); val original = binding { current.get() }
            val client = client(CoordinatorOperationDispatchGate(c) { HandoverStartupStatus.Open })
            try {
                val call = withContext(OperationDispatchContext(original, p).asCoroutineContext()) {
                    OperationTaggedCallFactory(client).newCall(Request.Builder().url(server.url("/api/v1/pos/orders")).post("{}".toRequestBody()).build())
                }
                current.set(false)
                assertTrue(withContext(Dispatchers.IO) { runCatching { call.execute().close() }.isFailure })
                assertEquals(0, server.requestCount)
                c.finish(p, OperationOutcome.NO_WRITE_SENT)
            } finally { close(client) }
        }
    }
    @Test fun admittedAContinuationCanFinishWhenFrozenButReadOnlyBindingCannotMutate() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(authority)
        val p = c.admit(authority); val original = binding()
        val action = HandoverAction(UUID.randomUUID().toString(), authority, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0)
        val i = c.freeze(authority, action)
        val gate = CoordinatorOperationDispatchGate(c) { HandoverStartupStatus.RecoveryRequired(i) }
        val request = Request.Builder().url("http://127.0.0.1/api/v1/pos/orders").post("{}".toRequestBody())
            .tag(OperationDispatchContext::class.java, OperationDispatchContext(original, p)).build()
        withContext(Dispatchers.IO) { gate.beforeAttempt(request) }
        assertThrows(java.io.IOException::class.java) { gate.beforeAttempt(request.newBuilder().tag(OperationDispatchContext::class.java, OperationDispatchContext(original, null)).build()) }
        assertThrows(java.io.IOException::class.java) { gate.beforeAttempt(request.newBuilder().get().tag(OperationDispatchContext::class.java, OperationDispatchContext(original, null)).build()) }
        c.finish(p, OperationOutcome.DURABLE_OUTCOME)
    }
    @Test fun boundReadsStillRequireLoadedStartupAndCurrentOriginalScope() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(authority)
        var status: HandoverStartupStatus = HandoverStartupStatus.Loading
        val gate = CoordinatorOperationDispatchGate(c) { status }
        val request = Request.Builder().url("http://127.0.0.1/api/v1/settings/company")
            .tag(OperationDispatchContext::class.java, OperationDispatchContext(binding(), null)).build()
        assertThrows(java.io.IOException::class.java) { gate.beforeAttempt(request) }
        status = HandoverStartupStatus.Open; gate.beforeAttempt(request)
        assertThrows(java.io.IOException::class.java) { gate.beforeAttempt(request.newBuilder().post("{}".toRequestBody()).build()) }
        Unit
    }
    @Test fun anonymousFactoryKeepsBothRecoveryRoutesAvailableWithoutBearerOrTerminal() {
        MockWebServer().use { server ->
            server.start(); val client = client(UnconfiguredOperationDispatchGate)
            try {
                val factory = AnonymousPasswordRecoveryCallFactory(client)
                for (endpoint in listOf("request", "confirm")) {
                    server.enqueue(MockResponse().setBody("{}"))
                    val request = Request.Builder().url(server.url("/api/v1/auth/password-reset/$endpoint"))
                        .header("Authorization", "Bearer must-not-be-sent").header("X-Terminal-Id", "must-not-be-sent")
                        .post("{}".toRequestBody()).build()
                    factory.newCall(request).execute().use { assertTrue(it.isSuccessful) }
                    val sent = server.takeRequest(); assertNull(sent.getHeader("Authorization")); assertNull(sent.getHeader("X-Terminal-Id"))
                }
                assertThrows(IllegalStateException::class.java) { factory.newCall(Request.Builder().url(server.url("/api/v1/pos/orders")).post("{}".toRequestBody()).build()) }
            } finally { close(client) }
        }
    }
    @Test fun authenticatedSettingsCannotBorrowAnonymousRecoveryExemptionWhenFrozenOrStale() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val c = HandoverCoordinator(Store()); c.initialize(authority)
            val current = AtomicBoolean(true); val original = binding { current.get() }
            val permit = c.admit(authority)
            val intent = c.freeze(authority, HandoverAction(UUID.randomUUID().toString(), authority, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0))
            val client = client(CoordinatorOperationDispatchGate(c) { HandoverStartupStatus.RecoveryRequired(intent) })
            try {
                val request = Request.Builder().url(server.url("/api/v1/auth/password-reset/confirm")).post("{}".toRequestBody()).build()
                val normal = OperationTaggedCallFactory(client, original)
                assertTrue(withContext(Dispatchers.IO) { runCatching { normal.newCall(request).execute().close() }.isFailure })
                val admitted = withContext(OperationDispatchContext(original, permit).asCoroutineContext()) { normal.newCall(request) }
                current.set(false)
                assertTrue(withContext(Dispatchers.IO) { runCatching { admitted.execute().close() }.isFailure })
                assertEquals(0, server.requestCount)
            } finally { close(client) }
        }
    }
    @Test fun authenticatedRecoveryMutationNeedsAndMarksItsActualAdmittedPermit() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(authority)
        val p = c.admit(authority); val original = binding(); val progress = OperationProgress()
        val request = Request.Builder().url("http://127.0.0.1/api/v1/auth/password-reset/request").post("{}".toRequestBody())
            .tag(OperationDispatchContext::class.java, OperationDispatchContext(original, p, progress)).build()
        withContext(Dispatchers.IO) { CoordinatorOperationDispatchGate(c) { HandoverStartupStatus.Open }.beforeAttempt(request) }
        assertEquals(OperationOutcome.UNRESOLVED, progress.outcome())
        assertTrue(runCatching { c.finish(p, OperationOutcome.NO_WRITE_SENT) }.isFailure)
        c.finish(p, OperationOutcome.DURABLE_OUTCOME)
        assertThrows(java.io.IOException::class.java) { UnconfiguredOperationDispatchGate.authorize(request.newBuilder().tag(OperationDispatchContext::class.java, null).build()) }
        Unit
    }
}
