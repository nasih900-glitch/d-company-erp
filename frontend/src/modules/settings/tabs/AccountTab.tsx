import { useState } from 'react';
import { KeyRound, Loader2, AlertCircle, Check } from 'lucide-react';

import { useAuth } from '@/modules/auth/AuthContext';
import { staff } from '@/lib/erp-api';
import { rolesLabel } from '@/lib/roles';

export default function AccountTab() {
  const { me } = useAuth();
  const [cur, setCur] = useState('');
  const [nxt, setNxt] = useState('');
  const [conf, setConf] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null); setDone(false);
    if (nxt.length < 8) { setErr('Password must be at least 8 characters'); return; }
    if (nxt !== conf) { setErr('New passwords do not match'); return; }
    setBusy(true);
    try {
      await staff.changeMyPassword(cur, nxt);
      setDone(true);
      setCur(''); setNxt(''); setConf('');
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
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
          <KeyRound size={14}/> Change password
        </h3>
        <form onSubmit={submit} className="space-y-3">
          <Field label="Current password">
            <input type="password" required className="input" value={cur} onChange={(e) => setCur(e.target.value)}/>
          </Field>
          <Field label="New password (≥ 8 chars)">
            <input type="password" required minLength={8} className="input" value={nxt} onChange={(e) => setNxt(e.target.value)}/>
          </Field>
          <Field label="Confirm new password">
            <input type="password" required className="input" value={conf} onChange={(e) => setConf(e.target.value)}/>
          </Field>
          {err && <ErrorRow text={err}/>}
          {done && (
            <div className="p-2.5 rounded-lg bg-accent-good/10 border border-accent-good/40 text-accent-good text-sm flex items-center gap-2">
              <Check size={14}/> Password updated.
            </div>
          )}
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <KeyRound size={14}/>}
            Update password
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
