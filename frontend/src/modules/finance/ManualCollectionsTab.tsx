import { useCallback, useEffect, useState } from 'react';
import { AlertCircle, Ban, BookOpen, Loader2, Plus, RefreshCw } from 'lucide-react';

import Modal from '@/components/ui/Modal';
import { useNotifications } from '@/components/ui/Notifications';
import { FINANCE_ACTION_FEEDBACK } from '@/lib/action-feedback';
import {
  finance,
  pos,
  shifts,
  type BranchReferenceDTO,
  type ManualCollectionDTO,
  type ManualCollectionMethod,
  type ShiftDTO,
} from '@/lib/erp-api';
import { inr, inrShort } from '@/lib/inr';
import {
  MANUAL_COLLECTION_METHODS,
  DEFAULT_BUSINESS_TIMEZONE,
  dateISOInTimeZone,
  defaultManualCollectionReference,
  manualCollectionMethodLabel,
  manualCollectionTotals,
  rupeesToMinor,
} from '@/lib/manual-collections';
import { useAuth } from '@/modules/auth/AuthContext';
import { useRealtimeRefresh } from '@/hooks/useRealtimeRefresh';
import { useLatestRequest } from '@/hooks/useLatestRequest';

// ============================================================================
// MANUAL COLLECTIONS — auditable off-POS / legacy daily totals
// ============================================================================
export default function ManualCollectionsTab() {
  const requests = useLatestRequest();
  const { me } = useAuth();
  const notifications = useNotifications();
  const [rows, setRows] = useState<ManualCollectionDTO[]>([]);
  const [branches, setBranches] = useState<BranchReferenceDTO[]>([]);
  const [openShifts, setOpenShifts] = useState<ShiftDTO[]>([]);
  const [companyTimezone, setCompanyTimezone] = useState(DEFAULT_BUSINESS_TIMEZONE);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [voiding, setVoiding] = useState<ManualCollectionDTO | null>(null);
  const [correcting, setCorrecting] = useState<ManualCollectionDTO | null>(null);

  const load = useCallback(async (silent = false) => {
    const isCurrent = requests.begin();
    if (!silent) setLoading(true);
    try {
      const [collections, branchRows, receiptIdentity, shiftRows] = await Promise.all([
        finance.listManualCollections({ include_voided: true, limit: 500 }),
        finance.listBranches(),
        pos.receiptBusiness().catch(() => null),
        shifts.list(true),
      ]);
      if (!isCurrent()) return;
      setErr(null);
      setRows(collections);
      setBranches(branchRows);
      setOpenShifts(shiftRows.filter((row) => row.status === 'open'));
      setCompanyTimezone(receiptIdentity?.timezone || DEFAULT_BUSINESS_TIMEZONE);
    } catch (error) {
      if (isCurrent()) setErr((error as Error).message);
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [requests]);

  useEffect(() => { void load(); }, [load]);
  useRealtimeRefresh({ resources: ['finance'], refresh: () => load(true) });

  if (loading) {
    return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading manual collections…</div>;
  }

  const totals = manualCollectionTotals(rows);
  const branchNames = new Map(branches.map((branch) => [branch.id, branch.name]));

  return (
    <div>
      <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm">
        <div className="flex items-start gap-2">
          <AlertCircle size={16} className="mt-0.5 shrink-0 text-accent-gold"/>
          <div>
            <b>Use this only for money genuinely collected outside POS or for an honest legacy daily total.</b>{' '}
            It contributes to revenue and payment movement, but it does not create a POS order,
            tax invoice, table ticket, gaming session, item mix, or automatic COGS. Entries cannot
            be edited or deleted; a mistake must be voided with a reason.
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
        <CollectionStat label="Active total" value={inrShort(totals.total_minor)} sub={inr(totals.total_minor)} tone="good"/>
        <CollectionStat label="Cash" value={inrShort(totals.cash_minor)} sub={inr(totals.cash_minor)}/>
        <CollectionStat label="UPI" value={inrShort(totals.upi_minor)} sub={inr(totals.upi_minor)}/>
        <CollectionStat label="Card + bank" value={inrShort(totals.card_minor + totals.bank_minor)}
          sub={`${totals.active_count} active · ${totals.voided_count} voided`}/>
      </div>

      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          Immutable collection register · newest business date first
        </p>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={() => void load()} aria-label="Refresh manual collections">
            <RefreshCw size={14}/>
          </button>
          <button className="btn btn-primary" onClick={() => setAddOpen(true)} disabled={!branches.length}>
            <Plus size={14}/> Add manual collection
          </button>
        </div>
      </div>

      {!branches.length && (
        <div className="card border-accent-gold/40 bg-accent-gold/10 text-accent-gold text-sm mb-3">
          No shop is available for this collection. Ask the protected owner to check your shop access.
        </div>
      )}
      {err && <ErrorRow text={err}/>}
      {rows.length === 500 && (
        <div className="card border-accent-gold/40 bg-accent-gold/10 text-accent-gold text-sm mb-3">
          Showing the newest 500 records. Use the report period totals for older collection history.
        </div>
      )}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No manual collections recorded. Normal orders should continue through Tables, Gaming or
          Shisha into POS; use this register only when no itemized order exists.
        </div>
      ) : (
        <div className="card !p-0 overflow-hidden">
          <table className="hidden w-full text-sm md:table">
            <thead className="bg-bg-raised">
              <tr>
                <th className="text-left p-3">Business date</th>
                <th className="text-left p-3">Branch / method</th>
                <th className="text-left p-3">Reference / note</th>
                <th className="text-left p-3">Recorded by</th>
                <th className="text-right p-3">Amount</th>
                <th className="text-right p-3 pr-4">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id} className={`border-b border-bg-border/60 last:border-0 ${row.is_voided ? 'opacity-60' : ''}`}>
                  <td className="p-3 font-mono text-xs">
                    {new Date(`${row.business_date}T00:00:00`).toLocaleDateString('en-IN')}
                    <div className="mt-1 font-sans text-[10px] text-fg-muted">
                      {row.source_kind === 'legacy_daily' ? 'Legacy daily total' : 'Manual daily total'}
                    </div>
                  </td>
                  <td className="p-3">
                    <div>{branchNames.get(row.branch_id) ?? 'Unknown branch'}</div>
                    <span className="chip mt-1 text-[10px]">{manualCollectionMethodLabel(row.method)}</span>
                  </td>
                  <td className="p-3 max-w-xs">
                    <div className={row.is_voided ? 'line-through' : ''}>{row.source_ref}</div>
                    {row.note && <div className="mt-1 text-xs text-fg-muted break-words">{row.note}</div>}
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
                    ) : row.is_corrected ? (
                      <span className="chip border-accent-gold/40 text-accent-gold text-[10px]">Corrected</span>
                    ) : row.method === 'cash' && row.source_shift_status !== 'open' ? (
                      <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs"
                        onClick={() => setCorrecting(row)} aria-label={`Correct ${row.source_ref}`}>
                        <Ban size={13}/> Correct
                      </button>
                    ) : (
                      <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs hover:!text-accent-bad"
                        onClick={() => setVoiding(row)} aria-label={`Void ${row.source_ref}`}>
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
                      {row.source_ref}
                    </div>
                    <div className="mt-1 text-xs text-fg-muted">
                      {new Date(`${row.business_date}T00:00:00`).toLocaleDateString('en-IN')}
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
                {row.note && <div className="mt-2 text-xs text-fg-muted break-words">{row.note}</div>}
                {row.void_reason && <div className="mt-2 text-xs text-accent-bad">Void reason: {row.void_reason}</div>}
                {row.correction && (
                  <div className="mt-2 text-xs text-accent-gold">
                    Corrected {new Date(row.correction.corrected_at).toLocaleString('en-IN')}: {row.correction.reason}
                  </div>
                )}
                <div className="mt-3 flex items-center justify-between border-t border-bg-border/60 pt-3">
                  <span className="text-[10px] text-fg-muted">
                    {row.source_kind === 'legacy_daily' ? 'Legacy daily total' : 'Manual daily total'}
                  </span>
                  {row.is_voided ? (
                    <span className="chip border-accent-bad/40 text-accent-bad text-[10px]">Voided</span>
                  ) : row.is_corrected ? (
                    <span className="chip border-accent-gold/40 text-accent-gold text-[10px]">Corrected</span>
                  ) : row.method === 'cash' && row.source_shift_status !== 'open' ? (
                    <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs"
                      onClick={() => setCorrecting(row)}>
                      <Ban size={13}/> Correct
                    </button>
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
        <ManualCollectionForm
          branches={branches}
          openShifts={openShifts}
          defaultBranchId={me?.branch_id ?? branches[0]?.id ?? ''}
          companyTimezone={companyTimezone}
          onClose={() => setAddOpen(false)}
          onSuccess={() => {
            setAddOpen(false);
            void load();
            const feedback = FINANCE_ACTION_FEEDBACK.manualCollectionRecorded;
            notifications.success(feedback.message, { title: feedback.title });
          }}
        />
      )}
      {voiding && (
        <VoidManualCollectionForm
          row={voiding}
          onClose={() => setVoiding(null)}
          onSuccess={() => {
            setVoiding(null);
            void load();
            const feedback = FINANCE_ACTION_FEEDBACK.manualCollectionVoided;
            notifications.success(feedback.message, { title: feedback.title });
          }}
        />
      )}
      {correcting && (
        <ManualCollectionCorrectionForm
          row={correcting}
          openShifts={openShifts.filter((shift) => shift.branch_id === correcting.branch_id)}
          onClose={() => setCorrecting(null)}
          onSuccess={() => {
            setCorrecting(null);
            void load();
            notifications.success('The closed-shift cash collection was reversed in the selected current drawer.', { title: 'Correction recorded' });
          }}
        />
      )}
    </div>
  );
}

function newManualCollectionKey(): string {
  const randomPart = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `manual-collection:${randomPart}`;
}

function newManualCollectionCorrectionKey(): string {
  const id = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `manual-collection-correction:${id}`;
}

function ManualCollectionForm({
  branches,
  openShifts,
  defaultBranchId,
  companyTimezone,
  onClose,
  onSuccess,
}: {
  branches: BranchReferenceDTO[];
  openShifts: ShiftDTO[];
  defaultBranchId: string;
  companyTimezone: string;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [form, setForm] = useState<{
    branch_id: string;
    shift_id: string;
    business_date: string;
    method: ManualCollectionMethod;
    amount_rupees: string;
    source_ref: string;
    note: string;
  }>(() => {
    const businessDate = dateISOInTimeZone(new Date(), companyTimezone);
    const method: ManualCollectionMethod = 'cash';
    const branchId = branches.some((branch) => branch.id === defaultBranchId)
        ? defaultBranchId
        : (branches[0]?.id ?? '');
    return {
      branch_id: branchId,
      shift_id: openShifts.find((shift) => shift.branch_id === branchId)?.id ?? '',
      business_date: businessDate,
      method,
      amount_rupees: '',
      source_ref: defaultManualCollectionReference(businessDate, method),
      note: '',
    };
  });
  const [idempotencyKey] = useState(newManualCollectionKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  function changeBusinessDate(businessDate: string) {
    setForm((current) => ({
      ...current,
      business_date: businessDate,
      source_ref: current.source_ref === defaultManualCollectionReference(current.business_date, current.method)
        ? defaultManualCollectionReference(businessDate, current.method)
        : current.source_ref,
    }));
  }

  function changeMethod(method: ManualCollectionMethod) {
    setForm((current) => ({
      ...current,
      method,
      source_ref: current.source_ref === defaultManualCollectionReference(current.business_date, current.method)
        ? defaultManualCollectionReference(current.business_date, method)
        : current.source_ref,
    }));
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const amountMinor = rupeesToMinor(form.amount_rupees);
    if (amountMinor === null) {
      setErr('Enter an amount greater than ₹0 with no more than two decimal places.');
      return;
    }
    if (!form.source_ref.trim()) {
      setErr('Enter a reference that can be matched to the daily sheet, bank statement, or other evidence.');
      return;
    }
    if (form.method === 'cash' && !form.shift_id) {
      setErr('Select the open shift drawer that received this cash.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await finance.createManualCollection({
        branch_id: form.branch_id,
        shift_id: form.method === 'cash' ? form.shift_id : undefined,
        business_date: form.business_date,
        method: form.method,
        amount_minor: amountMinor,
        source_ref: form.source_ref.trim(),
        note: form.note.trim() || undefined,
      }, idempotencyKey);
      onSuccess();
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={busy ? () => undefined : onClose} title="Add manual collection">
      <form onSubmit={submit} className="space-y-3">
        <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-xs">
          This records an unitemized payment total only. It will not create an order, invoice,
          receipt, table ticket, or gaming session.
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="Business date">
            <input type="date" required max={dateISOInTimeZone(new Date(), companyTimezone)}
              className="input" value={form.business_date}
              onChange={(event) => changeBusinessDate(event.target.value)}/>
          </Field>
          <Field label="Branch">
            <select className="input" required value={form.branch_id}
              onChange={(event) => {
                const branchId = event.target.value;
                setForm((current) => ({
                  ...current,
                  branch_id: branchId,
                  shift_id: openShifts.find((shift) => shift.branch_id === branchId)?.id ?? '',
                }));
              }}>
              {branches.map((branch) => <option key={branch.id} value={branch.id}>{branch.name}</option>)}
            </select>
          </Field>
        </div>
        {form.method === 'cash' && (
          <Field label="Open shift drawer">
            <select className="input" required value={form.shift_id}
              onChange={(event) => setForm((current) => ({ ...current, shift_id: event.target.value }))}>
              <option value="">Select an open drawer…</option>
              {openShifts.filter((shift) => shift.branch_id === form.branch_id).map((shift) => (
                <option key={shift.id} value={shift.id}>
                  {(shift.opened_by_name || shift.opened_by_email || 'Authorised staff')} · {inr(shift.expected_minor ?? 0)} expected
                </option>
              ))}
            </select>
            <span className="mt-1 block text-[10px] text-fg-muted">
              The server adds this cash to the selected drawer in the same transaction.
            </span>
          </Field>
        )}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="Payment method">
            <select className="input" value={form.method}
              onChange={(event) => changeMethod(event.target.value as ManualCollectionMethod)}>
              {MANUAL_COLLECTION_METHODS.map((method) => (
                <option key={method.value} value={method.value}>{method.label}</option>
              ))}
            </select>
          </Field>
          <Field label="Amount (₹)">
            <input type="number" required min="0.01" step="0.01" inputMode="decimal" autoFocus
              className="input font-mono text-right text-xl" value={form.amount_rupees}
              onChange={(event) => setForm((current) => ({ ...current, amount_rupees: event.target.value }))}/>
          </Field>
        </div>
        <Field label="Reference">
          <input className="input" required minLength={1} maxLength={160} value={form.source_ref}
            onChange={(event) => setForm((current) => ({ ...current, source_ref: event.target.value }))}/>
          <span className="mt-1 block text-[10px] text-fg-muted">
            Kept private in ERP. Google Sheets receives a generated ERP reference instead.
          </span>
        </Field>
        <Field label="Note (optional)">
          <textarea className="input" rows={3} maxLength={500} value={form.note}
            placeholder="Why this was collected outside POS and where the supporting evidence is kept"
            onChange={(event) => setForm((current) => ({ ...current, note: event.target.value }))}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <BookOpen size={14}/>}
            Record collection
          </button>
        </div>
      </form>
    </Modal>
  );
}

function VoidManualCollectionForm({
  row,
  onClose,
  onSuccess,
}: {
  row: ManualCollectionDTO;
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
      await finance.voidManualCollection(row.id, reason.trim());
      onSuccess();
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={busy ? () => undefined : onClose} title="Void manual collection">
      <form onSubmit={submit} className="space-y-3">
        <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm">
          <b>Void {inr(row.amount_minor)} · {manualCollectionMethodLabel(row.method)} · {row.business_date}?</b>
          <p className="mt-1 text-xs text-fg-muted">
            The original record stays visible for audit, but it will be excluded from revenue,
            payment movement, P&amp;L and active collection totals.
          </p>
        </div>
        <Field label="Reason">
          <textarea className="input" required minLength={3} maxLength={500} rows={3} autoFocus
            value={reason} placeholder="Explain what was wrong and, if applicable, reference the replacement entry"
            onChange={(event) => setReason(event.target.value)}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn bg-accent-bad text-white hover:opacity-90" disabled={busy || reason.trim().length < 3}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Ban size={14}/>}
            Void collection
          </button>
        </div>
      </form>
    </Modal>
  );
}

function ManualCollectionCorrectionForm({
  row,
  openShifts,
  onClose,
  onSuccess,
}: {
  row: ManualCollectionDTO;
  openShifts: ShiftDTO[];
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [shiftId, setShiftId] = useState(openShifts[0]?.id ?? '');
  const [reason, setReason] = useState('');
  const [key] = useState(newManualCollectionCorrectionKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!shiftId) {
      setErr('Open a same-branch shift and select its drawer before correcting this entry.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await finance.correctManualCollection(
        row.id,
        { settlement_shift_id: shiftId, reason: reason.trim() },
        key,
      );
      onSuccess();
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={busy ? () => undefined : onClose} title="Correct closed-shift collection">
      <form onSubmit={submit} className="space-y-3">
        <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm">
          The original {inr(row.amount_minor)} receipt and closed shift stay unchanged. This full reversal is dated now and deducts the amount from a current same-branch drawer.
        </div>
        <Field label="Current open drawer">
          <select className="input" required value={shiftId} onChange={(event) => setShiftId(event.target.value)}>
            <option value="">Select an open drawer…</option>
            {openShifts.map((shift) => (
              <option key={shift.id} value={shift.id}>
                {(shift.opened_by_name || shift.opened_by_email || 'Authorised staff')} · {inr(shift.expected_minor ?? 0)} expected
              </option>
            ))}
          </select>
        </Field>
        <Field label="Correction reason">
          <textarea className="input" required minLength={3} maxLength={500} rows={3}
            value={reason} onChange={(event) => setReason(event.target.value)}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy || !shiftId || reason.trim().length < 3}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Ban size={14}/>} Record full correction
          </button>
        </div>
      </form>
    </Modal>
  );
}

function CollectionStat({
  label,
  value,
  sub,
  tone = 'default',
}: {
  label: string;
  value: string;
  sub?: string;
  tone?: 'default' | 'good' | 'bad';
}) {
  const color = tone === 'bad'
    ? 'text-accent-bad'
    : tone === 'good'
      ? 'text-accent-good'
      : 'text-fg';
  return (
    <div className="card">
      <div className="text-xs text-fg-muted uppercase tracking-wider">{label}</div>
      <div className={`text-2xl font-bold mt-1 ${color}`}>{value}</div>
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
