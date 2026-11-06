package cloud.dcompany.erp.core.handover

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class HandoverCoordinatorTest {
    private val scope = HandoverScope("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    private val a = HandoverAuthority(scope, 1)
    private val b = HandoverAuthority(scope.copy(actorId = "55555555-5555-4555-8555-555555555555"), 2)
    private fun action() = HandoverAction(UUID.randomUUID().toString(), a, "66666666-6666-4666-8666-666666666666", b.scope.actorId, 0)
    private fun receipt(action: HandoverAction) = AcceptedHandover(UUID.randomUUID().toString(), action.key,
        scope.companyId, requireNotNull(scope.branchId), requireNotNull(scope.terminalId), action.shiftId, scope.actorId, action.incomingUserId, 0, 1)
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        var failRead = false; var failBegin = false; var failCas = false
        var enterBegin: CompletableDeferred<Unit>? = null; var releaseBegin: CompletableDeferred<Unit>? = null
        override suspend fun read(): HandoverIntent? { if (failRead) throw IOException("read"); return value }
        override suspend fun begin(intent: HandoverIntent) {
            enterBegin?.complete(Unit); releaseBegin?.await()
            if (failBegin) throw IOException("write")
            check(value == null || value == intent); value = intent
        }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) {
            if (failCas) throw IOException("sync")
            validateHandoverTransition(expected, next); check(value == expected); value = next
        }
        override suspend fun cancelNotSent(expected: HandoverIntent) {
            check(value == expected && expected.stage == HandoverStage.NOT_SENT); value = null
        }
    }
    private suspend fun refuses(block: suspend () -> Unit) {
        var refused = false
        try { block() } catch (_: Exception) { refused = true }
        assertTrue("Unsafe transition must fail closed", refused)
    }
    @Test fun initializationAndScopeEpochAreRequiredBeforeAdmission() = runBlocking {
        val c = HandoverCoordinator(Store())
        refuses { c.admit(a) }; c.initialize(a)
        refuses { c.admit(b) }
        val permit = c.admit(a)
        refuses { c.requireContinuation(permit, b) }
        c.finish(permit, OperationOutcome.NO_WRITE_SENT); c.initialize(b)
        refuses { c.requireContinuation(permit, b) }; refuses { c.admit(a) }
    }
    @Test fun freezeSerializesWithNewAdmissionAndPersistsBeforeReturning() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        store.enterBegin = CompletableDeferred(); store.releaseBegin = CompletableDeferred()
        val intent = action(); val freeze = async { c.freeze(a, intent) }; store.enterBegin!!.await()
        val later = async { runCatching { c.admit(a) } }
        yield(); assertFalse(later.isCompleted); assertFalse(freeze.isCompleted)
        store.releaseBegin!!.complete(Unit); freeze.await()
        assertEquals(intent, store.value?.action); assertTrue(later.await().isFailure)
    }
    @Test fun alreadyAdmittedACompletesRoomCheckpointDuringDrainWithoutMutexDeadlock() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a)
        val afterHttp = CompletableDeferred<Unit>(); val room = CompletableDeferred<Unit>(); var checkpoint = false
        val operation = async { c.runOperation(a) { permit ->
            c.beforeMutationDispatch(permit, a); afterHttp.complete(Unit); room.await()
            c.requireContinuation(permit, a); checkpoint = true
            OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME)
        } }
        afterHttp.await(); val i = c.freeze(a, action())
        assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Blocked)
        val drained = async { c.awaitDrained(i.action.key, 1000) }
        room.complete(Unit); operation.await()
        assertTrue(checkpoint); assertTrue(drained.await() is HandoverDrainResult.Drained)
        refuses { c.admit(a) }
    }
    @Test fun structuredChildMustFinishEvenWhenParentBodyReturns() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val op = async { c.runOperation(a) { _ ->
            launch { entered.complete(Unit); release.await() }
            OperationResult(Unit, OperationOutcome.NO_WRITE_SENT)
        } }
        entered.await(); val i = c.freeze(a, action()); yield()
        assertFalse(op.isCompleted); assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Blocked)
        release.complete(Unit); op.await(); assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Drained)
    }
    @Test fun externalChildCapabilityPreventsPrematureParentFinishAndCannotCrossActor() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a)
        val parent = c.admit(a); val i = c.freeze(a, action()); val child = c.child(parent)
        refuses { c.finish(parent, OperationOutcome.NO_WRITE_SENT) }
        refuses { c.beforeMutationDispatch(child, b) }
        c.beforeMutationDispatch(child, a); c.finish(child, OperationOutcome.DURABLE_OUTCOME)
        c.finish(parent, OperationOutcome.NO_WRITE_SENT)
        assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Drained)
    }
    @Test fun canceledDispatchedWorkStaysUnresolvedRatherThanPretendingHttpIdleIsClean() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a)
        val entered = CompletableDeferred<HandoverCoordinator.Permit>(); val block = CompletableDeferred<Unit>()
        val job = launch { c.runOperation(a) { permit ->
            c.beforeMutationDispatch(permit, a); entered.complete(permit); block.await()
            OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME)
        } }
        val p = entered.await(); val i = c.freeze(a, action()); job.cancelAndJoin()
        assertTrue(c.state.value.activeOperations.isEmpty()); assertEquals(setOf(p.id), c.state.value.unresolvedOperations)
        assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Blocked)
        refuses { c.finish(p, OperationOutcome.NO_WRITE_SENT) }
        c.finish(p, OperationOutcome.DURABLE_OUTCOME)
        assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Drained)
    }
    @Test fun canceledReadOrValidationBeforeDispatchDoesNotInventUnknownMoneyFailure() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a)
        val started = CompletableDeferred<Unit>(); val wait = CompletableDeferred<Unit>()
        val job = launch { c.runOperation(a) { _ ->
            started.complete(Unit); wait.await(); OperationResult(Unit, OperationOutcome.NO_WRITE_SENT)
        } }
        started.await(); val i = c.freeze(a, action()); job.cancelAndJoin()
        assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Drained)
        assertTrue(c.state.value.unresolvedOperations.isEmpty())
    }
    @Test fun explicitUnknownOutcomeBlocksUntilOriginalActorDurableResolution() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a)
        val p = c.admit(a); c.beforeMutationDispatch(p, a); c.finish(p, OperationOutcome.UNRESOLVED)
        val i = c.freeze(a, action()); refuses { c.requireContinuation(p, a) }
        assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Blocked)
        c.finish(p, OperationOutcome.DURABLE_OUTCOME)
        assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Drained)
    }
    @Test fun onlyOriginalActorCanAdmitNarrowExistingActionCleanupWhileFrozen() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a); c.freeze(a, action())
        refuses { c.admitCleanup(a, OperationPurpose.ORDINARY, "order:existing") }
        refuses { c.admitCleanup(b, OperationPurpose.RELEASE_OWN_CHECKOUT, "order:existing") }
        refuses { c.admitCleanup(a, OperationPurpose.RELEASE_OWN_CHECKOUT, "") }
        val cleanup = c.admitCleanup(a, OperationPurpose.RELEASE_OWN_CHECKOUT, "order:existing")
        assertEquals(scope, cleanup.authority.scope); c.beforeMutationDispatch(cleanup, a)
        c.finish(cleanup, OperationOutcome.DURABLE_OUTCOME)
    }
    @Test fun notSentAbortRestoresOnlySameAAndRetainsOtherUnresolvedBusiness() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a)
        val prior = c.admit(a); c.beforeMutationDispatch(prior, a); c.finish(prior, OperationOutcome.UNRESOLVED)
        val i = c.freeze(a, action()); refuses { c.cancelNotSent(b, i.action.key) }
        refuses { c.cancelNotSent(a, i.action.key) }
        val capability = c.resumeSameActor(a, i)
        assertTrue(c.state.value.unresolvedOperations.contains(prior.id))
        c.runResumedOperation(capability) { OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) }
        assertEquals(i, c.state.value.intent); refuses { c.admit(b) }; refuses { c.markSentUnknown(a, i.action.key) }
    }
    @Test fun dispatchedFenceSurvivesRestartAnd404CannotBecomeNotSentOrCancellation() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val i = c.freeze(a, action()); c.markSentUnknown(a, i.action.key)
        val restarted = HandoverCoordinator(store); restarted.initialize(a.copy(epoch = 2))
        assertEquals(HandoverStage.SENT_UNKNOWN, restarted.state.value.intent?.stage)
        refuses { restarted.admit(a.copy(epoch = 2)) }
        refuses { restarted.cancelNotSent(a.copy(epoch = 2), i.action.key) }
        refuses { store.compareAndSet(store.value!!, i) } // Missing receipt/HTTP404 never licenses rollback.
    }
    @Test fun exactAcceptedReceiptPersistsAndCannotBeRewrittenOrCancelled() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store); c.initialize(a)
        val i = c.freeze(a, action()); c.markSentUnknown(a, i.action.key)
        refuses { c.accept(a, i.action.key, receipt(i.action).copy(incomingUserId = scope.actorId)) }
        val accepted = c.accept(a, i.action.key, receipt(i.action))
        val restored = HandoverCoordinator(store); restored.initialize(a.copy(epoch = 2))
        assertEquals(accepted, restored.state.value.intent)
        refuses { restored.cancelNotSent(a.copy(epoch = 2), i.action.key) }
        refuses { store.compareAndSet(accepted, accepted.copy(receipt = receipt(i.action))) }
    }
    @Test fun failedStorageReadOrDurableWriteBlocksAdmissionAndNeverClaimsDispatch() = runBlocking {
        val store = Store(); val c = HandoverCoordinator(store)
        store.failRead = true; refuses { c.initialize(a) }; refuses { c.admit(a) }
        store.failRead = false; c.initialize(a); val p = c.admit(a)
        store.failBegin = true; refuses { c.freeze(a, action()) }; refuses { c.admit(a) }
        assertTrue(c.state.value.storageFault)
        c.requireContinuation(p, a); c.finish(p, OperationOutcome.NO_WRITE_SENT)
        store.failBegin = false; c.initialize(a); val i = c.freeze(a, action())
        store.failCas = true; refuses { c.markSentUnknown(a, i.action.key) }
        assertEquals(HandoverStage.NOT_SENT, store.value?.stage); assertTrue(c.state.value.storageFault)
    }
    @Test fun unregisteredOrOtherCoordinatorPermitCannotAuthorizeContinuation() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a)
        val other = HandoverCoordinator(Store()); other.initialize(a)
        val p = other.admit(a); refuses { c.requireContinuation(p, a) }
        other.finish(p, OperationOutcome.NO_WRITE_SENT); refuses { other.requireContinuation(p, a) }
    }
    @Test fun intentSchemaContainsNoCredentialFieldsAndRejectsUnexpectedSecretKeys() {
        val i = HandoverIntent(action()); val raw = HandoverJson.encodeToString(i)
        assertFalse(raw.contains("password")); assertFalse(raw.contains("token")); assertFalse(raw.contains("authBinding"))
        assertThrows(Exception::class.java) { HandoverJson.decodeFromString<HandoverIntent>(raw.dropLast(1) + ",\"password\":\"secret\"}") }
        assertThrows(Exception::class.java) { i.copy(action = i.action.copy(incomingUserId = scope.actorId)).validate() }
    }
    @Test fun thrownCallerCanResolveItsRecordedUnknownByIdOnlyUnderOriginalAuthority() = runBlocking {
        val c = HandoverCoordinator(Store()); c.initialize(a)
        assertTrue(runCatching { c.runOperation(a) { permit ->
            c.beforeMutationDispatch(permit, a)
            throw IOException("Reply lost")
            @Suppress("UNREACHABLE_CODE") OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME)
        } }.isFailure)
        val id = c.state.value.unresolvedOperations.single()
        refuses { c.resolve(b, id, OperationOutcome.DURABLE_OUTCOME) }
        refuses { c.resolve(a, id, OperationOutcome.NO_WRITE_SENT) }
        c.resolve(a, id, OperationOutcome.DURABLE_OUTCOME)
        assertTrue(c.state.value.unresolvedOperations.isEmpty())
        refuses { c.resolve(a, id, OperationOutcome.DURABLE_OUTCOME) }
    }

    @Test fun restoredNotSentIsUnverifiedUntilExactOriginalActorReconciliation() = runBlocking {
        val store = Store(); val i = HandoverIntent(action(), priorMutationUncertainty = false); store.begin(i)
        val c = HandoverCoordinator(store); val freshA = a.copy(epoch = 2); c.initialize(freshA)
        assertTrue(c.state.value.priorQuiescenceUnverified)
        val blocked = c.awaitDrained(i.action.key, 10)
        assertTrue(blocked is HandoverDrainResult.Blocked && blocked.priorQuiescenceUnverified)
        refuses { c.markSentUnknown(freshA, i.action.key) }; refuses { c.cancelNotSent(freshA, i.action.key) }
        var callbacks = 0
        refuses { c.reconcilePriorQuiescence(b, i) { callbacks++; PriorQuiescenceResult.VERIFIED } }
        refuses { c.reconcilePriorQuiescence(freshA, HandoverIntent(action())) { callbacks++; PriorQuiescenceResult.VERIFIED } }
        assertEquals(0, callbacks)
        assertTrue(c.reconcilePriorQuiescence(freshA, i) { exact ->
            assertEquals(i, exact); callbacks++; PriorQuiescenceResult.VERIFIED
        })
        assertFalse(c.state.value.priorQuiescenceUnverified)
        assertTrue(c.awaitDrained(i.action.key, 10) is HandoverDrainResult.Drained)
        c.markSentUnknown(freshA, i.action.key)
        assertEquals(HandoverStage.SENT_UNKNOWN, store.value?.stage)
    }
    @Test fun blockedOrThrowingPriorReconciliationNeverClearsRestoredBarrier() = runBlocking {
        val store = Store(); val i = HandoverIntent(action()); store.begin(i)
        val c = HandoverCoordinator(store); c.initialize(a)
        assertFalse(c.reconcilePriorQuiescence(a, i) { PriorQuiescenceResult.BLOCKED })
        refuses { c.reconcilePriorQuiescence(a, i) { throw IOException("Domain outcome unavailable") } }
        assertTrue(c.state.value.priorQuiescenceUnverified)
        assertEquals(i, store.value)
        refuses { c.markSentUnknown(a, i.action.key) }; refuses { c.cancelNotSent(a, i.action.key) }
    }
    @Test fun priorReconciliationRunsOutsideMutexAndRejectsInterveningWorkEvenIfFinished() = runBlocking {
        val store = Store(); val i = HandoverIntent(action(), priorMutationUncertainty = false); store.begin(i)
        val c = HandoverCoordinator(store); c.initialize(a)
        refuses { c.reconcilePriorQuiescence(a, i) {
            // This would deadlock if the callback held the coordinator mutex.
            val cleanup = c.admitCleanup(a, OperationPurpose.RELEASE_OWN_CHECKOUT, "order:prior")
            c.finish(cleanup, OperationOutcome.NO_WRITE_SENT)
            PriorQuiescenceResult.VERIFIED
        } }
        assertTrue(c.state.value.priorQuiescenceUnverified)
        assertTrue(c.state.value.activeOperations.isEmpty())
        assertTrue(c.reconcilePriorQuiescence(a, i) { PriorQuiescenceResult.VERIFIED })
        c.cancelNotSent(a, i.action.key)
        assertNull(store.value)
        val ordinary = c.admit(a); c.finish(ordinary, OperationOutcome.NO_WRITE_SENT)
        refuses { c.admit(b) }
    }
    @Test fun authorityOrActiveWorkChangeDuringPriorReconciliationRefusesStaleSuccess() = runBlocking {
        val store = Store(); val i = HandoverIntent(action()); store.begin(i)
        val c = HandoverCoordinator(store); c.initialize(a)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val result = async { runCatching { c.reconcilePriorQuiescence(a, i) {
            entered.complete(Unit); release.await(); PriorQuiescenceResult.VERIFIED
        } } }
        entered.await()
        val cleanup = c.admitCleanup(a, OperationPurpose.RECONCILE_OWN_ACTION, "existing-key")
        release.complete(Unit); assertTrue(result.await().isFailure)
        assertTrue(c.state.value.priorQuiescenceUnverified)
        c.finish(cleanup, OperationOutcome.NO_WRITE_SENT)
        val entered2 = CompletableDeferred<Unit>(); val release2 = CompletableDeferred<Unit>()
        val result2 = async { runCatching { c.reconcilePriorQuiescence(a, i) {
            entered2.complete(Unit); release2.await(); PriorQuiescenceResult.VERIFIED
        } } }
        entered2.await(); c.initialize(b); release2.complete(Unit)
        assertTrue(result2.await().isFailure); assertTrue(c.state.value.priorQuiescenceUnverified)
    }
    @Test fun changedOrUnreadableStorageDuringPriorReconciliationKeepsFenceFailClosed() = runBlocking {
        val store = Store(); val i = HandoverIntent(action()); store.begin(i)
        val c = HandoverCoordinator(store); c.initialize(a)
        refuses { c.reconcilePriorQuiescence(a, i) {
            store.value = HandoverIntent(action()); PriorQuiescenceResult.VERIFIED
        } }
        assertTrue(c.state.value.storageFault); assertTrue(c.state.value.priorQuiescenceUnverified)
        refuses { c.markSentUnknown(a, i.action.key) }
        store.value = i; c.initialize(a)
        refuses { c.reconcilePriorQuiescence(a, i) {
            store.failRead = true; PriorQuiescenceResult.VERIFIED
        } }
        assertTrue(c.state.value.storageFault); assertEquals(i, store.value)
    }

}
