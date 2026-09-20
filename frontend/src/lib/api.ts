import axios, { AxiosError, type AxiosRequestConfig } from 'axios';
import { readStoredTerminalId } from './operational-context';
import { recordFailedSupportAction } from './support-context';
import { apiFailureMessage } from './api-error-message';

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
const COOKIE_SESSION_HEADER = 'X-Session-Transport';
const COOKIE_SESSION_SIGNED_OUT_KEY = 'dcompany_cookie_session_signed_out';
const COOKIE_SESSION_RENEWAL_LOCK = 'dcompany-cookie-session-renewal';

/**
 * HttpOnly refresh cookies are safe only when the browser and API are the same
 * origin. Capacitor/file shells and deliberately separate API hosts keep the
 * native-compatible JSON token contract instead.
 */
export function isSameOriginHttpApi(apiUrl: string, pageUrl: string): boolean {
  try {
    const page = new URL(pageUrl);
    const resolvedApi = new URL(apiUrl, page);
    return (
      (page.protocol === 'http:' || page.protocol === 'https:') &&
      resolvedApi.origin === page.origin
    );
  } catch {
    return false;
  }
}

/**
 * AbortController cancellation is normal during navigation, sign-out, and
 * request replacement. It must not become a fake network incident in the
 * contextual support report or alarm the operator.
 */
export function isExpectedRequestCancellation(error: unknown): boolean {
  return axios.isCancel(error)
    || (axios.isAxiosError(error) && error.code === 'ERR_CANCELED');
}

export const COOKIE_SESSION_MODE =
  typeof window !== 'undefined' &&
  isSameOriginHttpApi(BASE_URL, window.location.href);

type CookieRefreshState = 'unknown' | 'available' | 'absent';
let cookieRefreshState: CookieRefreshState = 'unknown';
let volatileAccessToken: string | null = null;
let volatilePricingToken: string | null = null;
let volatilePricingExpiresAt = 0;
let legacyRefreshForCookieMigration: string | null = null;
let sessionGeneration = 0;

type ActiveSessionRenewal = {
  generation: number;
  controller: AbortController;
  promise: Promise<string>;
};
let activeSessionRenewal: ActiveSessionRenewal | null = null;

function advanceSessionGeneration(): void {
  sessionGeneration += 1;
  const previousRenewal = activeSessionRenewal;
  activeSessionRenewal = null;
  // In cookie mode this also asks the browser to stop accepting a late
  // Set-Cookie from the superseded account's refresh response. The generation
  // check below remains the final application-side guard if cancellation loses
  // a race with a response already delivered by the network stack.
  previousRenewal?.controller.abort();
}

function readStorage(key: string): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string): void {
  try { localStorage.setItem(key, value); } catch { /* storage may be unavailable */ }
}

function removeStorage(...keys: string[]): void {
  try { keys.forEach((key) => localStorage.removeItem(key)); } catch { /* no-op */ }
}

if (COOKIE_SESSION_MODE) {
  // One release transition may still have the former plaintext refresh token.
  // Move it into memory immediately, erase the disk copy, then exchange it for
  // the HttpOnly cookie during normal boot. A signed-out marker suppresses
  // migration so a failed offline logout cannot silently sign the user back in.
  if (readStorage(COOKIE_SESSION_SIGNED_OUT_KEY) !== '1') {
    legacyRefreshForCookieMigration = readStorage('refresh_token');
  }
  removeStorage(
    'access_token',
    'refresh_token',
    'pricing_token',
    'pricing_token_expires_at',
  );
}

export function sessionTransportHeaders(): Record<string, string> | undefined {
  return COOKIE_SESSION_MODE ? { [COOKIE_SESSION_HEADER]: 'cookie' } : undefined;
}

export function readAccessToken(): string | null {
  return COOKIE_SESSION_MODE ? volatileAccessToken : readStorage('access_token');
}

/** Non-secret lineage used to retire callbacks owned by an earlier login. */
export function readSessionGeneration(): number {
  return sessionGeneration;
}

function validateSessionTokens(accessToken: string, refreshToken?: string): void {
  if (!accessToken.trim()) throw new Error('The server returned an empty access token.');
  if (!COOKIE_SESSION_MODE && !refreshToken?.trim()) {
    throw new Error('The server returned an empty refresh token.');
  }
}

