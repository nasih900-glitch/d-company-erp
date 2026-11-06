package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.Terminal
import cloud.dcompany.erp.ui.screens.gaming.GameSession
import cloud.dcompany.erp.ui.screens.shift.ShiftDetail
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

class WorkspaceSelectorTest {
    private class Store : HandoverIntentStore {
        override suspend fun read(): HandoverIntent? = null
        override suspend fun begin(intent: HandoverIntent) = error("No handover write")
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) = error("No handover write")
        override suspend fun cancelNotSent(expected: HandoverIntent) = error("No handover write")
    }
    private class Fixture(branchlessOwner: Boolean = false, mismatchedEffectiveBranch: Boolean = false) {
        val scope = HandoverScope(id(), id(), id(), id())
        val actor = HandoverAuthority(scope, 1)
        var generation = 1L
        val cached = CacheScopeLease(CacheScope(scope.actorId, scope.companyId,
            if (branchlessOwner) null else if (mismatchedEffectiveBranch) id() else scope.branchId, scope.terminalId), generation)
        var cache: CacheScopeLease? = cached
        var login: SessionRefreshLease? = SessionRefreshLease(StoredSession(token(scope.actorId, scope.companyId, cached.scope.branchId), "synthetic-refresh"), 1)
        val initial = requireNotNull(login)
        val original = OriginalOperationBinding(actor, cached, initial) { cache == cached && login?.session?.lineage === initial.session.lineage }
        val authority = WorkspaceExitAuthority(original, initial, cached, generation,
            { login }, { lease -> login?.accessToken?.takeIf { login?.session?.lineage === lease.session.lineage } }, { cache }, { generation })
        val coordinator = HandoverCoordinator(Store())
        var status: HandoverStartupStatus = HandoverStartupStatus.Open
        lateinit var cap: HandoverCoordinator.WorkspaceExit
        suspend fun start(kind: WorkspaceExitKind = WorkspaceExitKind.TERMINAL_CHANGE, deactivate: Boolean = true, complete: Boolean = true) {
            coordinator.initialize(actor)
            cap = coordinator.beginWorkspaceExit(actor, kind, authority::beforeDeactivation)
            if (deactivate) {
                assertTrue(coordinator.finalizeWorkspaceExit(cap, authority::beforeDeactivation) { cache = null; generation++; true })
                if (complete) coordinator.completeWorkspaceExit(cap, authority::afterDeactivation)
            }
        }
        fun gate(branch: String = requireNotNull(scope.branchId)) = WorkspaceSelectorGate(authority, cap, coordinator, branch) { status }
    }
    private fun request(path: String, post: Boolean = false) = Request.Builder().url("http://localhost/api/v1/$path")
        .apply { if (post) post("{}".toRequestBody()) }.build()
    private fun clientApi(f: Fixture, server: MockWebServer, client: okhttp3.OkHttpClient): WorkspaceSelectorReads =
        WorkspaceSelectorReads(f.gate(), Retrofit.Builder().baseUrl(server.url("/api/v1/")).client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType())).build().create(WorkspaceSelectorApi::class.java))

    @Test fun actualFactoryRunsNormalReassignmentReadsWithOriginalBearerNoTerminalOrCacheAdoption() = runBlocking<Unit> {
        val f = Fixture(); f.start()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("[]"))
            // A branch-scoped legacy NULL-terminal row must remain visible as a safety blocker.
            server.enqueue(MockResponse().setBody("""[{"id":"${id()}","branch_id":"${f.scope.branchId}","terminal_id":null,"status":"open","opened_at":"2026-11-05T00:00:00Z"}]"""))
            server.enqueue(MockResponse().setBody("[]")); server.enqueue(MockResponse().setBody("[]"))
            val client = workspaceSelectorClient(f.authority, f.gate())
            try {
                val reads = clientApi(f, server, client)
                assertTrue(reads.terminals().isEmpty())
                val shifts = reads.shifts(); assertEquals(1, shifts.size); assertNull(shifts.single().terminalId)
                assertEquals(f.scope.branchId, shifts.single().branchId); assertEquals("open", shifts.single().status)
                assertTrue(reads.sessions().isEmpty()); assertTrue(reads.terminals().isEmpty())
                val paths = (1..4).map { requireNotNull(server.takeRequest()).also { r ->
                    assertEquals("GET", r.method); assertEquals("Bearer ${f.initial.accessToken}", r.getHeader("Authorization"))
                    assertNull(r.getHeader("X-Terminal-Id")); assertNull(r.getHeader("Idempotency-Key")); assertEquals(0, r.bodySize)
                }.path }
                assertEquals(listOf("/api/v1/settings/terminals?branch_id=${f.scope.branchId}", "/api/v1/pos/shifts?only_open=true&limit=200",
                    "/api/v1/gaming/sessions?unbilled_only=true&include_pos_linked_reviewable=false&limit=500",
                    "/api/v1/settings/terminals?branch_id=${f.scope.branchId}"), paths)
                assertNull(f.cache); assertEquals(2L, f.generation); assertSame(f.initial, f.login)
                assertTrue(f.coordinator.state.value.activeOperations.isEmpty()); assertNull(f.coordinator.state.value.intent)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun bootstrapMutationForeignBranchExtraQueryAndEncodedAliasesNeverReachNetwork() = runBlocking<Unit> {
        val f = Fixture(); f.start()
        MockWebServer().use { server ->
            server.start(); repeat(20) { server.enqueue(MockResponse().setBody("[]")) }
            val client = workspaceSelectorClient(f.authority, f.gate())
            try {
                for ((path, post) in listOf("auth/login" to true, "auth/me" to false, "auth/logout" to true,
                    "pos/shifts/open" to true, "finance/expenses" to false, "staff/my-attendance/clock-in" to true,
                    "settings/terminals?branch_id=${id()}" to false, "settings/terminals?branch_id=${f.scope.branchId}&branch_id=${f.scope.branchId}" to false,
                    "pos/shifts?only_open=false&limit=200" to false, "pos/shifts?only_open=true&limit=201" to false,
                    "gaming/sessions?unbilled_only=true&include_pos_linked_reviewable=true&limit=500" to false,
                    "gaming/sessions?unbilled_only=true&include_pos_linked_reviewable=false&limit=501" to false,
                    "%73ettings/terminals?branch_id=${f.scope.branchId}" to false, "settings%2Fterminals?branch_id=${f.scope.branchId}" to false)) {
                    val r = request(path, post).newBuilder().url(server.url("/api/v1/$path")).build()
                    assertTrue(path, runCatching { client.newCall(r).execute().close() }.isFailure)
                }
                assertEquals(0, server.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun staleLoginSameActorReloginForeignCompanyAndCacheGenerationAreDenied() = runBlocking<Unit> {
        for (change in listOf("B", "same-A-login", "foreign-company", "foreign-actor-same-lineage", "foreign-branch", "no-auth", "generation", "active-cache", "startup")) {
            val f = Fixture(); f.start(); val gate = f.gate()
            when (change) {
                "B" -> f.login = SessionRefreshLease(StoredSession(token(id(), f.scope.companyId, f.scope.branchId), "B"), 2)
                "same-A-login" -> f.login = SessionRefreshLease(StoredSession(f.initial.accessToken, "new-explicit-login"), 2)
                "foreign-company" -> f.login = SessionRefreshLease(StoredSession(token(f.scope.actorId, id(), f.scope.branchId), "same-lineage", f.initial.session.lineage), 2)
                "foreign-actor-same-lineage" -> f.login = SessionRefreshLease(StoredSession(token(id(), f.scope.companyId, f.scope.branchId), "same-lineage", f.initial.session.lineage), 2)
                "foreign-branch" -> f.login = SessionRefreshLease(StoredSession(token(f.scope.actorId, f.scope.companyId, id()), "same-lineage", f.initial.session.lineage), 2)
                "no-auth" -> f.login = null
                "generation" -> f.generation++
                "active-cache" -> f.cache = f.cached.copy(generation = 3)
                "startup" -> f.status = HandoverStartupStatus.Loading
            }
            assertThrows(IOException::class.java) { gate.authorize(request("pos/shifts?only_open=true&limit=200")) }
        }
    }
    @Test fun sameLineageRefreshAndValidatedBranchlessOwnerKeepOnlyOriginalReadScope() = runBlocking<Unit> {
        for (branchless in listOf(false, true)) {
            val f = Fixture(branchless); f.start()
            f.login = SessionRefreshLease(StoredSession(f.initial.accessToken, "same-lineage-rotation", f.initial.session.lineage), 2)
            f.gate().authorize(request("settings/terminals?branch_id=${f.scope.branchId}"))
            assertThrows(IOException::class.java) { f.gate(id()).authorize(request("pos/shifts?only_open=true&limit=200")) }
            assertThrows(IOException::class.java) { f.gate().authorize(request("pos/shifts?only_open=true&limit=200").newBuilder().header("X-Terminal-Id", id()).build()) }
        }
    }
    @Test fun activeOrUncompletedExitAndLogoutPurposeCannotMintSelectorReads() = runBlocking<Unit> {
        for ((kind, deactivate, complete) in listOf(Triple(WorkspaceExitKind.TERMINAL_CHANGE, false, false),
            Triple(WorkspaceExitKind.TERMINAL_CHANGE, true, false), Triple(WorkspaceExitKind.LOGOUT, true, true))) {
            val f = Fixture(); f.start(kind, deactivate, complete)
            assertThrows(IOException::class.java) { f.gate().authorize(request("pos/shifts?only_open=true&limit=200")) }
        }
        val f = Fixture(); f.start(); val oldGate = f.gate()
        f.coordinator.beginWorkspaceExit(null, WorkspaceExitKind.LOGOUT, f.authority::afterDeactivation)
        assertThrows(IOException::class.java) { oldGate.authorize(request("pos/shifts?only_open=true&limit=200")) }
    }
    @Test fun suspendedSelectorResponseCannotPublishAfterBOrInactiveGenerationChanges() = runBlocking<Unit> {
        for (change in listOf("B", "generation", "fence")) {
            val f = Fixture(); f.start(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val fake = object : WorkspaceSelectorApi {
                override suspend fun terminals(branchId: String): List<Terminal> { entered.complete(Unit); release.await(); return listOf(Terminal(id(), "Synthetic", branchId)) }
                override suspend fun shifts(onlyOpen: Boolean, limit: Int): List<ShiftDetail> = error("Not called")
                override suspend fun sessions(unbilledOnly: Boolean, includePosLinkedReviewable: Boolean, limit: Int): List<GameSession> = error("Not called")
            }
            val reads = WorkspaceSelectorReads(f.gate(), fake)
            val pending = async { runCatching { reads.terminals() } }; entered.await()
            when (change) {
                "B" -> f.login = SessionRefreshLease(StoredSession(token(id(), f.scope.companyId, f.scope.branchId), "B"), 2)
                "generation" -> f.generation++
                "fence" -> f.status = HandoverStartupStatus.Preparing
            }
            release.complete(Unit); assertTrue(pending.await().isFailure)
        }
    }
    @Test fun actualSingleAttemptClientDoesNotFollowRedirectOr503AndRechecksAfterConnect() = runBlocking<Unit> {
        for (mode in listOf("redirect", "503", "connect-drift")) {
            val f = Fixture(); f.start()
            MockWebServer().use { server ->
                server.start(); server.enqueue(if (mode == "redirect") MockResponse().setResponseCode(302).setHeader("Location", server.url("/api/v1/auth/me"))
                    else if (mode == "503") MockResponse().setResponseCode(503).setHeader("Retry-After", "0") else MockResponse().setBody("[]"))
                server.enqueue(MockResponse().setBody("[]"))
                val client = workspaceSelectorClient(f.authority, f.gate()).newBuilder().apply {
                    if (mode == "connect-drift") eventListener(object : EventListener() {
                        override fun connectEnd(call: Call, address: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: Protocol?) { f.generation++ }
                    })
                }.build()
                try {
                    runCatching { clientApi(f, server, client).shifts() }
                    assertEquals(if (mode == "connect-drift") 0 else 1, server.requestCount)
                } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            }
        }
    }
    @Test fun explicitRetryCapturesOnlyFreshBearerWithinTheSameOriginalFamily() = runBlocking<Unit> {
        val f = Fixture(); f.start()
        MockWebServer().use { server ->
            server.start(); repeat(2) { server.enqueue(MockResponse().setBody("[]")) }
            suspend fun explicitRead() {
                val client = workspaceSelectorClient(f.authority, f.gate())
                try { clientApi(f, server, client).terminals() }
                finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            }
            explicitRead()
            val oldHeader = requireNotNull(server.takeRequest()).getHeader("Authorization")
            val rotated = f.initial.accessToken.substringBeforeLast('.') + ".rotated-fixture"
            f.login = SessionRefreshLease(StoredSession(rotated, "same-family-refresh", f.initial.session.lineage), 2)
            explicitRead()
            assertEquals("Bearer ${f.initial.accessToken}", oldHeader)
            assertEquals("Bearer $rotated", requireNotNull(server.takeRequest()).getHeader("Authorization"))
            assertNull(f.cache); assertEquals(2, server.requestCount)
        }
    }
    @Test fun malformedRawBranchCannotBeCoercedIntoLegacyCompanyScope() = runBlocking<Unit> {
        for (branchless in listOf(false, true)) for (raw in listOf("\"\"", "\"   \"", "true", "200", "{}", "[]", "\"not-a-uuid\"")) {
            val f = Fixture(branchlessOwner = branchless); f.start()
            val claims = "{\"sub\":\"${f.scope.actorId}\",\"company_id\":\"${f.scope.companyId}\",\"branch_id\":$raw}"
            val access = "synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".fixture"
            f.login = SessionRefreshLease(StoredSession(access, "same-family", f.initial.session.lineage), 2)
            assertThrows(IOException::class.java) { f.gate().authorize(request("pos/shifts?only_open=true&limit=200")) }
        }
    }
    @Test fun genuineNullOrMissingJwtBranchPreservesConservativeReadsWithVerifiedCachedBranch() = runBlocking<Unit> {
        for (missing in listOf(false, true)) {
            val f = Fixture(); f.start()
            val claims = "{\"sub\":\"${f.scope.actorId}\",\"company_id\":\"${f.scope.companyId}\"" +
                (if (missing) "}" else ",\"branch_id\":null}")
            val access = "synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".fixture"
            f.login = SessionRefreshLease(StoredSession(access, "same-original-family", f.initial.session.lineage), 2)
            MockWebServer().use { server ->
                server.start(); repeat(3) { server.enqueue(MockResponse().setBody("[]")) }
                val client = workspaceSelectorClient(f.authority, f.gate())
                try {
                    val reads = clientApi(f, server, client)
                    reads.terminals(); reads.shifts(); reads.sessions()
                    repeat(3) {
                        val wire = requireNotNull(server.takeRequest())
                        assertEquals("Bearer $access", wire.getHeader("Authorization")); assertNull(wire.getHeader("X-Terminal-Id"))
                    }
                    assertEquals(3, server.requestCount); assertNull(f.cache); assertEquals(2L, f.generation)
                    assertThrows(IOException::class.java) { f.gate(id()).authorize(request("pos/shifts?only_open=true&limit=200")) }
                } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            }
        }
    }
    @Test fun legacyNullJwtCannotBorrowADifferentEffectiveOriginalBranch() = runBlocking<Unit> {
        val f = Fixture(mismatchedEffectiveBranch = true); f.start()
        f.login = SessionRefreshLease(StoredSession(token(f.scope.actorId, f.scope.companyId, null), "same-original-family", f.initial.session.lineage), 2)
        assertThrows(IOException::class.java) { f.gate().authorize(request("pos/shifts?only_open=true&limit=200")) }
        assertThrows(IOException::class.java) { f.gate(requireNotNull(f.cached.scope.branchId)).authorize(request("pos/shifts?only_open=true&limit=200")) }
    }
    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun token(actor: String, company: String, branch: String?): String {
            val claims = "{\"sub\":\"$actor\",\"company_id\":\"$company\",\"branch_id\":${branch?.let { "\"$it\"" } ?: "null"}}"
            return "synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".fixture"
        }
    }
}
