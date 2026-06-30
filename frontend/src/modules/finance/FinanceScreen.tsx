/**
 * Finance screen — tabbed.
 *
 *  Overview     today's P&L (demo) + KPIs
 *  Expenses     list, add, delete expenses (live)
 *  Partners     list partners + capital movements (invest / withdraw / profit_share)
 *
 * In demo mode the Expenses & Partners tabs show empty states; live mode
 * hits /api/v1/finance/* and /api/v1/settings/*.
 */
import { useEffect, useState } from 'react';
import {
  TrendingUp, TrendingDown, Receipt, Users, Plus, Trash2,
  Loader2, AlertCircle, Wallet, Banknote, RefreshCw,
} from 'lucide-react';

import { inr, inrShort } from '@/lib/inr';
import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import {
  finance, settings, reports,
  type ExpenseDTO, type PartnerDTO, type BranchDTO, type ExpenseCategoryDTO,
  type CapitalEntryDTO, type ReportDataDTO,
} from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';

type Tab = 'overview' | 'expenses' | 'partners';

export default function FinanceScreen() {
  const [tab, setTab] = useState<Tab>('overview');
  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Finance</h2>
        <p className="text-fg-muted text-sm">
          P&amp;L · expenses · partner capital · Kerala GST split
        </p>
      </header>

      <div className="scroll-strip flex gap-1 mb-6 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'overview'} onClick={() => setTab('overview')}>
          <TrendingUp size={14}/> Overview
        </TabBtn>
        <TabBtn active={tab === 'expenses'} onClick={() => setTab('expenses')}>
          <Receipt size={14}/> Expenses
        </TabBtn>
        <TabBtn active={tab === 'partners'} onClick={() => setTab('partners')}>
          <Users size={14}/> Partners
        </TabBtn>
      </div>

      {tab === 'overview' && <OverviewTab/>}
      {tab === 'expenses' && <ExpensesTab/>}
      {tab === 'partners' && <PartnersTab/>}
    </div>
  );
}

