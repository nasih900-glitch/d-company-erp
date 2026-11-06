package cloud.dcompany.erp.core.remote

import android.content.Context
import android.content.SharedPreferences
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class RemoteDeviceIdentityUnavailable : java.io.IOException("Remote support device identity could not be read safely. Remote support is paused; ask an Owner to review the saved identity.")

internal enum class RemoteDeviceKeyStatus(val storedValue: String) {
    CREATED("created"),
    PENDING("pending"),
    ACTIVE("active"),
    REVOKED("revoked"),
    EXPIRED("expired");

    companion object {
        fun fromStored(value: String?): RemoteDeviceKeyStatus? =
            entries.firstOrNull { it.storedValue == value }
    }
}

@Serializable
internal data class PersistedRemoteDeviceIdentity(
    val companyId: String,
    val installationId: String,
    val keyId: String,
    val enrollmentId: String,
    val status: String,
    val fingerprintSha256: String,
    val pairingCode: String? = null,
    val serverTime: String? = null,
    val enrolledAt: String? = null,
    val pendingExpiresAt: String? = null,
    val approvedAt: String? = null,
    val revokedAt: String? = null,
) {
    val keyStatus: RemoteDeviceKeyStatus?
        get() = RemoteDeviceKeyStatus.fromStored(status)
}

@Serializable
private data class PersistedRemoteDeviceIdentityState(
    val format: Int = DEVICE_IDENTITY_STORE_FORMAT,
    val identities: List<PersistedRemoteDeviceIdentity> = emptyList(),
    val retiredKeyIds: List<String> = emptyList(),
)

internal fun validRemoteDeviceIdentity(
    value: PersistedRemoteDeviceIdentity,
): PersistedRemoteDeviceIdentity? {
    if (
        !isCanonicalUuidV4(value.companyId) ||
        !isCanonicalUuidV4(value.installationId) ||
        !isCanonicalUuidV4(value.keyId) ||
        !isCanonicalUuidV4(value.enrollmentId) ||
        !value.fingerprintSha256.matches(LOWERCASE_DEVICE_FINGERPRINT)
    ) return null
    val status = value.keyStatus ?: return null
    if (value.pairingCode != null && groupedRemotePairingCode(value.pairingCode) == null) return null
    if (status == RemoteDeviceKeyStatus.CREATED) {
        if (
            value.pairingCode != null ||
            value.serverTime != null ||
            value.enrolledAt != null ||
            value.pendingExpiresAt != null ||
            value.approvedAt != null ||
            value.revokedAt != null
        ) return null
        return value
    }
    val serverTime = parseRemoteInstant(value.serverTime) ?: return null
    val enrolledAt = parseRemoteInstant(value.enrolledAt) ?: return null
    val pendingExpiresAt = parseRemoteInstant(value.pendingExpiresAt) ?: return null
    if (!pendingExpiresAt.isAfter(enrolledAt) || pendingExpiresAt.isAfter(enrolledAt.plusSeconds(900))) {
        return null
    }
    if (status == RemoteDeviceKeyStatus.PENDING) {
        if (
            value.pairingCode == null ||
            value.approvedAt != null ||
            value.revokedAt != null ||
            !pendingExpiresAt.isAfter(serverTime)
        ) return null
    } else if (value.pairingCode != null) {
        return null
    }
    if (value.approvedAt != null && parseRemoteInstant(value.approvedAt) == null) return null
    if (value.revokedAt != null && parseRemoteInstant(value.revokedAt) == null) return null
    if (status == RemoteDeviceKeyStatus.ACTIVE && value.approvedAt == null) return null
    if (status == RemoteDeviceKeyStatus.REVOKED && value.revokedAt == null) return null
    if (serverTime.isBefore(enrolledAt.minusSeconds(30))) return null
    return value
}

internal fun remotePendingDeviceKeyDeadline(
    serverTimeRaw: String,
    pendingExpiresAtRaw: String,
    nowElapsedMillis: Long,
): Long? {
    val serverTime = parseRemoteInstant(serverTimeRaw) ?: return null
    val expiresAt = parseRemoteInstant(pendingExpiresAtRaw) ?: return null
    val remaining = runCatching { Duration.between(serverTime, expiresAt).toMillis() }
        .getOrNull()
        ?: return null
    if (remaining !in 1L..900_000L || nowElapsedMillis < 0L) return null
    return nowElapsedMillis + remaining
}

