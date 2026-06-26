/**
 * Insights — the "how is the business actually doing" screen.
 *
 *  Tabs:
 *    Growth      — MoM / YoY revenue, top items, hour-of-day heatmap
 *    Inventory   — current stock at cost, low-stock alerts, recipe margins
 *    Accounting  — Chart of Accounts, Trial Balance, Balance Sheet, GL
 *    Losses      — waste/damage/negative-stock in ₹
 */
import { useEffect, useMemo, useState } from 'react';
import {
  TrendingUp, Boxes, Calculator, AlertTriangle,
  Loader2, ArrowUp, ArrowDown,
} from 'lucide-react';

import { inr, inrShort } from '@/lib/inr';
import {
  accounting, insights,
  type AccountDTO, type TrialBalanceDTO, type BalanceSheetDTO,
  type InventoryValuationDTO, type RecipeMarginDTO, type GrowthDTO,
  type TopItemDTO, type HeatmapCellDTO, type LossesDTO, type GLEntryDTO,
} from '@/lib/erp-api';

type Tab = 'growth' | 'inventory' | 'accounting' | 'losses';

export default function InsightsScreen() {
  const [tab, setTab] = useState<Tab>('growth');
  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Insights</h2>
        <p className="text-fg-muted text-sm">
          How the business is actually performing · live from the database
        </p>
      </header>

      <div className="scroll-strip flex gap-1 mb-6 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'growth'} onClick={() => setTab('growth')}>
          <TrendingUp size={14}/> Growth
        </TabBtn>
        <TabBtn active={tab === 'inventory'} onClick={() => setTab('inventory')}>
          <Boxes size={14}/> Inventory
        </TabBtn>
        <TabBtn active={tab === 'accounting'} onClick={() => setTab('accounting')}>
          <Calculator size={14}/> Accounting
        </TabBtn>
        <TabBtn active={tab === 'losses'} onClick={() => setTab('losses')}>
          <AlertTriangle size={14}/> Losses
        </TabBtn>
      </div>

      {tab === 'growth'     && <GrowthTab/>}
      {tab === 'inventory'  && <InventoryTab/>}
      {tab === 'accounting' && <AccountingTab/>}
      {tab === 'losses'     && <LossesTab/>}
    </div>
  );
}

