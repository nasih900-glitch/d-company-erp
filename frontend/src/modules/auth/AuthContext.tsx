import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { api } from '@/lib/api';
import { DEMO_MODE, DEMO_USER } from '@/lib/demo';

interface Me {
  user_id: string;
  email: string;
  name: string;
  roles: string[];
  protected_access: boolean;
  company_id: string;
  branch_id: string | null;
}

/**
 * Claim a POS terminal so X-Terminal-Id can be sent on every request.
 *
 * Why: the backend POS routes require this header so receipts know which
 * device issued them (multi-terminal support). On a fresh device we:
 *   1. Look in localStorage — already claimed? done.
 *   2. Ask /settings/terminals — pick the first one.
 *   3. None exist? Skip silently; POS will surface a friendly error.
 */
async function ensureTerminalClaimed(): Promise<void> {
  if (localStorage.getItem('terminal_id')) return;
  try {
    const r = await api.get<Array<{ id: string }>>('/settings/terminals');
    if (r.data?.length) {
      localStorage.setItem('terminal_id', r.data[0].id);
    }
  } catch {
    // Non-fatal — POS will show its own error if it needs the terminal.
  }
}

function clearStoredSession() {
  localStorage.removeItem('access_token');
  localStorage.removeItem('refresh_token');
  localStorage.removeItem('terminal_id');
  localStorage.removeItem('pricing_token');
  localStorage.removeItem('pricing_token_expires_at');
}

interface AuthState {
  me: Me | null;
  loading: boolean;
  demo: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthCtx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Demo mode: skip the network entirely, auto-login as owner.
    if (DEMO_MODE) {
      setMe(DEMO_USER);
      setLoading(false);
      return;
    }
    const token = localStorage.getItem('access_token');
    if (!token) {
      setLoading(false);
      return;
    }
    api
      .get<Me>('/auth/me')
      .then(async (r) => {
        setMe(r.data);
        await ensureTerminalClaimed();
      })
      .catch(() => clearStoredSession())
      .finally(() => setLoading(false));
  }, []);

  async function login(email: string, password: string) {
    if (DEMO_MODE) {
      setMe(DEMO_USER);
      return;
    }
    clearStoredSession();
    const r = await api.post<{ access_token: string; refresh_token: string }>(
      '/auth/login',
      { email, password },
    );
    localStorage.setItem('access_token', r.data.access_token);
    localStorage.setItem('refresh_token', r.data.refresh_token);
    const meRes = await api.get<Me>('/auth/me');
    setMe(meRes.data);
    await ensureTerminalClaimed();
  }

  function logout() {
    if (DEMO_MODE) {
      setMe(null);
      return;
    }
    clearStoredSession();
    setMe(null);
  }

  return (
    <AuthCtx.Provider value={{ me, loading, demo: DEMO_MODE, login, logout }}>
      {children}
    </AuthCtx.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
