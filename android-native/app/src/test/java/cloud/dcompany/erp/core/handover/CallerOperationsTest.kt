package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallerOperationsTest {
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { check(value == null); value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { check(value == expected); value = next }
        override suspend fun cancelNotSent(expected: HandoverIntent) { check(value == expected); value = null }
    }
    private class Fixture {
        val scope = HandoverScope(id(), id(), id(), id())
        val authority = HandoverAuthority(scope, 1)
        val current = AtomicBoolean(true)
        val binding = OriginalOperationBinding(authority, CacheScopeLease(CacheScope(scope.actorId, scope.companyId, scope.branchId, scope.terminalId), 1),
            SessionRefreshLease(StoredSession("synthetic-A", "synthetic-refresh"), 1)) { current.get() }
        val coordinator = HandoverCoordinator(Store())
        val caller = CallerOperations(binding) { original, block -> coordinator.runOperation(original.authority, block) }
        suspend fun start() { coordinator.initialize(authority) }
        fun action() = HandoverAction(id(), authority, id(), id(), 0)
        suspend fun dispatch() {
            val context = requireNotNull(OperationDispatchContext.captured())
            coordinator.beforeMutationDispatch(requireNotNull(context.permit), context.binding.authority)
            requireNotNull(context.progress).mutationDispatched()
        }
    }
    @Test fun admissionRefusalRestoresSynchronousBusyFlagWithoutEnteringOldBody() = runBlocking {
        val f = Fixture(); f.start(); f.coordinator.freeze(f.authority, f.action())
        var busy = true; var entered = false
        f.caller.launch(this, onRejected = { busy = false }) { entered = true }.join()
        assertFalse(busy); assertFalse(entered); assertTrue(f.coordinator.state.value.activeOperations.isEmpty())
    }
    @Test fun staleQueuedACallbackNeverObtainsFreshBAuthority() = runBlocking {
        val f = Fixture(); f.start(); f.current.set(false)
        var entered = false; var rejected = false
        f.caller.launch(this, { rejected = true }) { entered = true }.join()
        assertTrue(rejected); assertFalse(entered); assertTrue(f.coordinator.state.value.activeOperations.isEmpty())
    }
    @Test fun actualChildRoomCheckpointKeepsDrainBlockedAfterParentBodyReturns() = runBlocking {
        val f = Fixture(); f.start()
        val childEntered = CompletableDeferred<Unit>(); val allowRoom = CompletableDeferred<Unit>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = f.caller.launch(owner, { throw AssertionError(it) }) {
                f.dispatch()
                val child = launch { childEntered.complete(Unit); allowRoom.await() }
                child.join()
                reconciled()
            }
            childEntered.await()
            val intent = f.coordinator.freeze(f.authority, f.action())
            assertTrue(f.coordinator.awaitDrained(intent.action.key, 20) is HandoverDrainResult.Blocked)
            allowRoom.complete(Unit); job.join()
            assertTrue(f.coordinator.awaitDrained(intent.action.key, 100) is HandoverDrainResult.Drained)
        } finally { owner.cancel() }
    }
    @Test fun swallowedLostReplyIsUnknownUntilRealReconciliationNotNormalWrapperReturn() = runBlocking {
        val f = Fixture(); f.start()
        f.caller.run { f.dispatch(); /* existing caller can render its retry UI without claiming settlement */ }
        assertEquals(1, f.coordinator.state.value.unresolvedOperations.size)
    }
    @Test fun checkpointCannotSettleALaterDispatchedWrite() = runBlocking {
        val f = Fixture(); f.start()
        f.caller.run { f.dispatch(); reconciled(); f.dispatch() }
        assertEquals(1, f.coordinator.state.value.unresolvedOperations.size)
    }
    @Test fun checkpointCannotAcknowledgeAnotherStillRunningChildsUnknownWrite() = runBlocking {
        val f = Fixture(); f.start(); var premature = false
        f.caller.run {
            f.dispatch()
            val release = CompletableDeferred<Unit>()
            val child = launch { release.await() }
            premature = runCatching { reconciled() }.isFailure
            release.complete(Unit); child.join()
            // No successful domain reconciliation is supplied for the already dispatched write.
        }
        assertTrue(premature); assertEquals(1, f.coordinator.state.value.unresolvedOperations.size)
    }
    @Test fun cancellationAfterSendPreservesUnknownAndCancellationBeforeSendDoesNotInventMoneyFailure() = runBlocking {
        val f = Fixture(); f.start(); val started = CompletableDeferred<Unit>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = f.caller.launch(owner, {}) { f.dispatch(); started.complete(Unit); CompletableDeferred<Unit>().await() }
            started.await(); job.cancel(); job.join()
            assertEquals(1, f.coordinator.state.value.unresolvedOperations.size)
        } finally { owner.cancel() }
        val clean = Fixture(); clean.start()
        clean.caller.run { /* validation denied before any request */ }
        assertTrue(clean.coordinator.state.value.unresolvedOperations.isEmpty())
        assertTrue(clean.coordinator.state.value.activeOperations.isEmpty())
    }
    companion object { private fun id() = UUID.randomUUID().toString() }
}
