package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.auth.TokenStore
import cloud.dcompany.erp.core.net.ClientIdentityInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

sealed interface RealtimeEvent {
    data class Changed(val resource: String) : RealtimeEvent
    data object ReconnectedAfterGap : RealtimeEvent
}

/**
 * Coalesces invalidations while the process-wide consumer is busy without
 * dropping a resource. A reconnect supersedes only changes queued before the
 * gap marker; changes that arrive on the new socket remain queued after it.
 */
internal class RealtimeEventAccumulator {
    private val resources = linkedSetOf<String>()
    private var reconnectPending = false

    @Synchronized
    fun add(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.Changed -> resources += event.resource
            RealtimeEvent.ReconnectedAfterGap -> {
                resources.clear()
                reconnectPending = true
            }
        }
    }

    @Synchronized
    fun hasPending(): Boolean = reconnectPending || resources.isNotEmpty()

    @Synchronized
    fun takeNext(): RealtimeEvent? {
        if (reconnectPending) {
            reconnectPending = false
            return RealtimeEvent.ReconnectedAfterGap
        }
        val resource = resources.firstOrNull() ?: return null
        resources.remove(resource)
        return RealtimeEvent.Changed(resource)
    }
}

/**
 * OkHttp invokes websocket callbacks on its own threads, where suspending is
 * impossible. Keep callback publication non-blocking, then drain through one
 * coroutine into a rendezvous SharedFlow. The pending set is bounded in
 * practice by the server's finite resource vocabulary and repeated frames for
 * the same resource collapse to one trailing invalidation.
 *
 * Waiting for a subscriber before removing an event also closes the cold-start
 * race between constructing [RealtimeClient] and starting DCompanyApp's
 * process-wide collector.
 */
internal class ReliableRealtimeEventBus(scope: CoroutineScope) {
    private val pending = RealtimeEventAccumulator()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val _events = MutableSharedFlow<RealtimeEvent>()
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            for (ignored in wakeups) {
                drainPending()
            }
        }
    }

    fun publish(event: RealtimeEvent) {
        pending.add(event)
        // CONFLATED can always accept or merge a wake-up while open. Pending
        // data lives in the accumulator, never in this signal-only channel.
        wakeups.trySend(Unit)
    }

    private suspend fun drainPending() {
        while (pending.hasPending()) {
            _events.subscriptionCount.first { count -> count > 0 }
            val event = pending.takeNext() ?: continue
            _events.emit(event)
        }
    }
}

@Serializable
private data class AuthFrame(val token: String)

@Serializable
private data class IncomingFrame(val type: String, val resource: String? = null)

@Serializable
private data class PongFrame(val type: String = "pong")

internal data class RealtimeSocketIdentity<T : Any>(
    val generation: Long,
    val socket: T,
)

/**
 * Serialises socket replacement with listener callbacks. A generation alone
 * distinguishes reconnects, while referential identity also rejects a
 * callback from an unexpected socket within the same generation.
 *
 * Callback side effects run while the same monitor used by [open] is held.
 * This is important: merely checking an old socket and then cancelling the
 * watchdog outside the lock would leave a check-then-act window in which a
 * new socket could be installed and have its watchdog cancelled.
 */
internal class RealtimeSocketLifecycle<T : Any> {
    private var generation = 0L
    private var current: RealtimeSocketIdentity<T>? = null

    @Synchronized
    fun open(create: (generation: Long) -> T): RealtimeSocketIdentity<T>? {
        return openIf(canOpen = { true }, create = create)
    }

    @Synchronized
    fun openIf(
        canOpen: () -> Boolean,
        create: (generation: Long) -> T,
    ): RealtimeSocketIdentity<T>? {
        if (current != null || !canOpen()) return null
        val identity = RealtimeSocketIdentity(
            generation = ++generation,
            socket = create(generation),
        )
        current = identity
        return identity
    }

    @Synchronized
    fun ifCurrent(
        generation: Long,
        socket: T,
        action: () -> Unit,
    ): Boolean {
        if (!matches(generation, socket)) return false
        action()
        return true
    }

    @Synchronized
    fun clearIfCurrent(
        generation: Long,
        socket: T,
        afterClear: () -> Unit,
    ): Boolean {
        if (!matches(generation, socket)) return false
        current = null
        afterClear()
        return true
    }

