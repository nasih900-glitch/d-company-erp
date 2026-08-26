package cloud.dcompany.erp.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.tokenDataStore by preferencesDataStore(name = "dcompany_session")

// Deliberately not a data class: generated toString()/copy() methods would
// make bearer credentials too easy to leak into diagnostics or crash logs.
internal class StoredSession(
    val access: String,
    val refresh: String,
    val lineage: Any = Any(),
)

/**
 * Exact in-memory session snapshot captured before a refresh request starts.
 * The opaque lineage survives token rotation but never survives sign-out or
 * an explicit login, so an old request cannot install or clear another user's
 * credentials.
 */
internal class SessionRefreshLease internal constructor(
    internal val session: StoredSession,
    internal val mutationVersion: Long,
) {
    val accessToken: String get() = session.access
    val refreshToken: String get() = session.refresh

    internal fun isSameSnapshot(other: SessionRefreshLease): Boolean =
        session === other.session && mutationVersion == other.mutationVersion
}

/**
 * Identifies one explicit login attempt without retaining either credential.
 *
 * Unlike [SessionRefreshLease], this lease deliberately follows token rotation:
 * post-login validation must be able to roll back a refresh that happened while
 * `/auth/me` was in flight. A later explicit login advances the process-wide
 * generation, so a stale store instance cannot clear the newer cashier's session.
 */
internal class LoginSessionLease internal constructor(
    internal val lineage: Any,
    internal val explicitLoginGeneration: Long,
)

/**
 * Opaque identity for one installed login. It follows access-token rotation,
 * but never survives sign-out or a new explicit login — including a relogin
 * by the same employee.
 */
internal class PricingSessionLease internal constructor(
    internal val lineage: Any,
    internal val owner: OutboxOwnerIdentity,
)

/**
 * Kept behind a small interface so migration ordering can be tested without
 * writing the old plaintext format back to a production device.
 */
internal interface LegacySessionStorage {
    suspend fun read(): StoredSession?
    suspend fun clear()
}

private class DataStoreLegacySessionStorage(
    private val context: Context,
) : LegacySessionStorage {
    override suspend fun read(): StoredSession? {
        val legacy = context.tokenDataStore.data.first()
        val access = legacy[TokenStore.LEGACY_ACCESS_KEY]
        val refresh = legacy[TokenStore.LEGACY_REFRESH_KEY]
        return if (!access.isNullOrBlank() && !refresh.isNullOrBlank()) {
            StoredSession(access, refresh)
        } else {
            null
        }
    }

    override suspend fun clear() {
        context.tokenDataStore.edit {
            it.remove(TokenStore.LEGACY_ACCESS_KEY)
            it.remove(TokenStore.LEGACY_REFRESH_KEY)
        }
    }
}

internal enum class TokenLoadSource {
    ENCRYPTED,
    LEGACY,
}

/** Internal observation point used only by deterministic concurrency tests. */
internal fun interface TokenStoreLoadObserver {
    suspend fun afterCandidateRead(source: TokenLoadSource)
}

private object NoOpTokenStoreLoadObserver : TokenStoreLoadObserver {
    override suspend fun afterCandidateRead(source: TokenLoadSource) = Unit
}

/**
 * Keystore-backed access + refresh token persistence.
 *
 * The previous implementation wrote both bearer credentials directly into a
 * Preferences DataStore. This store keeps only an AES-GCM envelope on disk;
 * its non-exportable key lives in AndroidKeyStore. Existing installations are
 * migrated once, and the legacy plaintext keys are removed only after the
 * encrypted write succeeds.
 */
