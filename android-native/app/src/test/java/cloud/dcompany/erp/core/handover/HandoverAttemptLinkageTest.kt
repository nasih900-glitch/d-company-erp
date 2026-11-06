package cloud.dcompany.erp.core.handover

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HandoverAttemptLinkageTest {
    private val scope = HandoverScope("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    private val a = HandoverAuthority(scope, 1)
    private val next = HandoverAuthority(scope, 2)
    private val identity = KnownJournalAttempt(KnownJournalDomain.FINANCE, scope, "stored-key", "stored-target", "stored-kind", "a".repeat(64))
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null; var failRead = false; var failCas = false
        var duringRead: (() -> Unit)? = null
        override suspend fun read(): HandoverIntent? { if (failRead) throw IOException("read failed"); duringRead?.invoke(); return value }
        override suspend fun begin(intent: HandoverIntent) { check(value == null); value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) {
            if (failCas) throw IOException("fsync failed")
            check(value == expected); validateHandoverTransition(expected, next); value = next
        }
        override suspend fun cancelNotSent(expected: HandoverIntent) { check(value == expected); value = null }
    }
    private suspend fun coordinator(store: Store = Store()) = HandoverCoordinator(store).also { it.initialize(a) }
    private suspend fun refuses(block: suspend () -> Unit) { assertTrue(runCatching { block() }.isFailure) }
    private suspend fun lost(c: HandoverCoordinator, key: KnownJournalAttempt = identity): HandoverCoordinator.Permit {
        val p = c.admit(a); c.bindKnownAttempt(p, key); c.beforeMutationDispatch(p, a); c.finish(p, OperationOutcome.UNRESOLVED); return p
    }
    private suspend fun retry(c: HandoverCoordinator, key: KnownJournalAttempt = identity, authority: HandoverAuthority = a,
        checkpoint: suspend () -> Unit = {}) {
        val p = c.admit(authority); c.bindKnownAttempt(p, key); c.beforeMutationDispatch(p, authority)
        c.checkpointKnownAttempt(p, key, { true }, checkpoint); c.finish(p, OperationOutcome.DURABLE_OUTCOME)
    }
    @Test fun exactValidatedRetryCheckpointRetiresOnlyItsPriorFinishedAttempt() = runBlocking<Unit> {
        val c = coordinator(); val old = lost(c); val other = lost(c, identity.copy(actionKey = "other"))
        var committed = false; retry(c, checkpoint = { committed = true })
        assertTrue(committed); assertEquals(setOf(other.id), c.state.value.unresolvedOperations); assertFalse(old.id in c.state.value.activeOperations)
    }
    @Test fun everyDifferentSemanticFieldLeavesOriginalUnknownUntouched() = runBlocking<Unit> {
        for (different in listOf(identity.copy(actionKey = "other"), identity.copy(target = "other"), identity.copy(kind = "other"),
            identity.copy(storedIntentSha256 = "b".repeat(64)), identity.copy(domain = KnownJournalDomain.STAFF_TIME))) {
            val c = coordinator(); val old = lost(c); retry(c, different)
            assertEquals(setOf(old.id), c.state.value.unresolvedOperations)
        }
    }
    @Test fun foreignScopeCannotBindOrRunCheckpoint() = runBlocking<Unit> {
        val c = coordinator(); val p = c.admit(a); var checkpoint = false
        val foreign = identity.copy(scope = scope.copy(companyId = UUID.randomUUID().toString()))
        refuses { c.bindKnownAttempt(p, foreign) }; refuses { c.checkpointKnownAttempt(p, foreign, { true }) { checkpoint = true } }
        assertFalse(checkpoint); c.finish(p, OperationOutcome.NO_WRITE_SENT)
    }
    @Test fun noBindingAfterWireOrMutationChildHistoryAndNoIdentityReplacement() = runBlocking<Unit> {
        val c = coordinator(); val p = c.admit(a); c.beforeMutationDispatch(p, a)
        refuses { c.bindKnownAttempt(p, identity) }; c.finish(p, OperationOutcome.DURABLE_OUTCOME)
        val root = c.admit(a); val child = c.child(root); c.beforeMutationDispatch(child, a); c.finish(child, OperationOutcome.DURABLE_OUTCOME)
        refuses { c.bindKnownAttempt(root, identity) }; c.finish(root, OperationOutcome.DURABLE_OUTCOME)
        val linked = c.admit(a); c.bindKnownAttempt(linked, identity); c.bindKnownAttempt(linked, identity)
        refuses { c.bindKnownAttempt(linked, identity.copy(actionKey = "other")) }; refuses { c.child(linked) }
        c.finish(linked, OperationOutcome.NO_WRITE_SENT)
    }
    @Test fun linkedWireCannotUseGenericFinishResolveOrAnotherPhysicalMutationAsProof() = runBlocking<Unit> {
        val c = coordinator(); val p = c.admit(a); c.bindKnownAttempt(p, identity); c.beforeMutationDispatch(p, a)
        refuses { c.beforeMutationDispatch(p, a) }; refuses { c.finish(p, OperationOutcome.DURABLE_OUTCOME) }
        c.finish(p, OperationOutcome.UNRESOLVED); refuses { c.resolve(a, p.id, OperationOutcome.DURABLE_OUTCOME) }
        assertEquals(setOf(p.id), c.state.value.unresolvedOperations)
    }
    @Test fun emptyMissingOrRejectedCheckpointIsNotSettlement() = runBlocking<Unit> {
        val c = coordinator(); val old = lost(c); val p = c.admit(a); c.bindKnownAttempt(p, identity)
        refuses { c.checkpointKnownAttempt(p, identity, { true }) { error("must not run before dispatch") } }
        c.beforeMutationDispatch(p, a)
        refuses { c.checkpointKnownAttempt(p, identity, { true }) { throw IOException("owned CAS refused") } }
        assertTrue(old.id in c.state.value.unresolvedOperations); assertTrue(p.id in c.state.value.activeOperations)
        c.finish(p, OperationOutcome.UNRESOLVED)
    }
    @Test fun activeMatchingAttemptIsNeverRetiredBySuccessfulRetry() = runBlocking<Unit> {
        val c = coordinator(); val active = c.admit(a); c.bindKnownAttempt(active, identity); c.beforeMutationDispatch(active, a)
        retry(c); assertEquals(setOf(active.id), c.state.value.activeOperations)
        c.finish(active, OperationOutcome.UNRESOLVED)
    }
    @Test fun matchingActiveOrLaterFailureWasNotAPriorFinishedUnknownAtBinding() = runBlocking<Unit> {
        val c = coordinator(); val earlierActive = c.admit(a); c.bindKnownAttempt(earlierActive, identity); c.beforeMutationDispatch(earlierActive, a)
        val retry = c.admit(a); c.bindKnownAttempt(retry, identity); c.beforeMutationDispatch(retry, a)
        c.checkpointKnownAttempt(retry, identity, { true }) { c.finish(earlierActive, OperationOutcome.UNRESOLVED) }
        c.finish(retry, OperationOutcome.DURABLE_OUTCOME)
        assertEquals(setOf(earlierActive.id), c.state.value.unresolvedOperations)
    }
    @Test fun failedReadOnlyParentAfterAllKnownChildrenCheckpointDoesNotInventMoneyUncertainty() = runBlocking<Unit> {
        val store = Store(); val c = coordinator(store)
        runCatching { c.runOperation<Unit>(a) { root ->
            c.runChildOperation(root) { p ->
                c.bindKnownAttempt(p, identity); c.beforeMutationDispatch(p, a)
                c.freeze(a, HandoverAction(UUID.randomUUID().toString(), a, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0))
                c.checkpointKnownAttempt(p, identity, { true }) {}
                OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME)
            }
            throw IOException("later read or presentation failed")
        } }
        assertTrue(c.state.value.activeOperations.isEmpty()); assertTrue(c.state.value.unresolvedOperations.isEmpty())
        assertFalse(requireNotNull(store.value).priorMutationUncertainty)
    }

    @Test fun actualFailedChildOnlyParentPrunesOnlyAfterAllItsUnknownSiblingsResolve() = runBlocking<Unit> {
        val c = coordinator(); var parentId = ""; var firstId = ""; var secondId = ""
        runCatching { c.runOperation<Unit>(a) { root ->
            parentId = root.id
            for (key in listOf(identity, identity.copy(actionKey = "sibling"))) {
                runCatching { c.runChildOperation<Unit>(root) { p ->
                    if (key == identity) firstId = p.id else secondId = p.id
                    c.bindKnownAttempt(p, key); c.beforeMutationDispatch(p, a); throw IOException("lost child response")
                } }
            }
            OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME)
        } }
        assertEquals(setOf(parentId, firstId, secondId), c.state.value.unresolvedOperations)
        retry(c); assertEquals(setOf(parentId, secondId), c.state.value.unresolvedOperations)
        retry(c, identity.copy(actionKey = "sibling")); assertTrue(c.state.value.unresolvedOperations.isEmpty())
    }
    @Test fun successfulKnownChildCannotRetireDirectOrUnclassifiedSiblingOrExplicitParentUnknown() = runBlocking<Unit> {
        for (directParent in listOf(true, false)) {
            val c = coordinator(); val parent = c.admit(a); val child = c.child(parent)
            c.bindKnownAttempt(child, identity); c.beforeMutationDispatch(child, a); c.finish(child, OperationOutcome.UNRESOLVED)
            if (directParent) c.beforeMutationDispatch(parent, a)
            c.finish(parent, OperationOutcome.UNRESOLVED)
            val unclassified = c.admit(a); c.beforeMutationDispatch(unclassified, a); c.finish(unclassified, OperationOutcome.UNRESOLVED)
            retry(c); assertEquals(setOf(parent.id, unclassified.id), c.state.value.unresolvedOperations)
        }
    }
    @Test fun failedParentWithOwnWireRemainsUnknownAfterItsKnownChildRecovers() = runBlocking<Unit> {
        val c = coordinator(); var parent = ""
        runCatching { c.runOperation<Unit>(a) { root ->
            parent = root.id; c.beforeMutationDispatch(root, a)
            c.runChildOperation<Unit>(root) { p -> c.bindKnownAttempt(p, identity); c.beforeMutationDispatch(p, a); throw IOException("lost") }
            error("unreachable")
        } }
        retry(c); assertEquals(setOf(parent), c.state.value.unresolvedOperations)
    }
    @Test fun checkpointRunsOutsideMutexAndCancellationWaitsOnlyForLocalCheckpoint() = runBlocking<Unit> {
        val c = coordinator(); val old = lost(c); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var committed = false
        val job = launch { c.runOperation(a) { p ->
            c.bindKnownAttempt(p, identity); c.beforeMutationDispatch(p, a)
            c.checkpointKnownAttempt(p, identity, { true }) {
                entered.complete(Unit); release.await(); c.requireContinuation(p, a); committed = true
            }
            OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME)
        } }
        entered.await(); job.cancel(); assertFalse(job.isCompleted); assertTrue(old.id in c.state.value.unresolvedOperations)
        release.complete(Unit); job.join()
        assertTrue(committed); assertTrue(c.state.value.activeOperations.isEmpty()); assertTrue(c.state.value.unresolvedOperations.isEmpty())
    }
    @Test fun authorityDriftAfterCheckpointDoesNotRetirePriorUnknown() = runBlocking<Unit> {
        val c = coordinator(); val old = lost(c); val p = c.admit(a); c.bindKnownAttempt(p, identity); c.beforeMutationDispatch(p, a)
        var current = true
        refuses { c.checkpointKnownAttempt(p, identity, { current }) { current = false } }
        assertEquals(setOf(old.id), c.state.value.unresolvedOperations); c.finish(p, OperationOutcome.UNRESOLVED)
    }
    @Test fun checkpointFsFailurePreservesCurrentAndOldLifetime() = runBlocking<Unit> {
        val c = coordinator(); val old = lost(c)
        runCatching { c.runOperation<Unit>(a) { p ->
            c.bindKnownAttempt(p, identity); c.beforeMutationDispatch(p, a)
            c.checkpointKnownAttempt(p, identity, { true }) { throw IOException("fsync") }
            OperationResult(Unit, OperationOutcome.DURABLE_OUTCOME)
        } }
        assertEquals(2, c.state.value.unresolvedOperations.size); assertTrue(old.id in c.state.value.unresolvedOperations)
    }
    @Test fun markerClearFailureCannotRemoveCheckpointedCurrentWorkBeforeFailedCleanup() = runBlocking<Unit> {
        val store = Store(); val c = coordinator(store)
        val p = c.admit(a); c.bindKnownAttempt(p, identity); c.beforeMutationDispatch(p, a)
        val action = HandoverAction(UUID.randomUUID().toString(), a, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0)
        c.freeze(a, action); c.checkpointKnownAttempt(p, identity, { true }) {}
        store.failCas = true; refuses { c.finish(p, OperationOutcome.DURABLE_OUTCOME) }
        assertTrue(p.id in c.state.value.activeOperations); assertTrue(c.state.value.storageFault); assertTrue(requireNotNull(store.value).priorMutationUncertainty)
    }
    @Test fun realPrivateFileCheckpointRequiresExactRetainedBytesBeforeRetiringUnknown() = runBlocking<Unit> {
        val dir = java.nio.file.Files.createTempDirectory("known-attempt-core-").toFile()
        try {
            val retained = java.io.File(dir, "synthetic-owned-intent"); val bytes = "exact-nonsecret-stored-intent".toByteArray()
            retained.writeBytes(bytes)
            val key = identity.copy(storedIntentSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val c = coordinator(); val old = lost(c, key)
            retained.writeText("different-intent")
            val wrong = c.admit(a); c.bindKnownAttempt(wrong, key); c.beforeMutationDispatch(wrong, a)
            refuses { c.checkpointKnownAttempt(wrong, key, { true }) { check(retained.readBytes().contentEquals(bytes)); retained.delete() } }
            assertTrue(old.id in c.state.value.unresolvedOperations); c.finish(wrong, OperationOutcome.UNRESOLVED)
            retained.writeBytes(bytes)
            retry(c, key, checkpoint = {
                check(retained.readBytes().contentEquals(bytes))
                java.io.FileOutputStream(java.io.File(dir, "synthetic-exact-receipt")).use { out -> out.write("validated".toByteArray()); out.fd.sync() }
                check(retained.delete())
            })
            assertTrue(c.state.value.unresolvedOperations.isEmpty()); assertEquals("validated", java.io.File(dir, "synthetic-exact-receipt").readText())
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }

    @Test fun freshSameARebindPreservesOldIdsAndAllowsOnlyExactKnownRecoveryUnderNewEpoch() = runBlocking<Unit> {
        val c = coordinator(); val old = lost(c); val generic = c.admit(a); c.beforeMutationDispatch(generic, a); c.finish(generic, OperationOutcome.UNRESOLVED)
        c.rebindVerifiedSameActor(a, next) { true }
        assertEquals(a, old.authority); assertEquals(setOf(old.id, generic.id), c.state.value.unresolvedOperations)
        refuses { c.requireContinuation(old, next) }; refuses { c.beforeMutationDispatch(old, a) }; refuses { c.resolve(next, old.id, OperationOutcome.DURABLE_OUTCOME) }
        retry(c, authority = next); assertEquals(setOf(generic.id), c.state.value.unresolvedOperations)
    }
    @Test fun sameARebindRejectsActiveChildSupportForeignScopeStaleEpochAndUnverifiedAuthority() = runBlocking<Unit> {
        val c = coordinator(); val parent = c.admit(a); val child = c.child(parent)
        refuses { c.rebindVerifiedSameActor(a, next) { true } }
        c.finish(child, OperationOutcome.NO_WRITE_SENT); c.finish(parent, OperationOutcome.NO_WRITE_SENT)
        val support = c.admitOwnerSupport(scope.companyId, scope.actorId) { true }
        refuses { c.rebindVerifiedSameActor(a, next) { true } }; c.finishOwnerSupport(support)
        for (wrong in listOf(next.copy(scope = scope.copy(actorId = UUID.randomUUID().toString())), next.copy(scope = scope.copy(branchId = null)), a)) {
            refuses { c.rebindVerifiedSameActor(a, wrong) { true } }
        }
        refuses { c.rebindVerifiedSameActor(a, next) { false } }
        c.rebindVerifiedSameActor(a, next) { true }; refuses { c.admit(a) }
    }
    @Test fun freshAuthorityLostDuringRetainedReadAndDiskDriftCannotAdvanceEpoch() = runBlocking<Unit> {
        val store = Store(); val c = coordinator(store); val old = lost(c); var fresh = true
        store.duringRead = { fresh = false }
        refuses { c.rebindVerifiedSameActor(a, next) { fresh } }
        store.duringRead = null
        val stillA = c.admit(a); c.finish(stillA, OperationOutcome.NO_WRITE_SENT)
        assertEquals(setOf(old.id), c.state.value.unresolvedOperations)
        store.value = HandoverIntent(HandoverAction(UUID.randomUUID().toString(), a, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0))
        refuses { c.rebindVerifiedSameActor(a, next) { true } }
        assertTrue(c.state.value.storageFault); assertEquals(setOf(old.id), c.state.value.unresolvedOperations)
    }

    @Test fun retainedStickyLegacyUncertaintyAndStorageFaultAreNeverClearedByRebind() = runBlocking<Unit> {
        for (sticky in listOf(false, true)) {
            val store = Store(); val action = HandoverAction(UUID.randomUUID().toString(), a, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0)
            store.value = HandoverIntent(action, priorMutationUncertainty = sticky)
            val c = coordinator(store); val before = c.state.value
            c.rebindVerifiedSameActor(a, next) { true }
            assertEquals(before, c.state.value); assertEquals(store.value, c.state.value.intent)
            assertTrue(c.state.value.priorQuiescenceUnverified); refuses { c.markSentUnknown(next, action.key) }
        }
        val store = Store(); val c = coordinator(store); store.failRead = true
        refuses { c.rebindVerifiedSameActor(a, next) { true } }; assertTrue(c.state.value.storageFault)
        store.failRead = false; c.rebindVerifiedSameActor(a, next) { true }; assertTrue(c.state.value.storageFault); refuses { c.admit(next) }
    }
}
