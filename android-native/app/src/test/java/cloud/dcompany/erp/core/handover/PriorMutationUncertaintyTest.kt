package cloud.dcompany.erp.core.handover

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PriorMutationUncertaintyTest {
    private val scope = HandoverScope(id(), id(), id(), id())
    private val a = HandoverAuthority(scope, 1)
    private fun action() = HandoverAction(id(), a, id(), id(), 0)
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        var failWrite = false
        var beforeWrite: (suspend (HandoverIntent) -> Unit)? = null
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { check(value == null); value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) {
            validateHandoverTransition(expected, next); check(value == expected)
            beforeWrite?.invoke(next)
            if (failWrite) throw IOException("Synthetic fsync failure")
            value = next
        }
        override suspend fun cancelNotSent(expected: HandoverIntent) { check(value == expected && !expected.priorMutationUncertainty); value = null }
    }
    @Test fun mutationAfterFreezeCannotReachWireUntilUncertaintyFsyncCompletes() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val p = c.admit(a); val initial = c.freeze(a, action()); assertFalse(initial.priorMutationUncertainty)
        val saving = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.beforeWrite = { if (it.priorMutationUncertainty) { saving.complete(Unit); release.await() } }
        var wire = false
        val request = async { c.beforeMutationDispatch(p, a); wire = true }
        saving.await(); assertFalse(wire); assertFalse(requireNotNull(store.value).priorMutationUncertainty)
        release.complete(Unit); request.await(); assertTrue(wire); assertTrue(requireNotNull(store.value).priorMutationUncertainty)
        c.finish(p, OperationOutcome.DURABLE_OUTCOME)
        assertFalse(requireNotNull(store.value).priorMutationUncertainty)
    }
    @Test fun markerWriteFailureDeniesDispatchAndMarkerClearFailureRetainsWorkPermit() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val p = c.admit(a); c.freeze(a, action()); store.failWrite = true
        var sent = false
        assertTrue(runCatching { c.beforeMutationDispatch(p, a); sent = true }.isFailure)
        assertFalse(sent); assertTrue(c.state.value.storageFault)

        val clearStore = Store(); val clearing = HandoverCoordinator(clearStore); clearing.initialize(a)
        val started = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        val workflow = async {
            runCatching { clearing.runOperation(a) { permit ->
                clearing.beforeMutationDispatch(permit, a); started.complete(Unit); done.await()
                OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME)
            } }
        }
        started.await(); clearing.freeze(a, action()); clearStore.failWrite = true; done.complete(Unit)
        val failure = workflow.await().exceptionOrNull()
        assertTrue(failure is IOException); assertTrue(clearing.state.value.storageFault)
        assertEquals(1, clearing.state.value.unresolvedOperations.size)
        assertTrue(requireNotNull(clearStore.value).priorMutationUncertainty)
    }
    @Test fun dispatchedChildKeepsMarkerUntilTheEntireParentLifetimeSettles() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val parent = c.admit(a); val child = c.child(parent)
        c.beforeMutationDispatch(child, a); c.freeze(a, action())
        c.finish(child, OperationOutcome.DURABLE_OUTCOME)
        assertTrue(requireNotNull(store.value).priorMutationUncertainty)
        c.finish(parent, OperationOutcome.NO_WRITE_SENT)
        assertFalse(requireNotNull(store.value).priorMutationUncertainty)
    }
    @Test fun clearingAnEarlierOutcomeCannotRacePastALaterMutationDispatch() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val first = c.admit(a); val second = c.admit(a)
        c.beforeMutationDispatch(first, a); c.freeze(a, action())
        val clearing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()
        store.beforeWrite = { next ->
            writes += next.priorMutationUncertainty
            if (!next.priorMutationUncertainty) { clearing.complete(Unit); release.await() }
        }
        val completion = async { c.finish(first, OperationOutcome.DURABLE_OUTCOME) }
        clearing.await(); var wire = false
        val later = async { c.beforeMutationDispatch(second, a); wire = true }
        assertFalse(wire); release.complete(Unit); completion.await(); later.await()
        assertEquals(listOf(false, true), writes); assertTrue(wire)
        assertTrue(requireNotNull(store.value).priorMutationUncertainty)
    }
    @Test fun restoredTrueOrMissingMarkerStaysStickyDespiteCleanupAndGenericVerification() = runBlocking {
        for (legacy in listOf(false, true)) {
            val store = Store(); val original = HandoverIntent(action())
            store.value = if (legacy) HandoverJson.decodeFromString<HandoverIntent>(
                HandoverJson.encodeToString(original).replace(",\"priorMutationUncertainty\":true", "")) else original
            assertTrue(requireNotNull(store.value).priorMutationUncertainty)
            val fresh = a.copy(epoch = 2); val c = HandoverCoordinator(store); c.initialize(fresh)
            assertTrue(c.reconcilePriorQuiescence(fresh, requireNotNull(store.value)) { PriorQuiescenceResult.VERIFIED })
            val cleanup = c.admitCleanup(fresh, OperationPurpose.RELEASE_OWN_CHECKOUT, "known-original-order")
            c.beforeMutationDispatch(cleanup, fresh); c.finish(cleanup, OperationOutcome.DURABLE_OUTCOME)
            assertTrue(requireNotNull(store.value).priorMutationUncertainty); assertTrue(c.state.value.mutationUncertaintySticky)
            assertTrue(c.awaitDrained(original.action.key, 10) is HandoverDrainResult.Blocked)
            assertTrue(runCatching { c.markSentUnknown(fresh, original.action.key) }.isFailure)
            assertTrue(runCatching { c.cancelNotSent(fresh, original.action.key) }.isFailure)
        }
    }
    @Test fun explicitFreshAResumptionPreservesFenceAndCannotGrantBOrStaleEpoch() = runBlocking {
        val store = Store(); store.value = HandoverIntent(action()); val original = requireNotNull(store.value)
        val c = HandoverCoordinator(store); val fresh = a.copy(epoch = 2); c.initialize(fresh)
        val cap = c.resumeSameActor(fresh, original)
        c.runResumedOperation(cap) { permit -> c.beforeMutationDispatch(permit, fresh); OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME) }
        assertEquals(original, store.value); assertTrue(c.state.value.sameActorResumed)
        assertTrue(runCatching { c.admit(fresh) }.isFailure) // No globally Open fallback.
        assertTrue(runCatching { c.resumeSameActor(fresh.copy(scope = scope.copy(actorId = id())), original) }.isFailure)
        assertTrue(runCatching { c.markSentUnknown(fresh, original.action.key) }.isFailure)
        c.pauseSameActorResumption(cap)
        assertTrue(runCatching { c.runResumedOperation(cap) { OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) } }.isFailure)
        val old = c.resumeSameActor(fresh, original); c.initialize(fresh.copy(epoch = 3))
        assertTrue(runCatching { c.runResumedOperation(old) { OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) } }.isFailure)
        assertEquals(original, store.value)
    }
    @Test fun sentOrAcceptedHandoverCannotCreateAnAResumptionCapability() = runBlocking {
        val initial = HandoverIntent(action(), priorMutationUncertainty = false)
        val receipt = AcceptedHandover(id(), initial.action.key, scope.companyId, requireNotNull(scope.branchId),
            requireNotNull(scope.terminalId), initial.action.shiftId, scope.actorId, initial.action.incomingUserId, 0, 1)
        for (record in listOf(initial.copy(stage = HandoverStage.SENT_UNKNOWN), initial.copy(stage = HandoverStage.ACCEPTED, receipt = receipt))) {
            val store = Store(); store.value = record
            val fresh = a.copy(epoch = 2); val c = HandoverCoordinator(store); c.initialize(fresh)
            assertTrue(runCatching { c.resumeSameActor(fresh, record) }.isFailure)
            assertFalse(c.state.value.sameActorResumed); assertEquals(record, store.value)
        }
    }
    companion object { private fun id() = UUID.randomUUID().toString() }
}
