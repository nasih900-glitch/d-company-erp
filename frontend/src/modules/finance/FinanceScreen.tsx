/**
 * Finance screen — tabbed.
 *
 *  Overview     today's P&L (demo) + KPIs
 *  Expenses     list, add, delete expenses (live)
 *  Collections  immutable off-POS / legacy daily collection register
 *  Partners     list partners + contributed-capital movements (invest / withdraw)
 *
 * In demo mode the write-oriented tabs show empty states; live mode
 * hits /api/v1/finance/* and /api/v1/settings/*.
 */
import { useCallback, useEffect, useState } from 'react';
import {
  TrendingUp, TrendingDown, Receipt, Users, Plus, Trash2,
  Loader2, AlertCircle, Wallet, Banknote, RefreshCw, BookOpen,
  Ban,
} from 'lucide-react';

import { inr, inrShort } from '@/lib/inr';
import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import {
  finance, settings, reports,
  type ExpenseDTO, type PartnerDTO, type BranchDTO, type ExpenseCategoryDTO,
  type CapitalEntryDTO, type ReportDataDTO, type PartnerPLReportDTO,
  type BusinessMetricsDTO, type DistributableProfitReportDTO,
} from '@/lib/erp-api';
import {
  DEFAULT_BUSINESS_TIMEZONE,
  dateISOInTimeZone,
  rupeesToMinor,
} from '@/lib/manual-collections';
import Modal from '@/components/ui/Modal';
import ManualCollectionsTab from './ManualCollectionsTab';

type Tab = 'overview' | 'expenses' | 'collections' | 'partners';

export default function FinanceScreen() {
  const [tab, setTab] = useState<Tab>('overview');
  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Finance</h2>
        <p className="text-fg-muted text-sm">
          P&amp;L · expenses · manual collections · partner capital
        </p>
      </header>

      <div className="scroll-strip flex gap-1 mb-6 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'overview'} onClick={() => setTab('overview')}>
          <TrendingUp size={14}/> Overview
        </TabBtn>
        <TabBtn active={tab === 'expenses'} onClick={() => setTab('expenses')}>
          <Receipt size={14}/> Expenses
        </TabBtn>
        <TabBtn active={tab === 'collections'} onClick={() => setTab('collections')}>
          <BookOpen size={14}/> Manual collections
        </TabBtn>
        <TabBtn active={tab === 'partners'} onClick={() => setTab('partners')}>
          <Users size={14}/> Partners
        </TabBtn>
      </div>

      {tab === 'overview' && <OverviewTab/>}
      {tab === 'expenses' && <ExpensesTab/>}
      {tab === 'collections' && <ManualCollectionsTab/>}
      {tab === 'partners' && <PartnersTab/>}
    </div>
  );
}

