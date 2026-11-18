import { useCallback, useEffect, useState } from 'react';
import { usePaidFocusedField } from './use-paid-focused-field';
import { Link } from 'react-router-dom';
import { HistoricalPaidRecoveryController } from '@/lib/paid-bill-historical-controller';
import { inr } from '@/lib/inr';
import { paidStatusLabel } from '@/lib/paid-bill-contract';
import { subscribeRootWorkspace } from '@/lib/root-workspace-epoch';
import { subscribePaidBillWork } from '@/lib/paid-bill-journal';

export default function HistoricalPaidBillRecovery() {
  const [controller, setController] = useState<HistoricalPaidRecoveryController | null>(null);
  const [view, setView] = useState<HistoricalPaidRecoveryController['view']>({ busy: false, error: null, notice: 'Keep browser data. The original Owner must authenticate to check saved work.', private: null });
  const [password, setPassword] = useState(''), [reason, setReason] = useState('');
  useEffect(() => {
    const value = new HistoricalPaidRecoveryController(); setController(value);
    const unsubscribe = value.subscribe(() => { setView(value.view); if (!value.view.private) { setPassword(''); setReason(''); } });
    const clearInputs = () => { setPassword(''); setReason(''); };
    const context = subscribeRootWorkspace(clearInputs), journal = subscribePaidBillWork(clearInputs);
    return () => { context(); journal(); unsubscribe(); value.dispose(); };
  }, []);
  const currentPage = useCallback(() => controller !== null, [controller]);
  const visibleField = usePaidFocusedField(currentPage);
  const data = view.private;
  return <main ref={visibleField} className="min-h-[100dvh] bg-bg px-4 py-6 text-fg sm:p-8"><section className="card mx-auto w-full max-w-lg space-y-4" aria-label="Saved Owner work recovery">
    <h1 className="text-xl font-semibold">Recover saved Owner work</h1>
    <p className="text-sm text-fg-muted">This check keeps the current register and sign-in records unchanged. It cannot submit a bill change or authorize a payout.</p>
    <p role="status" className="text-sm">{view.notice}</p>
    {view.error && <p role="alert" className="text-sm text-accent-bad">{view.error}</p>}
    {!data && <form className="space-y-3" onSubmit={e => { e.preventDefault(); const secret = password; setPassword(''); void controller?.unlock(secret); }}>
      <label className="block text-sm">Original Owner password<input type="password" className="input mt-1" autoComplete="current-password" maxLength={1024} disabled={view.busy} value={password} onChange={e => setPassword(e.target.value)} /></label>
      <button type="submit" className="btn btn-primary" disabled={!controller || view.busy || !password}>Authenticate and check saved work</button>
    </form>}
    {data && <section aria-label="Authenticated saved request" className="space-y-3 border-t border-bg-border pt-3">
      <h2 className="font-semibold">{data.action.expected.invoice_no}</h2>
      <dl className="grid grid-cols-2 gap-2 text-sm"><dt>Original bill</dt><dd>{inr(data.action.expected.original_total_minor)}</dd><dt>Reviewed amount</dt><dd>{inr(data.action.body.target_total_minor)}</dd></dl>
      <p className="whitespace-pre-wrap break-words text-sm">{data.action.body.reason}</p>
      {data.outcome?.outcome === 'accepted' && <div className="space-y-2 text-sm"><strong>Current bill state: {paidStatusLabel(data.outcome.current.status)}</strong><p>Current bill refund remaining: {data.outcome.current.payout_remaining_minor === null ? 'Unknown — review the existing task' : inr(data.outcome.current.payout_remaining_minor)}</p>
        {data.outcome.accepted.refund_request_id && <p>This saved action’s existing refund task: <span className="break-all">{data.outcome.accepted.refund_request_id}</span>. Return to normal authorized operations to review this exact task. No payout can be started here.</p>}</div>}
      {data.outcome?.outcome === 'cancelled' && <p className="text-sm">Only the saved request was stopped. The original bill was kept.</p>}
      {data.pending && <button type="button" className="btn btn-primary" disabled={view.busy} onClick={() => void controller?.check()}>Check saved outcome again</button>}
      {data.pending && !data.outcome && <form className="space-y-2" onSubmit={e => { e.preventDefault(); void controller?.check(true, reason); }}>
        <label className="block text-sm">Reason for stopping this saved request<textarea className="input mt-1" maxLength={500} disabled={view.busy || data.reason !== null} value={data.reason ?? reason} onChange={e => setReason(e.target.value)} /></label>
        <p className="text-xs text-fg-muted">Stops only an unaccepted request. An accepted winner returns its existing task; it is never withdrawn here.</p>
        <button type="submit" className="btn btn-ghost" disabled={view.busy || (data.reason ?? reason.trim()).length < 3}>Stop this exact saved request</button>
      </form>}
    </section>}
    <button type="button" className="btn btn-ghost" onClick={() => { setPassword(''); void controller?.lock(); }}>Lock recovery access</button>
    <Link className="block text-sm underline" to="/login">Return to sign in</Link>
  </section></main>;
}
