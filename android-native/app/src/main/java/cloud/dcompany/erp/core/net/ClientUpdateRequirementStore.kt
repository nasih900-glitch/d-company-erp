package cloud.dcompany.erp.core.net

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val STORE_NAME = "client_update_requirement"
private const val KEY_INSTALLED_VERSION_CODE = "installed_version_code"
private const val KEY_POLICY_REVISION = "policy_revision"
private const val KEY_NOTICE_JSON = "notice_json"
private const val PERSISTENCE_SCHEMA_VERSION = 1

private val persistenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
private data class PersistedClientUpdateRequirement(
    @SerialName("schema_version") val schemaVersion: Int = PERSISTENCE_SCHEMA_VERSION,
    @SerialName("installed_version_code") val installedVersionCode: Int,
    val message: String,
    @SerialName("update_url") val updateUrl: String? = null,
    @SerialName("current_version_code") val currentVersionCode: Int? = null,
    @SerialName("minimum_supported_version_code") val minimumSupportedVersionCode: Int? = null,
    @SerialName("latest_version_code") val latestVersionCode: Int? = null,
    @SerialName("policy_revision") val policyRevision: Int = 0,
    @SerialName("latest_version_name") val latestVersionName: String? = null,
    @SerialName("release_notes") val releaseNotes: String? = null,
    @SerialName("apk_sha256") val apkSha256: String? = null,
    @SerialName("apk_size_bytes") val apkSizeBytes: Long? = null,
    @SerialName("apk_signing_cert_sha256") val apkSigningCertSha256: String? = null,
)

internal sealed interface PersistedRequirementRestore {
    data object None : PersistedRequirementRestore
    data object ClearStaleVersion : PersistedRequirementRestore
    data class Required(val notice: ClientUpdateNotice) : PersistedRequirementRestore
}

internal fun encodePersistedUpdateRequirement(
    installedVersionCode: Int,
    notice: ClientUpdateNotice,
): String = persistenceJson.encodeToString(
    PersistedClientUpdateRequirement.serializer(),
    PersistedClientUpdateRequirement(
        installedVersionCode = installedVersionCode,
        message = notice.message,
        updateUrl = notice.updateUrl,
        currentVersionCode = notice.currentVersionCode,
        minimumSupportedVersionCode = notice.minimumSupportedVersionCode,
        latestVersionCode = notice.latestVersionCode,
        policyRevision = notice.policyRevision.coerceAtLeast(0),
        latestVersionName = notice.latestVersionName,
        releaseNotes = notice.releaseNotes,
        apkSha256 = notice.apkSha256,
        apkSizeBytes = notice.apkSizeBytes,
        apkSigningCertSha256 = notice.apkSigningCertSha256,
    ),
)

/**
 * Pure restore policy kept JVM-testable.
 *
 * The separate SharedPreferences version key is intentional: if the JSON is
 * corrupt, the same installed build must remain blocked, while a genuinely
 * newer in-place install can still prove that the old requirement is stale.
 */
