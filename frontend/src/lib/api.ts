import axios, { AxiosError, type AxiosRequestConfig } from 'axios';
import { readStoredTerminalId } from './operational-context';

/**
 * Base URL resolution order (most specific wins):
 *   1. VITE_API_URL at build time          — set by Tauri/Capacitor/CI builds
 *   2. window.__ERP_API_URL__ at runtime   — set by mobile shells before page load
 *   3. /api/v1                             — dev proxy (Vite) and same-origin web
 */
const RUNTIME_URL =
  (typeof window !== 'undefined' &&
    (window as unknown as { __ERP_API_URL__?: string }).__ERP_API_URL__) ||
  undefined;

function normalizedApiUrl(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed || undefined;
}

const BASE_URL =
  normalizedApiUrl(import.meta.env.VITE_API_URL) ??
  normalizedApiUrl(RUNTIME_URL) ??
  '/api/v1';

const API_TIMEOUT_MS = 20_000;

export const api = axios.create({
  baseURL: BASE_URL,
  timeout: API_TIMEOUT_MS,
  headers: { 'Content-Type': 'application/json' },
  // FastAPI's `list[str] | None = Query(alias=...)` expects repeated plain
  // keys ("status=a&status=b"). Axios's default array serializer emits
  // bracketed keys ("status[]=a") instead, which FastAPI silently ignores —
  // this previously made status-filtered queries fall back to their
  // unfiltered default instead of erroring, which is exactly the kind of
  // silent-wrong-data bug that's hard to notice.
  paramsSerializer: { indexes: null },
});

export { BASE_URL };

// ---------------------------------------------------------------- request side
// Inject access token + tenant headers.
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  const url = String(config.url || '');
  // These two routes validate identity/terminal state. Sending an unvalidated
  // cached terminal here would prevent the app from repairing stale storage.
  const validatesContext = url.includes('/auth/') || url.includes('/settings/terminals');
  const terminalId = validatesContext ? null : readStoredTerminalId();
  if (terminalId) config.headers['X-Terminal-Id'] = terminalId;
  const pricingToken = localStorage.getItem('pricing_token');
  const pricingExpiresAt = Number(localStorage.getItem('pricing_token_expires_at') || '0');
  if (pricingToken && pricingExpiresAt > Date.now()) {
    config.headers['X-Pricing-Token'] = pricingToken;
  } else {
    localStorage.removeItem('pricing_token');
    localStorage.removeItem('pricing_token_expires_at');
  }
  return config;
});

// ---------------------------------------------------------------- response side
// Auto-refresh expired access token on 401, then retry the original request.
// This means the 15-minute access-token expiry no longer silently kicks the
// user out — they keep working as long as the 7-day refresh token is valid.
//
// Single-flight refresh: if 10 requests fire concurrently and each gets a 401,
// only ONE call to /auth/refresh is made; the rest wait on the same promise
// and then retry with the new token.
let refreshPromise: Promise<string> | null = null;

// This module lives outside React, so it cannot clear AuthContext state or
// navigate on its own. AuthProvider registers a handler here on mount; a
// forced logout then goes through exactly the same path as a manual logout
// (auth state cleared → RequireAuth renders <Navigate to="/login">), which
// works under BOTH HashRouter and BrowserRouter. Writing window.location.hash
// only ever worked under the former, so on the Android/web BrowserRouter build
// the cashier was left on a fully rendered POS with no login screen.
type ForcedLogoutHandler = () => void;
let forcedLogoutHandler: ForcedLogoutHandler | null = null;

export function setForcedLogoutHandler(handler: ForcedLogoutHandler | null): void {
  forcedLogoutHandler = handler;
}

function forceLogout(): void {
  // Credentials only. The terminal ID and the open-shift context are device
  // identity, not credentials — wiping them here stranded the POS behind a
  // terminal-selection prompt (2+ terminals) with an apparently vanished cart.
  localStorage.removeItem('access_token');
  localStorage.removeItem('refresh_token');
  localStorage.removeItem('pricing_token');
  localStorage.removeItem('pricing_token_expires_at');
  if (forcedLogoutHandler) {
    forcedLogoutHandler();
    return;
  }
  // No provider mounted (yet) to clear — reload so the app boots tokenless and
  // lands on the login route under either router mode. Tokens are already gone,
  // so this cannot loop.
  if (typeof window !== 'undefined') window.location.reload();
}

