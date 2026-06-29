/**
 * Live POS — calls the FastAPI backend.
 *
 * Differs from POSScreen.tsx (demo) in three ways:
 *   1. Menu items loaded from /api/v1/menu/items on mount.
 *   2. Pay button POSTs to /api/v1/pos/orders with an Idempotency-Key.
 *   3. Receipt rendered from the BACKEND's PricedOrder response so the
 *      invoice number, CGST/SGST split, and round-off are exactly what
 *      will land in GSTR-1.
 *
 * Auto-opens a shift if one isn't already in localStorage.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Plus, Minus, Trash2, ShoppingCart, Receipt as ReceiptIcon,
  Banknote, CreditCard, Smartphone, QrCode, X, Check, Loader2,
  Search, UserRound, Crown, AlertCircle,
} from 'lucide-react';

import { inr } from '@/lib/inr';
import {
  customers,
  memberships,
  menu,
  pos,
  type CustomerDTO,
  type MembershipTierDTO,
  type MenuItemDTO,
  type OrderDTO,
  type SubscriptionDTO,
} from '@/lib/erp-api';
import { COMPANY } from '@/lib/demo-data';
import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import LiveReceipt from './LiveReceipt';

type CartLine = { item: MenuItemDTO; qty: number };
type PayMethod = 'cash' | 'upi' | 'card' | 'qr';
type OrderType = 'dine_in' | 'takeaway' | 'delivery';
type DeliveryVia = 'inhouse' | 'zomato' | 'swiggy' | 'ubereats' | 'other_aggregator';
type CustomerLookupState = 'idle' | 'found' | 'new' | 'error';

const CATEGORY_FROM_TYPE: Record<string, string> = {
  food: 'Food', drink: 'Drinks', dessert: 'Desserts', gaming: 'Gaming', event: 'Events',
};

export default function LivePOSScreen() {
  const [items, setItems] = useState<MenuItemDTO[]>([]);
  const [shiftId, setShiftId] = useState<string | null>(() => localStorage.getItem('shift_id'));
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [cart, setCart] = useState<CartLine[]>([]);
  const [orderType, setOrderType] = useState<OrderType>('dine_in');
  const [deliveryVia, setDeliveryVia] = useState<DeliveryVia>('inhouse');
  const [deliveryStateCode, setDeliveryStateCode] = useState('32');
  const [query, setQuery] = useState('');
  const [customerPhone, setCustomerPhone] = useState('');
  const [customerName, setCustomerName] = useState('');
  const [customer, setCustomer] = useState<CustomerDTO | null>(null);
  const [subscription, setSubscription] = useState<SubscriptionDTO | null>(null);
  const [membershipTier, setMembershipTier] = useState<MembershipTierDTO | null>(null);
  const [customerLookupState, setCustomerLookupState] = useState<CustomerLookupState>('idle');
  const [customerMessage, setCustomerMessage] = useState<string | null>(null);
  const [customerBusy, setCustomerBusy] = useState(false);
  const [showPay, setShowPay] = useState(false);
  const [paying, setPaying] = useState(false);
  const [receipt, setReceipt] = useState<OrderDTO | null>(null);
  const pendingOrderRef = useRef<OrderDTO | null>(null);
  const checkoutKeyRef = useRef<string | null>(null);

  // Load menu items + ensure a shift is open
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const storedShiftId = localStorage.getItem('shift_id');
        const all = await menu.items();
        if (!cancelled) setItems(all.filter((i) => i.is_available && isAppStoreAllowedType(i.type)));
        if (storedShiftId) {
          if (!cancelled) setShiftId(storedShiftId);
          return;
        }
        const openedShift = await pos.openShift(0);
        localStorage.setItem('shift_id', openedShift.id);
        if (!cancelled) setShiftId(openedShift.id);
      } catch (e) {
        if (!cancelled) setError((e as Error).message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // Group by type into pseudo-categories
  const categories = useMemo(() => {
    const groups = new Map<string, MenuItemDTO[]>();
    for (const i of items) {
      const cat = CATEGORY_FROM_TYPE[i.type] || i.type;
      if (!groups.has(cat)) groups.set(cat, []);
      groups.get(cat)!.push(i);
    }
    return Array.from(groups.entries());
  }, [items]);
  const [activeCat, setActiveCat] = useState<string | null>(null);
  useEffect(() => { if (!activeCat && categories.length) setActiveCat(categories[0][0]); }, [categories, activeCat]);
  const q = query.trim().toLowerCase();
  const filtered = useMemo(() => {
    const base = q ? items : categories.find(([c]) => c === activeCat)?.[1] ?? [];
    return base.filter((item) => {
      if (!q) return true;
      return [item.name, item.sku, item.type].some((value) => value.toLowerCase().includes(q));
    });
  }, [activeCat, categories, items, q]);
  const cartQty = useMemo(() => cart.reduce((sum, line) => sum + line.qty, 0), [cart]);

  function add(item: MenuItemDTO) {
    setCart((c) => {
      const ex = c.find((l) => l.item.id === item.id);
      return ex ? c.map((l) => l.item.id === item.id ? { ...l, qty: l.qty + 1 } : l) : [...c, { item, qty: 1 }];
    });
  }
  function adjust(id: string, delta: number) {
    setCart((c) => c.map((l) => l.item.id === id ? { ...l, qty: Math.max(0, l.qty + delta) } : l).filter((l) => l.qty > 0));
  }

  // Cart preview math (backend does the canonical math on submit)
  const preview = useMemo(() => {
    let gross = 0;
    for (const l of cart) gross += l.item.base_price_minor * l.qty;
    return gross;
  }, [cart]);
  const estimatedMembershipDiscount = useMemo(() => {
    if (!membershipTier) return 0;
    return cart.reduce((sum, line) => {
      const rate = discountRateForItem(line.item, membershipTier);
      return sum + Math.round(line.item.base_price_minor * line.qty * rate);
    }, 0);
  }, [cart, membershipTier]);
  const estimatedPayable = Math.max(0, preview - estimatedMembershipDiscount);

  useEffect(() => {
    pendingOrderRef.current = null;
    checkoutKeyRef.current = null;
  }, [cart, customerName, customerPhone, deliveryStateCode, deliveryVia, orderType]);

  async function lookupCustomer() {
    const phone = customerPhone.trim();
    setCustomer(null);
    setSubscription(null);
    setMembershipTier(null);
    setCustomerMessage(null);

    if (!phone) {
      setCustomerLookupState('idle');
      return;
    }
    if (phone.length < 6) {
      setCustomerLookupState('error');
      setCustomerMessage('Enter a valid phone number.');
      return;
    }

    setCustomerBusy(true);
    try {
      const found = await customers.byPhone(phone);
      if (!found) {
        setCustomerLookupState('new');
        setCustomerMessage('New customer. The sale will create the profile.');
        return;
      }
      setCustomer(found);
      if (!customerName.trim() && found.name) setCustomerName(found.name);
      const sub = await memberships.getCustomerSubscription(found.id);
      setSubscription(sub);
      if (sub) {
        const tiers = await memberships.listTiers();
        setMembershipTier(tiers.find((tier) => tier.id === sub.tier_id) ?? null);
        setCustomerLookupState('found');
        setCustomerMessage(`${sub.tier_name} membership active. Discounts apply automatically.`);
      } else {
        setCustomerLookupState('found');
        setCustomerMessage(`${found.name || found.phone} found. No active membership.`);
      }
    } catch (e) {
      setCustomerLookupState('error');
      setCustomerMessage((e as Error).message);
    } finally {
      setCustomerBusy(false);
    }
  }

  function clearCustomer() {
    setCustomerPhone('');
    setCustomerName('');
    setCustomer(null);
    setSubscription(null);
    setMembershipTier(null);
    setCustomerLookupState('idle');
    setCustomerMessage(null);
  }

  async function pay(method: PayMethod) {
    if (!cart.length || !shiftId) return;
    setPaying(true);
    setError(null);
    try {
      const fallbackNonce = `${Date.now()}:${Math.random().toString(36).slice(2)}`;
      const checkoutKey = checkoutKeyRef.current ?? `${shiftId}:${globalThis.crypto?.randomUUID?.() ?? fallbackNonce}`;
      checkoutKeyRef.current = checkoutKey;
      let order = pendingOrderRef.current;
      if (!order) {
        order = await pos.createOrder(
          {
            type: orderType,
            shift_id: shiftId,
            lines: cart.map((l) => ({ menu_item_id: l.item.id, qty: l.qty })),
            delivery_via: orderType === 'delivery' ? deliveryVia : undefined,
            customer_state_code: orderType === 'delivery' ? deliveryStateCode.trim() || undefined : undefined,
            place_of_supply_state_code: orderType === 'delivery' ? deliveryStateCode.trim() || undefined : undefined,
            customer_name: customerName.trim() || undefined,
            customer_phone: customerPhone.trim() || undefined,
          },
          `order:${checkoutKey}`,
        );
        pendingOrderRef.current = order;
      }
      await pos.recordPayment(order.id, {
        method,
        amount_minor: order.total_minor,
        tendered_minor: method === 'cash' ? order.total_minor : undefined,
      }, `payment:${checkoutKey}`);
      setReceipt({ ...order, status: 'paid' });
      pendingOrderRef.current = null;
      checkoutKeyRef.current = null;
      setShowPay(false);
      setCart([]);
      clearCustomer();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPaying(false);
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96 text-fg-muted">
        <Loader2 className="animate-spin mr-2" /> Connecting to {COMPANY.name} backend…
      </div>
    );
  }
  if (error && !items.length) {
    return (
      <div className="card max-w-md mx-auto mt-12">
        <h3 className="text-lg font-bold text-accent-bad mb-2">Can't reach the backend</h3>
        <p className="text-sm text-fg-muted mb-3">{error}</p>
        <p className="text-xs text-fg-muted">
          Make sure the backend container is running: <code className="text-fg">docker compose ps</code>.
          Then refresh.
        </p>
      </div>
    );
  }

  return (
    <div className="min-h-full pb-32 xl:grid xl:grid-cols-[minmax(0,1fr)_440px] xl:gap-6 xl:pb-0">
      <section className="min-w-0">
        <header className="flex items-start justify-between mb-4 gap-4 flex-wrap">
          <div>
            <h2 className="text-2xl font-bold">POS · Live</h2>
            <p className="text-fg-muted text-sm">
              Shift open · {items.length} items · backend live
            </p>
          </div>
          <div className="scroll-strip flex max-w-full gap-1 rounded-xl border border-bg-border bg-bg-surface p-1">
            {(['dine_in', 'takeaway', 'delivery'] as OrderType[]).map((t) => (
              <button key={t}
                onClick={() => setOrderType(t)}
                className={`shrink-0 rounded-lg px-3 py-2 text-sm font-medium transition ${
                  orderType === t ? 'bg-accent text-bg' : 'text-fg-muted hover:text-fg'
                }`}
              >{t.replace('_', ' ')}</button>
            ))}
          </div>
        </header>

        {orderType === 'delivery' && (
          <section className="card !p-3 mb-4 max-w-3xl">
            <div className="grid grid-cols-1 md:grid-cols-[1fr_140px] gap-2">
              <select
                className="input !min-h-[40px] !py-2"
                value={deliveryVia}
                onChange={(e) => setDeliveryVia(e.target.value as DeliveryVia)}
              >
                <option value="inhouse">In-house delivery</option>
                <option value="zomato">Zomato</option>
                <option value="swiggy">Swiggy</option>
                <option value="ubereats">Uber Eats</option>
                <option value="other_aggregator">Other aggregator</option>
              </select>
              <input
                className="input !min-h-[40px] !py-2"
                value={deliveryStateCode}
                inputMode="numeric"
                maxLength={2}
                placeholder="State code"
                onChange={(e) => setDeliveryStateCode(e.target.value.replace(/\D/g, '').slice(0, 2))}
              />
            </div>
            {deliveryVia !== 'inhouse' && (
              <p className="text-xs text-fg-muted mt-2">
                Platform delivery is tracked separately; D Company GST is zero on this bill.
              </p>
            )}
          </section>
        )}

        <CustomerAttachPanel
          phone={customerPhone}
          name={customerName}
          customer={customer}
          subscription={subscription}
          tier={membershipTier}
          lookupState={customerLookupState}
          message={customerMessage}
          busy={customerBusy}
          onPhoneChange={(value) => {
            setCustomerPhone(value);
            setCustomer(null);
            setSubscription(null);
            setMembershipTier(null);
            setCustomerLookupState(value.trim() ? 'idle' : 'idle');
            setCustomerMessage(null);
          }}
          onNameChange={setCustomerName}
          onLookup={lookupCustomer}
          onClear={clearCustomer}
        />

        <div className="relative mb-4 max-w-xl">
          <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-fg-muted"/>
          <input
            className="input !pl-9 !min-h-[42px] !py-2"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search menu"
          />
        </div>

        <div className="scroll-strip flex gap-2 mb-4 pb-1 -mx-3 px-3 md:mx-0 md:px-0">
          {categories.map(([c, categoryItems]) => (
            <button key={c} onClick={() => setActiveCat(c)}
              className={`shrink-0 px-4 py-2 rounded-full text-sm font-medium whitespace-nowrap border transition ${
                activeCat === c ? 'bg-accent text-bg border-accent' : 'bg-bg-surface text-fg-muted border-bg-border hover:text-fg'
              }`}
            >
              {c}
              <span className="ml-2 text-[10px] opacity-70">{categoryItems.length}</span>
            </button>
          ))}
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-3">
          {!filtered.length && (
            <div className="card col-span-full text-sm text-fg-muted">No menu items match.</div>
          )}
          {filtered.map((item) => (
            <button key={item.id} onClick={() => add(item)} className="card text-left hover:border-accent transition group p-3 sm:p-4">
              <div className="break-words text-sm font-semibold">{item.name}</div>
              <div className="text-fg-muted text-xs mt-1 flex items-center justify-between">
                <span>{inr(item.base_price_minor)}</span>
                <span className="chip !py-0 !px-2 !text-[10px]">{(item.tax_rate * 100).toFixed(0)}%</span>
              </div>
            </button>
          ))}
        </div>
      </section>

      <aside className="hidden xl:flex card flex-col">
        <header className="flex items-center justify-between mb-3">
          <h3 className="text-lg font-semibold flex items-center gap-2"><ShoppingCart size={18} /> Cart · {cartQty}</h3>
          {cart.length > 0 && <button onClick={() => setCart([])} className="text-xs text-fg-muted hover:text-accent-bad">Clear</button>}
        </header>

        <div className="flex-1 space-y-2 overflow-auto min-h-[120px]">
          {!cart.length && <p className="text-fg-muted text-sm text-center py-8">Tap items to build the order.</p>}
          {cart.map((l) => (
            <div key={l.item.id} className="flex items-center gap-2 py-1">
              <div className="flex-1 min-w-0">
                <div className="font-medium text-sm truncate">{l.item.name}</div>
                <div className="text-xs text-fg-muted">{inr(l.item.base_price_minor)} ea</div>
              </div>
              <div className="w-20 text-right font-mono text-sm">{inr(l.item.base_price_minor * l.qty)}</div>
              <div className="flex items-center gap-1">
                <button onClick={() => adjust(l.item.id, -1)} className="btn btn-ghost !min-h-[36px] !p-2"><Minus size={12} /></button>
                <span className="w-6 text-center font-mono text-sm">{l.qty}</span>
                <button onClick={() => adjust(l.item.id, 1)} className="btn btn-ghost !min-h-[36px] !p-2"><Plus size={12} /></button>
                <button onClick={() => adjust(l.item.id, -l.qty)} className="btn btn-ghost !min-h-[36px] !p-2"><Trash2 size={12} /></button>
              </div>
            </div>
          ))}
        </div>

        <div className="mt-3 pt-3 border-t border-bg-border text-sm">
          {estimatedMembershipDiscount > 0 && (
            <div className="flex justify-between text-accent-good mb-1">
              <span>Membership discount (est.)</span><span>-{inr(estimatedMembershipDiscount)}</span>
            </div>
          )}
          <div className="flex justify-between text-lg font-bold">
            <span>Total (est.)</span><span>{inr(estimatedPayable)}</span>
          </div>
          <p className="text-xs text-fg-muted mt-1">Final GST split &amp; round-off computed by backend on charge.</p>
        </div>

        <button onClick={() => setShowPay(true)} disabled={!cart.length || paying}
          className="btn btn-primary mt-3 disabled:opacity-40 disabled:cursor-not-allowed">
          <ReceiptIcon size={16} /> Charge {inr(estimatedPayable)}
        </button>
        {error && <p className="text-accent-bad text-xs mt-2">{error}</p>}
      </aside>

      {/* Mobile sticky charge bar (live mode) */}
      {cart.length > 0 && (
        <button
          onClick={() => setShowPay(true)}
          className="xl:hidden fixed left-3 right-3 bottom-3 z-30 btn btn-primary !min-h-[60px] min-w-0 text-sm font-bold shadow-2xl sm:text-base"
          style={{ bottom: 'max(0.75rem, calc(env(safe-area-inset-bottom) + 0.5rem))' }}
        >
          <ReceiptIcon size={18}/>
          <span className="shrink-0">{cartQty} items</span>
          <span className="opacity-80">·</span>
          <span className="min-w-0 truncate">Charge {inr(estimatedPayable)}</span>
        </button>
      )}

      {showPay && (
        <Modal title={`Charge ${inr(estimatedPayable)}`} onClose={() => !paying && setShowPay(false)}>
          {estimatedMembershipDiscount > 0 && (
            <div className="mb-3 rounded-xl border border-accent-good/30 bg-accent-good/10 px-3 py-2 text-xs text-accent-good">
              Estimated membership discount: {inr(estimatedMembershipDiscount)}. Backend will compute the final bill.
            </div>
          )}
          <div className="grid grid-cols-2 gap-3">
            <PayButton icon={<Banknote size={28}/>}   label="Cash" sub="Drawer opens"    disabled={paying} onClick={() => pay('cash')} />
            <PayButton icon={<Smartphone size={28}/>} label="UPI"  sub="GPay / PhonePe"   disabled={paying} onClick={() => pay('upi')} />
            <PayButton icon={<CreditCard size={28}/>} label="Card" sub="Visa / RuPay"     disabled={paying} onClick={() => pay('card')} />
            <PayButton icon={<QrCode size={28}/>}     label="QR"   sub="Print dynamic QR" disabled={paying} onClick={() => pay('qr')} />
          </div>
          {paying && (
            <div className="mt-4 flex items-center justify-center gap-2 text-fg-muted text-sm">
              <Loader2 size={14} className="animate-spin" /> Saving order…
            </div>
          )}
        </Modal>
      )}

      {receipt && (
        <Modal title="Receipt" onClose={() => setReceipt(null)} wide>
          <LiveReceipt order={receipt} />
          <div className="flex gap-2 mt-4 print:hidden">
            <button onClick={() => window.print()} className="btn btn-primary flex-1"><ReceiptIcon size={16}/> Print</button>
            <button onClick={() => setReceipt(null)} className="btn btn-ghost"><Check size={16}/> Done</button>
          </div>
        </Modal>
      )}
    </div>
  );
}

