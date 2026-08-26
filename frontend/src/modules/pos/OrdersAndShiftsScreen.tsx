/**
 * Orders & Shifts — the operations history screen.
 *
 *  Tabs:
 *    Orders   — today's orders (newest first) · click for full receipt
 *    Shifts   — open shift cash reconciliation · close shift workflow
 *
 * Why this matters: at end of day the cashier needs to:
 *   1. See every receipt issued (audit + spot mistakes)
 *   2. Count the cash drawer
 *   3. Close the shift — variance is recorded
 */
import { useCallback, useEffect, useState } from 'react';
import {
  Receipt, Loader2, AlertCircle, RefreshCw, Eye, Lock, ShieldCheck,
  ClipboardList, X,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { inr } from '@/lib/inr';
import { parseRupeesToMinor } from '@/lib/money-input';
import { resolveOpenShift, shiftResolutionMessage } from '@/lib/operational-context';
import {
  orders, shifts,
  type OrderListItemDTO, type ShiftDTO,
} from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';
import { useAuth } from '@/modules/auth/AuthContext';
import { subscribeRealtime } from '@/lib/realtime';
import { SkeletonCard } from '@/components/ui/Skeleton';

type Tab = 'orders' | 'shifts';
// Fallback only — real-time push (see subscribeRealtime below) is what
// actually keeps this current; this just covers a missed/dropped push.
const OPERATIONS_POLL_MS = 120_000;

export default function OrdersAndShiftsScreen() {
  const [tab, setTab] = useState<Tab>('orders');

  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Operations</h2>
        <p className="text-fg-muted text-sm">
          Today's orders &amp; shift reconciliation
        </p>
      </header>

      <div className="scroll-strip flex gap-1 mb-6 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'orders'} onClick={() => setTab('orders')}>
          <Receipt size={14}/> Orders
        </TabBtn>
        <TabBtn active={tab === 'shifts'} onClick={() => setTab('shifts')}>
          <ClipboardList size={14}/> Shifts
        </TabBtn>
      </div>

      {tab === 'orders' ? <OrdersTab/> : <ShiftsTab/>}
    </div>
  );
}