// ============================================================================
// GROWTH TAB
// ============================================================================
function GrowthTab() {
  const [period, setPeriod] = useState<'wow' | 'mom' | 'yoy'>('mom');
  const [growth, setGrowth] = useState<GrowthDTO | null>(null);
  const [top, setTop] = useState<TopItemDTO[]>([]);
  const [heatmap, setHeatmap] = useState<HeatmapCellDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try {
      const [g, t, h] = await Promise.all([
        insights.growth(period),
        insights.topItems({ limit: 10 }),
        insights.heatmap(),
      ]);
      setGrowth(g); setTop(t); setHeatmap(h);
    } catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, [period]);

  if (loading) return <LoadingCard/>;
  if (err) return <ErrorCard text={err}/>;
  if (!growth) return null;

  const revUp = growth.revenue_delta_pct >= 0;
  const ordUp = growth.orders_delta_pct >= 0;

  return (
    <div>
      <div className="flex justify-end mb-3">
        <div className="inline-flex rounded-lg overflow-hidden border border-bg-border">
          {(['wow', 'mom', 'yoy'] as const).map((p) => (
            <button key={p} onClick={() => setPeriod(p)}
              className={`px-3 py-1.5 text-xs font-medium ${
                period === p ? 'bg-accent text-bg' : 'text-fg-muted hover:bg-bg-raised'
              }`}>
              {p === 'wow' ? 'Week' : p === 'mom' ? 'Month' : 'Year'}
            </button>
          ))}
        </div>
      </div>

      {/* Headline */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6">
        <ComparisonCard
          title={`Revenue · ${growth.current.label}`}
          current={growth.current.revenue_minor}
          previous={growth.previous.revenue_minor}
          delta={growth.revenue_delta_pct}
          previousLabel={growth.previous.label}
          up={revUp}
        />
        <ComparisonCard
          title={`Orders · ${growth.current.label}`}
          current={growth.current.orders_count}
          previous={growth.previous.orders_count}
          delta={growth.orders_delta_pct}
          previousLabel={growth.previous.label}
          up={ordUp}
          rupee={false}
        />
      </div>

      {/* Top items */}
      <div className="card mb-6">
        <h3 className="font-semibold mb-3">Top items (this month)</h3>
        {!top.length ? (
          <p className="text-fg-muted text-sm">No sales yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-xs text-fg-muted">
              <tr>
                <th className="text-left p-2">Item</th>
                <th className="text-left p-2">Type</th>
                <th className="text-right p-2">Qty sold</th>
                <th className="text-right p-2">Revenue</th>
              </tr>
            </thead>
            <tbody>
              {top.map((t) => (
                <tr key={t.menu_item_id} className="border-t border-bg-border/60">
                  <td className="p-2 font-medium">{t.name}</td>
                  <td className="p-2 text-fg-muted text-xs">{t.type}</td>
                  <td className="p-2 text-right font-mono">{t.qty_sold.toFixed(1)}</td>
                  <td className="p-2 text-right font-mono font-medium">{inr(t.revenue_minor)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Heatmap */}
      <div className="card">
        <h3 className="font-semibold mb-3">When customers buy (last 30 days)</h3>
        <HeatmapGrid cells={heatmap}/>
        <p className="text-xs text-fg-muted mt-3">
          Darker cells = more revenue. Use this to plan staffing.
        </p>
      </div>
    </div>
  );
}

function ComparisonCard({
  title, current, previous, delta, previousLabel, up, rupee = true,
}: {
  title: string; current: number; previous: number;
  delta: number; previousLabel: string; up: boolean; rupee?: boolean;
}) {
  return (
    <div className="card">
      <div className="text-xs text-fg-muted uppercase tracking-wider mb-2">{title}</div>
      <div className="flex items-baseline gap-3">
        <div className="text-3xl font-bold">{rupee ? inrShort(current) : current.toLocaleString('en-IN')}</div>
        <div className={`flex items-center gap-1 text-sm font-mono ${up ? 'text-accent-good' : 'text-accent-bad'}`}>
          {up ? <ArrowUp size={14}/> : <ArrowDown size={14}/>}
          {Math.abs(delta).toFixed(1)}%
        </div>
      </div>
      <div className="text-xs text-fg-muted mt-2">
        vs {previousLabel}: {rupee ? inr(previous) : previous}
      </div>
    </div>
  );
}

function HeatmapGrid({ cells }: { cells: HeatmapCellDTO[] }) {
  const grid = useMemo(() => {
    const m: Record<string, number> = {};
    let max = 0;
    for (const c of cells) {
      m[`${c.day_of_week}-${c.hour}`] = c.revenue_minor;
      if (c.revenue_minor > max) max = c.revenue_minor;
    }
    return { m, max };
  }, [cells]);
  const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  return (
    <div className="overflow-x-auto">
      <table className="text-[10px]" style={{ borderSpacing: 2, borderCollapse: 'separate' }}>
        <thead>
          <tr>
            <th></th>
            {Array.from({ length: 24 }, (_, h) => (
              <th key={h} className="text-fg-muted font-mono w-7 text-center">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {DAYS.map((day, d) => (
            <tr key={day}>
              <td className="text-fg-muted text-right pr-2 font-medium">{day}</td>
              {Array.from({ length: 24 }, (_, h) => {
                const v = grid.m[`${d}-${h}`] ?? 0;
                const intensity = grid.max > 0 ? v / grid.max : 0;
                const bg = v > 0
                  ? `rgba(124, 58, 237, ${0.15 + intensity * 0.7})`
                  : 'rgba(255,255,255,0.03)';
                return (
                  <td key={h} title={`${day} ${h}:00 — ${inr(v)}`}
                    style={{ background: bg, width: 28, height: 22 }}
                    className="rounded text-center text-fg-muted">
                    {v > 0 && intensity > 0.5 ? '·' : ''}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ============================================================================
// INVENTORY TAB — valuation + recipe margin
// ============================================================================
function InventoryTab() {
  const [val, setVal] = useState<InventoryValuationDTO | null>(null);
  const [margin, setMargin] = useState<RecipeMarginDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try {
      const [v, m] = await Promise.all([
        insights.inventoryValuation(),
        insights.recipeMargin(),
      ]);
      setVal(v); setMargin(m);
    } catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (loading) return <LoadingCard/>;
  if (err) return <ErrorCard text={err}/>;
  if (!val) return null;

  return (
    <div>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mb-6">
        <Stat label="Stock value" value={inr(val.total_valuation_minor)}/>
        <Stat label="Ingredients" value={val.lines.length.toString()}/>
        <Stat label="Low-stock items" value={val.low_stock_count.toString()}
          tone={val.low_stock_count > 0 ? 'bad' : 'good'}/>
      </div>

      <div className="card !p-0 mb-6 overflow-hidden">
        <div className="px-4 py-3 border-b border-bg-border">
          <h3 className="font-semibold">Inventory valuation (current stock × avg cost)</h3>
        </div>
        {!val.lines.length ? (
          <div className="p-4 text-fg-muted text-sm">No ingredients yet.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-bg-raised text-xs text-fg-muted">
              <tr>
                <th className="text-left p-3">SKU</th>
                <th className="text-left p-3">Name</th>
                <th className="text-right p-3">Qty</th>
                <th className="text-right p-3">Avg cost</th>
                <th className="text-right p-3">Value</th>
                <th className="p-3"></th>
              </tr>
            </thead>
            <tbody>
              {[...val.lines].sort((a, b) => b.valuation_minor - a.valuation_minor).map((l) => (
                <tr key={l.ingredient_id} className="border-t border-bg-border/60">
                  <td className="p-3 font-mono text-xs text-fg-muted">{l.sku}</td>
                  <td className="p-3 font-medium">{l.name}</td>
                  <td className={`p-3 text-right font-mono ${l.is_low_stock ? 'text-accent-bad' : ''}`}>
                    {l.current_qty.toLocaleString('en-IN')} {l.base_unit}
                  </td>
                  <td className="p-3 text-right font-mono text-xs">{inr(l.avg_cost_minor)}</td>
                  <td className="p-3 text-right font-mono font-medium">{inr(l.valuation_minor)}</td>
                  <td className="p-3">
                    {l.is_low_stock && (
                      <span className="chip text-[10px] border-accent-bad/40 text-accent-bad">low</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card !p-0 overflow-hidden">
        <div className="px-4 py-3 border-b border-bg-border">
          <h3 className="font-semibold">Recipe margins (which items make money)</h3>
        </div>
        {!margin.length ? (
          <div className="p-4 text-fg-muted text-sm">
            No recipes defined yet. Add ingredients + recipes to see margin analysis.
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-bg-raised text-xs text-fg-muted">
              <tr>
                <th className="text-left p-3">Item</th>
                <th className="text-right p-3">Sale price</th>
                <th className="text-right p-3">Cost</th>
                <th className="text-right p-3">Margin (₹)</th>
                <th className="text-right p-3">Margin %</th>
              </tr>
            </thead>
            <tbody>
              {margin.map((r) => (
                <tr key={r.menu_item_id} className="border-t border-bg-border/60">
                  <td className="p-3 font-medium">{r.name}</td>
                  <td className="p-3 text-right font-mono">{inr(r.sale_price_minor)}</td>
                  <td className="p-3 text-right font-mono text-fg-muted">{inr(r.cost_minor)}</td>
                  <td className={`p-3 text-right font-mono font-medium ${r.margin_minor >= 0 ? 'text-accent-good' : 'text-accent-bad'}`}>
                    {inr(r.margin_minor)}
                  </td>
                  <td className="p-3 text-right font-mono">
                    <span className={`chip ${
                      r.margin_pct >= 60 ? 'border-accent-good/40 text-accent-good' :
                      r.margin_pct >= 30 ? 'border-accent-gold/40 text-accent-gold' :
                                           'border-accent-bad/40 text-accent-bad'
                    }`}>
                      {r.margin_pct.toFixed(0)}%
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

// ============================================================================
// ACCOUNTING TAB — Trial Balance, Balance Sheet, COA, GL
// ============================================================================
function AccountingTab() {
  const [tb, setTb] = useState<TrialBalanceDTO | null>(null);
  const [bs, setBs] = useState<BalanceSheetDTO | null>(null);
  const [coa, setCoa] = useState<AccountDTO[]>([]);
  const [gl, setGl] = useState<GLEntryDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [view, setView] = useState<'tb' | 'bs' | 'coa' | 'gl'>('tb');

  async function load() {
    setLoading(true); setErr(null);
    try {
      const [t, b, c, g] = await Promise.all([
        accounting.trialBalance(),
        accounting.balanceSheet(),
        accounting.chartOfAccounts(),
        accounting.generalLedger({ limit: 100 }),
      ]);
      setTb(t); setBs(b); setCoa(c); setGl(g);
    } catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (loading) return <LoadingCard/>;
  if (err) return <ErrorCard text={err}/>;

  return (
    <div>
      <div className="flex gap-2 mb-4 flex-wrap">
        {([
          ['tb', 'Trial Balance'],
          ['bs', 'Balance Sheet'],
          ['coa', 'Chart of Accounts'],
          ['gl', 'General Ledger'],
        ] as const).map(([k, label]) => (
          <button key={k} onClick={() => setView(k)}
            className={`chip ${view === k ? 'border-accent text-accent' : ''}`}>
            {label}
          </button>
        ))}
      </div>

      {view === 'tb' && tb && (
        <div className="card !p-0 overflow-hidden">
          <div className="px-4 py-3 border-b border-bg-border flex justify-between items-center">
            <h3 className="font-semibold">Trial Balance · as of {tb.as_of}</h3>
            <span className={`chip ${tb.is_balanced ? 'border-accent-good/40 text-accent-good' : 'border-accent-bad/40 text-accent-bad'}`}>
              {tb.is_balanced ? '✓ Balanced' : '⚠ Not balanced'}
            </span>
          </div>
          <table className="w-full text-sm">
            <thead className="bg-bg-raised text-xs text-fg-muted">
              <tr>
                <th className="text-left p-3">Code</th>
                <th className="text-left p-3">Account</th>
                <th className="text-left p-3">Type</th>
                <th className="text-right p-3">Debit</th>
                <th className="text-right p-3">Credit</th>
              </tr>
            </thead>
            <tbody>
              {tb.lines.map((l) => (
                <tr key={l.account_code} className="border-t border-bg-border/60">
                  <td className="p-3 font-mono text-xs">{l.account_code}</td>
                  <td className="p-3 font-medium">{l.account_name}</td>
                  <td className="p-3 text-fg-muted text-xs">{l.account_type}</td>
                  <td className="p-3 text-right font-mono">
                    {l.debit_minor ? inr(l.debit_minor) : '—'}
                  </td>
                  <td className="p-3 text-right font-mono">
                    {l.credit_minor ? inr(l.credit_minor) : '—'}
                  </td>
                </tr>
              ))}
              <tr className="font-bold bg-bg-raised border-t-2 border-accent">
                <td colSpan={3} className="p-3 text-right">Totals</td>
                <td className="p-3 text-right font-mono">{inr(tb.total_debit_minor)}</td>
                <td className="p-3 text-right font-mono">{inr(tb.total_credit_minor)}</td>
              </tr>
            </tbody>
          </table>
        </div>
      )}

      {view === 'bs' && bs && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <BSSection title="Assets" section={bs.assets}/>
          <BSSection title="Liabilities" section={bs.liabilities}/>
          <BSSection title="Equity" section={bs.equity}/>
          <div className="md:col-span-3 card">
            <div className="flex justify-between items-center">
              <span className="font-semibold">Assets = Liabilities + Equity</span>
              <span className={`chip ${bs.is_balanced ? 'border-accent-good/40 text-accent-good' : 'border-accent-bad/40 text-accent-bad'}`}>
                {bs.is_balanced ? '✓ Balanced' : `⚠ Off by ${inr(bs.assets.total_minor - bs.liabilities.total_minor - bs.equity.total_minor)}`}
              </span>
            </div>
          </div>
        </div>
      )}

      {view === 'coa' && (
        <div className="card !p-0 overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-bg-raised text-xs text-fg-muted">
              <tr>
                <th className="text-left p-3">Code</th>
                <th className="text-left p-3">Account</th>
                <th className="text-left p-3">Type</th>
                <th className="text-left p-3">Normal side</th>
                <th className="text-left p-3">Status</th>
              </tr>
            </thead>
            <tbody>
              {coa.map((a) => (
                <tr key={a.id} className="border-t border-bg-border/60">
                  <td className="p-3 font-mono text-xs">{a.code}</td>
                  <td className="p-3 font-medium">{a.name}</td>
                  <td className="p-3 text-fg-muted text-xs">{a.type}</td>
                  <td className="p-3 text-xs font-mono">{a.normal_side.toUpperCase()}</td>
                  <td className="p-3">
                    {a.is_active
                      ? <span className="chip border-accent-good/40 text-accent-good">Active</span>
                      : <span className="chip">Inactive</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {view === 'gl' && (
        <div className="card !p-0 overflow-hidden">
          <div className="px-4 py-3 border-b border-bg-border">
            <h3 className="font-semibold">General Ledger (today, most recent 100 entries)</h3>
          </div>
          {!gl.length ? (
            <div className="p-4 text-fg-muted text-sm">No journal entries today.</div>
          ) : (
            <table className="w-full text-sm">
              <thead className="bg-bg-raised text-xs text-fg-muted">
                <tr>
                  <th className="text-left p-3">Time</th>
                  <th className="text-left p-3">Account</th>
                  <th className="text-left p-3">Memo</th>
                  <th className="text-right p-3">Debit</th>
                  <th className="text-right p-3">Credit</th>
                </tr>
              </thead>
              <tbody>
                {gl.map((e, i) => (
                  <tr key={i} className="border-t border-bg-border/60">
                    <td className="p-3 font-mono text-xs">
                      {new Date(e.occurred_at).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' })}
                    </td>
                    <td className="p-3">
                      <span className="font-mono text-xs text-fg-muted">{e.account_code}</span> {e.account_name}
                    </td>
                    <td className="p-3 text-fg-muted text-xs">{e.memo}</td>
                    <td className="p-3 text-right font-mono">{e.debit_minor ? inr(e.debit_minor) : '—'}</td>
                    <td className="p-3 text-right font-mono">{e.credit_minor ? inr(e.credit_minor) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}

function BSSection({ title, section }: { title: string; section: BalanceSheetDTO['assets'] }) {
  return (
    <div className="card">
      <h4 className="font-semibold mb-3 text-fg-muted uppercase tracking-wider text-xs">{title}</h4>
      {section.lines.map((l, i) => (
        <div key={i} className="flex justify-between py-1.5 text-sm">
          <span>{l.name}</span>
          <span className={`font-mono ${l.amount_minor < 0 ? 'text-accent-bad' : ''}`}>{inr(l.amount_minor)}</span>
        </div>
      ))}
      <div className="flex justify-between font-bold border-t border-bg-border pt-2 mt-2">
        <span>Total {title.toLowerCase()}</span>
        <span className="font-mono">{inr(section.total_minor)}</span>
      </div>
    </div>
  );
}

// ============================================================================
// LOSSES TAB
// ============================================================================
function LossesTab() {
  const [data, setData] = useState<LossesDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try { setData(await insights.losses()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (loading) return <LoadingCard/>;
  if (err) return <ErrorCard text={err}/>;
  if (!data) return null;

  return (
    <div>
      <p className="text-fg-muted text-sm mb-3">
        {data.from_date} → {data.to_date} · waste + damage + negative-stock corrections
      </p>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
        <Stat label="Waste" value={inr(data.waste_minor)} tone={data.waste_minor > 0 ? 'bad' : 'default'}/>
        <Stat label="Damage" value={inr(data.damage_minor)} tone={data.damage_minor > 0 ? 'bad' : 'default'}/>
        <Stat label="Negative stock" value={inr(data.negative_stock_minor)}
          tone={data.negative_stock_minor > 0 ? 'gold' : 'default'}/>
        <Stat label="Total losses" value={inr(data.total_loss_minor)}
          tone={data.total_loss_minor > 0 ? 'bad' : 'good'}/>
      </div>

      <div className="card !p-0 overflow-hidden">
        <div className="px-4 py-3 border-b border-bg-border">
          <h3 className="font-semibold">By ingredient</h3>
        </div>
        {!data.lines.length ? (
          <div className="p-4 text-fg-muted text-sm">
            <span className="text-accent-good">✓ No losses in this period.</span> Keep it up.
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-bg-raised text-xs text-fg-muted">
              <tr>
                <th className="text-left p-3">SKU</th>
                <th className="text-left p-3">Ingredient</th>
                <th className="text-right p-3">Qty lost</th>
                <th className="text-right p-3">Cost lost</th>
                <th className="text-right p-3">Events</th>
              </tr>
            </thead>
            <tbody>
              {data.lines.map((l) => (
                <tr key={l.ingredient_id} className="border-t border-bg-border/60">
                  <td className="p-3 font-mono text-xs">{l.sku}</td>
                  <td className="p-3 font-medium">{l.name}</td>
                  <td className="p-3 text-right font-mono">{l.qty_lost.toFixed(2)}</td>
                  <td className="p-3 text-right font-mono text-accent-bad">{inr(l.cost_lost_minor)}</td>
                  <td className="p-3 text-right font-mono text-fg-muted">{l.movement_count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

// ============================================================================
// shared bits
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
function Stat({ label, value, tone = 'default' }: {
  label: string; value: string; tone?: 'default' | 'good' | 'bad' | 'gold';
}) {
  const color =
    tone === 'bad' ? 'text-accent-bad' :
    tone === 'good' ? 'text-accent-good' :
    tone === 'gold' ? 'text-accent-gold' : 'text-fg';
  return (
    <div className="card">
      <div className="text-xs text-fg-muted uppercase tracking-wider">{label}</div>
      <div className={`text-2xl font-bold mt-1 ${color}`}>{value}</div>
    </div>
  );
}
function LoadingCard() {
  return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;
}
function ErrorCard({ text }: { text: string }) {
  return <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
    <AlertTriangle size={14}/> {text}
  </div>;
}