// ============================================================================
// OVERVIEW TAB — today's P&L from live /reports/daily
// ============================================================================
function todayISO(): string {
  const d = new Date();
  const p = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function OverviewTab() {
  const [data, setData] = useState<ReportDataDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try {
      setData(await reports.daily(todayISO()));
    } catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (loading) {
    return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading today's P&L…</div>;
  }
  if (err) return <ErrorRow text={err}/>;
  if (!data) return null;

  const rev = data.revenue;
  const tax = data.tax_collected;
  const exp = data.expense_total_minor;
  const net = data.net_profit_minor;
  const totalGst = tax.cgst_minor + tax.sgst_minor + tax.igst_minor;
  const empty = data.orders_count === 0 && data.tickets_count === 0 && exp === 0;

  return (
    <>
      <div className="flex items-baseline justify-between mb-3 flex-wrap gap-2">
        <p className="text-xs text-fg-muted">
          Today · {data.orders_count} order{data.orders_count === 1 ? '' : 's'} · {data.tickets_count} ticket{data.tickets_count === 1 ? '' : 's'} · Avg {inr(data.avg_ticket_minor)}
        </p>
        <button className="btn btn-ghost !min-h-[28px] !py-1 !px-2 text-xs" onClick={load}>
          <RefreshCw size={11}/>
        </button>
      </div>

      {empty && (
        <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-accent-gold text-sm">
          <b>No activity today yet.</b> Take an order from <b>POS</b> or record an expense from the Expenses tab to see real numbers here.
        </div>
      )}

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 md:gap-4 mb-6">
        <Stat label="Revenue (gross)"   value={inrShort(rev.total_minor)} sub={inr(rev.total_minor)} tone="good"/>
        <Stat label="GST collected"     value={inrShort(totalGst)} sub="CGST + SGST + IGST"/>
        <Stat label="Expenses"          value={inrShort(exp)} sub={inr(exp)} tone="bad"/>
        <Stat label="Net profit (today)" value={inrShort(net)} sub={inr(net)} tone={net >= 0 ? 'good' : 'bad'}/>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3 md:gap-4">
        <div className="card">
          <h3 className="font-semibold mb-4 flex items-center gap-2">
            <TrendingUp size={16} className="text-accent-good"/> Revenue
          </h3>
          {rev.food_minor > 0 && <Row label="Food (5% GST)" v={rev.food_minor}/>}
          {rev.gaming_minor > 0 && <Row label="Gaming (18% GST)" v={rev.gaming_minor}/>}
          {isAppStoreAllowedType('hookah') && rev.hookah_minor > 0 && <Row label="Shisha (18% GST)" v={rev.hookah_minor}/>}
          {rev.event_tickets_minor > 0 && <Row label="Event tickets (18% GST)" v={rev.event_tickets_minor}/>}
          {rev.delivery_aggregator_minor > 0 && (
            <Row label="Delivery aggregator (§9(5))" v={rev.delivery_aggregator_minor} sub="zero GST on our invoice"/>
          )}
          {rev.other_minor > 0 && <Row label="Other" v={rev.other_minor}/>}
          {rev.total_minor === 0 && <Row label="No revenue yet" v={0}/>}
          <Divider/>
          <Row label="Less: Output CGST" v={-tax.cgst_minor}/>
          <Row label="Less: Output SGST" v={-tax.sgst_minor}/>
          {tax.igst_minor > 0 && <Row label="Less: Output IGST" v={-tax.igst_minor}/>}
          <Divider/>
          <Row label="Net revenue (after GST)" v={data.net_revenue_minor} bold/>
        </div>

        <div className="card">
          <h3 className="font-semibold mb-4 flex items-center gap-2">
            <TrendingDown size={16} className="text-accent-bad"/> Expenses by category
          </h3>
          {data.expenses.length === 0 ? (
            <p className="text-xs text-fg-muted">No expenses recorded today.</p>
          ) : data.expenses.map((e) => <Row key={e.category} label={e.category} v={e.amount_minor}/>)}
          <Divider/>
          <Row label="Total expenses" v={exp} bold/>
        </div>
      </div>

      <p className="text-xs text-fg-muted mt-4">
        For monthly / quarterly / yearly P&amp;L → open <b>Reports</b> in the sidebar.
      </p>
    </>
  );
}

// ============================================================================
// EXPENSES TAB
// ============================================================================
function ExpensesTab() {
  const [rows, setRows] = useState<ExpenseDTO[]>([]);
  const [cats, setCats] = useState<ExpenseCategoryDTO[]>([]);
  const [branches, setBranches] = useState<BranchDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);

  async function load() {
    setLoading(true); setErr(null);
    try {
      const [r, c, b] = await Promise.all([
        finance.listExpenses(),
        settings.listExpenseCategories(),
        settings.listBranches(),
      ]);
      setRows(r); setCats(c); setBranches(b);
    } catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  async function onDelete(id: string) {
    if (!confirm('Delete this expense?')) return;
    try { await finance.deleteExpense(id); await load(); }
    catch (e) { alert((e as Error).message); }
  }

  if (loading) return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;

  const total = rows.reduce((s, r) => s + r.amount_minor, 0);
  const catName = (id: string) => cats.find((c) => c.id === id)?.name ?? '—';

  return (
    <div>
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <div>
          <p className="text-sm text-fg-muted">
            {rows.length} expense{rows.length === 1 ? '' : 's'} · Total: <b>{inr(total)}</b>
          </p>
        </div>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          <button className="btn btn-primary" onClick={() => setAddOpen(true)}
            disabled={!cats.length || !branches.length}>
            <Plus size={14}/> Add expense
          </button>
        </div>
      </div>

      {(!cats.length || !branches.length) && (
        <div className="card border-accent-gold/40 bg-accent-gold/10 text-accent-gold text-sm mb-3 flex items-start gap-2">
          <AlertCircle size={14} className="mt-0.5"/>
          <div>
            You need at least one branch and one expense category before adding expenses.
            {!branches.length && <> Add a branch in <b>Settings → Branches</b>.</>}
          </div>
        </div>
      )}

      {err && <ErrorRow text={err}/>}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">No expenses recorded yet.</div>
      ) : (
        <div className="card !p-0 overflow-hidden">
          <table className="hidden w-full text-sm md:table">
            <thead className="bg-bg-raised">
              <tr>
                <th className="text-left p-3">Date</th>
                <th className="text-left p-3">Category</th>
                <th className="text-left p-3">Vendor / invoice</th>
                <th className="text-left p-3">Paid via</th>
                <th className="text-right p-3">Amount</th>
                <th className="text-right p-3 pr-4"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="border-b border-bg-border/60 last:border-0">
                  <td className="p-3 font-mono text-xs">{new Date(r.paid_at).toLocaleDateString('en-IN')}</td>
                  <td className="p-3">{catName(r.category_id)}</td>
                  <td className="p-3 text-fg-muted text-xs">
                    {r.vendor_name || '—'}{r.invoice_no ? ` · ${r.invoice_no}` : ''}
                  </td>
                  <td className="p-3"><span className="chip text-xs">{r.paid_via}</span></td>
                  <td className="p-3 text-right font-mono">{inr(r.amount_minor)}</td>
                  <td className="p-3 text-right pr-4">
                    <button onClick={() => onDelete(r.id)}
                      className="text-fg-muted hover:text-accent-bad">
                      <Trash2 size={14}/>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mobile-card-list md:hidden">
            {rows.map((r) => (
              <div key={r.id} className="mobile-record-card">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="font-semibold">{catName(r.category_id)}</div>
                    <div className="mt-1 text-xs text-fg-muted">
                      {new Date(r.paid_at).toLocaleDateString('en-IN')} · {r.vendor_name || 'No vendor'}
                    </div>
                    {r.invoice_no && (
                      <div className="mt-0.5 truncate font-mono text-[10px] text-fg-muted">
                        Invoice {r.invoice_no}
                      </div>
                    )}
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="font-mono font-semibold">{inr(r.amount_minor)}</div>
                    <span className="chip mt-1 text-[10px]">{r.paid_via}</span>
                  </div>
                </div>
                <div className="mt-3 flex justify-end">
                  <button
                    aria-label="Delete expense"
                    onClick={() => onDelete(r.id)}
                    className="btn btn-ghost !min-h-[32px] !min-w-[32px] !px-2 !py-1 hover:!text-accent-bad"
                  >
                    <Trash2 size={14}/>
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {addOpen && <ExpenseForm cats={cats} branches={branches}
        onClose={() => setAddOpen(false)}
        onSuccess={() => { setAddOpen(false); load(); }}/>}
    </div>
  );
}

function ExpenseForm({
  cats, branches, onClose, onSuccess,
}: {
  cats: ExpenseCategoryDTO[];
  branches: BranchDTO[];
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [form, setForm] = useState({
    branch_id: branches[0]?.id ?? '',
    category_id: cats[0]?.id ?? '',
    amount_rupees: '',
    paid_via: 'cash' as 'cash' | 'card' | 'bank' | 'upi',
    paid_at: new Date().toISOString().slice(0, 16),
    vendor_name: '',
    invoice_no: '',
    note: '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      await finance.createExpense({
        branch_id: form.branch_id,
        category_id: form.category_id,
        amount_minor: Math.round(parseFloat(form.amount_rupees || '0') * 100),
        paid_via: form.paid_via,
        paid_at: new Date(form.paid_at).toISOString(),
        vendor_name: form.vendor_name || undefined,
        invoice_no: form.invoice_no || undefined,
        note: form.note || undefined,
      });
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title="Add expense">
      <form onSubmit={submit} className="space-y-3">
        <div className="grid grid-cols-2 gap-3">
          <Field label="Branch">
            <select className="input" required value={form.branch_id}
              onChange={(e) => setForm({ ...form, branch_id: e.target.value })}>
              {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </Field>
          <Field label="Category">
            <select className="input" required value={form.category_id}
              onChange={(e) => setForm({ ...form, category_id: e.target.value })}>
              {cats.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Amount (₹)">
            <input type="number" min={0} step="0.01" required className="input font-mono text-right"
              value={form.amount_rupees}
              onChange={(e) => setForm({ ...form, amount_rupees: e.target.value })}/>
          </Field>
          <Field label="Paid via">
            <select className="input" value={form.paid_via}
              onChange={(e) => setForm({ ...form, paid_via: e.target.value as typeof form.paid_via })}>
              <option value="cash">Cash</option>
              <option value="upi">UPI</option>
              <option value="card">Card</option>
              <option value="bank">Bank transfer</option>
            </select>
          </Field>
        </div>
        <Field label="Date / time">
          <input type="datetime-local" className="input" required value={form.paid_at}
            onChange={(e) => setForm({ ...form, paid_at: e.target.value })}/>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Vendor">
            <input className="input" value={form.vendor_name}
              onChange={(e) => setForm({ ...form, vendor_name: e.target.value })}/>
          </Field>
          <Field label="Invoice no.">
            <input className="input" value={form.invoice_no}
              onChange={(e) => setForm({ ...form, invoice_no: e.target.value })}/>
          </Field>
        </div>
        <Field label="Note">
          <textarea className="input" rows={2} value={form.note}
            onChange={(e) => setForm({ ...form, note: e.target.value })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            Record expense
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ============================================================================
// PARTNERS TAB
// ============================================================================
function PartnersTab() {
  const [rows, setRows] = useState<PartnerDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [capPartner, setCapPartner] = useState<PartnerDTO | null>(null);
  const [ledgerPartner, setLedgerPartner] = useState<PartnerDTO | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try { setRows(await finance.listPartners()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (loading) return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;

  return (
    <div>
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          {rows.length} partner{rows.length === 1 ? '' : 's'}
        </p>
        <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
          <Plus size={14}/> Add partner
        </button>
      </div>

      {err && <ErrorRow text={err}/>}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {rows.map((p) => (
          <div key={p.id} className="card">
            <div className="flex justify-between items-start mb-3">
              <div>
                <div className="font-bold">{p.name}</div>
                <div className="text-xs text-fg-muted">
                  Share: <b>{p.share_pct}%</b> · Since {new Date(p.joined_at).toLocaleDateString('en-IN')}
                </div>
              </div>
              <div className="text-right">
                <div className="text-xs text-fg-muted">Capital balance</div>
                <div className={`font-bold font-mono ${p.capital_balance_minor < 0 ? 'text-accent-bad' : ''}`}>
                  {inr(p.capital_balance_minor)}
                </div>
              </div>
            </div>
            <div className="flex gap-1 pt-3 border-t border-bg-border/60">
              <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs"
                onClick={() => setCapPartner(p)}>
                <Banknote size={11}/> Record movement
              </button>
              <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs"
                onClick={() => setLedgerPartner(p)}>
                <Wallet size={11}/> Ledger
              </button>
            </div>
          </div>
        ))}
        {!rows.length && <div className="card text-fg-muted text-sm">No partners yet.</div>}
      </div>

      {addOpen && <PartnerForm onClose={() => setAddOpen(false)}
        onSuccess={() => { setAddOpen(false); load(); }}/>}
      {capPartner && <CapitalForm partner={capPartner}
        onClose={() => setCapPartner(null)}
        onSuccess={() => { setCapPartner(null); load(); }}/>}
      {ledgerPartner && <LedgerModal partner={ledgerPartner}
        onClose={() => setLedgerPartner(null)}/>}
    </div>
  );
}

function PartnerForm({ onClose, onSuccess }: { onClose: () => void; onSuccess: () => void }) {
  const [form, setForm] = useState({
    name: '', share_pct: '50', joined_at: new Date().toISOString().slice(0, 10), notes: '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      await finance.createPartner({
        name: form.name.trim(),
        share_pct: parseFloat(form.share_pct),
        joined_at: new Date(form.joined_at).toISOString(),
        notes: form.notes || undefined,
      });
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title="Add partner">
      <form onSubmit={submit} className="space-y-3">
        <Field label="Name"><input className="input" required value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}/></Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Share %">
            <input type="number" min={0.01} max={100} step="0.01" required className="input font-mono"
              value={form.share_pct}
              onChange={(e) => setForm({ ...form, share_pct: e.target.value })}/>
          </Field>
          <Field label="Joined">
            <input type="date" className="input" required value={form.joined_at}
              onChange={(e) => setForm({ ...form, joined_at: e.target.value })}/>
          </Field>
        </div>
        <Field label="Notes">
          <textarea className="input" rows={2} value={form.notes}
            onChange={(e) => setForm({ ...form, notes: e.target.value })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null} Create
          </button>
        </div>
      </form>
    </Modal>
  );
}

function CapitalForm({
  partner, onClose, onSuccess,
}: { partner: PartnerDTO; onClose: () => void; onSuccess: () => void }) {
  const [form, setForm] = useState({
    type: 'invest' as 'invest' | 'withdraw' | 'profit_share',
    amount_rupees: '',
    effective_at: new Date().toISOString().slice(0, 16),
    note: '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      await finance.createCapitalEntry({
        partner_id: partner.id,
        type: form.type,
        amount_minor: Math.round(parseFloat(form.amount_rupees || '0') * 100),
        effective_at: new Date(form.effective_at).toISOString(),
        note: form.note || undefined,
      });
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={`Capital movement — ${partner.name}`}>
      <form onSubmit={submit} className="space-y-3">
        <Field label="Type">
          <select className="input" value={form.type}
            onChange={(e) => setForm({ ...form, type: e.target.value as typeof form.type })}>
            <option value="invest">Investment (+)</option>
            <option value="withdraw">Withdrawal (−)</option>
            <option value="profit_share">Profit share (+)</option>
          </select>
        </Field>
        <Field label="Amount (₹)">
          <input type="number" min={0.01} step="0.01" required className="input font-mono text-right"
            value={form.amount_rupees}
            onChange={(e) => setForm({ ...form, amount_rupees: e.target.value })}/>
        </Field>
        <Field label="Effective date / time">
          <input type="datetime-local" className="input" required value={form.effective_at}
            onChange={(e) => setForm({ ...form, effective_at: e.target.value })}/>
        </Field>
        <Field label="Note">
          <input className="input" value={form.note}
            onChange={(e) => setForm({ ...form, note: e.target.value })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null} Record
          </button>
        </div>
      </form>
    </Modal>
  );
}

function LedgerModal({ partner, onClose }: { partner: PartnerDTO; onClose: () => void }) {
  const [rows, setRows] = useState<CapitalEntryDTO[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    finance.listCapital(partner.id)
      .then(setRows)
      .finally(() => setLoading(false));
  }, [partner.id]);

  return (
    <Modal open onClose={onClose} title={`${partner.name} — capital ledger`} size="lg">
      {loading ? (
        <div className="text-fg-muted flex items-center gap-2"><Loader2 className="animate-spin" size={14}/> Loading…</div>
      ) : !rows.length ? (
        <p className="text-fg-muted text-sm">No movements yet.</p>
      ) : (
        <div className="overflow-x-auto">
        <table className="w-full min-w-[520px] text-sm">
          <thead>
            <tr className="text-left text-fg-muted">
              <th className="py-2">Date</th>
              <th>Type</th>
              <th className="text-right">Amount</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className="border-t border-bg-border/60">
                <td className="py-2 font-mono text-xs">{new Date(r.effective_at).toLocaleDateString('en-IN')}</td>
                <td><span className="chip text-xs">{r.type}</span></td>
                <td className={`text-right font-mono ${r.type === 'withdraw' ? 'text-accent-bad' : ''}`}>
                  {r.type === 'withdraw' ? '−' : '+'}{inr(r.amount_minor)}
                </td>
                <td className="text-xs text-fg-muted">{r.note || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------- helpers
function Row({ label, v, sub, bold }: { label: string; v: number; sub?: string; bold?: boolean }) {
  return (
    <div className={`flex justify-between py-1.5 ${bold ? 'font-bold border-t border-bg-border mt-2 pt-2' : ''}`}>
      <div>
        <div className="text-sm">{label}</div>
        {sub && <div className="text-[10px] text-fg-muted">{sub}</div>}
      </div>
      <div className={`font-mono ${v < 0 ? 'text-accent-bad' : ''}`}>{inr(Math.abs(v))}</div>
    </div>
  );
}
function Divider() { return <div className="h-px bg-bg-border my-1"/>; }
function Stat({ label, value, sub, tone = 'default' }: {
  label: string; value: string; sub?: string; tone?: 'default' | 'good' | 'bad';
}) {
  const color = tone === 'bad' ? 'text-accent-bad' : tone === 'good' ? 'text-accent-good' : 'text-fg';
  return (
    <div className="card">
      <div className="text-xs text-fg-muted uppercase tracking-wider">{label}</div>
      <div className={`text-2xl font-bold mt-1 ${color}`}>{value}</div>
      {sub && <div className="text-xs text-fg-muted mt-1 font-mono">{sub}</div>}
    </div>
  );
}
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
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2 mb-3">
      <AlertCircle size={14}/> {text}
    </div>
  );
}
