package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.net.MeResponse
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real manager/coordinator/domain adapter with explicit synthetic private stores/API, no Android session. */
class OriginalHandoverDomainsTest {
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        override suspend fun read() = value
        override suspend fun begin(intent: HandoverIntent) { check(value == null); value = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { check(value == expected); value = next }
        override suspend fun cancelNotSent(expected: HandoverIntent) { check(value == expected); value = null }
    }
    private class Fixture {
        val store = Store(); val startup = HandoverStartupFence(store)
        var profile = MeResponse(id(), "synthetic@example.invalid", "Synthetic A", listOf("owner"), companyId = id(), branchId = id())
        val terminal = ValidatedTerminalDisplay(id(), "Synthetic till", requireNotNull(profile.branchId), "hybrid")
        var cache = CacheScopeLease(CacheScope(profile.userId, profile.companyId, profile.branchId, terminal.terminalId), 1)
        var session = SessionRefreshLease(StoredSession(token(profile), "synthetic-refresh"), 1)
        fun manager() = HandoverOperations(store, startup, { PersistedStartupStateResult.Ready }, { session },
            { old -> session.accessToken.takeIf { old.session.lineage === session.session.lineage } }, { cache }, { profile }, { terminal })
        var operations = manager()
        lateinit var original: OriginalOperationBinding
        lateinit var expected: HandoverIntent
        lateinit var cap: HandoverCoordinator.Preparation
        var local = LocalHandoverWork(true, emptySet())
        var workersSettled = true
        val claims = IdleCheckoutClaims()
        var own = linkedSetOf<String>()
        var releases = 0
        var failState = false
        var loseDeleteReply = false
        var failReadAfterDelete = false
        var wrongRevision = false
        var changeActorDuringRead = false
        val api = object : HandoverStateApi {
            override suspend fun me(): MeResponse {
                val permit = requireNotNull(currentCoroutineContext()[OperationDispatchContext]?.permit)
                assertTrue(permit.purpose in setOf(OperationPurpose.OBSERVE_HANDOVER_STATE, OperationPurpose.RELEASE_OWN_CHECKOUT))
                return profile
            }
            override suspend fun state(shiftId: String): OriginalOperatorState {
                assertEquals(expected.action.shiftId, shiftId)
                if (failState || (failReadAfterDelete && releases > 0)) throw IOException("Synthetic unavailable state")
                if (changeActorDuringRead) profile = profile.copy(userId = id())
                return OriginalOperatorState(shiftId, "open", null, if (wrongRevision) 1 else 0, "untracked", own.toList())
            }
            override suspend fun release(orderId: String, token: String) {
                val context = requireNotNull(currentCoroutineContext()[OperationDispatchContext])
                val permit = requireNotNull(context.permit)
                assertEquals(OperationPurpose.RELEASE_OWN_CHECKOUT, permit.purpose)
                assertEquals(orderId, permit.existingAction)
                assertEquals("synthetic-private-claim", token)
                operations.coordinator.beforeMutationDispatch(permit, context.binding.authority)
                releases++; own.remove(orderId)
                if (loseDeleteReply) throw IOException("Synthetic DELETE committed, reply lost")
            }
        }
        val domains get() = OriginalHandoverDomains(operations, claims, { local }, { api }, { _, _ -> workersSettled })
        suspend fun start(restored: Boolean = false, uncertain: Boolean = false, normalPreparation: Boolean = false) {
            startup.load(); original = requireNotNull(operations.captureOriginal(profile, cache))
            if (normalPreparation) {
                val action = HandoverAction(id(), original.authority, id(), id(), 0)
                cap = operations.prepare(original, action)
                expected = HandoverIntent(action, priorMutationUncertainty = false) // Identity fixture only; not written.
                return
            }
            operations.run(original) {
                expected = operations.coordinator.freeze(original.authority, HandoverAction(id(), original.authority, id(), id(), 0))
                OperationResult(Unit, OperationOutcome.NO_WRITE_SENT)
            }
            if (restored || uncertain) {
                if (uncertain) { expected = expected.copy(priorMutationUncertainty = true); store.value = expected }
                session = SessionRefreshLease(StoredSession(token(profile), "synthetic-recreated-refresh"), 2)
                cache = cache.copy(generation = 2)
                startup.load(); operations = manager()
                assertNull(operations.captureOriginal(profile, cache))
                original = requireNotNull(operations.captureForRecovery(profile, cache, expected))
            }
        }
        fun knownClaim(abandoned: Boolean = true): String = id().also {
            own += it; claims.remember(original, it, "synthetic-private-claim")
            if (abandoned) claims.markAbandoned(original.authority.scope, it, "synthetic-private-claim")
        }
    }
    @Test fun realAdapterVerifiesWarmAndRestoredExactOriginWithoutMakingStartupGloballyOpen() = runBlocking {
        for (restored in listOf(false, true)) {
            val f = Fixture(); f.start(restored)
            assertTrue(f.domains.inspect(f.original, f.expected) is OriginalDomainCheck.Clear)
            assertTrue(f.operations.status() is HandoverStartupStatus.RecoveryRequired)
            assertTrue(f.operations.coordinator.state.value.activeOperations.isEmpty())
            assertFalse(f.operations.coordinator.state.value.priorQuiescenceUnverified)
        }
    }
    @Test fun localPrivateDraftOrPendingPaymentPreventsAnyIdleClaimRelease() = runBlocking {
        val f = Fixture(); f.start(); f.knownClaim()
        f.local = LocalHandoverWork(true, setOf(LocalHandoverWorkKind.ROOM))
        assertEquals(OriginalDomainBlock.LOCAL_WORK, (f.domains.releaseKnownIdleClaims(f.original, f.expected) as OriginalDomainCheck.Blocked).reason)
        assertEquals(0, f.releases); assertEquals(1, f.own.size)
    }
    @Test fun knownIdleClaimReleaseIsAwaitedAndLostReplyRequiresFreshOwnClaimAbsence() = runBlocking {
        val f = Fixture(); f.start(); val order = f.knownClaim(); f.loseDeleteReply = true
        assertTrue(f.domains.releaseKnownIdleClaims(f.original, f.expected) is OriginalDomainCheck.Clear)
        assertEquals(1, f.releases); assertNull(f.claims.known(f.original.authority.scope, order))
        assertTrue(f.operations.coordinator.state.value.activeOperations.isEmpty())
        assertFalse(requireNotNull(f.store.value).priorMutationUncertainty)
    }
    @Test fun unknownAcquireHasNoInventedTokenOrExpiryProof() = runBlocking {
        val f = Fixture(); f.start(); f.own += id()
        assertEquals(OriginalDomainBlock.UNKNOWN_CHECKOUT, (f.domains.releaseKnownIdleClaims(f.original, f.expected) as OriginalDomainCheck.Blocked).reason)
        assertEquals(0, f.releases)
    }
    @Test fun failedReadAfterDeleteRetainsHandleAndUnresolvedMutationInsteadOfClaimingCleanup() = runBlocking {
        val f = Fixture(); f.start(); val order = f.knownClaim(); f.failReadAfterDelete = true
        assertTrue(f.domains.releaseKnownIdleClaims(f.original, f.expected) is OriginalDomainCheck.Blocked)
        assertNotNull(f.claims.known(f.original.authority.scope, order))
        assertTrue(requireNotNull(f.store.value).priorMutationUncertainty)
        assertTrue(f.operations.coordinator.state.value.unresolvedOperations.isNotEmpty())
    }
    @Test fun unavailableRevisionChangedOrNewActorReadbackNeverProvesAbsence() = runBlocking {
        for (kind in listOf("unavailable", "revision", "actor")) {
            val f = Fixture(); f.start(restored = true)
            when (kind) { "unavailable" -> f.failState = true; "revision" -> f.wrongRevision = true; else -> f.changeActorDuringRead = true }
            assertTrue(f.domains.inspect(f.original, f.expected) is OriginalDomainCheck.Blocked)
            assertTrue(f.operations.coordinator.state.value.priorQuiescenceUnverified)
            assertTrue(f.operations.coordinator.state.value.activeOperations.isEmpty())
        }
    }
    @Test fun observedCleanDomainsCannotEraseRestoredUnknownDirectMutation() = runBlocking {
        val f = Fixture(); f.start(restored = true, uncertain = true)
        assertTrue(f.domains.inspect(f.original, f.expected) is OriginalDomainCheck.Clear)
        assertTrue(f.operations.coordinator.state.value.mutationUncertaintySticky)
        assertTrue(requireNotNull(f.store.value).priorMutationUncertainty)
        assertTrue(runCatching { f.operations.coordinator.markSentUnknown(f.original.authority, f.expected.action.key) }.isFailure)
    }
    @Test fun claimHandleIsRedactedAndLateOldAHandleCannotBeRecordedAsB() = runBlocking {
        val f = Fixture(); f.start(); val order = f.knownClaim()
        assertFalse(requireNotNull(f.claims.known(f.original.authority.scope, order)).toString().contains("synthetic-private-claim"))
        f.profile = f.profile.copy(userId = id())
        assertTrue(runCatching { f.claims.remember(f.original, id(), "synthetic-private-claim") }.isFailure)
        assertNotNull(f.claims.known(f.original.authority.scope, order))
    }
    @Test fun freshAbsenceCannotForgetALateOrRenewedClaimHandle() = runBlocking {
        val f = Fixture(); f.start()
        val oldOrder = f.knownClaim()
        val observed = f.claims.snapshot(f.original.authority.scope)
        f.claims.remember(f.original, oldOrder, "synthetic-renewed-token")
        val late = id(); f.claims.remember(f.original, late, "synthetic-late-token")
        f.claims.observeAbsent(f.original.authority.scope, emptySet(), observed)
        assertEquals("synthetic-renewed-token", f.claims.known(f.original.authority.scope, oldOrder)?.token)
        assertNotNull(f.claims.known(f.original.authority.scope, late))
    }
    @Test fun normalPreparationCleansKnownIdleClaimsBeforeAnyDurableIntentThenRechecksAndPersists() = runBlocking {
        val f = Fixture(); f.start(normalPreparation = true); f.knownClaim(); f.loseDeleteReply = true
        assertEquals(HandoverStartupStatus.Preparing, f.operations.status())
        assertNull(f.store.value)
        assertTrue(f.domains.releasePreparedKnownIdleClaims(f.original, f.cap))
        assertEquals(1, f.releases); assertNull(f.store.value)
        val result = f.domains.persistPrepared(f.original, f.cap)
        assertTrue(result is PreparedDomainCheck.RetainedNotSent)
        val retained = (result as PreparedDomainCheck.RetainedNotSent).intent
        assertEquals(retained, f.store.value); assertFalse(retained.priorMutationUncertainty)
        assertNull(f.operations.coordinator.state.value.preparationAction)
        assertTrue(f.operations.status() is HandoverStartupStatus.RecoveryRequired)
        assertEquals(HandoverStage.SENT_UNKNOWN, f.operations.coordinator.markSentUnknown(f.original.authority, retained.action.key).stage)
    }
    @Test fun noKnownHandleIsNotProofThatAllServerClaimsAreClear() = runBlocking {
        val f = Fixture(); f.start(normalPreparation = true); f.own += id()
        assertTrue(f.domains.releasePreparedKnownIdleClaims(f.original, f.cap))
        assertTrue(f.domains.persistPrepared(f.original, f.cap) is PreparedDomainCheck.Blocked)
        assertEquals(0, f.releases); assertNull(f.store.value)
        assertEquals(HandoverStartupStatus.Preparing, f.operations.status())
        f.operations.abortPreparation(f.original, f.cap)
        assertEquals(HandoverStartupStatus.Open, f.operations.status())
    }
    @Test fun actualPreparedDomainRefusesToPersistWhenWorkerLifetimeJoinIsUnverified() = runBlocking {
        val f = Fixture(); f.start(normalPreparation = true); f.workersSettled = false
        assertFalse(f.domains.releasePreparedKnownIdleClaims(f.original, f.cap))
        assertTrue(f.domains.persistPrepared(f.original, f.cap) is PreparedDomainCheck.Blocked)
        assertNull(f.store.value); assertEquals(HandoverStartupStatus.Preparing, f.operations.status())
    }
    @Test fun activeUnconfirmedCheckoutMustBeExplicitlyClosedBeforeCleanupOrDurableSwitchIntent() = runBlocking {
        val f = Fixture(); f.start(normalPreparation = true); val order = f.knownClaim(abandoned = false)
        assertFalse(f.domains.releasePreparedKnownIdleClaims(f.original, f.cap))
        val held = f.domains.persistPrepared(f.original, f.cap) as PreparedDomainCheck.Blocked
        assertEquals(OriginalDomainBlock.OPEN_CHECKOUT, held.check.reason)
        assertEquals(0, f.releases); assertNull(f.store.value)
        f.claims.markAbandoned(f.original.authority.scope, order, "synthetic-private-claim")
        assertTrue(f.domains.releasePreparedKnownIdleClaims(f.original, f.cap))
        assertTrue(f.domains.persistPrepared(f.original, f.cap) is PreparedDomainCheck.RetainedNotSent)
    }
    companion object {
        private fun id() = UUID.randomUUID().toString()
        private fun token(profile: MeResponse): String {
            val raw = "{\"sub\":\"${profile.userId}\",\"company_id\":\"${profile.companyId}\",\"branch_id\":\"${profile.branchId}\"}"
            return "synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray()) + ".fixture"
        }
    }
}
