package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Explicit DI for standalone fake-API controller tests; never installed in Application. */
internal fun standaloneOperationCaller(): CallerOperations {
    val scope = HandoverScope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, null)
    val authority = HandoverAuthority(scope, 1)
    val store = object : HandoverIntentStore {
        override suspend fun read(): HandoverIntent? = null
        override suspend fun begin(intent: HandoverIntent): Unit = error("This fixture does not hand over a till.")
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent): Unit = error("No handover fixture")
        override suspend fun cancelNotSent(expected: HandoverIntent): Unit = error("No handover fixture")
    }
    val coordinator = HandoverCoordinator(store)
    runBlocking { coordinator.initialize(authority) }
    val binding = OriginalOperationBinding(authority, CacheScopeLease(CacheScope(scope.actorId, scope.companyId, null, null), 1),
        SessionRefreshLease(StoredSession("standalone-fake-access", "standalone-fake-refresh"), 1)) { true }
    return CallerOperations(binding) { original, block -> coordinator.runOperation(original.authority, block) }
}
