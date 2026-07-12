import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowLeft, CheckCircle2, KeyRound, Loader2, LogIn, UserPlus,
} from 'lucide-react';

import { auth } from '@/lib/erp-api';
import { useAuth } from './AuthContext';

type Mode = 'login' | 'reset-request' | 'reset-confirm' | 'register-request' | 'register-confirm';

type PendingChallenge = {
  id: string;
  destination: string;
  email: string;
};

export default function Login() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [challenge, setChallenge] = useState<PendingChallenge | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  function switchMode(next: Mode) {
    setMode(next);
    setErr(null);
    setSuccess(null);
    setCode('');
    setChallenge(null);
    setPassword('');
    setConfirmPassword('');
  }

  function validateNewPassword() {
    if (password.length < 10) throw new Error('Password must be at least 10 characters');
    if (password !== confirmPassword) throw new Error('Passwords do not match');
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setPending(true);
    setErr(null);
    try {
      if (mode === 'login') {
        await login(email, password);
        nav('/pos', { replace: true });
        return;
      }

      if (mode === 'reset-request') {
        const result = await auth.requestPasswordReset(email.trim());
        setChallenge({ id: result.challenge_id, destination: result.destination, email: email.trim() });
        setMode('reset-confirm');
        return;
      }

      if (mode === 'reset-confirm') {
        validateNewPassword();
        if (!challenge) throw new Error('Request a new approval code');
        const result = await auth.confirmPasswordReset(challenge.id, code.trim(), password);
        setMode('login');
        setPassword('');
        setConfirmPassword('');
        setCode('');
        setChallenge(null);
        setSuccess(result.message);
        return;
      }

      if (mode === 'register-request') {
        validateNewPassword();
        const result = await auth.requestRegistration({
          email: email.trim(),
          name: name.trim(),
          phone: phone.trim() || undefined,
          password,
        });
        setChallenge({ id: result.challenge_id, destination: result.destination, email: email.trim() });
        setMode('register-confirm');
        return;
      }

      if (!challenge) throw new Error('Request a new approval code');
      const result = await auth.confirmRegistration(challenge.id, code.trim());
      setMode('login');
      setPassword('');
      setConfirmPassword('');
      setCode('');
      setChallenge(null);
      setSuccess(result.message);
    } catch (error) {
      setErr(error instanceof Error ? error.message : 'Unable to complete this request');
    } finally {
      setPending(false);
    }
  }

  const isConfirm = mode === 'reset-confirm' || mode === 'register-confirm';
  const title = mode === 'login'
    ? 'D Company ERP'
    : mode.startsWith('reset')
      ? 'Reset password'
      : 'Create new login';

  return (
    <div className="min-h-[100dvh] overflow-x-hidden flex items-center justify-center px-4 py-6 sm:p-6">
      <form onSubmit={submit} className="card w-full max-w-[24rem] space-y-5">
        <div className="relative space-y-3 text-center">
          {mode !== 'login' && (
            <button
              type="button"
              className="absolute left-0 top-0 rounded-lg p-2 text-fg-muted hover:bg-bg-raised hover:text-fg"
              onClick={() => switchMode('login')}
              aria-label="Back to sign in"
            >
              <ArrowLeft size={19}/>
            </button>
          )}
          <img
            src="/brand/den-emblem-gold.png"
            alt="D Company"
            className="mx-auto h-20 w-20 rounded-full object-contain bg-bg/80 ring-1 ring-accent-gold/50 shadow-glow"
          />
          <h1 className="text-2xl font-bold">{title}</h1>
          <p className="text-fg-muted text-sm">
            {mode === 'login' && 'Sign in to continue'}
            {mode === 'reset-request' && 'Enter the login email to request approval'}
            {mode === 'register-request' && 'Add an approved D Company account'}
            {isConfirm && `Approval code sent to ${challenge?.destination ?? 'the business mailbox'}`}
          </p>
        </div>

        {mode === 'register-request' && (
          <>
            <Field label="Full name">
              <input
                className="input"
                value={name}
                onChange={(e) => setName(e.target.value)}
                autoComplete="name"
                maxLength={200}
                required
                autoFocus
              />
            </Field>
            <Field label="Phone (optional)">
              <input
                className="input"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                autoComplete="tel"
                maxLength={20}
                inputMode="tel"
              />
            </Field>
          </>
        )}

        {!isConfirm && (
          <Field label="Email">
            <input
              className="input"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              required
              autoFocus={mode !== 'register-request'}
            />
          </Field>
        )}

        {mode === 'login' && (
          <Field label="Password">
            <input
              className="input"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </Field>
        )}

        {(mode === 'register-request' || mode === 'reset-confirm') && (
          <>
            <Field label="New password">
              <input
                className="input"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="new-password"
                minLength={10}
                required
              />
            </Field>
            <Field label="Confirm password">
              <input
                className="input"
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                autoComplete="new-password"
                minLength={10}
                required
              />
            </Field>
          </>
        )}

        {isConfirm && (
          <Field label="6-digit approval code">
            <input
              className="input text-center text-xl tracking-[0.35em]"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              autoComplete="one-time-code"
              inputMode="numeric"
              pattern="[0-9]{6}"
              required
              autoFocus
            />
          </Field>
        )}

        {err && <Status tone="error" text={err}/>}
        {success && <Status tone="success" text={success}/>}

        <button className="btn btn-primary w-full" disabled={pending} type="submit">
          {pending ? <Loader2 className="animate-spin" size={16}/> : mode === 'login'
            ? <LogIn size={16}/>
            : mode.startsWith('register') ? <UserPlus size={16}/> : <KeyRound size={16}/>}
          {pending
            ? 'Please wait…'
            : mode === 'login'
              ? 'Sign in'
              : isConfirm
                ? 'Confirm approval code'
                : 'Send approval code'}
        </button>

        {mode === 'login' && (
          <div className="grid grid-cols-2 gap-2 border-t border-bg-border pt-4">
            <button type="button" className="btn btn-ghost text-xs" onClick={() => switchMode('reset-request')}>
              <KeyRound size={14}/> Forgot password
            </button>
            <button type="button" className="btn btn-ghost text-xs" onClick={() => switchMode('register-request')}>
              <UserPlus size={14}/> New login
            </button>
          </div>
        )}

        <div className="flex items-center justify-center gap-4 text-xs text-fg-muted">
          <a className="hover:text-fg" href="/privacy.html">Privacy</a>
          <a className="hover:text-fg" href="/support.html">Support</a>
        </div>
      </form>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs text-fg-muted">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}

function Status({ tone, text }: { tone: 'error' | 'success'; text: string }) {
  const success = tone === 'success';
  return (
    <div className={`flex items-center gap-2 rounded-lg border p-2.5 text-sm ${success
      ? 'border-accent-good/40 bg-accent-good/10 text-accent-good'
      : 'border-accent-bad/40 bg-accent-bad/10 text-accent-bad'}`}>
      {success && <CheckCircle2 size={15}/>} {text}
    </div>
  );
}
