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
import { useEffect, useState } from 'react';
import {
  Receipt, Loader2, AlertCircle, RefreshCw, Eye, Lock, ShieldCheck,
  ClipboardList, X,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { inr } from '@/lib/inr';
import {
  orders, shifts,
  type OrderListItemDTO, type ShiftDTO,
} from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';

type Tab = 'orders' | 'shifts';

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

  async function load() {
    if (!LIVE_MODE) { setLoading(false); return; }
    setLoading(true); setErr(null);
    try { setRows(await orders.list()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (!LIVE_MODE) return <div className="card text-fg-muted text-sm">Order history is live-mode only.</div>;
  if (loading) return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;

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
  const [rows, setRows] = useState<ShiftDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [closing, setClosing] = useState<ShiftDTO | null>(null);
  const [opening, setOpening] = useState(false);

  async function load() {
    if (!LIVE_MODE) { setLoading(false); return; }
    setLoading(true); setErr(null);
    try { setRows(await shifts.list()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (!LIVE_MODE) return <div className="card text-fg-muted text-sm">Shift management is live-mode only.</div>;
  if (loading) return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;

  const openShift = rows.find((s) => s.status === 'open');

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
          {!openShift && (
            <button className="btn btn-primary" onClick={() => setOpening(true)}>
              <ShieldCheck size={14}/> Open shift
            </button>
          )}
        </div>
      </div>

      {err && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {err}
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
                  {s.closed_at && (
                    <div className="text-xs text-fg-muted">
                      Closed {new Date(s.closed_at).toLocaleString('en-IN')}
                    </div>
                  )}
                </div>
                {s.status === 'open' && (
                  <button className="btn btn-primary" onClick={() => setClosing(s)}>
                    <Lock size={14}/> Close shift
                  </button>
                )}
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
        onSuccess={() => { setOpening(false); load(); }}/>}
      {closing && <CloseShiftForm shift={closing}
        onClose={() => setClosing(null)}
        onSuccess={() => { setClosing(null); load(); }}/>}
    </div>
  );
}

function OpenShiftForm({ onClose, onSuccess }: { onClose: () => void; onSuccess: () => void }) {
  const [float, setFloat] = useState('500');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      await shifts.open(Math.round(parseFloat(float || '0') * 100));
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
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

function CloseShiftForm({
  shift, onClose, onSuccess,
}: { shift: ShiftDTO; onClose: () => void; onSuccess: () => void }) {
  const [counted, setCounted] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [result, setResult] = useState<{ variance_minor: number } | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const r = await shifts.close(shift.id, Math.round(parseFloat(counted || '0') * 100));
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
        <Field label="Counted cash (₹) — what's actually in the drawer">
          <input type="number" required min={0} step="0.01" autoFocus
            className="input font-mono text-right text-xl" value={counted}
            onChange={(e) => setCounted(e.target.value)}/>
        </Field>
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
