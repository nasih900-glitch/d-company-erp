package cloud.dcompany.erp.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val CONNECTIVITY_LOG_TAG = "DCompanyConnectivity"
internal const val CONNECTIVITY_RECOVERY_STABILITY_MILLIS = 1_500L

/** One atomic status for operational decisions and user-visible app chrome. */
internal enum class ConnectivityPhase {
    NO_NETWORK,
    VERIFYING,
    SERVER_UNREACHABLE,
    RECOVERING,
    ONLINE,
}

internal data class ConnectivityPresentation(
    val phase: ConnectivityPhase,
) {
    val networkValidated: Boolean
        get() = phase != ConnectivityPhase.NO_NETWORK

    val backendReachability: BackendReachability
        get() = when (phase) {
            ConnectivityPhase.ONLINE -> BackendReachability.REACHABLE
            ConnectivityPhase.SERVER_UNREACHABLE -> BackendReachability.UNREACHABLE
            ConnectivityPhase.NO_NETWORK,
            ConnectivityPhase.VERIFYING,
            ConnectivityPhase.RECOVERING -> BackendReachability.UNKNOWN
        }

    val online: Boolean
        get() = phase == ConnectivityPhase.ONLINE
}

internal data class ConnectivityMachineState(
    val phase: ConnectivityPhase = ConnectivityPhase.NO_NETWORK,
    val generation: Long = 0L,
    val probeInFlight: Boolean = false,
    val retryAttempt: Int = 0,
    val requiresRecoveryStability: Boolean = false,
    val notifyValidatedReconnectWhenOnline: Boolean = false,
) {
    val presentation: ConnectivityPresentation
        get() = ConnectivityPresentation(phase)
}

internal sealed interface ConnectivityEvent {
    data class NetworkChanged(
        val validated: Boolean,
        val notifyReconnect: Boolean,
    ) : ConnectivityEvent

    data object BackendTransportFailure : ConnectivityEvent
    data object BackendHttpResponse : ConnectivityEvent
    data class ProbeCompleted(val generation: Long, val successful: Boolean) : ConnectivityEvent
    data class RecoverySettled(val generation: Long) : ConnectivityEvent
    data class RetryProbe(val generation: Long) : ConnectivityEvent
}

internal sealed interface ConnectivityEffect {
    data class StartProbe(val generation: Long) : ConnectivityEffect
    data class SettleRecovery(val generation: Long, val delayMillis: Long) : ConnectivityEffect
    data class RetryLater(val generation: Long, val delayMillis: Long) : ConnectivityEffect
    data object NotifyValidatedReconnect : ConnectivityEffect
    data object NotifyBackOnline : ConnectivityEffect
}

internal data class ConnectivityTransition(
    val state: ConnectivityMachineState,
    val effects: List<ConnectivityEffect> = emptyList(),
)

/**
 * Pure reducer. Network callbacks, ordinary HTTP hints, probes and timers all
 * pass through this one serialized owner before they can change app state.
 */
