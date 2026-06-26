import { useEffect, useMemo, useState } from 'react';
import {
  Plus, Minus, Trash2, ShoppingCart, Receipt as ReceiptIcon,
  Banknote, CreditCard, Smartphone, QrCode, X, Check, ChevronDown,
} from 'lucide-react';

import { CATEGORIES, COMPANY, MENU, type MenuItem } from '@/lib/demo-data';
import { inr, splitTaxFromInclusive, roundToRupee } from '@/lib/inr';
import { pushToSheet } from '@/lib/google-sheets';
import Receipt from './Receipt';

type CartLine = { item: MenuItem; qty: number };
type PayMethod = 'cash' | 'upi' | 'card' | 'qr';
type OrderType = 'dine_in' | 'takeaway' | 'delivery';

export default function POSScreen() {
  const [category, setCategory] = useState<string>(CATEGORIES[0]);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [orderType, setOrderType] = useState<OrderType>('dine_in');
  const [table] = useState<string>('T-04');
  const [showPay, setShowPay] = useState(false);
  const [receipt, setReceipt] = useState<null | ReceiptData>(null);
  // Mobile-only — cart sheet visibility.
  const [cartOpen, setCartOpen] = useState(false);

  const filtered = useMemo(() => MENU.filter((m) => m.category === category), [category]);

  function add(item: MenuItem) {
    setCart((c) => {
      const existing = c.find((l) => l.item.id === item.id);
      if (existing) return c.map((l) => (l.item.id === item.id ? { ...l, qty: l.qty + 1 } : l));
      return [...c, { item, qty: 1 }];
    });
  }
  function adjust(id: string, delta: number) {
    setCart((c) =>
      c.map((l) => (l.item.id === id ? { ...l, qty: Math.max(0, l.qty + delta) } : l)).filter((l) => l.qty > 0),
    );
  }
  function clearLine(id: string) { setCart((c) => c.filter((l) => l.item.id !== id)); }

  const totals = useMemo(() => {
    let taxable = 0, cgst = 0, sgst = 0, gross = 0;
    for (const l of cart) {
      const line_inclusive = l.item.price * l.qty;
      const sp = splitTaxFromInclusive(line_inclusive, l.item.rate);
      taxable += sp.taxable;
      cgst += sp.cgst;
      sgst += sp.sgst;
      gross += line_inclusive;
    }
    const { rounded, round_off } = roundToRupee(gross);
    return { taxable, cgst, sgst, gross, total: rounded, round_off };
  }, [cart]);

  // When cart drops to zero on mobile, auto-close the sheet.
  useEffect(() => { if (!cart.length) setCartOpen(false); }, [cart.length]);

  function pay(method: PayMethod) {
    if (!cart.length) return;
    const lines = cart.map((l) => {
      const sp = splitTaxFromInclusive(l.item.price * l.qty, l.item.rate);
      return {
        sku: l.item.sku, name: l.item.name, hsn: l.item.hsn, rate: l.item.rate,
        qty: l.qty, unit_inclusive: l.item.price, line_inclusive: l.item.price * l.qty,
        ...sp,
      };
    });
    const invoiceNo = `D/MN/2026-27/${String(231 + Math.floor(Math.random() * 70)).padStart(5, '0')}`;
    const at = new Date();
    setReceipt({
      invoice_no: invoiceNo, type: orderType,
      table: orderType === 'dine_in' ? table : undefined,
      method, lines, totals, at,
    });
    setShowPay(false);
    setCart([]);

    void pushToSheet('order', {
      invoice_no: invoiceNo,
      date: at.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }),
      time: at.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }),
      type: orderType,
      table: orderType === 'dine_in' ? table : '',
      items_text: lines.map((l) => `${l.qty}× ${l.name}`).join(', '),
      items_count: lines.reduce((s, l) => s + l.qty, 0),
      cashier: 'Demo Owner',
      taxable_minor: totals.taxable,
      cgst_minor: totals.cgst,
      sgst_minor: totals.sgst,
      igst_minor: 0,
      round_off_minor: totals.round_off,
      total_minor: totals.total,
      method,
      gstin: COMPANY.gstin,
      place_of_supply: `${COMPANY.state_code}-${COMPANY.state_name}`,
    });
  }

  return (
    <div className="min-h-full pb-32 xl:grid xl:grid-cols-[minmax(0,1fr)_440px] xl:gap-6 xl:pb-0">
      {/* -------- Menu pane -------- */}
      <section className="min-w-0">
        <header className="flex items-start justify-between mb-3 gap-3 flex-wrap">
          <div>
            <h2 className="text-xl md:text-2xl font-bold">POS</h2>
            <p className="text-fg-muted text-xs md:text-sm">
              Shift open · POS-01 · {orderType.replace('_', ' ')}
              {orderType === 'dine_in' && ` · ${table}`}
            </p>
          </div>
          <div className="scroll-strip flex max-w-full gap-1 rounded-xl border border-bg-border bg-bg-surface p-1 text-xs md:text-sm">
            {(['dine_in', 'takeaway', 'delivery'] as OrderType[]).map((t) => (
              <button key={t}
                onClick={() => setOrderType(t)}
                className={`shrink-0 rounded-lg px-2.5 py-1.5 font-medium transition md:px-3 md:py-2 ${
                  orderType === t ? 'bg-accent text-bg' : 'text-fg-muted hover:text-fg'
                }`}
              >{t.replace('_', ' ')}</button>
            ))}
          </div>
        </header>

        {/* Category chips — horizontal scroll */}
        <div className="scroll-strip flex gap-2 mb-3 pb-1 -mx-3 px-3 md:mx-0 md:px-0">
          {CATEGORIES.map((c) => (
            <button key={c}
              onClick={() => setCategory(c)}
              className={`px-4 py-2 rounded-full text-sm font-medium whitespace-nowrap border transition flex-shrink-0 ${
                category === c
                  ? 'bg-accent text-bg border-accent'
                  : 'bg-bg-surface text-fg-muted border-bg-border hover:text-fg'
              }`}
            >{c}</button>
          ))}
        </div>

        {/* Menu grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2 md:gap-3">
          {filtered.map((item) => (
            <button key={item.id} onClick={() => add(item)}
              className="card text-left hover:border-accent transition group p-3 md:p-4 active:scale-[0.97]"
            >
              <div className="text-3xl mb-1.5">{item.emoji}</div>
              <div className="font-semibold text-sm leading-tight">{item.name}</div>
              <div className="text-fg-muted text-xs mt-1 flex items-center justify-between gap-2">
                <span className="font-mono">{inr(item.price, { decimals: 0 })}</span>
                <span className="chip !py-0 !px-1.5 !text-[10px]">{(item.rate * 100).toFixed(0)}%</span>
              </div>
            </button>
          ))}
        </div>
      </section>

      {/* ============== Cart pane ==============
          Desktop:  side panel, always visible.
          Mobile:   bottom sheet, slides up when cartOpen=true. */}
      <aside
        className={`
          hidden xl:flex card flex-col
          xl:!relative xl:!inset-auto xl:!h-auto xl:!translate-y-0
        `}
      >
        <CartContents
          cart={cart}
          totals={totals}
          adjust={adjust}
          clearLine={clearLine}
          clearAll={() => setCart([])}
          onCharge={() => setShowPay(true)}
        />
      </aside>

      {/* Mobile sticky charge bar */}
      {cart.length > 0 && (
        <button
          onClick={() => setCartOpen(true)}
          className="xl:hidden fixed left-3 right-3 bottom-3 z-30 btn btn-primary !min-h-[60px] min-w-0 text-sm font-bold shadow-2xl sm:text-base"
          style={{ bottom: 'max(0.75rem, calc(env(safe-area-inset-bottom) + 0.5rem))' }}
        >
          <ShoppingCart size={18}/>
          <span className="shrink-0">{cart.reduce((s, l) => s + l.qty, 0)} items</span>
          <span className="opacity-80">·</span>
          <span className="min-w-0 truncate">{inr(totals.total)}</span>
          <span className="ml-auto flex shrink-0 items-center gap-1 text-xs opacity-80">
            View <ChevronDown size={14} className="rotate-180"/>
          </span>
        </button>
      )}

      {/* Mobile cart sheet */}
      {cartOpen && (
        <MobileSheet title="Cart" onClose={() => setCartOpen(false)}>
          <CartContents
            cart={cart}
            totals={totals}
            adjust={adjust}
            clearLine={clearLine}
            clearAll={() => { setCart([]); setCartOpen(false); }}
            onCharge={() => { setCartOpen(false); setShowPay(true); }}
          />
        </MobileSheet>
      )}

      {/* -------- Payment sheet -------- */}
      {showPay && (
        <MobileSheet title={`Charge ${inr(totals.total)}`} onClose={() => setShowPay(false)}>
          <div className="grid grid-cols-2 gap-3">
            <PayButton icon={<Banknote size={28}/>}   label="Cash" sub="Drawer opens"     onClick={() => pay('cash')} />
            <PayButton icon={<Smartphone size={28}/>} label="UPI"  sub="GPay / PhonePe"    onClick={() => pay('upi')} />
            <PayButton icon={<CreditCard size={28}/>} label="Card" sub="Visa / RuPay"      onClick={() => pay('card')} />
            <PayButton icon={<QrCode size={28}/>}     label="QR"   sub="Print dynamic QR"  onClick={() => pay('qr')} />
          </div>
        </MobileSheet>
      )}

      {/* -------- Receipt -------- */}
      {receipt && (
        <MobileSheet title="Receipt" onClose={() => setReceipt(null)}>
          <Receipt data={receipt} />
          <div className="flex gap-2 mt-4 print:hidden">
            <button onClick={() => window.print()} className="btn btn-primary flex-1">
              <ReceiptIcon size={16}/> Print
            </button>
            <button onClick={() => setReceipt(null)} className="btn btn-ghost">
              <Check size={16}/> Done
            </button>
          </div>
        </MobileSheet>
      )}
    </div>
  );
}

