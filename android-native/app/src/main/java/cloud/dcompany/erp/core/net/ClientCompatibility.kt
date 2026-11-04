package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.update.DirectUpdateMetadataResult
import cloud.dcompany.erp.core.update.validateDirectUpdateMetadata
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal const val DEFAULT_COMPATIBILITY_CHECK_TIMEOUT_MILLIS = 5_000L

data class ClientUpdateNotice(
    val message: String,
    val updateUrl: String?,
    val currentVersionCode: Int?,
    val minimumSupportedVersionCode: Int?,
    val latestVersionCode: Int?,
    val policyRevision: Int = 0,
    val latestVersionName: String? = null,
    val releaseNotes: String? = null,
    val apkSha256: String? = null,
    val apkSizeBytes: Long? = null,
    val apkSigningCertSha256: String? = null,
)

/** A decision stamp survives relaxation, so late 426 responses cannot re-block it. */
@kotlinx.serialization.Serializable
data class ClientPolicyAuthority(val revision: Int, val minimum: Int, val required: Boolean)

sealed interface ClientCompatibilityState {
    data object Checking : ClientCompatibilityState
    data object Supported : ClientCompatibilityState
    data class UpdateAvailable(val notice: ClientUpdateNotice) : ClientCompatibilityState
    data class UpdateRequired(val notice: ClientUpdateNotice) : ClientCompatibilityState
}

/**
 * Process-wide compatibility authority.
 *
 * Startup uncertainty deliberately fails open after a short bound: a cafe
 * must still be able to use its saved offline state when the internet is down.
 * A server decision does the opposite — either an explicit update_required
 * response or any HTTP 426 remains blocking for this installed build. The app
 * wires that decision to a small non-sensitive store so process death cannot
 * reopen an obsolete offline workspace; an in-place upgrade clears it by
 * changing BuildConfig.VERSION_CODE. Nothing here mutates authentication,
 * Room, or outbox state.
 */