    @Synchronized
    fun invalidate(afterClear: (T?) -> Unit): T? {
        // Invalidate before close(): OkHttp is allowed to invoke onClosed as
        // soon as close starts, and that callback must already be stale.
        generation += 1
        val socket = current?.socket
        current = null
        afterClear(socket)
        return socket
    }

    @Synchronized
    fun inspect(action: (RealtimeSocketIdentity<T>?) -> Unit) {
        action(current)
    }

    @Synchronized
    fun isCurrent(identity: RealtimeSocketIdentity<T>): Boolean =
        matches(identity.generation, identity.socket)

    private fun matches(generation: Long, socket: T): Boolean {
        val current = current ?: return false
        return current.generation == generation && current.socket === socket
    }
}

/**
 * Live push over WebSocket — a 1:1 port of frontend/src/lib/realtime.ts onto
 * OkHttp, against the same endpoint (see backend app/api/v1/ws/router.py):
 * connect unauthenticated, send `{"token": ...}` as the first text frame,
 * the server pings every 20s, silence past 2.5x that means the link is
 * dead no matter what the socket object claims. Same capped-exponential
 * reconnect, same "refetch everything" after a reconnect-following-a-gap.
 *
 * One shared `changes` flow replaces the JS version's per-resource listener
 * map. DCompanyApp owns the process-wide cache invalidation collector, while
 * session authority observes the same stream for role changes. Feature
 * screens never fetch from this class; they read Room, and Room re-emits once
 * the authoritative refresh pull lands.
 */
