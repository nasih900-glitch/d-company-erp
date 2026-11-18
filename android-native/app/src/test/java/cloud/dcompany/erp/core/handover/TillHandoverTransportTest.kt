package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import java.io.IOException
import java.net.InetAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class TillHandoverTransportTest {
    private class Store : HandoverIntentStore {
        @Volatile var value: HandoverIntent? = null
        var failNextStage: HandoverStage? = null
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { check(value == null); value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) {
            check(value == expected)
            if (next.stage == failNextStage) throw IOException("Test storage refused the transition")
            value = next
        }
        override suspend fun cancelNotSent(expected: HandoverIntent) { check(value == expected); value = null }
        override suspend fun completeAccepted(expected: HandoverIntent) {
            check(value == expected && expected.stage == HandoverStage.ACCEPTED)
            value = null
        }
    }

    private class Fixture : AutoCloseable {
        val store = Store()
        val startup = HandoverStartupFence(store)
        @Volatile var profile = MeResponse(id(), "staff-a@example.invalid", "Staff A", listOf("owner"),
            companyId = id(), branchId = id(), effectivePermissions = listOf("pos.write"))
        val terminal = ValidatedTerminalDisplay(id(), "Till", requireNotNull(profile.branchId), "hybrid")
        var cache = CacheScopeLease(CacheScope(profile.userId, profile.companyId, profile.branchId, terminal.terminalId), 1)
        var session = SessionRefreshLease(StoredSession(token(profile), "test-refresh"), 1)
        var operations = manager()
        lateinit var original: OriginalOperationBinding
        lateinit var action: HandoverAction
        var localWork = LocalHandoverWork(verified = true, emptySet())
        var workersClear = true
        val clients = mutableListOf<OkHttpClient>()
        val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
        val base get() = server.url("/api/v1/").newBuilder().host("127.0.0.1").build()
        val shiftPath get() = "pos/shifts/${action.shiftId}"
        val sendPath get() = "$shiftPath/operator-handovers"
        val receiptPath get() = "$shiftPath/operator-handover-receipt?action_key=${action.key}"
        fun manager() = HandoverOperations(store, startup, { PersistedStartupStateResult.Ready }, { session },
            { login -> session.accessToken.takeIf { login.session.lineage === session.session.lineage } },
            { cache }, { profile }, { terminal })

        suspend fun start() {
            startup.load()
            original = requireNotNull(operations.captureOriginal(profile, cache))
            action = HandoverAction(id(), original.authority, id(), id(), 0)
        }

        suspend fun restore(expected: HandoverIntent): OriginalOperationBinding {
            session = SessionRefreshLease(StoredSession(token(profile), "fresh-test-login"), 2)
            cache = cache.copy(generation = 2)
            startup.load()
            operations = manager()
            assertNull(operations.captureOriginal(profile, cache))
            return requireNotNull(operations.captureForSentHandover(profile, cache, expected))
        }

        val domainApi = object : HandoverStateApi {
            override suspend fun me() = profile
            override suspend fun state(shiftId: String) =
                OriginalOperatorState(shiftId, "open", null, 0, "untracked", emptyList())
            override suspend fun release(orderId: String, token: String) = error("No known idle claim in this fixture")
        }

        val transport get() = TillHandoverTransport(operations,
            OriginalHandoverDomains(operations, IdleCheckoutClaims(), { localWork },
                { domainApi }, { _, _ -> workersClear })) { binding, bearer ->
            buildTillHandoverApi(client(binding, bearer), base.toString(), boundRead = binding)
        }

        fun client(binding: OriginalOperationBinding = original, bearer: String = session.accessToken): OkHttpClient =
            ApiClient.ephemeralAuthorityClient(bearer, terminal.terminalId, beforeDispatch = {
                if (!binding.isCurrent() || session.accessToken != bearer) throw IOException("Test original login changed")
            }, singleAttempt = true, operationGate = operations.dispatchGate).also { clients += it }

        fun receipt() = TillHandoverReceipt(id(), action.shiftId, requireNotNull(action.origin.scope.branchId),
            terminal.terminalId, action.origin.scope.actorId, null, action.incomingUserId, 0, 1, "NOT_COUNTED",
            null, "handover", null, null, "2026-11-05T12:00:00Z")

        fun me() = server.enqueue(json(profile))
        fun request() = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        fun wireRequest(path: String, method: String = "GET", key: String? = null) = Request.Builder()
            .url(base.resolve(path) ?: error("Invalid fixture URL"))
            .apply {
                if (method == "POST") post("{}".toRequestBody())
                if (key != null) header("Idempotency-Key", key)
            }.build()

        override fun close() {
            clients.forEach { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
            server.close()
        }
    }

    @Test fun submitPersistsUnknownBeforePhysicalPostAndStoresOnlyVerifiedReceipt() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start()
            val receipt = f.receipt()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/auth/me" -> json(f.profile)
                    "/api/v1/${f.sendPath}" -> if (f.store.value?.stage == HandoverStage.SENT_UNKNOWN) json(receipt, 201)
                        else MockResponse().setResponseCode(500)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val accepted = f.transport.submit(f.original, f.action, "test-incoming-password", approved = true)
            assertEquals(HandoverStage.ACCEPTED, accepted.stage)
            assertEquals(receipt.verify(f.action), accepted.receipt)
            assertEquals(accepted, f.store.value)
            assertEquals("/api/v1/auth/me", f.request().path)
            val sent = f.request()
            assertEquals("POST", sent.method)
            assertEquals("/api/v1/${f.sendPath}", sent.path)
            assertEquals("Bearer ${f.session.accessToken}", sent.getHeader("Authorization"))
            assertEquals(f.terminal.terminalId, sent.getHeader("X-Terminal-Id"))
            assertEquals(f.action.key, sent.getHeader("Idempotency-Key"))
            val body = ApiClient.json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals(setOf("incoming_user_id", "expected_revision", "approved", "password"), body.keys)
            assertEquals(f.action.incomingUserId, body.getValue("incoming_user_id").jsonPrimitive.content)
            assertEquals("0", body.getValue("expected_revision").jsonPrimitive.content)
            assertEquals("true", body.getValue("approved").jsonPrimitive.content)
            assertFalse(HandoverJson.encodeToString(accepted).contains("test-incoming-password"))
            assertEquals(2, f.server.requestCount)
            assertTrue(f.operations.coordinator.state.value.activeOperations.isEmpty())
            assertTrue(f.operations.status() is HandoverStartupStatus.RecoveryRequired)
        }
    }

    @Test fun lostPhysicalPostRestartsFencedAndOnlyExactReceiptSettlesUnknown() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.me()
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertTrue(runCatching { f.transport.submit(f.original, f.action, "pw", true) }.exceptionOrNull() is IOException)
            assertEquals("/api/v1/auth/me", f.request().path)
            val sent = f.request()
            assertEquals("POST", sent.method)
            assertEquals("/api/v1/${f.sendPath}", sent.path)
            assertEquals(2, f.server.requestCount)
            val unknown = requireNotNull(f.store.value)
            assertEquals(HandoverStage.SENT_UNKNOWN, unknown.stage)
            val original = f.restore(unknown)
            assertFalse(f.original.isCurrent())
            val recovery = f.transport
            for (code in listOf(404, 403)) {
                f.me(); f.server.enqueue(MockResponse().setResponseCode(code))
                assertTrue(runCatching { recovery.receipt(original, unknown) }.isFailure)
                assertEquals(unknown, f.store.value)
                assertTrue(f.operations.coordinator.state.value.activeOperations.isEmpty())
            }
            f.me(); f.server.enqueue(json(f.receipt()))
            assertEquals(HandoverStage.ACCEPTED, recovery.receipt(original, unknown).stage)
            repeat(3) {
                assertEquals("/api/v1/auth/me", f.request().path)
                val read = f.request()
                assertEquals("GET", read.method)
                assertEquals("/api/v1/${f.receiptPath}", read.path)
                assertEquals("Bearer ${f.session.accessToken}", read.getHeader("Authorization"))
                assertEquals(f.terminal.terminalId, read.getHeader("X-Terminal-Id"))
                assertNull(read.getHeader("Idempotency-Key"))
            }
        }
    }

    @Test fun onePhysicalAttemptFor503AndExplicitRetryKeepsKeyAndUnknownOnDeniedPassword() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.me()
            f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            f.server.enqueue(json(f.receipt(), 201))
            val transport = f.transport
            assertTrue(runCatching { transport.submit(f.original, f.action, "pw1", true) }.isFailure)
            assertEquals(2, f.server.requestCount)
            val unknown = requireNotNull(f.store.value)
            assertEquals(HandoverStage.SENT_UNKNOWN, unknown.stage)
            assertEquals("/api/v1/auth/me", f.request().path)
            val first = f.request()
            // Remove the unused 201 by supplying a dispatcher for the explicit second attempt.
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/api/v1/auth/me") json(f.profile) else MockResponse().setResponseCode(401)
            }
            assertTrue(runCatching { transport.retry(f.original, unknown, "pw2", true) }.isFailure)
            assertEquals(unknown, f.store.value)
            assertEquals("/api/v1/auth/me", f.request().path)
            val retry = f.request()
            assertEquals(first.path, retry.path)
            assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
            val firstBody = ApiClient.json.parseToJsonElement(first.body.readUtf8()).jsonObject
            val retryBody = ApiClient.json.parseToJsonElement(retry.body.readUtf8()).jsonObject
            assertNotEquals(firstBody["password"], retryBody["password"])
            assertEquals(firstBody - "password", retryBody - "password")
            assertEquals(4, f.server.requestCount)
        }
    }

    @Test fun priorDomainBlockOrFailedDurableUnknownCannotSend() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start()
            f.workersClear = false
            val failure = runCatching { f.transport.submit(f.original, f.action, "pw", true) }.exceptionOrNull()
            assertTrue((failure as? TillPreparationBlocked)?.evidence.orEmpty().contains("stage=WORKER_JOIN"))
            assertNull(f.store.value)
            assertEquals(HandoverStartupStatus.Open, f.operations.status())
            assertEquals(0, f.server.requestCount)
            f.workersClear = true
            f.store.failNextStage = HandoverStage.SENT_UNKNOWN
            f.me()
            assertTrue(runCatching { f.transport.submit(f.original, f.action, "pw", true) }.isFailure)
            assertEquals(HandoverStage.NOT_SENT, f.store.value?.stage)
            assertEquals("/api/v1/auth/me", f.request().path)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun preflightReportsOnlySafeLocalCategoriesAndDoesNotSend() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start()
            f.localWork = LocalHandoverWork(false, setOf(LocalHandoverWorkKind.ROOM),
                setOf(LocalHandoverWorkKind.SHARED_ATTENDANCE), roomPendingCount = 3)
            val failure = runCatching { f.transport.submit(f.original, f.action, "private-test-password", true) }.exceptionOrNull()
            val evidence = (failure as? TillPreparationBlocked)?.evidence.orEmpty()
            assertTrue(evidence.contains("stage=LOCAL_BEFORE_CLAIM_CLEANUP"))
            assertTrue(evidence.contains("local_verified=false pending=ROOM unverified=SHARED_ATTENDANCE room=3"))
            assertFalse(evidence.contains(f.action.key))
            assertFalse(evidence.contains("private-test-password"))
            assertEquals(0, f.server.requestCount)
            assertNull(f.store.value)
            assertEquals(HandoverStartupStatus.Open, f.operations.status())
        }
    }

    @Test fun wrongReceiptThenFailedAcceptCheckpointCannotSettle() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.me()
            f.server.enqueue(json(f.receipt().copy(incomingUserId = id()), 201))
            assertTrue(runCatching { f.transport.submit(f.original, f.action, "pw", true) }.isFailure)
            val unknown = requireNotNull(f.store.value)
            assertEquals(HandoverStage.SENT_UNKNOWN, unknown.stage)
            f.store.failNextStage = HandoverStage.ACCEPTED
            f.me(); f.server.enqueue(json(f.receipt()))
            assertTrue(runCatching { f.transport.receipt(f.original, unknown) }.isFailure)
            assertEquals(unknown, f.store.value)
            assertTrue(f.operations.coordinator.state.value.storageFault)
            assertTrue(f.operations.coordinator.state.value.activeOperations.isEmpty())
        }
    }

    @Test fun missingRequiredNullAndChangedOriginalAfterResponseStayUnknown() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.me()
            val wire = HandoverJson.encodeToString(f.receipt()).replace("\"counted_cash_minor\":null,", "")
            f.server.enqueue(MockResponse().setResponseCode(201).setBody(wire))
            assertTrue(runCatching { f.transport.submit(f.original, f.action, "pw", true) }.isFailure)
            val unknown = requireNotNull(f.store.value)
            assertEquals(HandoverStage.SENT_UNKNOWN, unknown.stage)
            val validReceipt = f.receipt()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/v1/auth/me") return json(f.profile)
                    f.profile = f.profile.copy(userId = id())
                    return json(validReceipt)
                }
            }
            assertTrue(runCatching { f.transport.receipt(f.original, unknown) }.isFailure)
            assertEquals(4, f.server.requestCount)
            assertEquals(unknown, f.store.value)
            assertTrue(f.operations.coordinator.state.value.activeOperations.isEmpty())
        }
    }

    @Test fun exactReceiptCapabilityCannotBorrowBootstrapPostAliasesQueryOrChildWork() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.me()
            f.server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { f.transport.submit(f.original, f.action, "pw", true) }.isFailure)
            val unknown = requireNotNull(f.store.value)
            val client = OperationTaggedCallFactory(f.client())
            val before = f.server.requestCount
            f.operations.withHandoverWire(f.original, unknown, OperationPurpose.READ_HANDOVER_RECEIPT,
                f.session.accessToken) { permit ->
                val badRequests = listOf(
                    f.wireRequest("auth/me"),
                    f.wireRequest(f.sendPath, "POST", f.action.key),
                    f.wireRequest(f.receiptPath + "&action_key=${f.action.key}"),
                    f.wireRequest(f.receiptPath.replace("operator-", "%6fperator-")),
                    f.wireRequest(f.receiptPath.replace("/operator-", "%2Foperator-")),
                    f.wireRequest(f.receiptPath.replace(f.action.key, id())),
                )
                for (request in badRequests) {
                    assertTrue(runCatching { withContext(Dispatchers.IO) { client.newCall(request).execute().close() } }.isFailure)
                }
                assertTrue(runCatching { f.operations.coordinator.child(permit) }.isFailure)
                assertEquals(before, f.server.requestCount)
                f.server.enqueue(json(f.receipt()))
                withContext(Dispatchers.IO) { client.newCall(f.wireRequest(f.receiptPath)).execute().use { assertTrue(it.isSuccessful) } }
                assertTrue(runCatching { withContext(Dispatchers.IO) {
                    client.newCall(f.wireRequest(f.receiptPath)).execute().close()
                } }.isFailure)
                assertEquals(before + 1, f.server.requestCount)
            }
            assertEquals(unknown, f.store.value)
        }
    }

    @Test fun exactPostPermitRejectsSubstitutedBodyAndDifferentActionBeforeNetwork() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.me(); f.server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { f.transport.submit(f.original, f.action, "pw", true) }.isFailure)
            val unknown = requireNotNull(f.store.value)
            assertEquals(HandoverStage.SENT_UNKNOWN, unknown.stage)
            val client = OperationTaggedCallFactory(f.client())
            val before = f.server.requestCount
            val body = TillHandoverBody(f.action, "pw")
            val wrongAction = f.action.copy(incomingUserId = id(), expectedRevision = 1)
            val wrongBody = TillHandoverBody(wrongAction, "pw")
            assertTrue(runCatching {
                f.operations.withHandoverWire(f.original, unknown, OperationPurpose.SEND_HANDOVER,
                    f.session.accessToken, wrongBody) { error("An alternate action cannot acquire a permit.") }
            }.isFailure)
            f.operations.withHandoverWire(f.original, unknown, OperationPurpose.SEND_HANDOVER,
                f.session.accessToken, body) {
                val target = f.wireRequest(f.sendPath, "POST", f.action.key)
                for (substitute in listOf(wrongBody, TillHandoverBody(f.action, "other-password"))) {
                    val altered = target.newBuilder().post(substitute).build()
                    assertTrue(runCatching { withContext(Dispatchers.IO) { client.newCall(altered).execute().close() } }.isFailure)
                }
                assertEquals(before, f.server.requestCount)
                f.server.enqueue(json(f.receipt(), 201))
                withContext(Dispatchers.IO) {
                    client.newCall(target.newBuilder().post(body).build()).execute().use { assertTrue(it.isSuccessful) }
                }
                assertEquals(before + 1, f.server.requestCount)
            }
            assertEquals(unknown, f.store.value)
        }
    }

    @Test fun eligibleNamesUseCapturedOpenShiftAndContinueAnEmptyPage() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start()
            val state = OriginalOperatorState(f.action.shiftId, "open", null, 0, "untracked", emptyList())
            val cursor = "00000000-0000-0000-0000-000000000001"
            f.me(); f.server.enqueue(json(state)); f.server.enqueue(json(TillOperatorCandidates(emptyList(), cursor)))
            val (revision, emptyPage) = f.transport.choices(f.original, f.action.shiftId)
            assertEquals(0, revision)
            assertEquals(cursor, emptyPage.nextCursor)
            assertTrue(emptyPage.items.isEmpty())
            val b = TillOperatorCandidate(f.action.incomingUserId, "Staff B")
            f.me(); f.server.enqueue(json(state)); f.server.enqueue(json(TillOperatorCandidates(listOf(b), null)))
            assertEquals(listOf(b), f.transport.choices(f.original, f.action.shiftId, cursor).second.items)
            assertEquals("/api/v1/auth/me", f.request().path)
            assertEquals("/api/v1/${f.shiftPath}/operator-state", f.request().path)
            assertEquals("/api/v1/${f.shiftPath}/operator-candidates", f.request().path)
            assertEquals("/api/v1/auth/me", f.request().path)
            assertEquals("/api/v1/${f.shiftPath}/operator-state", f.request().path)
            assertEquals("/api/v1/${f.shiftPath}/operator-candidates?after_id=$cursor", f.request().path)
            assertNull(f.store.value)
        }
    }

    @Test fun differentOriginalActorOrStickyUnknownCannotBorrowRetainedWirePermit() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.me(); f.server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { f.transport.submit(f.original, f.action, "pw", true) }.isFailure)
            val unknown = requireNotNull(f.store.value)
            val sticky = unknown.copy(priorMutationUncertainty = true)
            f.store.value = sticky
            val current = f.restore(sticky)
            assertNull(f.operations.captureForSentHandover(f.profile.copy(userId = id()), f.cache, sticky))
            assertTrue(runCatching {
                f.operations.withHandoverWire(current, sticky, OperationPurpose.READ_HANDOVER_RECEIPT, f.session.accessToken) {
                    error("A sticky unknown cannot admit the wire.")
                }
            }.isFailure)
            assertEquals(sticky, f.store.value)
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun incomingReadGateRequiresExactReceiptHeadersAndNeverAdmitsOperationalWrites() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start()
            val receipt = f.receipt().verify(f.action)
            var current = true
            val gate = AcceptedTillReadGate(receipt, "incoming-bearer", canReadGaming = true) { current }
            val exact = f.wireRequest("${f.shiftPath}/operator-state")
                .newBuilder().header("Authorization", "Bearer incoming-bearer")
                .header("X-Terminal-Id", receipt.terminalId).build()
            gate.beforeAttempt(exact)
            for (request in listOf(
                exact.newBuilder().header("Authorization", "Bearer previous-cashier").build(),
                exact.newBuilder().header("X-Terminal-Id", id()).build(),
                f.wireRequest("${f.shiftPath}/operator-state?wrong=true"),
                f.wireRequest(f.sendPath, "POST", f.action.key),
                f.wireRequest("auth/login", "POST"),
                f.wireRequest("gaming/sessions?unbilled_only=false&include_pos_linked_reviewable=false&limit=500"),
            )) {
                assertThrows(IOException::class.java) { gate.beforeAttempt(request) }
            }
            current = false
            assertThrows(IOException::class.java) { gate.beforeAttempt(exact) }
        }
    }

    @Test fun acceptedIntentRemainsUntilExactIncomingPublicationChecksPass() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start(); f.me(); f.server.enqueue(json(f.receipt(), 201))
            val accepted = f.transport.submit(f.original, f.action, "pw", true)
            val stale = accepted.copy(receipt = requireNotNull(accepted.receipt).copy(receiptId = id()))
            assertTrue(runCatching { f.operations.coordinator.completeAccepted(stale) { true } }.isFailure)
            assertTrue(runCatching { f.operations.coordinator.completeAccepted(accepted) { false } }.isFailure)
            assertEquals(accepted, f.store.value)
            assertTrue(f.operations.status() is HandoverStartupStatus.RecoveryRequired)
            f.operations.coordinator.completeAccepted(accepted) { true }
            assertNull(f.store.value)
            assertEquals(HandoverStartupStatus.Open, f.operations.status())
        }
    }

    @Test fun onlyTheExactLinkedOwnerReturnCanReopenTheOriginalCashier() = runBlocking<Unit> {
        Fixture().use { f ->
            f.start()
            val acceptedWire = f.receipt()
            val accepted = HandoverIntent(f.action, HandoverStage.ACCEPTED, acceptedWire.verify(f.action),
                priorMutationUncertainty = false)
            val a = f.action.origin.scope.actorId
            val correction = acceptedWire.copy(id = id(), initiatedBy = id(), previousOperator = f.action.incomingUserId,
                incomingUserId = a, fromRevision = 1, toRevision = 2, kind = "owner_recovery",
                reason = "Return the till to its previous cashier", resolvesHandoverId = acceptedWire.id)
            val current = IncomingTillState(f.action.shiftId, "open", a, 2, "recorded", correction, hasRunningGames = true)
            current.verifyOwnerReturn(accepted)
            assertTrue(runCatching { current.verify(accepted) }.isFailure)
            for (changed in listOf(
                current.copy(operatorUserId = f.action.incomingUserId),
                current.copy(revision = 3),
                current.copy(shiftStatus = "closed"),
                current.copy(latest = correction.copy(resolvesHandoverId = id())),
                current.copy(latest = correction.copy(previousOperator = id())),
                current.copy(latest = correction.copy(countedCashMinor = 0)),
            )) assertTrue(runCatching { changed.verifyOwnerReturn(accepted) }.isFailure)
        }
    }

    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun token(profile: MeResponse): String {
            val raw = """{"sub":"${profile.userId}","company_id":"${profile.companyId}","branch_id":"${profile.branchId}"}"""
            return "fixture." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray()) + ".jwt"
        }
        private inline fun <reified T> json(value: T, code: Int = 200): MockResponse =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
                .setBody(HandoverJson.encodeToString(value))
    }
}