function OverviewTab() {
  const [data, setData] = useState<ReportDataDTO | null>(null);
  const [metrics, setMetrics] = useState<BusinessMetricsDTO | null>(null);
  const [gstRegistered, setGstRegistered] = useState(true);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try {
      const [company, businessMetrics] = await Promise.all([
        settings.getCompany(),
        finance.businessMetrics(),
      ]);
      const daily = await reports.daily(dateISOInTimeZone(
        new Date(),
        company.timezone || DEFAULT_BUSINESS_TIMEZONE,
      ));
      setData(daily);
      setMetrics(businessMetrics);
      setGstRegistered(company.gst_registration_type !== 'unregistered');
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
  const manualCollections = data.manual_collections_minor;
  const empty = data.orders_count === 0 && data.tickets_count === 0
    && manualCollections === 0 && exp === 0;

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

      {!gstRegistered && (
        <div className="card mb-4 border-bg-border bg-bg-raised text-sm text-fg-muted">
          <b>Not GST-registered.</b> The GST fields below are inactive placeholders for when
          you register — they are not live tax figures, and no GST is actually being charged
          or collected today.
        </div>
      )}

      {manualCollections > 0 && (
        <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm">
          <b>{inr(manualCollections)} of today's revenue is unitemized manual collection.</b>{' '}
          It is included in P&amp;L and payment movement, but has no POS order, tax invoice,
          table ticket, gaming session, item mix, or automatic COGS
          {manualCollections > 0 ? ' — so Gross profit below runs a little rich on days with a big manual-collection share, since none of it carries any COGS.' : '.'}
        </div>
      )}

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 md:gap-4 mb-6">
        <Stat label="Revenue (gross)"   value={inrShort(rev.total_minor)} sub={inr(rev.total_minor)} tone="good"/>
        {gstRegistered && <Stat label="GST collected"     value={inrShort(totalGst)} sub="CGST + SGST + IGST"/>}
        <Stat label="Expenses"          value={inrShort(exp)} sub={inr(exp)} tone="bad"/>
        <Stat label="Operating profit (today)" value={inrShort(net)} sub={`${inr(net)} · before depreciation`} tone={net >= 0 ? 'good' : 'bad'}/>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3 md:gap-4">
        <div className="card">
          <h3 className="font-semibold mb-4 flex items-center gap-2">
            <TrendingUp size={16} className="text-accent-good"/> Revenue
          </h3>
          {rev.food_minor > 0 && <Row label={gstRegistered ? 'Food (5% GST)' : 'Food'} v={rev.food_minor}/>}
          {rev.gaming_minor > 0 && <Row label={gstRegistered ? 'Gaming (18% GST)' : 'Gaming'} v={rev.gaming_minor}/>}
          {isAppStoreAllowedType('hookah') && rev.hookah_minor > 0 && <Row label={gstRegistered ? 'Shisha (18% GST)' : 'Shisha'} v={rev.hookah_minor}/>}
          {rev.event_tickets_minor > 0 && <Row label={gstRegistered ? 'Event tickets (18% GST)' : 'Event tickets'} v={rev.event_tickets_minor}/>}
          {rev.delivery_aggregator_minor > 0 && (
            <Row label={gstRegistered ? 'Delivery aggregator (§9(5))' : 'Delivery aggregator'} v={rev.delivery_aggregator_minor} sub={gstRegistered ? 'zero GST on our invoice' : undefined}/>
          )}
          {rev.manual_collections_minor > 0 && (
            <Row label="Manual collections (unitemized)" v={rev.manual_collections_minor}
              sub="off-POS / daily legacy total"/>
          )}
          {rev.other_minor > 0 && <Row label="Other" v={rev.other_minor}/>}
          {rev.total_minor === 0 && <Row label="No revenue yet" v={0}/>}
          {rev.discounts_and_points_redeemed_minor > 0 && (
            <Row label="Less: discounts & points redeemed" v={-rev.discounts_and_points_redeemed_minor}/>
          )}
          {gstRegistered && (
            <>
              <Divider/>
              {tax.cgst_minor > 0 && <Row label="Less: Output CGST" v={-tax.cgst_minor}/>}
              {tax.sgst_minor > 0 && <Row label="Less: Output SGST" v={-tax.sgst_minor}/>}
              {tax.igst_minor > 0 && <Row label="Less: Output IGST" v={-tax.igst_minor}/>}
              <Divider/>
              <Row label="Net revenue (after GST)" v={data.net_revenue_minor} bold/>
            </>
          )}
          <Divider/>
          <Row label="Less: cost of goods sold" v={-data.cogs_minor} sub="what the food/drinks/items you sold actually cost you"/>
          <Row label="Gross profit" v={data.gross_profit_minor} bold sub={manualCollections > 0 ? 'includes manual collections at 0% COGS' : undefined}/>
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

      <div className="card mt-3 md:mt-4">
        <Row label="Gross profit" v={data.gross_profit_minor}/>
        <Row label="Less: total expenses" v={-exp}/>
        <Divider/>
        <Row label="Operating profit (today)" v={net} bold sub="before equipment depreciation"/>
      </div>

      <p className="text-xs text-fg-muted mt-4">
        For monthly / quarterly / yearly P&amp;L → open <b>Reports</b> in the sidebar.
      </p>

      {metrics && (
        <div className="mt-6">
          <h3 className="font-semibold mb-3 flex items-center gap-2 text-sm text-fg-muted">
            Business metrics · {new Date(metrics.period_start).toLocaleDateString('en-IN', { month: 'short', day: 'numeric' })}
            {' – '}{new Date(metrics.period_end).toLocaleDateString('en-IN', { month: 'short', day: 'numeric' })}
          </h3>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3 md:gap-4">
            <Stat label="Avg order value (this period)" value={inrShort(metrics.aov_minor)}
              sub={`${metrics.orders_count} order${metrics.orders_count === 1 ? '' : 's'} this period`}/>
            <Stat label="MRR" value={inrShort(metrics.mrr_minor)}
              sub={`${metrics.active_members_count} active member${metrics.active_members_count === 1 ? '' : 's'}`}/>
            <Stat label="ARR" value={inrShort(metrics.arr_minor)} sub="MRR × 12"/>
            <Stat label="Customer LTV (all-time)" value={inrShort(metrics.ltv_minor)}
              sub={`avg across ${metrics.customers_count} customer${metrics.customers_count === 1 ? '' : 's'}, all-time`}/>
            <Stat label="CAC" value={metrics.cac_minor === null ? '—' : inrShort(metrics.cac_minor)}
              sub={metrics.cac_minor === null
                ? 'no new customers this period'
                : `₹${(metrics.marketing_spend_minor / 100).toFixed(0)} marketing ÷ ${metrics.new_customers_count} new`}/>
            <Stat label="Burn rate" value={inrShort(metrics.burn_rate_minor)}
              tone={metrics.burn_rate_minor > 0 ? 'bad' : 'good'}
              sub={metrics.burn_rate_minor > 0 ? 'lost money this period, after cost of goods sold and expenses' : 'profitable this period'}/>
          </div>
          <p className="text-xs text-fg-muted mt-3">
            These are the metrics that actually fit a single-location, self-funded business —
            not SaaS/VC fundraising metrics like Rule of 40 or TAM/SAM/SOM, which don't apply here.
          </p>
        </div>
      )}
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
  const [split, setSplit] = useState<PartnerPLReportDTO | null>(null);
  const [distributable, setDistributable] = useState<DistributableProfitReportDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [capPartner, setCapPartner] = useState<PartnerDTO | null>(null);
  const [ledgerPartner, setLedgerPartner] = useState<PartnerDTO | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try {
      const [partners, profitSplit, distributableProfit] = await Promise.all([
        finance.listPartners(),
        finance.partnerProfitSplit(),
        finance.distributableProfit(),
      ]);
      setRows(partners);
      setSplit(profitSplit);
      setDistributable(distributableProfit);
    }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  const shareByPartnerId = new Map(split?.partners.map((p) => [p.partner_id, p.profit_share_minor]) ?? []);
  const distributableByPartnerId = new Map(
    distributable?.partners.map((p) => [p.partner_id, p]) ?? []
  );

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

      {distributable && (
        <div className="card mb-3 border-accent-good/40">
          <div className="flex justify-between items-start flex-wrap gap-2 mb-3">
            <div>
              <div className="font-bold text-sm">Safe to distribute right now</div>
              <div className="text-[11px] text-fg-muted mt-0.5">
                All-time profit, minus everything ever withdrawn, minus a {distributable.reserve_months}-month
                safety buffer — capped by actual cash on hand, not just what the books say.
                Before equipment depreciation — this figure doesn't yet charge for gaming/kitchen
                equipment wearing out, so treat it as an upper bound, not the final word.
              </div>
            </div>
            <div className={`text-2xl font-bold font-mono ${distributable.safe_to_distribute_minor > 0 ? 'text-accent-good' : ''}`}>
              {inr(distributable.safe_to_distribute_minor)}
            </div>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs pt-3 border-t border-bg-border/60">
            <div>
              <div className="text-fg-muted">Lifetime profit (pre-depreciation)</div>
              <div className={`font-mono font-semibold ${distributable.lifetime_net_profit_minor < 0 ? 'text-accent-bad' : ''}`}>
                {inr(distributable.lifetime_net_profit_minor)}
              </div>
            </div>
            <div>
              <div className="text-fg-muted">Already withdrawn</div>
              <div className="font-mono font-semibold">{inr(distributable.lifetime_withdrawn_minor)}</div>
            </div>
            <div>
              <div className="text-fg-muted">Reserve kept back</div>
              <div className="font-mono font-semibold">{inr(distributable.reserve_minor)}</div>
            </div>
            <div>
              <div className="text-fg-muted">Cash on hand</div>
              <div className="font-mono font-semibold">{inr(distributable.liquid_cash_minor)}</div>
            </div>
          </div>
          {distributable.cash_based_capacity_minor < distributable.profit_based_capacity_minor && (
            <p className="text-[11px] text-accent-gold mt-3">
              Limited by cash on hand, not by profit — some of what you've earned is currently tied up in stock or equipment.
            </p>
          )}
        </div>
      )}

      {split && (
        <div className="card mb-3 flex justify-between items-center flex-wrap gap-2">
          <div className="text-sm text-fg-muted">
            Operating profit this month ({new Date(split.period_start).toLocaleDateString('en-IN')}
            {' – '}{new Date(split.period_end).toLocaleDateString('en-IN')}), split by ownership share
          </div>
          <div className={`font-bold font-mono ${split.net_profit_minor < 0 ? 'text-accent-bad' : ''}`}>
            {inr(split.net_profit_minor)}
          </div>
        </div>
      )}

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
                <div className="text-xs text-fg-muted">Active capital balance</div>
                <div className={`font-bold font-mono ${p.capital_balance_minor < 0 ? 'text-accent-bad' : ''}`}>
                  {inr(p.capital_balance_minor)}
                </div>
                <div className="text-[10px] text-fg-muted">Voided entries excluded</div>
              </div>
            </div>
            {distributableByPartnerId.has(p.id) && (
              <div className="flex justify-between items-center text-xs mb-3 pb-3 border-b border-bg-border/60">
                <span className="text-fg-muted">
                  Safe to take out right now
                  <span className="block text-[10px]">
                    Withdrawn to date: {inr(distributableByPartnerId.get(p.id)?.lifetime_withdrawn_minor ?? 0)}
                  </span>
                </span>
                <span className={`font-bold font-mono ${(distributableByPartnerId.get(p.id)?.distributable_share_minor ?? 0) > 0 ? 'text-accent-good' : ''}`}>
                  {inr(distributableByPartnerId.get(p.id)?.distributable_share_minor ?? 0)}
                </span>
              </div>
            )}
            {shareByPartnerId.has(p.id) && (
              <div className="flex justify-between items-center text-xs mb-3 pb-3 border-b border-bg-border/60">
                <span className="text-fg-muted">
                  This month's indicative profit share
                  <span className="block text-[10px]">Informational · not added to capital</span>
                </span>
                <span className={`font-bold font-mono ${(shareByPartnerId.get(p.id) ?? 0) < 0 ? 'text-accent-bad' : 'text-accent-good'}`}>
                  {inr(shareByPartnerId.get(p.id) ?? 0)}
                </span>
              </div>
            )}
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
        onClose={() => setLedgerPartner(null)} onChanged={load}/>}
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
    type: 'invest' as 'invest' | 'withdraw',
    amount_rupees: '',
    effective_at: dateTimeLocalNow(),
    settlement_account: 'bank' as 'cash' | 'bank' | 'upi',
    source_ref: '',
    note: '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const amountMinor = rupeesToMinor(form.amount_rupees);
    if (amountMinor === null) {
      setErr('Enter an amount greater than ₹0 with no more than two decimal places.');
      return;
    }
    const sourceRef = form.source_ref.trim();
    if (!sourceRef) {
      setErr('Enter the unique bank, UPI, or cash-voucher reference that proves this movement.');
      return;
    }
    setBusy(true); setErr(null);
    try {
      await finance.createCapitalEntry({
        partner_id: partner.id,
        type: form.type,
        amount_minor: amountMinor,
        effective_at: new Date(form.effective_at).toISOString(),
        settlement_account: form.settlement_account,
        source_ref: sourceRef,
        note: form.note.trim() || undefined,
      });
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={`Capital movement — ${partner.name}`}>
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-fg-muted">
          Investment records money contributed by this partner. Withdrawal records capital repaid.
          Profit share is calculated separately and does not change contributed capital.
        </p>
        <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-xs">
          Entries are immutable. Use a unique evidence reference so a retry cannot create a
          duplicate; mistakes must be voided with a reason from the ledger.
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="Movement type">
            <select className="input" value={form.type}
              onChange={(e) => setForm({ ...form, type: e.target.value as typeof form.type })}>
              <option value="invest">Investment (+)</option>
              <option value="withdraw">Capital repayment (−)</option>
            </select>
          </Field>
          <Field label="Settlement account">
            <select className="input" value={form.settlement_account}
              onChange={(e) => setForm({
                ...form,
                settlement_account: e.target.value as typeof form.settlement_account,
              })}>
              <option value="bank">Bank transfer</option>
              <option value="upi">UPI</option>
              <option value="cash">Cash</option>
            </select>
          </Field>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="Amount (₹)">
            <input type="number" min={0.01} step="0.01" required inputMode="decimal"
              className="input font-mono text-right" value={form.amount_rupees}
              onChange={(e) => setForm({ ...form, amount_rupees: e.target.value })}/>
          </Field>
          <Field label="Effective date / time">
            <input type="datetime-local" className="input" required value={form.effective_at}
              onChange={(e) => setForm({ ...form, effective_at: e.target.value })}/>
          </Field>
        </div>
        <Field label="Evidence reference">
          <input className="input" required minLength={1} maxLength={160}
            placeholder="Bank UTR, UPI transaction ID, or cash voucher no."
            value={form.source_ref}
            onChange={(e) => setForm({ ...form, source_ref: e.target.value })}/>
          <span className="mt-1 block text-[10px] text-fg-muted">
            Must be unique. On a network retry, submit the same reference instead of inventing a new one.
          </span>
        </Field>
        <Field label="Note (optional)">
          <textarea className="input" rows={2} maxLength={500} value={form.note}
            onChange={(e) => setForm({ ...form, note: e.target.value })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null} Record
          </button>
        </div>
      </form>
    </Modal>
  );
}