internal class ConnectivityStateMachine(
    initialState: ConnectivityMachineState = ConnectivityMachineState(),
) {
    var state: ConnectivityMachineState = initialState
        private set

    fun reduce(event: ConnectivityEvent): ConnectivityTransition {
        val transition = when (event) {
            is ConnectivityEvent.NetworkChanged -> networkChanged(event)
            ConnectivityEvent.BackendTransportFailure -> backendFailed()
            ConnectivityEvent.BackendHttpResponse -> backendResponded()
            is ConnectivityEvent.ProbeCompleted -> probeCompleted(event)
            is ConnectivityEvent.RecoverySettled -> recoverySettled(event)
            is ConnectivityEvent.RetryProbe -> retryProbe(event)
        }
        state = transition.state
        return transition
    }

    private fun networkChanged(event: ConnectivityEvent.NetworkChanged): ConnectivityTransition {
        val wasValidated = state.phase != ConnectivityPhase.NO_NETWORK
        if (wasValidated == event.validated) return ConnectivityTransition(state)

        val generation = state.generation + 1L
        return if (!event.validated) {
            ConnectivityTransition(
                state.copy(
                    phase = ConnectivityPhase.NO_NETWORK,
                    generation = generation,
                    probeInFlight = false,
                    retryAttempt = 0,
                    requiresRecoveryStability = false,
                    notifyValidatedReconnectWhenOnline = false,
                ),
            )
        } else {
            ConnectivityTransition(
                state.copy(
                    phase = ConnectivityPhase.VERIFYING,
                    generation = generation,
                    probeInFlight = true,
                    retryAttempt = 0,
                    requiresRecoveryStability = event.notifyReconnect,
                    notifyValidatedReconnectWhenOnline = event.notifyReconnect,
                ),
                listOf(ConnectivityEffect.StartProbe(generation)),
            )
        }
    }

    private fun backendFailed(): ConnectivityTransition {
        if (state.phase == ConnectivityPhase.NO_NETWORK) return ConnectivityTransition(state)

        // SERVER_UNREACHABLE is only entered after a failed /readyz proof. Once
        // that outage is authoritative, ordinary request failures must not
        // restart or multiply the coordinator's bounded retry loop.
        if (state.phase == ConnectivityPhase.SERVER_UNREACHABLE) {
            return ConnectivityTransition(state)
        }

        if (state.probeInFlight) {
            return ConnectivityTransition(
                state.copy(
                    // Do not replace a previously proven ONLINE presentation
                    // while its one authoritative readiness check is running.
                    // A second failed call is not a second proof of a global
                    // outage, and exposing VERIFYING/RECOVERING here made the
                    // tablet header visibly pulse on otherwise healthy links.
                    phase = if (state.phase == ConnectivityPhase.ONLINE) {
                        ConnectivityPhase.ONLINE
                    } else {
                        ConnectivityPhase.VERIFYING
                    },
                    requiresRecoveryStability = state.phase != ConnectivityPhase.ONLINE,
                ),
            )
        }

        val generation = state.generation + 1L
        val wasConfirmedOnline = state.phase == ConnectivityPhase.ONLINE
        return ConnectivityTransition(
            state.copy(
                // The failed request itself retains its own ambiguity/outbox
                // handling. Keep the last confirmed global presentation until
                // /readyz decides whether this is a real ERP outage.
                phase = if (wasConfirmedOnline) {
                    ConnectivityPhase.ONLINE
                } else {
                    ConnectivityPhase.VERIFYING
                },
                generation = generation,
                probeInFlight = true,
                requiresRecoveryStability = !wasConfirmedOnline,
            ),
            listOf(ConnectivityEffect.StartProbe(generation)),
        )
    }

    private fun backendResponded(): ConnectivityTransition {
        if (
            state.phase == ConnectivityPhase.NO_NETWORK ||
            state.phase == ConnectivityPhase.ONLINE ||
            state.phase == ConnectivityPhase.RECOVERING ||
            state.probeInFlight
        ) return ConnectivityTransition(state)

        // An ordinary response is a useful hint, but only /readyz may recover
        // the whole ERP. Start one current-generation proof immediately.
        val generation = state.generation + 1L
        return ConnectivityTransition(
            state.copy(
                phase = ConnectivityPhase.VERIFYING,
                generation = generation,
                probeInFlight = true,
            ),
            listOf(ConnectivityEffect.StartProbe(generation)),
        )
    }

    private fun probeCompleted(event: ConnectivityEvent.ProbeCompleted): ConnectivityTransition {
        if (event.generation != state.generation || !state.probeInFlight) {
            return ConnectivityTransition(state)
        }
        return if (event.successful && !state.requiresRecoveryStability) {
            ConnectivityTransition(
                state.copy(
                    phase = ConnectivityPhase.ONLINE,
                    probeInFlight = false,
                    retryAttempt = 0,
                    requiresRecoveryStability = false,
                    notifyValidatedReconnectWhenOnline = false,
                ),
                onlineEffects(state),
            )
        } else if (event.successful) {
            ConnectivityTransition(
                state.copy(
                    phase = ConnectivityPhase.RECOVERING,
                    probeInFlight = false,
                ),
                listOf(
                    ConnectivityEffect.SettleRecovery(
                        generation = state.generation,
                        delayMillis = CONNECTIVITY_RECOVERY_STABILITY_MILLIS,
                    ),
                ),
            )
        } else {
            val nextAttempt = state.retryAttempt + 1
            ConnectivityTransition(
                state.copy(
                    phase = ConnectivityPhase.SERVER_UNREACHABLE,
                    probeInFlight = false,
                    retryAttempt = nextAttempt,
                ),
                listOf(
                    ConnectivityEffect.RetryLater(
                        generation = state.generation,
                        delayMillis = connectivityRetryDelayMillis(nextAttempt),
                    ),
                ),
            )
        }
    }

    private fun recoverySettled(event: ConnectivityEvent.RecoverySettled): ConnectivityTransition {
        if (event.generation != state.generation || state.phase != ConnectivityPhase.RECOVERING) {
            return ConnectivityTransition(state)
        }
        return ConnectivityTransition(
            state.copy(
                phase = ConnectivityPhase.ONLINE,
                retryAttempt = 0,
                requiresRecoveryStability = false,
                notifyValidatedReconnectWhenOnline = false,
            ),
            onlineEffects(state),
        )
    }

    private fun retryProbe(event: ConnectivityEvent.RetryProbe): ConnectivityTransition {
        if (
            event.generation != state.generation ||
            state.phase != ConnectivityPhase.SERVER_UNREACHABLE ||
            state.probeInFlight
        ) return ConnectivityTransition(state)

        return ConnectivityTransition(
            state.copy(probeInFlight = true),
            listOf(ConnectivityEffect.StartProbe(state.generation)),
        )
    }

    private fun onlineEffects(previous: ConnectivityMachineState): List<ConnectivityEffect> {
        // A transient request failure can ask /readyz for proof while the last
        // authoritative presentation remains ONLINE. Successful proof in that
        // case is not a reconnect: firing onBackOnline would unnecessarily
        // drain every outbox, reconnect the websocket and send a heartbeat,
        // which can churn otherwise stable operational screens.
        if (previous.phase == ConnectivityPhase.ONLINE) return emptyList()

        return buildList {
            if (previous.notifyValidatedReconnectWhenOnline) {
                add(ConnectivityEffect.NotifyValidatedReconnect)
            }
            add(ConnectivityEffect.NotifyBackOnline)
        }
    }
}