/** Tenant-scoped public key metadata; private material never leaves AndroidKeyStore. */
internal class RemoteDeviceIdentityStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    internal class ExpectedState internal constructor(internal val raw: String, val identity: PersistedRemoteDeviceIdentity) {
        override fun toString() = "ExpectedRemoteDeviceState(<redacted>)"
    }
    /** A malformed/missing record cannot be checkpointed as though it were an empty store. */
    fun expectedState(identity: PersistedRemoteDeviceIdentity): ExpectedState? = synchronized(lock) {
        val raw = readRawLocked() ?: return@synchronized null
        if (readLocked().identities.firstOrNull { it.keyId == identity.keyId } != identity) return@synchronized null
        ExpectedState(raw, identity)
    }
    fun isCurrent(expected: ExpectedState): Boolean = synchronized(lock) {
        readRawLocked() == expected.raw && readLocked().identities.firstOrNull { it.keyId == expected.identity.keyId } == expected.identity
    }
    fun compareAndSet(expected: ExpectedState, updated: PersistedRemoteDeviceIdentity): Boolean = synchronized(lock) {
        val previous = expected.identity
        val valid = validRemoteDeviceIdentity(updated) ?: return@synchronized false
        if (readRawLocked() != expected.raw ||
            previous.companyId != valid.companyId || previous.installationId != valid.installationId ||
            previous.keyId != valid.keyId || previous.enrollmentId != valid.enrollmentId ||
            previous.fingerprintSha256 != valid.fingerprintSha256) return@synchronized false
        val current = readLocked()
        if (current.identities.firstOrNull { it.keyId == previous.keyId } != previous) return@synchronized false
        val promoting = valid.keyStatus == RemoteDeviceKeyStatus.ACTIVE && previous.keyStatus != RemoteDeviceKeyStatus.ACTIVE
        val superseded = if (promoting) current.identities.filter {
            it.companyId == valid.companyId && it.installationId == valid.installationId && it.keyId != valid.keyId
        }.map { it.keyId } else emptyList()
        val retained = current.identities.filterNot {
            it.keyId == valid.keyId || (promoting && it.companyId == valid.companyId && it.installationId == valid.installationId)
        }
        val next = current.copy(identities = retained + valid, retiredKeyIds = (current.retiredKeyIds + superseded).distinct())
        if (next.identities.size > MAX_DEVICE_IDENTITIES || next.retiredKeyIds.size > MAX_RETIRED_DEVICE_KEYS || !validIdentitySet(next.identities)) return@synchronized false
        writeLocked(next)
    }

    fun identities(companyId: String, installationId: String): List<PersistedRemoteDeviceIdentity> =
        synchronized(lock) {
            readLocked().identities.filter {
                it.companyId == companyId && it.installationId == installationId
            }
        }

    fun activeIdentity(companyId: String, installationId: String): PersistedRemoteDeviceIdentity? =
        identities(companyId, installationId).firstOrNull {
            it.keyStatus == RemoteDeviceKeyStatus.ACTIVE
        }

    fun enrollmentCandidate(
        companyId: String,
        installationId: String,
    ): PersistedRemoteDeviceIdentity? = identities(companyId, installationId).firstOrNull {
        it.keyStatus in setOf(RemoteDeviceKeyStatus.CREATED, RemoteDeviceKeyStatus.PENDING)
    }

    fun identityForKey(
        companyId: String,
        installationId: String,
        keyId: String,
    ): PersistedRemoteDeviceIdentity? = identities(companyId, installationId)
        .firstOrNull { it.keyId == keyId }

    fun put(identity: PersistedRemoteDeviceIdentity): Boolean = synchronized(lock) {
        val valid = validRemoteDeviceIdentity(identity) ?: return@synchronized false
        val current = readLocked()
        val retained = current.identities.filterNot { it.keyId == valid.keyId }
        if (retained.size >= MAX_DEVICE_IDENTITIES) return@synchronized false
        val next = current.copy(identities = retained + valid)
        if (!validIdentitySet(next.identities)) return@synchronized false
        writeLocked(next)
    }

    /** Atomically selects a newly approved key and journals every superseded alias for deletion. */
    fun promote(identity: PersistedRemoteDeviceIdentity): Boolean = synchronized(lock) {
        val valid = validRemoteDeviceIdentity(identity)
            ?.takeIf { it.keyStatus == RemoteDeviceKeyStatus.ACTIVE }
            ?: return@synchronized false
        val current = readLocked()
        val superseded = current.identities.filter {
            it.companyId == valid.companyId &&
                it.installationId == valid.installationId &&
                it.keyId != valid.keyId
        }.map(PersistedRemoteDeviceIdentity::keyId)
        val retained = current.identities.filterNot {
            it.companyId == valid.companyId && it.installationId == valid.installationId
        }
        val retired = (current.retiredKeyIds + superseded).distinct()
        if (retired.size > MAX_RETIRED_DEVICE_KEYS) return@synchronized false
        val next = current.copy(
            identities = retained + valid,
            retiredKeyIds = retired,
        )
        writeLocked(next)
    }

    fun retiredKeyIds(): List<String> = synchronized(lock) { readLocked().retiredKeyIds }

    fun acknowledgeRetiredKeyDeleted(keyId: String): Boolean = synchronized(lock) {
        val current = readLocked()
        if (keyId !in current.retiredKeyIds) return@synchronized true
        writeLocked(current.copy(retiredKeyIds = current.retiredKeyIds - keyId))
    }

    fun remove(companyId: String, installationId: String, keyId: String): Boolean =
        synchronized(lock) {
            val current = readLocked()
            val retained = current.identities.filterNot {
                it.companyId == companyId &&
                    it.installationId == installationId &&
                    it.keyId == keyId
            }
            if (retained.size == current.identities.size) return@synchronized true
            writeLocked(current.copy(identities = retained))
        }

    private fun readRawLocked(): String? = try { prefs.getString(KEY_STATE, null) }
        catch (_: Exception) { throw RemoteDeviceIdentityUnavailable() }

    private fun readLocked(): PersistedRemoteDeviceIdentityState {
        val raw = readRawLocked() ?: return PersistedRemoteDeviceIdentityState()
        val decoded = runCatching {
            json.decodeFromString<PersistedRemoteDeviceIdentityState>(raw)
        }.getOrElse { throw RemoteDeviceIdentityUnavailable() }
        if (
            decoded.format != DEVICE_IDENTITY_STORE_FORMAT ||
            decoded.identities.size > MAX_DEVICE_IDENTITIES ||
            decoded.retiredKeyIds.size > MAX_RETIRED_DEVICE_KEYS ||
            decoded.retiredKeyIds.any { !isCanonicalUuidV4(it) } ||
            decoded.retiredKeyIds.distinct().size != decoded.retiredKeyIds.size ||
            decoded.identities.map(PersistedRemoteDeviceIdentity::keyId).distinct().size !=
            decoded.identities.size ||
            decoded.identities.map(PersistedRemoteDeviceIdentity::enrollmentId).distinct().size !=
            decoded.identities.size ||
            decoded.identities.any { validRemoteDeviceIdentity(it) == null } ||
            !validIdentitySet(decoded.identities)
        ) throw RemoteDeviceIdentityUnavailable()
        return decoded
    }

    private fun writeLocked(value: PersistedRemoteDeviceIdentityState): Boolean = prefs.edit()
        .putString(KEY_STATE, json.encodeToString(value))
        .commit()

    private companion object {
        val lock = Any()
        const val PREFS_NAME = "dcompany_remote_device_identity"
        const val KEY_STATE = "state"
    }
}

private fun validIdentitySet(identities: List<PersistedRemoteDeviceIdentity>): Boolean =
    identities.groupBy { it.companyId to it.installationId }.values.all { scoped ->
        scoped.count { it.keyStatus == RemoteDeviceKeyStatus.ACTIVE } <= 1 &&
            scoped.count {
                it.keyStatus in setOf(RemoteDeviceKeyStatus.CREATED, RemoteDeviceKeyStatus.PENDING)
            } <= 1
    }

private const val DEVICE_IDENTITY_STORE_FORMAT = 1
private const val MAX_DEVICE_IDENTITIES = 16
private const val MAX_RETIRED_DEVICE_KEYS = 16
private val LOWERCASE_DEVICE_FINGERPRINT = Regex("[0-9a-f]{64}")
