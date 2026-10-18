import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertCircle, Check, Lock, RefreshCw, ShieldCheck } from 'lucide-react';
import { useAuth } from '@/modules/auth/AuthContext';
import {
  tenderCorrections,
  type DailyTenderPreviewDTO,
  type PaymentTenderPreviewDTO,
  type TenderReceiptDTO,
} from '@/lib/erp-api';
import type { ApiError } from '@/lib/api';
import { inr } from '@/lib/inr';
import { parseRupeesToMinor } from '@/lib/money-input';
import { ownerScope, ownerWorkspaceGeneration } from '@/lib/owner-workspace';
import { availableLocalStorage } from '@/lib/stable-mutation-intent';
import {
  matchesTenderReceipt,
  TenderJournal,
  withTenderRecoveryLock,
  type TenderAction,
} from './tender-journal';
import { businessLabel, shopTime } from '@/modules/audit/audit-presentation';

export default function TenderCorrections({
  paymentId,
  onCommitted,
}: {
  paymentId?: string;
  onCommitted?: () => void;
}) {
  const { me } = useAuth();
  if (
    !me?.audit_access ||
    !me.branch_id ||
    !me.effective_permissions?.includes('finance.tender.correct')
  )
    return null;
  return (
    <TenderWorkspace
      key={ownerScope(me) + ':' + (paymentId ?? 'daily')}
      paymentId={paymentId}
      onCommitted={onCommitted}
    />
  );
}
function TenderWorkspace({
  paymentId,
  onCommitted,
}: {
  paymentId?: string;
  onCommitted?: () => void;
}) {
  const { me } = useAuth();
  const [journal] = useState(
    () => new TenderJournal(me!, availableLocalStorage()),
  );
  const [pending, setPending] = useState<TenderAction | null>(() => {
    try {
      return journal.read();
    } catch {
      return null;
    }
  });
  const [error, setError] = useState<string | null>(() => {
    try {
      journal.read();
      return null;
    } catch (cause) {
      return (cause as Error).message;
    }
  });
  const [tokens, setTokens] = useState<{
    action: string;
    history: string;
    expires: number;
  } | null>(null);
  const [password, setPassword] = useState('');
  const [day, setDay] = useState(() =>
    pending?.operation === 'daily' ? pending.body.business_date : '',
  );
  const [cash, setCash] = useState('');
  const [upi, setUpi] = useState('');
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [preview, setPreview] = useState<
    PaymentTenderPreviewDTO | DailyTenderPreviewDTO | null
  >(null);
  const [receipt, setReceipt] = useState<TenderReceiptDTO | null>(null);
  const [history, setHistory] = useState<TenderReceiptDTO[]>([]);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [rejected, setRejected] = useState(false);
  const inFlight = useRef(false);
  const mounted = useRef(true);
  const operation = pending?.operation ?? (paymentId ? 'payment' : 'daily');
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    if (!tokens) return;
    const timer = window.setTimeout(
      () => setTokens(null),
      Math.max(0, tokens.expires - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [tokens]);
  async function unlock(event: React.FormEvent) {
    event.preventDefault();
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    const generation = ownerWorkspaceGeneration();
    const secret = password;
    setPassword('');
    try {
      const action = await tenderCorrections.unlock(
        me!.branch_id!,
        operation,
        secret,
      );
      if (!mounted.current || generation !== ownerWorkspaceGeneration()) return;
      const next = {
        action: action.token,
        history: action.history_token,
        expires: Date.now() + action.expires_in * 1000,
      };
      setTokens(next);
      const rows = await tenderCorrections.history(
        {
          branch_id: me!.branch_id!,
          limit: 50,
          ...(pending?.operation === 'payment'
            ? { payment_id: pending.body.payment_id }
            : paymentId
              ? { payment_id: paymentId }
              : {}),
        },
        next.history,
      );
      if (mounted.current && generation === ownerWorkspaceGeneration())
        setHistory(rows);
    } catch (cause) {
      if (mounted.current) setError((cause as Error).message);
    } finally {
      inFlight.current = false;
      if (mounted.current) setBusy(false);
    }
  }
  async function prepare() {
    if (!tokens || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    setReceipt(null);
    setConfirmed(false);
    const generation = ownerWorkspaceGeneration();
    try {
      if (journal.read())
        throw new Error(
          'Recover the saved request before preparing a different correction.',
        );
      const result = paymentId
        ? await tenderCorrections.preparePayment(paymentId, tokens.action)
        : await tenderCorrections.prepareDaily(
            { branch_id: me!.branch_id!, business_date: day },
            tokens.action,
          );
      if (mounted.current && generation === ownerWorkspaceGeneration())
        setPreview(result);
    } catch (cause) {
      if (mounted.current) setError((cause as Error).message);
    } finally {
      inFlight.current = false;
      if (mounted.current) setBusy(false);
    }
  }
  async function commit() {
    if (!tokens || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    setRejected(false);
    const generation = ownerWorkspaceGeneration();
    try {
      await withTenderRecoveryLock(journal.scope, async () => {
        if (!mounted.current || generation !== ownerWorkspaceGeneration())
          return;
        const stored = journal.read();
        let action = stored ?? pending;
        // Another tab may have recovered and cleared this exact command. The
        // retained in-memory recovery identity still wins over a fresh UUID.
        if (!stored && pending) journal.save(pending);
        if (
          action &&
          (!pending || JSON.stringify(action) !== JSON.stringify(pending))
        ) {
          setPending(action);
          setPreview(null);
          setConfirmed(false);
          throw new Error(
            'Another correction is awaiting its final receipt. Review that saved identity and choose Recover saved result explicitly.',
          );
        }
        if (!action) {
          if (!preview || !confirmed)
            throw new Error('Review and confirm the source preview first.');
          if (reason.trim().length < 3)
            throw new Error(
              'Enter a clear reason of at least three characters.',
            );
          const common = {
            source_fingerprint: preview.source_fingerprint,
            reason: reason.trim(),
            evidence_reference: reference.trim() || null,
          };
          const base = {
            version: 1 as const,
            scope: journal.scope,
            key: 'tender:web:' + crypto.randomUUID(),
          };
          if ('payment_id' in preview)
            action = {
              ...base,
              operation: 'payment',
              body: { ...common, payment_id: preview.payment_id },
            };
          else {
            const actualCash = parseRupeesToMinor(cash),
              actualUpi = parseRupeesToMinor(upi);
            if (actualCash === null || actualUpi === null)
              throw new Error(
                'Enter nonnegative Cash and UPI actuals with at most two decimals.',
              );
            action = {
              ...base,
              operation: 'daily',
              body: {
                ...common,
                branch_id: preview.branch_id,
                business_date: preview.business_date,
                actual_cash_minor: actualCash,
                actual_upi_minor: actualUpi,
              },
            };
          }
          journal.save(action);
          setPending(action);
        }
        const result =
          action.operation === 'payment'
            ? await tenderCorrections.commitPayment(
                action.body,
                action.key,
                tokens.action,
              )
            : await tenderCorrections.commitDaily(
                action.body,
                action.key,
                tokens.action,
              );
        if (!mounted.current || generation !== ownerWorkspaceGeneration())
          return;
        if (!matchesTenderReceipt(action, result, me!))
          throw new Error(
            'The returned receipt does not match the saved request. Evidence was kept; verify history before retrying.',
          );
        journal.clear(action);
        setPending(null);
        setReceipt(result);
        setPreview(null);
        setConfirmed(false);
        setHistory((rows) => [
          result,
          ...rows.filter((row) => row.id !== result.id),
        ]);
        onCommitted?.();
      });
    } catch (cause) {
      if (!mounted.current || generation !== ownerWorkspaceGeneration()) return;
      const failure = cause as ApiError;
      setError(failure.message);
      setRejected(
        [409, 422].includes(failure.status ?? 0) &&
          ['conflict', 'business_rule', 'validation_error'].includes(
            failure.code ?? '',
          ) &&
          failure.details?.retryable !== true,
      );
      if (failure.status === 401 || failure.status === 403) setTokens(null);
    } finally {
      inFlight.current = false;
      if (mounted.current) setBusy(false);
    }
  }
  const actualCash = parseRupeesToMinor(cash),
    actualUpi = parseRupeesToMinor(upi);
  const daily = preview && 'recorded_cash_minor' in preview ? preview : null;
  const delta =
    daily && actualCash !== null && actualUpi !== null
      ? actualCash + actualUpi - daily.recorded_total_minor
      : null;
  const transfer =
    daily && actualCash !== null
      ? actualCash - daily.recorded_cash_minor
      : null;
  const supported = daily && delta === 0 && transfer !== null && transfer < 0;
  return (
    <section
      className="rounded-xl border border-bg-border bg-bg-surface p-4 space-y-4"
      aria-label="Protected tender correction"
    >
      <header>
        <h3 className="flex items-center gap-2 font-semibold">
          <ShieldCheck size={17} />
          {paymentId
            ? 'Correct recorded payment method'
            : 'Review daily Cash / UPI actuals'}
        </h3>
        <p className="mt-1 text-sm text-fg-muted">
          Owner-attested evidence. Original bills and recorded drawer closes
          stay unchanged.
        </p>
      </header>
      {error && (
        <p role="alert" className="text-sm text-accent-bad">
          <AlertCircle size={14} className="mr-1 inline" />
          {error}
        </p>
      )}
      {!tokens ? (
        <form onSubmit={unlock} className="flex flex-wrap items-end gap-3">
          <label className="min-w-0 flex-1 text-sm">
            Owner password
            <input
              className="input mt-1 w-full"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <button className="btn btn-primary" disabled={busy || !password}>
            <Lock size={14} />
            {busy ? 'Confirming…' : 'Confirm owner access'}
          </button>
        </form>
      ) : (
        <>
          {pending ? (
            <div className="rounded-xl border border-bg-border bg-bg-raised p-4 space-y-3">
              <h4 className="font-semibold">
                Saved request needs its final receipt
              </h4>
              <p className="text-sm">
                No new correction will be created. Recover the exact saved
                request with the same action key and source.
              </p>
              <dl className="text-sm space-y-1">
                <p>
                  {pending.operation === 'payment'
                    ? `Payment ${pending.body.payment_id}`
                    : `Business date ${pending.body.business_date} · Cash ${inr(pending.body.actual_cash_minor)} / UPI ${inr(pending.body.actual_upi_minor)}`}
                </p>
                <p>{pending.body.reason}</p>
                <p className="break-all font-mono text-xs text-fg-muted">
                  {pending.key}
                </p>
              </dl>
              <button
                className="btn btn-primary"
                onClick={() => void commit()}
                disabled={busy}
              >
                <RefreshCw size={14} /> Recover saved result
              </button>
              {rejected && (
                <div className="space-y-2 text-sm">
                  <p>
                    The server rejected this exact request without a matching
                    committed receipt. Review its history before starting a
                    fresh source preview.
                  </p>
                  <button
                    className="btn btn-ghost"
                    onClick={() => {
                      journal.clear(pending);
                      setPending(null);
                      setTokens(null);
                      setPreview(null);
                      setRejected(false);
                      setConfirmed(false);
                      setError(null);
                    }}
                  >
                    Reviewed rejection — prepare fresh source
                  </button>
                </div>
              )}
            </div>
          ) : (
            <>
              {!paymentId && (
                <label className="block max-w-xs text-sm">
                  Closed shop business date
                  <input
                    type="date"
                    className="input mt-1 w-full"
                    value={day}
                    onChange={(event) => {
                      setDay(event.target.value);
                      setPreview(null);
                      setConfirmed(false);
                    }}
                  />
                </label>
              )}
              <button
                className="btn btn-ghost"
                disabled={busy || (!paymentId && !day)}
                onClick={() => void prepare()}
              >
                <RefreshCw size={14} />
                {busy ? 'Checking source…' : 'Prepare source preview'}
              </button>
              {preview && (
                <div className="rounded-xl border border-bg-border p-4 space-y-4">
                  <p className="text-sm font-semibold">
                    {preview.business_date} · {preview.business_timezone}
                  </p>
                  {'payment_id' in preview ? (
                    <>
                      <p className="text-sm">
                        {preview.invoice_no} · Complete Cash leg{' '}
                        {inr(preview.amount_minor)} → UPI. Bill total change{' '}
                        {inr(preview.total_change_minor)}.
                      </p>
                      <div className="grid grid-cols-2 gap-3 text-sm">
                        <Amount
                          label="Cash before → after"
                          value={`${inr(preview.before_cash_minor)} → ${inr(preview.after_cash_minor)}`}
                        />
                        <Amount
                          label="UPI before → after"
                          value={`${inr(preview.before_upi_minor)} → ${inr(preview.after_upi_minor)}`}
                        />
                      </div>
                    </>
                  ) : (
                    daily && (
                      <>
                        <div className="grid gap-3 sm:grid-cols-2">
                          <Amount
                            label="Recorded Cash"
                            value={inr(daily.recorded_cash_minor)}
                          />
                          <Amount
                            label="Recorded UPI"
                            value={inr(daily.recorded_upi_minor)}
                          />
                          <label className="text-sm">
                            Confirmed Cash actual (₹)
                            <input
                              className="input mt-1 w-full"
                              inputMode="decimal"
                              value={cash}
                              onChange={(event) => {
                                setCash(event.target.value);
                                setConfirmed(false);
                              }}
                            />
                          </label>
                          <label className="text-sm">
                            Confirmed UPI actual (₹)
                            <input
                              className="input mt-1 w-full"
                              inputMode="decimal"
                              value={upi}
                              onChange={(event) => {
                                setUpi(event.target.value);
                                setConfirmed(false);
                              }}
                            />
                          </label>
                        </div>
                        <p className="text-xs text-fg-muted">
                          Cash + UPI scope {inr(daily.recorded_total_minor)}.
                          Other rails are unchanged:{' '}
                          {Object.entries(daily.unchanged_other_rails)
                            .map(
                              ([rail, amount]) =>
                                `${businessLabel(rail)} ${inr(amount)}`,
                            )
                            .join(' · ') || 'none recorded'}
                          . Overall recorded{' '}
                          {inr(daily.recorded_all_rails_total_minor)}. This is
                          not a physical drawer count.
                        </p>
                        {delta !== null && (
                          <p className="text-sm font-medium">
                            {delta !== 0
                              ? `Unresolved total difference ${inr(delta)} — no financial posting.`
                              : supported
                                ? `Unallocated transfer: Cash ${inr(transfer!)} / UPI +${inr(-transfer!)}. Revenue unchanged.`
                                : 'No supported Cash-to-UPI transfer. Nothing will be posted.'}
                          </p>
                        )}
                      </>
                    )
                  )}
                  <label className="block text-sm">
                    Reason
                    <input
                      className="input mt-1 w-full"
                      maxLength={500}
                      value={reason}
                      onChange={(event) => {
                        setReason(event.target.value);
                        setConfirmed(false);
                      }}
                    />
                  </label>
                  <label className="block text-sm">
                    Evidence reference (optional)
                    <input
                      className="input mt-1 w-full"
                      maxLength={200}
                      value={reference}
                      onChange={(event) => {
                        setReference(event.target.value);
                        setConfirmed(false);
                      }}
                    />
                  </label>
                  <label className="flex items-start gap-2 text-sm">
                    <input
                      className="mt-1"
                      type="checkbox"
                      checked={confirmed}
                      onChange={(event) => setConfirmed(event.target.checked)}
                    />
                    <span>
                      I reviewed these source amounts. This is owner
                      attestation, not bank verification. Unallocated actuals do
                      not identify a bill or staff member.
                    </span>
                  </label>
                  <button
                    className="btn btn-primary"
                    disabled={
                      busy ||
                      !confirmed ||
                      reason.trim().length < 3 ||
                      Boolean(
                        daily &&
                          (delta === null || (delta === 0 && !supported)),
                      )
                    }
                    onClick={() => void commit()}
                  >
                    <Check size={14} />
                    {daily && delta !== 0
                      ? 'Record unresolved actuals — no posting'
                      : 'Confirm reviewed correction'}
                  </button>
                </div>
              )}
            </>
          )}
          {receipt && (
            <div
              className="rounded-xl border border-bg-border p-4"
              role="status"
            >
              <h4 className="font-semibold">
                {receipt.financial_effect
                  ? 'Correction recorded'
                  : 'Unresolved actuals recorded — no money changed'}
              </h4>
              <p className="mt-1 text-sm">
                {receipt.receipt_reference} · {receipt.business_date}
              </p>
              <p className="mt-1 text-sm text-fg-muted">{receipt.reason}</p>
              <TenderReceiptFacts receipt={receipt} />
              <Link
                className="mt-3 inline-flex text-sm underline"
                to={`/audit?reference=${encodeURIComponent(receipt.invoice_no ?? receipt.receipt_reference)}`}
              >
                Open correction Audit
              </Link>
            </div>
          )}
          <details>
            <summary className="cursor-pointer text-sm font-semibold">
              Retained correction receipts · {history.length} loaded
            </summary>
            <p className="my-2 text-xs text-fg-muted">
              Latest protected history, up to 50 entries. Business date is
              separate from recording time.
            </p>
            <div className="space-y-3">
              {history.map((row) => (
                <article
                  key={row.id}
                  className="rounded-lg border border-bg-border p-3 text-sm"
                >
                  <b>{row.receipt_reference}</b>
                  <p>
                    {row.business_date} ·{' '}
                    {row.financial_effect
                      ? 'Applied · revenue unchanged'
                      : 'Unresolved · no financial effect'}
                  </p>
                  <p>{row.invoice_no ?? 'Unallocated owner attestation'}</p>
                  <p className="mt-1 text-fg-muted">{row.reason}</p>
                  <p className="mt-1 text-xs text-fg-muted">
                    Recorded {shopTime(row.recorded_at, row.business_timezone)}
                  </p>
                  <TenderReceiptFacts receipt={row} />
                </article>
              ))}
            </div>
          </details>
        </>
      )}
    </section>
  );
}
function Amount({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs text-fg-muted">{label}</p>
      <p className="mt-1 font-semibold tabular-nums">{value}</p>
    </div>
  );
}

export function TenderReceiptFacts({ receipt }: { receipt: TenderReceiptDTO }) {
  const signed = (amount: number) => `${amount > 0 ? '+' : ''}${inr(amount)}`;
  if (receipt.operation === 'payment_method_corrected')
    return (
      <div className="mt-3 space-y-1 text-sm">
        <p className="tabular-nums">
          Complete payment leg{' '}
          {receipt.amount_minor == null
            ? 'not retained'
            : inr(receipt.amount_minor)}{' '}
          · {receipt.original_method?.toUpperCase() ?? 'Not retained'} →{' '}
          {receipt.effective_method?.toUpperCase() ?? 'Not retained'}
        </p>
        <p className="text-fg-muted">
          Identified bill correction · bill total unchanged.
        </p>
      </div>
    );
  return (
    <div className="mt-3 space-y-2 text-sm">
      <dl className="grid gap-2 sm:grid-cols-2">
        <Amount
          label="Recorded Cash → owner-confirmed actual"
          value={`${receipt.before_cash_minor == null ? 'Not retained' : inr(receipt.before_cash_minor)} → ${receipt.actual_cash_minor == null ? 'Not retained' : inr(receipt.actual_cash_minor)}`}
        />
        <Amount
          label="Recorded UPI → owner-confirmed actual"
          value={`${receipt.before_upi_minor == null ? 'Not retained' : inr(receipt.before_upi_minor)} → ${receipt.actual_upi_minor == null ? 'Not retained' : inr(receipt.actual_upi_minor)}`}
        />
      </dl>
      <p className="tabular-nums font-medium">
        Total difference {signed(receipt.total_difference_minor)}
      </p>
      {receipt.financial_effect &&
      receipt.cash_delta_minor !== null &&
      receipt.upi_delta_minor !== null ? (
        <p className="tabular-nums">
          Applied unallocated classification: Cash{' '}
          {signed(receipt.cash_delta_minor)} · UPI{' '}
          {signed(receipt.upi_delta_minor)} · revenue unchanged.
        </p>
      ) : (
        <p className="text-fg-muted">
          No financial posting. Actuals remain unresolved; no classification
          deltas were applied.
        </p>
      )}
      <p className="text-xs text-fg-muted">
        Cash / UPI attestation only; other rails unchanged. Not allocated to a
        bill, staff member or physical drawer count.
      </p>
    </div>
  );
}
