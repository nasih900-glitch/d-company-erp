package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalReassignmentResumeTest {
    private val a = CacheScope("a", "company", "branch", "till-a")
    private val b = CacheScope("b", "company", "branch", "till-b")
    private class Marker(var value: CacheScope?) : CacheScopeMarker {
        override fun current() = value
        override fun remember(scope: CacheScope): Boolean { value = scope; return true }
        override fun clear(): Boolean { value = null; return true }
    }
    private fun cache() = CacheIsolationCoordinator(object : ScopeDataPurger {
        override suspend fun hasUnresolvedWork() = false
        override suspend fun purgeIfClean() = true
    }, Marker(a))

    private suspend fun staleActivationCannotPublish(callsite: String) = coroutineScope {
        val cache = cache()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var login = "a"; var selection = callsite; var header = "a"; var screen = "a"; var cleaned = 0
        val resumed = async {
            resumeOriginalCachedTerminal(cache, a, cache.currentGeneration(), { login == "a" && selection == callsite },
                { commit -> if (login == "a") { commit(); true } else false },
                { header = "a-restored"; screen = "a-restored" }, { cleaned++; header = "cleared" },
                { scope -> cache.adoptCachedOnlyIfInactive(scope).also { entered.complete(Unit); release.await() } })
        }
        entered.await()
        login = "b"; selection = "b-selector"
        cache.activateValidated(b)
        val bLease = cache.currentLease()
        header = "b"; screen = "b-selector"
        release.complete(Unit)
        assertNull(resumed.await())
        assertEquals(bLease, cache.currentLease())
        assertEquals("b", header); assertEquals("b-selector", screen); assertEquals(0, cleaned)
    }

    @Test fun sameOriginalLoginPublishesOnlyItsOwnedLease() = runBlocking<Unit> {
        val cache = cache(); var publications = 0; var cleaned = 0
        val lease = resumeOriginalCachedTerminal(cache, a, cache.currentGeneration(), { true },
            { it(); true }, { publications++ }, { cleaned++ })
        assertNotNull(lease); assertEquals(lease, cache.currentLease())
        assertEquals(1, publications); assertEquals(0, cleaned)
    }

    @Test fun changedOriginalSelectorCannotPublishOrClearItsReplacement() = runBlocking<Unit> {
        val cache = cache(); var selectorCurrent = true; var publications = 0; var cleaned = 0
        val result = resumeOriginalCachedTerminal(cache, a, cache.currentGeneration(), { selectorCurrent },
            { it(); true }, { publications++ }, { cleaned++ },
            { scope -> cache.adoptCachedOnlyIfInactive(scope).also { selectorCurrent = false } })
        assertNull(result); assertNull(cache.currentLease()); assertEquals(0, publications); assertEquals(1, cleaned)
    }

    @Test fun loginCommitRefusalNeverPublishesOrClearsNewLoginRuntime() = runBlocking<Unit> {
        val cache = cache(); var publications = 0; var cleaned = 0
        val result = resumeOriginalCachedTerminal(cache, a, cache.currentGeneration(), { true },
            { false }, { publications++ }, { cleaned++ })
        assertNull(result); assertNull(cache.currentLease()); assertEquals(0, publications); assertEquals(0, cleaned)
    }

    @Test fun failedPublicationCleansOnlyOwnedLeaseAndRetainsFailure() = runBlocking<Unit> {
        val cache = cache(); var cleaned = 0; val failure = IllegalStateException("label unavailable")
        val result = runCatching {
            resumeOriginalCachedTerminal(cache, a, cache.currentGeneration(), { true },
                { it(); true }, { throw failure }, { cleaned++ })
        }
        assertSame(failure, result.exceptionOrNull()); assertNull(cache.currentLease()); assertEquals(1, cleaned)
    }

    @Test fun cancelledPublicationCleansOnlyOwnedLease() = runBlocking<Unit> {
        val cache = cache(); var cleaned = 0
        val result = runCatching {
            resumeOriginalCachedTerminal(cache, a, cache.currentGeneration(), { true },
                { it(); true }, { throw CancellationException("cancelled") }, { cleaned++ })
        }
        assertTrue(result.exceptionOrNull() is CancellationException); assertNull(cache.currentLease()); assertEquals(1, cleaned)
    }

    @Test fun borrowedLeaseIsNeverPublishedOrCleaned() = runBlocking<Unit> {
        val cache = cache(); var publications = 0; var cleaned = 0
        val result = resumeOriginalCachedTerminal(cache, a, cache.currentGeneration(), { true },
            { it(); true }, { publications++ }, { cleaned++ },
            { scope -> cache.activateValidated(scope); cache.adoptCachedOnlyIfInactive(scope) })
        assertNull(result); assertEquals(a, cache.currentLease()?.scope); assertEquals(0, publications); assertEquals(0, cleaned)
    }

    @Test fun interveningCacheGenerationCannotBorrowExpectedInactiveAuthority() = runBlocking<Unit> {
        val cache = cache(); var publications = 0
        val result = resumeOriginalCachedTerminal(cache, a, cache.currentGeneration(), { true },
            { it(); true }, { publications++ }, {},
            { scope -> cache.deactivate(); cache.adoptCachedOnlyIfInactive(scope) })
        assertNull(result); assertNull(cache.currentLease()); assertEquals(0, publications)
    }

    @Test fun bothProductionCallsitesUseOwnedResumeAndOriginalSelectorFeedback() {
        val source = String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/java/cloud/dcompany/erp/ui/SessionViewModel.kt")), java.nio.charset.StandardCharsets.UTF_8)
        val restore = source.substringAfter("private suspend fun restorePreviousTerminalAfterInterruptedChange(").substringBefore("private fun terminalReassignmentApiError")
        val cancel = source.substringAfter("fun cancelTerminalReassignment()").substringBefore("/** Keep authority")
        for (body in listOf(restore, cancel)) {
            assertTrue(body.contains("resumeOriginalCachedTerminal(")); assertTrue(body.contains("commitIfOriginalLoginCurrent"))
            assertFalse(body.contains("cacheIsolation.activateCached(")); assertFalse(body.contains("cacheIsolation.deactivate()"))
        }
        assertTrue(cancel.contains("pendingTerminalSession === pending && _state.value === choosing"))
        assertTrue(restore.contains("_state.value === visible"))
        assertTrue(cancel.contains("caller(pending.me, owned).run")); assertTrue(cancel.contains("connectForOriginal(login)"))
    }

    @Test fun cancelDoesNotPublishAAfterBActivation() = runBlocking<Unit> { staleActivationCannotPublish("cancel") }
    @Test fun interruptedRestoreDoesNotPublishAAfterBActivation() = runBlocking<Unit> { staleActivationCannotPublish("interrupted") }
}