// ============================================================
// Cart contents — shared between desktop sidebar + mobile sheet
// ============================================================
function CartContents({
  cart, totals, adjust, clearLine, clearAll, onCharge,
}: {
  cart: CartLine[];
  totals: { taxable: number; cgst: number; sgst: number; total: number; round_off: number };
  adjust: (id: string, delta: number) => void;
  clearLine: (id: string) => void;
  clearAll: () => void;
  onCharge: () => void;
}) {
  return (
    <div className="flex flex-col h-full min-h-0">
      <header className="flex items-center justify-between mb-3">
        <h3 className="text-lg font-semibold flex items-center gap-2">
          <ShoppingCart size={18} /> Cart · {cart.length}
        </h3>
        {cart.length > 0 && (
          <button onClick={clearAll} className="text-xs text-fg-muted hover:text-accent-bad">
            Clear
          </button>
        )}
      </header>

      <div className="flex-1 space-y-2 overflow-auto min-h-[120px]">
        {!cart.length && (
          <p className="text-fg-muted text-sm text-center py-8">
            Tap items to build the order.
          </p>
        )}
        {cart.map((l) => (
          <div key={l.item.id} className="flex items-center gap-2 py-1">
            <div className="text-2xl">{l.item.emoji}</div>
            <div className="flex-1 min-w-0">
              <div className="font-medium text-sm truncate">{l.item.name}</div>
              <div className="text-xs text-fg-muted font-mono">{inr(l.item.price)} ea</div>
            </div>
            <div className="flex items-center gap-1">
              <button onClick={() => adjust(l.item.id, -1)} className="btn btn-ghost !min-h-[40px] !p-2">
                <Minus size={14} />
              </button>
              <span className="w-7 text-center font-mono text-sm">{l.qty}</span>
              <button onClick={() => adjust(l.item.id, 1)} className="btn btn-ghost !min-h-[40px] !p-2">
                <Plus size={14} />
              </button>
              <button onClick={() => clearLine(l.item.id)} className="btn btn-ghost !min-h-[40px] !p-2">
                <Trash2 size={14} />
              </button>
            </div>
          </div>
        ))}
      </div>

      <div className="mt-3 pt-3 border-t border-bg-border space-y-1 text-sm font-mono">
        <Row label="Taxable"          v={totals.taxable} />
        <Row label="CGST"             v={totals.cgst} subtle />
        <Row label="SGST"             v={totals.sgst} subtle />
        {totals.round_off !== 0 && <Row label="Round off" v={totals.round_off} subtle />}
        <div className="flex justify-between text-lg font-bold pt-2">
          <span>Total</span><span>{inr(totals.total)}</span>
        </div>
      </div>

      <button
        onClick={onCharge}
        disabled={!cart.length}
        className="btn btn-primary mt-3 disabled:opacity-40 disabled:cursor-not-allowed !min-h-[52px] text-base font-bold"
      >
        <ReceiptIcon size={16} /> Charge {inr(totals.total)}
      </button>
    </div>
  );
}