class TokenStore internal constructor(
    context: Context,
    private val legacyStorage: LegacySessionStorage,
    private val loadObserver: TokenStoreLoadObserver,
) {

    constructor(context: Context) : this(
        context = context,
        legacyStorage = DataStoreLegacySessionStorage(context.applicationContext),
        loadObserver = NoOpTokenStoreLoadObserver,
    )

    internal constructor(
        context: Context,
        loadObserver: TokenStoreLoadObserver,
    ) : this(
        context = context,
        legacyStorage = DataStoreLegacySessionStorage(context.applicationContext),
        loadObserver = loadObserver,
    )

    private val context = context.applicationContext
    private val securePreferences = this.context.getSharedPreferences(
        SECURE_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    // One volatile object prevents request interceptors from ever observing a
    // new access token paired with an old refresh token (or vice versa).
    @Volatile private var cachedSession: StoredSession? = null
    @Volatile private var legacyCleanupComplete: Boolean = false

    suspend fun load() {
        val loadVersion = currentMutationVersion()

        if (securePreferences.getBoolean(SESSION_CLEARED, false)) {
            // A durable sign-out tombstone takes precedence over any legacy
            // plaintext left behind by a failed/aborted DataStore cleanup.
            // Without this gate a later restart could silently migrate those
            // old credentials and resurrect a session the cashier cleared.
            val tombstoneStillCurrent = synchronized(SESSION_MUTATION_LOCK) {
                if (
                    sessionMutationVersion != loadVersion ||
                    !securePreferences.getBoolean(SESSION_CLEARED, false)
                ) {
                    false
                } else {
                    cachedSession = null
                    true
                }
            }
            if (tombstoneStillCurrent) runCatching { clearLegacyPlaintext() }
            return
        }

        val encrypted = securePreferences.getString(ENCRYPTED_SESSION, null)
        if (encrypted != null) {
            val restored = runCatching { decrypt(encrypted) }.getOrNull()
            if (restored != null) {
                loadObserver.afterCandidateRead(TokenLoadSource.ENCRYPTED)
                val published = synchronized(SESSION_MUTATION_LOCK) {
                    if (
                        sessionMutationVersion != loadVersion ||
                        securePreferences.getBoolean(SESSION_CLEARED, false) ||
                        securePreferences.getString(ENCRYPTED_SESSION, null) != encrypted
                    ) {
                        false
                    } else {
                        cachedSession = restored
                        true
                    }
                }
                // The encrypted envelope is authoritative. A transient
                // DataStore cleanup failure must not crash-loop startup; the
                // next load retries removal before any legacy value is used.
                if (published) runCatching { clearLegacyPlaintext() }
                return
            }

            // A restored backup cannot decrypt with another device's key, and
            // a damaged envelope must never crash-loop the till. Fail signed
            // out, destroy the unusable material, and preserve the separate
            // outbox-owner gate so another employee cannot sync pending work.
            val invalidated = synchronized(SESSION_MUTATION_LOCK) {
                if (
                    sessionMutationVersion != loadVersion ||
                    securePreferences.getBoolean(SESSION_CLEARED, false) ||
                    securePreferences.getString(ENCRYPTED_SESSION, null) != encrypted
                ) {
                    null
                } else {
                    cachedSession = null
                    val result = eraseDurableSessionLocked()
                    sessionMutationVersion += 1
                    result
                }
            }
            if (invalidated != null) {
                // Cleanup is best effort here. The tombstone/envelope removal
                // above is authoritative and load must still fail signed out.
                runCatching { clearLegacyPlaintext() }
            }
            return
        }

        val legacy = legacyStorage.read()
        if (legacy != null) loadObserver.afterCandidateRead(TokenLoadSource.LEGACY)
        val migrated = if (legacy != null) {
            runCatching {
                synchronized(SESSION_MUTATION_LOCK) {
                    if (
                        sessionMutationVersion != loadVersion ||
                        securePreferences.getBoolean(SESSION_CLEARED, false) ||
                        securePreferences.getString(ENCRYPTED_SESSION, null) != null
                    ) {
                        false
                    } else {
                        saveLocked(legacy)
                        true
                    }
                }
            }.getOrDefault(false)
        } else {
            synchronized(SESSION_MUTATION_LOCK) {
                if (
                    sessionMutationVersion == loadVersion &&
                    !securePreferences.getBoolean(SESSION_CLEARED, false) &&
                    securePreferences.getString(ENCRYPTED_SESSION, null) == null
                ) {
                    cachedSession = null
                }
            }
            false
        }
        if (legacy != null && !migrated) {
            // Keep plaintext only so migration can retry next launch, but do
            // not activate it in memory or overwrite a concurrent sign-out.
            return
        }
        runCatching { clearLegacyPlaintext() }
    }

    fun accessToken(): String? = cachedSession?.access
    fun refreshToken(): String? = cachedSession?.refresh

    internal fun currentPricingSession(): PricingSessionLease? =
        synchronized(SESSION_MUTATION_LOCK) {
            cachedSession?.let { session ->
                AccessTokenIdentityParser.parse(session.access)?.let { owner ->
                    PricingSessionLease(session.lineage, owner)
                }
            }
        }

    internal fun isCurrent(lease: PricingSessionLease): Boolean =
        synchronized(SESSION_MUTATION_LOCK) {
            cachedSession?.lineage === lease.lineage
        }

    internal fun refreshLease(): SessionRefreshLease? = synchronized(SESSION_MUTATION_LOCK) {
        cachedSession?.let { SessionRefreshLease(it, sessionMutationVersion) }
    }

    internal fun isCurrent(lease: SessionRefreshLease): Boolean =
        synchronized(SESSION_MUTATION_LOCK) { isCurrentLocked(lease) }

    /**
     * Returns a rotated access token only when it belongs to the same logical
     * login as [lease]. An explicit login always has a new lineage and must
     * never receive an old request from the previous cashier.
     */
    internal fun currentAccessFor(lease: SessionRefreshLease): String? =
        synchronized(SESSION_MUTATION_LOCK) {
            cachedSession
                ?.takeIf { it.lineage === lease.session.lineage }
                ?.access
        }

    /** True only while this explicit-login lineage is still the installed session. */
    internal fun isCurrent(lease: LoginSessionLease): Boolean =
        synchronized(SESSION_MUTATION_LOCK) {
            explicitLoginGeneration == lease.explicitLoginGeneration &&
                cachedSession?.lineage === lease.lineage
        }

    fun save(access: String, refresh: String) {
        installNewSession(access, refresh)
    }

    /** Persist a new explicit login and return the only lease allowed to undo it. */
    internal fun installForLogin(access: String, refresh: String): LoginSessionLease {
        val installed = installNewSession(access, refresh)
        return LoginSessionLease(
            lineage = installed.session.lineage,
            explicitLoginGeneration = installed.explicitLoginGeneration,
        )
    }

    private fun installNewSession(access: String, refresh: String): InstalledSession {
        require(access.isNotBlank() && refresh.isNotBlank()) {
            "Session credentials cannot be blank"
        }
        val session = StoredSession(access, refresh)
        val installed = synchronized(SESSION_MUTATION_LOCK) {
            saveLocked(session)
            explicitLoginGeneration += 1
            InstalledSession(session, explicitLoginGeneration)
        }
        // Old installs may still have plaintext if a previous migration
        // attempt was interrupted. The encrypted envelope is already durable,
        // so cleanup is best-effort and cannot invalidate this successful save.
        if (!legacyCleanupComplete) {
            runBlocking { runCatching { clearLegacyPlaintext() } }
        }
        return installed
    }

    /**
     * Installs a refresh response only if the exact session that initiated the
     * request is still current. Sign-out, explicit login and another completed
     * refresh all invalidate the lease.
     */
    internal fun saveRefreshedIfCurrent(
        lease: SessionRefreshLease,
        access: String,
        refresh: String,
    ): Boolean {
        require(access.isNotBlank() && refresh.isNotBlank()) {
            "Session credentials cannot be blank"
        }
        val saved = synchronized(SESSION_MUTATION_LOCK) {
            if (!isCurrentLocked(lease)) {
                false
            } else {
                saveLocked(
                    StoredSession(
                        access = access,
                        refresh = refresh,
                        lineage = lease.session.lineage,
                    ),
                )
                true
            }
        }
        if (saved && !legacyCleanupComplete) {
            runBlocking { runCatching { clearLegacyPlaintext() } }
        }
        return saved
    }

    private fun saveLocked(session: StoredSession) {
        val envelope = encrypt(session.access, session.refresh)
        check(
            securePreferences.edit()
                .putString(ENCRYPTED_SESSION, envelope)
                .remove(SESSION_CLEARED)
                .commit(),
        ) { "Secure session could not be persisted" }
        // Publish to request interceptors only after the durable write exists.
        sessionMutationVersion += 1
        cachedSession = session
    }

    fun clear() {
        val durableResult = synchronized(SESSION_MUTATION_LOCK) {
            // Increment while holding the same lock used by load publication.
            // Whichever operation obtains this lock last is authoritative; a
            // load that read credentials earlier can no longer publish them.
            sessionMutationVersion += 1
            explicitLoginGeneration += 1
            cachedSession = null
            eraseDurableSessionLocked()
        }
        finishDurableClear(durableResult)
    }

    /** Clear only the session that received a definitive refresh rejection. */
    internal fun clearIfCurrent(lease: SessionRefreshLease): Boolean {
        val durableResult = synchronized(SESSION_MUTATION_LOCK) {
            if (!isCurrentLocked(lease)) {
                null
            } else {
                sessionMutationVersion += 1
                explicitLoginGeneration += 1
                cachedSession = null
                eraseDurableSessionLocked()
            }
        } ?: return false
        finishDurableClear(durableResult)
        return true
    }

    /**
     * Roll back a login whose post-login validation failed.
     *
     * Refreshes preserve the lineage and are therefore removed too. Sign-out
     * and every newer explicit login replace it, making a late rollback a safe
     * no-op instead of deleting another authenticated session.
     */
    internal fun rollbackLoginIfCurrent(lease: LoginSessionLease): Boolean {
        val durableResult = synchronized(SESSION_MUTATION_LOCK) {
            if (
                explicitLoginGeneration != lease.explicitLoginGeneration ||
                cachedSession?.lineage !== lease.lineage
            ) {
                null
            } else {
                sessionMutationVersion += 1
                explicitLoginGeneration += 1
                cachedSession = null
                eraseDurableSessionLocked()
            }
        } ?: return false
        finishDurableClear(durableResult)
        return true
    }

    fun hasSession(): Boolean = cachedSession != null

    private fun encrypt(access: String, refresh: String): String {
        val plaintext = JSONObject()
            .put("version", 1)
            .put("access", access)
            .put("refresh", refresh)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return try {
            encryptPayload(plaintext)
        } catch (first: Exception) {
            if (first !is GeneralSecurityException && first !is ProviderException) {
                throw first
            }
            // Keystore aliases can survive in an invalid/unrecoverable state
            // after OS restore or provider reset. Rotate once so the next
            // successful login/refresh is not permanently poisoned.
            if (!deleteKey()) {
                throw IllegalStateException(
                    "Invalid secure-session key could not be rotated",
                    first,
                )
            }
            encryptPayload(plaintext)
        }
    }

    private fun encryptPayload(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(SESSION_AAD)
        val ciphertext = cipher.doFinal(plaintext)
        return "${encode(cipher.iv)}.${encode(ciphertext)}"
    }

    private fun decrypt(envelope: String): StoredSession {
        val parts = envelope.split('.', limit = 2)
        require(parts.size == 2) { "Invalid encrypted session envelope" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, decode(parts[0])),
        )
        cipher.updateAAD(SESSION_AAD)
        val payload = JSONObject(cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8))
        require(payload.optInt("version") == 1) { "Unsupported encrypted session version" }
        val access = payload.getString("access")
        val refresh = payload.getString("refresh")
        require(access.isNotBlank() && refresh.isNotBlank()) {
            "Encrypted session credentials are blank"
        }
        return StoredSession(access, refresh)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun eraseDurableSessionLocked(): DurableEraseResult {
        // One atomic preferences commit removes the envelope and writes a
        // tombstone. Even if legacy DataStore cleanup fails, load() will not
        // migrate old plaintext credentials after an explicit sign-out.
        val tombstonePersisted = securePreferences.edit()
            .remove(ENCRYPTED_SESSION)
            .putBoolean(SESSION_CLEARED, true)
            .commit()
        val keyDeleted = deleteKey()
        return DurableEraseResult(tombstonePersisted, keyDeleted)
    }

    private fun finishDurableClear(durableResult: DurableEraseResult) {
        val legacyCleared = runBlocking {
            runCatching { clearLegacyPlaintext() }.isSuccess
        }
        check(durableResult.tombstonePersisted || (durableResult.keyDeleted && legacyCleared)) {
            "Secure session could not be durably cleared"
        }
    }

    private fun isCurrentLocked(lease: SessionRefreshLease): Boolean =
        sessionMutationVersion == lease.mutationVersion && cachedSession === lease.session

    private fun deleteKey(): Boolean = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
            !containsAlias(KEY_ALIAS)
        }
    }.getOrDefault(false)

    private suspend fun clearLegacyPlaintext() {
        legacyStorage.clear()
        legacyCleanupComplete = true
    }

    private fun currentMutationVersion(): Long = synchronized(SESSION_MUTATION_LOCK) {
        sessionMutationVersion
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(
        bytes,
        Base64.NO_WRAP or Base64.URL_SAFE,
    )

    private fun decode(value: String): ByteArray = Base64.decode(
        value,
        Base64.NO_WRAP or Base64.URL_SAFE,
    )

    private data class DurableEraseResult(
        val tombstonePersisted: Boolean,
        val keyDeleted: Boolean,
    )

    private data class InstalledSession(
        val session: StoredSession,
        val explicitLoginGeneration: Long,
    )

    internal companion object {
        /**
         * All TokenStore instances in this process coordinate through the
         * same lock. This matters in tests and during Android component
         * recreation: an older instance must not outlive sign-out and publish
         * a stale disk snapshot into memory.
         */
        private val SESSION_MUTATION_LOCK = Any()
        private var sessionMutationVersion: Long = 0
        private var explicitLoginGeneration: Long = 0

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dcompany.session.aes.v1"
        const val SECURE_PREFERENCES = "dcompany_secure_session"
        const val ENCRYPTED_SESSION = "session_envelope"
        const val SESSION_CLEARED = "session_cleared"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val SESSION_AAD: ByteArray = "cloud.dcompany.erp/session/v1".toByteArray()
        internal val LEGACY_ACCESS_KEY = stringPreferencesKey("access_token")
        internal val LEGACY_REFRESH_KEY = stringPreferencesKey("refresh_token")
    }
}
