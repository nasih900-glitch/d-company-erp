/**
 * Tables / Floor — full live CRUD, plus building a food order per table.
 *  - List tables
 *  - Add / edit / delete
 *  - Quick status cycle (tap chip)
 *  - Tap a table to build/continue its order: add items (sends to the
 *    kitchen as soon as they're saved), then "Send to POS" when ready to
 *    bill. Once sent, more items are added by finding the table in POS —
 *    not from here.
 */
import { useEffect, useMemo, useState } from 'react';
import {
  Users, Plus, Edit2, Trash2, AlertCircle, Loader2, RefreshCw,
  ShoppingBag, Send, ChefHat, Minus,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { TABLES, type TableRec } from '@/lib/demo-data';
import { inr } from '@/lib/inr';
import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import {
  menu, orders, pos, shifts, tables,
  type MenuItemDTO, type OrderDTO, type TableDTO,
} from '@/lib/erp-api';
import { clearDraft, loadDraft, saveDraft } from '@/lib/draft-storage';
import { resolveRequiredOpenShift } from '@/lib/operational-context';
import { useAuth } from '@/modules/auth/AuthContext';
import Modal from '@/components/ui/Modal';

// Food/drink/dessert only — gaming and shisha (hookah) sessions bill
// through the Gaming tab's own Send to POS flow, not Tables.
const CATEGORY_FROM_TYPE: Record<string, string> = {
  food: 'Food', drink: 'Drinks', dessert: 'Desserts',
};
const TABLE_ORDERABLE_TYPES = new Set(['food', 'drink', 'dessert']);

// Not-yet-sent items survive a refresh — only cleared once they're actually
// saved to the backend via "Send to Kitchen".
function tableCartDraftKey(tableId: string) {
  return `table-cart-draft:${tableId}`;
}

const STATUS_COLOR: Record<TableDTO['status'], string> = {
  available: 'border-accent-good/40 text-accent-good',
  occupied:  'border-accent-bad/40 text-accent-bad',
  reserved:  'border-accent-gold/40 text-accent-gold',
  cleaning:  'border-fg-muted/40 text-fg-muted',
  merged:    'border-accent-purple/40 text-accent-purple',
};
const STATUS_NEXT: Record<TableDTO['status'], TableDTO['status']> = {
  available: 'occupied',
  occupied:  'cleaning',
  cleaning:  'available',
  reserved:  'occupied',
  merged:    'available',
};

export default function TablesScreen() {
  const [rows, setRows] = useState<TableDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<TableDTO | null>(null);
  const [orderFor, setOrderFor] = useState<TableDTO | null>(null);

  async function load() {
    setLoading(true); setError(null);
    try {
      if (LIVE_MODE) setRows(await tables.list());
      else setRows(TABLES.map(demoToDTO));
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  async function cycleStatus(t: TableDTO) {
    try { await tables.updateStatus(t.id, STATUS_NEXT[t.status]); await load(); }
    catch (e) { alert((e as Error).message); }
  }

  async function onDelete(t: TableDTO) {
    if (!confirm(`Delete table ${t.code}?`)) return;
    try { await tables.delete(t.id); await load(); }
    catch (e) { alert((e as Error).message); }
  }

  const summary = {
    total: rows.length,
    occupied: rows.filter((t) => t.status === 'occupied').length,
    available: rows.filter((t) => t.status === 'available').length,
  };

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Tables &amp; floor</h2>
          <p className="text-fg-muted text-sm">
            {summary.total} tables · {summary.occupied} occupied · {summary.available} free
          </p>
        </div>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
            <Plus size={14}/> New table
          </button>
        </div>
      </header>

      {error && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
          <AlertCircle size={14}/> {error}
        </div>
      )}

      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : !rows.length ? (
        <div className="card text-fg-muted text-sm">
          No tables yet. Click <b>New table</b> to add the first one.
        </div>
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-5 gap-3 md:gap-4">
          {[...rows].sort((a, b) => a.code.localeCompare(b.code)).map((t) => (
            <div key={t.id} className="card">
              <div className="flex justify-between items-start mb-2">
                <div>
                  <div className="font-bold text-lg">{t.code}</div>
                  <div className="text-xs text-fg-muted flex items-center gap-1">
                    <Users size={11}/> {t.seats} seats · {t.shape}
                  </div>
                </div>
                <div className="flex flex-col gap-1">
                  <button className="text-fg-muted hover:text-accent p-1" onClick={() => setEdit(t)}>
                    <Edit2 size={12}/>
                  </button>
                  <button className="text-fg-muted hover:text-accent-bad p-1" onClick={() => onDelete(t)}>
                    <Trash2 size={12}/>
                  </button>
                </div>
              </div>
              <button onClick={() => cycleStatus(t)}
                className={`chip w-full justify-center mb-2 ${STATUS_COLOR[t.status]}`}>
                {t.status}
              </button>
              {LIVE_MODE && (
                <button className="btn btn-ghost w-full !py-1.5 text-xs" onClick={() => setOrderFor(t)}>
                  <ShoppingBag size={13}/> Order
                </button>
              )}
            </div>
          ))}
        </div>
      )}

      {orderFor && <TableOrderView table={orderFor}
        onClose={() => { setOrderFor(null); load(); }}/>}
      {addOpen && <TableForm onClose={() => setAddOpen(false)}
        onSuccess={() => { setAddOpen(false); load(); }}/>}
      {edit && <TableForm table={edit} onClose={() => setEdit(null)}
        onSuccess={() => { setEdit(null); load(); }}/>}
    </div>
  );
}

