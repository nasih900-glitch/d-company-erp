/**
 * Kitchen Display System — full-screen tiles, no shell.
 *
 * Polls /kitchen/queue every 3 seconds. Kitchen staff taps the state
 * buttons (Received → Preparing → Ready → Served). The cashier on
 * POS sees the state change on their order list.
 *
 * Mounted at /kitchen — open on the kitchen iPad in full-screen mode.
 */
import { useEffect, useRef, useState } from 'react';
import {
  ChefHat, Clock, CheckCircle2, Flame, Coffee, IceCream,
  Utensils, AlertTriangle, Loader2,
} from 'lucide-react';

import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import { kitchen, type KitchenOrderDTO } from '@/lib/erp-api';

const TYPE_ICON: Record<string, React.ReactNode> = {
  food:    <Utensils size={14}/>,
  drink:   <Coffee size={14}/>,
  dessert: <IceCream size={14}/>,
  hookah:  <Flame size={14}/>,
};

const STATE_BG: Record<string, string> = {
  received:  'bg-bg-surface border-fg-muted/40',
  preparing: 'bg-accent-gold/15 border-accent-gold/60',
  ready:     'bg-accent-good/20 border-accent-good/70',
  served:    'bg-fg-muted/10 border-fg-muted/30 opacity-60',
};

const NEXT_STATE: Record<string, KitchenOrderDTO['kitchen_state']> = {
  received:  'preparing',
  preparing: 'ready',
  ready:     'served',
  served:    'served',
};

const NEXT_LABEL: Record<string, string> = {
  received:  'Start preparing',
  preparing: 'Mark ready',
  ready:     'Mark served',
  served:    '—',
};

export default function KitchenScreen() {
  const [orders, setOrders] = useState<KitchenOrderDTO[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [lastSync, setLastSync] = useState<Date | null>(null);
  const initialLoad = useRef(true);

  async function load() {
    try {
      const data = await kitchen.queue();
      const visibleOrders = data
        .map((order) => ({
          ...order,
          lines: order.lines.filter((line) => isAppStoreAllowedType(line.type)),
        }))
        .filter((order) => order.lines.length > 0);
      setOrders(visibleOrders);
      setLastSync(new Date());
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      initialLoad.current = false;
    }
  }

  useEffect(() => {
    load();
    const id = setInterval(load, 3000);
    return () => clearInterval(id);
  }, []);

  async function advance(o: KitchenOrderDTO) {
    if (o.kitchen_state === 'served') return;
    try {
      await kitchen.setState(o.id, NEXT_STATE[o.kitchen_state]);
      await load();
    } catch (e) {
      alert((e as Error).message);
    }
  }

  const counts = {
    received:  orders.filter((o) => o.kitchen_state === 'received').length,
    preparing: orders.filter((o) => o.kitchen_state === 'preparing').length,
    ready:     orders.filter((o) => o.kitchen_state === 'ready').length,
  };

  return (
    <div className="min-h-screen bg-bg text-fg">
      <header className="sticky top-0 z-10 bg-bg-surface border-b border-bg-border p-4 flex items-center justify-between"
        style={{ paddingTop: 'max(1rem, env(safe-area-inset-top))' }}>
        <div className="flex items-center gap-3">
          <ChefHat size={24} className="text-accent"/>
          <h1 className="text-xl font-bold">Kitchen</h1>
        </div>
        <div className="flex items-center gap-3 text-sm">
          <span className="chip border-fg-muted/40">{counts.received} new</span>
          <span className="chip border-accent-gold/40 text-accent-gold">{counts.preparing} preparing</span>
          <span className="chip border-accent-good/40 text-accent-good">{counts.ready} ready</span>
          <span className="text-xs text-fg-muted hidden sm:inline">
            {lastSync ? `synced ${Math.floor((Date.now() - lastSync.getTime()) / 1000)}s ago` : 'syncing…'}
          </span>
        </div>
      </header>

      {err && (
        <div className="max-w-5xl mx-auto m-4 p-3 rounded-lg bg-accent-bad/15 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
          <AlertTriangle size={14}/> {err}
        </div>
      )}

      <main className="p-4">
        {initialLoad.current ? (
          <div className="text-center text-fg-muted flex items-center justify-center gap-2 py-12">
            <Loader2 className="animate-spin" size={18}/> Loading…
          </div>
        ) : !orders.length ? (
          <div className="text-center text-fg-muted py-12">
            <ChefHat size={48} className="mx-auto mb-3 opacity-30"/>
            <p className="text-lg">No orders right now</p>
            <p className="text-sm">Incoming tickets will appear here in real time.</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3">
            {orders.map((o) => (
              <div key={o.id}
                className={`rounded-xl border-2 p-4 transition ${STATE_BG[o.kitchen_state]}`}>
                <div className="flex justify-between items-start mb-3">
                  <div>
                    <div className="font-bold text-lg">
                      {o.invoice_no || `Order #${o.id.slice(0, 6)}`}
                    </div>
                    <div className="text-xs text-fg-muted">
                      {o.type} · {o.customer_name || 'Walk-in'}
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="flex items-center gap-1 text-xs text-fg-muted">
                      <Clock size={11}/> {o.minutes_waiting}m
                    </div>
                    {o.kitchen_state === 'ready' && (
                      <CheckCircle2 size={20} className="text-accent-good mt-1"/>
                    )}
                  </div>
                </div>

                <div className="space-y-1.5 mb-3">
                  {o.lines.map((ln, i) => (
                    <div key={i} className="flex items-center gap-2 text-sm">
                      <span className="text-fg-muted shrink-0">
                        {TYPE_ICON[ln.type] ?? <Utensils size={14}/>}
                      </span>
                      <span className="font-mono text-fg-muted text-xs shrink-0 w-8 text-right">
                        {ln.qty}×
                      </span>
                      <span className="flex-1 truncate">{ln.name}</span>
                    </div>
                  ))}
                </div>

                {o.kitchen_state !== 'served' && (
                  <button onClick={() => advance(o)}
                    className="w-full mt-3 py-2.5 rounded-lg font-bold text-sm
                               bg-accent text-bg hover:bg-accent/90 active:scale-95 transition">
                    {NEXT_LABEL[o.kitchen_state]}
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}
