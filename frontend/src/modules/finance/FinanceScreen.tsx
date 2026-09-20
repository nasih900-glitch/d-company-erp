/**
 * Finance screen — tabbed.
 *
 *  Overview     today's P&L (demo) + KPIs
 *  Expenses     list, add, delete expenses (live)
 *  Collections  immutable off-POS / legacy daily collection register
 *  Partners     list partners + contributed-capital movements (invest / withdraw)
 *  Assets       fixed asset register (equipment) — straight-line depreciated
 *
 * In demo mode the write-oriented tabs show empty states; live mode
 * hits /api/v1/finance/* and /api/v1/settings/*.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  TrendingUp, TrendingDown, Receipt, Users, Plus, Trash2,
  Loader2, AlertCircle, Wallet, Banknote, RefreshCw, BookOpen,
  Ban, Package, HandCoins, Camera, Upload, FileText, Eye,
} from 'lucide-react';

import { inr, inrShort } from '@/lib/inr';
import { FINANCE_ACTION_FEEDBACK } from '@/lib/action-feedback';
import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import { parseRupeesToMinor } from '@/lib/money-input';
import {
  GAMING_CENTRE_FEATURES,
  profileDeferredMoneyLabel,
  profileMembershipMoneyLabel,
} from '@/lib/product-profile';
import {
  finance, insights, pos, settings, reports, shifts,
  type ExpenseDTO, type PartnerDTO, type BranchReferenceDTO, type ExpenseCategoryDTO,
  type CapitalEntryDTO, type ReportDataDTO, type PartnerPLReportDTO,
  type BusinessMetricsDTO, type DistributableProfitReportDTO,
  type CostingCoverageDTO, type ExpenseReceiptDTO, type ExpenseReceiptSource,
  type ExpenseReceiptStatus, type ShiftDTO,
} from '@/lib/erp-api';
import { rupeesToMinor } from '@/lib/manual-collections';
import { PromptModal } from '@/components/ui/ConfirmDialog';
import Modal from '@/components/ui/Modal';
import { useNotifications } from '@/components/ui/Notifications';
import { useRealtimeRefresh } from '@/hooks/useRealtimeRefresh';
import { useLatestRequest } from '@/hooks/useLatestRequest';
import AssetsTab from './AssetsTab';
import ManualCollectionsTab from './ManualCollectionsTab';
import TipPayoutsTab from './TipPayoutsTab';
import { SkeletonCard } from '@/components/ui/Skeleton';
import {
  allocationIsAuthoritative,
  allocationUnavailableReason,
  optionalCostingCoverage,
  optionalPartnerAllocation,
  PARTNER_CASH_UNVERIFIED,
  verifiedDistributionCap,
  verifiedPartnerDistribution,
  verifiedPartnerProfitShare,
  verifiedSpendableCash,
} from './partner-allocation';
import {
  DEFAULT_EXPENSE_PAYMENT_RAIL,
  EXPENSE_CASH_DRAWER_GUIDANCE,
  EXPENSE_PAYMENT_OPTIONS,
  type ExpensePaymentOption,
} from './expense-payment-policy';
import {
  EXPENSE_RECEIPT_ACCEPT,
  EXPENSE_RECEIPT_STATUS_LABEL,
  expenseReceiptFileError,
  expenseReceiptReviewError,
  expenseReceiptReviewStatuses,
  expenseReceiptSummary,
  normalizeExpenseReceiptFile,
  pickedExpenseReceiptSource,
  persistExpenseWithOptionalReceipt,
} from './expense-receipts';

type Tab = 'overview' | 'expenses' | 'collections' | 'tips' | 'partners' | 'assets';

const TAX_COMPLIANCE_UI_ENABLED = GAMING_CENTRE_FEATURES.taxCompliance;

export default function FinanceScreen() {
  const [tab, setTab] = useState<Tab>('overview');
  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Finance</h2>
        <p className="text-fg-muted text-sm">
          P&amp;L · expenses · manual collections · tip payouts · partner capital · fixed assets
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
        <TabBtn active={tab === 'tips'} onClick={() => setTab('tips')}>
          <HandCoins size={14}/> Tip payouts
        </TabBtn>
        <TabBtn active={tab === 'partners'} onClick={() => setTab('partners')}>
          <Users size={14}/> Partners
        </TabBtn>
        <TabBtn active={tab === 'assets'} onClick={() => setTab('assets')}>
          <Package size={14}/> Fixed assets
        </TabBtn>
      </div>

      {tab === 'overview' && <OverviewTab/>}
      {tab === 'expenses' && <ExpensesTab/>}
      {tab === 'collections' && <ManualCollectionsTab/>}
      {tab === 'tips' && <TipPayoutsTab/>}
      {tab === 'partners' && <PartnersTab/>}
      {tab === 'assets' && <AssetsTab/>}
    </div>
  );
}

function OverviewTab() {
  const requests = useLatestRequest();
  const [data, setData] = useState<ReportDataDTO | null>(null);
  const [metrics, setMetrics] = useState<BusinessMetricsDTO | null>(null);
  const [costing, setCosting] = useState<CostingCoverageDTO | null>(null);
  const [gstRegistered, setGstRegistered] = useState(true);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async (silent = false) => {
    const isCurrent = requests.begin();
    if (!silent) setLoading(true);
    setRefreshing(true);
    try {
      const [receiptIdentity, daily, businessMetrics, costingCoverage] = await Promise.all([
        TAX_COMPLIANCE_UI_ENABLED ? pos.receiptBusiness().catch(() => null) : Promise.resolve(null),
        reports.daily(),
        finance.businessMetrics(),
        optionalCostingCoverage(() => insights.costingCoverage()),
      ]);
      if (!isCurrent()) return;
      setErr(null);
      setData(daily);
      setMetrics(businessMetrics);
      setCosting(costingCoverage);
      setGstRegistered(receiptIdentity?.gst_registration_type !== 'unregistered');
    } catch (e) { if (isCurrent()) setErr((e as Error).message); }
    finally { if (isCurrent()) { setLoading(false); setRefreshing(false); } }
  }, [requests]);
  useEffect(() => { void load(); }, [load]);
  useRealtimeRefresh({
    resources: ['finance', 'inventory'],
    refresh: () => load(true),
  });

  if (loading && !data) {
    return <SkeletonCard />;
  }
  if (!data) return (
    <div className="card space-y-3">
      <ErrorRow text={err ?? 'Finance figures are unavailable. Retry to load the latest totals.'}/>
      <button className="btn btn-primary" onClick={() => void load()} disabled={refreshing}>Retry finance</button>
    </div>
  );

  const rev = data.revenue;
  const tax = data.tax_collected;
  const exp = data.expense_total_minor;
  const net = data.net_profit_minor;
  const totalGst = tax.total_minor;
  const manualCollections = data.manual_collections_minor;
  const membershipRevenueLabel = profileMembershipMoneyLabel(
    'revenue',
    rev.memberships_minor,
  );
  const eventRevenueLabel = profileDeferredMoneyLabel('eventRevenue', rev.event_tickets_minor);
  const deliveryRevenueLabel = profileDeferredMoneyLabel('deliveryRevenue', rev.delivery_aggregator_minor);
  const taxCollectedLabel = profileDeferredMoneyLabel('taxCollected', totalGst);
  const empty = data.orders_count === 0 && data.tickets_count === 0
    && manualCollections === 0 && exp === 0;

  return (
    <>
      <div className="flex items-baseline justify-between mb-3 flex-wrap gap-2">
        <p className="text-xs text-fg-muted">
          Today · {data.orders_count} order{data.orders_count === 1 ? '' : 's'} · {data.tickets_count} ticket{data.tickets_count === 1 ? '' : 's'} · Avg {inr(data.avg_ticket_minor)}
        </p>
        <button className="btn btn-ghost text-xs" aria-label="Refresh finance" onClick={() => void load()} disabled={refreshing}>
          <RefreshCw size={14} className={refreshing ? 'animate-spin' : ''}/>
          {refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
      {err && (
        <div className="mb-4 rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm" role="alert">
          <p className="font-medium text-accent-bad">Finance could not refresh. These are the last verified figures.</p>
          <p className="mt-1 text-fg-muted">{err} Check the connection and use Refresh before relying on these totals.</p>
        </div>
      )}
      <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm">
        <b>Management cash/receipt-basis P&amp;L.</b>{' '}
        This operational view is not an accrual or statutory set of accounts.
      </div>

      {empty && (
        <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-accent-gold text-sm">
          <b>No activity today yet.</b> Take an order from <b>POS</b> or record an expense from the Expenses tab to see real numbers here.
        </div>
      )}

      {TAX_COMPLIANCE_UI_ENABLED && !gstRegistered && (
        <div className="card mb-4 border-bg-border bg-bg-raised text-sm text-fg-muted">
          <b>Not GST-registered.</b> The GST fields below are inactive placeholders for when
          you register — they are not live tax figures, and no GST is actually being charged
          or collected today.
        </div>
      )}

      {costing?.is_complete !== true && (
        <div className="card mb-4 border-accent-bad/50 bg-accent-bad/10 text-sm">
          <div className="flex items-start gap-2">
            <AlertCircle size={17} className="text-accent-bad mt-0.5 shrink-0"/>
            <div>
              <b>{costing ? `Profit is provisional: ${costing.incomplete_item_count} sellable item${costing.incomplete_item_count === 1 ? '' : 's'} lack complete costing.` : 'Profit is provisional: product costing could not be verified.'}</b>{' '}
              Automatic COGS excludes unknown costs, so gross and operating profit may be overstated.
              Fix the listed items in <b>Menu → Recipe</b> and receive stock with a real unit cost in <b>Inventory</b> before distributing profit.
              <div className="mt-2 text-xs text-fg-muted">
                {costing?.issues.slice(0, 4).map((issue) => issue.name).join(' · ')}
                {costing && costing.issues.length > 4 ? ` · +${costing.issues.length - 4} more` : ''}
              </div>
            </div>
          </div>
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
        {taxCollectedLabel && (
          <Stat
            label={taxCollectedLabel}
            value={inrShort(totalGst)}
            sub={TAX_COMPLIANCE_UI_ENABLED ? 'CGST + SGST + IGST' : 'retained from historical records'}
          />
        )}
        <Stat label="Expenses"          value={inrShort(exp)} sub={inr(exp)} tone="bad"/>
        <Stat label={costing?.is_complete === true ? 'Operating profit (today)' : 'Provisional operating profit'} value={inrShort(net)} sub={`${inr(net)} · after equipment depreciation`} tone={net < 0 ? 'bad' : costing?.is_complete === true ? 'good' : undefined}/>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3 md:gap-4">
        <div className="card">
          <h3 className="font-semibold mb-4 flex items-center gap-2">
            <TrendingUp size={16} className="text-accent-good"/> Revenue
          </h3>
          {rev.food_minor > 0 && <Row label={TAX_COMPLIANCE_UI_ENABLED && gstRegistered ? 'Food (5% GST)' : 'Food / drinks / snacks'} v={rev.food_minor}/>}
          {rev.gaming_minor > 0 && <Row label={TAX_COMPLIANCE_UI_ENABLED && gstRegistered ? 'Gaming (18% GST)' : 'Gaming'} v={rev.gaming_minor}/>}
          {isAppStoreAllowedType('hookah') && rev.hookah_minor > 0 && <Row label={TAX_COMPLIANCE_UI_ENABLED && gstRegistered ? 'Shisha (18% GST)' : 'Shisha'} v={rev.hookah_minor}/>}
          {eventRevenueLabel && <Row label={eventRevenueLabel} v={rev.event_tickets_minor}/>}
          {membershipRevenueLabel && (
            <Row label={membershipRevenueLabel} v={rev.memberships_minor}/>
          )}
          {deliveryRevenueLabel && (
            <Row
              label={deliveryRevenueLabel}
              v={rev.delivery_aggregator_minor}
              sub={TAX_COMPLIANCE_UI_ENABLED && gstRegistered ? 'zero GST on our invoice' : undefined}
            />
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
          {rev.rounding_income_minor > 0 && (
            <Row label="Invoice round-up" v={rev.rounding_income_minor}/>
          )}
          {rev.rounding_expense_minor > 0 && (
            <Row label="Less: invoice round-down" v={-rev.rounding_expense_minor}/>
          )}
          {TAX_COMPLIANCE_UI_ENABLED && gstRegistered ? (
            <>
              <Divider/>
              {tax.cgst_minor > 0 && <Row label="Less: Output CGST" v={-tax.cgst_minor}/>}
              {tax.sgst_minor > 0 && <Row label="Less: Output SGST" v={-tax.sgst_minor}/>}
              {tax.igst_minor > 0 && <Row label="Less: Output IGST" v={-tax.igst_minor}/>}
              {tax.cess_minor > 0 && <Row label="Less: Output cess" v={-tax.cess_minor}/>}
              <Divider/>
              <Row label="Net revenue (after GST)" v={data.net_revenue_minor} bold/>
            </>
          ) : taxCollectedLabel ? (
            <>
              <Divider/>
              <Row label="Less: recorded indirect tax" v={-totalGst}/>
              <Divider/>
              <Row label="Net revenue after recorded tax" v={data.net_revenue_minor} bold/>
            </>
          ) : null}
          <Divider/>
          <Row label="Less: recorded cost of goods sold" v={-data.cogs_minor}
            sub={costing?.is_complete !== true ? 'cost coverage is incomplete or unverified; unknown costs are excluded' : 'what the food/drinks/items you sold actually cost you'}/>
          <Row label={costing?.is_complete !== true ? 'Provisional gross profit' : 'Gross profit'}
            v={data.gross_profit_minor} bold
            sub={manualCollections > 0 ? 'includes manual collections at 0% COGS' : undefined}/>
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
        <Row label={costing?.is_complete === true ? 'Gross profit' : 'Provisional gross profit'} v={data.gross_profit_minor}/>
        <Row label="Less: total expenses" v={-exp}/>
        {data.depreciation_minor > 0 && (
          <Row label="Less: equipment depreciation" v={-data.depreciation_minor}
            sub="straight-line, computed from the asset register"/>
        )}
        <Divider/>
        <Row label={costing?.is_complete === true ? 'Operating profit (today)' : 'Provisional operating profit'} v={net} bold sub="after equipment depreciation"/>
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
            {GAMING_CENTRE_FEATURES.memberships && (
              <Stat label="Active memberships" value={metrics.active_members_count.toString()}
                sub="unexpired, non-revoked terms active right now"/>
            )}
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
          {GAMING_CENTRE_FEATURES.memberships && (
            <p className="text-xs text-fg-muted mt-3">
              Memberships are prepaid manual terms, not recurring subscriptions. MRR/ARR stay
              hidden until an actual recurring-billing provider is operating.
            </p>
          )}
        </div>
      )}
    </>
  );
}

// ============================================================================
// EXPENSES TAB
// ============================================================================
function ExpensesTab() {
  const requests = useLatestRequest();
  const notifications = useNotifications();
  const [rows, setRows] = useState<ExpenseDTO[]>([]);
  const [cats, setCats] = useState<ExpenseCategoryDTO[]>([]);
  const [branches, setBranches] = useState<BranchReferenceDTO[]>([]);
  const [openShifts, setOpenShifts] = useState<ShiftDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [voidExpense, setVoidExpense] = useState<ExpenseDTO | null>(null);
  const [correctExpense, setCorrectExpense] = useState<ExpenseDTO | null>(null);
  const [voidBusy, setVoidBusy] = useState(false);
  const [receiptExpense, setReceiptExpense] = useState<ExpenseDTO | null>(null);

  const load = useCallback(async (silent = false) => {
    const isCurrent = requests.begin();
    if (!silent) setLoading(true);
    try {
      const [r, c, b, s] = await Promise.all([
        finance.listExpenses(),
        settings.listExpenseCategories(),
        finance.listBranches(),
        shifts.list(true),
      ]);
      if (!isCurrent()) return;
      setErr(null);
      setRows(r); setCats(c); setBranches(b); setOpenShifts(s);
    } catch (e) { if (isCurrent()) setErr((e as Error).message); }
    finally { if (isCurrent()) setLoading(false); }
  }, [requests]);
  useEffect(() => { void load(); }, [load]);
  useRealtimeRefresh({ resources: ['finance', 'shifts'], refresh: () => load(true) });

  async function confirmVoid(reason: string) {
    if (!voidExpense || voidBusy) return;
    setVoidBusy(true);
    try {
      await finance.voidExpense(voidExpense.id, reason);
      setVoidExpense(null);
      await load();
      notifications.success('The expense was voided with an audit reason.', {
        title: 'Expense voided',
      });
    } catch (e) {
      notifications.error((e as Error).message, { title: 'Could not void expense' });
    } finally {
      setVoidBusy(false);
    }
  }

  if (loading) return <SkeletonCard />;

  const total = rows.reduce((s, r) => s + (r.is_corrected ? 0 : r.amount_minor), 0);
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
          <button className="btn btn-ghost" onClick={() => void load()}><RefreshCw size={14}/></button>
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
            {!branches.length && <> Ask the protected owner to check your shop access.</>}
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
                <th className="text-left p-3">Receipt</th>
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
                    {r.correction && (
                      <div className="mt-1 text-accent-gold">
                        Corrected {new Date(r.correction.corrected_at).toLocaleString('en-IN')}: {r.correction.reason}
                      </div>
                    )}
                  </td>
                  <td className="p-3"><span className="chip text-xs">{r.paid_via}</span></td>
                  <td className="p-3">
                    <button
                      type="button"
                      className="text-left text-xs text-accent hover:underline"
                      onClick={() => setReceiptExpense(r)}
                    >
                      {expenseReceiptSummary(r)}
                    </button>
                  </td>
                  <td className="p-3 text-right font-mono">{inr(r.amount_minor)}</td>
                  <td className="p-3 text-right pr-4">
                    {r.is_corrected ? (
                      <span className="chip border-accent-gold/40 text-accent-gold text-[10px]">Corrected</span>
                    ) : r.paid_via === 'cash' && r.shift_id && r.source_shift_status !== 'open' ? (
                      <button aria-label="Correct closed-shift expense" onClick={() => setCorrectExpense(r)}
                        className="btn btn-ghost !min-h-[32px] !px-2 !py-1 text-xs">
                        <Ban size={13}/> Correct
                      </button>
                    ) : (
                      <button aria-label="Void expense" onClick={() => setVoidExpense(r)}
                        className="text-fg-muted hover:text-accent-bad">
                        <Trash2 size={14}/>
                      </button>
                    )}
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
                    {r.correction && (
                      <div className="mt-1 text-xs text-accent-gold">
                        Corrected {new Date(r.correction.corrected_at).toLocaleString('en-IN')}: {r.correction.reason}
                      </div>
                    )}
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="font-mono font-semibold">{inr(r.amount_minor)}</div>
                    <span className="chip mt-1 text-[10px]">{r.paid_via}</span>
                  </div>
                </div>
                <div className="mt-3 flex items-center justify-between gap-2">
                  <button
                    type="button"
                    className="btn btn-ghost !min-h-[32px] !px-2 !py-1 text-xs"
                    onClick={() => setReceiptExpense(r)}
                  >
                    <FileText size={13}/>{expenseReceiptSummary(r)}
                  </button>
                  {r.is_corrected ? (
                    <span className="chip border-accent-gold/40 text-accent-gold text-[10px]">Corrected</span>
                  ) : r.paid_via === 'cash' && r.shift_id && r.source_shift_status !== 'open' ? (
                    <button aria-label="Correct closed-shift expense" onClick={() => setCorrectExpense(r)}
                      className="btn btn-ghost !min-h-[32px] !px-2 !py-1 text-xs">
                      <Ban size={13}/> Correct
                    </button>
                  ) : (
                    <button
                      aria-label="Void expense"
                      onClick={() => setVoidExpense(r)}
                      className="btn btn-ghost !min-h-[32px] !min-w-[32px] !px-2 !py-1 hover:!text-accent-bad"
                    >
                      <Trash2 size={14}/>
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {addOpen && (
        <ExpenseForm
          cats={cats}
          branches={branches}
          openShifts={openShifts}
          onClose={() => setAddOpen(false)}
          onSuccess={({ receipt, receiptRequested }) => {
            setAddOpen(false);
            void load();
            if (receipt) {
              notifications.success('The expense and its receipt were saved.', {
                title: 'Expense saved',
              });
            } else if (receiptRequested) {
              notifications.warning(
                'The expense is saved. Its receipt still needs to be uploaded from the expense list.',
                { title: 'Receipt still needed', critical: true },
              );
            } else {
              notifications.success('The expense was recorded without a receipt.', {
                title: 'Expense saved',
              });
            }
          }}
        />
      )}
      {receiptExpense && (
        <ExpenseReceiptsModal
          expense={receiptExpense}
          onClose={() => setReceiptExpense(null)}
          onChanged={() => void load(true)}
        />
      )}
      {voidExpense && (
        <PromptModal
          title="Void expense"
          label={`Reason for voiding the ${inr(voidExpense.amount_minor)} expense${voidExpense.vendor_name ? ` from ${voidExpense.vendor_name}` : ''}`}
          placeholder="For example: duplicate entry or wrong amount"
          confirmLabel="Void expense"
          danger
          busy={voidBusy}
          minLength={3}
          maxLength={500}
          onSubmit={(reason) => { void confirmVoid(reason); }}
          onCancel={() => { if (!voidBusy) setVoidExpense(null); }}
        />
      )}
      {correctExpense && (
        <ExpenseCorrectionForm
          row={correctExpense}
          openShifts={openShifts.filter((shift) => shift.branch_id === correctExpense.branch_id)}
          onClose={() => setCorrectExpense(null)}
          onSuccess={() => {
            setCorrectExpense(null);
            void load();
            notifications.success(
              'The closed-shift cash expense was reversed in the selected current drawer.',
              { title: 'Correction recorded' },
            );
          }}
        />
      )}
    </div>
  );
}

function newExpenseKey(): string {
  const randomPart = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (part) => {
      const random = Math.floor(Math.random() * 16);
      return (part === 'x' ? random : (random & 0x3) | 0x8).toString(16);
    });
  return `expense:${randomPart}`;
}

function newExpenseCorrectionKey(): string {
  const id = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `expense-correction:${id}`;
}

function ExpenseCorrectionForm({
  row,
  openShifts,
  onClose,
  onSuccess,
}: {
  row: ExpenseDTO;
  openShifts: ShiftDTO[];
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [shiftId, setShiftId] = useState(openShifts[0]?.id ?? '');
  const [reason, setReason] = useState('');
  const [key] = useState(newExpenseCorrectionKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!shiftId) {
      setErr('Open a same-branch shift and select its drawer before correcting this expense.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await finance.correctExpense(
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
    <Modal open onClose={busy ? () => undefined : onClose} title="Correct closed-shift expense">
      <form onSubmit={submit} className="space-y-3">
        <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm">
          The original {inr(row.amount_minor)} expense and closed shift stay unchanged. This full
          reversal is dated now and returns the amount to a current same-branch drawer.
        </div>
        <Field label="Current open drawer">
          <select className="input" required value={shiftId}
            onChange={(event) => setShiftId(event.target.value)}>
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
          <button type="submit" className="btn btn-primary"
            disabled={busy || !shiftId || reason.trim().length < 3}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Ban size={14}/>} Record full correction
          </button>
        </div>
      </form>
    </Modal>
  );
}

export function localDateTimeInputValue(value: Date): string {
  const local = new Date(value.getTime() - value.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function newExpenseReceiptKey(kind: 'upload' | 'review'): string {
  const randomPart = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `expense-receipt-${kind}:${randomPart}`;
}

function ExpenseForm({
  cats, branches, openShifts, onClose, onSuccess,
}: {
  cats: ExpenseCategoryDTO[];
  branches: BranchReferenceDTO[];
  openShifts: ShiftDTO[];
  onClose: () => void;
  onSuccess: (result: {
    expense: ExpenseDTO;
    receipt: ExpenseReceiptDTO | null;
    receiptRequested: boolean;
  }) => void;
}) {
  const initialBranchId = branches[0]?.id ?? '';
  const [form, setForm] = useState({
    branch_id: initialBranchId,
    category_id: cats[0]?.id ?? '',
    amount_rupees: '',
    paid_via: DEFAULT_EXPENSE_PAYMENT_RAIL,
    shift_id: openShifts.find((shift) => shift.branch_id === initialBranchId)?.id ?? '',
    paid_at: localDateTimeInputValue(new Date()),
    vendor_name: '',
    invoice_no: '',
    note: '',
  });
  const [idempotencyKey] = useState(newExpenseKey);
  const [receiptIdempotencyKey] = useState(() => newExpenseReceiptKey('upload'));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [receiptSource, setReceiptSource] = useState<ExpenseReceiptSource>('file');
  const [receiptWasRequested, setReceiptWasRequested] = useState(false);
  const [savedExpense, setSavedExpense] = useState<ExpenseDTO | null>(null);
  const eligibleShifts = openShifts.filter((shift) => shift.branch_id === form.branch_id);

  function selectReceipt(file: File, source: ExpenseReceiptSource) {
    const fileError = expenseReceiptFileError(file);
    if (fileError) {
      setErr(fileError);
      return;
    }
    setSelectedFile(file);
    setReceiptSource(source);
    setReceiptWasRequested(true);
    setErr(null);
  }

  function close() {
    if (busy) return;
    if (savedExpense) {
      onSuccess({ expense: savedExpense, receipt: null, receiptRequested: receiptWasRequested });
      return;
    }
    onClose();
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const result = await persistExpenseWithOptionalReceipt({
        savedExpense,
        selectedFile,
        source: receiptSource,
        createExpense: async () => {
          const amountMinor = parseRupeesToMinor(form.amount_rupees);
          if (amountMinor === null || amountMinor <= 0) {
            throw new Error('Expense amount must be above ₹0 with at most two decimals.');
          }
          if (form.paid_via === 'cash' && !form.shift_id) {
            throw new Error('Select the open shift drawer that paid this cash expense.');
          }
          return finance.createExpense({
            branch_id: form.branch_id,
            category_id: form.category_id,
            amount_minor: amountMinor,
            paid_via: form.paid_via,
            shift_id: form.paid_via === 'cash' ? form.shift_id : undefined,
            paid_at: new Date(form.paid_at).toISOString(),
            vendor_name: form.vendor_name || undefined,
            invoice_no: form.invoice_no || undefined,
            note: form.note || undefined,
          }, idempotencyKey);
        },
        uploadReceipt: finance.uploadExpenseReceipt,
        receiptIdempotencyKey,
      });
      setSavedExpense(result.expense);
      if (result.receiptError) {
        setErr(`The expense is saved, but the receipt was not uploaded. ${result.receiptError}`);
        return;
      }
      onSuccess({
        expense: result.expense,
        receipt: result.receipt,
        receiptRequested: receiptWasRequested,
      });
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={close} title="Add expense" size="lg">
      <form onSubmit={submit} className="space-y-3">
        {savedExpense && (
          <div className="rounded-xl border border-accent-good/40 bg-accent-good/10 p-3 text-sm">
            <b>The expense is already saved.</b> Retrying below uploads only the receipt and cannot
            create a duplicate expense.
          </div>
        )}
        <fieldset disabled={busy || savedExpense !== null} className="space-y-3 disabled:opacity-60">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Branch">
              <select className="input" required value={form.branch_id}
                onChange={(e) => {
                  const branchId = e.target.value;
                  setForm({
                    ...form,
                    branch_id: branchId,
                    shift_id: openShifts.find((shift) => shift.branch_id === branchId)?.id ?? '',
                  });
                }}>
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
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Amount (₹)">
              <input type="number" min={0} step="0.01" required className="input font-mono text-right"
                value={form.amount_rupees}
                onChange={(e) => setForm({ ...form, amount_rupees: e.target.value })}/>
            </Field>
            <Field label="Paid via">
              <select className="input" value={form.paid_via}
                onChange={(e) => {
                  const paidVia = e.target.value as ExpensePaymentOption['value'];
                  setForm({
                    ...form,
                    paid_via: paidVia,
                    shift_id: paidVia === 'cash'
                      ? eligibleShifts[0]?.id ?? ''
                      : form.shift_id,
                  });
                }}>
                {EXPENSE_PAYMENT_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </Field>
          </div>
          {form.paid_via === 'cash' && (
            <Field label="Open shift drawer">
              <select
                className="input"
                required
                value={form.shift_id}
                onChange={(e) => setForm({ ...form, shift_id: e.target.value })}
              >
                <option value="">Select the shift that supplied the cash</option>
                {eligibleShifts.map((shift) => (
                  <option key={shift.id} value={shift.id}>
                    {shift.opened_by_name || shift.opened_by_email || 'Authorised user'} · opened{' '}
                    {new Date(shift.opened_at).toLocaleString('en-IN')} · drawer {inr(shift.expected_minor ?? 0)}
                  </option>
                ))}
              </select>
              {!eligibleShifts.length && (
                <p className="mt-1 text-xs text-accent-bad" role="alert">
                  This branch has no open shift. Open a shift before recording a cash paid-out.
                </p>
              )}
            </Field>
          )}
          <div className="flex items-start gap-2 rounded-xl border border-accent-gold/30 bg-accent-gold/5 p-3 text-xs text-fg-muted">
            <AlertCircle size={14} className="mt-0.5 shrink-0 text-accent-gold"/>
            <p>{EXPENSE_CASH_DRAWER_GUIDANCE}</p>
          </div>
          <Field label="Date / time">
            <input type="datetime-local" className="input" required value={form.paid_at}
              onChange={(e) => setForm({ ...form, paid_at: e.target.value })}/>
          </Field>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
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
        </fieldset>

        <ReceiptFilePicker
          selectedFile={selectedFile}
          disabled={busy}
          onSelect={selectReceipt}
          onClear={() => { setSelectedFile(null); setErr(null); }}
        />
        {err && <ErrorRow text={err}/>}
        <div className="flex flex-col-reverse justify-end gap-2 pt-2 sm:flex-row">
          <button type="button" className="btn btn-ghost" onClick={close} disabled={busy}>
            {savedExpense ? 'Finish later' : 'Cancel'}
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={busy || (form.paid_via === 'cash' && !form.shift_id)}
          >
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            {savedExpense
              ? selectedFile ? 'Retry receipt upload' : 'Finish without receipt'
              : selectedFile ? 'Save expense and receipt' : 'Record expense'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

export function ReceiptFilePicker({
  selectedFile,
  disabled,
  onSelect,
  onClear,
}: {
  selectedFile: File | null;
  disabled: boolean;
  onSelect: (file: File, source: ExpenseReceiptSource) => void;
  onClear: () => void;
}) {
  const cameraInput = useRef<HTMLInputElement>(null);
  const uploadInput = useRef<HTMLInputElement>(null);

  function selected(input: HTMLInputElement, source: ExpenseReceiptSource) {
    const file = input.files?.[0];
    input.value = '';
    if (file) onSelect(file, source);
  }

  return (
    <section className="rounded-xl border border-bg-border bg-bg-raised/40 p-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-sm font-semibold">Bill or receipt</p>
          <p className="mt-0.5 text-xs text-fg-muted">
            Optional. Take a photo now or attach a PDF or image. Maximum 10 MB.
          </p>
        </div>
        {selectedFile && (
          <button type="button" className="text-xs text-fg-muted hover:text-accent-bad"
            onClick={onClear} disabled={disabled}>
            Remove
          </button>
        )}
      </div>
      <input
        ref={cameraInput}
        hidden
        type="file"
        accept="image/jpeg,image/png,image/webp"
        capture="environment"
        aria-label="Take receipt photo"
        onChange={(event) => selected(event.currentTarget, 'camera')}
      />
      <input
        ref={uploadInput}
        hidden
        type="file"
        accept={EXPENSE_RECEIPT_ACCEPT}
        aria-label="Choose receipt file"
        onChange={(event) => {
          const file = event.currentTarget.files?.[0];
          event.currentTarget.value = '';
          if (file) onSelect(file, pickedExpenseReceiptSource(file));
        }}
      />
      <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
        <button type="button" className="btn btn-ghost" disabled={disabled}
          onClick={() => cameraInput.current?.click()}>
          <Camera size={15}/> Take photo
        </button>
        <button type="button" className="btn btn-ghost" disabled={disabled}
          onClick={() => uploadInput.current?.click()}>
          <Upload size={15}/> Choose receipt
        </button>
      </div>
      {selectedFile && (
        <div className="mt-3 flex items-center gap-2 rounded-lg border border-accent/30 bg-accent/5 p-2 text-xs">
          <FileText size={14} className="shrink-0 text-accent"/>
          <span className="min-w-0 flex-1 truncate">{selectedFile.name}</span>
          <span className="shrink-0 text-fg-muted">{formatFileSize(selectedFile.size)}</span>
        </div>
      )}
    </section>
  );
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function ExpenseReceiptsModal({
  expense,
  onClose,
  onChanged,
}: {
  expense: ExpenseDTO;
  onClose: () => void;
  onChanged: () => void;
}) {
  const notifications = useNotifications();
  const [rows, setRows] = useState<ExpenseReceiptDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [receiptSource, setReceiptSource] = useState<ExpenseReceiptSource>('file');
  const [uploading, setUploading] = useState(false);
  const [openingId, setOpeningId] = useState<string | null>(null);
  const [preview, setPreview] = useState<{
    url: string;
    filename: string;
    contentType: string;
  } | null>(null);
  const [reviewStatus, setReviewStatus] = useState<ExpenseReceiptStatus>(
    expense.receipt_status ?? 'pending',
  );
  const [reviewNote, setReviewNote] = useState('');
  const [reviewing, setReviewing] = useState(false);
  const [uploadIdempotencyKey, setUploadIdempotencyKey] = useState(
    () => newExpenseReceiptKey('upload'),
  );
  const [reviewIdempotencyKey, setReviewIdempotencyKey] = useState(
    () => newExpenseReceiptKey('review'),
  );

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const receipts = await finance.listExpenseReceipts(expense.id);
      setRows(receipts);
      setErr(null);
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setLoading(false);
    }
  }, [expense.id]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview.url);
  }, [preview]);

  function selectReceipt(file: File, source: ExpenseReceiptSource) {
    const fileError = expenseReceiptFileError(file);
    if (fileError) {
      setErr(fileError);
      return;
    }
    setSelectedFile(file);
    setReceiptSource(source);
    setErr(null);
  }

  async function upload() {
    if (!selectedFile || uploading) return;
    setUploading(true); setErr(null);
    try {
      await finance.uploadExpenseReceipt(
        expense.id,
        normalizeExpenseReceiptFile(selectedFile),
        receiptSource,
        uploadIdempotencyKey,
      );
      setSelectedFile(null);
      setUploadIdempotencyKey(newExpenseReceiptKey('upload'));
      setReviewStatus('pending');
      await load();
      onChanged();
      notifications.success('The receipt was attached and is ready for review.', {
        title: 'Receipt uploaded',
      });
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setUploading(false);
    }
  }

  async function openReceipt(receipt: ExpenseReceiptDTO) {
    if (openingId) return;
    setOpeningId(receipt.id); setErr(null);
    try {
      const blob = await finance.getExpenseReceiptContent(receipt.id);
      const url = URL.createObjectURL(blob);
      setPreview((current) => {
        if (current) URL.revokeObjectURL(current.url);
        return {
          url,
          filename: receipt.original_filename,
          contentType: receipt.content_type || blob.type,
        };
      });
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setOpeningId(null);
    }
  }

  async function saveReview() {
    if (reviewing) return;
    const reviewError = expenseReceiptReviewError(reviewStatus, reviewNote);
    if (reviewError) {
      setErr(reviewError);
      return;
    }
    setReviewing(true); setErr(null);
    try {
      await finance.reviewExpenseReceipts(expense.id, {
        status: reviewStatus,
        review_note: reviewNote.trim() || undefined,
      }, reviewIdempotencyKey);
      setReviewIdempotencyKey(newExpenseReceiptKey('review'));
      onChanged();
      notifications.success(`Receipt status changed to ${EXPENSE_RECEIPT_STATUS_LABEL[reviewStatus]}.`, {
        title: 'Review saved',
      });
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setReviewing(false);
    }
  }

  const statusOptions = expenseReceiptReviewStatuses(rows.length, reviewStatus);

  return (
    <Modal open onClose={onClose} title="Expense receipt" size="lg">
      <div className="space-y-4">
        <div className="rounded-xl border border-bg-border bg-bg-raised/40 p-3 text-sm">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="font-semibold">{expense.vendor_name || 'Manual expense'}</p>
              <p className="mt-0.5 text-xs text-fg-muted">
                {new Date(expense.paid_at).toLocaleDateString('en-IN')}
                {expense.invoice_no ? ` · Invoice ${expense.invoice_no}` : ''}
              </p>
            </div>
            <p className="font-mono font-semibold">{inr(expense.amount_minor)}</p>
          </div>
        </div>

        <ReceiptFilePicker
          selectedFile={selectedFile}
          disabled={uploading}
          onSelect={selectReceipt}
          onClear={() => { setSelectedFile(null); setErr(null); }}
        />
        {selectedFile && (
          <div className="flex justify-end">
            <button type="button" className="btn btn-primary" onClick={() => void upload()}
              disabled={uploading}>
              {uploading ? <Loader2 size={14} className="animate-spin"/> : <Upload size={14}/>} Upload receipt
            </button>
          </div>
        )}

        {err && <ErrorRow text={err}/>}

        <section>
          <h4 className="mb-2 text-sm font-semibold">
            Attached receipts · {loading ? '…' : rows.length}
          </h4>
          {loading ? (
            <div className="rounded-xl border border-bg-border p-4 text-sm text-fg-muted">
              Loading receipts…
            </div>
          ) : rows.length === 0 ? (
            <div className="rounded-xl border border-bg-border p-4 text-sm text-fg-muted">
              No receipt is attached. This does not stop you from recording the manual expense.
            </div>
          ) : (
            <div className="space-y-2">
              {rows.map((receipt) => (
                <div key={receipt.id}
                  className="flex flex-wrap items-center gap-3 rounded-xl border border-bg-border p-3">
                  <FileText size={17} className="shrink-0 text-accent"/>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium">{receipt.original_filename}</p>
                    <p className="text-xs text-fg-muted">
                      {receipt.source === 'camera'
                        ? 'Camera'
                        : receipt.source === 'gallery' ? 'Photo library' : 'File upload'} ·{' '}
                      {formatFileSize(receipt.size_bytes ?? receipt.byte_size ?? 0)}
                    </p>
                  </div>
                  <button type="button" className="btn btn-ghost !min-h-[36px] !py-1.5 text-xs"
                    onClick={() => void openReceipt(receipt)} disabled={openingId !== null}>
                    {openingId === receipt.id ? <Loader2 size={13} className="animate-spin"/> : <Eye size={13}/>} View
                  </button>
                </div>
              ))}
            </div>
          )}
        </section>

        {preview && (
          <section className="rounded-xl border border-bg-border bg-bg-raised/30 p-3">
            <div className="mb-3 flex items-center justify-between gap-3">
              <p className="min-w-0 truncate text-sm font-semibold">{preview.filename}</p>
              <a className="btn btn-ghost !min-h-[36px] !py-1.5 text-xs"
                href={preview.url} download={preview.filename}>
                Download
              </a>
            </div>
            <ExpenseReceiptContentPreview preview={preview}/>
          </section>
        )}

        {!loading && (
          <section className="rounded-xl border border-bg-border p-3">
            <p className="text-sm font-semibold">Receipt review</p>
            <p className="mt-0.5 text-xs text-fg-muted">
              Mark the attached evidence after checking it against this expense.
            </p>
            <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-[minmax(0,12rem)_1fr]">
              <select className="input" value={reviewStatus}
                onChange={(event) => setReviewStatus(event.target.value as ExpenseReceiptStatus)}>
                {statusOptions.map((status) => (
                  <option key={status} value={status}>{EXPENSE_RECEIPT_STATUS_LABEL[status]}</option>
                ))}
              </select>
              <input className="input"
                placeholder={reviewStatus === 'rejected' || reviewStatus === 'not_required'
                  ? 'Reason (required)'
                  : 'Review note (optional)'}
                value={reviewNote}
                onChange={(event) => setReviewNote(event.target.value)}/>
            </div>
            <div className="mt-3 flex justify-end">
              <button type="button" className="btn btn-primary" onClick={() => void saveReview()}
                disabled={reviewing}>
                {reviewing && <Loader2 size={14} className="animate-spin"/>} Save review
              </button>
            </div>
          </section>
        )}
      </div>
    </Modal>
  );
}

export function ExpenseReceiptContentPreview({
  preview,
}: {
  preview: { url: string; filename: string; contentType: string };
}) {
  if (preview.contentType === 'application/pdf') {
    return (
      <iframe
        src={preview.url}
        title={`Receipt ${preview.filename}`}
        sandbox=""
        referrerPolicy="no-referrer"
        className="h-[50vh] w-full rounded-lg bg-white"
      />
    );
  }
  if (['image/jpeg', 'image/png', 'image/webp'].includes(preview.contentType)) {
    return (
      <img src={preview.url} alt={`Receipt ${preview.filename}`}
        className="max-h-[50vh] w-full rounded-lg bg-white object-contain"/>
    );
  }
  return (
    <p className="text-sm text-fg-muted">
      This device cannot preview this file type. Use Download to open it in a compatible app.
    </p>
  );
}
// ============================================================================
// PARTNERS TAB
// ============================================================================
function PartnersTab() {
  const requests = useLatestRequest();
  const notifications = useNotifications();
  const [rows, setRows] = useState<PartnerDTO[]>([]);
  const [split, setSplit] = useState<PartnerPLReportDTO | null>(null);
  const [distributable, setDistributable] = useState<DistributableProfitReportDTO | null>(null);
  const [allocationWarning, setAllocationWarning] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [capPartner, setCapPartner] = useState<PartnerDTO | null>(null);
  const [ledgerPartner, setLedgerPartner] = useState<PartnerDTO | null>(null);

  const load = useCallback(async (silent = false) => {
    const isCurrent = requests.begin();
    if (!silent) setLoading(true);
    try {
      const [partners, profitSplit, distributableProfit] = await Promise.all([
        finance.listPartners(),
        optionalPartnerAllocation(() => finance.partnerProfitSplit()),
        optionalPartnerAllocation(() => finance.distributableProfit()),
      ]);
      if (!isCurrent()) return;
      setErr(null);
      setRows(partners);
      const warning = profitSplit.unavailableReason ?? distributableProfit.unavailableReason;
      setAllocationWarning(warning);
      setSplit(warning ? null : profitSplit.value);
      setDistributable(warning ? null : distributableProfit.value);
    }
    catch (e) {
      if (isCurrent()) {
        setErr((e as Error).message);
        // Old allocation capacity must not remain actionable after a failed refresh.
        setSplit(null);
        setDistributable(null);
        setAllocationWarning('Partner allocations could not be verified. Refresh before using any distribution or profit-share figure. Saved partner records remain visible below.');
      }
    }
    finally { if (isCurrent()) setLoading(false); }
  }, [requests]);
  useEffect(() => { void load(); }, [load]);
  useRealtimeRefresh({ resources: ['finance'], refresh: () => load(true) });

  const shareByPartnerId = new Map<string, number>();
  for (const partner of split?.partners ?? []) {
    const amount = split ? verifiedPartnerProfitShare(split, partner) : null;
    if (amount !== null) shareByPartnerId.set(partner.partner_id, amount);
  }
  const distributableByPartnerId = new Map<string, { amount: number; withdrawn: number }>();
  for (const partner of distributable?.partners ?? []) {
    const amount = distributable ? verifiedPartnerDistribution(distributable, partner) : null;
    if (amount !== null) {
      distributableByPartnerId.set(partner.partner_id, {
        amount,
        withdrawn: partner.lifetime_withdrawn_minor,
      });
    }
  }
  const spendableCash = distributable ? verifiedSpendableCash(distributable) : null;
  const distributionCap = distributable ? verifiedDistributionCap(distributable) : null;
  const distributionUnavailable = distributable !== null && distributionCap === null;
  const splitUnavailable = split !== null && !allocationIsAuthoritative(split);

  if (loading) return <SkeletonCard />;

  return (
    <div>
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          {rows.length} partner{rows.length === 1 ? '' : 's'}
        </p>
        <div className="flex gap-2">
        <button className="btn btn-ghost" onClick={() => void load()} aria-label="Refresh partners"><RefreshCw size={14}/> Refresh</button>
        <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
          <Plus size={14}/> Add partner
        </button>
        </div>
      </div>

      {err && <ErrorRow text={err}/>}
      {allocationWarning && (
        <div className="card mb-3 border-accent-gold/40 text-sm" role="status">
          <b>Partner allocations unavailable.</b> {allocationWarning}
          <p className="mt-1 text-fg-muted">Partner identity and capital history are separate from ownership allocations. No distribution or profit shares are assumed.</p>
        </div>
      )}

      {distributable && (
        <div className={`card mb-3 ${distributionUnavailable ? 'border-accent-gold/40' : 'border-accent-good/40'}`} role={distributionUnavailable ? 'status' : undefined}>
          <div className="flex justify-between items-start flex-wrap gap-2 mb-3">
            <div>
              <div className="font-bold text-sm">
                {distributionUnavailable ? 'Partner distribution unavailable' : 'Server-calculated distribution cap'}
              </div>
              <div className="text-[11px] text-fg-muted mt-0.5">
                {distributionUnavailable
                  ? 'The provisional operating result remains visible, but no amount is presented as safe to withdraw until historical product costs reconcile.'
                  : <>All-time profit, minus everything ever withdrawn, minus a {distributable.reserve_months}-month
                    safety buffer — capped by posted till and bank balances, excluding unsettled provider funds.
                    Includes {inr(distributable.lifetime_depreciation_minor)} in equipment depreciation.</>}
              </div>
            </div>
            <div className={`text-2xl font-bold font-mono ${(distributionCap ?? 0) > 0 ? 'text-accent-good' : distributionUnavailable ? 'text-accent-gold' : ''}`}>
              {distributionCap === null ? 'Unavailable' : inr(distributionCap)}
            </div>
          </div>
          <div className={`grid grid-cols-1 ${distributionUnavailable ? 'md:grid-cols-2' : 'md:grid-cols-4'} gap-2 text-xs pt-3 border-t border-bg-border/60`}>
            <div>
              <div className="text-fg-muted">{distributionUnavailable ? 'Provisional lifetime profit (after depreciation)' : 'Lifetime profit (after depreciation)'}</div>
              <div className={`font-mono font-semibold ${distributable.lifetime_net_profit_minor < 0 ? 'text-accent-bad' : ''}`}>
                {inr(distributable.lifetime_net_profit_minor)}
              </div>
            </div>
            {distributionUnavailable ? (
              <div>
                <div className="text-fg-muted">Historical costing check</div>
                <div className="font-semibold text-accent-gold">
                  {distributable.costing_confidence
                    ? `${distributable.costing_confidence.unresolved_order_count} order${distributable.costing_confidence.unresolved_order_count === 1 ? '' : 's'} need reconciliation`
                    : 'Not available from the server'}
                </div>
              </div>
            ) : <>
              <div>
                <div className="text-fg-muted">Already withdrawn</div>
                <div className="font-mono font-semibold">{inr(distributable.lifetime_withdrawn_minor)}</div>
              </div>
              <div>
                <div className="text-fg-muted">Reserve kept back</div>
                <div className="font-mono font-semibold">{inr(distributable.reserve_minor)}</div>
              </div>
              <div>
                <div className="text-fg-muted">Spendable cash · till + bank</div>
                <div className="font-mono font-semibold">{spendableCash === null ? 'Unavailable' : inr(spendableCash)}</div>
              </div>
            </>}
          </div>
          {distributionUnavailable && <p className="text-xs text-accent-gold mt-3">{allocationUnavailableReason(distributable)}</p>}
          {!distributionUnavailable && spendableCash === null && <p className="text-xs text-accent-gold mt-3">{PARTNER_CASH_UNVERIFIED}</p>}
          {!distributionUnavailable && spendableCash !== null && distributable.cash_based_capacity_minor < distributable.profit_based_capacity_minor && (
            <p className="text-[11px] text-accent-gold mt-3">
              Limited by spendable funds, not by profit — some value may be tied up in stock, equipment or provider settlement receivables.
            </p>
          )}
        </div>
      )}

      {split && (
        <div className={`card mb-3 flex justify-between items-center flex-wrap gap-2 ${splitUnavailable ? 'border-accent-gold/40' : ''}`}>
          <div className="text-sm text-fg-muted max-w-3xl">
            {splitUnavailable ? 'Provisional operating result' : 'Operating profit'} this month ({new Date(split.period_start).toLocaleDateString('en-IN')}
            {' – '}{new Date(split.period_end).toLocaleDateString('en-IN')})
            {splitUnavailable
              ? <span className="block text-xs text-accent-gold mt-1">Partner profit shares are hidden. {allocationUnavailableReason(split)}</span>
              : ', split by ownership share'}
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
                  Server-calculated allocation cap
                  <span className="block text-[10px]">
                    Withdrawn to date: {inr(distributableByPartnerId.get(p.id)?.withdrawn ?? 0)}
                  </span>
                </span>
                <span className={`font-bold font-mono ${(distributableByPartnerId.get(p.id)?.amount ?? 0) > 0 ? 'text-accent-good' : ''}`}>
                  {inr(distributableByPartnerId.get(p.id)?.amount ?? 0)}
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

      {addOpen && <PartnerForm
        onClose={() => setAddOpen(false)}
        onSuccess={() => {
          setAddOpen(false);
          void load();
          const feedback = FINANCE_ACTION_FEEDBACK.partnerRecorded;
          notifications.success(feedback.message, { title: feedback.title });
        }}
      />}
      {capPartner && <CapitalForm
        partner={capPartner}
        onClose={() => setCapPartner(null)}
        onSuccess={() => {
          setCapPartner(null);
          void load();
          const feedback = FINANCE_ACTION_FEEDBACK.capitalRecorded;
          notifications.success(feedback.message, { title: feedback.title });
        }}
      />}
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

function newCapitalEntryKey(): string {
  const randomPart = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `capital-entry:${randomPart}`;
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
  const [idempotencyKey] = useState(newCapitalEntryKey);
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
      }, idempotencyKey);
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
  const requests = useLatestRequest();
  const notifications = useNotifications();
  const [rows, setRows] = useState<CapitalEntryDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [voiding, setVoiding] = useState<CapitalEntryDTO | null>(null);

  const load = useCallback(async (silent = false) => {
    const isCurrent = requests.begin();
    if (!silent) setLoading(true);
    try {
      const capital = await finance.listCapital(partner.id, true);
      if (isCurrent()) { setRows(capital); setErr(null); }
    } catch (error) {
      if (isCurrent()) setErr((error as Error).message);
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [partner.id, requests]);

  useEffect(() => { void load(); }, [load]);
  useRealtimeRefresh({ resources: ['finance'], refresh: () => load(true) });

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
            const feedback = FINANCE_ACTION_FEEDBACK.capitalVoided;
            notifications.success(feedback.message, { title: feedback.title });
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
