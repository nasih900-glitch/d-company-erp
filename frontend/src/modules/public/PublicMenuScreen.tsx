/**
 * Public menu — no auth required.
 *
 * Customers scan QR sticker on the table → land here → browse the menu.
 * Read-only for now; order-from-phone flow will plug into here later.
 *
 * Route: /menu (handled by App.tsx outside the auth wrapper)
 */
import { useEffect, useState } from 'react';
import { Loader2, AlertCircle, Coffee, Utensils, IceCream, Gamepad2, Tv, Flame } from 'lucide-react';

import { inr } from '@/lib/inr';
import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import { publicApi, type PublicMenuDTO, type PublicMenuItemDTO } from '@/lib/erp-api';

const TYPE_ICON: Record<string, React.ReactNode> = {
  food:    <Utensils size={16}/>,
  drink:   <Coffee size={16}/>,
  dessert: <IceCream size={16}/>,
  gaming:  <Gamepad2 size={16}/>,
  event:   <Tv size={16}/>,
  hookah:  <Flame size={16}/>,
};

export default function PublicMenuScreen() {
  const [data, setData] = useState<PublicMenuDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [activeCat, setActiveCat] = useState<string | null>(null);

  useEffect(() => {
    publicApi.menu()
      .then(setData)
      .catch((e) => setErr((e as Error).message))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg text-fg">
        <Loader2 className="animate-spin" size={32}/>
      </div>
    );
  }
  if (err) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg text-accent-bad">
        <AlertCircle size={20}/> {err}
      </div>
    );
  }
  if (!data) return null;

  const visibleItems = data.items.filter((item) => isAppStoreAllowedType(item.type));
  const visibleCategoryIds = new Set(visibleItems.map((item) => item.category_id));
  const cats = data.categories
    .filter((cat) => visibleCategoryIds.has(cat.id))
    .sort((a, b) => a.sort_order - b.sort_order);
  const filtered = activeCat
    ? visibleItems.filter((i) => i.category_id === activeCat)
    : visibleItems;
  // Group by category
  const grouped: Record<string, PublicMenuItemDTO[]> = {};
  for (const it of filtered) {
    (grouped[it.category_name] ||= []).push(it);
  }

  return (
    <div className="min-h-screen bg-bg text-fg">
      <header className="sticky top-0 bg-bg-surface border-b border-bg-border px-4 py-4 z-10 shadow-md"
        style={{ paddingTop: 'max(1rem, env(safe-area-inset-top))' }}>
        <div className="max-w-2xl mx-auto flex items-center gap-3">
          <img
            src="/brand/den-emblem-gold.png"
            alt=""
            aria-hidden="true"
            className="h-12 w-12 shrink-0 rounded-full object-contain bg-bg/80 ring-1 ring-accent-gold/50"
          />
          <div className="min-w-0">
            <h1 className="truncate text-2xl font-bold tracking-tight">{data.company_name}</h1>
            <p className="text-xs text-fg-muted">Browse our menu · prices include GST</p>
          </div>
        </div>
        {cats.length > 0 && (
          <div className="max-w-2xl mx-auto flex gap-2 overflow-x-auto pb-1 pt-3 -mx-1 px-1">
            <button onClick={() => setActiveCat(null)}
              className={`chip whitespace-nowrap ${!activeCat ? 'border-accent text-accent' : ''}`}>
              All
            </button>
            {cats.map((c) => (
              <button key={c.id} onClick={() => setActiveCat(c.id)}
                className={`chip whitespace-nowrap ${activeCat === c.id ? 'border-accent text-accent' : ''}`}>
                {c.name}
              </button>
            ))}
          </div>
        )}
      </header>

      <main className="max-w-2xl mx-auto px-4 py-6"
        style={{ paddingBottom: 'max(1.5rem, env(safe-area-inset-bottom))' }}>
        {!visibleItems.length ? (
          <div className="card text-center text-fg-muted">
            Our menu is being prepared — please ask at the counter.
          </div>
        ) : (
          Object.entries(grouped).map(([catName, items]) => (
            <section key={catName} className="mb-6">
              <h2 className="text-sm font-bold uppercase tracking-wider text-fg-muted mb-2 px-1">
                {catName}
              </h2>
              <div className="space-y-2">
                {items.map((it) => (
                  <div key={it.id} className="card flex items-start gap-3">
                    <div className="p-2 bg-bg-raised rounded-lg text-fg-muted shrink-0">
                      {TYPE_ICON[it.type] ?? <Utensils size={16}/>}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="font-medium">{it.name}</div>
                      {it.description && (
                        <div className="text-xs text-fg-muted mt-0.5">{it.description}</div>
                      )}
                    </div>
                    <div className="font-mono font-bold text-right shrink-0">
                      {inr(it.base_price_minor)}
                    </div>
                  </div>
                ))}
              </div>
            </section>
          ))
        )}

        <footer className="text-center text-xs text-fg-muted mt-8 pt-6 border-t border-bg-border">
          To place an order, please call our staff.
          {data.company_gstin && <div className="mt-1">GSTIN: {data.company_gstin}</div>}
        </footer>
      </main>
    </div>
  );
}
