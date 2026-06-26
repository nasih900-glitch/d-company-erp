import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from './AuthContext';

export default function Login() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setPending(true);
    setErr(null);
    try {
      await login(email, password);
      nav('/pos', { replace: true });
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="min-h-[100dvh] overflow-x-hidden flex items-center justify-center px-4 py-6 sm:p-6">
      <form onSubmit={onSubmit} className="card w-full max-w-[22rem] space-y-5 sm:max-w-sm">
        <div className="space-y-3 text-center">
          <img
            src="/brand/den-emblem-gold.png"
            alt="D Company"
            className="mx-auto h-20 w-20 rounded-full object-contain bg-bg/80 ring-1 ring-accent-gold/50 shadow-glow"
          />
          <h1 className="text-2xl font-bold">D Company ERP</h1>
          <p className="text-fg-muted text-sm">Sign in to continue</p>
        </div>
        <label className="block">
          <span className="text-xs text-fg-muted">Email</span>
          <input
            className="input mt-1"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            required
            autoFocus
          />
        </label>
        <label className="block">
          <span className="text-xs text-fg-muted">Password</span>
          <input
            className="input mt-1"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        {err && <p className="text-accent-bad text-sm">{err}</p>}
        <button className="btn btn-primary w-full" disabled={pending} type="submit">
          {pending ? 'Signing in…' : 'Sign in'}
        </button>
        <div className="flex items-center justify-center gap-4 text-xs text-fg-muted">
          <a className="hover:text-fg" href="/privacy.html">Privacy</a>
          <a className="hover:text-fg" href="/support.html">Support</a>
        </div>
      </form>
    </div>
  );
}