function applySessionTokens(accessToken: string, refreshToken?: string): void {
  volatileAccessToken = accessToken;
  if (COOKIE_SESSION_MODE) {
    cookieRefreshState = 'available';
    legacyRefreshForCookieMigration = null;
    removeStorage('access_token', 'refresh_token', COOKIE_SESSION_SIGNED_OUT_KEY);
    return;
  }
  writeStorage('access_token', accessToken);
  writeStorage('refresh_token', refreshToken!);
}

export function installSessionTokens(accessToken: string, refreshToken?: string): void {
  // Validate before superseding a healthy session. A malformed login response
  // must not cancel the current account's valid renewal authority.
  validateSessionTokens(accessToken, refreshToken);
  advanceSessionGeneration();
  applySessionTokens(accessToken, refreshToken);
}

export function clearPricingToken(): void {
  volatilePricingToken = null;
  volatilePricingExpiresAt = 0;
  removeStorage('pricing_token', 'pricing_token_expires_at');
}

export function storePricingToken(token: string, expiresInSeconds: number): void {
  if (!token.trim() || !Number.isFinite(expiresInSeconds) || expiresInSeconds <= 0) {
    throw new Error('The server returned an invalid pricing unlock.');
  }
  const expiresAt = Date.now() + expiresInSeconds * 1000;
  volatilePricingToken = token;
  volatilePricingExpiresAt = expiresAt;
  if (!COOKIE_SESSION_MODE) {
    writeStorage('pricing_token', token);
    writeStorage('pricing_token_expires_at', String(expiresAt));
  } else {
    // Remove credentials left by a previous web build. Protected unlocks are
    // intentionally memory-only and must be re-entered after a page restart.
    removeStorage('pricing_token', 'pricing_token_expires_at');
  }
}

export function readPricingToken(): string | null {
  const token = COOKIE_SESSION_MODE
    ? volatilePricingToken
    : readStorage('pricing_token');
  const expiresAt = COOKIE_SESSION_MODE
    ? volatilePricingExpiresAt
    : Number(readStorage('pricing_token_expires_at') || '0');
  if (token && expiresAt > Date.now()) return token;
  clearPricingToken();
  return null;
}

export function hasActivePricingToken(): boolean {
  return readPricingToken() !== null;
}

export function clearSessionCredentials(): void {
  advanceSessionGeneration();
  volatileAccessToken = null;
  legacyRefreshForCookieMigration = null;
  cookieRefreshState = 'absent';
  removeStorage('access_token', 'refresh_token');
  clearPricingToken();
  if (COOKIE_SESSION_MODE) writeStorage(COOKIE_SESSION_SIGNED_OUT_KEY, '1');
}

export function hasSessionCandidate(): boolean {
  if (!COOKIE_SESSION_MODE) return readAccessToken() !== null;
  return (
    cookieRefreshState !== 'absent' &&
    readStorage(COOKIE_SESSION_SIGNED_OUT_KEY) !== '1'
  );
}

