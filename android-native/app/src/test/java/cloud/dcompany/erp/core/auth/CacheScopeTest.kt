package cloud.dcompany.erp.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheScopeTest {

    private val scopeA = CacheScope("user-a", "company", "branch-a", "terminal-a")
    private val scopeB = CacheScope("user-b", "company", "branch-b", "terminal-b")

    @Test
    fun `A to B invalidates marker then atomically purges all scoped data before publishing B`() = runBlocking {
        val events = mutableListOf<String>()
        val marker = FakeMarker(scopeA, events)
        val coordinator = CacheIsolationCoordinator(
            purger = FakePurger(events),
            marker = marker,
        )

        assertFalse(coordinator.isReady())
        assertEquals(CacheScopeActivation.PURGED, coordinator.activateValidated(scopeB))

        assertEquals(listOf("preflight", "clear", "purge", "marker:user-b"), events)
        assertEquals(scopeB, marker.current())
        assertTrue(coordinator.isReady())
    }

    @Test
    fun `same exact scope retains rows without invalidation or purge`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = CacheIsolationCoordinator(FakePurger(events), FakeMarker(scopeA, events))

        assertEquals(CacheScopeActivation.RETAINED, coordinator.activateValidated(scopeA))
        assertTrue(events.isEmpty())
        assertTrue(coordinator.isReady())
    }

    @Test
    fun `offline cached activation requires exact user company branch and terminal`() = runBlocking {
        val coordinator = CacheIsolationCoordinator(FakePurger(), FakeMarker(scopeA))

        assertEquals(CacheScopeActivation.RETAINED, coordinator.activateCached(scopeA))
        coordinator.deactivate()

        assertThrows(CacheScopeException::class.java) {
            runBlocking { coordinator.activateCached(scopeA.copy(terminalId = "other-terminal")) }
        }
        assertFalse(coordinator.isReady())
    }

    @Test
    fun `known unresolved work preserves prior marker and refuses scope switch`() = runBlocking {
        val events = mutableListOf<String>()
        val marker = FakeMarker(scopeA, events)
        val coordinator = CacheIsolationCoordinator(
            FakePurger(events, unresolvedAtPreflight = true),
            marker,
        )

        assertThrows(CacheScopeException::class.java) {
            runBlocking { coordinator.activateValidated(scopeB) }
        }

        assertEquals(listOf("preflight"), events)
        assertEquals(scopeA, marker.current())
        assertFalse(coordinator.isReady())
    }

    @Test
    fun `legacy null marker with unresolved work fails closed instead of guessing owner`() = runBlocking {
        val coordinator = CacheIsolationCoordinator(
            FakePurger(unresolvedAtPreflight = true),
            FakeMarker(null),
        )

        val error = assertThrows(CacheScopeException::class.java) {
            runBlocking { coordinator.activateValidated(scopeB) }
        }

        assertTrue(error.message.orEmpty().contains("cannot be proven"))
        assertFalse(coordinator.isReady())
    }

    @Test
    fun `write appearing after preflight leaves marker invalid and B unpublished`() = runBlocking {
        val events = mutableListOf<String>()
        val marker = FakeMarker(scopeA, events)
        val coordinator = CacheIsolationCoordinator(
            FakePurger(events, unresolvedAtPurge = true),
            marker,
        )

        assertThrows(CacheScopeException::class.java) {
            runBlocking { coordinator.activateValidated(scopeB) }
        }

        assertEquals(listOf("preflight", "clear", "purge"), events)
        assertNull(marker.current())
        assertFalse(coordinator.isReady())
    }

    @Test
    fun `clear purge and remember failures all fail closed`() = runBlocking {
        val clearMarker = FakeMarker(scopeA, failClear = true)
        val clearFailure = CacheIsolationCoordinator(FakePurger(), clearMarker)
        assertThrows(CacheScopeException::class.java) {
            runBlocking { clearFailure.activateValidated(scopeB) }
        }
        assertEquals(scopeA, clearMarker.current())
        assertFalse(clearFailure.isReady())

        val purgeMarker = FakeMarker(scopeA)
        val purgeFailure = CacheIsolationCoordinator(FakePurger(failPurge = true), purgeMarker)
        assertThrows(CacheScopeException::class.java) {
            runBlocking { purgeFailure.activateValidated(scopeB) }
        }
        assertNull(purgeMarker.current())
        assertFalse(purgeFailure.isReady())

        val rememberMarker = FakeMarker(scopeA, failRemember = true)
        val rememberFailure = CacheIsolationCoordinator(FakePurger(), rememberMarker)
        assertThrows(CacheScopeException::class.java) {
            runBlocking { rememberFailure.activateValidated(scopeB) }
        }
        assertNull(rememberMarker.current())
        assertFalse(rememberFailure.isReady())
    }

    @Test
    fun `delayed A server response is discarded after B activation`() = runBlocking {
        val coordinator = CacheIsolationCoordinator(FakePurger(), FakeMarker(scopeA))
        coordinator.activateValidated(scopeA)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        var stored: String? = null

        val request = async {
            coordinator.fetchAndCommitScoped(
                fetch = {
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                    "response-from-A"
                },
                store = { stored = it },
            )
        }
        requestStarted.await()
        assertEquals(CacheScopeActivation.PURGED, coordinator.activateValidated(scopeB))
        releaseResponse.complete(Unit)

        assertFalse(request.await())
        assertNull(stored)
        assertEquals(scopeB, coordinator.currentLease()?.scope)
    }

    @Test
    fun `delayed A local mutation cannot land after clean A to B purge`() = runBlocking {
        val coordinator = CacheIsolationCoordinator(FakePurger(), FakeMarker(scopeA))
        coordinator.activateValidated(scopeA)
        val leaseA = requireNotNull(coordinator.currentLease())
        val actionStarted = CompletableDeferred<Unit>()
        val releaseAction = CompletableDeferred<Unit>()
        var localWriteLanded = false

        val oldAction = async {
            actionStarted.complete(Unit)
            releaseAction.await()
            coordinator.commitIfCurrent(leaseA) { localWriteLanded = true }
        }
        actionStarted.await()
        assertEquals(CacheScopeActivation.PURGED, coordinator.activateValidated(scopeB))
        releaseAction.complete(Unit)

        assertFalse(oldAction.await())
        assertFalse(localWriteLanded)
    }

    @Test
    fun `local mutation that wins mutex is seen by preflight and blocks B`() = runBlocking {
        val purger = FakePurger()
        val coordinator = CacheIsolationCoordinator(purger, FakeMarker(scopeA))
        coordinator.activateValidated(scopeA)
        val leaseA = requireNotNull(coordinator.currentLease())

        assertTrue(coordinator.commitIfCurrent(leaseA) { purger.unresolvedAtPreflight = true })
        assertThrows(CacheScopeException::class.java) {
            runBlocking { coordinator.activateValidated(scopeB) }
        }
        assertFalse(coordinator.isReady())
    }

    @Test
    fun `atomic sign out revokes lease before a delayed feature mutation can land`() = runBlocking {
        val coordinator = CacheIsolationCoordinator(FakePurger(), FakeMarker(scopeA))
        coordinator.activateValidated(scopeA)
        val leaseA = requireNotNull(coordinator.currentLease())
        val releaseAction = CompletableDeferred<Unit>()
        var localWriteLanded = false

        val oldAction = async {
            releaseAction.await()
            coordinator.commitIfCurrent(leaseA) { localWriteLanded = true }
        }
        assertEquals(
            OutboxGateResult.Allowed,
            coordinator.deactivateAfterOutboxGate { OutboxGateResult.Allowed },
        )
        releaseAction.complete(Unit)

        assertFalse(oldAction.await())
        assertFalse(localWriteLanded)
        assertFalse(coordinator.isReady())
    }

    @Test
    fun `feature mutation that enters first is included in atomic sign out recheck`() = runBlocking {
        val coordinator = CacheIsolationCoordinator(FakePurger(), FakeMarker(scopeA))
        coordinator.activateValidated(scopeA)
        val leaseA = requireNotNull(coordinator.currentLease())
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var unresolved = false

        val oldAction = async {
            coordinator.commitIfCurrent(leaseA) {
                writeEntered.complete(Unit)
                unresolved = true
                releaseWrite.await()
            }
        }
        writeEntered.await()
        val signOut = async {
            coordinator.deactivateAfterOutboxGate {
                if (unresolved) OutboxGateResult.Blocked("saved work remains")
                else OutboxGateResult.Allowed
            }
        }
        releaseWrite.complete(Unit)

        assertTrue(oldAction.await())
        assertTrue(signOut.await() is OutboxGateResult.Blocked)
        assertEquals(scopeA, coordinator.currentLease()?.scope)
    }

    @Test
    fun `ordinary deactivation waits for an already entered scoped commit`() = runBlocking {
        val coordinator = CacheIsolationCoordinator(FakePurger(), FakeMarker(scopeA))
        coordinator.activateValidated(scopeA)
        val leaseA = requireNotNull(coordinator.currentLease())
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()

        val oldAction = async {
            coordinator.commitIfCurrent(leaseA) {
                writeEntered.complete(Unit)
                releaseWrite.await()
            }
        }
        writeEntered.await()
        val deactivation = async { coordinator.deactivate() }
        assertFalse(deactivation.isCompleted)
        releaseWrite.complete(Unit)

        assertTrue(oldAction.await())
        deactivation.await()
        assertFalse(coordinator.isReady())
    }

    @Test
    fun `cache inventory is exhaustive unique and all tables are scope bound`() {
        assertEquals(39, SERVER_DERIVED_CACHE_TABLES.size)
        assertEquals(35, LOCAL_DURABLE_TABLES.size)
        assertEquals(SERVER_DERIVED_CACHE_TABLES.size, SERVER_DERIVED_CACHE_TABLES.toSet().size)
        assertEquals(LOCAL_DURABLE_TABLES.size, LOCAL_DURABLE_TABLES.toSet().size)
        assertEquals(74, ALL_SCOPE_TABLES.size)
        assertTrue(SERVER_DERIVED_CACHE_TABLES.toSet().intersect(LOCAL_DURABLE_TABLES).isEmpty())
        assertTrue("menu_variants" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("menu_modifier_groups" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("menu_modifiers" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("report_snapshots" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("sync_meta" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("server_open_shift_cache" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("shift_history_cache" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("membership_payment_task_cache" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("membership_refund_task_cache" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("membership_refund_attempt_cache" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("cafe_bill_cache" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("gaming_package_cache" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("customer_order_history_cache" in SERVER_DERIVED_CACHE_TABLES)
        assertTrue("local_orders" in LOCAL_DURABLE_TABLES)
        assertTrue("local_shifts" in LOCAL_DURABLE_TABLES)
        assertTrue("local_held_order_payments" in LOCAL_DURABLE_TABLES)
        assertTrue("local_membership_payment_actions" in LOCAL_DURABLE_TABLES)
        assertTrue("local_membership_refund_actions" in LOCAL_DURABLE_TABLES)
        assertTrue("local_cafe_bills" in LOCAL_DURABLE_TABLES)
        assertTrue("local_cafe_actions" in LOCAL_DURABLE_TABLES)
        assertTrue("local_kitchen_cancellation_acks" in LOCAL_DURABLE_TABLES)
        assertTrue("local_gaming_package_extensions" in LOCAL_DURABLE_TABLES)
        assertTrue("pos_receipts" in LOCAL_DURABLE_TABLES)
    }

    private class FakePurger(
        private val events: MutableList<String> = mutableListOf(),
        var unresolvedAtPreflight: Boolean = false,
        private val unresolvedAtPurge: Boolean = false,
        private val failPurge: Boolean = false,
    ) : ScopeDataPurger {
        override suspend fun hasUnresolvedWork(): Boolean {
            events += "preflight"
            return unresolvedAtPreflight
        }

        override suspend fun purgeIfClean(): Boolean {
            events += "purge"
            if (failPurge) error("purge failed")
            return !unresolvedAtPurge
        }
    }

    private class FakeMarker(
        initial: CacheScope?,
        private val events: MutableList<String> = mutableListOf(),
        private val failRemember: Boolean = false,
        private val failClear: Boolean = false,
    ) : CacheScopeMarker {
        private var stored = initial

        override fun current(): CacheScope? = stored

        override fun remember(scope: CacheScope): Boolean {
            events += "marker:${scope.userId}"
            if (failRemember) return false
            stored = scope
            return true
        }

        override fun clear(): Boolean {
            events += "clear"
            if (failClear) return false
            stored = null
            return true
        }
    }
}
