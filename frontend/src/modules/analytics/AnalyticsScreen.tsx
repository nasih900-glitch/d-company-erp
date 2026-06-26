/**
 * Analytics — live KPIs from /api/v1/analytics/dashboard.
 *
 * Live mode: today's real numbers (revenue split, orders, avg ticket,
 * inventory value, low-stock count, open gaming sessions, net profit).
 * Demo mode: HOURLY_REVENUE + TOP_ITEMS fixtures.
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
} from 'recharts';
import {
  TrendingUp, ShoppingBag, Wallet, Boxes, AlertTriangle,
  Loader2, RefreshCw, Gamepad2, ArrowUpRight, ShieldCheck,
  FileText, Utensils, Users, Activity,
} from 'lucide-react';

import { inr, inrShort } from '@/lib/inr';
import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import { analytics, type DashboardKPIsDTO } from '@/lib/erp-api';
import { useAuth } from '@/modules/auth/AuthContext';

function todayISO(): string {
  const d = new Date();
  const p = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export default function AnalyticsScreen() {
  const { me, demo } = useAuth();
  const [data, setData] = useState<DashboardKPIsDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try {
      setData(await analytics.dashboard(todayISO()));
    } catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  const empty = data && data.orders_count === 0 && data.tickets_count === 0
    && data.revenue_total_minor === 0;
  const canSeeProtected = demo || me?.protected_access;

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Analytics</h2>
          <p className="text-fg-muted text-sm">
            Today's performance · live · refreshes on click
          </p>
        </div>
        <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
      </header>

      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : err ? (
        <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm">{err}</div>
      ) : !data ? null : (
        <>
          {empty && (
            <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-accent-gold text-sm">
              <b>No business activity today.</b> KPIs will start showing as soon as you take your first order at POS or sell your first event ticket.
            </div>
          )}

          <OwnerCommandCenter data={data} canSeeProtected={Boolean(canSeeProtected)}/>

          {/* KPI strip — revenue + orders + avg ticket + net profit */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
            <KPI label="Revenue"    value={inrShort(data.revenue_total_minor)} sub={inr(data.revenue_total_minor)}
              icon={<Wallet/>} tone="good"/>
            <KPI label="Orders"     value={data.orders_count.toString()}       sub={`${data.tickets_count} tickets`}
              icon={<ShoppingBag/>}/>
            <KPI label="Avg ticket" value={inr(data.avg_ticket_minor)}         sub="per receipt"
              icon={<TrendingUp/>}/>
            <KPI label="Net profit (today)" value={inrShort(data.net_profit_minor)}
              sub={inr(data.net_profit_minor)}
              icon={<TrendingUp/>} tone={data.net_profit_minor >= 0 ? 'good' : 'bad'}/>
          </div>

          {/* Operations strip */}
          <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mb-6">
            <KPI label="Inventory value" value={inrShort(data.inventory_value_minor)}
              sub="all branches" icon={<Boxes/>}/>
            <KPI label="Low-stock items" value={data.low_stock_items.toString()}
              sub="need restock" icon={<AlertTriangle/>}
              tone={data.low_stock_items > 0 ? 'bad' : 'good'}/>
            <KPI label="Open gaming sessions" value={data.open_sessions.toString()}
              sub="active now" icon={<Gamepad2/>}
              tone={data.open_sessions > 0 ? 'good' : 'default'}/>
          </div>

          {/* Revenue split chart (recharts) */}
          <div className="card mb-6">
            <h3 className="font-semibold mb-3 flex items-center gap-2">
              <TrendingUp size={16}/> Revenue by stream (today)
            </h3>
            <div style={{ width: '100%', height: 250 }}>
              <ResponsiveContainer>
                <BarChart data={[
                  { name: 'Food', value: data.revenue_food_minor / 100 },
                  { name: 'Gaming', value: data.revenue_gaming_minor / 100 },
                  ...(isAppStoreAllowedType('hookah')
                    ? [{ name: 'Hookah', value: data.revenue_hookah_minor / 100 }]
                    : []),
                  { name: 'Events', value: data.revenue_events_minor / 100 },
                ]}>
                  <XAxis dataKey="name" stroke="#999"/>
                  <YAxis stroke="#999" tickFormatter={(v) => `₹${v >= 1000 ? (v/1000).toFixed(0) + 'K' : v}`}/>
                  <Tooltip contentStyle={{ background: '#1a1d2e', border: '1px solid #2d3149' }}
                    formatter={(v: number) => [`₹${v.toLocaleString('en-IN')}`, 'Revenue']}/>
                  <Bar dataKey="value" fill="#7c3aed" radius={[6, 6, 0, 0]}/>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>

        </>
      )}
    </div>
  );
}