export const api = axios.create({
  baseURL: BASE_URL,
  timeout: API_TIMEOUT_MS,
  withCredentials: COOKIE_SESSION_MODE,
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

export function isBugReportApiRequest(url: string): boolean {
  const path = url.split(/[?#]/, 1)[0] ?? '';
  return /(?:^|\/)bug-reports(?:\/|$)/.test(path);
}

// ---------------------------------------------------------------- request side
// Inject access token + tenant headers.
type SessionRequestConfig = AxiosRequestConfig & {
  _retried?: boolean;
  _sessionGeneration?: number;
};

api.interceptors.request.use((config) => {
  const sessionConfig = config as typeof config & SessionRequestConfig;
  if (sessionConfig._sessionGeneration === undefined) {
    sessionConfig._sessionGeneration = sessionGeneration;
  } else if (sessionConfig._sessionGeneration !== sessionGeneration) {
    // api.request() runs request interceptors asynchronously. Recheck here so
    // a replacement login queued after the response-side check cannot attach
    // its bearer to the prior account's retried operation.
    throw sessionRenewalError(
      'session_changed',
      'The request belongs to a session that is no longer active.',
    );
  }
  const token = readAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  const url = String(config.url || '');
  const supportRequest = isBugReportApiRequest(url);
  // These two routes validate identity/terminal state. Sending an unvalidated
  // cached terminal here would prevent the app from repairing stale storage.
  // Support requests carry captured branch/terminal evidence in their body.
  // Binding them to the device's current terminal would make a legitimate
  // retry change meaning after terminal selection changes.
  const validatesContext = url.includes('/auth/')
    || url.includes('/settings/terminals');
  const omitsTerminalContext = validatesContext || supportRequest;
  if (supportRequest) config.headers.delete('X-Terminal-Id');
  const terminalId = omitsTerminalContext ? null : readStoredTerminalId();
  if (terminalId) config.headers['X-Terminal-Id'] = terminalId;
  const pricingToken = readPricingToken();
  if (pricingToken) config.headers['X-Pricing-Token'] = pricingToken;
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
  clearSessionCredentials();
  void clearBrowserRefreshCookie();
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
  if (!COOKIE_SESSION_MODE && !readStorage('refresh_token')) return true;
  const status = axios.isAxiosError(error) ? error.response?.status : undefined;
  return status === 401 || status === 403;
}

function sessionRenewalError(
  code: 'network_error' | 'session_changed' | 'unauthorized',
  message: string,
  status?: number,
): ApiError {
  const error: ApiError = new Error(message);
  error.code = code;
  error.status = status;
  return error;
}

function isSessionChanged(error: unknown): boolean {
  return error instanceof Error
    && (error as ApiError).code === 'session_changed';
}

/**
 * Renews one logical session for both Axios and WebSocket callers. A logout or
 * replacement login detaches and aborts the old generation, so the new account
 * can never join its promise or receive its tokens.
 */
export function renewSessionAccessToken(): Promise<string> {
  if (activeSessionRenewal?.generation === sessionGeneration) {
    return activeSessionRenewal.promise;
  }

  const generation = sessionGeneration;
  const controller = new AbortController();
  const promise = (async () => {
    const refresh = COOKIE_SESSION_MODE
      ? legacyRefreshForCookieMigration
      : readStorage('refresh_token');
    try {
      const renew = async (): Promise<string> => {
        if (generation !== sessionGeneration || controller.signal.aborted) {
          throw sessionRenewalError(
            'session_changed',
            'The session changed while its access was being renewed.',
          );
        }
        if (!COOKIE_SESSION_MODE && !refresh) throw new Error('no refresh token');
        const response = await axios.post<{ access_token: string; refresh_token: string }>(
          `${BASE_URL}/auth/refresh`,
          COOKIE_SESSION_MODE
            ? (refresh ? { refresh_token: refresh } : {})
            : { refresh_token: refresh },
          {
            timeout: API_TIMEOUT_MS,
            withCredentials: COOKIE_SESSION_MODE,
            headers: sessionTransportHeaders(),
            signal: controller.signal,
          },
        );
        if (generation !== sessionGeneration || controller.signal.aborted) {
          throw sessionRenewalError(
            'session_changed',
            'The session changed while its access was being renewed.',
          );
        }
        validateSessionTokens(response.data.access_token, response.data.refresh_token);
        applySessionTokens(response.data.access_token, response.data.refresh_token);
        return response.data.access_token;
      };

      if (!COOKIE_SESSION_MODE) return await renew();
      const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
      if (!locks || typeof locks.request !== 'function') {
        throw sessionRenewalError(
          'network_error',
          'This browser cannot safely coordinate session renewal across tabs.',
        );
      }
      return await locks.request(
        COOKIE_SESSION_RENEWAL_LOCK,
        { mode: 'exclusive', signal: controller.signal },
        renew,
      );
    } catch (error) {
      if (generation !== sessionGeneration || isSessionChanged(error)) {
        throw sessionRenewalError(
          'session_changed',
          'The session changed while its access was being renewed.',
        );
      }
      if (isRefreshRejection(error)) {
        const status = axios.isAxiosError(error) ? error.response?.status : 401;
        forceLogout();
        throw sessionRenewalError(
          'unauthorized',
          axios.isAxiosError(error) ? error.message : 'The session can no longer be renewed.',
          status ?? 401,
        );
      }
      throw sessionRenewalError(
        'network_error',
        'Could not reach the server to renew this session. Check the connection and try again.',
      );
    }
  })().finally(() => {
    if (activeSessionRenewal?.promise === promise) activeSessionRenewal = null;
  });
  activeSessionRenewal = { generation, controller, promise };
  return promise;
}

export async function restoreSessionFromRefresh(): Promise<string> {
  if (!COOKIE_SESSION_MODE || !hasSessionCandidate()) {
    const error: ApiError = new Error('no browser session');
    error.code = 'unauthorized';
    error.status = 401;
    throw error;
  }
  try {
    return await renewSessionAccessToken();
  } catch (error) {
    if (error instanceof Error) throw error;
    throw sessionRenewalError('network_error', 'Could not renew this session.');
  }
}

/** Best-effort server-side deletion of the HttpOnly browser credential. */
export async function clearBrowserRefreshCookie(): Promise<void> {
  if (!COOKIE_SESSION_MODE) return;
  try {
    await axios.post(
      `${BASE_URL}/auth/logout`,
      {},
      {
        timeout: API_TIMEOUT_MS,
        withCredentials: true,
        headers: sessionTransportHeaders(),
      },
    );
  } catch {
    // Local state is still signed out and carries a non-secret suppression
    // marker. The cookie is SameSite+HttpOnly and expires server-side; the next
    // online login/logout attempt will replace or remove it.
  }
}

api.interceptors.response.use(
  (r) => r,
  async (err: AxiosError<{ error?: { code: string; message: string } }>) => {
    if ((err as ApiError)?.code === 'session_changed') return Promise.reject(err);
    if (isExpectedRequestCancellation(err)) return Promise.reject(err);

    const cfg = err.config as SessionRequestConfig;

    // 401 → try to refresh the token once, then retry the original request.
    // Skip refresh for the /auth/login or /auth/refresh routes themselves so
    // we don't loop. Skip if there is no refresh token saved.
    const url = String(cfg?.url || '');
    const isAuthRoute = url.includes('/auth/login') || url.includes('/auth/refresh');
    const hasRefresh = COOKIE_SESSION_MODE
      ? hasSessionCandidate()
      : !!readStorage('refresh_token');

    if (
      err.response?.status === 401 &&
      hasRefresh && !isAuthRoute && !cfg._retried
    ) {
      if (
        cfg._sessionGeneration !== undefined
        && cfg._sessionGeneration !== sessionGeneration
      ) {
        return Promise.reject(sessionRenewalError(
          'session_changed',
          'The request belongs to a session that is no longer active.',
        ));
      }
      try {
        const newToken = await renewSessionAccessToken();
        if (
          cfg._sessionGeneration !== undefined
          && cfg._sessionGeneration !== sessionGeneration
        ) {
          throw sessionRenewalError(
            'session_changed',
            'The request belongs to a session that is no longer active.',
          );
        }
        cfg._retried = true;
        cfg.headers = { ...(cfg.headers || {}), Authorization: `Bearer ${newToken}` };
        return api.request(cfg);
      } catch (refreshError) {
        const refreshCode = (refreshError as ApiError)?.code;
        if (refreshCode === 'network_error') {
          // Bad link, not a bad token: keep the refresh token and report a
          // retryable failure instead of the misleading original 401.
          recordFailedSupportAction({
            method: cfg.method,
            url,
            errorCode: 'network_error',
          });
          return Promise.reject(refreshError);
        }
        if (refreshCode !== 'unauthorized') {
          return Promise.reject(refreshError);
        }
        // Preserve the established caller-facing 401 mapping below after the
        // shared renewal boundary has already performed the definitive logout.
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

    const message = apiFailureMessage(serverMessage, err.response?.status);
    const enriched: ApiError = new Error(message);
    enriched.code = serverCode ?? 'network_error';
    // Callers need to tell "the server rejected this session" (401/403) apart
    // from "we never got an answer" (timeout/offline, no status at all) — only
    // the former may destroy a stored session.
    enriched.status = err.response?.status;
    recordFailedSupportAction({
      method: cfg?.method,
      url,
      errorCode: enriched.code,
      status: enriched.status,
    });
    return Promise.reject(enriched);
  },
);

export type ApiError = Error & { code?: string; status?: number };
