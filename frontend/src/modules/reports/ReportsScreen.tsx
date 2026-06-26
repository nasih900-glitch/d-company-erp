/**
 * Reports — Daily, Monthly, Quarterly, Yearly P&L.
 *
 * Real data when live mode is on (backend at /api/v1/reports/*).
 * Demo data when demo mode is on (in-memory fixtures from demo-data).
 *
 * Print-friendly: Cmd+P → clean A4 P&L with only the report content visible.
 * Also "Push to Google Sheets" button — fires this report into the ERP Entries
 * tab in your sheet.
 */
import { useEffect, useState } from 'react';
import { AlertTriangle, Calendar, FileSpreadsheet, Loader2, Printer, ShieldCheck } from 'lucide-react';

import { api } from '@/lib/api';
import { COMPANY, HOURLY_REVENUE, TODAY_KPI } from '@/lib/demo-data';
import { LIVE_MODE } from '@/lib/demo';
import { inr } from '@/lib/inr';
import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import { pushToSheet, type SinkKind } from '@/lib/google-sheets';

type Period = 'daily' | 'weekly' | 'monthly' | 'quarterly' | 'half_yearly' | 'yearly';
type HalfYear = 'H1' | 'H2';

const PERIODS: Array<{ value: Period; label: string }> = [
  { value: 'daily', label: 'Daily' },
  { value: 'weekly', label: 'Weekly' },
  { value: 'monthly', label: 'Monthly' },
  { value: 'quarterly', label: 'Quarterly' },
  { value: 'half_yearly', label: 'Half-yearly' },
  { value: 'yearly', label: 'Yearly' },
];

interface ReportData {
  period: Period | 'custom';
  label: string;
  period_start: string;
  period_end: string;
  fiscal_year: string;
  orders_count: number;
  tickets_count: number;
  avg_ticket_minor: number;
  revenue: {
    food_minor: number;
    gaming_minor: number;
    hookah_minor: number;
    event_tickets_minor: number;
    delivery_aggregator_minor: number;
    other_minor: number;
    total_minor: number;
  };
  tax_collected: {
    cgst_minor: number;
    sgst_minor: number;
    igst_minor: number;
    cess_minor: number;
    total_minor: number;
  };
  payments_received: {
    cash_minor: number;
    upi_minor: number;
    card_minor: number;
    qr_minor: number;
    wallet_minor: number;
    other_minor: number;
    total_minor: number;
  };
  expenses: Array<{ category: string; amount_minor: number }>;
  expense_total_minor: number;
  gross_revenue_minor: number;
  net_revenue_minor: number;
  net_profit_minor: number;
}

interface TaxComplianceIssue {
  severity: 'critical' | 'warning' | 'info';
  area: string;
  title: string;
  detail: string;
  count: number;
  action: string;
}

interface TaxComplianceData {
  period_start: string;
  period_end: string;
  company_gst_registered: boolean;
  gstin: string | null;
  checked_orders: number;
  checked_order_lines: number;
  taxable_minor: number;
  gst_collected_minor: number;
  aggregator_delivery_minor: number;
  event_ticket_revenue_minor: number;
  critical_count: number;
  warning_count: number;
  info_count: number;
  issues: TaxComplianceIssue[];
}

