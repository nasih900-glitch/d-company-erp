package cloud.dcompany.erp.core.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether this app has recently received an HTTP response from the ERP API. */
internal enum class BackendReachability {
    UNKNOWN,
    REACHABLE,
    UNREACHABLE,
}

/**
 * Process-wide transport signal fed by the one OkHttp error boundary.
 *
 * Android's validated-network capability proves internet access, not that the
 * D Company API, DNS, TLS endpoint, or local emulator reverse tunnel is
 * reachable. Any HTTP response proves reachability (even a 4xx/5xx); only a
 * request with no HTTP response marks the backend unreachable.
 */
internal class BackendReachabilityTracker {
    private val _state = MutableStateFlow(BackendReachability.UNKNOWN)
    val state: StateFlow<BackendReachability> = _state.asStateFlow()

    fun recordHttpResponse() {
        _state.value = BackendReachability.REACHABLE
    }

    fun recordApiFailure(error: ApiException) {
        _state.value = if (error.status == null) {
            BackendReachability.UNREACHABLE
        } else {
            BackendReachability.REACHABLE
        }
    }

    fun recordTransportFailure() {
        _state.value = BackendReachability.UNREACHABLE
    }

    fun reset() {
        _state.value = BackendReachability.UNKNOWN
    }
}

/** Unknown is optimistic until the first API probe completes. */
internal fun backendIsOnline(
    networkValidated: Boolean,
    backendReachability: BackendReachability,
): Boolean = networkValidated && backendReachability != BackendReachability.UNREACHABLE
