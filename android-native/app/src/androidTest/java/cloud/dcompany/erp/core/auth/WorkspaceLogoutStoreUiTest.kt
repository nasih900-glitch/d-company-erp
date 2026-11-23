package cloud.dcompany.erp.core.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual encrypted session and profile DataStore; synthetic users on Root's isolated QA package only. */
@RunWith(AndroidJUnit4::class)
class WorkspaceLogoutStoreUiTest {
    private fun profile(actor: String) = Json.encodeToString(MeResponse.serializer(),
        MeResponse(actor, "$actor@example.invalid", "Synthetic $actor", roles = listOf("cashier"), companyId = "synthetic-company"))

    @Test fun suspendedOriginalLogoutCannotClearNewBSessionOrProfile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokens = TokenStore(context).also { it.clear() }
        val cache = ShiftCache(context)
        val a = profile("A"); val b = profile("B")
        try {
            tokens.save("synthetic-A", "synthetic-A-refresh"); cache.rememberProfile(a)
            val removal = requireNotNull(tokens.captureLogoutLease(tokens.refreshLease()))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val oldLogout = async { runCatching {
                bestEffortAuthenticatedLogout(5_000,
                    revokeRemoteSession = { entered.complete(Unit); release.await() },
                    clearLocalSession = { check(tokens.clearForLogoutIfCurrent(removal)) })
            } }
            entered.await()
            tokens.save("synthetic-B", "synthetic-B-refresh"); cache.rememberProfile(b)
            release.complete(Unit)
            assertTrue(oldLogout.await().isFailure)
            assertFalse(cache.forgetOriginalProfile(a) { tokens.isCurrentLogoutRemoval(removal) })
            var oldPublication = false
            assertFalse(tokens.publishIfCurrentLogoutRemoval(removal) { oldPublication = true })
            assertFalse(oldPublication)
            assertEquals("synthetic-B", tokens.accessToken()); assertEquals(b, cache.cachedProfile())
            val restarted = TokenStore(context); restarted.load()
            val restoredCache = ShiftCache(context); restoredCache.loadProfile()
            assertEquals("synthetic-B", restarted.accessToken()); assertEquals(b, restoredCache.cachedProfile())
        } finally { tokens.clear(); cache.rememberProfile(null) }
    }
    @Test fun BInstalledAfterARemovalInvalidatesProfileClearAndSignedOutPublication() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokens = TokenStore(context).also { it.clear() }; val cache = ShiftCache(context)
        val a = profile("A"); val b = profile("B")
        try {
            tokens.save("synthetic-A", "synthetic-A-refresh"); cache.rememberProfile(a)
            val removal = requireNotNull(tokens.captureLogoutLease(tokens.refreshLease()))
            assertTrue(tokens.clearForLogoutIfCurrent(removal))
            tokens.save("synthetic-B", "synthetic-B-refresh"); cache.rememberProfile(b)
            assertFalse(tokens.clearForLogoutIfCurrent(removal))
            assertFalse(cache.forgetOriginalProfile(a) { tokens.isCurrentLogoutRemoval(removal) })
            assertFalse(tokens.publishIfCurrentLogoutRemoval(removal) { fail("Stale A must not publish SignedOut") })
            assertEquals("synthetic-B", tokens.accessToken()); assertEquals(b, cache.cachedProfile())
        } finally { tokens.clear(); cache.rememberProfile(null) }
    }
    @Test fun sameALoginRefreshAndRepeatedOwnedClearRemainRecoverable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokens = TokenStore(context).also { it.clear() }; val cache = ShiftCache(context)
        val a = profile("A")
        try {
            tokens.save("synthetic-A", "synthetic-A-refresh"); cache.rememberProfile(a)
            val original = requireNotNull(tokens.refreshLease())
            val removal = requireNotNull(tokens.captureLogoutLease(original))
            assertTrue(tokens.saveRefreshedIfCurrent(original, "synthetic-A-rotated", "synthetic-A-refresh-rotated"))
            assertTrue(tokens.clearForLogoutIfCurrent(removal))
            assertTrue(tokens.clearForLogoutIfCurrent(removal))
            assertTrue(cache.forgetOriginalProfile(a) { tokens.isCurrentLogoutRemoval(removal) })
            assertTrue(cache.forgetOriginalProfile(a) { tokens.isCurrentLogoutRemoval(removal) })
            var published = false
            assertTrue(tokens.publishIfCurrentLogoutRemoval(removal) { published = true })
            assertTrue(published); assertNull(tokens.accessToken()); assertNull(cache.cachedProfile())
        } finally { tokens.clear(); cache.rememberProfile(null) }
    }
}
