package cloud.dcompany.erp.core.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TokenStoreSecurityTest {

    @Test
    fun tokensSurviveRestartWithoutAppearingInPreferencesPlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val access = "access-token-that-must-never-appear-on-disk"
        val refresh = "refresh-token-that-must-never-appear-on-disk"
        val first = TokenStore(context)
        first.clear()
        first.save(access, refresh)

        val restored = TokenStore(context)
        runBlocking { restored.load() }

        assertEquals(access, restored.accessToken())
        assertEquals(refresh, restored.refreshToken())
        val preferencesFile = File(
            context.applicationInfo.dataDir,
            "shared_prefs/dcompany_secure_session.xml",
        )
        val persisted = preferencesFile.readText()
        assertFalse(persisted.contains(access))
        assertFalse(persisted.contains(refresh))

        restored.clear()
        val signedOut = TokenStore(context)
        runBlocking { signedOut.load() }
        assertNull(signedOut.accessToken())
        assertNull(signedOut.refreshToken())

        // clear() also rotates the key. A fresh login after sign-out must not
        // inherit an invalid/poisoned alias from the previous session.
        signedOut.save("replacement-access", "replacement-refresh")
        val replacement = TokenStore(context)
        runBlocking { replacement.load() }
        assertEquals("replacement-access", replacement.accessToken())
        assertEquals("replacement-refresh", replacement.refreshToken())
        replacement.clear()
    }

    @Test
    fun corruptedEnvelopeFailsSignedOutAndDoesNotPoisonNextLogin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TokenStore(context)
        store.clear()
        store.save("old-access", "old-refresh")
        val preferences = context.getSharedPreferences(
            "dcompany_secure_session",
            android.content.Context.MODE_PRIVATE,
        )
        assertTrue(
            preferences.edit()
                .putString("session_envelope", "not-a-valid-gcm-envelope")
                .commit(),
        )

        val invalidated = TokenStore(context)
        runBlocking { invalidated.load() }
        assertNull(invalidated.accessToken())
        assertNull(invalidated.refreshToken())
        assertTrue(preferences.getBoolean("session_cleared", false))
        assertNull(preferences.getString("session_envelope", null))

        invalidated.save("new-access", "new-refresh")
        val restored = TokenStore(context)
        runBlocking { restored.load() }
        assertEquals("new-access", restored.accessToken())
        assertEquals("new-refresh", restored.refreshToken())
        restored.clear()
    }

    @Test
    fun clearWinsWhenEncryptedLoadAlreadyReadAndDecryptedOldCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = TokenStore(context)
        seed.clear()
        seed.save("stale-access", "stale-refresh")

        val observer = BlockingLoadObserver(TokenLoadSource.ENCRYPTED)
        val loadingStore = TokenStore(context, observer)
        val loadJob = launch(Dispatchers.Default) { loadingStore.load() }

        withTimeout(5_000) { observer.reached.await() }
        try {
            // Use another instance to prove the process-wide coordination also
            // covers Android component recreation, not only one object monitor.
            withContext(Dispatchers.IO) { TokenStore(context).clear() }
        } finally {
            observer.release.complete(Unit)
        }
        withTimeout(5_000) { loadJob.join() }

        assertNull(loadingStore.accessToken())
        assertNull(loadingStore.refreshToken())
        assertFalse(loadingStore.hasSession())

        val restarted = TokenStore(context)
        restarted.load()
        assertFalse(restarted.hasSession())
        restarted.clear()
    }

    @Test
    fun clearWinsWhenLegacyMigrationAlreadyReadOldPlaintextCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Start from the exact pre-migration shape: no encrypted envelope and
        // no sign-out tombstone, with credentials available only to legacy IO.
        withContext(Dispatchers.IO) { TokenStore(context).clear() }
        val securePreferences = context.getSharedPreferences(
            "dcompany_secure_session",
            android.content.Context.MODE_PRIVATE,
        )
        assertTrue(securePreferences.edit().clear().commit())

        val legacy = InMemoryLegacySessionStorage(
            StoredSession("legacy-access", "legacy-refresh"),
        )
        val observer = BlockingLoadObserver(TokenLoadSource.LEGACY)
        val loadingStore = TokenStore(context, legacy, observer)
        val loadJob = launch(Dispatchers.Default) { loadingStore.load() }

        withTimeout(5_000) { observer.reached.await() }
        try {
            // clear() removes the legacy value, but load() still owns the old
            // value it read before this call. The final generation/tombstone
            // gate is what must prevent that snapshot from being migrated.
            withContext(Dispatchers.IO) { loadingStore.clear() }
        } finally {
            observer.release.complete(Unit)
        }
        withTimeout(5_000) { loadJob.join() }

        assertNull(loadingStore.accessToken())
        assertNull(loadingStore.refreshToken())
        assertFalse(loadingStore.hasSession())
        assertNull(legacy.current)
        assertTrue(securePreferences.getBoolean("session_cleared", false))
        assertNull(securePreferences.getString("session_envelope", null))

        val restarted = TokenStore(context)
        restarted.load()
        assertFalse(restarted.hasSession())
        restarted.clear()
    }

    @Test
    fun failedPostLoginValidationRollsBackMemoryAndDurableSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TokenStore(context).also { it.clear() }
        val login = store.installForLogin("unverified-access", "unverified-refresh")

        assertTrue(store.rollbackLoginIfCurrent(login))
        assertFalse(store.hasSession())
        assertNull(store.accessToken())
        assertNull(store.refreshToken())

        val restarted = TokenStore(context)
        restarted.load()
        assertFalse(restarted.hasSession())
        restarted.clear()
    }

    @Test
    fun failedPostLoginValidationAlsoRollsBackRefreshFromSameLogin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TokenStore(context).also { it.clear() }
        val login = store.installForLogin("login-access", "login-refresh")
        val refresh = checkNotNull(store.refreshLease())
        assertTrue(
            store.saveRefreshedIfCurrent(
                refresh,
                access = "rotated-access",
                refresh = "rotated-refresh",
            ),
        )

        assertTrue(store.rollbackLoginIfCurrent(login))
        assertFalse(store.hasSession())

        val restarted = TokenStore(context)
        restarted.load()
        assertFalse(restarted.hasSession())
        restarted.clear()
    }

    @Test
    fun staleFailedLoginRollbackCannotClearNewerExplicitLogin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staleStore = TokenStore(context).also { it.clear() }
        val staleLogin = staleStore.installForLogin("stale-access", "stale-refresh")

        // Use a separate instance to model Android component recreation. Its
        // in-memory session is intentionally not visible through staleStore.
        val currentStore = TokenStore(context)
        currentStore.installForLogin("current-access", "current-refresh")

        assertFalse(staleStore.rollbackLoginIfCurrent(staleLogin))
        assertEquals("current-access", currentStore.accessToken())
        assertEquals("current-refresh", currentStore.refreshToken())

        val restarted = TokenStore(context)
        restarted.load()
        assertEquals("current-access", restarted.accessToken())
        assertEquals("current-refresh", restarted.refreshToken())
        restarted.clear()
    }

    private class BlockingLoadObserver(
        private val expectedSource: TokenLoadSource,
    ) : TokenStoreLoadObserver {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun afterCandidateRead(source: TokenLoadSource) {
            if (source != expectedSource) return
            reached.complete(Unit)
            release.await()
        }
    }

    private class InMemoryLegacySessionStorage(
        initial: StoredSession?,
    ) : LegacySessionStorage {
        @Volatile
        var current: StoredSession? = initial
            private set

        override suspend fun read(): StoredSession? = current

        override suspend fun clear() {
            current = null
        }
    }
}
