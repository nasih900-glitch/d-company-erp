/**
 * Real-time push over WebSocket — replaces polling-on-a-timer with a
 * server-initiated "this changed" signal, the same way any high-end
 * live-data app works: the screen doesn't ask "did anything change yet?"
 * every N seconds, the server tells it the instant something does.
 *
 * One shared connection for the whole app (not one per screen). Screens
 * subscribe to a resource ("shifts", "tables", "orders", "gaming",
 * "kitchen", "attendance") and get called back when it changes — they
 * already have a REST fetch for that resource (the one that used to run
 * on a timer), so the callback just re-runs it. No new state-merging
 * logic, no risk of a push payload drifting out of sync with a plain GET.
 *
 * Auth is a first-message handshake, not a query-string token — the token
 * would otherwise sit in plaintext in server access logs.
 */
import { BASE_URL, readAccessToken } from './api';

type Listener = () => void;

const listeners = new Map<string, Set<Listener>>();
let socket: WebSocket | null = null;
let reconnectAttempt = 0;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let intentionallyClosed = false;

/**
 * A TCP connection can die without either end being told — a wifi blip, a
 * laptop sleeping, a NAT or proxy dropping an idle mapping. The browser keeps
 * reporting readyState === OPEN, onclose never fires, and the app goes on
 * believing it is live while receiving nothing. On a POS that shows a table as
 * free while somebody is sitting at it, which is worse than showing an error.
 *
 * The server already pings every 20s (see backend app/api/v1/ws/router.py), so
 * silence longer than a couple of those is proof the link is gone no matter
 * what readyState claims.
 */
let lastMessageAt = 0;
let watchdog: ReturnType<typeof setInterval> | null = null;
const SERVER_PING_INTERVAL_MS = 20_000;
const SILENCE_LIMIT_MS = SERVER_PING_INTERVAL_MS * 2.5;

function wsUrl(): string {
  if (/^https?:\/\//i.test(BASE_URL)) {
    return BASE_URL.replace(/^http/i, 'ws').replace(/\/$/, '') + '/ws';
  }
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const path = BASE_URL.startsWith('/') ? BASE_URL : `/${BASE_URL}`;
  return `${proto}//${window.location.host}${path.replace(/\/$/, '')}/ws`;
}

function notify(resource: string) {
  listeners.get(resource)?.forEach((cb) => {
    try { cb(); } catch { /* one screen's refetch failing shouldn't break others */ }
  });
}

/** Every subscribed screen re-runs its fetch. Used after a gap in the stream. */
function refetchEverything() {
  listeners.forEach((set) => {
    set.forEach((cb) => {
      try { cb(); } catch { /* one screen's refetch failing shouldn't break others */ }
    });
  });
}

/**
 * Tears down a socket the browser still thinks is fine. close() is called for
 * tidiness but is not relied on: a half-open socket may never fire onclose, so
 * the reference is dropped and the reconnect scheduled here rather than from
 * the handler.
 */
function dropAndReconnect() {
  stopWatchdog();
  const dead = socket;
  socket = null;
  try { dead?.close(); } catch { /* already gone */ }
  if (!intentionallyClosed) scheduleReconnect();
}

function checkLiveness() {
  if (intentionallyClosed || !socket) return;
  if (Date.now() - lastMessageAt > SILENCE_LIMIT_MS) dropAndReconnect();
}

function startWatchdog() {
  stopWatchdog();
  watchdog = setInterval(checkLiveness, SERVER_PING_INTERVAL_MS / 2);
}

function stopWatchdog() {
  if (watchdog) { clearInterval(watchdog); watchdog = null; }
}

if (typeof window !== 'undefined') {
  // A sleeping laptop or a backgrounded tab is the classic way to end up with
  // a socket that is dead but still reads as OPEN. Both of these are moments
  // where the answer is knowable immediately, so don't wait for the interval.
  window.addEventListener('online', () => { checkLiveness(); connectRealtime(); });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') { checkLiveness(); connectRealtime(); }
  });
}

function scheduleReconnect() {
  if (intentionallyClosed || reconnectTimer) return;
  // Capped exponential backoff — instant retry on a blip, but a flaky
  // network doesn't turn into a reconnect storm.
  const delayMs = Math.min(30_000, 1000 * 2 ** reconnectAttempt);
  reconnectAttempt += 1;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectRealtime();
  }, delayMs);
}

/**
 * Connects using the current access token — read
 * fresh every call (including on every reconnect attempt) rather than
 * captured once, so a token rotated by the normal HTTP refresh flow while
 * the socket was briefly down doesn't leave reconnects retrying with a
 * token that's already stale.
 */
export function connectRealtime(): void {
  const token = readAccessToken();
  if (!token) return;
  intentionallyClosed = false;
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return;
  }
  try {
    socket = new WebSocket(wsUrl());
  } catch {
    scheduleReconnect();
    return;
  }

  socket.onopen = () => {
    const wasReconnect = reconnectAttempt > 0;
    reconnectAttempt = 0;
    lastMessageAt = Date.now();
    socket?.send(JSON.stringify({ token: readAccessToken() }));
    startWatchdog();
    // Anything that changed while we were disconnected produced a "changed"
    // frame nobody was listening to. Without this every screen keeps showing
    // whatever it had before the drop until the next unrelated change — the
    // connection looks healthy again while the data on screen is stale.
    if (wasReconnect) refetchEverything();
  };

  socket.onmessage = (event) => {
    lastMessageAt = Date.now();
    try {
      const msg = JSON.parse(event.data);
      if (msg?.type === 'changed' && typeof msg.resource === 'string') {
        notify(msg.resource);
      } else if (msg?.type === 'ping') {
        // Replying is not required by the server, but a send that throws is a
        // second, earlier signal that this socket is no longer usable.
        try { socket?.send(JSON.stringify({ type: 'pong' })); } catch { dropAndReconnect(); }
      }
    } catch {
      // Non-JSON or unrecognized frame — ignore, the connection itself is what matters.
    }
  };

  socket.onclose = () => {
    socket = null;
    stopWatchdog();
    if (!intentionallyClosed) scheduleReconnect();
  };

  socket.onerror = () => {
    // onclose fires right after; reconnect is handled there.
  };
}

export function disconnectRealtime(): void {
  intentionallyClosed = true;
  reconnectAttempt = 0;
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  stopWatchdog();
  socket?.close();
  socket = null;
}

/** Subscribe a screen's refetch callback to a resource. Returns an unsubscribe function. */
export function subscribeRealtime(resource: string, callback: Listener): () => void {
  let set = listeners.get(resource);
  if (!set) { set = new Set(); listeners.set(resource, set); }
  set.add(callback);
  return () => { set!.delete(callback); };
}