internal fun restorePersistedUpdateRequirement(
    storedInstalledVersionCode: Int?,
    storedPolicyRevision: Int? = null,
    encodedNotice: String?,
    currentInstalledVersionCode: Int,
): PersistedRequirementRestore {
    if (storedInstalledVersionCode == null) return PersistedRequirementRestore.None
    if (storedInstalledVersionCode != currentInstalledVersionCode) {
        return PersistedRequirementRestore.ClearStaleVersion
    }

    val persisted = encodedNotice?.let { raw ->
        runCatching {
            persistenceJson.decodeFromString(
                PersistedClientUpdateRequirement.serializer(),
                raw,
            )
        }.getOrNull()
    }
    if (
        persisted == null ||
        persisted.schemaVersion != PERSISTENCE_SCHEMA_VERSION ||
        persisted.installedVersionCode != currentInstalledVersionCode
    ) {
        return PersistedRequirementRestore.Required(
            ClientUpdateNotice(
                message =
                    "This app build was previously blocked by the ERP server. " +
                        "Reconnect or install the verified update before continuing.",
                updateUrl = null,
                currentVersionCode = currentInstalledVersionCode,
                minimumSupportedVersionCode = null,
                latestVersionCode = null,
                policyRevision = storedPolicyRevision?.coerceAtLeast(0) ?: 0,
            ),
        )
    }

    val restoredPolicyRevision = maxOf(
        persisted.policyRevision.coerceAtLeast(0),
        storedPolicyRevision?.coerceAtLeast(0) ?: 0,
    )
    return PersistedRequirementRestore.Required(
        ClientUpdateNotice(
            message = persisted.message,
            updateUrl = persisted.updateUrl,
            currentVersionCode = persisted.currentVersionCode,
            minimumSupportedVersionCode = persisted.minimumSupportedVersionCode,
            latestVersionCode = persisted.latestVersionCode,
            policyRevision = restoredPolicyRevision,
            latestVersionName = persisted.latestVersionName,
            releaseNotes = persisted.releaseNotes,
            apkSha256 = persisted.apkSha256,
            apkSizeBytes = persisted.apkSizeBytes,
            apkSigningCertSha256 = persisted.apkSigningCertSha256,
        ),
    )
}

/** Persists only public compatibility metadata; no account or business data. */
class ClientUpdateRequirementStore(
    context: Context,
    private val installedVersionCode: Int,
) {
    private val preferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun restore(): ClientUpdateNotice? = synchronized(lock) {
        when (
            val restored = restorePersistedUpdateRequirement(
                storedInstalledVersionCode = if (preferences.contains(KEY_INSTALLED_VERSION_CODE)) {
                    preferences.getInt(KEY_INSTALLED_VERSION_CODE, -1)
                } else {
                    null
                },
                storedPolicyRevision = if (preferences.contains(KEY_POLICY_REVISION)) {
                    preferences.getInt(KEY_POLICY_REVISION, 0)
                } else {
                    null
                },
                encodedNotice = preferences.getString(KEY_NOTICE_JSON, null),
                currentInstalledVersionCode = installedVersionCode,
            )
        ) {
            PersistedRequirementRestore.None -> null
            PersistedRequirementRestore.ClearStaleVersion -> {
                // This build already ignores the stale record, so cleanup does
                // not need to block Application.onCreate().
                preferences.edit().clear().apply()
                null
            }
            is PersistedRequirementRestore.Required -> restored.notice
        }
    }

    @SuppressLint("ApplySharedPref")
    fun persist(notice: ClientUpdateNotice): Boolean = synchronized(lock) {
        // commit() is deliberately synchronous. apply() could lose the block
        // if Android kills the process immediately after the server's 426.
        preferences.edit()
            .putInt(KEY_INSTALLED_VERSION_CODE, installedVersionCode)
            .putInt(KEY_POLICY_REVISION, notice.policyRevision.coerceAtLeast(0))
            .putString(
                KEY_NOTICE_JSON,
                encodePersistedUpdateRequirement(installedVersionCode, notice),
            )
            .commit()
    }

    /** Clear only the exact required policy that the caller reviewed. */
    @SuppressLint("ApplySharedPref")
    fun clearIfPolicyRevision(expectedPolicyRevision: Int): Boolean = synchronized(lock) {
        if (!preferences.contains(KEY_INSTALLED_VERSION_CODE)) return@synchronized false
        if (preferences.getInt(KEY_INSTALLED_VERSION_CODE, -1) != installedVersionCode) {
            return@synchronized false
        }
        val storedRevision = if (preferences.contains(KEY_POLICY_REVISION)) {
            preferences.getInt(KEY_POLICY_REVISION, 0)
        } else {
            0
        }
        if (storedRevision != expectedPolicyRevision.coerceAtLeast(0)) return@synchronized false
        preferences.edit().clear().commit()
    }
}
