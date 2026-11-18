import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '@/modules/auth/AuthContext';
import type { ReceiptHistoryDTO } from '@/lib/erp-api';
import { inr } from '@/lib/inr';
import { usePaidFocusedField } from './use-paid-focused-field';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useInlineWorkspaceFocus } from '@/lib/use-inline-workspace-focus';
import { hasPendingWork } from '@/lib/start-work';
import { ownerWorkspaceGeneration } from '@/lib/owner-workspace';
import { capturePaidBillLease, currentPaidEndpoint, paidBillAmendments, paidBillCapability, requirePaidLease, type PaidBillLease } from '@/lib/paid-bill-api';
import { PaidBillJournal, paidCanonicalJson, subscribePaidBillWork, withPaidBillAdmission, type PaidBillEnvelope } from '@/lib/paid-bill-journal';
import { knownPaidStatus, validPaidSummary, validPaidBody, exactPaidRefundUrl, matchesPaidAcceptance, matchesPaidOutcome, paidStatusLabel, parsePaidAmount, type PaidBillAction, type PaidBillCommitBody, type PaidBillOutcome, type PaidBillPreview, type PaidBillPreviewBody, type PaidBillScope, type PaidBillState } from '@/lib/paid-bill-contract';

export function PaidBillSummary({ value }: { value: ReceiptHistoryDTO['paid_bill_amendment'] }) {
  if (value === null || value === undefined) return null;
  if (!validPaidSummary(value)) return <p role="status">Bill-change details could not be verified. Refresh the receipt.</p>;
  return <section className="rounded-xl border border-bg-border p-4 text-sm" aria-label="Bill change summary">
    <strong>{value.protocol === 1 ? paidStatusLabel(value.status) : 'Bill-change state needs Owner review'}</strong>
    <dl className="mt-2 grid grid-cols-2 gap-2"><dt>Original bill</dt><dd>{inr(value.original_total_minor)}</dd><dt>Refund recorded</dt><dd>{inr(value.actual_refunded_minor)}</dd><dt>Effective amount</dt><dd>{inr(value.effective_total_minor)}</dd>
      {value.proposed_total_minor !== null && <><dt>Proposed amount</dt><dd>{inr(value.proposed_total_minor)}</dd></>}
      <dt>Refund remaining</dt><dd>{value.payout_remaining_minor === null ? 'Unknown — review task' : inr(value.payout_remaining_minor)}</dd>
      {value.physical_completed_pending_accounting_minor > 0 && <><dt>Returned, accounting pending</dt><dd>{inr(value.physical_completed_pending_accounting_minor)}</dd></>}
    </dl><p className="mt-2 text-fg-muted">A requested change does not mean money has been returned. Original invoice and stock consumption stay recorded.</p>
  </section>;
}

export default function PaidBillAmendment({ receipt, onCommitted }: { receipt: ReceiptHistoryDTO; onCommitted: () => Promise<void> }) {
  const { me, terminalId, terminalReady } = useAuth();
  const allowed = Boolean(me?.audit_access && me.effective_permissions?.includes('finance.receipt.correct'));
  let endpoint = '';
  try { endpoint = currentPaidEndpoint(); } catch { /* fail closed below */ }
  const scope = me && endpoint && terminalReady && terminalId === receipt.terminal_id && (!me.branch_id || me.branch_id === receipt.branch_id)
    ? { endpoint, companyId: me.company_id, userId: me.user_id, branchId: receipt.branch_id, terminalId } : null;
  if (!allowed) return null;
  if (!scope) return <p role="status" className="text-sm">Return to this receipt’s original server, branch and terminal to change it.</p>;
  return <PaidBillWorkspace key={JSON.stringify([scope, receipt.order_id, ownerWorkspaceGeneration()])} scope={scope} receipt={receipt} onCommitted={onCommitted} />;
}

