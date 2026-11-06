package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class OriginalOperationRoomFenceTest {
    private class Marker(var value: CacheScope?) : CacheScopeMarker {
        override fun current() = value
        override fun remember(scope: CacheScope): Boolean { value = scope; return true }
        override fun clear(): Boolean { value = null; return true }
    }
    private class Purger : ScopeDataPurger {
        override suspend fun hasUnresolvedWork() = false
        override suspend fun purgeIfClean() = true
    }
    private class Store : HandoverIntentStore {
        override suspend fun read(): HandoverIntent? = null
        override suspend fun begin(intent: HandoverIntent): Unit = error("unused")
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent): Unit = error("unused")
        override suspend fun cancelNotSent(expected: HandoverIntent): Unit = error("unused")
    }
    private class Fixture {
        val a = CacheScope(id(), id(), id(), id())
        val cache = CacheIsolationCoordinator(Purger(), Marker(a))
        val handover = HandoverCoordinator(Store())
        val authority = HandoverAuthority(HandoverScope(a.userId, a.companyId, a.branchId, a.terminalId), 1)
        lateinit var original: OriginalOperationBinding
        suspend fun start() {
            cache.activateValidated(a); handover.initialize(authority)
            val lease = requireNotNull(cache.currentLease())
            original = OriginalOperationBinding(authority, lease, SessionRefreshLease(StoredSession("synthetic-A", "synthetic-refresh"), 1)) {
                cache.currentLease() == lease
            }
        }
    }
    @Test fun noCacheSupportDeliveryPermitCannotBorrowOrdinaryRoomCacheMutation() = runBlocking {
        val f = Fixture(); f.start()
        val permit = f.handover.admitOwnerSupport(f.a.companyId, f.a.userId) { true }
        val context = OwnerSupportDeliveryContext(permit, OutboxOwnerIdentity(f.a.userId, f.a.companyId, f.a.branchId)) { true }
        var wrote = false
        withContext(context) {
            assertFalse(f.cache.commitIfCurrent(f.original.cacheLease) { wrote = true })
            assertEquals(ScopedCommitResult.Stale, f.cache.commitResultIfCurrent(f.original.cacheLease) { wrote = true; 1 })
        }
        assertFalse(wrote); f.handover.finishOwnerSupport(permit)
    }
    @Test fun capturedAOperationCannotCommitUsingAFreshlyReadBLeaseAfterResponse() = runBlocking {
        val f = Fixture(); f.start(); val permit = f.handover.admit(f.authority)
        val captured = OperationDispatchContext(f.original, permit)
        f.cache.activateValidated(f.a.copy(userId = id()))
        val b = requireNotNull(f.cache.currentLease()); var landed = false
        withContext(captured.asCoroutineContext() + Dispatchers.IO) {
            assertFalse(f.cache.commitIfCurrent(b) { landed = true })
            assertEquals(ScopedCommitResult.Stale, f.cache.commitResultIfCurrent(b) { landed = true; 1 })
        }
        assertFalse(landed); f.handover.finish(permit, OperationOutcome.NO_WRITE_SENT)
    }
    @Test fun escapedLateChildCannotCommitAfterItsPermitLifetimeFinishedEvenWithSameA() = runBlocking {
        val f = Fixture(); f.start(); val permit = f.handover.admit(f.authority)
        val captured = OperationDispatchContext(f.original, permit); val lease = f.original.cacheLease
        f.handover.finish(permit, OperationOutcome.NO_WRITE_SENT)
        var landed = false
        withContext(captured.asCoroutineContext()) { assertFalse(f.cache.commitIfCurrent(lease) { landed = true }) }
        assertFalse(landed)
    }
    @Test fun activeOriginalOperationAndExistingUncontextualizedBootstrapReadersKeepTheirLeaseContract() = runBlocking {
        val f = Fixture(); f.start(); val permit = f.handover.admit(f.authority); val lease = f.original.cacheLease
        var writes = 0
        withContext(OperationDispatchContext(f.original, permit).asCoroutineContext() + Dispatchers.IO) {
            assertTrue(f.cache.commitIfCurrent(lease) { writes++ })
        }
        f.handover.finish(permit, OperationOutcome.NO_WRITE_SENT)
        assertTrue(f.cache.commitIfCurrent(lease) { writes++ })
        withContext(OperationDispatchContext(f.original, null).asCoroutineContext()) {
            assertFalse(f.cache.commitIfCurrent(lease) { writes++ })
        }
        assertEquals(2, writes)
    }
    companion object { private fun id() = UUID.randomUUID().toString() }
}
