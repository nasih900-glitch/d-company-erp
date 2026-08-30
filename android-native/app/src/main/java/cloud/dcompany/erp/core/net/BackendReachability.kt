package cloud.dcompany.erp.core.net

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether this app has recently received an HTTP response from the ERP API. */
internal enum class BackendReachability {
    UNKNOWN,
    REACHABLE,
    UNREACHABLE,
}

/** Ordered hints from normal API traffic. Only a readiness probe may recover globally. */
internal sealed interface BackendReachabilityEvent {
    data object HttpResponse : BackendReachabilityEvent
    data object TransportFailure : BackendReachabilityEvent
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
    private val _events = MutableSharedFlow<BackendReachabilityEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BackendReachabilityEvent> = _events.asSharedFlow()

    @Synchronized
    fun recordHttpResponse() {
        val changed = _state.value != BackendReachability.REACHABLE
        _state.value = BackendReachability.REACHABLE
        if (changed) _events.tryEmit(BackendReachabilityEvent.HttpResponse)
    }

    @Synchronized
    fun recordApiFailure(error: ApiException) {
        if (error.status == null) {
            val changed = _state.value != BackendReachability.UNREACHABLE
            _state.value = BackendReachability.UNREACHABLE
            if (changed) _events.tryEmit(BackendReachabilityEvent.TransportFailure)
        } else {
            val changed = _state.value != BackendReachability.REACHABLE
            _state.value = BackendReachability.REACHABLE
            if (changed) _events.tryEmit(BackendReachabilityEvent.HttpResponse)
        }
    }

    @Synchronized
    fun recordTransportFailure() {
        val changed = _state.value != BackendReachability.UNREACHABLE
        _state.value = BackendReachability.UNREACHABLE
        if (changed) _events.tryEmit(BackendReachabilityEvent.TransportFailure)
    }

    /**
     * The isolated readiness probe is consumed directly by the connectivity
     * coordinator. Keep this legacy snapshot aligned without feeding a second
     * recovery event back into that coordinator.
     */
    @Synchronized
    fun recordReadinessProof() {
        _state.value = BackendReachability.REACHABLE
    }

    @Synchronized
    fun reset() {
        _state.value = BackendReachability.UNKNOWN
    }
}

/** Unknown is optimistic until the first API probe completes. */
internal fun backendIsOnline(
    networkValidated: Boolean,
    backendReachability: BackendReachability,
): Boolean = networkValidated && backendReachability != BackendReachability.UNREACHABLE

/**
 * Coroutine timeouts cancel their underlying OkHttp call. That is a caller
 * decision, not evidence that DNS, TLS, the network, or the ERP server failed.
 */
internal fun shouldPublishTransportFailure(callCancelled: Boolean): Boolean = !callCancelled

/**
 * A real HTTP response still proves server reachability even if its caller was
 * cancelled immediately afterwards. A response-less [ApiException] does not:
 * nested interceptors can surface one while the original OkHttp call is being
 * cancelled, and that caller-owned cancellation must not become a global
 * server-outage signal.
 */
internal fun shouldPublishApiReachability(
    error: ApiException,
    callCancelled: Boolean,
): Boolean = error.status != null || shouldPublishTransportFailure(callCancelled)
