package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.auth.TokenStore
import cloud.dcompany.erp.core.net.ClientIdentityInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

@Serializable
private data class AuthFrame(val token: String)

@Serializable
private data class IncomingFrame(val type: String, val resource: String? = null)

@Serializable
private data class PongFrame(val type: String = "pong")

/**
 * Live push over WebSocket — a 1:1 port of frontend/src/lib/realtime.ts onto
 * OkHttp, against the same endpoint (see backend app/api/v1/ws/router.py):
 * connect unauthenticated, send `{"token": ...}` as the first text frame,
 * the server pings every 20s, silence past 2.5x that means the link is
 * dead no matter what the socket object claims. Same capped-exponential
 * reconnect, same "refetch everything" after a reconnect-following-a-gap.
 *
 * One shared `changes` flow instead of the JS version's per-resource
 * listener map — Kotlin Flow already solves "many subscribers, one
 * source" for free. Nothing subscribes to this directly except the single
 * collector in DCompanyApp, which maps a `Changed(resource)` event to
 * `SyncEngine.requestSync()` + `SyncEngine.refresh(resource)` — a screen
 * never touches this class, the same way a screen never called the JS
 * version's `subscribeRealtime()` itself; it just reads Room, and Room
 * re-emits once that refresh pull lands.
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

    private val _changes = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
    val changes: SharedFlow<RealtimeEvent> = _changes.asSharedFlow()

    private var socket: WebSocket? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var intentionallyClosed = false
    @Volatile private var lastMessageAtMillis = 0L

    private val serverPingIntervalMs = 20_000L
    private val silenceLimitMs = (serverPingIntervalMs * 2.5).toLong()

    /** No-op if a connection is already open or in flight. */
    fun connect() {
        if (tokens.accessToken() == null) return
        intentionallyClosed = false
        if (socket != null) return
        reconnectJob?.cancel()
        reconnectJob = null
        socket = client.newWebSocket(Request.Builder().url(wsUrl()).build(), Listener())
    }

    fun disconnect() {
        intentionallyClosed = true
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        stopWatchdog()
        socket?.close(1000, "client disconnect")
        socket = null
    }

    private fun wsUrl(): String {
        val ws = BuildConfig.API_BASE_URL
            .replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws")
            .trimEnd('/')
        return "$ws/ws"
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val wasReconnect = reconnectAttempt > 0
            reconnectAttempt = 0
            lastMessageAtMillis = System.currentTimeMillis()
            // Read fresh rather than whatever connect() saw — a token
            // rotated by the normal HTTP refresh flow while this socket was
            // reconnecting must never leave it retrying with a stale one.
            val token = tokens.accessToken() ?: return
            webSocket.send(json.encodeToString(AuthFrame.serializer(), AuthFrame(token)))
            startWatchdog()
            // Anything that changed while disconnected produced a "changed"
            // frame nobody was listening to — without this, every screen
            // keeps showing stale data until the next unrelated change.
            if (wasReconnect) _changes.tryEmit(RealtimeEvent.ReconnectedAfterGap)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastMessageAtMillis = System.currentTimeMillis()
            val msg = runCatching { json.decodeFromString(IncomingFrame.serializer(), text) }
                .getOrNull() ?: return
            when (msg.type) {
                "changed" -> msg.resource?.let { _changes.tryEmit(RealtimeEvent.Changed(it)) }
                "ping" -> {
                    // Replying isn't required by the server, but a send that
                    // throws is a second, earlier signal this socket is dead.
                    val sent = runCatching {
                        webSocket.send(json.encodeToString(PongFrame.serializer(), PongFrame()))
                    }
                    if (sent.isFailure) dropAndReconnect()
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            stopWatchdog()
            if (!intentionallyClosed) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            stopWatchdog()
            if (!intentionallyClosed) scheduleReconnect()
        }
    }

    /**
     * Tears down a socket the OS still thinks is fine. close() is called for
     * tidiness but not relied on — a half-open socket may never fire
     * onClosed — so the reference is dropped and the reconnect scheduled
     * here rather than waiting for a callback that might not come.
     */
    private fun dropAndReconnect() {
        stopWatchdog()
        val dead = socket
        socket = null
        runCatching { dead?.close(1000, "stale") }
        if (!intentionallyClosed) scheduleReconnect()
    }

    private fun checkLiveness() {
        if (intentionallyClosed || socket == null) return
        if (System.currentTimeMillis() - lastMessageAtMillis > silenceLimitMs) dropAndReconnect()
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(serverPingIntervalMs / 2)
                checkLiveness()
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun scheduleReconnect() {
        if (intentionallyClosed || reconnectJob != null) return
        // Capped exponential backoff — instant retry on a blip, but a flaky
        // network doesn't turn into a reconnect storm.
        val delayMs = min(30_000L, (1000.0 * 2.0.pow(reconnectAttempt)).toLong())
        reconnectAttempt += 1
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJob = null
            connect()
        }
    }
}
