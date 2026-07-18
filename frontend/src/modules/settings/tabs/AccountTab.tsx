import { useState } from 'react';
import { AlertCircle, Bell, Check, KeyRound, Loader2 } from 'lucide-react';

import { auth } from '@/lib/erp-api';
import { alarmsEnabled, setAlarmsEnabled } from '@/lib/alarm';
import { rolesLabel } from '@/lib/roles';
import { useAuth } from '@/modules/auth/AuthContext';

export default function AccountTab() {
  const { me } = useAuth();
  const [challenge, setChallenge] = useState<{ id: string; destination: string } | null>(null);
  const [code, setCode] = useState('');
  const [nxt, setNxt] = useState('');
  const [conf, setConf] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [alarms, setAlarms] = useState(alarmsEnabled());

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    setDone(false);
    setBusy(true);
    try {
      if (!me?.email) throw new Error('Your login email is unavailable');
      if (!challenge) {
        const result = await auth.requestPasswordReset(me.email);
        setChallenge({ id: result.challenge_id, destination: result.destination });
        return;
      }
      if (nxt.length < 10) throw new Error('Password must be at least 10 characters');
      if (nxt !== conf) throw new Error('New passwords do not match');
      await auth.confirmPasswordReset(challenge.id, code, nxt);
      setDone(true);
      setChallenge(null);
      setCode('');
      setNxt('');
      setConf('');
    } catch (error) {
      setErr(error instanceof Error ? error.message : 'Unable to update password');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4 max-w-xl">
      <div className="card">
        <h3 className="font-bold mb-3">Your account</h3>
        <Row label="Name" value={me?.name ?? '—'}/>
        <Row label="Email" value={me?.email ?? '—'}/>
        <Row label="Role" value={rolesLabel(me?.roles)}/>
      </div>

      <div className="card">
        <h3 className="font-bold mb-3 flex items-center gap-2">
          <Bell size={14}/> Notifications & alarms
        </h3>
        <label className="flex items-start justify-between gap-3 cursor-pointer">
          <span>
            <span className="block text-sm font-medium">Alarm sounds & alerts</span>
            <span className="block text-xs text-fg-muted">
              Gaming session overtime and orders left waiting too long, on this device/browser.
            </span>
          </span>
          <input
            type="checkbox"
            className="mt-1 h-5 w-5 accent-accent shrink-0"
            checked={alarms}
            onChange={(e) => { setAlarms(e.target.checked); setAlarmsEnabled(e.target.checked); }}
          />
        </label>
      </div>

      <div className="card">
        <h3 className="font-bold mb-3 flex items-center gap-2">
          <KeyRound size={14}/> Change password
        </h3>
        <form onSubmit={submit} className="space-y-3">
          {!challenge ? (
            <p className="rounded-lg border border-bg-border bg-bg-raised/40 p-3 text-sm text-fg-muted">
              Changing your password requires a code from the business security mailbox.
            </p>
          ) : (
            <>
              <div className="rounded-lg border border-accent-gold/30 bg-accent-gold/10 p-3 text-sm text-accent-gold">
                Approval code sent to {challenge.destination}
              </div>
              <Field label="6-digit approval code">
                <input className="input text-center text-xl tracking-[0.35em]" required autoFocus
                  inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}"
                  value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}/>
              </Field>
              <Field label="New password (at least 10 characters)">
                <input type="password" required minLength={10} className="input" value={nxt}
                  onChange={(e) => setNxt(e.target.value)} autoComplete="new-password"/>
              </Field>
              <Field label="Confirm new password">
                <input type="password" required minLength={10} className="input" value={conf}
                  onChange={(e) => setConf(e.target.value)} autoComplete="new-password"/>
              </Field>
            </>
          )}
          {err && <ErrorRow text={err}/>}
          {done && (
            <div className="p-2.5 rounded-lg bg-accent-good/10 border border-accent-good/40 text-accent-good text-sm flex items-center gap-2">
              <Check size={14}/> Password updated.
            </div>
          )}
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <KeyRound size={14}/>}
            {busy ? 'Please wait…' : challenge ? 'Update approved password' : 'Send approval code'}
          </button>
        </form>
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between py-2 border-b border-bg-border/60 last:border-0">
      <span className="text-fg-muted text-sm">{label}</span>
      <span className="font-medium text-sm">{value}</span>
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

function ErrorRow({ text }: { text: string }) {
  return (
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
      <AlertCircle size={14}/> {text}
    </div>
  );
}
