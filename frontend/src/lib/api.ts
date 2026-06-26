import axios, { AxiosError, type AxiosRequestConfig } from 'axios';

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

const BASE_URL = import.meta.env.VITE_API_URL ?? RUNTIME_URL ?? '/api/v1';

export const api = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

export { BASE_URL };

// ---------------------------------------------------------------- request side
// Inject access token + tenant headers.
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  const terminalId = localStorage.getItem('terminal_id');
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

async function refreshAccessToken(): Promise<string> {
  if (refreshPromise) return refreshPromise;
  refreshPromise = (async () => {
    const refresh = localStorage.getItem('refresh_token');
    if (!refresh) throw new Error('no refresh token');
    const r = await axios.post<{ access_token: string; refresh_token: string }>(
      `${BASE_URL}/auth/refresh`,
      { refresh_token: refresh },
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
      } catch {
        // Refresh failed — wipe creds and let the original 401 bubble up.
        localStorage.removeItem('access_token');
        localStorage.removeItem('refresh_token');
        localStorage.removeItem('terminal_id');
        localStorage.removeItem('pricing_token');
        localStorage.removeItem('pricing_token_expires_at');
        if (typeof window !== 'undefined' && !window.location.hash.includes('/login')) {
          window.location.hash = '/login';
        }
      }
    }

    const message =
      err.response?.data?.error?.message ??
      err.message ??
      'Unknown error talking to the API';
    const enriched = new Error(message);
    (enriched as Error & { code?: string }).code =
      err.response?.data?.error?.code ?? 'network_error';
    return Promise.reject(enriched);
  },
);

export type ApiError = Error & { code?: string };