function LedgerModal({
  partner,
  onClose,
  onChanged,
}: {
  partner: PartnerDTO;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [rows, setRows] = useState<CapitalEntryDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [voiding, setVoiding] = useState<CapitalEntryDTO | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      setRows(await finance.listCapital(partner.id, true));
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setLoading(false);
    }
  }, [partner.id]);

  useEffect(() => { void load(); }, [load]);

  const activeBalance = rows.reduce((sum, row) => {
    if (row.is_voided) return sum;
    return sum + (row.type === 'withdraw' ? -row.amount_minor : row.amount_minor);
  }, 0);
  const activeCount = rows.filter((row) => !row.is_voided).length;

  return (
    <>
      <Modal open onClose={onClose} title={`${partner.name} — capital ledger`} size="lg">
        <div className="mb-3 flex items-center justify-between gap-3 rounded-xl border border-bg-border bg-bg-raised p-3">
          <div>
            <div className="text-xs text-fg-muted">Active investments minus capital repayments</div>
            <div className="text-[10px] text-fg-muted">{activeCount} active · voided entries excluded</div>
          </div>
          <div className={`font-mono text-lg font-bold ${activeBalance < 0 ? 'text-accent-bad' : ''}`}>
            {inr(activeBalance)}
          </div>
        </div>
        {err && <ErrorRow text={err}/>}
        {loading ? (
          <div className="text-fg-muted flex items-center gap-2"><Loader2 className="animate-spin" size={14}/> Loading…</div>
        ) : !rows.length ? (
          <p className="text-fg-muted text-sm">No investment or capital-repayment movements yet.</p>
        ) : (
          <>
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full min-w-[850px] text-sm">
                <thead>
                  <tr className="text-left text-fg-muted">
                    <th className="py-2">Effective</th>
                    <th>Movement / account</th>
                    <th>Evidence / note</th>
                    <th>Recorded by</th>
                    <th className="text-right">Amount</th>
                    <th className="text-right">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <CapitalLedgerRow key={row.id} row={row} onVoid={() => setVoiding(row)}/>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="space-y-2 md:hidden">
              {rows.map((row) => (
                <CapitalLedgerCard key={row.id} row={row} onVoid={() => setVoiding(row)}/>
              ))}
            </div>
          </>
        )}
      </Modal>
      {voiding && (
        <VoidCapitalEntryForm
          row={voiding}
          onClose={() => setVoiding(null)}
          onSuccess={async () => {
            setVoiding(null);
            await load();
            onChanged();
          }}
        />
      )}
    </>
  );
}

