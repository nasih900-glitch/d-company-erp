package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.*
import cloud.dcompany.erp.core.handover.*
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.InetAddress
import java.time.Instant
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

class RemotePendingRecoveryTest {
    private class Fixture(val actionKind: RemotePendingKind = RemotePendingKind.DECISION) : AutoCloseable {
        val company = "11111111-1111-4111-8111-111111111111"
        val installation = "22222222-2222-4222-8222-222222222222"
        val key = "33333333-3333-4333-8333-333333333333"
        val grant = "44444444-4444-4444-8444-444444444444"
        val actor = "55555555-5555-4555-8555-555555555555"
        val id = "66666666-6666-4666-8666-666666666666"
        val sessionId = "77777777-7777-4777-8777-777777777777"
        val owner = "88888888-8888-4888-8888-888888888888"
        val action = when (actionKind) {
            RemotePendingKind.DECISION -> RemotePendingAction.decision(PendingRemoteGrantDecision(grant, RemoteGrantDecisionWire.ACCEPTED, id))
            RemotePendingKind.REVOKE -> RemotePendingAction.revoke(PendingRemoteGrantRevocation(grant, id))
            RemotePendingKind.END -> RemotePendingAction.end(PendingRemoteSessionEnd(sessionId, id, "user_ended"))
        }
        val initial = PersistedRemoteAssistanceState(consent = if (actionKind == RemotePendingKind.REVOKE) "revoked" else "allowed",
            grantId = grant, grantKind = "one_time", grantExpiresAt = "2026-11-05T10:10:00Z",
            pendingDecision = if (actionKind == RemotePendingKind.DECISION) PendingRemoteGrantDecision(grant, RemoteGrantDecisionWire.ACCEPTED, id) else null,
            pendingRevocation = if (actionKind == RemotePendingKind.REVOKE) PendingRemoteGrantRevocation(grant, id) else null,
            pendingSessionEnd = if (actionKind == RemotePendingKind.END) PendingRemoteSessionEnd(sessionId, id, "user_ended") else null)
        var retained = initial
        var current = true
        var failCheckpoint = false
        var beforeCheckpoint: () -> Unit = {}
        var preflight: suspend () -> Unit = {}
        var checkpointExtra: (RemotePendingResolution) -> Unit = {}
        var uncertainPosts = 0
        val profile = MeResponse(actor, "synthetic@example.invalid", "Synthetic", listOf("owner"), companyId = company, branchId = null)
        val login = SessionRefreshLease(StoredSession("synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"sub\":\"$actor\",\"company_id\":\"$company\",\"branch_id\":null}".toByteArray()) + ".fixture", "synthetic"), 1)
        val cache = CacheScopeLease(CacheScope(actor, company, null, null), 1)
        private val store = object : HandoverIntentStore {
            override suspend fun read(): HandoverIntent? = null
            override suspend fun begin(intent: HandoverIntent): Unit = error("unused")
            override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent): Unit = error("unused")
            override suspend fun cancelNotSent(expected: HandoverIntent): Unit = error("unused")
        }
        private val startup = HandoverStartupFence(store)
        val manager = HandoverOperations(store, startup, { PersistedStartupStateResult.Ready }, { login.takeIf { current } },
            { old -> login.accessToken.takeIf { current && old.session.lineage === login.session.lineage } }, { cache }, { profile }, { null })
        lateinit var original: OriginalOperationBinding
        lateinit var identity: KnownJournalAttempt
        val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
        var signedKey = key
        val client = ApiClient.remoteProofClientBuilder(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-ERP-Device-Key-Id", signedKey).build())
        }, manager.dispatchGate, emptyList()).build()
        val calls = OperationTaggedCallFactory(client)
        fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build()
        val api = Retrofit.Builder().baseUrl(url("/api/v1/")).callFactory(calls)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build().create(RemoteAssistanceApi::class.java)
        val tag = RemoteRequestScopeTag(RemoteAssistanceJournalScope(company, installation, actor))
        suspend fun start() { startup.load(); original = requireNotNull(manager.captureOriginal(profile, cache)); identity = action.identity(original.authority.scope, installation) }
        fun grantStatus(status: String = "expired", decisionId: String? = null) = RemoteGrantStatusResponse(
            "2026-11-05T10:11:00Z", company, installation, grant, "one_time", status, actor,
            if (decisionId == null) null else actor, "2026-11-05T10:00:00Z", "2026-11-05T10:10:00Z",
            if (decisionId == null) null else "2026-11-05T10:01:00Z", decisionId, null, null, null)
        fun sessionStatus(endId: String? = null, reason: String? = null) = RemoteSessionStatusResponse(
            "2026-11-05T10:11:00Z", company, installation, sessionId, grant,
            if (endId == null) "expired" else "ended", actor, actor,
            "2026-11-05T10:00:00Z", "2026-11-05T10:10:00Z", null, null,
            if (endId == null) null else "2026-11-05T10:02:00Z", endId, reason)
        fun grantPost(status: String = "active") = RemoteGrantResponse(grant, installation, "one_time", status, owner,
            requestedForUserId = actor, respondedByUserId = actor, requestedAt = "2026-11-05T10:00:00Z",
            expiresAt = "2026-11-05T10:10:00Z", respondedAt = "2026-11-05T10:01:00Z",
            revokedAt = if (status == "revoked") "2026-11-05T10:02:00Z" else null)
        suspend fun post() = when (actionKind) {
            RemotePendingKind.DECISION -> api.decideGrant(grant, RemoteGrantDecisionRequest(installation, RemoteGrantDecisionWire.ACCEPTED, id), tag)
            RemotePendingKind.REVOKE -> api.revokeGrant(grant, RemoteGrantRevocationRequest(installation, id), tag)
            RemotePendingKind.END -> api.endSession(sessionId, RemoteSessionEndRequest(installation, id, "user_ended"), tag)
        }
        suspend fun lost(): String {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertTrue(runCatching { manager.runKnownJournalAttempt(original, identity, { post().also { check(it.isSuccessful) } }, { error("no lost reply checkpoint") }) }.isFailure)
            assertEquals(1, server.requestCount)
            assertEquals(initial, retained)
            return manager.coordinator.state.value.unresolvedOperations.single()
        }
        suspend fun recover(): RemotePendingResolution? = manager.run(original) {
            val expected = retained
            val recovery = RemotePendingRecovery(manager, api, original, tag, key,
                { current && retained == expected && action.matches(retained) }, checkpoint = { resolution ->
                beforeCheckpoint(); check(current && retained == expected)
                if (failCheckpoint) throw IOException("synthetic failed commit")
                checkpointExtra(resolution)
                retained = requireNotNull(resolvedRemotePendingState(expected, action, resolution))
            }, postUncertain = { uncertainPosts++ }, reassertPending = { preflight() })
            val resolved = recovery.reconcile(action)
            if (resolved == null) throw IOException("still unresolved")
            OperationResult(resolved, OperationOutcome.NO_WRITE_SENT)
        }
        override fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun canceledOriginalPostDiscardsFreshConsentBeforeAnAutomaticReplay() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = async(Dispatchers.IO) { f.recover() }
            withTimeout(5_000) { while (f.server.requestCount < 1) delay(10) }
            job.cancelAndJoin()
            assertEquals(1, f.uncertainPosts); assertEquals(1, f.server.requestCount)
            assertEquals(f.initial, f.retained)
            assertTrue(f.manager.coordinator.state.value.unresolvedOperations.isNotEmpty())
        }
    }
    @Test fun memoryFirstFailedSaveCannotSendUntilTheActualStoreReassertsDurably() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val prefs = MemoryFirstRemotePreferences()
            val scope = RemoteAssistanceJournalScope(f.company, f.installation, f.actor)
            val store = RemoteAssistanceStore(prefs.value, { scope }, { f.id })
            prefs.succeeds = false
            assertNull(store.recordDecision(f.grant, true, RemoteGrantKind.ONE_TIME, Instant.parse("2026-11-05T10:10:00Z")))
            assertNotNull(store.snapshot().pendingDecision); assertFalse(store.inspectHandoverWork().verified)
            f.preflight = { check(store.reassertPending(scope, store.pendingRecoverySnapshot()!!, f.action)) }
            f.checkpointExtra = { check(store.checkpointPending(scope, store.snapshot(), f.action, it)) }
            assertTrue(runCatching { f.recover() }.isFailure)
            assertEquals(0, f.server.requestCount)
            assertNull(store.recordDecision(f.grant, true, RemoteGrantKind.ONE_TIME, Instant.parse("2026-11-05T10:10:00Z")))
            prefs.succeeds = true
            assertEquals(f.id, store.recordDecision(f.grant, true, RemoteGrantKind.ONE_TIME, Instant.parse("2026-11-05T10:10:00Z"))?.decisionId)
            f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.grantPost())))
            assertEquals(RemoteResolutionKind.MATCHED_ACTION_ACK, f.recover()?.kind)
            assertEquals(1, f.server.requestCount); assertNull(store.snapshot().pendingDecision)
            assertEquals(prefs.memory, prefs.disk)
        }
    }
    @Test fun memoryFirstCheckpointFailureDoesNotRetireRegistryUnknownOrUseRamAckAsCleanProof() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val old = f.lost(); val prefs = MemoryFirstRemotePreferences()
            val scope = RemoteAssistanceJournalScope(f.company, f.installation, f.actor)
            val store = RemoteAssistanceStore(prefs.value, { scope }, { f.id })
            assertNotNull(store.recordDecision(f.grant, true, RemoteGrantKind.ONE_TIME, Instant.parse("2026-11-05T10:10:00Z")))
            f.preflight = { check(store.reassertPending(scope, store.pendingRecoverySnapshot()!!, f.action)) }
            var failOnce = true
            f.checkpointExtra = { resolution ->
                if (failOnce) { prefs.succeeds = false; failOnce = false }
                check(store.checkpointPending(scope, store.snapshot(), f.action, resolution))
            }
            f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.grantPost())))
            f.server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            assertTrue(runCatching { f.recover() }.isFailure)
            assertTrue(old in f.manager.coordinator.state.value.unresolvedOperations)
            assertNotNull(store.snapshot().pendingDecision); assertNull(store.snapshot().lastMutationResolution)
            assertFalse(store.inspectHandoverWork().verified)
            assertNotEquals(prefs.memory, prefs.disk)
            prefs.succeeds = true
            f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.grantPost())))
            f.recover()
            assertFalse(old in f.manager.coordinator.state.value.unresolvedOperations)
            assertNull(store.snapshot().pendingDecision); assertEquals(prefs.memory, prefs.disk)
        }
    }

    @Test fun stablePostReplayRetiresOnlyItsExactLostAttemptWithoutAStatusRead() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val prior = f.lost()
            f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.grantPost())))
            val resolved = f.recover()
            assertEquals(RemoteResolutionKind.MATCHED_ACTION_ACK, resolved?.kind)
            assertFalse(prior in f.manager.coordinator.state.value.unresolvedOperations)
            assertNull(f.retained.pendingDecision)
            assertEquals(2, f.server.requestCount)
            val first = f.server.takeRequest(); val retry = f.server.takeRequest()
            assertEquals("POST", first.method); assertEquals(first.path, retry.path)
            assertEquals(first.body.readUtf8(), retry.body.readUtf8())
        }
    }

    @Test fun neverSentExpiredActionRecoversAsSupersededThroughExactPositiveRead() = runBlocking<Unit> {
        for (kind in RemotePendingKind.entries) Fixture(kind).use { f ->
            f.start(); val prior = f.lost()
            f.server.enqueue(MockResponse().setResponseCode(410).setBody("{}"))
            f.server.enqueue(MockResponse().setBody(if (kind == RemotePendingKind.END) Json.encodeToString(f.sessionStatus()) else Json.encodeToString(f.grantStatus())))
            val resolved = f.recover()
            assertEquals(RemoteResolutionKind.TERMINAL_SUPERSEDED, resolved?.kind)
            assertFalse(prior in f.manager.coordinator.state.value.unresolvedOperations)
            assertEquals(resolved, f.retained.lastMutationResolution)
            assertEquals(3, f.server.requestCount)
            f.server.takeRequest(); assertEquals("POST", f.server.takeRequest().method)
            assertEquals("/api/v1/remote-assistance/device/${if (kind == RemotePendingKind.END) "sessions/${f.sessionId}" else "grants/${f.grant}"}/status?installation_id=${f.installation}", f.server.takeRequest().path)
            if (kind != RemotePendingKind.END) assertNotEquals("allowed", f.retained.consent)
        }
    }

    @Test fun absentErrorAndConsumedGrantDoNotSettleRevocationOrClearOriginalUnknown() = runBlocking<Unit> {
        for (response in listOf(MockResponse().setResponseCode(404).setBody("{}"), MockResponse().setResponseCode(410).setBody("{}"))) Fixture(RemotePendingKind.REVOKE).use { f ->
            f.start(); val prior = f.lost(); f.server.enqueue(MockResponse().setResponseCode(410).setBody("{}")); f.server.enqueue(response)
            assertTrue(runCatching { f.recover() }.isFailure)
            assertTrue(prior in f.manager.coordinator.state.value.unresolvedOperations); assertEquals(f.initial, f.retained)
        }
        Fixture(RemotePendingKind.REVOKE).use { f ->
            f.start(); val prior = f.lost(); f.server.enqueue(MockResponse().setResponseCode(410).setBody("{}"))
            val consumed = f.grantStatus("consumed", f.owner).copy(consumedAt = "2026-11-05T10:02:00Z")
            f.server.enqueue(MockResponse().setBody(Json.encodeToString(consumed)))
            assertTrue(runCatching { f.recover() }.isFailure)
            assertTrue(prior in f.manager.coordinator.state.value.unresolvedOperations); assertEquals(f.initial, f.retained)
        }
    }

    @Test fun classifierSeparatesMatchingHistoricalAckFromTerminalSupersessionAndConflictingBody() {
        Fixture().use { f ->
            val scope = HandoverScope(f.actor, f.company, null, null)
            assertEquals(RemoteResolutionKind.MATCHED_ACTION_ACK, classifyRemoteGrantStatus(f.action, scope, f.installation,
                f.grantStatus("expired", f.id))?.kind)
            assertNull(classifyRemoteGrantStatus(f.action, scope, f.installation, f.grantStatus("declined", f.id)))
            assertEquals(RemoteResolutionKind.TERMINAL_SUPERSEDED, classifyRemoteGrantStatus(f.action, scope, f.installation, f.grantStatus())?.kind)
            assertEquals(RemoteResolutionKind.TERMINAL_SUPERSEDED, classifyRemoteGrantStatus(f.action, scope, f.installation,
                f.grantStatus().copy(expiresAt = "2026-11-06T10:10:00Z"))?.kind) // restrictive early expiry, no response tuple
            assertFalse(RemotePendingResolution(f.action, RemoteResolutionKind.MATCHED_ACTION_ACK, "requested").valid())
            assertNull(classifyRemoteGrantStatus(f.action, scope, f.installation, f.grantStatus().copy(requestedForUserId = f.owner)))
            assertNull(classifyRemoteGrantStatus(f.action, scope, f.installation, f.grantStatus().copy(companyId = f.owner)))
            assertNull(classifyRemoteGrantStatus(f.action, scope, f.installation, f.grantStatus().copy(installationId = f.owner)))
            assertNull(classifyRemoteGrantStatus(f.action, scope, f.installation, f.grantStatus().copy(id = f.owner)))
            assertNull(classifyRemoteGrantStatus(f.action, scope, f.installation, f.grantStatus().copy(respondedAt = "2026-11-05T10:01:00Z")))
            assertNull(classifyRemoteGrantStatus(f.action, scope, f.installation, f.grantStatus().copy(serverTime = "2026-11-05T09:00:00Z")))
            val consumed = f.grantStatus("consumed", f.id).copy(consumedAt = "2026-11-05T10:02:00Z")
            assertEquals(RemoteResolutionKind.MATCHED_ACTION_ACK, classifyRemoteGrantStatus(f.action, scope, f.installation, consumed)?.kind)
        }
        Fixture(RemotePendingKind.END).use { f ->
            val scope = HandoverScope(f.actor, f.company, null, null)
            assertNull(classifyRemoteSessionStatus(f.action, scope, f.installation, f.sessionStatus(f.id, "owner_ended")))
            assertEquals(RemoteResolutionKind.TERMINAL_SUPERSEDED, classifyRemoteSessionStatus(f.action, scope, f.installation, f.sessionStatus(f.owner, "owner_ended"))?.kind)
            assertEquals(RemoteResolutionKind.MATCHED_ACTION_ACK, classifyRemoteSessionStatus(f.action, scope, f.installation, f.sessionStatus(f.id, "user_ended"))?.kind)
            assertNull(classifyRemoteSessionStatus(f.action, scope, f.installation, f.sessionStatus().copy(endedAt = "2026-11-05T10:02:00Z")))
        }
    }

    @Test fun checkpointFailureChangedPendingAndStaleAAlwaysRetainOriginalUnknown() = runBlocking<Unit> {
        for (failure in 0..2) Fixture().use { f ->
            f.start(); val old = f.lost(); f.server.enqueue(MockResponse().setResponseCode(410).setBody("{}"))
            f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.grantStatus())))
            when (failure) { 0 -> f.failCheckpoint = true; 1 -> f.beforeCheckpoint = { f.retained = f.retained.copy(pendingDecision = f.retained.pendingDecision?.copy(decisionId = f.owner)) }; 2 -> f.beforeCheckpoint = { f.current = false } }
            assertTrue(runCatching { f.recover() }.isFailure)
            assertTrue(old in f.manager.coordinator.state.value.unresolvedOperations)
            assertNotNull(f.retained.pendingDecision)
        }
    }

    @Test fun laterUnknownAndUnclassifiedDirectWorkSurviveExactObservationCheckpoint() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val old = f.lost()
            val direct = f.manager.coordinator.admit(f.original.authority)
            f.manager.coordinator.beforeMutationDispatch(direct, f.original.authority)
            f.manager.coordinator.finish(direct, OperationOutcome.UNRESOLVED)
            f.server.enqueue(MockResponse().setResponseCode(410).setBody("{}")); f.server.enqueue(MockResponse().setBody(Json.encodeToString(f.grantStatus())))
            var later: HandoverCoordinator.Permit? = null
            f.beforeCheckpoint = { runBlocking {
                later = f.manager.coordinator.admit(f.original.authority)
                f.manager.coordinator.bindKnownAttempt(requireNotNull(later), f.identity)
                f.manager.coordinator.beforeMutationDispatch(requireNotNull(later), f.original.authority)
                f.manager.coordinator.finish(requireNotNull(later), OperationOutcome.UNRESOLVED)
            } }
            f.recover()
            val remaining = f.manager.coordinator.state.value.unresolvedOperations
            assertFalse(old in remaining); assertTrue(direct.id in remaining); assertTrue(requireNotNull(later).id in remaining)
        }
    }

    @Test fun exactReadCapabilityRejectsAliasesBootstrapChildrenMutationsAndWrongKeyBeforeWire() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val observation = KnownRemoteStatusObservation(f.identity, f.installation, f.key)
            val exact = observation.encodedPath + "?installation_id=${f.installation}"
            for ((method, path) in listOf("GET" to exact.replace("grants", "%67rants"), "GET" to exact.replace("/status", "%2Fstatus"),
                "GET" to exact + "&extra=1", "GET" to exact + "&installation_id=${f.installation}", "POST" to "/api/v1/auth/login", "PUT" to "/api/v1/remote-assistance/device/sessions/${f.sessionId}/frame")) {
                assertTrue(runCatching { f.manager.observeKnownRemoteStatus(f.original, observation) {
                    val req = Request.Builder().url(f.url(path)).method(method, if (method == "GET") null else "{}".toRequestBody()).build()
                    f.calls.newCall(req).execute().close()
                } }.isFailure)
            }
            f.manager.observeKnownRemoteStatus(f.original, observation) { permit ->
                assertTrue(runCatching { f.manager.coordinator.child(permit) }.isFailure)
                assertTrue(runCatching { f.manager.coordinator.beforeMutationDispatch(permit, f.original.authority) }.isFailure)
            }
            f.signedKey = f.owner
            assertTrue(runCatching { f.manager.observeKnownRemoteStatus(f.original, observation) {
                f.api.grantStatus(f.grant, f.installation, f.tag)
            } }.isFailure)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun staleOriginalAndCanceledObservationCannotBorrowNewAuthorityOrLoseThePendingAction() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); val old = f.lost(); f.current = false
            assertTrue(runCatching { f.recover() }.isFailure)
            assertEquals(1, f.server.requestCount); assertEquals(f.initial, f.retained)
            assertTrue(old in f.manager.coordinator.state.value.unresolvedOperations)
        }
        Fixture().use { f ->
            f.start(); val old = f.lost(); f.server.enqueue(MockResponse().setResponseCode(410).setBody("{}"))
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = async(Dispatchers.IO) { f.recover() }
            withTimeout(5_000) { while (f.server.requestCount < 3) delay(10) }
            job.cancelAndJoin()
            assertEquals(f.initial, f.retained); assertTrue(old in f.manager.coordinator.state.value.unresolvedOperations)
            assertTrue(f.manager.coordinator.state.value.activeOperations.isEmpty())
        }
    }
}