function CustomerAttachPanel({
  phone,
  name,
  customer,
  subscription,
  tier,
  lookupState,
  message,
  busy,
  onPhoneChange,
  onNameChange,
  onLookup,
  onClear,
}: {
  phone: string;
  name: string;
  customer: CustomerDTO | null;
  subscription: SubscriptionDTO | null;
  tier: MembershipTierDTO | null;
  lookupState: CustomerLookupState;
  message: string | null;
  busy: boolean;
  onPhoneChange: (value: string) => void;
  onNameChange: (value: string) => void;
  onLookup: () => void;
  onClear: () => void;
}) {
  const hasCustomerInput = phone.trim() || name.trim();
  const tone =
    lookupState === 'error' ? 'text-accent-bad border-accent-bad/40 bg-accent-bad/10' :
    subscription ? 'text-accent-good border-accent-good/40 bg-accent-good/10' :
    lookupState === 'new' ? 'text-accent-gold border-accent-gold/40 bg-accent-gold/10' :
    'text-fg-muted border-bg-border bg-bg-surface';

  return (
    <section className="card !p-3 mb-4 max-w-3xl">
      <div className="flex items-center justify-between gap-3 mb-3">
        <div className="flex items-center gap-2 font-semibold text-sm">
          <UserRound size={15} className="text-accent"/> Customer
        </div>
        {hasCustomerInput && (
          <button className="text-xs text-fg-muted hover:text-accent-bad" onClick={onClear}>
            Clear
          </button>
        )}
      </div>
      <div className="grid grid-cols-1 md:grid-cols-[180px_1fr_auto] gap-2">
        <input
          className="input !min-h-[40px] !py-2"
          value={phone}
          inputMode="tel"
          placeholder="Phone"
          onChange={(e) => onPhoneChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              onLookup();
            }
          }}
        />
        <input
          className="input !min-h-[40px] !py-2"
          value={name}
          placeholder="Name"
          onChange={(e) => onNameChange(e.target.value)}
        />
        <button className="btn btn-ghost !min-h-[40px] !py-2 !px-4" onClick={onLookup} disabled={busy || !phone.trim()}>
          {busy ? <Loader2 size={13} className="animate-spin"/> : <Search size={13}/>}
          Find
        </button>
      </div>

      {message && (
        <div className={`mt-3 rounded-xl border px-3 py-2 text-xs flex items-start gap-2 ${tone}`}>
          {lookupState === 'error' ? <AlertCircle size={13} className="mt-0.5 shrink-0"/> : <Crown size={13} className="mt-0.5 shrink-0"/>}
          <div>
            <div>{message}</div>
            {customer && (
              <div className="mt-1 opacity-80">
                Visits: {customer.visit_count} · Points: {customer.loyalty_points.toLocaleString('en-IN')}
              </div>
            )}
            {tier && (
              <div className="mt-1 opacity-90">
                Food {(tier.food_discount_pct * 100).toFixed(0)}% · Gaming {(tier.gaming_discount_pct * 100).toFixed(0)}%
              </div>
            )}
          </div>
        </div>
      )}
    </section>
  );
}

