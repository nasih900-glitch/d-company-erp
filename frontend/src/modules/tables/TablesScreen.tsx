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
  ShoppingBag, Send, ChefHat, Minus, Settings,
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
import { subscribeRealtime } from '@/lib/realtime';
import {
  beginTableRetryOperation,
  clearTableRetryOperation,
  hasLockedTableRetryOperation,
  isAmbiguousApiError,
  isTableDraftHydratedForKey,
  normalizeTableCartDraft,
  replaceTableDraftLines,
  tableIdempotencyKey,
  type TableCartDraft,
} from '@/lib/retry-drafts';
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
function scopedTableCartDraftKey(
  companyId: string,
  branchId: string,
  userId: string,
  terminalId: string,
  tableId: string,
) {
  return `table-cart-draft:${companyId}:${branchId}:${userId}:${terminalId}:${tableId}`;
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
// Fallback only — the real-time push (see subscribeRealtime below) is what
// actually keeps this in sync; this just covers the rare case of a missed
// or dropped push (e.g. reconnecting after a network blip).
const TABLES_POLL_MS = 120_000;

export default function TablesScreen() {
  const [rows, setRows] = useState<TableDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<TableDTO | null>(null);
  const [orderFor, setOrderFor] = useState<TableDTO | null>(null);
  const [manageMode, setManageMode] = useState(false);

  async function load() {
    setLoading(true); setError(null);
    try {
      if (LIVE_MODE) setRows(await tables.list());
      else setRows(TABLES.map(demoToDTO));
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  // Table status is shared across every device on the floor. Real-time push
  // is what actually keeps this current — the moment anyone changes a
  // table's status or sends an order, every other screen hears about it
  // within about a second, quietly (no loading spinner). The interval below
  // is just a safety net for a missed push, not the primary mechanism.
  useEffect(() => {
    if (!LIVE_MODE) return;
    const refresh = () => { tables.list().then(setRows).catch(() => {}); };
    const unsubscribe = subscribeRealtime('tables', refresh);
    const id = setInterval(refresh, TABLES_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, []);

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
          <button className={`btn ${manageMode ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setManageMode(!manageMode)}>
            <Settings size={14}/> {manageMode ? 'Done' : 'Manage'}
          </button>
          {manageMode && (
            <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
              <Plus size={14}/> New table
            </button>
          )}
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
          No tables yet. Click <b>Manage</b>, then <b>New table</b>, to add the first one.
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
                {manageMode && (
                  <div className="flex flex-col gap-1">
                    <button className="text-fg-muted hover:text-accent p-1" onClick={() => setEdit(t)}>
                      <Edit2 size={12}/>
                    </button>
                    <button className="text-fg-muted hover:text-accent-bad p-1" onClick={() => onDelete(t)}>
                      <Trash2 size={12}/>
                    </button>
                  </div>
                )}
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

type CartLine = { item: MenuItemDTO; qty: number; note: string };

function TableOrderView({ table, onClose }: { table: TableDTO; onClose: () => void }) {
  const { me, terminalId, terminalReady } = useAuth();
  const draftKey = me?.company_id && me.branch_id && me.user_id && terminalId
    ? scopedTableCartDraftKey(
        me.company_id,
        me.branch_id,
        me.user_id,
        terminalId,
        table.id,
      )
    : null;
  const [items, setItems] = useState<MenuItemDTO[]>([]);
  const [order, setOrder] = useState<OrderDTO | null>(null);
  const [shiftId, setShiftId] = useState<string | null>(null);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [cartDraft, setCartDraft] = useState<TableCartDraft | null>(null);
  const [hydratedDraftKey, setHydratedDraftKey] = useState<string | null>(null);
  const draftHydrated = isTableDraftHydratedForKey(draftKey, hydratedDraftKey);
  const [activeCat, setActiveCat] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [sendingToPos, setSendingToPos] = useState(false);
  const [cancellingOrder, setCancellingOrder] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const storedDraft = draftKey
      ? normalizeTableCartDraft(loadDraft<unknown>(draftKey))
      : null;
    setHydratedDraftKey(null);
    setCart([]);
    setCartDraft(storedDraft);
    setOrder(null);
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
        const orderable = menuItems.filter((i) =>
          TABLE_ORDERABLE_TYPES.has(i.type) && isAppStoreAllowedType(i.type));
        const available = orderable.filter((i) => i.is_available);
        setItems(available);
        setShiftId(resolvedShiftId);
        if (existingOrders.length) {
          const full = await orders.get(existingOrders[0].id);
          if (!cancelled) setOrder(full);
        }
        if (storedDraft) {
          if (!storedDraft.operation && storedDraft.shiftId && storedDraft.shiftId !== resolvedShiftId) {
            if (draftKey) clearDraft(draftKey);
            setCart([]);
            setCartDraft(null);
            setError(
              'A saved table cart belonged to a different shift and was not restored.',
            );
            return;
          }
          const restored = storedDraft.lines
            .map((d) => {
              // Keep unavailable items in an interrupted operation so an exact
              // idempotent replay still sends the original request body.
              const item = orderable.find((i) => i.id === d.itemId);
              return item ? { item, qty: d.qty, note: d.note ?? '' } : null;
            })
            .filter((l): l is CartLine => l !== null);
          const restoredDraft = replaceTableDraftLines(
            storedDraft,
            restored.map((line) => ({
              itemId: line.item.id,
              qty: line.qty,
              note: line.note || undefined,
            })),
          );
          setCart(restored);
          setCartDraft(storedDraft.operation ? storedDraft : restoredDraft);
          if (storedDraft.operation && restored.length !== storedDraft.lines.length) {
            setError(
              'An item in the interrupted request is no longer visible in the menu. The original request remains locked and will retry with the exact saved item IDs.',
            );
          }
        }
      } catch (e) {
        if (!cancelled) setError((e as Error).message);
      } finally {
        if (!cancelled) {
          setHydratedDraftKey(draftKey);
          setLoading(false);
        }
      }
    })();
    return () => { cancelled = true; };
  }, [draftKey, me?.branch_id, me?.company_id, table.id, terminalId, terminalReady]);

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
    if (!draftHydrated) return;
    if (!draftKey) return;
    const key = draftKey;
    if (cartDraft) {
      saveDraft(key, cartDraft);
    } else {
      clearDraft(key);
    }
  }, [cartDraft, draftHydrated, draftKey]);

  function updateCart(next: CartLine[]) {
    if (cartDraft?.operation) return;
    setCart(next);
    setCartDraft((current) => {
      const updated = replaceTableDraftLines(
        current,
        next.map((line) => ({
          itemId: line.item.id,
          qty: line.qty,
          note: line.note || undefined,
        })),
      );
      return updated ? { ...updated, shiftId: shiftId ?? undefined } : null;
    });
  }

  function add(item: MenuItemDTO) {
    const ex = cart.find((line) => line.item.id === item.id);
    updateCart(ex
      ? cart.map((line) => line.item.id === item.id ? { ...line, qty: line.qty + 1 } : line)
      : [...cart, { item, qty: 1, note: '' }]);
  }
  function adjust(id: string, delta: number) {
    updateCart(cart
      .map((line) => line.item.id === id
        ? { ...line, qty: Math.max(0, line.qty + delta) }
        : line)
      .filter((line) => line.qty > 0));
  }

  function updateNote(id: string, note: string) {
    updateCart(cart.map((line) => line.item.id === id ? { ...line, note } : line));
  }

  const cartPreview = useMemo(
    () => cart.reduce((sum, l) => sum + l.item.base_price_minor * l.qty, 0),
    [cart],
  );

  const canEdit = !order || order.status === 'open';
  const hasLockedRetryOperation = hasLockedTableRetryOperation(cartDraft);
  const canSendToKitchen = canEdit
    && (cart.length > 0 || hasLockedRetryOperation)
    && (!!shiftId || hasLockedRetryOperation);
  const canSendToPos = !!order
    && order.status === 'open'
    && order.lines.length > 0
    && cart.length === 0
    && !hasLockedRetryOperation;

  async function sendToKitchen() {
    if ((!cart.length && !hasLockedRetryOperation) || (!shiftId && !hasLockedRetryOperation)) return;
    const baseDraft = cartDraft ?? replaceTableDraftLines(
      null,
      cart.map((line) => ({
        itemId: line.item.id,
        qty: line.qty,
        note: line.note || undefined,
      })),
    );
    if (!baseDraft) return;
    const retryDraft = beginTableRetryOperation(
      baseDraft,
      order
        ? { kind: 'append', orderId: order.id }
        : { kind: 'create', shiftId: shiftId! },
    );
    setCartDraft(retryDraft);
    // Persist before the request. If the response is lost, refresh can replay
    // this exact endpoint/body/key instead of creating or appending again.
    if (!draftKey || !saveDraft(draftKey, retryDraft)) {
      const editableDraft = clearTableRetryOperation(retryDraft);
      setCartDraft(editableDraft);
      setError(
        'Browser recovery storage is unavailable. Nothing was sent; enable storage before retrying.',
      );
      return;
    }
    setSending(true); setError(null);
    try {
      const lines = retryDraft.lines.map((line) => ({
        menu_item_id: line.itemId,
        qty: line.qty,
        note: line.note,
      }));
      const updated = retryDraft.operation?.kind === 'append'
        ? await pos.addLines(
            retryDraft.operation.orderId,
            lines,
            tableIdempotencyKey(table.id, retryDraft),
          )
        : await pos.createOrder(
            {
              type: 'dine_in',
              table_id: table.id,
              shift_id: retryDraft.operation?.kind === 'create'
                ? retryDraft.operation.shiftId
                : shiftId!,
              lines,
            },
            tableIdempotencyKey(table.id, retryDraft),
          );
      setOrder(updated);
      setCart([]);
      setCartDraft(null);
      if (draftKey) clearDraft(draftKey);
    } catch (e) {
      if (isAmbiguousApiError(e)) {
        setError(`${(e as Error).message} The result is unknown; use Retry Send to Kitchen to resume safely.`);
      } else {
        const editableDraft = clearTableRetryOperation(retryDraft);
        setCartDraft(editableDraft);
        if (draftKey) saveDraft(draftKey, editableDraft);
        setError((e as Error).message);
      }
    }
    finally { setSending(false); }
  }

  async function sendToPos() {
    if (!order || hasLockedRetryOperation) return;
    setSendingToPos(true); setError(null);
    try {
      setOrder(await pos.sendToPos(order.id));
    } catch (e) { setError((e as Error).message); }
    finally { setSendingToPos(false); }
  }

  async function cancelOrder() {
    if (!order || hasLockedRetryOperation) return;
    const reason = prompt(
      `Why are you cancelling the open order for Table ${table.code}?\n\n`
      + 'The reason will remain in the audit trail.',
    );
    if (!reason?.trim()) return;
    setCancellingOrder(true); setError(null);
    try {
      await pos.voidOrder(order.id, reason.trim());
      setOrder(null);
      setCart([]);
      setCartDraft(null);
      if (draftKey) clearDraft(draftKey);
    } catch (e) { setError((e as Error).message); }
    finally { setCancellingOrder(false); }
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

          {cartDraft?.operation && !sending && (
            <div className="rounded-lg border border-accent-gold/40 bg-accent-gold/10 p-2.5 text-sm text-accent-gold">
              The previous response was not confirmed. This cart is locked until the same request is retried safely.
            </div>
          )}

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
                  <div key={i} className="text-fg-muted">
                    <div className="flex justify-between">
                      <span>{l.qty} × {l.name}</span>
                      <span>{inr(l.line_total_minor)}</span>
                    </div>
                    {l.note && (
                      <div className="text-xs text-accent-gold mt-0.5">Note: {l.note}</div>
                    )}
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
                    disabled={!!cartDraft?.operation || sending}
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
                      <div key={l.item.id} className="space-y-1.5">
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-sm truncate flex-1">{l.item.name}</span>
                          <div className="flex items-center gap-1.5">
                            <button className="btn btn-ghost !p-1" disabled={!!cartDraft?.operation || sending}
                              onClick={() => adjust(l.item.id, -1)}>
                              <Minus size={12}/>
                            </button>
                            <span className="w-5 text-center text-sm">{l.qty}</span>
                            <button className="btn btn-ghost !p-1" disabled={!!cartDraft?.operation || sending}
                              onClick={() => adjust(l.item.id, 1)}>
                              <Plus size={12}/>
                            </button>
                          </div>
                          <span className="text-sm w-16 text-right">{inr(l.item.base_price_minor * l.qty)}</span>
                        </div>
                        <input
                          className="input !min-h-[34px] !py-1.5 text-xs"
                          maxLength={500}
                          disabled={!!cartDraft?.operation || sending}
                          placeholder="Preparation note (e.g. no sugar, less spicy)"
                          value={l.note}
                          onChange={(event) => updateNote(l.item.id, event.target.value)}
                        />
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
            {order?.status === 'open' && cart.length === 0 && !hasLockedRetryOperation && (
              <button className="btn btn-ghost text-accent-bad"
                disabled={cancellingOrder || sendingToPos}
                onClick={cancelOrder}>
                {cancellingOrder
                  ? <Loader2 className="animate-spin" size={14}/>
                  : <Trash2 size={14}/>} Cancel order
              </button>
            )}
            {canSendToKitchen && (
              <button className="btn btn-primary flex-1" disabled={sending} onClick={sendToKitchen}>
                {sending ? <Loader2 className="animate-spin" size={14}/> : <ChefHat size={14}/>}
                {cartDraft?.operation ? 'Retry Send to Kitchen' : 'Send to Kitchen'}
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
