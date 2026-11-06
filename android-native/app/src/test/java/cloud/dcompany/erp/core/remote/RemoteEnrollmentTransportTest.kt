package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.*
import cloud.dcompany.erp.core.handover.*
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.ApiClient
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

class RemoteEnrollmentTransportTest {
    private class Fixture : AutoCloseable {
        val company = "11111111-1111-4111-8111-111111111111"
        val installation = "22222222-2222-4222-8222-222222222222"
        val key = "33333333-3333-4333-8333-333333333333"
        val enrollment = "44444444-4444-4444-8444-444444444444"
        val actor = "55555555-5555-4555-8555-555555555555"
        val stored = PersistedRemoteDeviceIdentity(company, installation, key, enrollment, "created", "a".repeat(64))
        val profile = MeResponse(actor, "synthetic@example.invalid", "Synthetic", listOf("owner"), companyId = company, branchId = null)
        val session = SessionRefreshLease(StoredSession("synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"sub\":\"$actor\",\"company_id\":\"$company\",\"branch_id\":null}".toByteArray()) + ".fixture", "synthetic-refresh"), 1)
        val cache = CacheScopeLease(CacheScope(actor, company, null, null), 1)
        var current = true
        private val store = object : HandoverIntentStore {
            override suspend fun read(): HandoverIntent? = null
            override suspend fun begin(intent: HandoverIntent): Unit = error("unused")
            override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent): Unit = error("unused")
            override suspend fun cancelNotSent(expected: HandoverIntent): Unit = error("unused")
        }
        private val startup = HandoverStartupFence(store)
        val manager = HandoverOperations(store, startup, { PersistedStartupStateResult.Ready }, { session.takeIf { current } },
            { old -> session.accessToken.takeIf { current && old.session.lineage === session.session.lineage } }, { cache }, { profile }, { null })
        lateinit var original: OriginalOperationBinding
        lateinit var identity: KnownJournalAttempt
        lateinit var observation: KnownRemoteKeyObservation
        val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
        val proofCalls = AtomicInteger()
        val physicalCalls = AtomicInteger()
        private val countedGate = object : OperationDispatchGate {
            override fun authorize(request: Request) = manager.dispatchGate.authorize(request)
            override fun beforeAttempt(request: Request) {
                check(request.header("X-Synthetic-Device-Proof") == "present")
                physicalCalls.incrementAndGet()
                manager.dispatchGate.beforeAttempt(request)
            }
        }
        val client = ApiClient.remoteProofClientBuilder(Interceptor { chain ->
            val original = requireNotNull(chain.request().tag(OperationDispatchContext::class.java))
            check(original.binding.isCurrent()); proofCalls.incrementAndGet()
            chain.proceed(chain.request().newBuilder().header("X-Synthetic-Device-Proof", "present").build())
        }, countedGate, authentication = emptyList()).build()
        fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build()
        val api: RemoteAssistanceApi = Retrofit.Builder().baseUrl(url("/api/v1/"))
            .callFactory(OperationTaggedCallFactory(client)).addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
            .build().create(RemoteAssistanceApi::class.java)
        val tag = RemoteRequestScopeTag(RemoteAssistanceJournalScope(company, installation, actor))
        var retained = stored
        suspend fun start() { startup.load(); original = requireNotNull(manager.captureOriginal(profile, cache)); identity = remoteEnrollmentAttempt(original.authority.scope, stored); observation = KnownRemoteKeyObservation(identity, key, installation) }
        fun active() = RemoteDeviceKeyStatusResponse("2026-11-05T10:02:00Z", key, installation, "active", "a".repeat(64),
            "2026-11-05T10:00:00Z", "2026-11-05T10:10:00Z", "2026-11-05T10:01:00Z")
        suspend fun lostEnrollment(): String {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertTrue(runCatching { manager.runKnownJournalAttempt(original, identity, {
                api.enrollDeviceKey(RemoteDeviceKeyEnrollmentRequest(key, enrollment, installation, "synthetic-public-spki", 1, "synthetic-nonce", "synthetic-proof"), tag)
            }, { error("lost response must not checkpoint") }) }.isFailure)
            assertEquals("Lost-reply proof requires actual server receipt", 1, server.requestCount)
            assertEquals(1, physicalCalls.get()); assertEquals(1, proofCalls.get())
            return manager.coordinator.state.value.unresolvedOperations.single()
        }
        suspend fun observe(beforeCheckpoint: suspend () -> Unit = {}) = manager.observeKnownRemoteKey(original, observation) { permit ->
            val value = api.deviceKeyStatus(key, installation, tag)
            val positive = validatedRemoteDeviceStatus(stored, value, activeOnly = true)
            if (positive != null) manager.coordinator.checkpointKnownAttempt(permit, identity, original.isCurrent) {
                beforeCheckpoint(); check(retained == stored) { "exact owned candidate changed" }; retained = positive
            }
            value
        }
        suspend fun recoverCreated(): CreatedEnrollmentNextStep = manager.run(original) {
            val step = createdEnrollmentNextStep(manager.coordinator.hasFinishedKnownAttempt(original.authority, identity), {
                val response = runCatching { observe() }.getOrNull()
                if (response != null && response.status != "active") {
                    validatedRemoteDeviceStatus(stored, response)?.let { check(retained == stored); retained = it }
                }
                retained.status == "active"
            }, { current && retained == stored })
            if (step == CreatedEnrollmentNextStep.POST_SAME_INTENT) {
                manager.runKnownJournalAttempt(original, identity, {
                    val response = api.enrollDeviceKey(RemoteDeviceKeyEnrollmentRequest(key, enrollment, installation, "synthetic-public-spki", 2, "synthetic-fresh-nonce", "synthetic-fresh-proof"), tag)
                    requireNotNull(validatedRemoteDeviceStatus(stored, response))
                }, { value -> check(retained == stored); retained = value })
            }
            OperationResult(step, OperationOutcome.DURABLE_OUTCOME)
        }
        override fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }
    @Test fun sharedProductionBuilderHasExactlyOnePhysicalBoundaryAndNoColdReadyDefault() {
        Fixture().use { f ->
            assertEquals(1, f.client.networkInterceptors.count { it is OperationDispatchInterceptor })
            assertFalse(f.client.retryOnConnectionFailure); assertFalse(f.client.followRedirects); assertFalse(f.client.followSslRedirects)
            val cold = ApiClient.remoteProofClientBuilder(Interceptor { it.proceed(it.request()) }, UnconfiguredOperationDispatchGate, emptyList()).build()
            try {
                assertThrows(IOException::class.java) { cold.newCall(Request.Builder().url(f.url("/api/v1/remote-assistance/device/keys/enroll")).post("{}".toRequestBody()).build()).execute().close() }
                assertEquals(0, f.server.requestCount)
            } finally { cold.dispatcher.executorService.shutdown(); cold.connectionPool.evictAll() }
        }
    }

    @Test fun actualSelectionUsesOnePostInitiallyAndGetRecoveryOnlyForItsExactRetainedUnknown() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.active())))
            assertEquals(CreatedEnrollmentNextStep.POST_SAME_INTENT, f.recoverCreated())
            assertEquals(1, f.server.requestCount); assertEquals("POST", f.server.takeRequest().method)
        }
        Fixture().use { f ->
            f.start(); f.lostEnrollment(); f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.active())))
            assertEquals(CreatedEnrollmentNextStep.ACTIVE_CHECKPOINTED, f.recoverCreated())
            assertEquals(2, f.server.requestCount); assertEquals("POST", f.server.takeRequest().method); assertEquals("GET", f.server.takeRequest().method)
            assertTrue(f.manager.coordinator.state.value.unresolvedOperations.isEmpty())
        }
    }
    @Test fun get404NeverClearsUnknownAndSelectsSameEnrollmentPostWithFreshProof() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val old = f.lostEnrollment(); f.server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.active())))
            assertEquals(CreatedEnrollmentNextStep.POST_SAME_INTENT, f.recoverCreated())
            val first = f.server.takeRequest(); assertEquals("GET", f.server.takeRequest().method); val retry = f.server.takeRequest()
            assertTrue(first.body.readUtf8().contains(f.enrollment)); val retryBody = retry.body.readUtf8()
            assertTrue(retryBody.contains(f.enrollment)); assertTrue(retryBody.contains("synthetic-fresh-nonce"))
            assertEquals(3, f.server.requestCount); assertFalse(old in f.manager.coordinator.state.value.unresolvedOperations)
        }
    }
    @Test fun getPendingRetainsUnknownAndDoesNotPostOverChangedExpectedCandidate() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val old = f.lostEnrollment(); f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.active().copy(
                status = "pending", approvedAt = null, pairingCode = "01AB2CDE3FGH"))))
            assertEquals(CreatedEnrollmentNextStep.HELD, f.recoverCreated())
            assertEquals("pending", f.retained.status); assertEquals(2, f.server.requestCount)
            assertEquals(setOf(old), f.manager.coordinator.state.value.unresolvedOperations)
        }
    }

    @Test fun actualLostEnrollmentThenExactActiveGetRetiresOnlyMatchingFinishedUnknown() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val old = f.lostEnrollment()
            f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.active())))
            f.observe { assertTrue(old in f.manager.coordinator.state.value.unresolvedOperations) }
            assertEquals("active", f.retained.status); assertTrue(f.manager.coordinator.state.value.unresolvedOperations.isEmpty())
            assertEquals(2, f.physicalCalls.get()); assertEquals(2, f.proofCalls.get())
            assertEquals("POST", f.server.takeRequest().method)
            val read = f.server.takeRequest(); assertEquals("GET", read.method)
            assertEquals("/api/v1/remote-assistance/device/keys/${f.key}/status?installation_id=${f.installation}", read.path)
        }
    }
    @Test fun realGateRejectsEveryOtherRouteQueryMutationAliasAndBootstrapBeforeNetwork() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start()
            f.manager.observeKnownRemoteKey(f.original, f.observation) { permit ->
                val path = f.observation.encodedPath; val query = "installation_id=${f.installation}"
                for ((method, target) in listOf("GET" to "/api/v1/auth/me", "POST" to "/api/v1/auth/login", "GET" to "/api/v1/remote-assistance/device/state?$query",
                    "GET" to "$path?installation_id=${f.enrollment}", "GET" to "$path?$query&$query", "GET" to "$path?$query&other=1",
                    "GET" to path.replace("/keys/", "/%6beys/") + "?$query", "GET" to path.replace("/device/", "/device%2F") + "?$query",
                    "GET" to path.replace(f.key, f.enrollment) + "?$query", "POST" to "$path?$query", "PUT" to "/api/v1/remote-assistance/device/sessions/${f.key}/frame",
                    "POST" to "/api/v1/remote-assistance/device/sessions/${f.key}/end")) {
                    val request = Request.Builder().url(f.url(target)).method(method, if (method == "GET") null else "{}".toRequestBody()).build()
                    val call = OperationTaggedCallFactory(f.client).newCall(request)
                    assertTrue(withContext(Dispatchers.IO) { runCatching { call.execute().close() }.isFailure })
                }
                assertTrue(runCatching { f.manager.coordinator.child(permit) }.isFailure)
                assertTrue(runCatching { f.manager.coordinator.beforeMutationDispatch(permit, f.original.authority) }.isFailure)
                assertTrue(runCatching { f.manager.coordinator.checkpointKnownAttempt(permit, f.identity, { true }) { error("GET never started") } }.isFailure)
            }
            assertEquals(0, f.server.requestCount); assertEquals(0, f.proofCalls.get()); assertEquals(0, f.physicalCalls.get())
        }
    }
    @Test fun wrongObjectNonActiveInvalidTimeOrHttpFailureCannotCheckpointOrRetire() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val old = f.lostEnrollment()
            for (response in listOf(f.active().copy(keyId = f.enrollment), f.active().copy(installationId = f.enrollment),
                f.active().copy(fingerprintSha256 = "b".repeat(64)), f.active().copy(approvedAt = "bad"),
                f.active().copy(status = "expired"), f.active().copy(status = "revoked", revokedAt = "2026-11-05T10:01:30Z"))) {
                f.server.enqueue(MockResponse().setBody(Json.encodeToString(response))); f.observe()
                assertEquals(f.stored, f.retained); assertEquals(setOf(old), f.manager.coordinator.state.value.unresolvedOperations)
            }
            for (status in listOf(401,403,404,204)) {
                f.server.enqueue(MockResponse().setResponseCode(status).setBody("{}")); assertTrue(runCatching { f.observe() }.isFailure)
                assertEquals(setOf(old), f.manager.coordinator.state.value.unresolvedOperations)
            }
        }
    }
    @Test fun changedCandidateFailedCheckpointAndStaleOriginalCannotRetireUnknown() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val old = f.lostEnrollment(); f.retained = f.stored.copy(enrollmentId = f.actor)
            f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.active())))
            assertTrue(runCatching { f.observe() }.isFailure); assertEquals(2, f.server.requestCount); assertEquals(setOf(old), f.manager.coordinator.state.value.unresolvedOperations)
            f.retained = f.stored; f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.active())))
            assertTrue(runCatching { f.observe { throw IOException("fsync refused") } }.isFailure)
            assertEquals(3, f.server.requestCount)
            assertEquals(setOf(old), f.manager.coordinator.state.value.unresolvedOperations)
            f.current = false; val requests = f.server.requestCount
            assertTrue(runCatching { f.observe() }.isFailure); assertEquals(requests, f.server.requestCount)
        }
    }
    @Test fun cancellationWaitsOnlyForOwnedCheckpointAndDoesNotInventNewUnknown() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.lostEnrollment(); f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.active())))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val job = launch { f.observe { entered.complete(Unit); release.await() } }
            entered.await(); job.cancel(); assertFalse(job.isCompleted); release.complete(Unit); job.join()
            assertEquals("active", f.retained.status); assertTrue(f.manager.coordinator.state.value.unresolvedOperations.isEmpty())
            assertTrue(f.manager.coordinator.state.value.activeOperations.isEmpty())
        }
    }
}
