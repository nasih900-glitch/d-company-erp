import { useCallback, useEffect, useState } from 'react';
import { AlertCircle, Ban, HandCoins, Loader2, Plus, RefreshCw } from 'lucide-react';

import Modal from '@/components/ui/Modal';
import {
  accounting,
  finance,
  type BranchReferenceDTO,
  type TipPayoutDTO,
  type TipPayoutMethod,
} from '@/lib/erp-api';
import { inr, inrShort } from '@/lib/inr';
import { manualCollectionMethodLabel, MANUAL_COLLECTION_METHODS, rupeesToMinor } from '@/lib/manual-collections';
import { useAuth } from '@/modules/auth/AuthContext';
import { useRealtimeRefresh } from '@/hooks/useRealtimeRefresh';

// Ledger account code for Tips Payable — see backend/app/services/accounting/accounts.py.
const TIPS_PAYABLE_ACCOUNT_CODE = '2400';

// ============================================================================
// TIP PAYOUTS — the only write path that pays TIPS_PAYABLE back out to staff
// ============================================================================
export default function TipPayoutsTab() {
  const { me } = useAuth();
  const [rows, setRows] = useState<TipPayoutDTO[]>([]);
  const [branches, setBranches] = useState<BranchReferenceDTO[]>([]);
  const [tipsPayableBalance, setTipsPayableBalance] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [voiding, setVoiding] = useState<TipPayoutDTO | null>(null);

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    setErr(null);
    try {
      const [payouts, branchRows, trialBalance] = await Promise.all([
        finance.listTipPayouts({ include_voided: true, limit: 500 }),
        finance.listBranches(),
        accounting.trialBalance(),
      ]);
      setRows(payouts);
      setBranches(branchRows);
      const tipsLine = trialBalance.lines.find((line) => line.account_code === TIPS_PAYABLE_ACCOUNT_CODE);
      setTipsPayableBalance(tipsLine?.balance_minor ?? 0);
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      if (!silent) setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);
  useRealtimeRefresh({ resources: ['finance'], refresh: () => load(true) });

  if (loading) {
    return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading tip payouts…</div>;
  }

  const branchNames = new Map(branches.map((branch) => [branch.id, branch.name]));
  const activePayouts = rows.filter((row) => !row.is_voided);
  const lifetimePaidOut = activePayouts.reduce((sum, row) => sum + row.amount_minor, 0);

  return (
    <div>
      <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm">
        <div className="flex items-start gap-2">
          <AlertCircle size={16} className="mt-0.5 shrink-0 text-accent-gold"/>
          <div>
            <b>Records money actually handed to staff, clearing it out of Tips Payable.</b>{' '}
            This is one lump-sum payout, not a per-staff breakdown — use the note to record
            how it was split. Entries cannot be edited or deleted; a mistake must be voided
            with a reason.
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-4">
        <div className="card border-accent-good/40">
          <div className="text-xs text-fg-muted uppercase tracking-wider">Owed to staff right now</div>
          <div className="text-2xl font-bold mt-1 font-mono">
            {tipsPayableBalance === null ? '—' : inr(tipsPayableBalance)}
          </div>
          <div className="text-xs text-fg-muted mt-1">
            Tips Payable balance · collected from tipped orders, not yet paid out
          </div>
        </div>
        <TipStat label="Paid out to date" value={inrShort(lifetimePaidOut)} sub={inr(lifetimePaidOut)}/>
      </div>

      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          Immutable payout register · newest first
        </p>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={() => void load()} aria-label="Refresh tip payouts">
            <RefreshCw size={14}/>
          </button>
          <button className="btn btn-primary" onClick={() => setAddOpen(true)} disabled={!branches.length}>
            <Plus size={14}/> Pay out tips
          </button>
        </div>
      </div>

      {!branches.length && (
        <div className="card border-accent-gold/40 bg-accent-gold/10 text-accent-gold text-sm mb-3">
          No shop is available for this payout. Ask the protected owner to check your shop access.
        </div>
      )}
      {err && <ErrorRow text={err}/>}
      {rows.length === 500 && (
        <div className="card border-accent-gold/40 bg-accent-gold/10 text-accent-gold text-sm mb-3">
          Showing the newest 500 records.
        </div>
      )}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No tip payouts recorded yet. Tips collected on orders sit in Tips Payable until paid out here.
        </div>
      ) : (
        <div className="card !p-0 overflow-hidden">
          <table className="hidden w-full text-sm md:table">
            <thead className="bg-bg-raised">
              <tr>
                <th className="text-left p-3">Paid</th>
                <th className="text-left p-3">Branch / method</th>
                <th className="text-left p-3">Note</th>
                <th className="text-left p-3">Recorded by</th>
                <th className="text-right p-3">Amount</th>
                <th className="text-right p-3 pr-4">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id} className={`border-b border-bg-border/60 last:border-0 ${row.is_voided ? 'opacity-60' : ''}`}>
                  <td className="p-3 font-mono text-xs">
                    {new Date(row.paid_at).toLocaleString('en-IN')}
                  </td>
                  <td className="p-3">
                    <div>{branchNames.get(row.branch_id) ?? 'Unknown branch'}</div>
                    <span className="chip mt-1 text-[10px]">{manualCollectionMethodLabel(row.method)}</span>
                  </td>
                  <td className="p-3 max-w-xs">
                    <div className={row.is_voided ? 'line-through' : ''}>{row.note}</div>
                    {row.void_reason && (
                      <div className="mt-1 text-xs text-accent-bad break-words">Void reason: {row.void_reason}</div>
                    )}
                  </td>
                  <td className="p-3 text-xs text-fg-muted">
                    <div>{row.created_by_name ?? `User ${row.created_by.slice(0, 8)}`}</div>
                    <div>{new Date(row.created_at).toLocaleString('en-IN')}</div>
                    {row.is_voided && row.voided_at && (
                      <div className="mt-1">Voided by {row.voided_by_name ?? (row.voided_by ? `User ${row.voided_by.slice(0, 8)}` : 'unknown')}
                        {' · '}{new Date(row.voided_at).toLocaleString('en-IN')}</div>
                    )}
                  </td>
                  <td className={`p-3 text-right font-mono font-semibold ${row.is_voided ? 'line-through' : ''}`}>
                    {inr(row.amount_minor)}
                  </td>
                  <td className="p-3 text-right pr-4">
                    {row.is_voided ? (
                      <span className="chip border-accent-bad/40 text-accent-bad text-[10px]">Voided</span>
                    ) : (
                      <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs hover:!text-accent-bad"
                        onClick={() => setVoiding(row)} aria-label="Void tip payout">
                        <Ban size={13}/> Void
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="mobile-card-list md:hidden">
            {rows.map((row) => (
              <div key={row.id} className={`mobile-record-card ${row.is_voided ? 'opacity-60' : ''}`}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className={`font-semibold break-words ${row.is_voided ? 'line-through' : ''}`}>
                      {row.note}
                    </div>
                    <div className="mt-1 text-xs text-fg-muted">
                      {new Date(row.paid_at).toLocaleString('en-IN')}
                      {' · '}{branchNames.get(row.branch_id) ?? 'Unknown branch'}
                    </div>
                    <span className="chip mt-2 text-[10px]">{manualCollectionMethodLabel(row.method)}</span>
                  </div>
                  <div className="shrink-0 text-right">
                    <div className={`font-mono font-semibold ${row.is_voided ? 'line-through' : ''}`}>
                      {inr(row.amount_minor)}
                    </div>
                    <div className="mt-1 text-[10px] text-fg-muted">
                      {row.created_by_name ?? `User ${row.created_by.slice(0, 8)}`}
                    </div>
                  </div>
                </div>
                {row.void_reason && <div className="mt-2 text-xs text-accent-bad">Void reason: {row.void_reason}</div>}
                <div className="mt-3 flex items-center justify-end border-t border-bg-border/60 pt-3">
                  {row.is_voided ? (
                    <span className="chip border-accent-bad/40 text-accent-bad text-[10px]">Voided</span>
                  ) : (
                    <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs hover:!text-accent-bad"
                      onClick={() => setVoiding(row)}>
                      <Ban size={13}/> Void
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {addOpen && (
        <TipPayoutForm
          branches={branches}
          defaultBranchId={me?.branch_id ?? branches[0]?.id ?? ''}
          tipsPayableBalance={tipsPayableBalance}
          onClose={() => setAddOpen(false)}
          onSuccess={() => { setAddOpen(false); void load(); }}
        />
      )}
      {voiding && (
        <VoidTipPayoutForm
          row={voiding}
          onClose={() => setVoiding(null)}
          onSuccess={() => { setVoiding(null); void load(); }}
        />
      )}
    </div>
  );
}

function newTipPayoutKey(): string {
  const randomPart = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `tip-payout:${randomPart}`;
}

function dateTimeLocalNow(): string {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

function TipPayoutForm({
  branches,
  defaultBranchId,
  tipsPayableBalance,
  onClose,
  onSuccess,
}: {
  branches: BranchReferenceDTO[];
  defaultBranchId: string;
  tipsPayableBalance: number | null;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [form, setForm] = useState<{
    branch_id: string;
    method: TipPayoutMethod;
    amount_rupees: string;
    paid_at: string;
    note: string;
  }>({
    branch_id: branches.some((branch) => branch.id === defaultBranchId)
      ? defaultBranchId
      : (branches[0]?.id ?? ''),
    method: 'cash',
    amount_rupees: '',
    paid_at: dateTimeLocalNow(),
    note: '',
  });
  const [idempotencyKey] = useState(newTipPayoutKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const amountMinorPreview = rupeesToMinor(form.amount_rupees);
  const exceedsBalance = tipsPayableBalance !== null
    && amountMinorPreview !== null
    && amountMinorPreview > tipsPayableBalance;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const amountMinor = rupeesToMinor(form.amount_rupees);
    if (amountMinor === null) {
      setErr('Enter an amount greater than ₹0 with no more than two decimal places.');
      return;
    }
    if (form.note.trim().length < 3) {
      setErr('Enter a note explaining how this was split among staff (at least 3 characters).');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await finance.createTipPayout({
        branch_id: form.branch_id,
        amount_minor: amountMinor,
        method: form.method,
        paid_at: new Date(form.paid_at).toISOString(),
        note: form.note.trim(),
      }, idempotencyKey);
      onSuccess();
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={busy ? () => undefined : onClose} title="Pay out tips">
      <form onSubmit={submit} className="space-y-3">
        <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-xs">
          This debits Tips Payable and credits the account you actually paid staff from.
          {tipsPayableBalance !== null && (
            <> Currently owed: <b>{inr(tipsPayableBalance)}</b>.</>
          )}
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="Branch">
            <select className="input" required value={form.branch_id}
              onChange={(event) => setForm((current) => ({ ...current, branch_id: event.target.value }))}>
              {branches.map((branch) => <option key={branch.id} value={branch.id}>{branch.name}</option>)}
            </select>
          </Field>
          <Field label="Paid via">
            <select className="input" value={form.method}
              onChange={(event) => setForm((current) => ({ ...current, method: event.target.value as TipPayoutMethod }))}>
              {MANUAL_COLLECTION_METHODS.map((method) => (
                <option key={method.value} value={method.value}>{method.label}</option>
              ))}
            </select>
          </Field>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="Amount (₹)">
            <input type="number" required min="0.01" step="0.01" inputMode="decimal" autoFocus
              className="input font-mono text-right text-xl" value={form.amount_rupees}
              onChange={(event) => setForm((current) => ({ ...current, amount_rupees: event.target.value }))}/>
          </Field>
          <Field label="Date / time paid">
            <input type="datetime-local" className="input" required value={form.paid_at}
              onChange={(event) => setForm((current) => ({ ...current, paid_at: event.target.value }))}/>
          </Field>
        </div>
        {exceedsBalance && (
          <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-2.5 text-xs text-accent-bad flex items-center gap-2">
            <AlertCircle size={14}/> This is more than the {inr(tipsPayableBalance ?? 0)} currently owed to staff.
          </div>
        )}
        <Field label="Note">
          <textarea className="input" required minLength={3} maxLength={500} rows={3} value={form.note}
            placeholder="e.g. Split among staff on shift — Anu, Basil, Reji"
            onChange={(event) => setForm((current) => ({ ...current, note: event.target.value }))}/>
          <span className="mt-1 block text-[10px] text-fg-muted">
            This is the only record of how the payout was distributed until Payroll exists.
          </span>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy || exceedsBalance}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <HandCoins size={14}/>}
            Record payout
          </button>
        </div>
      </form>
    </Modal>
  );
}

function VoidTipPayoutForm({
  row,
  onClose,
  onSuccess,
}: {
  row: TipPayoutDTO;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      await finance.voidTipPayout(row.id, reason.trim());
      onSuccess();
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={busy ? () => undefined : onClose} title="Void tip payout">
      <form onSubmit={submit} className="space-y-3">
        <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm">
          <b>Void {inr(row.amount_minor)} · {manualCollectionMethodLabel(row.method)}?</b>
          <p className="mt-1 text-xs text-fg-muted">
            The original record stays visible for audit, but Tips Payable will show this amount
            as still owed to staff again.
          </p>
        </div>
        <Field label="Reason">
          <textarea className="input" required minLength={3} maxLength={500} rows={3} autoFocus
            value={reason} placeholder="Explain what was wrong"
            onChange={(event) => setReason(event.target.value)}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn bg-accent-bad text-white hover:opacity-90" disabled={busy || reason.trim().length < 3}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Ban size={14}/>}
            Void payout
          </button>
        </div>
      </form>
    </Modal>
  );
}

function TipStat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card">
      <div className="text-xs text-fg-muted uppercase tracking-wider">{label}</div>
      <div className="text-2xl font-bold mt-1">{value}</div>
      {sub && <div className="text-xs text-fg-muted mt-1 font-mono">{sub}</div>}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="block text-xs text-fg-muted mb-1">{label}</span>
      {children}
    </label>
  );
}

function ErrorRow({ text }: { text: string }) {
  return (
    <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad">
      {text}
    </div>
  );
}
