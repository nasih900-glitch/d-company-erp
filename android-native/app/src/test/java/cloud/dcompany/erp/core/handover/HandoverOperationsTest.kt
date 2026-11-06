package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.net.MeResponse
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class HandoverOperationsTest {
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { check(value == null); value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { check(value == expected); value = next }
        override suspend fun cancelNotSent(expected: HandoverIntent) { check(value == expected); value = null }
    }
    private class Fixture(branchless: Boolean = false) {
        val store = Store(); val startup = HandoverStartupFence(store)
        var ready: PersistedStartupStateResult? = null
        var profile = MeResponse(id(), "synthetic@example.invalid", "Synthetic Owner", roles = listOf("owner"), companyId = id(), branchId = if (branchless) null else id())
        var cache = CacheScopeLease(CacheScope(profile.userId, profile.companyId, profile.branchId, null), 1)
        var terminal: ValidatedTerminalDisplay? = null
        var cacheAvailable = true
        var session = SessionRefreshLease(StoredSession(token(profile), "synthetic-refresh"), 1)
        val manager = HandoverOperations(store, startup, { ready }, { session },
            { old -> session.accessToken.takeIf { old.session.lineage === session.session.lineage } }, { cache.takeIf { cacheAvailable } }, { profile }, { terminal })
        suspend fun start() { startup.load(); ready = PersistedStartupStateResult.Ready }
        fun capture() = manager.captureOriginal(profile, cache)
    }
    @Test fun rawStartupAndDurableFenceMustBothLoadBeforeAnyCallerCapture() = runBlocking {
        val f = Fixture(); assertNull(f.capture()); assertEquals(HandoverStartupStatus.Loading, f.manager.status())
        f.ready = PersistedStartupStateResult.Ready; assertNull(f.capture())
        f.startup.load(); assertNotNull(f.capture())
    }
    @Test fun rootCallerWaitsForSameLoginWorkspaceThenNeverRebindsToANewLease() = runBlocking {
        val f = Fixture(); f.start(); f.cacheAvailable = false
        val caller = f.manager.deferredCaller(f.profile.userId, f.profile.companyId, f.session)
        var entered = 0
        assertTrue(runCatching { caller.run { entered++ } }.isFailure)
        f.cacheAvailable = true
        caller.run { entered++ }
        assertEquals(1, entered)
        f.cache = f.cache.copy(generation = 2)
        assertTrue(runCatching { caller.run { entered++ } }.isFailure)
        assertEquals(1, entered)
    }
    @Test fun deferredRootCallerCannotBorrowBOrExplicitSameActorReloginBeforeFirstUse() = runBlocking {
        for (differentActor in listOf(false, true)) {
            val f = Fixture(); f.start(); f.cacheAvailable = false
            val caller = f.manager.deferredCaller(f.profile.userId, f.profile.companyId, f.session)
            if (differentActor) f.profile = f.profile.copy(userId = id())
            f.session = SessionRefreshLease(StoredSession(token(f.profile), "synthetic-new-login"), 2)
            f.cache = f.cache.copy(scope = f.cache.scope.copy(userId = f.profile.userId))
            f.cacheAvailable = true
            var entered = false
            assertTrue(runCatching { caller.run { entered = true } }.isFailure)
            assertFalse(entered)
        }
    }
    @Test fun deferredRootCallerFollowsOnlyOriginalLoginTokenRotation() = runBlocking {
        val f = Fixture(); f.start(); f.cacheAvailable = false
        val caller = f.manager.deferredCaller(f.profile.userId, f.profile.companyId, f.session)
        f.session = SessionRefreshLease(StoredSession(token(f.profile), "synthetic-rotated", f.session.session.lineage), 2)
        f.cacheAvailable = true
        caller.run { }
        assertTrue(f.manager.coordinator.state.value.initialized)
    }
    @Test fun tokenRotationKeepsOriginalEpochButExplicitReloginNeverDoes() = runBlocking {
        val f = Fixture(); f.start(); val a = requireNotNull(f.capture())
        f.session = SessionRefreshLease(StoredSession(token(f.profile), "synthetic-rotated", f.session.session.lineage), 2)
        assertTrue(a.isCurrent()); assertSame(a, f.capture())
        f.session = SessionRefreshLease(StoredSession(token(f.profile), "synthetic-explicit-login"), 3)
        assertFalse(a.isCurrent()); val next = requireNotNull(f.capture()); assertTrue(next.authority.epoch > a.authority.epoch)
    }
    @Test fun tokenCompanyOrCacheMismatchCannotBorrowCurrentProfileAuthority() = runBlocking {
        val f = Fixture(); f.start()
        f.session = SessionRefreshLease(StoredSession(token(f.profile.copy(companyId = id())), "synthetic"), 1)
        assertNull(f.capture())
        f.session = SessionRefreshLease(StoredSession(token(f.profile), "synthetic"), 2)
        val wrong = f.cache.copy(scope = f.cache.scope.copy(userId = id()))
        assertNull(f.manager.captureOriginal(f.profile, wrong))
    }
    @Test fun branchlessOwnerIsTrackedWithoutBorrowingATerminalOrTillHandoverAuthority() = runBlocking {
        val f = Fixture(branchless = true); f.start(); val original = requireNotNull(f.capture())
        f.manager.run(original) { OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) }
        assertTrue(f.manager.coordinator.state.value.initialized)
        assertNull(original.authority.scope.branchId); assertNull(original.authority.scope.terminalId)
        assertThrows(IllegalArgumentException::class.java) { HandoverAction(id(), original.authority, id(), id(), 0).validate() }
        Unit
    }
    @Test fun changedModulePermissionOrCacheGenerationInvalidatesAlreadyCapturedACallback() = runBlocking {
        val f = Fixture(); f.start(); val original = requireNotNull(f.capture())
        f.profile = f.profile.copy(accessibleModules = emptyList())
        assertFalse(original.isCurrent()); assertNotSame(original, f.capture())
        val current = requireNotNull(f.capture()); f.cache = f.cache.copy(generation = 2)
        assertFalse(current.isCurrent())
        var ran = false
        assertTrue(runCatching { f.manager.run(current) { ran = true; OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) } }.isFailure)
        assertFalse(ran)
    }
    @Test fun nestedAContinuationCanFinishAfterFreezeWithoutAdmittingANewOrdinaryIntent() = runBlocking {
        val f = Fixture(); f.start()
        val branch = requireNotNull(f.profile.branchId); val terminal = id()
        f.terminal = ValidatedTerminalDisplay(terminal, "Synthetic Till", branch, "hybrid")
        f.cache = f.cache.copy(scope = f.cache.scope.copy(terminalId = terminal))
        val original = requireNotNull(f.capture())
        f.manager.run(original) {
            val intent = f.manager.coordinator.freeze(original.authority, HandoverAction(id(), original.authority, id(), id(), 0))
            f.manager.run(original) { OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) }
            assertTrue(f.manager.coordinator.awaitDrained(intent.action.key, 10) is HandoverDrainResult.Blocked)
            OperationResult(Unit, OperationOutcome.NO_WRITE_SENT)
        }
        val intent = requireNotNull(f.store.value)
        assertTrue(f.manager.coordinator.awaitDrained(intent.action.key, 100) is HandoverDrainResult.Drained)
        assertNull(f.capture())
    }
    @Test fun independentUndispatchedCallbackOnTheSameThreadCannotBorrowItsCallersPermit() = runBlocking {
        val f = Fixture(); f.start()
        f.terminal = ValidatedTerminalDisplay(id(), "Synthetic Till", requireNotNull(f.profile.branchId), "hybrid")
        f.cache = f.cache.copy(scope = f.cache.scope.copy(terminalId = f.terminal?.terminalId))
        val original = requireNotNull(f.capture())
        val external = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            f.manager.run(original) {
                f.manager.coordinator.freeze(original.authority, HandoverAction(id(), original.authority, id(), id(), 0))
                var entered = false; var denied = false
                external.launch(start = CoroutineStart.UNDISPATCHED) {
                    denied = runCatching { f.manager.run(original) { entered = true; OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) } }.isFailure
                }.join()
                assertTrue(denied); assertFalse(entered)
                OperationResult(Unit, OperationOutcome.NO_WRITE_SENT)
            }
        } finally { external.cancel() }
    }
    @Test fun sameAResumptionCapabilityNeverMakesProductionStartupOrOrdinaryFactoryGloballyReady() = runBlocking {
        val f = Fixture(); f.start()
        f.terminal = ValidatedTerminalDisplay(id(), "Synthetic Till", requireNotNull(f.profile.branchId), "hybrid")
        f.cache = f.cache.copy(scope = f.cache.scope.copy(terminalId = f.terminal?.terminalId))
        val original = requireNotNull(f.capture())
        f.manager.run(original) {
            f.manager.coordinator.freeze(original.authority, HandoverAction(id(), original.authority, id(), id(), 0))
            OperationResult(Unit, OperationOutcome.NO_WRITE_SENT)
        }
        f.manager.coordinator.resumeSameActor(original.authority, requireNotNull(f.store.value))
        assertTrue(f.manager.status() is HandoverStartupStatus.RecoveryRequired)
        assertNull(f.capture())
        assertTrue(runCatching { f.manager.run(original) { OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) } }.isFailure)
    }
    private fun known(binding: OriginalOperationBinding) = KnownJournalAttempt(KnownJournalDomain.FINANCE,
        binding.authority.scope, "stored-key", "stored-target", "stored-kind", "a".repeat(64))

    @Test fun finiteFacadeRetiresPriorUnknownOnlyAfterValidatedResponseAndOwnedCheckpoint() = runBlocking<Unit> {
        val f = Fixture(); f.start(); val original = requireNotNull(f.capture()); val key = known(original)
        runCatching { f.manager.runKnownJournalAttempt<Unit>(original, key, {
            val permit = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[OperationDispatchContext]?.permit)
            f.manager.coordinator.beforeMutationDispatch(permit, original.authority)
            throw java.io.IOException("lost reply")
        }, { error("no response, no checkpoint") }) }
        val old = f.manager.coordinator.state.value.unresolvedOperations.single()
        var checkpoint = false
        val result = f.manager.runKnownJournalAttempt(original, key, {
            val permit = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[OperationDispatchContext]?.permit)
            f.manager.coordinator.beforeMutationDispatch(permit, original.authority)
            "validated-exact-response"
        }, { value ->
            assertEquals("validated-exact-response", value)
            assertTrue(old in f.manager.coordinator.state.value.unresolvedOperations)
            checkpoint = true
        })
        assertEquals("validated-exact-response", result); assertTrue(checkpoint)
        assertTrue(f.manager.coordinator.state.value.unresolvedOperations.isEmpty())
    }

    @Test fun finiteFacadeCheckpointFailureAndResponseValidationFailureStayUnknown() = runBlocking<Unit> {
        val f = Fixture(); f.start(); val original = requireNotNull(f.capture()); val key = known(original)
        for (responseValid in listOf(false, true)) {
            var checkpointEntered = false
            assertTrue(runCatching { f.manager.runKnownJournalAttempt(original, key, {
                val permit = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[OperationDispatchContext]?.permit)
                f.manager.coordinator.beforeMutationDispatch(permit, original.authority)
                check(responseValid) { "invalid response" }
                "valid"
            }, { checkpointEntered = true; throw java.io.IOException("owned checkpoint refused") }) }.isFailure)
            assertEquals(responseValid, checkpointEntered)
        }
        assertEquals(2, f.manager.coordinator.state.value.unresolvedOperations.size)
    }

    @Test fun explicitVerifiedSameARebindPreservesUnknownAndRejectsOldCallerAndWrongProfile() = runBlocking<Unit> {
        val f = Fixture(); f.start(); val old = requireNotNull(f.capture()); val key = known(old)
        runCatching { f.manager.runKnownJournalAttempt<Unit>(old, key, {
            val permit = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[OperationDispatchContext]?.permit)
            f.manager.coordinator.beforeMutationDispatch(permit, old.authority); throw java.io.IOException("lost")
        }, { error("unused") }) }
        val unknown = f.manager.coordinator.state.value.unresolvedOperations
        f.session = SessionRefreshLease(StoredSession(token(f.profile), "synthetic-new-login"), 2)
        f.cache = f.cache.copy(generation = 2)
        val fresh = requireNotNull(f.capture()); assertFalse(old.isCurrent())
        // Ordinary bind must not initialize over unknown work based on a cached profile.
        assertTrue(runCatching { f.manager.run(fresh) { OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) } }.isFailure)
        assertTrue(runCatching { f.manager.rebindVerifiedSameActor(fresh, f.profile.copy(userId = id())) }.isFailure)
        assertEquals(unknown, f.manager.coordinator.state.value.unresolvedOperations)
        f.manager.rebindVerifiedSameActor(fresh, f.profile)
        assertEquals(unknown, f.manager.coordinator.state.value.unresolvedOperations)
        assertTrue(runCatching { f.manager.run(old) { OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) } }.isFailure)
        f.manager.runKnownJournalAttempt(fresh, key, {
            val permit = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[OperationDispatchContext]?.permit)
            f.manager.coordinator.beforeMutationDispatch(permit, fresh.authority); "valid"
        }, {})
        assertTrue(f.manager.coordinator.state.value.unresolvedOperations.isEmpty())
    }

    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun token(profile: MeResponse): String {
            val branch = profile.branchId?.let { "\"$it\"" } ?: "null"
            val claims = "{\"sub\":\"${profile.userId}\",\"company_id\":\"${profile.companyId}\",\"branch_id\":$branch}"
            return "synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".fixture"
        }
    }
}