export default function ReportsScreen() {
  const [period, setPeriod] = useState<Period>('daily');
  const [report, setReport] = useState<ReportData | null>(null);
  const [taxHealth, setTaxHealth] = useState<TaxComplianceData | null>(null);
  const [taxError, setTaxError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Period-specific selectors
  const today = new Date().toISOString().slice(0, 10);
  const yyyy_mm = today.slice(0, 7);
  const fy = fiscalYearFor(new Date());
  const [onDate, setOnDate] = useState<string>(today);
  const [weekDate, setWeekDate] = useState<string>(today);
  const [month, setMonth] = useState<string>(yyyy_mm);
  const [year, setYear] = useState<string>(fy);
  const [quarter, setQuarter] = useState<number>(currentFiscalQuarter(new Date()));
  const [halfYear, setHalfYear] = useState<HalfYear>(currentFiscalHalf(new Date()));

  useEffect(() => { load(); }, [period, onDate, weekDate, month, year, quarter, halfYear]);

  async function load() {
    setLoading(true);
    setError(null);
    setTaxError(null);
    try {
      const data = await fetchReport(period, { onDate, weekDate, month, year, quarter, halfYear });
      setReport(data);
      try {
        setTaxHealth(await fetchTaxCompliance(data.period_start, data.period_end));
      } catch (taxIssue) {
        setTaxHealth(null);
        setTaxError((taxIssue as Error).message);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  async function pushToSheets() {
    if (!report) return;
    const ok = await pushToSheet(`${period}_report` as SinkKind, {
      date: report.period_start,
      time: new Date().toTimeString().slice(0, 5),
      period_id: report.label,
      label: report.label,
      orders_count: report.orders_count,
      food_minor: report.revenue.food_minor,
      gaming_minor: report.revenue.gaming_minor,
      hookah_minor: isAppStoreAllowedType('hookah') ? report.revenue.hookah_minor : 0,
      event_tickets_minor: report.revenue.event_tickets_minor,
      delivery_minor: report.revenue.delivery_aggregator_minor,
      gross_revenue_minor: report.gross_revenue_minor,
      net_revenue_minor: report.net_revenue_minor,
      cgst_minor: report.tax_collected.cgst_minor,
      sgst_minor: report.tax_collected.sgst_minor,
      igst_minor: report.tax_collected.igst_minor,
      expense_total_minor: report.expense_total_minor,
      net_profit_minor: report.net_profit_minor,
    });
    if (ok) alert('✓ Report pushed to Google Sheets (ERP Entries tab)');
    else alert('Failed to push to Google Sheets. Check Settings → Google Sheets.');
  }

  return (
    <div className="reports-screen">
      {/* Print-only header */}
      <div className="hidden print:block mb-4">
        <h1 className="text-2xl font-bold text-black">{COMPANY.name}</h1>
        <p className="text-xs text-black/70">{COMPANY.address}</p>
        <p className="text-xs text-black/70">
          GSTIN: {COMPANY.gstin} · FSSAI: {COMPANY.fssai}
        </p>
      </div>

      {/* Screen-only controls (hidden in print) */}
      <header className="flex items-end justify-between mb-4 flex-wrap gap-4 print:hidden">
        <div>
          <h2 className="text-2xl font-bold">Reports</h2>
          <p className="text-fg-muted text-sm">P&amp;L · Indian FY · Kerala GST</p>
        </div>
        <div className="flex gap-2">
          <button onClick={() => window.print()} className="btn btn-ghost">
            <Printer size={14}/> Print / Save PDF
          </button>
          <button onClick={pushToSheets} className="btn btn-primary">
            <FileSpreadsheet size={14}/> Push to Sheets
          </button>
        </div>
      </header>

      {/* Period tabs (hidden in print) */}
      <div className="scroll-strip flex gap-1 bg-bg-surface rounded-xl p-1 border border-bg-border mb-4 print:hidden">
        {PERIODS.map((p) => (
          <button key={p.value}
            onClick={() => setPeriod(p.value)}
            className={`shrink-0 px-4 py-2 rounded-lg text-sm font-medium transition sm:flex-1 capitalize ${
              period === p.value ? 'bg-accent text-bg' : 'text-fg-muted hover:text-fg'
            }`}
          >{p.label}</button>
        ))}
      </div>

      {/* Period selector (hidden in print) */}
      <div className="card !p-4 mb-4 print:hidden">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-[auto_repeat(3,minmax(0,200px))] sm:items-center">
          <Calendar size={16} className="hidden text-fg-muted sm:block"/>
          {period === 'daily' && (
            <input type="date" value={onDate} onChange={(e) => setOnDate(e.target.value)}
              className="input !min-h-[40px] !py-2 sm:max-w-[200px]"/>
          )}
          {period === 'weekly' && (
            <input type="date" value={weekDate} onChange={(e) => setWeekDate(e.target.value)}
              className="input !min-h-[40px] !py-2 sm:max-w-[200px]"/>
          )}
          {period === 'monthly' && (
            <input type="month" value={month} onChange={(e) => setMonth(e.target.value)}
              className="input !min-h-[40px] !py-2 sm:max-w-[200px]"/>
          )}
          {(period === 'quarterly' || period === 'half_yearly' || period === 'yearly') && (
            <input type="text" value={year} onChange={(e) => setYear(e.target.value)}
              placeholder="2026-27"
              className="input !min-h-[40px] !py-2 sm:max-w-[120px]"/>
          )}
          {period === 'quarterly' && (
            <select value={quarter} onChange={(e) => setQuarter(Number(e.target.value))}
              className="input !min-h-[40px] !py-2 sm:max-w-[160px]">
              <option value={1}>Q1 (Apr-Jun)</option>
              <option value={2}>Q2 (Jul-Sep)</option>
              <option value={3}>Q3 (Oct-Dec)</option>
              <option value={4}>Q4 (Jan-Mar)</option>
            </select>
          )}
          {period === 'half_yearly' && (
            <select value={halfYear} onChange={(e) => setHalfYear(e.target.value as HalfYear)}
              className="input !min-h-[40px] !py-2 sm:max-w-[160px]">
              <option value="H1">H1 (Apr-Sep)</option>
              <option value="H2">H2 (Oct-Mar)</option>
            </select>
          )}
        </div>
      </div>

      {error && <p className="text-accent-bad text-sm mb-3 print:hidden">{error}</p>}

      {loading && (
        <div className="flex items-center justify-center py-12 text-fg-muted">
          <Loader2 size={18} className="animate-spin mr-2"/> Computing…
        </div>
      )}

      {/* The actual report */}
      {report && !loading && (
        <article className="print:text-black">
          <header className="mb-4 pb-4 border-b border-bg-border print:border-black/30">
            <h3 className="text-xl font-bold">
              {period === 'daily' && 'Daily P&L'}
              {period === 'weekly' && 'Weekly P&L'}
              {period === 'monthly' && 'Monthly P&L'}
              {period === 'quarterly' && 'Quarterly P&L'}
              {period === 'half_yearly' && 'Half-yearly P&L'}
              {period === 'yearly' && 'Annual P&L'}
              <span className="ml-3 text-fg-muted print:text-black/60 font-normal">
                {report.label}
              </span>
            </h3>
            <p className="text-xs text-fg-muted print:text-black/70 mt-1">
              {report.period_start} to {report.period_end} · FY {report.fiscal_year}
            </p>
          </header>

          {/* KPI strip */}
          <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-7 gap-3 mb-6 print:gap-2">
            <KPI label="Orders" value={report.orders_count.toString()}/>
            <KPI label="Tickets" value={report.tickets_count.toString()}/>
            <KPI label="Avg ticket" value={inr(report.avg_ticket_minor)}/>
            <KPI label="Net revenue" value={inr(report.net_revenue_minor)}/>
            <KPI label="Profit margin" value={percent(report.net_profit_minor, report.net_revenue_minor)}
              tone={report.net_profit_minor >= 0 ? 'good' : 'bad'}/>
            <KPI label="Expense ratio" value={percent(report.expense_total_minor, report.net_revenue_minor)}
              tone={report.expense_total_minor <= report.net_revenue_minor ? 'good' : 'bad'}/>
            <KPI label="Net profit" value={inr(report.net_profit_minor)}
              tone={report.net_profit_minor >= 0 ? 'good' : 'bad'}/>
          </div>

          {taxError && (
            <p className="text-accent-bad text-sm mb-4 print:hidden">{taxError}</p>
          )}
          {taxHealth && <TaxHealthPanel data={taxHealth} />}

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 print:grid-cols-2 print:gap-3">
            {/* Revenue */}
            <section className="card print:border print:border-black/30 print:p-3">
              <h4 className="font-bold mb-3">Revenue</h4>
              <Row label="Food / drinks / desserts"          v={report.revenue.food_minor}/>
              <Row label="Gaming"                            v={report.revenue.gaming_minor}/>
              {isAppStoreAllowedType('hookah') && report.revenue.hookah_minor > 0 &&
                <Row label="Hookah"                           v={report.revenue.hookah_minor}/>}
              <Row label="Event tickets"                     v={report.revenue.event_tickets_minor}/>
              <Row label="Delivery (Zomato/Swiggy §9(5))"    v={report.revenue.delivery_aggregator_minor}
                   sub="aggregator pays the GST"/>
              {report.revenue.other_minor > 0 &&
                <Row label="Other"                          v={report.revenue.other_minor}/>}
              <Divider/>
              <Row label="Gross revenue" v={report.revenue.total_minor} bold/>
              <Row label="Less: GST collected" v={-report.tax_collected.total_minor}/>
              <Divider/>
              <Row label="Net revenue (after GST)" v={report.net_revenue_minor} bold/>
            </section>

            {/* GST collected */}
            <section className="card print:border print:border-black/30 print:p-3">
              <h4 className="font-bold mb-3">GST collected</h4>
              <Row label="CGST" v={report.tax_collected.cgst_minor}/>
              <Row label="SGST" v={report.tax_collected.sgst_minor}/>
              {report.tax_collected.igst_minor > 0 &&
                <Row label="IGST (inter-state)" v={report.tax_collected.igst_minor}/>}
              {report.tax_collected.cess_minor > 0 &&
                <Row label="Cess" v={report.tax_collected.cess_minor}/>}
              <Divider/>
              <Row label="Total GST" v={report.tax_collected.total_minor} bold/>
              <p className="text-[10px] text-fg-muted print:text-black/60 mt-2">
                Owed to govt at month-end via GSTR-3B (CGST+SGST) and GSTR-1
                (invoice-level). Net of input tax credit.
              </p>
            </section>

            {/* Payments */}
            <section className="card print:border print:border-black/30 print:p-3">
              <h4 className="font-bold mb-3">Payments received</h4>
              <Row label="Cash"   v={report.payments_received.cash_minor}/>
              <Row label="UPI"    v={report.payments_received.upi_minor}/>
              <Row label="Card"   v={report.payments_received.card_minor}/>
              <Row label="QR"     v={report.payments_received.qr_minor}/>
              <Row label="Wallet" v={report.payments_received.wallet_minor}/>
              {report.payments_received.other_minor > 0 &&
                <Row label="Other" v={report.payments_received.other_minor}/>}
              <Divider/>
              <Row label="Total received" v={report.payments_received.total_minor} bold/>
            </section>

            {/* Expenses */}
            <section className="card print:border print:border-black/30 print:p-3">
              <h4 className="font-bold mb-3">Expenses</h4>
              {report.expenses.length === 0 && (
                <p className="text-sm text-fg-muted print:text-black/60">
                  No expenses recorded in this period.
                </p>
              )}
              {report.expenses.map((e) => (
                <Row key={e.category} label={e.category} v={e.amount_minor}/>
              ))}
              {report.expenses.length > 0 && (
                <>
                  <Divider/>
                  <Row label="Total expenses" v={report.expense_total_minor} bold/>
                </>
              )}
            </section>
          </div>

          {/* Net profit summary */}
          <section className="card mt-4 print:border print:border-black/30 print:p-3">
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-center">
              <div>
                <div className="text-xs text-fg-muted print:text-black/60 uppercase tracking-wider">
                  Net revenue
                </div>
                <div className="text-2xl font-bold mt-1">
                  {inr(report.net_revenue_minor)}
                </div>
              </div>
              <div>
                <div className="text-xs text-fg-muted print:text-black/60 uppercase tracking-wider">
                  Expenses
                </div>
                <div className="text-2xl font-bold mt-1">
                  {inr(report.expense_total_minor)}
                </div>
              </div>
              <div>
                <div className="text-xs text-fg-muted print:text-black/60 uppercase tracking-wider">
                  Net profit
                </div>
                <div className={`text-3xl font-bold mt-1 ${
                  report.net_profit_minor >= 0 ? 'text-accent-good print:text-black' : 'text-accent-bad print:text-black'
                }`}>
                  {inr(report.net_profit_minor)}
                </div>
              </div>
            </div>
          </section>

          <footer className="text-center text-[10px] text-fg-muted print:text-black/60 mt-6">
            Generated {new Date().toLocaleString('en-IN')} · D Company ERP
            <br/>
            This report is computed live from journal entries and order data.
          </footer>
        </article>
      )}

      {/* Print styles */}
      <style>{`
        @media print {
          @page { size: A4; margin: 1.5cm; }
          body { background: white !important; }
          aside, nav, .no-print { display: none !important; }
        }
      `}</style>
    </div>
  );
}

// ----- Helpers -----
function fiscalYearFor(d: Date): string {
  const y = d.getFullYear();
  return d.getMonth() >= 3
    ? `${y}-${String(y + 1).slice(-2)}`
    : `${y - 1}-${String(y).slice(-2)}`;
}

function currentFiscalQuarter(d: Date): number {
  const m = d.getMonth();
  if (m >= 3 && m <= 5)  return 1;
  if (m >= 6 && m <= 8)  return 2;
  if (m >= 9 && m <= 11) return 3;
  return 4;
}

function currentFiscalHalf(d: Date): HalfYear {
  const m = d.getMonth();
  return m >= 3 && m <= 8 ? 'H1' : 'H2';
}

async function fetchReport(
  period: Period,
  opts: {
    onDate: string;
    weekDate: string;
    month: string;
    year: string;
    quarter: number;
    halfYear: HalfYear;
  },
): Promise<ReportData> {
  if (LIVE_MODE) {
    if (period === 'daily')
      return (await api.get<ReportData>('/reports/daily', { params: { on_date: opts.onDate } })).data;
    if (period === 'weekly') {
      const [from_date, to_date] = weekRangeFor(opts.weekDate);
      return (await api.get<ReportData>('/reports/range', { params: { from_date, to_date } })).data;
    }
    if (period === 'monthly')
      return (await api.get<ReportData>('/reports/monthly', { params: { yyyy_mm: opts.month } })).data;
    if (period === 'quarterly')
      return (await api.get<ReportData>('/reports/quarterly', { params: { fy: opts.year, q: opts.quarter } })).data;
    if (period === 'half_yearly') {
      const [from_date, to_date] = halfYearRange(opts.year, opts.halfYear);
      return (await api.get<ReportData>('/reports/range', { params: { from_date, to_date } })).data;
    }
    return (await api.get<ReportData>('/reports/yearly', { params: { fy: opts.year } })).data;
  }
  // Demo: synthesize a plausible report from the existing demo KPIs.
  return demoReport(period, opts);
}

async function fetchTaxCompliance(from_date: string, to_date: string): Promise<TaxComplianceData> {
  if (LIVE_MODE) {
    return (await api.get<TaxComplianceData>('/reports/tax-compliance', {
      params: { from_date, to_date },
    })).data;
  }
  return {
    period_start: from_date,
    period_end: to_date,
    company_gst_registered: true,
    gstin: COMPANY.gstin,
    checked_orders: 87,
    checked_order_lines: 214,
    taxable_minor: 9_84_300,
    gst_collected_minor: 58_900,
    aggregator_delivery_minor: TODAY_KPI.revenue_delivery_minor,
    event_ticket_revenue_minor: 2_00_000,
    critical_count: 0,
    warning_count: 1,
    info_count: 0,
    issues: [{
      severity: 'warning',
      area: 'Demo',
      title: 'Demo tax check',
      detail: 'Live mode runs the real GST health checks against backend invoices.',
      count: 1,
      action: 'Use the live ERP for filing-period review.',
    }],
  };
}

function demoReport(
  p: Period,
  opts: {
    onDate: string;
    weekDate: string;
    month: string;
    year: string;
    quarter: number;
    halfYear: HalfYear;
  },
): ReportData {
  // Scale the daily numbers up for monthly/quarterly/yearly
  const scale =
    p === 'daily'     ? 1 :
    p === 'weekly'    ? 7 :
    p === 'monthly'   ? 30 :
    p === 'quarterly' ? 90 :
    p === 'half_yearly' ? 182 :
                       365;
  const orders = Math.round(87 * scale);
  const tickets = Math.round(8 * scale);
  const food    = TODAY_KPI.revenue_food_minor * scale;
  const gaming  = TODAY_KPI.revenue_gaming_minor * scale;
  const hookah = 0;
  const delivery = TODAY_KPI.revenue_delivery_minor * scale;
  const tickets_revenue = 25000 * tickets;
  const gross = food + gaming + hookah + delivery + tickets_revenue;

  // GST: food 5%, gaming 18%, tickets 18%, delivery 0% (9(5))
  const cgst = Math.round(food * 0.05 / 2 / 1.05) + Math.round((gaming + tickets_revenue) * 0.18 / 2 / 1.18);
  const sgst = cgst;
  const total_tax = cgst + sgst;
  const net_revenue = gross - total_tax;

  // Expenses
  const cogs = Math.round(net_revenue * 0.32);
  const wages = Math.round(3_24_500 * scale);
  const rent  = Math.round((5_00_000 / 30) * scale);
  const utility = Math.round((1_80_000 / 30) * scale);
  const other = Math.round(25_000 * scale);
  const expense_total = cogs + wages + rent + utility + other;

  const label =
    p === 'daily' ? new Date(opts.onDate).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) :
    p === 'weekly' ? weekLabel(opts.weekDate) :
    p === 'monthly' ? opts.month :
    p === 'quarterly' ? `${opts.year} Q${opts.quarter}` :
    p === 'half_yearly' ? `${opts.year} ${opts.halfYear}` :
    `FY ${opts.year}`;
  const [periodStart, periodEnd] =
    p === 'weekly' ? weekRangeFor(opts.weekDate) :
    p === 'half_yearly' ? halfYearRange(opts.year, opts.halfYear) :
    [opts.onDate, opts.onDate];

  return {
    period: p,
    label,
    period_start: periodStart,
    period_end: periodEnd,
    fiscal_year: opts.year,
    orders_count: orders,
    tickets_count: tickets,
    avg_ticket_minor: orders ? Math.round(gross / orders) : 0,
    revenue: {
      food_minor: food,
      gaming_minor: gaming,
      hookah_minor: hookah,
      event_tickets_minor: tickets_revenue,
      delivery_aggregator_minor: delivery,
      other_minor: 0,
      total_minor: gross,
    },
    tax_collected: {
      cgst_minor: cgst, sgst_minor: sgst, igst_minor: 0, cess_minor: 0,
      total_minor: total_tax,
    },
    payments_received: {
      cash_minor:   Math.round(gross * 0.30),
      upi_minor:    Math.round(gross * 0.45),
      card_minor:   Math.round(gross * 0.20),
      qr_minor:     Math.round(gross * 0.04),
      wallet_minor: Math.round(gross * 0.01),
      other_minor:  0,
      total_minor:  gross,
    },
    expenses: [
      { category: 'COGS (Food)',      amount_minor: cogs },
      { category: 'Wages',            amount_minor: wages },
      { category: 'Rent',             amount_minor: rent },
      { category: 'Utilities',        amount_minor: utility },
      { category: 'Other',            amount_minor: other },
    ],
    expense_total_minor: expense_total,
    gross_revenue_minor: gross,
    net_revenue_minor: net_revenue,
    net_profit_minor: net_revenue - expense_total,
  };

  // Use the unused import to avoid TS noUnusedLocals
  void HOURLY_REVENUE;
}

function parseISODate(iso: string): Date {
  return new Date(`${iso}T00:00:00`);
}

function dateISO(d: Date): string {
  const p = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function weekRangeFor(iso: string): [string, string] {
  const d = parseISODate(iso);
  const day = d.getDay() || 7;
  const start = new Date(d);
  start.setDate(d.getDate() - day + 1);
  const end = new Date(start);
  end.setDate(start.getDate() + 6);
  return [dateISO(start), dateISO(end)];
}

function weekLabel(iso: string): string {
  const [start, end] = weekRangeFor(iso);
  return `${new Date(`${start}T00:00:00`).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })} - ${new Date(`${end}T00:00:00`).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })}`;
}

function halfYearRange(fy: string, half: HalfYear): [string, string] {
  const fyStartYear = Number(fy.split('-')[0]);
  if (!Number.isFinite(fyStartYear)) return [dateISO(new Date()), dateISO(new Date())];
  if (half === 'H1') return [`${fyStartYear}-04-01`, `${fyStartYear}-09-30`];
  return [`${fyStartYear}-10-01`, `${fyStartYear + 1}-03-31`];
}

function TaxHealthPanel({ data }: { data: TaxComplianceData }) {
  const status =
    data.critical_count > 0 ? 'critical' :
    data.warning_count > 0 ? 'warning' :
    'clean';
  const border =
    status === 'critical' ? 'border-accent-bad/50' :
    status === 'warning' ? 'border-accent-gold/50' :
    'border-accent-good/50';
  const icon =
    status === 'clean'
      ? <ShieldCheck size={17} className="text-accent-good" />
      : <AlertTriangle size={17} className={status === 'critical' ? 'text-accent-bad' : 'text-accent-gold'} />;

  return (
    <section className={`card mb-4 print:border print:border-black/30 print:p-3 ${border}`}>
      <div className="flex items-start justify-between gap-3 mb-3">
        <div className="flex items-center gap-2">
          {icon}
          <div>
            <h4 className="font-bold">GST health</h4>
            <p className="text-xs text-fg-muted print:text-black/60">
              {data.period_start} to {data.period_end} · {data.checked_orders} paid orders checked
            </p>
          </div>
        </div>
        <div className="text-right text-xs">
          <div className={data.critical_count ? 'text-accent-bad font-semibold' : 'text-fg-muted'}>
            {data.critical_count} critical
          </div>
          <div className={data.warning_count ? 'text-accent-gold font-semibold' : 'text-fg-muted'}>
            {data.warning_count} warnings
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3 text-sm">
        <MiniMetric label="Taxable checked" value={inr(data.taxable_minor)} />
        <MiniMetric label="GST collected" value={inr(data.gst_collected_minor)} />
        <MiniMetric label="ECO delivery" value={inr(data.aggregator_delivery_minor)} />
        <MiniMetric label="Event tickets" value={inr(data.event_ticket_revenue_minor)} />
      </div>

      <div className="divide-y divide-bg-border print:divide-black/20">
        {data.issues.slice(0, 6).map((issue) => (
          <div key={`${issue.severity}-${issue.area}-${issue.title}`} className="py-2">
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className="font-semibold text-sm">{issue.title}</div>
                <div className="text-xs text-fg-muted print:text-black/60">
                  {issue.area} · {issue.detail}
                </div>
                <div className="text-xs mt-1">{issue.action}</div>
              </div>
              <span className={`chip shrink-0 ${
                issue.severity === 'critical' ? 'border-accent-bad/40 text-accent-bad' :
                issue.severity === 'warning' ? 'border-accent-gold/40 text-accent-gold' :
                'border-accent-good/40 text-accent-good'
              }`}>
                {issue.count || issue.severity}
              </span>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function MiniMetric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[10px] text-fg-muted print:text-black/60 uppercase tracking-wider">{label}</div>
      <div className="font-mono font-semibold">{value}</div>
    </div>
  );
}

function Row({ label, v, sub, bold }: { label: string; v: number; sub?: string; bold?: boolean }) {
  return (
    <div className={`flex justify-between py-1.5 print:py-1 ${bold ? 'font-bold border-t border-bg-border print:border-black/30 mt-1 pt-2 text-base' : 'text-sm'}`}>
      <div>
        <div>{label}</div>
        {sub && <div className="text-[10px] text-fg-muted print:text-black/60">{sub}</div>}
      </div>
      <div className={`font-mono ${v < 0 ? 'text-accent-bad print:text-black' : ''}`}>
        {inr(Math.abs(v))}
      </div>
    </div>
  );
}
function Divider() { return <div className="h-px bg-bg-border print:bg-black/30 my-1"/>; }

function percent(numerator: number, denominator: number) {
  if (!denominator) return '0.0%';
  return `${((numerator / denominator) * 100).toFixed(1)}%`;
}

function KPI({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'bad' }) {
  const color = tone === 'bad' ? 'text-accent-bad print:text-black' :
                tone === 'good' ? 'text-accent-good print:text-black' :
                'text-fg print:text-black';
  return (
    <div className="card print:border print:border-black/30 print:p-2">
      <div className="text-[10px] text-fg-muted print:text-black/60 uppercase tracking-wider">
        {label}
      </div>
      <div className={`text-xl font-bold mt-1 ${color}`}>{value}</div>
    </div>
  );
}