internal fun connectivityRetryDelayMillis(attempt: Int): Long = when (attempt.coerceAtLeast(1)) {
    1 -> 2_000L
    2 -> 5_000L
    3 -> 10_000L
    else -> 30_000L
}

/** Android/network adapter around the pure serialized reducer above. */
internal class ConnectivityObserver(
    context: Context,
    private val scope: CoroutineScope,
    backendEvents: Flow<BackendReachabilityEvent>,
    private val readinessProbe: suspend () -> Boolean,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val events = Channel<ConnectivityEvent>(Channel.UNLIMITED)
    private val machine = ConnectivityStateMachine()
    private val started = AtomicBoolean(false)

    private val _presentation = MutableStateFlow(machine.state.presentation)
    val presentation: StateFlow<ConnectivityPresentation> = _presentation.asStateFlow()
    private val _networkValidated = MutableStateFlow(false)
    val networkValidated: StateFlow<Boolean> = _networkValidated.asStateFlow()
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var onBackOnline: (() -> Unit)? = null
    private var onValidatedReconnect: (() -> Unit)? = null
    private var probeJob: Job? = null
    private var timerJob: Job? = null

    init {
        scope.launch {
            backendEvents.collect { event ->
                events.send(
                    when (event) {
                        BackendReachabilityEvent.HttpResponse -> ConnectivityEvent.BackendHttpResponse
                        BackendReachabilityEvent.TransportFailure -> ConnectivityEvent.BackendTransportFailure
                    },
                )
            }
        }
        scope.launch {
            for (event in events) process(event)
        }
    }

    fun start(
        onValidatedReconnect: () -> Unit = {},
        onBackOnline: () -> Unit,
    ) {
        if (!started.compareAndSet(false, true)) return
        this.onValidatedReconnect = onValidatedReconnect
        this.onBackOnline = onBackOnline

        offerNetworkState(currentlyValidated(), notifyReconnect = false)
        manager?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                // A default-network handover can report the old Wi-Fi as lost
                // after cellular (or another Wi-Fi) is already active. Treating
                // that callback as proof of "no network" produced a false
                // offline/online flash even though the tablet never lost
                // validated internet. Re-read Android's current default for
                // every callback instead of publishing the departed network's
                // state as the whole-device state.
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) = refresh()
            },
        )
    }

    private suspend fun process(event: ConnectivityEvent) {
        val previous = machine.state
        val transition = machine.reduce(event)
        val current = transition.state
        if (current.generation != previous.generation) {
            probeJob?.cancel()
            timerJob?.cancel()
        }
        if (current != previous) publish(current)
        transition.effects.forEach(::runEffect)
    }

    private fun publish(state: ConnectivityMachineState) {
        val snapshot = state.presentation
        _presentation.value = snapshot
        _networkValidated.value = snapshot.networkValidated
        _online.value = snapshot.online
        Log.i(
            CONNECTIVITY_LOG_TAG,
            "availability=${snapshot.phase}, generation=${state.generation}, " +
                "probeInFlight=${state.probeInFlight}, retryAttempt=${state.retryAttempt}",
        )
    }

    private fun runEffect(effect: ConnectivityEffect) {
        when (effect) {
            is ConnectivityEffect.StartProbe -> startProbe(effect.generation)
            is ConnectivityEffect.SettleRecovery -> schedule(
                delayMillis = effect.delayMillis,
                event = ConnectivityEvent.RecoverySettled(effect.generation),
            )
            is ConnectivityEffect.RetryLater -> schedule(
                delayMillis = effect.delayMillis,
                event = ConnectivityEvent.RetryProbe(effect.generation),
            )
            ConnectivityEffect.NotifyValidatedReconnect -> onValidatedReconnect?.invoke()
            ConnectivityEffect.NotifyBackOnline -> onBackOnline?.invoke()
        }
    }

    private fun startProbe(generation: Long) {
        probeJob?.cancel()
        probeJob = scope.launch {
            val successful = try {
                readinessProbe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(CONNECTIVITY_LOG_TAG, "Readiness probe failed", failure)
                false
            }
            events.send(ConnectivityEvent.ProbeCompleted(generation, successful))
        }
    }

    private fun schedule(delayMillis: Long, event: ConnectivityEvent) {
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(delayMillis)
            events.send(event)
        }
    }

    private fun refresh() = offerNetworkState(currentlyValidated())

    private fun offerNetworkState(validated: Boolean, notifyReconnect: Boolean = true) {
        val result = events.trySend(ConnectivityEvent.NetworkChanged(validated, notifyReconnect))
        if (result.isFailure) {
            Log.e(CONNECTIVITY_LOG_TAG, "Could not enqueue Android connectivity state")
        }
    }

    private fun currentlyValidated(): Boolean {
        val active = manager?.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.isValidatedInternet()
    }
}

private fun NetworkCapabilities.isValidatedInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
