import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import {
  COOKIE_SESSION_MODE,
  api,
  clearBrowserRefreshCookie,
  clearSessionCredentials,
  hasSessionCandidate,
  installSessionTokens,
  readAccessToken,
  restoreSessionFromRefresh,
  sessionTransportHeaders,
  setForcedLogoutHandler,
  type ApiError,
} from '@/lib/api';
import { DEMO_MODE, DEMO_USER } from '@/lib/demo';
import type { TerminalDTO } from '@/lib/erp-api';
import { GAMING_CENTRE_TERMINAL_POLICY } from '@/lib/product-profile';
import {
  clearStoredTerminal,
  readStoredTerminalId,
  resolveTerminal,
  storeTerminalId,
  terminalResolutionMessage,
} from '@/lib/operational-context';
import { connectRealtime, disconnectRealtime } from '@/lib/realtime';
import { alarmPermission, requestAlarmPermission } from '@/lib/alarm';

interface Me {
  user_id: string;
  email: string;
  name: string;
  roles: string[];
  protected_access: boolean;
  audit_access: boolean;
  company_id: string;
  branch_id: string | null;
  accessible_modules: string[];
  effective_permissions?: string[];
}

const AUTH_BOOT_TIMEOUT_MS = 8000;
// Congested cafe wifi regularly blows the 8s cold-start budget. One more try on
// a longer budget beats sending a cashier back to the login screen with a
// customer waiting — see the boot effect below.
const AUTH_BOOT_RETRY_TIMEOUT_MS = 20_000;

/**
 * Only the server may invalidate a session. A 401/403 is that answer; an abort,
 * a timeout, an offline device or a 5xx is not — those must never clear stored
 * credentials.
 */
function isAuthRejection(error: unknown): boolean {
  const status = (error as ApiError | null)?.status;
  return status === 401 || status === 403;
}

interface TerminalClaim {
  terminalId: string | null;
  options: TerminalDTO[];
  issue: string | null;
}

async function resolveTerminalClaim(me: Me, signal?: AbortSignal): Promise<TerminalClaim> {
  try {
    const r = await api.get<TerminalDTO[]>('/settings/terminals', {
      params: me.branch_id ? { branch_id: me.branch_id } : undefined,
      signal,
    });
    const resolution = resolveTerminal(
      me.branch_id,
      readStoredTerminalId(),
      r.data,
      GAMING_CENTRE_TERMINAL_POLICY,
    );
    if (resolution.kind === 'ready') {
      storeTerminalId(resolution.terminalId);
      return { terminalId: resolution.terminalId, options: r.data, issue: null };
    }
    clearStoredTerminal();
    return {
      terminalId: null,
      options: 'terminals' in resolution ? resolution.terminals : [],
      issue: terminalResolutionMessage(resolution),
    };
  } catch (error) {
    // The request failed — that is not proof the stored terminal is wrong, so
    // the device keeps its identity and this load simply reports the issue.
    return {
      terminalId: null,
      options: [],
      issue: `Terminal validation failed: ${(error as Error).message}`,
    };
  }
}

// Credentials only. The terminal ID and its open-shift context belong to the
// device, not to the signed-in staff member: clearing them here made a branch
// with 2+ terminals refuse to open POS after any logout, and made the saved
// cart look lost. resolveTerminalClaim revalidates the stored terminal against
// the server on every login, so keeping it is not a trust shortcut.
function clearStoredSession() {
  clearSessionCredentials();
}

interface AuthState {
  me: Me | null;
  loading: boolean;
  demo: boolean;
  terminalId: string | null;
  terminalReady: boolean;
  terminalOptions: TerminalDTO[];
  terminalIssue: string | null;
  selectTerminal: (terminalId: string) => void;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthCtx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const [terminalId, setTerminalId] = useState<string | null>(null);
  const [terminalOptions, setTerminalOptions] = useState<TerminalDTO[]>([]);
  const [terminalIssue, setTerminalIssue] = useState<string | null>(null);

  function applyTerminalClaim(claim: TerminalClaim) {
    setTerminalId(claim.terminalId);
    setTerminalOptions(claim.options);
    setTerminalIssue(claim.issue);
  }

  // Ask for notification permission app-wide, not just on the Gaming screen
  // (GamingScreen.tsx also asks, but only if a staff member happens to open
  // it — someone who lands straight on POS and never visits Gaming would
  // otherwise never be prompted, and the held-order aging alarm's
  // notifyAlarm() call silently no-ops without granted permission).
  //
  // Goes through lib/alarm's platform-aware helpers: on the Android tablet
  // this raises the real OS POST_NOTIFICATIONS dialog, where the old
  // browser-only `Notification.requestPermission()` was simply undefined
  // inside the WebView and every alarm notification quietly never fired.
  useEffect(() => {
    if (!me) return;
    // This is a shared-terminal app — the same device sees many staff log
    // in/out across a shift. If the OS dialog is left undecided, a naive
    // [me]-keyed effect would re-prompt on every single login. Only ever
    // ask once per device; if that first ask goes undecided, we don't nag
    // again until the OS itself resets the permission.
    if (localStorage.getItem('notif_permission_asked')) return;
    void (async () => {
      if ((await alarmPermission()) !== 'default') return;
      localStorage.setItem('notif_permission_asked', '1');
      await requestAlarmPermission();
    })();
  }, [me]);

