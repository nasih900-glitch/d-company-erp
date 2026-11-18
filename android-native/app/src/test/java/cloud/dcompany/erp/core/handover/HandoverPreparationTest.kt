package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class HandoverPreparationTest {
    private fun id() = UUID.randomUUID().toString()
    private val a = HandoverAuthority(HandoverScope(id(), id(), id(), id()), 1)
    private fun action() = HandoverAction(id(), a, id(), id(), 0)
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        var beforeBegin: suspend () -> Unit = {}
        var failAfterWrite: Throwable? = null
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { beforeBegin(); check(value == null); value = intent; failAfterWrite?.let { throw it } }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { check(value == expected); value = next }
        override suspend fun cancelNotSent(expected: HandoverIntent) { check(value == expected); value = null }
    }
    @Test fun admissionClosesInRamWhileAlreadyAdmittedAChildrenCanFinish() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val prior = c.admit(a); val cap = c.beginPreparation(a, action(), { true })
        assertTrue(runCatching { c.admit(a) }.isFailure); assertNull(store.value)
        val child = c.child(prior)
        c.beforeMutationDispatch(child, a); c.finish(child, OperationOutcome.DURABLE_OUTCOME)
        assertTrue(c.awaitPreparationDrained(cap, 1) is HandoverDrainResult.Blocked)
        c.finish(prior, OperationOutcome.DURABLE_OUTCOME)
        assertTrue(c.awaitPreparationDrained(cap, 1) is HandoverDrainResult.Drained)
        assertNull(store.value)
    }
    @Test fun noAdmissionWindowExistsBetweenDomainProofAndDurableFreeze() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val cap = c.beginPreparation(a, action(), { true })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.beforeBegin = { entered.complete(Unit); release.await() }
        val save = async { c.observeAndPersistPreparation(a, cap, { true }) { _, permit ->
            assertTrue(runCatching { c.admit(a) }.isFailure)
            assertTrue(runCatching { c.child(permit) }.isFailure)
            PriorQuiescenceResult.VERIFIED
        } }
        entered.await()
        val newWrite = async { runCatching { c.admit(a) }.isFailure }
        yield(); assertFalse(newWrite.isCompleted)
        release.complete(Unit)
        val retained = requireNotNull(save.await())
        assertTrue(newWrite.await()); assertEquals(retained, store.value)
        assertEquals(HandoverStage.NOT_SENT, retained.stage); assertFalse(retained.priorMutationUncertainty)
        assertTrue(c.state.value.activeOperations.isEmpty())
        assertTrue(runCatching { c.admit(a) }.isFailure)
        assertTrue(runCatching { c.abortPreparation(a, cap, { true }) }.isFailure)
    }
    @Test fun crashBeforeDurablePhaseCannotHaveSentBAndLeavesOriginalARecoveryBaseline() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        c.beginPreparation(a, action(), { true }); assertNull(store.value)
        val recreated = HandoverCoordinator(store); recreated.initialize(a)
        assertNull(recreated.state.value.intent); assertNull(recreated.state.value.preparationAction)
        recreated.finish(recreated.admit(a), OperationOutcome.NO_WRITE_SENT)
    }
    @Test fun blockedOrCancelledProofKeepsPreparationClosedUntilExactAAbort() = runBlocking {
        for (cancel in listOf(false, true)) {
            val store = Store(); val c = HandoverCoordinator(store); c.initialize(a); val cap = c.beginPreparation(a, action(), { true })
            val result = runCatching { c.observeAndPersistPreparation(a, cap, { true }) { _, _ ->
                if (cancel) throw CancellationException("Synthetic observation cancellation")
                PriorQuiescenceResult.BLOCKED
            } }
            if (cancel) assertTrue(result.exceptionOrNull() is CancellationException) else assertNull(result.getOrThrow())
            assertNull(store.value); assertEquals(cap.action, c.state.value.preparationAction)
            assertTrue(c.state.value.activeOperations.isEmpty()); assertTrue(runCatching { c.admit(a) }.isFailure)
            assertTrue(runCatching { c.abortPreparation(a.copy(epoch = 2), cap, { true }) }.isFailure)
            c.abortPreparation(a, cap, { true }); c.finish(c.admit(a), OperationOutcome.NO_WRITE_SENT)
        }
    }
    @Test fun unknownPriorWorkCannotBeWashedAwayByCleanStoresOrPreparationAbort() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val old = c.admit(a); c.beforeMutationDispatch(old, a); c.finish(old, OperationOutcome.UNRESOLVED)
        val cap = c.beginPreparation(a, action(), { true }); var called = false
        assertTrue(runCatching { c.observeAndPersistPreparation(a, cap, { true }) { _, _ -> called = true; PriorQuiescenceResult.VERIFIED } }.isFailure)
        assertFalse(called); assertNull(store.value)
        c.abortPreparation(a, cap, { true })
        assertTrue(c.state.value.unresolvedOperations.contains(old.id))
        c.finish(c.admit(a), OperationOutcome.NO_WRITE_SENT)
    }
    @Test fun storageFailureAfterWritingActualIntentRemainsClosedAndRestartRecoversIt() = runBlocking {
        for (failure in listOf(IOException("Synthetic storage failure"), CancellationException("Synthetic cancellation during storage"))) {
            val store = Store(); val c = HandoverCoordinator(store); c.initialize(a); val cap = c.beginPreparation(a, action(), { true })
            store.failAfterWrite = failure
            assertSame(failure, runCatching { c.observeAndPersistPreparation(a, cap, { true }) { _, _ -> PriorQuiescenceResult.VERIFIED } }.exceptionOrNull())
            assertNotNull(store.value); assertTrue(c.state.value.storageFault)
            assertTrue(runCatching { c.admit(a) }.isFailure); assertTrue(runCatching { c.abortPreparation(a, cap, { true }) }.isFailure)
            val recreated = HandoverCoordinator(store); recreated.initialize(a)
            assertEquals(store.value, recreated.state.value.intent)
            assertTrue(recreated.state.value.priorQuiescenceUnverified)
        }
    }
    @Test fun authorityLifecycleOrStorageDriftCannotPersistCleanIntent() = runBlocking {
        for (drift in listOf("authority", "lifecycle", "storage")) {
            val store = Store(); val c = HandoverCoordinator(store); c.initialize(a); val cap = c.beginPreparation(a, action(), { true }); var current = true
            assertTrue(runCatching { c.observeAndPersistPreparation(a, cap, { current }) { _, _ ->
                when (drift) {
                    "authority" -> current = false
                    "storage" -> store.value = HandoverIntent(action(), priorMutationUncertainty = true)
                    else -> { val p = c.admitCleanup(a, OperationPurpose.RELEASE_OWN_CHECKOUT, id(), cap); c.finish(p, OperationOutcome.NO_WRITE_SENT) }
                }
                PriorQuiescenceResult.VERIFIED
            } }.isFailure)
            if (drift != "storage") assertNull(store.value)
            assertTrue(c.state.value.activeOperations.isEmpty()); assertEquals(cap.action, c.state.value.preparationAction)
        }
    }
    @Test fun ramPreparationCannotSendBPostEvenThroughUppercaseOrRedirectAliases() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val alreadyAdmittedA = c.admit(a)
        val cap = c.beginPreparation(a, action(), { true })
        val original = OriginalOperationBinding(a,
            CacheScopeLease(CacheScope(a.scope.actorId, a.scope.companyId, a.scope.branchId, a.scope.terminalId), 1),
            SessionRefreshLease(StoredSession("synthetic-A", "synthetic-refresh"), 1), { true })
        val gate = CoordinatorOperationDispatchGate(c) { HandoverStartupStatus.Preparing }
        MockWebServer().use { server ->
            server.start(); repeat(4) { server.enqueue(MockResponse().setBody("{}")) }
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .addInterceptor(OperationDispatchInterceptor(gate)).addNetworkInterceptor(OperationDispatchInterceptor(gate, true)).build()
            try {
                withContext(OperationDispatchContext(original, alreadyAdmittedA)) {
                    val factory = OperationTaggedCallFactory(client)
                    val exact = "pos/shifts/${cap.action.shiftId}/operator-handovers"
                    for (path in listOf(exact, "pos/shifts/${cap.action.shiftId.uppercase()}/operator-handovers", exact + "/", exact + "?alias=1",
                        exact.replace("operator-handovers", "operator%2Dhandovers"),
                        exact.replace("operator-handovers", "%6fperator-handovers"),
                        exact.replace("/operator-handovers", "%2Foperator-handovers"),
                        exact.replace("pos/shifts/", "pos%2Fshifts%2F"))) {
                        val request = Request.Builder().url(server.url("/api/v1/$path"))
                            .header("Idempotency-Key", cap.action.key).post("{}".toRequestBody()).build()
                        assertTrue(path, runCatching { factory.newCall(request).execute().close() }.exceptionOrNull() is IOException)
                    }
                }
                assertEquals(0, server.requestCount); assertNull(store.value)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
        c.finish(alreadyAdmittedA, OperationOutcome.NO_WRITE_SENT)
    }

}
