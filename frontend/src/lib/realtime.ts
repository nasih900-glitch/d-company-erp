/**
 * Real-time push over WebSocket — replaces polling-on-a-timer with a
 * server-initiated "this changed" signal, the same way any high-end
 * live-data app works: the screen doesn't ask "did anything change yet?"
 * every N seconds, the server tells it the instant something does.
 *
 * One shared connection for the whole app (not one per screen). Screens
 * subscribe to a resource ("shifts", "tables", "orders", "gaming",
 * "kitchen") and get called back when it changes — they already have a
 * REST fetch for that resource (the one that used to run on a timer), so
 * the callback just re-runs it. No new state-merging logic, no risk of a
 * push payload drifting out of sync with a plain GET.
 *
 * Auth is a first-message handshake, not a query-string token — the token
 * would otherwise sit in plaintext in server access logs.
 */
import { BASE_URL } from './api';

type Listener = () => void;

const listeners = new Map<string, Set<Listener>>();
let socket: WebSocket | null = null;
let reconnectAttempt = 0;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let intentionallyClosed = false;

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
 * Connects using whatever access token is currently in localStorage — read
 * fresh every call (including on every reconnect attempt) rather than
 * captured once, so a token rotated by the normal HTTP refresh flow while
 * the socket was briefly down doesn't leave reconnects retrying with a
 * token that's already stale.
 */
export function connectRealtime(): void {
  const token = localStorage.getItem('access_token');
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
    reconnectAttempt = 0;
    socket?.send(JSON.stringify({ token: localStorage.getItem('access_token') }));
  };

  socket.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data);
      if (msg?.type === 'changed' && typeof msg.resource === 'string') {
        notify(msg.resource);
      }
    } catch {
      // Non-JSON or unrecognized frame — ignore, the connection itself is what matters.
    }
  };

  socket.onclose = () => {
    socket = null;
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