function discountRateForItem(item: MenuItemDTO, tier: MembershipTierDTO): number {
  if (['food', 'drink', 'dessert'].includes(item.type)) return tier.food_discount_pct;
  if (item.type === 'gaming') return tier.gaming_discount_pct;
  if (item.type === 'hookah') return tier.hookah_discount_pct;
  return 0;
}

function PayButton({ icon, label, sub, onClick, disabled }: { icon: React.ReactNode; label: string; sub: string; onClick: () => void; disabled?: boolean }) {
  return (
    <button onClick={onClick} disabled={disabled} className="card flex flex-col items-center gap-2 hover:border-accent py-5 disabled:opacity-40">
      <div className="text-accent">{icon}</div>
      <div className="font-semibold">{label}</div>
      <div className="text-xs text-fg-muted">{sub}</div>
    </button>
  );
}

function Modal({ title, children, onClose, wide }: { title: string; children: React.ReactNode; onClose: () => void; wide?: boolean }) {
  return (
    <div className="fixed inset-0 z-50 flex items-end md:items-center justify-center bg-bg/80 backdrop-blur-sm md:p-4 print:p-0 print:bg-white" onClick={onClose}>
      <div
        className={`bg-bg-surface border border-bg-border rounded-t-2xl md:rounded-2xl shadow-glow w-full ${wide ? 'md:max-w-md' : 'md:max-w-sm'} max-h-[calc(100dvh-1rem)] overflow-auto print:max-w-none print:w-auto print:bg-white print:text-black print:border-none print:shadow-none print:overflow-visible`}
        onClick={(e) => e.stopPropagation()}
        style={{ paddingBottom: 'max(0.5rem, env(safe-area-inset-bottom))' }}
      >
        <div className="flex items-center justify-between p-4 border-b border-bg-border print:hidden sticky top-0 bg-bg-surface">
          <h3 className="font-semibold">{title}</h3>
          <button onClick={onClose} className="text-fg-muted hover:text-fg p-1 -m-1"><X size={20}/></button>
        </div>
        <div className="p-4">{children}</div>
      </div>
    </div>
  );
}
