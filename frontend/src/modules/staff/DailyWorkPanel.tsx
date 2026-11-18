import { useCallback, useEffect, useState } from 'react';
import { readSessionGeneration } from '@/lib/api';
import { useAuth } from '@/modules/auth/AuthContext';
import { hasExactPermission } from '@/lib/effective-permissions';
import { readPendingWork, recoverStartWork, runStartWork, workConfirmation, type PendingWork } from '@/lib/start-work';
import { parseRupeesToMinor } from '@/lib/money-input';
import Modal from '@/components/ui/Modal';

export default function DailyWorkPanel(props: { onChanged?: () => void; allowNewStart?: boolean; embedded?: boolean; newStartReady?: () => boolean }) {
  const {me,terminalId} = useAuth();
  const key = [readSessionGeneration(),me?.user_id,me?.company_id,me?.branch_id,terminalId,me?.effective_permissions?.slice().sort().join()].join(':');
  return <DailyWorkPanelScoped key={key} {...props}/>;
}
function DailyWorkPanelScoped({ onChanged, allowNewStart = true, embedded = false, newStartReady }: { onChanged?: () => void; allowNewStart?: boolean; embedded?: boolean; newStartReady?: () => boolean }) {
  const { me, terminalId, terminalOptions } = useAuth();
  const [pending, setPending] = useState<PendingWork | null>(null), [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false), [float, setFloat] = useState('0'), [owner, setOwner] = useState(false);
  const [email, setEmail] = useState(''), [password, setPassword] = useState(''), [reason, setReason] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [ownerPending,setOwnerPending] = useState<PendingWork | null>(null);
  const refresh = useCallback(() => { setPassword(''); setOwner(false); setOwnerPending(null); try { setPending(readPendingWork()); } catch (e) { setError((e as Error).message); } }, []);
  useEffect(() => {
    refresh(); const hide = () => { if (document.hidden) { setPassword(''); setOwner(false); } };
    document.addEventListener('visibilitychange', hide); window.addEventListener('storage', refresh);
    return () => { document.removeEventListener('visibilitychange', hide); window.removeEventListener('storage', refresh); };
  }, [refresh]);
  const action = async (run: () => Promise<void>) => {
    if (busy) return; setBusy(true); setError(null); setNotice(null);
    try { await run(); setNotice(workConfirmation()); onChanged?.(); }
    catch (e) { setError((e as Error).message); }
    finally { refresh(); setBusy(false); }
  };
  if (!me) return null;
  const canStart = hasExactPermission(me, 'pos.shift.open') && hasExactPermission(me, 'staff.attendance.write') && terminalOptions?.some(t => t.id === terminalId && t.online_start_work_supported === true) === true;
  if (!canStart && !pending && !error) return null;
  return <section className={embedded ? "space-y-3" : "card mb-4 space-y-3"} aria-label="Start work">
    <p className="text-sm text-fg-muted">Start work opens a new shift and clocks in the signed-in account, {me.name}. An existing clock-in keeps its original time. Till verification stays separate.</p>
    {notice && <p role="status">{notice}</p>}{error && <p role="alert" className="text-accent-bad">{error}</p>}
    {pending ? <div className="space-y-2"><p>{pending.actorName} · {pending.phase === 'UNKNOWN' ? 'Waiting to confirm this saved start' : 'Shift start recorded; checking current status'}</p>
      <p className="text-sm">Keep this original request until its outcome is verified. A missing open shift is not proof that it never committed.</p>
      <button className="btn btn-primary" disabled={busy || !terminalId} onClick={() => { if (terminalId) void action(() => recoverStartWork(me, terminalId, pending)); }}>Check saved start</button>
      <button className="btn btn-ghost" disabled={busy} onClick={() => { setReason(pending.cancelReason ?? ''); setOwnerPending(pending); setOwner(true); }}>Owner review</button>
    </div> : canStart && allowNewStart && <form className="flex flex-wrap gap-2 items-end" onSubmit={e => {
e.preventDefault();
      if (!allowNewStart || newStartReady && !newStartReady()) { setError('Refresh Shifts to verify the current open-shift status before starting work.'); return; }
      const cash = parseRupeesToMinor(float);
      if (cash === null) { setError('Enter a non-negative opening float with at most two decimals.'); return; }
      if (terminalId) void action(async () => {
        if (newStartReady && !newStartReady()) throw new Error('Refresh Shifts to verify the current open-shift status before starting work.');
        await runStartWork(me, terminalId, cash);
      });
}}>
      <label>Opening float<input className="input" inputMode="decimal" value={float} onChange={e => setFloat(e.target.value)} disabled={busy} /></label>
      <button className="btn btn-primary" disabled={busy || !terminalId}>{busy ? 'Verifying…' : `Start work · ${me.name}`}</button>
    </form>}
    <Modal open={owner} onClose={() => { setPassword(''); setOwner(false); }} title="Owner review of the exact saved start">
      <div className="space-y-3"><p>Check the original result, or permanently retire only an uncommitted request. A committed shift and clock-in are preserved. The signed-in account stays unchanged.</p>
        <label className="block">Owner email<input className="input" value={email} onChange={e => setEmail(e.target.value)} autoComplete="off" /></label>
        <label className="block">Owner password<input className="input" type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete="off" /></label>
        <label className="block">Reason to retire an uncommitted request<textarea className="input" maxLength={500} value={reason} onChange={e => setReason(e.target.value)} /></label>
        {[false, true].map(cancel => <button key={String(cancel)} className="btn btn-primary" disabled={busy || !terminalId || !ownerPending || !password || !email || (cancel && reason.trim().length < 12)} onClick={() => {
          const secret = password; setPassword(''); setOwner(false);
          if (terminalId) void action(() => recoverStartWork(me, terminalId, ownerPending!, { email, password: secret, reason, cancel }));
        }}>{cancel ? 'Retire if uncommitted' : 'Check original result'}</button>)}
      </div>
    </Modal>
  </section>;
}