function CapitalLedgerRow({ row, onVoid }: { row: CapitalEntryDTO; onVoid: () => void }) {
  return (
    <tr className={`border-t border-bg-border/60 ${row.is_voided ? 'opacity-60' : ''}`}>
      <td className="py-3 pr-3 font-mono text-xs">
        {new Date(row.effective_at).toLocaleString('en-IN')}
      </td>
      <td className="pr-3">
        <span className="chip text-xs">{row.type === 'invest' ? 'Investment' : 'Capital repayment'}</span>
        <div className="mt-1 text-[10px] text-fg-muted">{capitalAccountLabel(row.settlement_account)}</div>
      </td>
      <td className="max-w-xs pr-3 text-xs">
        <div className={row.is_voided ? 'line-through' : ''}>{row.source_ref || 'Migrated record — no reference'}</div>
        {row.note && <div className="mt-1 text-fg-muted break-words">{row.note}</div>}
        {row.void_reason && <div className="mt-1 text-accent-bad break-words">Void: {row.void_reason}</div>}
      </td>
      <td className="pr-3 text-xs text-fg-muted">
        <div>{capitalActorLabel(row.created_by_name, row.created_by)}</div>
        <div>{new Date(row.created_at).toLocaleString('en-IN')}</div>
        {row.is_voided && row.voided_at && (
          <div className="mt-1">
            Voided by {capitalActorLabel(row.voided_by_name, row.voided_by)} ·{' '}
            {new Date(row.voided_at).toLocaleString('en-IN')}
          </div>
        )}
      </td>
      <td className={`pr-3 text-right font-mono font-semibold ${row.is_voided ? 'line-through' : row.type === 'withdraw' ? 'text-accent-bad' : ''}`}>
        {row.type === 'withdraw' ? '−' : '+'}{inr(row.amount_minor)}
      </td>
      <td className="text-right">
        {row.is_voided ? (
          <span className="chip border-accent-bad/40 text-accent-bad text-[10px]">Voided</span>
        ) : (
          <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs hover:!text-accent-bad"
            onClick={onVoid} aria-label={`Void ${row.source_ref || 'capital entry'}`}>
            <Ban size={12}/> Void
          </button>
        )}
      </td>
    </tr>
  );
}

