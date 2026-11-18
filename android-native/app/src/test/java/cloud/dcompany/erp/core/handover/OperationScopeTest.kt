package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OperationScopeTest {
    private val actor = UUID.randomUUID().toString()
    private val company = UUID.randomUUID().toString()
    private val branch = UUID.randomUUID().toString()
    private val terminal = UUID.randomUUID().toString()
    private val next = UUID.randomUUID().toString()
    private class Store : HandoverIntentStore {
        override suspend fun read(): HandoverIntent? = null
        override suspend fun begin(intent: HandoverIntent) { error("Branchless Owner cannot freeze a till") }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent) { error("No till handover") }
        override suspend fun cancelNotSent(expected: HandoverIntent) { error("No till handover") }
    }
    @Test fun branchlessOwnerParticipatesInOrdinaryRegistryWithoutInventedWorkspace() = runBlocking {
        val scope = effectiveOperationScope(CacheScope(actor, company, null, null), null)
        assertNull(scope.branchId); assertNull(scope.terminalId)
        val auth = HandoverAuthority(scope, 1); val c = HandoverCoordinator(Store()); c.initialize(auth)
        val permit = c.admit(auth); c.beforeMutationDispatch(permit, auth); c.finish(permit, OperationOutcome.DURABLE_OUTCOME)
        assertTrue(c.state.value.activeOperations.isEmpty())
        val action = HandoverAction(UUID.randomUUID().toString(), auth, UUID.randomUUID().toString(), next, 0)
        assertTrue(runCatching { c.freeze(auth, action) }.isFailure)
        assertNull(c.state.value.intent)
    }
    @Test fun branchlessOwnerCannotBorrowAnUnselectedTerminalOrFreezeWithOnlyBranch() {
        val active = ValidatedTerminalDisplay(terminal, "Synthetic", branch, "hybrid")
        val scope = effectiveOperationScope(CacheScope(actor, company, null, null), active)
        assertNull(scope.branchId); assertNull(scope.terminalId)
        val partial = HandoverAuthority(scope.copy(branchId = branch), 1)
        assertThrows(IllegalArgumentException::class.java) { HandoverAction(UUID.randomUUID().toString(), partial, UUID.randomUUID().toString(), next, 0).validate() }
    }
    @Test fun exactValidatedTerminalDerivesNullProfileBranchForBothOperationAndAction() {
        val scope = effectiveOperationScope(CacheScope(actor, company, null, terminal),
            ValidatedTerminalDisplay(terminal, "Synthetic", branch, "hybrid"))
        assertEquals(branch, scope.branchId); assertEquals(terminal, scope.terminalId)
        HandoverAction(UUID.randomUUID().toString(), HandoverAuthority(scope, 1), UUID.randomUUID().toString(), next, 0).validate()
    }
    @Test fun staleTerminalWrongBranchOrAbsentValidationNeverBorrowsAuthority() {
        val cached = CacheScope(actor, company, branch, terminal)
        assertThrows(IllegalArgumentException::class.java) { effectiveOperationScope(cached, null) }
        assertThrows(IllegalArgumentException::class.java) { effectiveOperationScope(cached, ValidatedTerminalDisplay(next, "Wrong", branch, "hybrid")) }
        assertThrows(IllegalArgumentException::class.java) { effectiveOperationScope(cached, ValidatedTerminalDisplay(terminal, "Wrong branch", next, "hybrid")) }
    }
}