class RealtimeClient(
    private val tokens: TokenStore,
    private val scope: CoroutineScope,
) {
    private val client = OkHttpClient.Builder()
        // No read timeout — a WebSocket is meant to sit open indefinitely.
        // Liveness is judged by the silence watchdog below, never a socket
        // timeout closing it out from under us.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor(ClientIdentityInterceptor())
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val eventBus = ReliableRealtimeEventBus(scope)
    val changes: SharedFlow<RealtimeEvent> = eventBus.events

    private val socketLifecycle = RealtimeSocketLifecycle<WebSocket>()
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var reconnectTicket = 0L
    private var watchdogJob: Job? = null
    private var intentionallyClosed = false
    private var lastMessageAtMillis = 0L

    private val serverPingIntervalMs = 20_000L
    private val silenceLimitMs = (serverPingIntervalMs * 2.5).toLong()

    /** No-op if a connection is already open or in flight. */
    fun connect() {
        connect(expectedReconnectTicket = null)
    }

    private fun connect(expectedReconnectTicket: Long?) {
        if (tokens.accessToken() == null) return
        socketLifecycle.openIf(
            canOpen = {
                expectedReconnectTicket == null ||
                    (!intentionallyClosed && reconnectTicket == expectedReconnectTicket)
            },
            create = { generation ->
                if (expectedReconnectTicket == null) intentionallyClosed = false
                cancelReconnectLocked()
                client.newWebSocket(
                    Request.Builder().url(wsUrl()).build(),
                    Listener(generation),
                )
            },
        )
    }

    fun disconnect() {
        val closing = socketLifecycle.invalidate { _ ->
            intentionallyClosed = true
            reconnectAttempt = 0
            cancelReconnectLocked()
            stopWatchdogLocked()
        }
        runCatching { closing?.close(1000, "client disconnect") }
    }

    private fun wsUrl(): String {
        val ws = BuildConfig.API_BASE_URL
            .replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws")
            .trimEnd('/')
        return "$ws/ws"
    }

    private inner class Listener(
        private val generation: Long,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socketLifecycle.ifCurrent(generation, webSocket) {
                val wasReconnect = reconnectAttempt > 0
                reconnectAttempt = 0
                lastMessageAtMillis = System.currentTimeMillis()
                // Read fresh rather than whatever connect() saw — a token
                // rotated by the normal HTTP refresh flow while this socket
                // was reconnecting must never leave it retrying with a stale
                // one.
                val token = tokens.accessToken() ?: return@ifCurrent
                val authenticated = runCatching {
                    webSocket.send(json.encodeToString(AuthFrame.serializer(), AuthFrame(token)))
                }.getOrDefault(false)
                if (!authenticated) {
                    scope.launch { dropAndReconnect(generation, webSocket) }
                    return@ifCurrent
                }
                startWatchdogLocked(generation, webSocket)
                // Anything that changed while disconnected produced a
                // "changed" frame nobody was listening to — without this,
                // every screen keeps showing stale data until the next
                // unrelated change.
                if (wasReconnect) eventBus.publish(RealtimeEvent.ReconnectedAfterGap)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!socketLifecycle.ifCurrent(generation, webSocket) {
                    lastMessageAtMillis = System.currentTimeMillis()
                }
            ) return

            val msg = runCatching { json.decodeFromString(IncomingFrame.serializer(), text) }
                .getOrNull() ?: return

            var sendFailed = false
            socketLifecycle.ifCurrent(generation, webSocket) {
                when (msg.type) {
                    "changed" -> msg.resource
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { eventBus.publish(RealtimeEvent.Changed(it)) }
                    "ping" -> {
                        // Replying isn't required by the server, but a send
                        // that throws is a second, earlier signal this socket
                        // is dead.
                        sendFailed = !runCatching {
                            webSocket.send(
                                json.encodeToString(PongFrame.serializer(), PongFrame()),
                            )
                        }.getOrDefault(false)
                    }
                }
            }
            if (sendFailed) dropAndReconnect(generation, webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleTerminalCallback(generation, webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleTerminalCallback(generation, webSocket)
        }
    }

    private fun handleTerminalCallback(generation: Long, webSocket: WebSocket) {
        var shouldReconnect = false
        val cleared = socketLifecycle.clearIfCurrent(generation, webSocket) {
            stopWatchdogLocked()
            shouldReconnect = !intentionallyClosed
        }
        if (cleared && shouldReconnect) scheduleReconnect()
    }

    /**
     * Tears down a socket the OS still thinks is fine. close() is called for
     * tidiness but not relied on — a half-open socket may never fire
     * onClosed — so the reference is dropped and the reconnect scheduled
     * here rather than waiting for a callback that might not come.
     */
    private fun dropAndReconnect(generation: Long, webSocket: WebSocket) {
        var shouldReconnect = false
        val cleared = socketLifecycle.clearIfCurrent(generation, webSocket) {
            stopWatchdogLocked()
            shouldReconnect = !intentionallyClosed
        }
        if (!cleared) return

        runCatching { webSocket.close(1000, "stale") }
        if (shouldReconnect) scheduleReconnect()
    }

    private fun checkLiveness(generation: Long, webSocket: WebSocket) {
        var stale = false
        socketLifecycle.ifCurrent(generation, webSocket) {
            stale = !intentionallyClosed &&
                System.currentTimeMillis() - lastMessageAtMillis > silenceLimitMs
        }
        if (stale) dropAndReconnect(generation, webSocket)
    }

    /** Caller holds [socketLifecycle]'s monitor through ifCurrent/open. */
    private fun startWatchdogLocked(generation: Long, webSocket: WebSocket) {
        stopWatchdogLocked()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(serverPingIntervalMs / 2)
                checkLiveness(generation, webSocket)
            }
        }
    }

    /** Caller holds [socketLifecycle]'s monitor. */
    private fun stopWatchdogLocked() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun scheduleReconnect() {
        socketLifecycle.inspect { current ->
            if (intentionallyClosed || current != null || reconnectJob != null) return@inspect
            // Capped exponential backoff — instant retry on a blip, but a
            // flaky network doesn't turn into a reconnect storm.
            val delayMs = min(30_000L, (1000.0 * 2.0.pow(reconnectAttempt)).toLong())
            reconnectAttempt += 1
            val ticket = ++reconnectTicket
            reconnectJob = scope.launch {
                delay(delayMs)
                var shouldConnect = false
                socketLifecycle.inspect { latest ->
                    if (ticket == reconnectTicket) {
                        reconnectJob = null
                        shouldConnect = !intentionallyClosed && latest == null
                    }
                }
                if (shouldConnect) connect(expectedReconnectTicket = ticket)
            }
        }
    }

    /** Caller holds [socketLifecycle]'s monitor through open/invalidate. */
    private fun cancelReconnectLocked() {
        reconnectTicket += 1
        reconnectJob?.cancel()
        reconnectJob = null
    }
}