/**
 * Was the refresh token *definitively* rejected, or did we merely fail to ask?
 * The backend answers an invalid/expired/revoked refresh token with 401 (and a
 * forbidden one with 403). A timeout, a dropped connection or a 5xx says
 * nothing about whether the still-valid 7-day token is good — discarding it on
 * those turned ~20s of bad cafe wifi into a permanent hard logout.
 */
function isRefreshRejection(error: unknown): boolean {
  // Nothing left to preserve (e.g. "no refresh token") — treat as definitive.
  if (!localStorage.getItem('refresh_token')) return true;
  const status = axios.isAxiosError(error) ? error.response?.status : undefined;
  return status === 401 || status === 403;
}

async function refreshAccessToken(): Promise<string> {
  if (refreshPromise) return refreshPromise;
  refreshPromise = (async () => {
    const refresh = localStorage.getItem('refresh_token');
    if (!refresh) throw new Error('no refresh token');
    const r = await axios.post<{ access_token: string; refresh_token: string }>(
      `${BASE_URL}/auth/refresh`,
      { refresh_token: refresh },
      { timeout: API_TIMEOUT_MS },
    );
    localStorage.setItem('access_token', r.data.access_token);
    localStorage.setItem('refresh_token', r.data.refresh_token);
    return r.data.access_token;
  })().finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
}

api.interceptors.response.use(
  (r) => r,
  async (err: AxiosError<{ error?: { code: string; message: string } }>) => {
    const cfg = err.config as AxiosRequestConfig & { _retried?: boolean };

    // 401 → try to refresh the token once, then retry the original request.
    // Skip refresh for the /auth/login or /auth/refresh routes themselves so
    // we don't loop. Skip if there is no refresh token saved.
    const url = String(cfg?.url || '');
    const isAuthRoute = url.includes('/auth/login') || url.includes('/auth/refresh');
    const hasRefresh = !!localStorage.getItem('refresh_token');

    if (
      err.response?.status === 401 &&
      hasRefresh && !isAuthRoute && !cfg._retried
    ) {
      try {
        const newToken = await refreshAccessToken();
        cfg._retried = true;
        cfg.headers = { ...(cfg.headers || {}), Authorization: `Bearer ${newToken}` };
        return api.request(cfg);
      } catch (refreshError) {
        if (isRefreshRejection(refreshError)) {
          // The server really did reject this session — log out for real.
          forceLogout();
        } else {
          // Bad link, not a bad token: keep the refresh token and report a
          // retryable failure instead of the misleading original 401.
          const retryable: ApiError = new Error(
            'Could not reach the server to renew this session. Check the connection and try again.',
          );
          retryable.code = 'network_error';
          return Promise.reject(retryable);
        }
      }
    }

    // Requests made with `responseType: 'blob'` (CSV/file exports) still get
    // their error body delivered as a Blob rather than parsed JSON, even on
    // a 4xx/5xx — axios doesn't know to parse it differently. Without this,
    // every export error would show the generic "Request failed with status
    // code 4xx" instead of the backend's actual error message.
    let serverMessage = err.response?.data?.error?.message;
    let serverCode = err.response?.data?.error?.code;
    if (!serverMessage && err.response?.data instanceof Blob) {
      try {
        const text = await err.response.data.text();
        const parsed = JSON.parse(text) as { error?: { code?: string; message?: string } };
        serverMessage = parsed?.error?.message;
        serverCode = parsed?.error?.code;
      } catch {
        // Not JSON (e.g. a genuine CSV body on a 2xx that got here some
        // other way) — fall through to the generic message below.
      }
    }

    const message = serverMessage ?? err.message ?? 'Unknown error talking to the API';
    const enriched: ApiError = new Error(message);
    enriched.code = serverCode ?? 'network_error';
    // Callers need to tell "the server rejected this session" (401/403) apart
    // from "we never got an answer" (timeout/offline, no status at all) — only
    // the former may destroy a stored session.
    enriched.status = err.response?.status;
    return Promise.reject(enriched);
  },
);

export type ApiError = Error & { code?: string; status?: number };
