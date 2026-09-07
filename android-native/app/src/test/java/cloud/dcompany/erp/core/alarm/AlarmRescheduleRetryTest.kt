package cloud.dcompany.erp.core.alarm

import android.app.AlarmManager
import android.content.Intent
import cloud.dcompany.erp.PersistedStartupFailure
import cloud.dcompany.erp.PersistedStartupStateRestorer
import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeMarker
import cloud.dcompany.erp.core.auth.ScopeDataPurger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRescheduleRetryTest {

    @Test
    fun `unknown persisted authority preserves alarms and skips scope work`() = runBlocking {
        val events = mutableListOf<String>()

        val result = runAlarmRescheduleAttempt(
            restorePersistedAuthority = {
                PersistedStartupStateResult.Unavailable(PersistedStartupFailure.TIMEOUT)
            },
            ensureActiveOwnedScope = { events += "scope"; true },
            cancelAll = { events += "cancel" },
            prepareForSystemReschedule = { events += "prepare"; true },
            reconcileGaming = { events += "gaming" },
            reconcileHeldOrders = { events += "held" },
        )

        assertEquals(AlarmRescheduleAttemptResult.RETRY, result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `restored definitive no-owner cancels without reconciling another scope`() = runBlocking {
        val events = mutableListOf<String>()

        val result = runAlarmRescheduleAttempt(
            restorePersistedAuthority = { PersistedStartupStateResult.Ready },
            ensureActiveOwnedScope = { events += "scope"; false },
            cancelAll = { events += "cancel" },
            prepareForSystemReschedule = { events += "prepare"; true },
            reconcileGaming = { events += "gaming" },
            reconcileHeldOrders = { events += "held" },
        )

        assertEquals(AlarmRescheduleAttemptResult.COMPLETE, result)
        assertEquals(listOf("scope", "cancel"), events)
    }

    @Test
    fun `restored owned scope preserves existing reschedule ordering`() = runBlocking {
        val events = mutableListOf<String>()

        val result = runAlarmRescheduleAttempt(
            restorePersistedAuthority = { events += "restore"; PersistedStartupStateResult.Ready },
            ensureActiveOwnedScope = { events += "scope"; true },
            cancelAll = { events += "cancel" },
            prepareForSystemReschedule = { events += "prepare"; true },
            reconcileGaming = { events += "gaming" },
            reconcileHeldOrders = { events += "held" },
        )

        assertEquals(AlarmRescheduleAttemptResult.COMPLETE, result)
        assertEquals(listOf("restore", "scope", "prepare", "gaming", "held"), events)
    }

    @Test
    fun `scope storage failure retries without cancelling alarms`() = runBlocking {
        var cancelled = false

        val result = runAlarmRescheduleAttempt(
            restorePersistedAuthority = { PersistedStartupStateResult.Ready },
            ensureActiveOwnedScope = { error("scope marker unavailable") },
            cancelAll = { cancelled = true },
            prepareForSystemReschedule = { true },
            reconcileGaming = {},
            reconcileHeldOrders = {},
        )

        assertEquals(AlarmRescheduleAttemptResult.RETRY, result)
        assertFalse(cancelled)
    }

    @Test
    fun `stale A reschedule racing active B retries without cancelling B alarms`() = runBlocking {
        val scopeA = CacheScope("user-a", "company", "branch-a", "terminal-a")
        val scopeB = CacheScope("user-b", "company", "branch-b", "terminal-b")
        var storedScope: CacheScope? = scopeB
        val coordinator = CacheIsolationCoordinator(
            purger = object : ScopeDataPurger {
                override suspend fun hasUnresolvedWork(): Boolean = false
                override suspend fun purgeIfClean(): Boolean = true
            },
            marker = object : CacheScopeMarker {
                override fun current(): CacheScope? = storedScope
                override fun remember(scope: CacheScope): Boolean {
                    storedScope = scope
                    return true
                }
                override fun clear(): Boolean {
                    storedScope = null
                    return true
                }
            },
        )
        coordinator.activateValidated(scopeB)
        val leaseB = requireNotNull(coordinator.currentLease())
        var cancelled = false

        val result = runAlarmRescheduleAttempt(
            restorePersistedAuthority = { PersistedStartupStateResult.Ready },
            ensureActiveOwnedScope = {
                operationalAlarmAdoptionOrNull(
                    coordinator.adoptCachedOnlyIfInactive(scopeA),
                ) != null
            },
            cancelAll = { cancelled = true },
            prepareForSystemReschedule = { true },
            reconcileGaming = {},
            reconcileHeldOrders = {},
        )

        assertEquals(AlarmRescheduleAttemptResult.RETRY, result)
        assertFalse(cancelled)
        assertEquals(leaseB, coordinator.currentLease())
    }

    @Test
    fun `foreground B winning after A adoption preserves B alarms and retries`() = runBlocking {
        val scopeA = CacheScope("user-a", "company", "branch-a", "terminal-a")
        val scopeB = CacheScope("user-b", "company", "branch-b", "terminal-b")
        var storedScope: CacheScope? = scopeA
        val coordinator = CacheIsolationCoordinator(
            purger = object : ScopeDataPurger {
                override suspend fun hasUnresolvedWork(): Boolean = false
                override suspend fun purgeIfClean(): Boolean = true
            },
            marker = object : CacheScopeMarker {
                override fun current(): CacheScope? = storedScope
                override fun remember(scope: CacheScope): Boolean {
                    storedScope = scope
                    return true
                }
                override fun clear(): Boolean {
                    storedScope = null
                    return true
                }
            },
        )
        var cancelled = false

        val result = runAlarmRescheduleAttempt(
            restorePersistedAuthority = { PersistedStartupStateResult.Ready },
            ensureActiveOwnedScope = {
                val adoption = requireNotNull(
                    operationalAlarmAdoptionOrNull(
                        coordinator.adoptCachedOnlyIfInactive(scopeA),
                    ),
                )
                assertTrue(adoption.adopted)

                // The foreground login wins only after stale recovery A has
                // adopted its saved lease. A must now become retryable rather
                // than reporting definitive no owner and cancelling B.
                coordinator.activateValidated(scopeB)
                requireOperationalAlarmOwnershipAfterAdoption(
                    tokenLineageStillOwned = true,
                    exactLeaseStillOwned = coordinator.currentLease() == adoption.lease,
                )
                true
            },
            cancelAll = { cancelled = true },
            prepareForSystemReschedule = { true },
            reconcileGaming = {},
            reconcileHeldOrders = {},
        )

        assertEquals(AlarmRescheduleAttemptResult.RETRY, result)
        assertFalse(cancelled)
        assertEquals(scopeB, coordinator.currentLease()?.scope)
    }

    @Test
    fun `foreground B winning after definitive false is replayed after global cancellation`() =
        runBlocking {
            val scopeB = CacheScope("user-b", "company", "branch-b", "terminal-b")
            var storedScope: CacheScope? = null
            val coordinator = CacheIsolationCoordinator(
                purger = object : ScopeDataPurger {
                    override suspend fun hasUnresolvedWork(): Boolean = false
                    override suspend fun purgeIfClean(): Boolean = true
                },
                marker = object : CacheScopeMarker {
                    override fun current(): CacheScope? = storedScope
                    override fun remember(scope: CacheScope): Boolean {
                        storedScope = scope
                        return true
                    }
                    override fun clear(): Boolean {
                        storedScope = null
                        return true
                    }
                },
            )
            var cancelled = false
            var replayedWorkspace: CacheScope? = null

            val result = runAlarmRescheduleAttempt(
                restorePersistedAuthority = { PersistedStartupStateResult.Ready },
                ensureActiveOwnedScope = {
                    // The stale receiver has already decided it owns nothing;
                    // B becomes active before the following global cancel.
                    coordinator.activateValidated(scopeB)
                    false
                },
                cancelAll = { cancelled = true },
                prepareForSystemReschedule = { true },
                reconcileGaming = {},
                reconcileHeldOrders = {},
                requestLatestScopeReconciliation = {
                    replayedWorkspace = coordinator.currentLease()?.scope
                },
            )

            assertEquals(AlarmRescheduleAttemptResult.COMPLETE, result)
            assertTrue(cancelled)
            assertEquals(scopeB, replayedWorkspace)
            assertEquals(scopeB, coordinator.currentLease()?.scope)
        }

    @Test
    fun `failed definitive no owner cleanup retries and still requests latest reconciliation`() =
        runBlocking {
            val events = mutableListOf<String>()

            val result = runAlarmRescheduleAttempt(
                restorePersistedAuthority = { PersistedStartupStateResult.Ready },
                ensureActiveOwnedScope = { events += "scope"; false },
                cancelAll = {
                    events += "cancel-failed"
                    error("preferences commit failed")
                },
                prepareForSystemReschedule = { events += "prepare"; true },
                reconcileGaming = { events += "gaming" },
                reconcileHeldOrders = { events += "held" },
                requestLatestScopeReconciliation = { events += "request-latest" },
            )

            assertEquals(AlarmRescheduleAttemptResult.RETRY, result)
            assertEquals(listOf("scope", "cancel-failed", "request-latest"), events)
        }

    @Test
    fun `failed reboot delivery-ledger commit retries before scheduling alarms`() = runBlocking {
        val events = mutableListOf<String>()

        val result = runAlarmRescheduleAttempt(
            restorePersistedAuthority = { PersistedStartupStateResult.Ready },
            ensureActiveOwnedScope = { events += "scope"; true },
            cancelAll = { events += "cancel" },
            prepareForSystemReschedule = { events += "prepare-failed"; false },
            reconcileGaming = { events += "gaming" },
            reconcileHeldOrders = { events += "held" },
        )

        assertEquals(AlarmRescheduleAttemptResult.RETRY, result)
        assertEquals(listOf("scope", "prepare-failed"), events)
    }

    @Test
    fun `process restorer retries a completed failure with the same loaders`() = runBlocking {
        var loads = 0
        val restorer = PersistedStartupStateRestorer(
            scope = this,
            timeoutMillis = 1_000L,
            loaders = listOf({
                loads += 1
                if (loads == 1) error("temporary encrypted-store failure")
            }),
        )

        assertEquals(
            PersistedStartupStateResult.Unavailable(PersistedStartupFailure.STORAGE),
            restorer.await(),
        )
        assertEquals(PersistedStartupStateResult.Ready, restorer.await(retryFailed = true))
        assertEquals(2, loads)
    }

    @Test
    fun `unknown authority stays durably retryable after the former five-attempt boundary`() {
        listOf(0, 1, 4, 5, 100, Int.MAX_VALUE).forEach { zeroBasedAttempt ->
            assertEquals(
                AlarmRescheduleWorkDisposition.RETRY,
                alarmRescheduleWorkDisposition(AlarmRescheduleAttemptResult.RETRY, zeroBasedAttempt),
            )
        }
        assertEquals(
            AlarmRescheduleWorkDisposition.SUCCESS,
            alarmRescheduleWorkDisposition(
                AlarmRescheduleAttemptResult.COMPLETE,
                Int.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `only declared local lifecycle actions can schedule recovery`() {
        assertTrue(isSupportedAlarmRescheduleAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(isSupportedAlarmRescheduleAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(
            isSupportedAlarmRescheduleAction(
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            ),
        )
        assertFalse(isSupportedAlarmRescheduleAction(null))
        assertFalse(isSupportedAlarmRescheduleAction("cloud.dcompany.erp.action.remote"))
    }

    @Test
    fun `structured cancellation propagates instead of becoming a retry`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runAlarmRescheduleAttempt(
                    restorePersistedAuthority = { throw CancellationException("cancel") },
                    ensureActiveOwnedScope = { true },
                    cancelAll = {},
                    prepareForSystemReschedule = { true },
                    reconcileGaming = {},
                    reconcileHeldOrders = {},
                )
            }
        }
    }
}