function Row({ label, v, subtle }: { label: string; v: number; subtle?: boolean }) {
  return (
    <div className={`flex justify-between ${subtle ? 'text-fg-muted' : ''}`}>
      <span>{label}</span><span>{inr(v)}</span>
    </div>
  );
}

function PayButton({ icon, label, sub, onClick }: { icon: React.ReactNode; label: string; sub: string; onClick: () => void }) {
  return (
    <button onClick={onClick} className="card flex flex-col items-center gap-2 hover:border-accent py-5 active:scale-95">
      <div className="text-accent">{icon}</div>
      <div className="font-semibold">{label}</div>
      <div className="text-xs text-fg-muted">{sub}</div>
    </button>
  );
}

// ============================================================
// MobileSheet — bottom sheet on phones, centered modal on tablets+
// Used for cart, payment selection, receipt, etc.
// ============================================================
function MobileSheet({
  title, children, onClose,
}: { title: string; children: React.ReactNode; onClose: () => void }) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-end md:items-center justify-center bg-bg/80 backdrop-blur-sm md:p-4 print:p-0 print:bg-white"
      onClick={onClose}
    >
      <div
        className="bg-bg-surface border border-bg-border rounded-t-2xl md:rounded-2xl shadow-glow w-full md:max-w-md max-h-[90vh] flex flex-col print:max-w-none print:w-auto print:bg-white print:text-black print:border-none print:shadow-none"
        onClick={(e) => e.stopPropagation()}
        style={{ paddingBottom: 'max(0.5rem, env(safe-area-inset-bottom))' }}
      >
        <div className="flex items-center justify-between p-4 border-b border-bg-border print:hidden flex-shrink-0">
          {/* Drag-handle hint on mobile */}
          <div className="md:hidden absolute top-1.5 left-1/2 -translate-x-1/2 w-10 h-1 bg-bg-border rounded-full"/>
          <h3 className="font-semibold">{title}</h3>
          <button onClick={onClose} className="text-fg-muted hover:text-fg p-1 -m-1"><X size={20}/></button>
        </div>
        <div className="p-4 overflow-auto">{children}</div>
      </div>
    </div>
  );
}

// Re-exported type for Receipt.tsx
export type ReceiptData = {
  invoice_no: string;
  type: OrderType;
  table?: string;
  method: PayMethod;
  lines: Array<{
    sku: string; name: string; hsn: string; rate: number; qty: number;
    unit_inclusive: number; line_inclusive: number;
    taxable: number; cgst: number; sgst: number; total: number;
  }>;
  totals: { taxable: number; cgst: number; sgst: number; gross: number; total: number; round_off: number };
  at: Date;
};