export function PaidBillWorkspace({ scope, receipt, onCommitted }: { scope: PaidBillScope; receipt: ReceiptHistoryDTO; onCommitted: () => Promise<void> }) {
  const auth = useAuth(), online = useOnlineStatus(), navigate = useNavigate();
  const live = useRef(auth); live.current = auth;
  const mounted = useRef(true), working = useRef(false), readEpoch = useRef(0);
  const journal = useMemo(() => new PaidBillJournal(scope), [scope]);
  const [saved, setSaved] = useState<PaidBillEnvelope | null>(null);
  const [storageVerified, setStorageVerified] = useState(false);
  const [state, setState] = useState<PaidBillState | null>(null);
  const [supported, setSupported] = useState(false), [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null), [notice, setNotice] = useState<string | null>(null);
  const [operation, setOperation] = useState<'decrease' | 'discard' | null>(null);
  const [received, setReceived] = useState<'' | 'received' | 'not_received' | 'unknown'>('');
  const [amount, setAmount] = useState(''), [reason, setReason] = useState('');
  const [shift, setShift] = useState(''), [mode, setMode] = useState<'' | 'cash' | 'original'>('');
  const [shiftLabels, setShiftLabels] = useState<Record<string, string>>({});
  const [options, setOptions] = useState<PaidBillPreview | null>(null);
  const [review, setReview] = useState<{ quote: PaidBillPreview; body: PaidBillCommitBody; generation: number } | null>(null);
  const [password, setPassword] = useState(''), [grant, setGrant] = useState<{ token: string; started: number; deadline: number; body: string; identity: PaidBillLease['identity'] } | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [resultAction, setResultAction] = useState<PaidBillAction | null>(null);
  const [result, setResult] = useState<Exclude<PaidBillOutcome, { outcome: 'unconfirmed' }> | null>(null);
  const focus = useInlineWorkspaceFocus(operation !== null, JSON.stringify([scope, receipt.order_id]));
  useEffect(() => {
    if (!grant) return;
    let timer: ReturnType<typeof setTimeout>;
    const expire = () => {
      const now = performance.now(), remaining = grant.deadline - now;
      if (!Number.isFinite(remaining) || now < grant.started || remaining <= 0) setGrant(current => current === grant ? null : current);
      else timer = setTimeout(expire, remaining);
    };
    expire(); return () => clearTimeout(timer);
  }, [grant]);
  function grantFor(body: PaidBillCommitBody, l: PaidBillLease): string {
    const now = performance.now();
    if (!grant || grant.identity !== l.identity || !Number.isFinite(now) || now < grant.started || now >= grant.deadline || grant.body !== paidCanonicalJson(body)) {
      setGrant(null); throw new Error('Owner approval expired or the reviewed command changed. Unlock this exact request again.');
    }
    return grant.token;
  }

  const lease = useCallback(() => capturePaidBillLease(scope, () => {
    const a = live.current;
    return mounted.current && a.me?.user_id === scope.userId && a.me.company_id === scope.companyId && a.me.audit_access === true && a.me.effective_permissions?.includes('finance.receipt.correct') === true && a.terminalReady && a.terminalId === scope.terminalId;
  }), [scope]);
  const currentWorkspace = useCallback(() => lease().current(), [lease]);
  const workspaceRef = usePaidFocusedField(currentWorkspace);
  const eligibleShifts = options?.settlement_shift_ids.join(',') ?? '';
  useEffect(() => {
    let active = true;
    setShiftLabels({});
    if (!eligibleShifts) return;
    const l = lease();
    void paidBillAmendments.settlementLabels(eligibleShifts.split(','), l).then(labels => {
      if (active && mounted.current && l.current()) setShiftLabels(labels);
    }).catch(() => { /* Metadata never changes the server's eligible choices. */ });
    return () => { active = false; };
  }, [eligibleShifts, lease]);
  useEffect(() => {
    const changed = () => {
      if (grant && lease().identity !== grant.identity) { setGrant(null); setPassword(''); }
    };
    window.addEventListener('storage', changed);
    return () => window.removeEventListener('storage', changed);
  }, [grant, lease]);
  const read = useCallback(() => { const next = journal.read(); if (mounted.current) { setSaved(next); setStorageVerified(true); } return next; }, [journal]);
  useEffect(() => {
    mounted.current = true;
    const epoch = ++readEpoch.current;
    try { read(); } catch (e) { setStorageVerified(false); setError((e as Error).message); }
    const unsubscribe = subscribePaidBillWork(() => { try { read(); } catch (e) { if (mounted.current) { setStorageVerified(false); setError((e as Error).message); } } });
    const l = lease();
    void Promise.all([paidBillAmendments.terminals(l), paidBillAmendments.state(receipt.order_id, l)]).then(([terminals, current]) => {
      if (mounted.current && l.current() && epoch === readEpoch.current) { setSupported(paidBillCapability(terminals, scope)); setState(current); }
    }).catch(e => { if (mounted.current && l.current()) setError((e as Error).message); });
    return () => { mounted.current = false; unsubscribe(); };
  }, [lease, read, receipt.order_id, scope]);

  async function capable(l: PaidBillLease) {
    if (!online) throw new Error('Reconnect before reviewing or recovering a paid-bill action.');
    const terminals = await paidBillAmendments.terminals(l);
    requirePaidLease(l);
    if (!paidBillCapability(terminals, scope)) { setSupported(false); throw new Error('This server does not support safe paid-bill change recovery. Saved work was kept.'); }
    setSupported(true);
  }
  async function run(task: (l: PaidBillLease) => Promise<void>) {
    if (working.current) return;
    const l = lease(); ++readEpoch.current; working.current = true; setBusy(true); setError(null); setNotice(null);
    try { await withPaidBillAdmission(async () => { requirePaidLease(l); await capable(l); await task(l); }, true); }
    catch (e) { if (mounted.current && l.current()) { setError((e as Error).message); setGrant(null); } }
    finally { working.current = false; if (mounted.current && l.current()) { setBusy(false); try { read(); } catch (e) { setError((e as Error).message); } } }
  }
  function invalidate() { setReview(null); setGrant(null); setNotice(null); }
  function start(kind: 'decrease' | 'discard', trigger: HTMLElement) {
    focus.openFrom(trigger); setOperation(kind); setReceived(''); setAmount(''); setReason(''); setShift(''); setMode(''); setOptions(null); invalidate(); setResult(null);
  }
  async function preview(l: PaidBillLease) {
    if (!operation || received !== 'received') throw new Error('Confirm whether the money was actually received first.');
    if (hasPendingWork()) throw new Error('Resolve saved Start work before preparing a bill change.');
    const ready = journal.ensureReady();
    const body: PaidBillPreviewBody = { protocol: 1, order_id: receipt.order_id, operation, target_total_minor: operation === 'discard' ? 0 : parsePaidAmount(amount), money_received: received, reason: reason.trim(), settlement_shift_id: shift || null, refund_mode: mode || null };
    if (!validPaidBody(body)) throw new Error('Use a reason of 3–500 printable characters and a valid exact amount and settlement selection.');
    if (state && body.target_total_minor > state.effective_total_minor) throw new Error('The new amount must not exceed the current effective bill amount.');
    const quote = await paidBillAmendments.preview(body, l); requirePaidLease(l);
    if (quote.protocol !== 1 || quote.order_id !== receipt.order_id || quote.invoice_no !== receipt.invoice_no || quote.original_total_minor !== receipt.total_minor || quote.proposed_total_minor !== body.target_total_minor || quote.settlement_shift_id !== body.settlement_shift_id || quote.refund_mode !== body.refund_mode || quote.proposed_refund_minor !== quote.effective_total_minor - body.target_total_minor) throw new Error('The reviewed bill does not match this receipt. Nothing was submitted.');
    setOptions(quote); setReview({ quote, body: { ...body, source_revision: quote.source_revision, source_fingerprint: quote.source_fingerprint }, generation: ready.generation }); setGrant(null);
  }
  async function unlock(l: PaidBillLease) {
    const secret = password; setPassword(''); setGrant(null);
    const body = journal.read().pending?.body ?? review?.body;
    if (!body) throw new Error('Review the exact command before unlocking it.');
    const started = performance.now();
    if (!Number.isFinite(started)) throw new Error('Owner approval time could not be verified.');
    const value = await paidBillAmendments.unlock(scope.branchId, secret, l);
    requirePaidLease(l);
    const deadline = started + value.expires_in_seconds * 1000;
    if (!Number.isFinite(deadline) || performance.now() >= deadline) throw new Error('Owner approval expired while unlocking. Unlock again.');
    setGrant({ token: value.token, started, deadline, identity: l.identity, body: paidCanonicalJson(body) });
  }
  async function finish(action: PaidBillAction, outcome: PaidBillOutcome, l: PaidBillLease) {
    requirePaidLease(l);
    const current = journal.read();
    if (!matchesPaidOutcome(action, outcome, current.cancellation_reason)) throw new Error('No matching durable outcome was confirmed. Keep the original command; no replacement was submitted.');
    setResult(outcome); setResultAction(action);
    try { journal.acknowledge(action, outcome); journal.clearExact(action, outcome); }
    catch { setNotice('Server outcome confirmed; local recovery acknowledgement is still pending. Keep browser data and check the same saved action.'); return; }
    setReview(null); setGrant(null);
    if (outcome.outcome === 'accepted') setState(outcome.current);
    setNotice(outcome.outcome === 'accepted' ? outcome.accepted.refund_request_id ? 'Change saved. Continue only its existing refund task; money has not been returned by this action.' : 'Discard saved. Earlier recorded refunds already cover the bill; no new payout or stock change was made.' : 'Saved request stopped. The bill and money are unchanged. A new change needs a separate review.');
    try { await onCommitted(); requirePaidLease(l); }
    catch { if (l.current()) setNotice('Server outcome confirmed. Receipt refresh is pending; retry refresh, do not submit another change.'); }
  }
  async function submit(l: PaidBillLease, retry = false) {
    const previous = journal.read();
    let action = previous.pending;
    if (!retry) {
      if (!review?.quote.commit_available || !grant || hasPendingWork()) throw new Error('Complete the exact review and Owner unlock before saving.');
      action = { version: 1, purpose: 'paid_bill_amendment', scope, key: 'paid-bill:web:' + crypto.randomUUID(), body: review.body, expected: { invoice_no: receipt.invoice_no, original_total_minor: review.quote.original_total_minor, effective_total_minor: review.quote.effective_total_minor, proposed_refund_minor: review.quote.proposed_refund_minor, task_required: review.quote.proposed_refund_minor > 0 } };
      grantFor(action.body, l); requirePaidLease(l);
      journal.reserve(action, review.generation); read();
    }
    if (!action || !grant || previous.cancellation_reason !== null) throw new Error('Recover or stop the saved request with its original body.');
    const original = action;
    requirePaidLease(l);
    const acceptance = await paidBillAmendments.commit(original.body, original.key, grantFor(original.body, l), l, () => { grantFor(original.body, l); });
    if (!matchesPaidAcceptance(original, acceptance)) throw new Error('The server reply does not match the saved bill change. Keep its evidence.');
    setNotice('Server saved the request. Confirming its exact recovery receipt…');
    const outcome = await paidBillAmendments.recoverByKey(original.key, grantFor(original.body, l), l, () => { grantFor(original.body, l); });
    if (outcome.outcome !== 'accepted' || paidCanonicalJson(outcome.accepted) !== paidCanonicalJson(acceptance)) throw new Error('Acceptance was returned; its exact recovery proof still needs checking. Do not create a second task.');
    await finish(original, outcome, l);
  }
  async function recover(l: PaidBillLease, stop = false) {
    const current = journal.read(), action = current.pending;
    if (!action || !grant) throw new Error('Unlock the original saved workspace first.');
    if (stop) {
      const why = current.cancellation_reason ?? cancelReason.trim();
      grantFor(action.body, l); journal.saveCancellationReason(action, why); read(); requirePaidLease(l);
      await finish(action, await paidBillAmendments.cancel(action.body, why, action.key, grantFor(action.body, l), l, () => { grantFor(action.body, l); }), l);
    } else await finish(action, await paidBillAmendments.recoverByKey(action.key, grantFor(action.body, l), l, () => { grantFor(action.body, l); }), l);
  }
  const pending = saved?.pending, wrongReceipt = pending && pending.body.order_id !== receipt.order_id;
  const editable = !busy && !pending && supported && storageVerified && state?.protocol === 1 && ['original', 'settled', 'withdrawn'].includes(state.status) && !receipt.receipt_correction;
  return <section ref={workspaceRef} aria-label="Owner paid bill actions" className="rounded-xl border border-bg-border p-4 space-y-3">
    <div ref={focus.fallbackRef} tabIndex={-1}><h4 className="font-semibold">Owner bill changes</h4><p className="text-xs text-fg-muted">Decrease or discard a paid bill using its existing refund process. Original invoice, payments, closed shift and stock consumption are preserved.</p></div>
    {error && <p role="alert" className="text-sm text-accent-bad">{error}</p>}{notice && <p role="status" className="text-sm">{notice}</p>}
    {grant && <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => { setGrant(null); setPassword(''); }}>Lock Owner approval</button>}
    {!supported && <p className="text-sm">Safe bill-change support has not been verified on this server. Refresh this receipt to check again.</p>}
    {pending ? <div className="space-y-3"><strong>Saved request for {pending.expected.invoice_no}</strong><p className="text-sm">Its outcome may be unknown. Keep browser data. Checking or stopping this request never withdraws an accepted refund task.</p>
      {wrongReceipt ? <p role="status">Open receipt {pending.expected.invoice_no} in this original workspace to recover it. No unlock or dispatch is available from this different receipt. <Link className="underline" to={`/operations?paid_bill_recovery=1&receipt=${encodeURIComponent(pending.body.order_id)}`}>Open the saved receipt</Link></p> : <>
        <label className="block text-sm">Owner password<input className="input mt-1" type="password" autoComplete="current-password" value={password} disabled={busy} onChange={e => setPassword(e.target.value)} /></label>
        <button type="button" className="btn btn-ghost" disabled={busy || !password || !online} onClick={() => void run(unlock)}>Unlock saved request</button>
        <div className="flex flex-wrap gap-2"><button type="button" className="btn btn-primary" disabled={busy || !grant} onClick={() => void run(l => recover(l))}>Check saved request outcome</button>
          {!saved.cancellation_reason && !saved.accepted && !saved.cancelled && <button type="button" className="btn btn-ghost" disabled={busy || !grant} onClick={() => void run(l => submit(l, true))}>Retry exact saved change</button>}</div>
        {!saved.accepted && !saved.cancelled && <div className="border-t border-bg-border pt-3"><label className="block text-sm">Reason for stopping this saved request<textarea className="input mt-1" maxLength={500} disabled={busy || saved.cancellation_reason !== null} value={saved.cancellation_reason ?? cancelReason} onChange={e => setCancelReason(e.target.value)} /></label><p className="text-xs text-fg-muted">Stops only an unaccepted request. If the server already accepted it, its existing refund task is returned instead. This does not discard the bill.</p><button type="button" className="btn btn-ghost mt-2" disabled={busy || !grant || (saved.cancellation_reason ?? cancelReason.trim()).length < 3} onClick={() => void run(l => recover(l, true))}>Stop saved request safely</button></div>}
      </>}
    </div> : <>
      <div className="flex flex-wrap gap-2"><button className="btn btn-primary" disabled={!editable} onClick={e => start('decrease', e.currentTarget)}>Change amount</button><button className="btn btn-ghost" disabled={!editable} onClick={e => start('discard', e.currentTarget)}>Discard bill</button></div>
      {operation && <div className="space-y-3"><div className="flex items-center justify-between gap-2"><h5 ref={focus.headingRef} tabIndex={-1} className="font-semibold">{operation === 'discard' ? 'Review paid bill discard' : 'Review lower bill amount'}</h5><button type="button" className="btn btn-ghost" disabled={busy} onClick={() => { focus.closeToInvoker(); setOperation(null); setPassword(''); setGrant(null); }}>Close change review</button></div>
        <label className="block text-sm">Was money actually received?<select className="input mt-1" value={received} disabled={busy} onChange={e => { setReceived(e.target.value as typeof received); invalidate(); }}><option value="">Choose after checking</option><option value="received">Yes, money was received</option><option value="not_received">No, money was not received</option><option value="unknown">I cannot confirm yet</option></select></label>
        {received === 'unknown' && <p role="status">Confirm the actual payment before changing this bill. No refund or correction can be prepared while it is unknown.</p>}
        {received === 'not_received' && <p role="status">Use the separate “Correct mistaken paid bill” review below if its full no-funds and unsupplied conditions apply. No refund is created here; every attestation must be reviewed separately.</p>}
        {received === 'received' && <>
          {operation === 'decrease' && <label className="block text-sm">New bill amount (₹)<input className="input mt-1" inputMode="decimal" value={amount} disabled={busy} onChange={e => { setAmount(e.target.value); invalidate(); }} /></label>}
          <label className="block text-sm">Reason for changing this bill<textarea className="input mt-1" maxLength={500} value={reason} disabled={busy} onChange={e => { setReason(e.target.value); invalidate(); }} /></label>
          {options && options.proposed_refund_minor > 0 && <div className="grid gap-2 sm:grid-cols-2"><label className="text-sm">Settlement shift<select className="input mt-1" value={shift} disabled={busy} onChange={e => { setShift(e.target.value); invalidate(); }}><option value="">Choose original settlement context</option>{options.settlement_shift_ids.map(id => <option key={id} value={id}>{shiftLabels[id] ?? `Shift ${id.slice(0, 8)} — details unavailable`}</option>)}</select></label><label className="text-sm">Refund method<select className="input mt-1" value={mode} disabled={busy} onChange={e => { setMode(e.target.value as typeof mode); invalidate(); }}><option value="">Choose method</option>{options.refund_modes.map(m => <option key={m} value={m}>{m === 'cash' ? 'Cash handover' : 'Original payment method'}</option>)}</select></label></div>}
          <button type="button" className="btn btn-ghost" disabled={busy || !editable || reason.trim().length < 3} onClick={() => void run(preview)}>Review exact change</button>
          {review && <div className="rounded-xl border border-bg-border p-3 text-sm"><p>Original {inr(review.quote.original_total_minor)} · Effective {inr(review.quote.effective_total_minor)}</p><p>Proposed {inr(review.quote.proposed_total_minor)} · Refund to arrange {inr(review.quote.proposed_refund_minor)}</p>{review.quote.blockers.map(b => <p role="status" key={b}>{b}</p>)}<p>No money is returned or stock restored when this request is saved.</p></div>}
          {review?.quote.commit_available && <><label className="block text-sm">Owner password<input className="input mt-1" type="password" autoComplete="current-password" disabled={busy} value={password} onChange={e => setPassword(e.target.value)} /></label><button type="button" className="btn btn-ghost" disabled={busy || !password} onClick={() => void run(unlock)}>Unlock reviewed change</button><button type="button" className="btn btn-primary" disabled={busy || !grant} onClick={() => void run(l => submit(l))}>Save reviewed change</button></>}
        </>}
      </div>}
    </>}
    {result?.outcome === 'accepted' && <div className="text-sm"><strong>{paidStatusLabel(result.current.status)}</strong><p>Refund remaining: {result.current.payout_remaining_minor === null ? 'Unknown — review task' : inr(result.current.payout_remaining_minor)}</p>{result.accepted.refund_request_id && <button type="button" className="btn btn-primary mt-2" onClick={() => navigate(exactPaidRefundUrl(result.accepted.refund_request_id!, result.accepted.order_id, resultAction?.expected.proposed_refund_minor))}>Open existing refund task</button>}</div>}
    {!result && state?.protocol === 1 && knownPaidStatus(state.status) && state.refund_request_id && <button type="button" className="btn btn-ghost" onClick={() => navigate(exactPaidRefundUrl(state.refund_request_id!, state.order_id))}>Open existing bill-change refund task</button>}
    {result?.outcome === 'cancelled' && <p className="text-sm">Saved request stopped. No bill value, payout or stock changed.</p>}
  </section>;
}
