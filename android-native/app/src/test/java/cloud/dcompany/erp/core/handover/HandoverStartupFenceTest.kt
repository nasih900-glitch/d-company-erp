package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.PersistedStartupFailure
import cloud.dcompany.erp.PersistedStartupStateResult
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HandoverStartupFenceTest {
    private val scope = HandoverScope("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    private fun intent() = HandoverIntent(HandoverAction(UUID.randomUUID().toString(), HandoverAuthority(scope, 1),
        "66666666-6666-4666-8666-666666666666", "55555555-5555-4555-8555-555555555555", 0))
    private class Store : HandoverIntentStore {
        var value: HandoverIntent? = null
        var read: (suspend () -> HandoverIntent?)? = null
        override suspend fun read() = read?.invoke() ?: value
        override suspend fun begin(intent: HandoverIntent) { error("Startup must not mutate a retained fence") }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { error("Startup cannot resolve a handover") }
        override suspend fun cancelNotSent(expected: HandoverIntent) { error("Startup must not cancel a handover") }
    }
    @Test fun unsetStartupAdapterNeverPermitsColdWorkerOrOperationalActivation() {
        val fence = HandoverStartupFence(Store())
        assertThrows(IllegalStateException::class.java) { fence.requireOperationalReadiness() }
        assertEquals(PersistedStartupStateResult.Unavailable(PersistedStartupFailure.STORAGE),
            persistedStartupWithHandoverFence(PersistedStartupStateResult.Ready, fence.state.value))
    }
    @Test fun positivelyReadEmptyStorePreservesNormalStartup() = runBlocking {
        val fence = HandoverStartupFence(Store()); fence.load(); fence.requireOperationalReadiness()
        assertEquals(PersistedStartupStateResult.Ready,
            persistedStartupWithHandoverFence(PersistedStartupStateResult.Ready, fence.state.value))
    }
    @Test fun notSentUnknownAndAcceptedFencesAllBlockCachedScopeAdoptionWithoutDeletingRecord() = runBlocking {
        val base = intent()
        val receipt = AcceptedHandover(UUID.randomUUID().toString(), base.action.key, scope.companyId, requireNotNull(scope.branchId),
            requireNotNull(scope.terminalId), base.action.shiftId, scope.actorId, base.action.incomingUserId, 0, 1)
        for (i in listOf(base, base.copy(stage = HandoverStage.SENT_UNKNOWN), base.copy(stage = HandoverStage.ACCEPTED, receipt = receipt))) {
            val store = Store().also { it.value = i }; val fence = HandoverStartupFence(store); fence.load()
            assertThrows(IllegalStateException::class.java) { fence.requireOperationalReadiness() }
            assertEquals(i, store.value)
            assertEquals(PersistedStartupStateResult.Unavailable(PersistedStartupFailure.HANDOVER_RECOVERY),
                persistedStartupWithHandoverFence(PersistedStartupStateResult.Ready, fence.state.value))
        }
    }
    @Test fun unreadableOrMalformedStoreDoesNotBecomeNoFence() = runBlocking {
        val store = Store(); val fence = HandoverStartupFence(store)
        store.read = { throw IOException("unreadable") }
        assertTrue(runCatching { fence.load() }.isFailure)
        assertEquals(HandoverStartupStatus.Unavailable, fence.state.value)
        store.read = null; store.value = intent().copy(action = intent().action.copy(key = "malformed"))
        assertTrue(runCatching { fence.load() }.isFailure)
        assertEquals(HandoverStartupStatus.Unavailable, fence.state.value)
    }
    @Test fun concurrentColdAndForegroundWaitersCannotObserveReadyBeforeFenceReadCompletes() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val store = Store()
        val retained = intent(); store.read = { entered.complete(Unit); release.await(); retained }
        val fence = HandoverStartupFence(store); val load = async { fence.load() }; entered.await()
        repeat(2) { assertEquals(PersistedStartupStateResult.Unavailable(PersistedStartupFailure.STORAGE),
            persistedStartupWithHandoverFence(PersistedStartupStateResult.Ready, fence.state.value)) }
        release.complete(Unit); load.await()
        assertEquals(PersistedStartupStateResult.Unavailable(PersistedStartupFailure.HANDOVER_RECOVERY),
            persistedStartupWithHandoverFence(PersistedStartupStateResult.Ready, fence.state.value))
    }
    @Test fun canceledFenceLoadRemainsUnavailable() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val wait = CompletableDeferred<Unit>(); val store = Store()
        store.read = { entered.complete(Unit); wait.await(); null }
        val fence = HandoverStartupFence(store); val job = launch { fence.load() }; entered.await(); job.cancelAndJoin()
        assertEquals(HandoverStartupStatus.Unavailable, fence.state.value)
    }
    @Test fun existingTimeoutOrStorageFailureIsNeverPromotedToReady() {
        for (error in listOf(PersistedStartupFailure.TIMEOUT, PersistedStartupFailure.STORAGE)) {
            val old = PersistedStartupStateResult.Unavailable(error)
            assertEquals(old, persistedStartupWithHandoverFence(old, HandoverStartupStatus.Open))
        }
    }
}