function OwnerCommandCenter({
  data,
  canSeeProtected,
}: {
  data: DashboardKPIsDTO;
  canSeeProtected: boolean;
}) {
  const margin = data.revenue_total_minor
    ? (data.net_profit_minor / data.revenue_total_minor) * 100
    : 0;
  const health = [
    {
      label: 'Profit',
      value: data.revenue_total_minor ? `${margin.toFixed(1)}%` : 'No sales',
      tone: data.net_profit_minor >= 0 ? 'good' : 'bad',
    },
    {
      label: 'Stock',
      value: data.low_stock_items > 0 ? `${data.low_stock_items} low` : 'OK',
      tone: data.low_stock_items > 0 ? 'bad' : 'good',
    },
    {
      label: 'Sales',
      value: data.orders_count || data.tickets_count ? `${data.orders_count + data.tickets_count} bills` : 'Quiet',
      tone: data.orders_count || data.tickets_count ? 'good' : 'default',
    },
    {
      label: 'Gaming',
      value: data.open_sessions > 0 ? `${data.open_sessions} live` : 'Idle',
      tone: data.open_sessions > 0 ? 'good' : 'default',
    },
  ] as const;

  const actions = [
    { to: '/pos', label: 'POS', icon: <Utensils size={15}/> },
    { to: '/reports', label: 'P&L', icon: <FileText size={15}/> },
    { to: '/customers', label: 'Customers', icon: <Users size={15}/> },
    { to: '/insights', label: 'Insights', icon: <Activity size={15}/> },
    ...(canSeeProtected
      ? [
          { to: '/inventory', label: 'Inventory', icon: <Boxes size={15}/> },
          { to: '/audit', label: 'Audit', icon: <ShieldCheck size={15}/> },
        ]
      : []),
  ];

  return (
    <section className="card mb-6">
      <div className="flex items-center justify-between gap-3 flex-wrap mb-4">
        <div>
          <h3 className="font-semibold">Owner dashboard</h3>
          <p className="text-xs text-fg-muted">Today · profit · stock · action points</p>
        </div>
        <div className={`chip ${data.low_stock_items || data.net_profit_minor < 0 ? 'border-accent-bad/40 text-accent-bad' : 'border-accent-good/40 text-accent-good'}`}>
          {data.low_stock_items || data.net_profit_minor < 0 ? <AlertTriangle size={12}/> : <ShieldCheck size={12}/>}
          {data.low_stock_items || data.net_profit_minor < 0 ? 'Needs attention' : 'Stable'}
        </div>
      </div>
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-2 mb-4">
        {health.map((item) => (
          <div key={item.label} className="rounded-xl border border-bg-border bg-bg-raised/60 px-3 py-2">
            <div className="text-[10px] text-fg-muted uppercase tracking-wider">{item.label}</div>
            <div className={`font-bold mt-0.5 ${
              item.tone === 'bad' ? 'text-accent-bad' :
              item.tone === 'good' ? 'text-accent-good' :
              'text-fg'
            }`}>
              {item.value}
            </div>
          </div>
        ))}
      </div>
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-2">
        {actions.map((action) => (
          <Link key={action.to} to={action.to}
            className="rounded-xl border border-bg-border bg-bg-raised/50 px-3 py-3 text-sm font-medium hover:border-accent hover:text-accent flex items-center justify-between gap-2 transition">
            <span className="min-w-0 flex items-center gap-2 truncate">{action.icon}{action.label}</span>
            <ArrowUpRight size={13}/>
          </Link>
        ))}
      </div>
    </section>
  );
}

function KPI({ label, value, sub, icon, tone = 'default' }: {
  label: string; value: string; sub?: string; icon: React.ReactNode;
  tone?: 'default' | 'good' | 'bad';
}) {
  const color = tone === 'bad' ? 'text-accent-bad' : tone === 'good' ? 'text-accent-good' : 'text-fg';
  return (
    <div className="card">
      <div className="flex items-center gap-3 mb-2">
        <div className="p-2 bg-bg-raised rounded-lg text-fg-muted">{icon}</div>
        <div className="text-xs text-fg-muted uppercase tracking-wider">{label}</div>
      </div>
      <div className={`text-2xl font-bold ${color}`}>{value}</div>
      {sub && <div className="text-xs text-fg-muted font-mono mt-1">{sub}</div>}
    </div>
  );
}