// ============================================================================
// Orders today
// ============================================================================
function OrdersTab() {
  const [rows, setRows] = useState<OrderListItemDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [view, setView] = useState<OrderListItemDTO | null>(null);

  const load = useCallback(async () => {
    if (!LIVE_MODE) { setLoading(false); return; }
    setLoading(true); setErr(null);
    try { setRows(await orders.list()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  // Real-time push keeps this current the moment an order is billed from
  // another device/login — quietly (no loading spinner). The interval is
  // just a safety net for a missed push.
  useEffect(() => {
    if (!LIVE_MODE) return;
    const refresh = () => { orders.list().then(setRows).catch(() => {}); };
    const unsubscribe = subscribeRealtime('orders', refresh);
    const id = setInterval(refresh, OPERATIONS_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, []);

  if (!LIVE_MODE) return <div className="card text-fg-muted text-sm">Order history is live-mode only.</div>;
  if (loading) return <SkeletonCard />;

  const total = rows.reduce((s, r) => s + r.total_minor, 0);

  return (
    <div>
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          {rows.length} order{rows.length === 1 ? '' : 's'} today · Total: <b>{inr(total)}</b>
        </p>
        <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
      </div>

      {err && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {err}
      </div>}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No orders today yet. Take your first order from <b>POS</b>.
        </div>
      ) : (
        <div className="card !p-0 overflow-hidden">
          <table className="hidden w-full text-sm md:table">
            <thead className="bg-bg-raised">
              <tr>
                <th className="text-left p-3">Time</th>
                <th className="text-left p-3">Invoice #</th>
                <th className="text-left p-3">Type</th>
                <th className="text-left p-3">Customer</th>
                <th className="text-right p-3">Items</th>
                <th className="text-right p-3">Total</th>
                <th className="text-left p-3">Status</th>
                <th className="p-3"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((o) => (
                <tr key={o.id} className="border-b border-bg-border/60 last:border-0">
                  <td className="p-3 font-mono text-xs">
                    {new Date(o.created_at).toLocaleTimeString('en-IN', {
                      hour: '2-digit', minute: '2-digit',
                    })}
                  </td>
                  <td className="p-3 font-mono text-xs">{o.invoice_no || '—'}</td>
                  <td className="p-3 text-fg-muted text-xs">{o.type}</td>
                  <td className="p-3 text-fg-muted">{o.customer_name || '—'}</td>
                  <td className="p-3 text-right font-mono text-xs">{o.items_count}</td>
                  <td className="p-3 text-right font-mono font-medium">{inr(o.total_minor)}</td>
                  <td className="p-3">
                    <span className={`chip text-[10px] ${
                      o.status === 'paid' ? 'border-accent-good/40 text-accent-good' :
                      o.status === 'open' ? 'border-accent-gold/40 text-accent-gold' :
                                            'border-fg-muted/40 text-fg-muted'
                    }`}>{o.status}</span>
                  </td>
                  <td className="p-3">
                    <button className="text-fg-muted hover:text-accent" onClick={() => setView(o)}>
                      <Eye size={14}/>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mobile-card-list md:hidden">
            {rows.map((o) => (
              <button
                key={o.id}
                type="button"
                onClick={() => setView(o)}
                className="mobile-record-card w-full text-left active:bg-bg-raised/40"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="font-mono text-[11px] text-fg-muted">
                      {new Date(o.created_at).toLocaleTimeString('en-IN', {
                        hour: '2-digit', minute: '2-digit',
                      })}
                    </div>
                    <div className="mt-1 truncate font-semibold">
                      {o.invoice_no || `Order ${o.id.slice(0, 8)}`}
                    </div>
                    <div className="mt-1 text-xs text-fg-muted">
                      {o.customer_name || 'Walk-in'} · {o.type}
                    </div>
                  </div>
                  <span className={`chip shrink-0 text-[10px] ${
                    o.status === 'paid' ? 'border-accent-good/40 text-accent-good' :
                    o.status === 'open' ? 'border-accent-gold/40 text-accent-gold' :
                                          'border-fg-muted/40 text-fg-muted'
                  }`}>{o.status}</span>
                </div>
                <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
                  <div>
                    <div className="text-fg-muted">Items</div>
                    <div className="font-mono font-semibold">{o.items_count}</div>
                  </div>
                  <div className="text-right">
                    <div className="text-fg-muted">Total</div>
                    <div className="font-mono font-semibold">{inr(o.total_minor)}</div>
                  </div>
                </div>
              </button>
            ))}
          </div>
        </div>
      )}

      {view && <OrderViewModal order={view} onClose={() => setView(null)}/>}
    </div>
  );
}

function OrderViewModal({ order, onClose }: { order: OrderListItemDTO; onClose: () => void }) {
  return (
    <Modal open onClose={onClose} title={`Order ${order.invoice_no || order.id.slice(0, 8)}`}>
      <div className="space-y-2 text-sm">
        <Row label="Type" value={order.type}/>
        <Row label="Status" value={order.status}/>
        <Row label="Customer" value={order.customer_name || '—'}/>
        <Row label="Time" value={new Date(order.created_at).toLocaleString('en-IN')}/>
        <Row label="Items" value={order.items_count.toString()}/>
        <Row label="Total" value={inr(order.total_minor)} bold/>
        <p className="text-xs text-fg-muted pt-2 border-t border-bg-border">
          Full receipt printing &amp; refunds will land in the next iteration. For now this is a quick lookup view.
        </p>
      </div>
    </Modal>
  );
}

// ============================================================================
// Shifts
// ============================================================================
function ShiftsTab() {
  const { me, terminalId, terminalReady } = useAuth();
  const [rows, setRows] = useState<ShiftDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [closing, setClosing] = useState<ShiftDTO | null>(null);
  const [opening, setOpening] = useState(false);

  const load = useCallback(async () => {
    if (!LIVE_MODE) { setLoading(false); return; }
    if (!terminalReady || !terminalId) {
      setRows([]);
      setErr('Select the POS terminal used by this device before managing shifts.');
      setLoading(false);
      return;
    }
    setLoading(true); setErr(null);
    try { setRows(await shifts.list()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }, [terminalId, terminalReady]);
  useEffect(() => { void load(); }, [load]);

  // Whether a shift is open, and who has it open, is shared across every
  // device on this terminal. Real-time push means a shift opened elsewhere
  // shows up here within about a second — this is what stops someone from
  // trying to open a second shift on top of one that's already running,
  // just because their screen hadn't caught up yet. The interval is just a
  // safety net for a missed push.
  useEffect(() => {
    if (!LIVE_MODE || !terminalReady || !terminalId) return;
    const refresh = () => { shifts.list().then(setRows).catch(() => {}); };
    const unsubscribe = subscribeRealtime('shifts', refresh);
    const id = setInterval(refresh, OPERATIONS_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, [terminalReady, terminalId]);

  if (!LIVE_MODE) return <div className="card text-fg-muted text-sm">Shift management is live-mode only.</div>;
  if (loading) return <SkeletonCard />;

  const openResolution = resolveOpenShift({
    storedShiftId: null,
    branchId: me?.branch_id ?? null,
    terminalId,
    openShifts: rows,
  });
  const openShift = openResolution.kind === 'ready' ? openResolution.shift : undefined;
  const scopeError = openResolution.kind === 'ambiguous_open_shifts'
    ? shiftResolutionMessage(openResolution)
    : null;

  return (
    <div>
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          {rows.length} shift{rows.length === 1 ? '' : 's'} ·{' '}
          {openShift
            ? <span className="text-accent-good">Open shift active</span>
            : <span>No shift currently open</span>}
        </p>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          {!openShift && !scopeError && (
            <button className="btn btn-primary" onClick={() => setOpening(true)}>
              <ShieldCheck size={14}/> Open shift
            </button>
          )}
        </div>
      </div>

      {err && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {err}
      </div>}
      {scopeError && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {scopeError}
      </div>}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No shifts recorded. Open the first shift before taking orders.
        </div>
      ) : (
        <div className="space-y-3">
          {rows.map((s) => (
            <div key={s.id} className="card">
              <div className="flex justify-between items-start gap-3 flex-wrap">
                <div>
                  <div className="font-bold flex items-center gap-2">
                    {s.status === 'open'
                      ? <span className="chip border-accent-good/40 text-accent-good">Open</span>
                      : <span className="chip border-fg-muted/40 text-fg-muted">Closed</span>}
                    {new Date(s.opened_at).toLocaleString('en-IN')}
                  </div>
                  <div className="text-xs text-fg-muted">
                    Opened by <span className="text-fg font-medium">
                      {s.opened_by_name ?? 'Unknown'}
                      {s.opened_by_email ? ` · ${s.opened_by_email}` : ''}
                    </span>
                  </div>
                  {s.closed_at && (
                    <div className="text-xs text-fg-muted">
                      Closed {new Date(s.closed_at).toLocaleString('en-IN')}
                    </div>
                  )}
                </div>
                {s.status === 'open' && (s.opened_by === me?.user_id || me?.protected_access) && (
                  <button className="btn btn-primary" onClick={() => setClosing(s)}>
                    <Lock size={14}/> Close shift
                  </button>
                )}
                {s.status === 'open' && s.opened_by !== me?.user_id && !me?.protected_access && (
                  <div className="max-w-xs text-right text-xs text-fg-muted">
                    Only the opener shown here, or a protected owner, can count and close this shift.
                  </div>
                )}
              </div>

              <div className="grid grid-cols-3 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                <Stat label="POS collections" value={inr(s.pos_sales_minor ?? 0)}/>
                <Stat label="Memberships" value={inr(s.membership_sales_minor ?? 0)}/>
                <Stat label="Gross collections" value={inr(s.gross_collections_minor ?? 0)}/>
              </div>
              <p className="mt-1 text-[10px] text-fg-muted">
                Payment receipts before refunds; opening float is excluded.
              </p>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                <Stat label="POS refunds" value={inr(s.settled_pos_refunds_minor ?? 0)}/>
                <Stat label="Membership refunds" value={inr(s.settled_membership_refunds_minor ?? 0)}/>
                <Stat label="Total refunds" value={inr(s.total_refunds_minor ?? 0)}/>
                <Stat label="Net collections" value={inr(s.net_collections_minor ?? 0)} tone="good"/>
              </div>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                <Stat label="Opening float" value={inr(s.opening_float_minor)}/>
                <Stat label="Expected cash" value={s.expected_minor != null ? inr(s.expected_minor) : '—'}/>
                <Stat label="Counted cash"  value={s.counted_minor != null ? inr(s.counted_minor) : '—'}/>
                <Stat label="Variance"
                  value={s.variance_minor != null ? inr(s.variance_minor) : '—'}
                  tone={s.variance_minor == null ? 'default' :
                        s.variance_minor === 0 ? 'good' :
                        Math.abs(s.variance_minor) < 5000 ? 'gold' : 'bad'}/>
              </div>
            </div>
          ))}
        </div>
      )}

      {opening && <OpenShiftForm
        onClose={() => setOpening(false)}
        onSuccess={() => { setOpening(false); load(); }}
        onError={load}/>}
      {closing && <CloseShiftForm shift={closing}
        onClose={() => setClosing(null)}
        onSuccess={() => { setClosing(null); load(); }}/>}
    </div>
  );
}

function OpenShiftForm({ onClose, onSuccess, onError }: { onClose: () => void; onSuccess: () => void; onError: () => void }) {
  const [float, setFloat] = useState('500');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const openingFloatMinor = parseRupeesToMinor(float);
      if (openingFloatMinor === null) {
        throw new Error('Opening float must be a non-negative amount with at most two decimals.');
      }
      await shifts.open(openingFloatMinor);
      onSuccess();
    } catch (e) {
      setErr((e as Error).message);
      // Most likely cause: someone else already opened a shift here since
      // this screen last synced. Refresh the list behind this modal so the
      // real shift is already showing by the time this error is dismissed.
      onError();
    }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title="Open shift">
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-fg-muted">
          The opening float is the cash already in the drawer before you start.
          Typical Indian café float: ₹500 — ₹1,000.
        </p>
        <Field label="Opening float (₹)">
          <input type="number" required min={0} step="0.01" autoFocus
            className="input font-mono text-right text-xl" value={float}
            onChange={(e) => setFloat(e.target.value)}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <ShieldCheck size={14}/>}
            Open shift
          </button>
        </div>
      </form>
    </Modal>
  );
}

// Indian note/coin denominations, largest first — used by the optional
// denomination-breakdown entry mode below.
const CASH_DENOMINATIONS = [500, 200, 100, 50, 20, 10, 5, 2, 1];

function CloseShiftForm({
  shift, onClose, onSuccess,
}: { shift: ShiftDTO; onClose: () => void; onSuccess: () => void }) {
  const [mode, setMode] = useState<'total' | 'denomination'>('total');
  const [counted, setCounted] = useState('');
  const [denomCounts, setDenomCounts] = useState<Record<number, string>>({
    500: '', 200: '', 100: '', 50: '', 20: '', 10: '', 5: '', 2: '', 1: '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [result, setResult] = useState<{ variance_minor: number } | null>(null);

  // Sum of (denomination × count), in paise — exact integer arithmetic, no
  // float round-trip through a rupee string.
  const denomTotalMinor = CASH_DENOMINATIONS.reduce(
    (sum, d) => sum + d * 100 * (parseInt(denomCounts[d] || '0', 10) || 0),
    0,
  );

  // Keep the free-text total field mirroring the breakdown while denomination
  // mode is active, so what's on screen always matches what will be submitted.
  useEffect(() => {
    if (mode === 'denomination') setCounted((denomTotalMinor / 100).toFixed(2));
  }, [mode, denomTotalMinor]);

  function setDenomCount(denom: number, value: string) {
    setDenomCounts((prev) => ({ ...prev, [denom]: value }));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      // Denomination mode submits the exact integer sum computed above;
      // free-text mode submits exactly as before (unchanged wire format).
      const countedMinor = mode === 'denomination'
        ? denomTotalMinor
        : parseRupeesToMinor(counted);
      if (countedMinor === null) {
        throw new Error('Counted cash must be a non-negative amount with at most two decimals.');
      }
      const r = await shifts.close(shift.id, countedMinor);
      setResult(r);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  if (result) {
    const v = result.variance_minor;
    return (
      <Modal open onClose={() => { onClose(); onSuccess(); }} title="Shift closed">
        <div className="space-y-3">
          <div className={`p-4 rounded-xl ${
            v === 0 ? 'bg-accent-good/15 border border-accent-good/40 text-accent-good' :
            Math.abs(v) < 5000 ? 'bg-accent-gold/15 border border-accent-gold/40 text-accent-gold' :
                                 'bg-accent-bad/15 border border-accent-bad/40 text-accent-bad'
          }`}>
            <div className="text-xs uppercase tracking-wider mb-1">Variance</div>
            <div className="text-3xl font-bold font-mono">{inr(v)}</div>
            <div className="text-xs mt-1">
              {v === 0 ? '✓ Drawer balanced exactly' :
               v > 0 ? 'Surplus — more cash than expected' :
                       'Short — less cash than expected'}
            </div>
          </div>
          <button className="btn btn-primary w-full" onClick={() => { onClose(); onSuccess(); }}>
            Done
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal open onClose={onClose} title="Close shift">
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-fg-muted">
          Count all the cash in the drawer (notes + coins), then enter the total below.
          The system compares it with the expected amount (opening float + cash sales − cash refunds).
        </p>
        <Field label="Opening float">
          <input className="input font-mono text-right" disabled
            value={inr(shift.opening_float_minor)}/>
        </Field>

        <div className="flex gap-1 p-1 rounded-lg bg-bg-raised">
          <button type="button"
            className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
              mode === 'total' ? 'bg-bg-surface shadow text-fg' : 'text-fg-muted'}`}
            onClick={() => setMode('total')}>
            Enter total
          </button>
          <button type="button"
            className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
              mode === 'denomination' ? 'bg-bg-surface shadow text-fg' : 'text-fg-muted'}`}
            onClick={() => setMode('denomination')}>
            Count denominations
          </button>
        </div>

        <Field label="Counted cash (₹) — what's actually in the drawer">
          <input type="number" required min={0} step="0.01" autoFocus={mode === 'total'}
            disabled={mode === 'denomination'}
            className="input font-mono text-right text-xl disabled:opacity-80" value={counted}
            onChange={(e) => setCounted(e.target.value)}/>
        </Field>

        {mode === 'denomination' && (
          <div className="rounded-xl border border-bg-border divide-y divide-bg-border overflow-hidden">
            {CASH_DENOMINATIONS.map((d, i) => {
              const count = parseInt(denomCounts[d] || '0', 10) || 0;
              return (
                <div key={d} className="flex items-center gap-3 px-3 py-2">
                  <span className="w-12 shrink-0 font-mono text-sm text-fg-muted">₹{d}</span>
                  <input type="number" min={0} step="1" inputMode="numeric" autoFocus={i === 0}
                    className="input font-mono text-right flex-1" value={denomCounts[d]}
                    placeholder="0"
                    onChange={(e) => setDenomCount(d, e.target.value)}/>
                  <span className="w-24 shrink-0 text-right font-mono text-sm text-fg-muted">
                    {inr(d * 100 * count)}
                  </span>
                </div>
              );
            })}
            <div className="flex items-center justify-between px-3 py-2 font-bold bg-bg-raised/50">
              <span>Total counted</span>
              <span className="font-mono">{inr(denomTotalMinor)}</span>
            </div>
          </div>
        )}

        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Lock size={14}/>}
            Close shift
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ============================================================================
// bits
// ============================================================================
function TabBtn({ active, onClick, children }: {
  active: boolean; onClick: () => void; children: React.ReactNode;
}) {
  return (
    <button onClick={onClick}
      className={`shrink-0 px-4 py-2 text-sm font-medium border-b-2 -mb-px whitespace-nowrap flex items-center gap-1.5
        ${active ? 'border-accent text-accent' : 'border-transparent text-fg-muted hover:text-fg'}`}>
      {children}
    </button>
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
      <X size={14}/> {text}
    </div>
  );
}
function Row({ label, value, bold }: { label: string; value: string; bold?: boolean }) {
  return (
    <div className={`flex justify-between py-1 ${bold ? 'font-bold border-t border-bg-border pt-2 mt-2' : ''}`}>
      <span className="text-fg-muted">{label}</span>
      <span className="font-mono">{value}</span>
    </div>
  );
}
function Stat({ label, value, tone = 'default' }: {
  label: string; value: string; tone?: 'default' | 'good' | 'gold' | 'bad';
}) {
  const color =
    tone === 'good' ? 'text-accent-good' :
    tone === 'gold' ? 'text-accent-gold' :
    tone === 'bad' ? 'text-accent-bad' : 'text-fg';
  return (
    <div>
      <div className="text-[10px] text-fg-muted uppercase tracking-wider">{label}</div>
      <div className={`font-mono font-bold ${color}`}>{value}</div>
    </div>
  );
}