class ClientCompatibilityGate(
    private val checkCompatibility: suspend () -> ClientCompatibilityResponse,
    // A slow compatibility endpoint must not hold the whole till behind the
    // non-dismissible startup gate. The server's runtime-parity probe is
    // bounded to four seconds; one second of transport/scheduling headroom
    // prevents a valid update offer being cancelled at the client boundary.
    // Normal API calls still enforce HTTP 426 when the endpoint is unavailable.
    private val startupTimeoutMillis: Long = DEFAULT_COMPATIBILITY_CHECK_TIMEOUT_MILLIS,
    private val installedVersionCode: Int = BuildConfig.VERSION_CODE,
    private val optionalUpdateSnoozeMillis: Long = DEFAULT_OPTIONAL_UPDATE_SNOOZE_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    initialRequiredNotice: ClientUpdateNotice? = null,
    private val persistRequiredNotice: (ClientUpdateNotice) -> Unit = {},
    private val clearRequiredNotice: (expectedPolicyRevision: Int) -> Boolean = { true },
    initialPolicyAuthority: ClientPolicyAuthority? = null,
    private val persistPolicyDecision: ((ClientPolicyAuthority, ClientUpdateNotice?) -> Boolean)? = null,
) {
    init {
        require(startupTimeoutMillis > 0) { "Startup compatibility timeout must be positive" }
        require(installedVersionCode > 0) { "Installed version code must be positive" }
        require(optionalUpdateSnoozeMillis > 0) { "Optional update snooze must be positive" }
    }

    private val _state = MutableStateFlow<ClientCompatibilityState>(
        initialRequiredNotice?.let(ClientCompatibilityState::UpdateRequired)
            ?: ClientCompatibilityState.Checking,
    )
    val state: StateFlow<ClientCompatibilityState> = _state.asStateFlow()
    private val checkMutex = Mutex()
    private val stateLock = Any()
    private var optionalUpdateSnooze: OptionalUpdateSnooze? = null
    private var highestAuthority = initialPolicyAuthority
        ?: initialRequiredNotice?.let { ClientPolicyAuthority(it.policyRevision, it.minimumSupportedVersionCode ?: 0, true) }

    private var lastPersistedAuthority = initialPolicyAuthority
    private var lastPersistedRequired = initialRequiredNotice

    private fun accepts(authority: ClientPolicyAuthority): Boolean {
        val seen = highestAuthority ?: return true
        return authority.revision > seen.revision || (authority.revision == seen.revision &&
            authority.required == seen.required && (seen.minimum == 0 || authority.minimum == seen.minimum))
    }

    private fun saveDecision(authority: ClientPolicyAuthority, required: ClientUpdateNotice?): Boolean {
        val currentRequired = (_state.value as? ClientCompatibilityState.UpdateRequired)?.notice
        if (highestAuthority == authority && currentRequired == required &&
            lastPersistedAuthority == authority && lastPersistedRequired == required) return true
        val persist = persistPolicyDecision
        if (persist != null && !runCatching { persist(authority, required) }.getOrDefault(false)) return false
        highestAuthority = authority
        lastPersistedAuthority = authority
        lastPersistedRequired = required
        return true
    }

    suspend fun checkAtStartup() = checkMutex.withLock {
        try {
            val response = withTimeoutOrNull(startupTimeoutMillis) { checkCompatibility() }
            if (response == null) {
                finishUncertainCheck()
            } else {
                apply(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiException) {
            if (failure.status == 426) {
                // ErrorInterceptor normally published the richer 426 details
                // before throwing. Keep that notice (especially update_url)
                // instead of overwriting it with this fallback.
                if (_state.value !is ClientCompatibilityState.UpdateRequired) {
                    requireUpdate(
                        ClientUpdateNotice(
                            message = failure.message ?: DEFAULT_REQUIRED_MESSAGE,
                            updateUrl = null,
                            currentVersionCode = installedVersionCode,
                            minimumSupportedVersionCode = null,
                            latestVersionCode = null,
                        ),
                    )
                }
            } else {
                finishUncertainCheck()
            }
        } catch (_: Exception) {
            // DNS, TLS, malformed proxy responses, and serialization drift are
            // not proof that the installed build is unsafe. Normal API calls
            // remain protected by the server's 426 middleware.
            finishUncertainCheck()
        }
    }

    /**
     * Refresh update authority after the app returns to the foreground without
     * putting a cached/offline workspace back behind the startup Checking UI.
     * Timeout and transport uncertainty preserve the current decision; a
     * definitive response or HTTP 426 is still applied immediately.
     */
    suspend fun recheckNonBlocking(): Boolean = checkMutex.withLock {
        try {
            val response = withTimeoutOrNull(startupTimeoutMillis) { checkCompatibility() }
                ?: return@withLock false
            apply(response)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiException) {
            if (failure.status == 426 && _state.value !is ClientCompatibilityState.UpdateRequired) {
                requireUpdate(
                    ClientUpdateNotice(
                        message = failure.message ?: DEFAULT_REQUIRED_MESSAGE,
                        updateUrl = null,
                        currentVersionCode = installedVersionCode,
                        minimumSupportedVersionCode = null,
                        latestVersionCode = null,
                    ),
                )
            }
            false
        } catch (_: Exception) {
            // Optional foreground refreshes are advisory under uncertainty.
            // The server still enforces a definitive 426 on normal requests.
            false
        }
    }

    fun requireUpdate(notice: ClientUpdateNotice) {
        val normalized = notice.copy(policyRevision = notice.policyRevision.coerceAtLeast(0),
            currentVersionCode = notice.currentVersionCode ?: installedVersionCode)
        synchronized(stateLock) {
            if (normalized.currentVersionCode != null && normalized.currentVersionCode != installedVersionCode) return
            val completeRequiredAuthority = normalized.minimumSupportedVersionCode?.let { minimum ->
                minimum > installedVersionCode && normalized.latestVersionCode?.let { it >= minimum } == true
            } == true
            if (normalized.policyRevision == 0 || !completeRequiredAuthority) {
                // An unversioned 426 is uncertainty, not evidence of an old
                // revision. Block conservatively until a complete live probe;
                // retain the known high-water stamp and any richer block.
                if (_state.value is ClientCompatibilityState.UpdateRequired) return
                val provisional = normalized.copy(policyRevision = 0)
                runCatching { persistRequiredNotice(provisional) }
                _state.value = ClientCompatibilityState.UpdateRequired(provisional)
                return
            }
            val authority = ClientPolicyAuthority(normalized.policyRevision, normalized.minimumSupportedVersionCode ?: 0, true)
            if (!accepts(authority)) return
            if (!saveDecision(authority, normalized)) highestAuthority = authority
            if (persistPolicyDecision == null &&
                (_state.value as? ClientCompatibilityState.UpdateRequired)?.notice != normalized) {
                runCatching { persistRequiredNotice(normalized) }
            }
            _state.value = ClientCompatibilityState.UpdateRequired(normalized)
        }
    }

    fun dismissOptionalUpdate() {
        synchronized(stateLock) {
            val current = _state.value
            if (current is ClientCompatibilityState.UpdateAvailable) {
                val versionCode = current.notice.latestVersionCode
                val now = runCatching(elapsedRealtimeMillis).getOrNull()
                optionalUpdateSnooze = if (versionCode != null && now != null && now >= 0) {
                    OptionalUpdateSnooze(versionCode, now)
                } else {
                    null
                }
                _state.value = ClientCompatibilityState.Supported
            }
        }
    }

    private fun apply(response: ClientCompatibilityResponse) {
        val next = when (response.status) {
            "update_required" -> ClientCompatibilityState.UpdateRequired(response.toNotice())
            "update_available" -> {
                // Respect "Later" for a bounded period instead of repeatedly
                // covering the operator's work. A newer version, snooze expiry,
                // clock reset, process restart, or required update shows again.
                if (optionalUpdateIsSnoozed(response.latestVersionCode)) {
                    ClientCompatibilityState.Supported
                } else {
                    ClientCompatibilityState.UpdateAvailable(response.toNotice())
                }
            }
            else -> ClientCompatibilityState.Supported
        }
        synchronized(stateLock) {
            if (response.platform != "android" || response.currentVersionCode != installedVersionCode ||
                response.policyRevision <= 0 || response.minimumSupportedVersionCode !in 1..response.latestVersionCode ||
                response.status !in setOf("supported", "update_available", "update_required")) return
            if (response.status == "update_available" &&
                (response.latestVersionCode <= installedVersionCode ||
                 validateDirectUpdateMetadata(response.toNotice(), installedVersionCode) !is DirectUpdateMetadataResult.Valid)) return
            val required = response.status == "update_required"
            if (required != (installedVersionCode < response.minimumSupportedVersionCode)) return
            val authority = ClientPolicyAuthority(response.policyRevision, response.minimumSupportedVersionCode, required)
            if (!accepts(authority)) return
            val current = _state.value
            if (next is ClientCompatibilityState.UpdateRequired) {
                requireUpdate(next.notice)
                return
            }
            if (current is ClientCompatibilityState.UpdateRequired) {
                // A relaxation requires a strictly newer complete authority. Optional
                // offers may coexist with supported operation on current clients.
                if (response.policyRevision <= current.notice.policyRevision) return
                if (persistPolicyDecision == null && !runCatching {
                        clearRequiredNotice(current.notice.policyRevision)
                    }.getOrDefault(false)) return
            }
            if (!saveDecision(authority, null)) return
            _state.value = next
        }
    }

    private fun optionalUpdateIsSnoozed(versionCode: Int): Boolean = synchronized(stateLock) {
        val snooze = optionalUpdateSnooze ?: return@synchronized false
        if (snooze.versionCode != versionCode) return@synchronized false
        val now = runCatching(elapsedRealtimeMillis).getOrNull()
            ?: return@synchronized false
        if (now < snooze.startedAtElapsedMillis) {
            optionalUpdateSnooze = null
            return@synchronized false
        }
        val active = now - snooze.startedAtElapsedMillis < optionalUpdateSnoozeMillis
        if (!active) optionalUpdateSnooze = null
        active
    }

    private fun finishUncertainCheck() {
        _state.update { current ->
            if (current is ClientCompatibilityState.Checking) {
                ClientCompatibilityState.Supported
            } else {
                current
            }
        }
    }

    private fun ClientCompatibilityResponse.toNotice() = ClientUpdateNotice(
        message = message,
        updateUrl = updateUrl,
        currentVersionCode = currentVersionCode,
        minimumSupportedVersionCode = minimumSupportedVersionCode,
        latestVersionCode = latestVersionCode,
        policyRevision = policyRevision.coerceAtLeast(0),
        latestVersionName = latestVersionName,
        releaseNotes = releaseNotes,
        apkSha256 = apkSha256,
        apkSizeBytes = apkSizeBytes,
        apkSigningCertSha256 = apkSigningCertSha256,
    )

    private companion object {
        const val DEFAULT_OPTIONAL_UPDATE_SNOOZE_MILLIS = 4L * 60L * 60L * 1_000L
        const val DEFAULT_REQUIRED_MESSAGE =
            "This app version is no longer compatible with the ERP server. Update before continuing."
    }
}

private data class OptionalUpdateSnooze(
    val versionCode: Int,
    val startedAtElapsedMillis: Long,
)

/** Only an unambiguous HTTPS URL is ever handed to Android's external browser. */
fun safeHttpsUpdateUrl(raw: String?): String? {
    val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return uri.toASCIIString()
}