type CartLine = { item: MenuItemDTO; qty: number };

function TableOrderView({ table, onClose }: { table: TableDTO; onClose: () => void }) {
  const { me, terminalId, terminalReady } = useAuth();
  const [items, setItems] = useState<MenuItemDTO[]>([]);
  const [order, setOrder] = useState<OrderDTO | null>(null);
  const [shiftId, setShiftId] = useState<string | null>(null);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [activeCat, setActiveCat] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [sendingToPos, setSendingToPos] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true); setError(null);
      try {
        const companyId = me?.company_id;
        const branchId = me?.branch_id;
        if (!companyId || !branchId) {
          throw new Error('This account has no branch assigned. Assign one before taking orders.');
        }
        if (!terminalReady || !terminalId) {
          throw new Error('Select the POS terminal used by this device before taking orders.');
        }
        const [menuItems, resolvedShiftId, existingOrders] = await Promise.all([
          menu.items(),
          resolveRequiredOpenShift({
            scope: { companyId, branchId, terminalId },
            listOpenShifts: () => shifts.list(true),
          }),
          orders.list({ table_id: table.id, status: ['open', 'held'] }),
        ]);
        if (cancelled) return;
        const available = menuItems.filter((i) =>
          i.is_available && TABLE_ORDERABLE_TYPES.has(i.type) && isAppStoreAllowedType(i.type));
        setItems(available);
        setShiftId(resolvedShiftId);
        if (existingOrders.length) {
          const full = await orders.get(existingOrders[0].id);
          if (!cancelled) setOrder(full);
        }
        const draft = loadDraft<Array<{ itemId: string; qty: number }>>(tableCartDraftKey(table.id));
        if (draft?.length) {
          const restored = draft
            .map((d) => {
              const item = available.find((i) => i.id === d.itemId);
              return item ? { item, qty: d.qty } : null;
            })
            .filter((l): l is CartLine => l !== null);
          if (restored.length) setCart(restored);
        }
      } catch (e) {
        if (!cancelled) setError((e as Error).message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [me?.branch_id, me?.company_id, table.id, terminalId, terminalReady]);

  const categories = useMemo(() => {
    const groups = new Map<string, MenuItemDTO[]>();
    for (const i of items) {
      const cat = CATEGORY_FROM_TYPE[i.type] || i.type;
      if (!groups.has(cat)) groups.set(cat, []);
      groups.get(cat)!.push(i);
    }
    return Array.from(groups.entries());
  }, [items]);
  useEffect(() => {
    if (!activeCat && categories.length) setActiveCat(categories[0][0]);
  }, [categories, activeCat]);

  // Not-yet-sent items survive a refresh — cleared once sendToKitchen saves them.
  useEffect(() => {
    const key = tableCartDraftKey(table.id);
    if (cart.length) {
      saveDraft(key, cart.map((l) => ({ itemId: l.item.id, qty: l.qty })));
    } else {
      clearDraft(key);
    }
  }, [cart, table.id]);

  function add(item: MenuItemDTO) {
    setCart((c) => {
      const ex = c.find((l) => l.item.id === item.id);
      return ex
        ? c.map((l) => (l.item.id === item.id ? { ...l, qty: l.qty + 1 } : l))
        : [...c, { item, qty: 1 }];
    });
  }
  function adjust(id: string, delta: number) {
    setCart((c) => c
      .map((l) => (l.item.id === id ? { ...l, qty: Math.max(0, l.qty + delta) } : l))
      .filter((l) => l.qty > 0));
  }

  const cartPreview = useMemo(
    () => cart.reduce((sum, l) => sum + l.item.base_price_minor * l.qty, 0),
    [cart],
  );

  const canEdit = !order || order.status === 'open';
  const canSendToKitchen = canEdit && cart.length > 0 && !!shiftId;
  const canSendToPos = !!order && order.status === 'open' && order.lines.length > 0 && cart.length === 0;

  async function sendToKitchen() {
    if (!shiftId || !cart.length) return;
    setSending(true); setError(null);
    try {
      const lines = cart.map((l) => ({ menu_item_id: l.item.id, qty: l.qty }));
      const updated = order
        ? await pos.addLines(order.id, lines, `table-lines:${order.id}:${Date.now()}`)
        : await pos.createOrder(
            { type: 'dine_in', table_id: table.id, shift_id: shiftId, lines },
            `table:${table.id}:${Date.now()}`,
          );
      setOrder(updated);
      setCart([]);
    } catch (e) { setError((e as Error).message); }
    finally { setSending(false); }
  }

  async function sendToPos() {
    if (!order) return;
    setSendingToPos(true); setError(null);
    try {
      setOrder(await pos.sendToPos(order.id));
    } catch (e) { setError((e as Error).message); }
    finally { setSendingToPos(false); }
  }

  return (
    <Modal open onClose={onClose} title={`Table ${table.code}`} size="lg">
      {loading ? (
        <div className="flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : (
        <div className="space-y-3">
          {error && <ErrorRow text={error}/>}

          {order && (
            <div className="card bg-bg-raised">
              <div className="flex justify-between items-center mb-2">
                <span className={`chip text-[10px] ${
                  order.status === 'held'
                    ? 'border-accent-gold/40 text-accent-gold'
                    : 'border-accent-good/40 text-accent-good'
                }`}>
                  {order.status === 'held' ? 'Sent to POS' : 'Open'}
                </span>
                <span className="font-bold">{inr(order.total_minor)}</span>
              </div>
              <div className="space-y-1 text-sm">
                {order.lines.map((l, i) => (
                  <div key={i} className="flex justify-between text-fg-muted">
                    <span>{l.qty} × {l.name}</span>
                    <span>{inr(l.line_total_minor)}</span>
                  </div>
                ))}
              </div>
              {order.status === 'held' && (
                <p className="text-xs text-fg-muted mt-2 pt-2 border-t border-bg-border">
                  Sent to POS — find Table {table.code} in POS to add more items or bill it.
                </p>
              )}
            </div>
          )}

          {canEdit && (
            <>
              <div className="flex gap-1.5 overflow-x-auto pb-1">
                {categories.map(([cat]) => (
                  <button key={cat}
                    className={`chip whitespace-nowrap ${activeCat === cat ? 'border-accent text-accent' : ''}`}
                    onClick={() => setActiveCat(cat)}>
                    {cat}
                  </button>
                ))}
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 max-h-64 overflow-y-auto">
                {(categories.find(([c]) => c === activeCat)?.[1] ?? []).map((item) => (
                  <button key={item.id} className="card !p-2.5 text-left hover:border-accent"
                    onClick={() => add(item)}>
                    <div className="text-sm font-medium truncate">{item.name}</div>
                    <div className="text-xs text-fg-muted">{inr(item.base_price_minor)}</div>
                  </button>
                ))}
                {!categories.length && (
                  <div className="col-span-full text-sm text-fg-muted">
                    No food/drink/dessert items available.
                  </div>
                )}
              </div>

              {cart.length > 0 && (
                <div className="card">
                  <div className="text-xs text-fg-muted mb-2">Adding now</div>
                  <div className="space-y-2">
                    {cart.map((l) => (
                      <div key={l.item.id} className="flex items-center justify-between gap-2">
                        <span className="text-sm truncate flex-1">{l.item.name}</span>
                        <div className="flex items-center gap-1.5">
                          <button className="btn btn-ghost !p-1" onClick={() => adjust(l.item.id, -1)}>
                            <Minus size={12}/>
                          </button>
                          <span className="w-5 text-center text-sm">{l.qty}</span>
                          <button className="btn btn-ghost !p-1" onClick={() => adjust(l.item.id, 1)}>
                            <Plus size={12}/>
                          </button>
                        </div>
                        <span className="text-sm w-16 text-right">{inr(l.item.base_price_minor * l.qty)}</span>
                      </div>
                    ))}
                  </div>
                  <div className="flex justify-between font-bold mt-2 pt-2 border-t border-bg-border">
                    <span>New items</span>
                    <span>{inr(cartPreview)}</span>
                  </div>
                </div>
              )}
            </>
          )}

          <div className="flex gap-2 pt-2">
            {canSendToKitchen && (
              <button className="btn btn-primary flex-1" disabled={sending} onClick={sendToKitchen}>
                {sending ? <Loader2 className="animate-spin" size={14}/> : <ChefHat size={14}/>} Send to Kitchen
              </button>
            )}
            {canSendToPos && (
              <button className="btn btn-primary flex-1" disabled={sendingToPos} onClick={sendToPos}>
                {sendingToPos ? <Loader2 className="animate-spin" size={14}/> : <Send size={14}/>} Send to POS
              </button>
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}

function TableForm({
  table, onClose, onSuccess,
}: { table?: TableDTO; onClose: () => void; onSuccess: () => void }) {
  const isEdit = !!table;
  const [form, setForm] = useState({
    code: table?.code ?? '',
    seats: table?.seats?.toString() ?? '2',
    shape: table?.shape ?? 'rect' as TableDTO['shape'],
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const body = {
        code: form.code.trim(),
        seats: parseInt(form.seats, 10),
        shape: form.shape,
      };
      if (isEdit) await tables.update(table!.id, body);
      else await tables.create(body);
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${table!.code}` : 'New table'}>
      <form onSubmit={submit} className="space-y-3">
        <Field label="Code (e.g. T1, B2, OUT-1)">
          <input className="input font-mono" required autoFocus disabled={isEdit}
            value={form.code}
            onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })}/>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Seats">
            <input type="number" required min={1} max={20} className="input font-mono"
              value={form.seats}
              onChange={(e) => setForm({ ...form, seats: e.target.value })}/>
          </Field>
          <Field label="Shape">
            <select className="input" value={form.shape}
              onChange={(e) => setForm({ ...form, shape: e.target.value as TableDTO['shape'] })}>
              <option value="rect">Rectangular</option>
              <option value="round">Round</option>
              <option value="booth">Booth</option>
            </select>
          </Field>
        </div>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            {isEdit ? 'Save' : 'Create'}
          </button>
        </div>
      </form>
    </Modal>
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
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
      <AlertCircle size={14}/> {text}
    </div>
  );
}
function demoToDTO(t: TableRec): TableDTO {
  return {
    id: t.id, floor_id: 'demo', code: t.code,
    seats: t.seats, shape: 'rect', x: 0, y: 0,
    status: t.status as TableDTO['status'],
  };
}