function CapitalLedgerCard({ row, onVoid }: { row: CapitalEntryDTO; onVoid: () => void }) {
  return (
    <div className={`rounded-xl border border-bg-border p-3 ${row.is_voided ? 'opacity-60' : ''}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className={`font-semibold break-words ${row.is_voided ? 'line-through' : ''}`}>
            {row.source_ref || 'Migrated record — no reference'}
          </div>
          <div className="mt-1 text-xs text-fg-muted">
            {row.type === 'invest' ? 'Investment' : 'Capital repayment'} · {capitalAccountLabel(row.settlement_account)}
          </div>
        </div>
        <div className={`shrink-0 font-mono font-semibold ${row.is_voided ? 'line-through' : row.type === 'withdraw' ? 'text-accent-bad' : ''}`}>
          {row.type === 'withdraw' ? '−' : '+'}{inr(row.amount_minor)}
        </div>
      </div>
      {row.note && <div className="mt-2 text-xs text-fg-muted break-words">{row.note}</div>}
      {row.void_reason && <div className="mt-2 text-xs text-accent-bad">Void: {row.void_reason}</div>}
      <div className="mt-3 border-t border-bg-border/60 pt-3 text-[10px] text-fg-muted">
        Effective {new Date(row.effective_at).toLocaleString('en-IN')} · recorded by{' '}
        {capitalActorLabel(row.created_by_name, row.created_by)}
      </div>
      <div className="mt-2 flex justify-end">
        {row.is_voided ? (
          <span className="chip border-accent-bad/40 text-accent-bad text-[10px]">Voided</span>
        ) : (
          <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs hover:!text-accent-bad"
            onClick={onVoid}>
            <Ban size={12}/> Void
          </button>
        )}
      </div>
    </div>
  );
}

function VoidCapitalEntryForm({
  row,
  onClose,
  onSuccess,
}: {
  row: CapitalEntryDTO;
  onClose: () => void;
  onSuccess: () => void | Promise<void>;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const trimmed = reason.trim();
    if (trimmed.length < 3) {
      setErr('Enter a clear void reason of at least 3 characters.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await finance.voidCapitalEntry(row.id, trimmed);
      await onSuccess();
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={busy ? () => undefined : onClose} title="Void capital movement">
      <form onSubmit={submit} className="space-y-3">
        <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm">
          <b>{row.type === 'invest' ? 'Investment' : 'Capital repayment'} · {inr(row.amount_minor)}</b>
          <div className="mt-1 text-xs text-fg-muted">{row.source_ref || 'Migrated record — no reference'}</div>
          <div className="mt-2 text-xs">
            The entry stays in the audit history, but will be excluded from the active capital balance.
          </div>
        </div>
        <Field label="Void reason">
          <textarea className="input" required minLength={3} maxLength={500} rows={3}
            autoFocus value={reason}
            placeholder="Explain exactly why this movement is wrong"
            onChange={(event) => setReason(event.target.value)}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-danger" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Ban size={14}/>}
            Void movement
          </button>
        </div>
      </form>
    </Modal>
  );
}

function capitalAccountLabel(account: CapitalEntryDTO['settlement_account']): string {
  if (account === 'bank') return 'Bank transfer';
  if (account === 'upi') return 'UPI';
  if (account === 'cash') return 'Cash';
  return 'Imported historical record';
}

function capitalActorLabel(name: string | null, id: string | null): string {
  return name || (id ? `User ${id.slice(0, 8)}` : 'Legacy import');
}

function dateTimeLocalNow(): string {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
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