  // One shared real-time connection for the whole app, live for as long as
  // someone is signed in — screens subscribe to it instead of each polling
  // their own REST endpoint on a timer. connectRealtime() is a no-op if
  // there's no access token (demo mode), so this is safe unconditionally.
  useEffect(() => {
    if (me) connectRealtime();
    else disconnectRealtime();
  }, [me]);

  // The axios layer can detect a definitively rejected session but cannot clear
  // React state or route anywhere. Registered before the boot effect below so a
  // 401 raised during boot is handled here too.
  useEffect(() => {
    setForcedLogoutHandler(() => {
      clearStoredSession();
      setTerminalId(null);
      setTerminalOptions([]);
      setTerminalIssue(null);
      setMe(null);
    });
    return () => setForcedLogoutHandler(null);
  }, []);

  useEffect(() => {
    // Demo mode: skip the network entirely, auto-login as owner.
    if (DEMO_MODE) {
      setMe(DEMO_USER);
      setLoading(false);
      return;
    }
    let cancelled = false;
    let controller: AbortController | null = null;
    let timeout = 0;

    if (!hasSessionCandidate()) {
      setLoading(false);
      return () => {
        cancelled = true;
      };
    }

    async function loadSession(budgetMs: number): Promise<Me> {
      const active = new AbortController();
      controller = active;
      timeout = window.setTimeout(() => active.abort(), budgetMs);
      try {
        if (COOKIE_SESSION_MODE && !readAccessToken()) {
          await restoreSessionFromRefresh();
        }
        const r = await api.get<Me>('/auth/me', { signal: active.signal });
        const claim = await resolveTerminalClaim(r.data, active.signal);
        if (!cancelled) applyTerminalClaim(claim);
        return r.data;
      } finally {
        window.clearTimeout(timeout);
      }
    }

    void (async () => {
      try {
        let data: Me;
        try {
          data = await loadSession(AUTH_BOOT_TIMEOUT_MS);
        } catch (error) {
          if (cancelled || isAuthRejection(error)) throw error;
          // Slow boot, not a dead session — one retry on a longer budget.
          data = await loadSession(AUTH_BOOT_RETRY_TIMEOUT_MS);
        }
        if (!cancelled) setMe(data);
      } catch (error) {
        // Aborting the cold-start budget used to land here and delete the
        // session, force-logging the tablet out exactly when the wifi was
        // congested. Only a real rejection may do that; otherwise the tokens
        // stay put and the next boot (or the next request once the link is
        // back) restores the session without anyone retyping a password.
        if (isAuthRejection(error)) clearStoredSession();
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
      window.clearTimeout(timeout);
      controller?.abort();
    };
  }, []);

  async function login(email: string, password: string) {
    if (DEMO_MODE) {
      setMe(DEMO_USER);
      return;
    }
    clearStoredSession();
    const r = await api.post<{ access_token: string; refresh_token: string }>(
      '/auth/login',
      { email: email.trim().toLowerCase(), password },
      { headers: sessionTransportHeaders() },
    );
    installSessionTokens(r.data.access_token, r.data.refresh_token);
    const meRes = await api.get<Me>('/auth/me');
    const claim = await resolveTerminalClaim(meRes.data);
    applyTerminalClaim(claim);
    setMe(meRes.data);
  }

  function selectTerminal(nextTerminalId: string) {
    const terminal = terminalOptions.find((candidate) => candidate.id === nextTerminalId);
    if (!terminal || !me?.branch_id || terminal.branch_id !== me.branch_id) {
      setTerminalIssue('The selected terminal does not belong to this account branch.');
      return;
    }
    storeTerminalId(terminal.id);
    setTerminalId(terminal.id);
    setTerminalIssue(null);
  }

  function logout() {
    if (DEMO_MODE) {
      setMe(null);
      return;
    }
    clearStoredSession();
    void clearBrowserRefreshCookie();
    setTerminalId(null);
    setTerminalOptions([]);
    setTerminalIssue(null);
    setMe(null);
  }

  return (
    <AuthCtx.Provider value={{
      me,
      loading,
      demo: DEMO_MODE,
      terminalId,
      terminalReady: DEMO_MODE || Boolean(terminalId),
      terminalOptions,
      terminalIssue,
      selectTerminal,
      login,
      logout,
    }}>
      {children}
    </AuthCtx.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
