package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.BuildConfig
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class ClientUpdateNotice(
    val message: String,
    val updateUrl: String?,
    val currentVersionCode: Int?,
    val minimumSupportedVersionCode: Int?,
    val latestVersionCode: Int?,
)

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
 * response or any HTTP 426 remains blocking for the life of this process.
 * Nothing here mutates authentication, Room, or outbox state.
 */
class ClientCompatibilityGate(
    private val checkCompatibility: suspend () -> ClientCompatibilityResponse,
    // A slow compatibility endpoint must not hold the whole till behind the
    // non-dismissible startup gate. Normal API calls still enforce HTTP 426,
    // so three seconds preserves the fail-safe update path while letting an
    // offline cafe reach its saved workspace promptly.
    private val startupTimeoutMillis: Long = 3_000L,
) {
    init {
        require(startupTimeoutMillis > 0) { "Startup compatibility timeout must be positive" }
    }

    private val _state = MutableStateFlow<ClientCompatibilityState>(ClientCompatibilityState.Checking)
    val state: StateFlow<ClientCompatibilityState> = _state.asStateFlow()
    private val checkMutex = Mutex()
    private var dismissedOptionalVersionCode: Int? = null

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
                            currentVersionCode = BuildConfig.VERSION_CODE,
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
    suspend fun recheckNonBlocking() = checkMutex.withLock {
        if (_state.value is ClientCompatibilityState.UpdateRequired) return@withLock
        try {
            val response = withTimeoutOrNull(startupTimeoutMillis) { checkCompatibility() }
                ?: return@withLock
            apply(response)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiException) {
            if (failure.status == 426 && _state.value !is ClientCompatibilityState.UpdateRequired) {
                requireUpdate(
                    ClientUpdateNotice(
                        message = failure.message ?: DEFAULT_REQUIRED_MESSAGE,
                        updateUrl = null,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        minimumSupportedVersionCode = null,
                        latestVersionCode = null,
                    ),
                )
            }
        } catch (_: Exception) {
            // Optional foreground refreshes are advisory under uncertainty.
            // The server still enforces a definitive 426 on normal requests.
        }
    }

    fun requireUpdate(notice: ClientUpdateNotice) {
        _state.value = ClientCompatibilityState.UpdateRequired(notice)
    }

    fun dismissOptionalUpdate() {
        _state.update { current ->
            if (current is ClientCompatibilityState.UpdateAvailable) {
                dismissedOptionalVersionCode = current.notice.latestVersionCode
                ClientCompatibilityState.Supported
            } else {
                current
            }
        }
    }

    private fun apply(response: ClientCompatibilityResponse) {
        val next = when (response.status) {
            "update_required" -> ClientCompatibilityState.UpdateRequired(response.toNotice())
            "update_available" -> {
                // A foreground compatibility refresh runs every fifteen
                // minutes. Respect "Later" for this release for the lifetime
                // of the process instead of repeatedly covering the operator's
                // work with the same advisory banner. A newer version code is
                // still shown immediately, and required updates always win.
                if (response.latestVersionCode == dismissedOptionalVersionCode) {
                    ClientCompatibilityState.Supported
                } else {
                    ClientCompatibilityState.UpdateAvailable(response.toNotice())
                }
            }
            else -> ClientCompatibilityState.Supported
        }
        _state.update { current ->
            // A definitive 426 may arrive from another request while the
            // startup probe is in flight. Never let its later result reopen
            // the app in that race.
            if (current is ClientCompatibilityState.UpdateRequired) current else next
        }
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
    )

    private companion object {
        const val DEFAULT_REQUIRED_MESSAGE =
            "This app version is no longer compatible with the ERP server. Update before continuing."
    }
}

/** Only an unambiguous HTTPS URL is ever handed to Android's external browser. */
fun safeHttpsUpdateUrl(raw: String?): String? {
    val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return uri.toASCIIString()
}
