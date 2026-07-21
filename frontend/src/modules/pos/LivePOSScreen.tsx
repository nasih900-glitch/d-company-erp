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
 * Requires an already-open shift for this terminal (validated against the
 * server, not just localStorage). Never opens one itself — opening a shift
 * is a deliberate action taken from the Shifts tab, and the person who opens
 * it is liable for that shift's cash and payment closing.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Plus, Minus, Trash2, ShoppingCart, Receipt as ReceiptIcon,
  Banknote, CreditCard, Smartphone, QrCode, X, Check, Loader2,
  Search, UserRound, Crown, AlertCircle, Inbox, XCircle, BellOff, Bell,
} from 'lucide-react';

import { ALARM_REPEAT_MS, notifyBrowser, playAlarmTone } from '@/lib/alarm';
import { clearDraft, loadDraft, saveDraft } from '@/lib/draft-storage';
import { inr } from '@/lib/inr';
import {
  customers,
  memberships,
  menu,
  orders,
  pos,
  settings,
  shifts,
  type CustomerDTO,
  type MembershipTierDTO,
  type MenuItemDTO,
  type OrderDTO,
  type OrderListItemDTO,
  type RewardDTO,
  type SubscriptionDTO,
} from '@/lib/erp-api';
import { isAppStoreAllowedType } from '@/lib/app-store-compliance';
import { resolveRequiredOpenShift } from '@/lib/operational-context';
import {
  applyCanonicalCheckoutBalance,
  buildCheckoutPaymentSubmission,
  buildCheckoutZeroFinalization,
  createOperationKey,
  hasBenefitCoveredZeroBalance,
  hasCollectibleCheckoutBalance,
  isAmbiguousApiError,
  normalizePosRetryDraft,
  shouldPreserveCheckoutRetry,
  type CheckoutDeliveryVia,
  type CheckoutOrderType,
  type CheckoutPaymentMethod,
  type PosCheckoutRetry,
  type PosRetryDraft,
} from '@/lib/retry-drafts';
import { useAuth } from '@/modules/auth/AuthContext';
import { QRCodeSVG } from 'qrcode.react';
import { ConfirmModal, PromptModal } from '@/components/ui/ConfirmDialog';
import { subscribeRealtime } from '@/lib/realtime';

import LiveReceipt from './LiveReceipt';
import {
  buildReceiptBusinessDetails,
  buildUpiPayLink,
  receiptConfigurationIssue,
  type ReceiptBusinessDetails,
} from './receipt-business';

type CartLine = { item: MenuItemDTO; qty: number };
type PayMethod = CheckoutPaymentMethod;
type OrderType = CheckoutOrderType;
type DeliveryVia = CheckoutDeliveryVia;
type CustomerLookupState = 'idle' | 'found' | 'new' | 'error';

const CATEGORY_FROM_TYPE: Record<string, string> = {
  food: 'Food', drink: 'Drinks', dessert: 'Desserts', gaming: 'Gaming', event: 'Events',
  hookah: 'Shisha', streaming: 'Streaming',
};

// A held order sitting this long unbilled starts alarming — orders should be
// billed or cleared, not forgotten about.
const HELD_ORDER_ALARM_MINUTES = 15;
// Fallback only — real-time push is the primary mechanism.
const HELD_ORDERS_POLL_MS = 120_000;
const UNBILLED_QUEUE_LIMIT = 500;

// In-progress work (cart being built, or an order being resumed) survives a
// refresh — cleared only once it's actually paid, or the cashier clears it.
function posDraftKey(
  companyId: string,
  branchId: string,
  userId: string,
  terminalId: string,
) {
  return `pos-draft:${companyId}:${branchId}:${userId}:${terminalId}`;
}

export default function LivePOSScreen() {
  const { me, terminalId, terminalReady } = useAuth();
  const draftKey = me?.company_id && me.branch_id && me.user_id && terminalId
    ? posDraftKey(me.company_id, me.branch_id, me.user_id, terminalId)
    : null;
  const [items, setItems] = useState<MenuItemDTO[]>([]);
  const [shiftId, setShiftId] = useState<string | null>(null);
  const [shiftError, setShiftError] = useState<string | null>(null);
  const [todaysSalesMinor, setTodaysSalesMinor] = useState<number | null>(null);
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
  const [showCart, setShowCart] = useState(false);
  const [showPay, setShowPay] = useState(false);
  const [paying, setPaying] = useState(false);
  const [receipt, setReceipt] = useState<OrderDTO | null>(null);
  const [receiptBusiness, setReceiptBusiness] = useState<ReceiptBusinessDetails | null>(null);
  const [receiptSettingsError, setReceiptSettingsError] = useState<string | null>(null);
  const [checkoutRetry, setCheckoutRetry] = useState<PosCheckoutRetry | null>(null);
  // The key of a retry actually found in storage at mount (a genuine
  // crash/reload recovery) vs. one built fresh during this session's normal
  // checkout flow. Every retry gets a unique key (createOperationKey()), so
  // comparing checkoutRetry.key against this tells the "Recover checkout"
  // modal whether recovery language is actually warranted.
  const [restoredRetryKey, setRestoredRetryKey] = useState<string | null>(null);
  const [hydratedDraftKey, setHydratedDraftKey] = useState<string | null>(null);
  const draftHydrated = Boolean(draftKey && hydratedDraftKey === draftKey);

  // Held orders — sent over from Tables/Gaming, waiting to be found and billed here.
  const [heldOrders, setHeldOrders] = useState<OrderListItemDTO[]>([]);
  const [heldLoading, setHeldLoading] = useState(false);
  const [heldError, setHeldError] = useState<string | null>(null);
  const [showHeldPicker, setShowHeldPicker] = useState(false);
  const [heldSearch, setHeldSearch] = useState('');
  const [resumingOrder, setResumingOrder] = useState<OrderDTO | null>(null);
  const [heldAlarmMuted, setHeldAlarmMuted] = useState(false);
  const [voidingId, setVoidingId] = useState<string | null>(null);
  const [voidPromptRow, setVoidPromptRow] = useState<OrderListItemDTO | null>(null);
  const [abandonConfirmVariant, setAbandonConfirmVariant] = useState<'benefit_covered' | 'no_payment' | null>(null);
  const [abandonReasonOrder, setAbandonReasonOrder] = useState<OrderDTO | null>(null);
  const [, setAlarmTick] = useState(0);
  const [discountInput, setDiscountInput] = useState('');
  const [applyingDiscount, setApplyingDiscount] = useState(false);
  const [discountError, setDiscountError] = useState<string | null>(null);
  // Set when a discount is entered on the cart-review screen before any
  // order exists yet (a brand-new walk-in sale) — prepareCheckout() applies
  // it to the order the moment it's created, before the payment screen shows.
  const [pendingCartDiscountMinor, setPendingCartDiscountMinor] = useState(0);
  const [pointsInput, setPointsInput] = useState('');
  const [applyingPoints, setApplyingPoints] = useState(false);
  const [pointsError, setPointsError] = useState<string | null>(null);
  // Same pre-order-creation problem as pendingCartDiscountMinor above, but
  // for points — prepareCheckout() redeems them the moment the order exists.
  const [pendingCartPointsMinor, setPendingCartPointsMinor] = useState(0);
  const [rewards, setRewards] = useState<RewardDTO[]>([]);
  const [redeemingReward, setRedeemingReward] = useState<string | null>(null);
  const [rewardError, setRewardError] = useState<string | null>(null);
  // Tip is never applied to the server ahead of time — unlike the discount
  // above, it only travels with the final payment call (see
  // buildCheckoutPaymentSubmission in retry-drafts.ts), so entering it just
  // updates checkoutRetry.tipMinor locally.
  const [tipInput, setTipInput] = useState('');
  const [tipError, setTipError] = useState<string | null>(null);
  // Cash handed over by the customer, entered on the confirm-payment screen
  // purely so the cashier can see the exact change to hand back without
  // mental math. Display-only — it is never sent to the server, and the
  // payment submission's tendered_minor still always mirrors amount+tip
  // exactly as before (see buildCheckoutPaymentSubmission in
  // retry-drafts.ts, entirely unchanged by this).
  const [cashTenderedInput, setCashTenderedInput] = useState('');
  const lastHeldAlarmAtRef = useRef(0);
  const heldOrderScopeRef = useRef(draftKey);
  heldOrderScopeRef.current = draftKey;

  // A new checkout attempt (new recovery key) must start with a blank
  // tendered field — never let a previous bill's typed amount linger and
  // produce a misleading change figure for a different bill.
  useEffect(() => {
    setCashTenderedInput('');
  }, [checkoutRetry?.key]);

  // Reward menu for whichever customer is attached — their balance doesn't
  // change mid-checkout (points are only reserved, not spent, until final
  // settlement), so one fetch per lookup is enough; no need to refresh after
  // a redemption within the same bill.
  useEffect(() => {
    if (!customer?.phone) { setRewards([]); return; }
    let cancelled = false;
    customers.rewardsByPhone(customer.phone)
      .then((r) => { if (!cancelled) setRewards(r); })
      .catch(() => { if (!cancelled) setRewards([]); });
    return () => { cancelled = true; };
  }, [customer?.phone]);

  // Load menu items, exact shift scope, and any durable checkout journal.
  useEffect(() => {
    let cancelled = false;
    const storedDraft = draftKey
      ? normalizePosRetryDraft(loadDraft<unknown>(draftKey))
      : null;
    setHydratedDraftKey(null);
    setItems([]);
    setCart([]);
    setResumingOrder(null);
    setHeldOrders([]);
    setHeldError(null);
    setOrderType('dine_in');
    setDeliveryVia('inhouse');
    setDeliveryStateCode('32');
    setCustomerPhone('');
    setCustomerName('');
    setCustomer(null);
    setSubscription(null);
    setMembershipTier(null);
    setCustomerLookupState('idle');
    setCustomerMessage(null);
    setShowCart(false);
    setShowPay(false);
    setReceipt(null);
    setError(null);
    setCheckoutRetry(storedDraft?.retry ?? null);
    setRestoredRetryKey(storedDraft?.retry?.key ?? null);
    (async () => {
      setLoading(true);
      setShiftId(null);
      setShiftError(null);
      try {
        const branchId = me?.branch_id;
        const companyId = me?.company_id;
        if (!companyId || !branchId) {
          throw new Error('This account has no branch assigned. Assign a branch before using POS.');
        }
        if (!terminalReady || !terminalId) {
          throw new Error('Select the POS terminal used by this device before opening a shift.');
        }
        if (!draftKey) {
          throw new Error('The POS recovery scope could not be established for this user and terminal.');
        }
        const activeDraftKey = draftKey;
        const [menuResult, shiftResult] = await Promise.allSettled([
          menu.items(),
          resolveRequiredOpenShift({
            scope: { companyId, branchId, terminalId },
            listOpenShifts: () => shifts.list(true),
          }),
        ]);
        if (cancelled) return;
        if (menuResult.status === 'rejected') throw menuResult.reason;
        const all = menuResult.value;
        let resolvedShiftId: string | null = null;
        if (shiftResult.status === 'fulfilled') {
          resolvedShiftId = shiftResult.value;
        } else {
          if (!storedDraft?.retry) throw shiftResult.reason;
          // A payment-response recovery must remain reachable even if the
          // original shift was subsequently closed. The backend/idempotency
          // record remains authoritative about whether that payment committed.
          setShiftError((shiftResult.reason as Error).message);
        }
        const available = all.filter((i) => i.is_available && isAppStoreAllowedType(i.type));
        const restorable = all.filter((i) => isAppStoreAllowedType(i.type));
        setItems(available);
        setShiftId(resolvedShiftId);

        if (storedDraft) {
          if (
            !storedDraft.retry
            && storedDraft.shiftId
            && storedDraft.shiftId !== resolvedShiftId
          ) {
            clearDraft(activeDraftKey);
            setCart([]);
            setError(
              'A saved cart belonged to a different shift and was not restored. Rebuild it under the current accountable shift.',
            );
            setHydratedDraftKey(activeDraftKey);
            return;
          }
          const restoredCart = storedDraft.cart
            .map((d) => {
              // An unavailable item can still belong to an interrupted request;
              // retain it so the retry body remains byte-for-byte equivalent.
              const item = restorable.find((i) => i.id === d.itemId);
              return item ? { item, qty: d.qty } : null;
            })
            .filter((l): l is CartLine => l !== null);
          setCart(restoredCart);
          setOrderType(storedDraft.orderType);
          setDeliveryVia(storedDraft.deliveryVia);
          setDeliveryStateCode(storedDraft.deliveryStateCode);
          setCustomerName(storedDraft.customerName);
          setCustomerPhone(storedDraft.customerPhone);

          let retry = storedDraft.retry;
          let pendingOrder: OrderDTO | null = null;
          if (retry?.pendingOrderId) {
            try {
              pendingOrder = await orders.get(retry.pendingOrderId);
              if (cancelled) return;
              if (
                retry.phase !== 'recording_payment'
                && pendingOrder.status === 'paid'
                && pendingOrder.invoice_no
                && pendingOrder.invoice_issued_at
              ) {
                setReceipt(pendingOrder);
                setCart([]);
                setResumingOrder(null);
                setCheckoutRetry(null);
                clearDraft(activeDraftKey);
                setHydratedDraftKey(activeDraftKey);
                return;
              }
              if (pendingOrder.status === 'void' && retry.phase !== 'recording_payment') {
                setCart([]);
                setResumingOrder(null);
                setCheckoutRetry(null);
                clearDraft(activeDraftKey);
                setError('The prepared order was voided, so this local checkout recovery was closed.');
                setHydratedDraftKey(activeDraftKey);
                return;
              }
              if (pendingOrder.status === 'refunded' && retry.phase !== 'recording_payment') {
                setCart([]);
                setResumingOrder(null);
                setCheckoutRetry(null);
                clearDraft(activeDraftKey);
                setReceipt(pendingOrder);
                setError('This order was already refunded; no further payment was recorded.');
                setHydratedDraftKey(activeDraftKey);
                return;
              }
              if (
                retry.phase === 'recording_payment'
                && (pendingOrder.status === 'void' || pendingOrder.status === 'refunded')
              ) {
                setError(
                  `The order is ${pendingOrder.status}, but this browser had already confirmed payment. `
                  + 'Keep this recovery locked and ask a protected owner to reconcile the physical payment.',
                );
              }
              if (retry.phase !== 'recording_payment') {
                retry = applyCanonicalCheckoutBalance(retry, pendingOrder);
              }
            } catch (e) {
              if (cancelled) return;
              setError(`${(e as Error).message} The interrupted checkout remains saved for reconciliation.`);
            }
          }

          const resumingOrderId = retry?.resumingOrderId ?? storedDraft.resumingOrderId;
          if (resumingOrderId) {
            try {
              const resumed = pendingOrder?.id === resumingOrderId
                ? pendingOrder
                : await orders.get(resumingOrderId);
              if (cancelled) return;
              if (resumed && (resumed.status === 'open' || resumed.status === 'held')) {
                setResumingOrder(resumed);
              } else if (!retry) {
                // It was paid/cleared elsewhere and there is no interrupted
                // checkout to recover, so the ordinary stale draft can go.
                setCart([]);
                clearDraft(activeDraftKey);
              }
            } catch (e) {
              if (cancelled) return;
              if (retry) {
                setError(`${(e as Error).message} The interrupted checkout remains saved for a safe retry.`);
              } else {
                setCart([]);
                clearDraft(activeDraftKey);
              }
            }
          }
          setCheckoutRetry(retry ?? null);
        }
        setHydratedDraftKey(activeDraftKey);
      } catch (e) {
        if (!cancelled) setShiftError((e as Error).message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [draftKey, me?.branch_id, me?.company_id, terminalId, terminalReady]);

  const loadHeldOrders = useCallback(async (silent = false) => {
    const requestScope = draftKey;
    if (!silent) { setHeldLoading(true); setHeldError(null); }
    try {
      const result = await orders.list({
        status: ['held', 'open'],
        limit: UNBILLED_QUEUE_LIMIT,
      });
      // Tables keep their open tickets in the Tables workflow until staff send
      // them. A table-less open order is a direct POS bill whose browser
      // journal may have been lost, so it must remain recoverable here.
      const queue = result.filter((order) => (
        order.status === 'held' || (order.status === 'open' && !order.table_id)
      ));
      if (heldOrderScopeRef.current === requestScope) {
        setHeldOrders(queue);
        if (result.length === UNBILLED_QUEUE_LIMIT) {
          setHeldError(
            'The unbilled queue reached its 500-order safety limit. Bill or void old orders and ask a protected owner to reconcile the backlog.',
          );
        }
      }
    } catch (e) {
      if (!silent && heldOrderScopeRef.current === requestScope) {
        setHeldError((e as Error).message);
      }
    } finally {
      if (!silent && heldOrderScopeRef.current === requestScope) setHeldLoading(false);
    }
  }, [draftKey]);
  useEffect(() => {
    if (!shiftId) return;
    loadHeldOrders();
    const unsubscribe = subscribeRealtime('orders', () => loadHeldOrders(true));
    const id = setInterval(() => loadHeldOrders(true), HELD_ORDERS_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, [loadHeldOrders, shiftId]);

  // Today's sales corner badge — scoped to itemized POS sales on this shift
  // (all payment methods), not the cash-only expected_minor float or any
  // separate off-POS manual collection. Naturally reads back to 0 once the
  // shift closes and a new one opens.
  const loadTodaysSales = useCallback(async () => {
    if (!shiftId) { setTodaysSalesMinor(null); return; }
    try {
      const rows = await shifts.list(true);
      const current = rows.find((s) => s.id === shiftId);
      if (current) setTodaysSalesMinor(current.pos_sales_minor);
    } catch {
      // Non-critical display — leave the last known value rather than error.
    }
  }, [shiftId]);
  useEffect(() => {
    if (!shiftId) { setTodaysSalesMinor(null); return; }
    loadTodaysSales();
    const unsubscribe = subscribeRealtime('shifts', loadTodaysSales);
    const id = setInterval(loadTodaysSales, HELD_ORDERS_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, [loadTodaysSales, shiftId]);

  // Age-based alarm: a held order sitting too long should nag, not vanish
  // from mind. The interval below re-renders every second (via setAlarmTick),
  // so this plain computation — not memoized — stays fresh without it.
  const overdueHeldOrders = heldOrders.filter((o) => {
    const heldSince = o.held_at ?? o.created_at;
    return Date.now() - new Date(heldSince).getTime() >= HELD_ORDER_ALARM_MINUTES * 60_000;
  });
  useEffect(() => {
    const id = setInterval(() => {
      setAlarmTick((n) => n + 1);
      if (heldAlarmMuted) return;
      const now = Date.now();
      const anyOverdue = heldOrders.some((o) => {
        const heldSince = o.held_at ?? o.created_at;
        return now - new Date(heldSince).getTime() >= HELD_ORDER_ALARM_MINUTES * 60_000;
      });
      if (!anyOverdue) return;
      if (now - lastHeldAlarmAtRef.current < ALARM_REPEAT_MS) return;
      lastHeldAlarmAtRef.current = now;
      playAlarmTone();
      notifyBrowser(
        '⏰ Incoming orders waiting',
        'One or more sent or recovered orders have been unbilled for a while. Bill or void them.',
        'dcompany-held-orders',
      );
    }, 1000);
    return () => clearInterval(id);
  }, [heldOrders, heldAlarmMuted]);

  async function resumeOrder(row: OrderListItemDTO) {
    if (cart.length) {
      setHeldError(
        'The current POS cart has unsent items. Charge or clear that cart before opening another queued order.',
      );
      return;
    }
    if (resumingOrder && resumingOrder.id !== row.id) {
      setHeldError(
        'Another queued order is already open. Finish it or use Cancel before selecting a different one.',
      );
      return;
    }
    setShowHeldPicker(false);
    setError(null);
    try {
      const full = await orders.get(row.id);
      const nextCustomerName = full.customer_name ?? '';
      const nextCustomerPhone = full.customer_phone ?? '';
      setResumingOrder(full);
      setCart([]);
      setCheckoutRetry(null);
      setPendingCartDiscountMinor(0);
      setPendingCartPointsMinor(0);
      setCustomerName(nextCustomerName);
      setCustomerPhone(nextCustomerPhone);
      setCustomer(null);
      setSubscription(null);
      setMembershipTier(null);
      setCustomerLookupState('idle');
      setCustomerMessage(nextCustomerPhone
        ? 'Customer loaded from this order. Lookup previews membership; the server verifies it when preparing the bill.'
        : null);
      if (draftKey) {
        saveDraft<PosRetryDraft>(draftKey, {
          version: 2,
          shiftId: shiftId ?? undefined,
          resumingOrderId: full.id,
          cart: [],
          orderType,
          deliveryVia,
          deliveryStateCode,
          customerName: nextCustomerName,
          customerPhone: nextCustomerPhone,
        });
      }
    } catch (e) { setError((e as Error).message); }
  }

  async function voidOrder(row: OrderListItemDTO, reason: string) {
    setVoidingId(row.id);
    try {
      await pos.voidOrder(row.id, reason);
      await loadHeldOrders(true);
    } catch (e) { setHeldError((e as Error).message); }
    finally { setVoidingId(null); }
  }
  function cancelResume() {
    setResumingOrder(null);
    setCart([]);
    setCheckoutRetry(null);
    setPendingCartDiscountMinor(0);
    setPendingCartPointsMinor(0);
    clearCustomer();
    if (draftKey) clearDraft(draftKey);
  }

  useEffect(() => {
    let cancelled = false;
    setReceiptSettingsError(null);
    Promise.all([settings.getCompany(), settings.listBranches()])
      .then(([company, branches]) => {
        if (cancelled) return;
        const branch = branches.find((candidate) => candidate.id === me?.branch_id) ?? branches[0] ?? null;
        if (!branch) {
          setReceiptBusiness(null);
          setReceiptSettingsError('No branch is configured. Add a branch in Settings before using POS.');
          return;
        }
        const details = buildReceiptBusinessDetails(company, branch, me?.name);
        setReceiptBusiness(details);
        setReceiptSettingsError(receiptConfigurationIssue(details));
      })
      .catch(() => {
        if (cancelled) return;
        setReceiptBusiness(null);
        setReceiptSettingsError('Receipt settings could not be loaded. Refresh before charging an order.');
      });
    return () => { cancelled = true; };
  }, [me?.branch_id, me?.name]);

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
    setCart((c) => {
      const next = c.map((l) => l.item.id === id ? { ...l, qty: Math.max(0, l.qty + delta) } : l).filter((l) => l.qty > 0);
      if (next.length === 0 && c.length > 0) {
        // Cart just emptied out via -/trash — a stashed discount/points
        // redemption meant for THIS cart must not silently reattach to
        // whatever order gets built or resumed next.
        setPendingCartDiscountMinor(0);
        setPendingCartPointsMinor(0);
      }
      return next;
    });
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
  const newItemsEstimate = Math.max(0, preview - estimatedMembershipDiscount);
  const estimatedPayable = resumingOrder ? resumingOrder.due_minor + newItemsEstimate : newItemsEstimate;

  useEffect(() => {
    if (!cart.length) setShowCart(false);
  }, [cart.length]);

  useEffect(() => {
    if (!draftKey || !draftHydrated) return;
    const key = draftKey;
    if (!cart.length && !resumingOrder && !checkoutRetry) {
      clearDraft(key);
      return;
    }
    saveDraft<PosRetryDraft>(key, {
      version: 2,
      shiftId: checkoutRetry?.snapshot.shiftId ?? shiftId ?? undefined,
      resumingOrderId: resumingOrder?.id,
      cart: cart.map((l) => ({ itemId: l.item.id, qty: l.qty })),
      orderType, deliveryVia, deliveryStateCode, customerName, customerPhone,
      retry: checkoutRetry ?? undefined,
    });
  }, [
    draftKey, draftHydrated, cart, resumingOrder, orderType, deliveryVia,
    deliveryStateCode, customerName, customerPhone, checkoutRetry, shiftId,
  ]);

  function buildPosDraft(retry: PosCheckoutRetry | null): PosRetryDraft {
    return {
      version: 2,
      shiftId: retry?.snapshot.shiftId ?? shiftId ?? undefined,
      resumingOrderId: resumingOrder?.id,
      cart: cart.map((line) => ({ itemId: line.item.id, qty: line.qty })),
      orderType,
      deliveryVia,
      deliveryStateCode,
      customerName,
      customerPhone,
      retry: retry ?? undefined,
    };
  }

  function persistCheckoutRetry(retry: PosCheckoutRetry | null): boolean {
    setCheckoutRetry(retry);
    if (!draftKey) return retry === null;
    const draft = buildPosDraft(retry);
    if (!draft.cart.length && !draft.resumingOrderId && !retry) {
      clearDraft(draftKey);
      return true;
    } else {
      // This synchronous write must happen before the next API request. React
      // effects alone are too late if the page refreshes while a request is in flight.
      return saveDraft(draftKey, draft);
    }
  }

  function finishCheckout(paidOrder: OrderDTO) {
    setReceipt(paidOrder);
    setCheckoutRetry(null);
    setShowPay(false);
    setCart([]);
    setResumingOrder(null);
    setPendingCartDiscountMinor(0);
    setPendingCartPointsMinor(0);
    clearCustomer();
    if (draftKey) clearDraft(draftKey);
    void loadHeldOrders(true);
    void loadTodaysSales();
  }

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
    // Points redemption is tied to whichever customer's balance it was
    // computed against — clearing the customer must not let it silently
    // reattach to a different (or no) customer at checkout.
    setPendingCartPointsMinor(0);
  }

  async function prepareCheckout(method: PayMethod) {
    if ((!shiftId && !checkoutRetry) || !receiptBusiness || receiptSettingsError) {
      if (receiptSettingsError) setError(receiptSettingsError);
      else if (!shiftId && !checkoutRetry) setError(shiftError || 'No validated shift is available for this terminal.');
      else if (!receiptBusiness) setError('Receipt configuration is still loading. Wait a moment and try again.');
      return;
    }
    if (!checkoutRetry && !resumingOrder && !cart.length) return;
    let retry: PosCheckoutRetry = checkoutRetry ?? {
      key: createOperationKey(),
      phase: 'preparing_order',
      paymentMethod: method,
      resumingOrderId: resumingOrder?.id,
      snapshot: {
        shiftId: shiftId!,
        cart: cart.map((line) => ({ itemId: line.item.id, qty: line.qty })),
        orderType,
        deliveryVia,
        deliveryStateCode,
        customerName,
        customerPhone,
      },
    };
    if (retry.phase === 'recording_payment' || retry.phase === 'finalizing_zero') {
      await completeCheckout();
      return;
    }
    retry = { ...retry, paymentMethod: method };
    if (!persistCheckoutRetry(retry)) {
      setError(
        'Checkout recovery storage is unavailable. No bill was sent. Enable browser storage before trying again.',
      );
      return;
    }
    setShowCart(false);
    setShowPay(false);
    setPaying(true);
    setError(null);
    try {
      let order: OrderDTO;
      if (retry.pendingOrderId) {
        order = await pos.getOrder(retry.pendingOrderId);
        if (order.status === 'paid' && order.invoice_no && order.invoice_issued_at) {
          finishCheckout(order);
          return;
        }
      } else {
        if (retry.resumingOrderId) {
          order = await pos.attachCustomer(
            retry.resumingOrderId,
            {
              customer_name: retry.snapshot.customerName.trim() || undefined,
              customer_phone: retry.snapshot.customerPhone.trim() || undefined,
            },
            `order-customer:${retry.key}`,
          );
          if (retry.snapshot.cart.length) {
            order = await pos.addLines(
                retry.resumingOrderId,
                retry.snapshot.cart.map((line) => ({ menu_item_id: line.itemId, qty: line.qty })),
                `resume-lines:${retry.key}`,
              );
          }
        } else {
          order = await pos.createOrder(
            {
              type: retry.snapshot.orderType,
              shift_id: retry.snapshot.shiftId,
              lines: retry.snapshot.cart.map((line) => ({ menu_item_id: line.itemId, qty: line.qty })),
              delivery_via: retry.snapshot.orderType === 'delivery'
                ? retry.snapshot.deliveryVia
                : undefined,
              customer_state_code: retry.snapshot.orderType === 'delivery'
                ? retry.snapshot.deliveryStateCode.trim() || undefined
                : undefined,
              place_of_supply_state_code: retry.snapshot.orderType === 'delivery'
                ? retry.snapshot.deliveryStateCode.trim() || undefined
                : undefined,
              customer_name: retry.snapshot.customerName.trim() || undefined,
              customer_phone: retry.snapshot.customerPhone.trim() || undefined,
            },
            `order:${retry.key}`,
          );
        }
      }
      if (retry.pendingOrderId !== order.id) {
        retry = { ...retry, pendingOrderId: order.id };
        if (!persistCheckoutRetry(retry)) {
          setError(
            'The server prepared the order, but its recovery checkpoint could not be saved. '
            + 'Keep this page open and restore browser storage before continuing; do not start another bill.',
          );
          return;
        }
      }
      if (order.status === 'void') {
        setCheckoutRetry(null);
        setCart([]);
        setResumingOrder(null);
        if (draftKey) clearDraft(draftKey);
        setError('This order was voided and cannot be charged.');
        return;
      }
      if (order.status === 'refunded') {
        setCheckoutRetry(null);
        setCart([]);
        setResumingOrder(null);
        setReceipt(order);
        if (draftKey) clearDraft(draftKey);
        setError('This order was already refunded; no new payment was recorded.');
        return;
      }
      if (order.status !== 'open' && order.status !== 'held') {
        throw new Error(`Order is ${order.status} and cannot be prepared for payment.`);
      }
      // A discount entered on the cart-review screen, before this order
      // existed — apply it now so the confirm-payment screen already shows
      // the discounted total instead of asking the cashier to re-enter it.
      if (pendingCartDiscountMinor > 0) {
        order = await pos.applyDiscount(order.id, pendingCartDiscountMinor, `cart-discount:${retry.key}`);
        setPendingCartDiscountMinor(0);
      }
      // Same idea as the discount above, but for points redeemed before this
      // order existed — requires a customer to already be attached.
      if (pendingCartPointsMinor > 0) {
        order = await pos.redeemPoints(order.id, pendingCartPointsMinor / 10, `cart-points:${retry.key}`);
        setPendingCartPointsMinor(0);
      }
      retry = applyCanonicalCheckoutBalance(retry, order);
      if (order.due_minor <= 0 && !hasBenefitCoveredZeroBalance(retry)) {
        throw new Error('The server reports no amount due, but no final invoice is available. Ask a protected owner to reconcile this order.');
      }
      if (!persistCheckoutRetry(retry)) {
        setError(
          'The exact server bill was prepared, but recovery storage failed. Keep this page open and restore browser storage before collecting payment.',
        );
        return;
      }
      setError(null);
    } catch (e) {
      if (shouldPreserveCheckoutRetry(e, retry)) {
        persistCheckoutRetry(retry);
        const recoveryHint = isAmbiguousApiError(e)
          ? 'The server result is unknown. Resume the same preparation key; do not start another bill.'
          : 'The prepared order remains saved and must be reconciled.';
        setError(`${(e as Error).message} ${recoveryHint}`);
      } else {
        persistCheckoutRetry(null);
        setShowPay(true);
        setError((e as Error).message);
      }
    } finally {
      setPaying(false);
    }
  }

  async function completeCheckout() {
    const current = checkoutRetry;
    if (!current) return;
    if (current.phase === 'preparing_order') {
      await prepareCheckout(current.paymentMethod);
      return;
    }
    if (
      !current.pendingOrderId
      || current.paymentAmountMinor === undefined
      || current.orderTotalMinor === undefined
    ) {
      setError('The checkout journal is incomplete. Ask a protected owner to reconcile it before collecting money.');
      return;
    }
    const benefitCoveredZero = hasBenefitCoveredZeroBalance(current);
    const retry: PosCheckoutRetry = benefitCoveredZero
      ? current.phase === 'finalizing_zero'
        ? current
        : { ...current, phase: 'finalizing_zero' }
      : current.phase === 'recording_payment'
        ? current
        : { ...current, phase: 'recording_payment' };
    const paymentSubmission = buildCheckoutPaymentSubmission(retry);
    const zeroFinalization = buildCheckoutZeroFinalization(retry);
    if (!paymentSubmission && !zeroFinalization) {
      setError('The checkout journal has an invalid settlement amount. Ask a protected owner to reconcile it before collecting money.');
      return;
    }
    if (!persistCheckoutRetry(retry)) {
      setError(
        'Checkout recovery storage is unavailable. Nothing was submitted; restore browser storage first.',
      );
      return;
    }

    setPaying(true);
    setError(null);
    try {
      const settlement = zeroFinalization
        ? await pos.finalizeZero(
          zeroFinalization.orderId,
          zeroFinalization.idempotencyKey,
        )
        : await pos.recordPayment(
          paymentSubmission!.orderId,
          paymentSubmission!.body,
          paymentSubmission!.idempotencyKey,
        );
      if (settlement.order_status !== 'paid' || !settlement.invoice_no) {
        throw new Error(
          zeroFinalization
            ? 'The membership benefit did not finalize an invoice.'
            : 'The payment attempt did not finalize an invoice. Do not collect payment again.',
        );
      }
      const paidOrder = await pos.getOrder(
        zeroFinalization?.orderId ?? paymentSubmission!.orderId,
      );
      if (
        paidOrder.status !== 'paid'
        || !paidOrder.invoice_no
        || !paidOrder.invoice_issued_at
      ) {
        throw new Error(
          zeroFinalization
            ? 'The membership benefit was accepted, but the final invoice could not be loaded. Resume this same recovery.'
            : 'Payment was accepted, but the final invoice could not be loaded. Resume this same recovery; do not charge again.',
        );
      }
      finishCheckout(paidOrder);
    } catch (e) {
      persistCheckoutRetry(retry);
      setError(zeroFinalization
        ? `${(e as Error).message} The no-payment membership settlement remains locked to the same key. Resume it; do not collect money.`
        : `${(e as Error).message} The payment attempt remains locked to the same key. `
          + 'Do not collect money again; resume or ask a protected owner to reconcile it.');
    } finally {
      setPaying(false);
    }
  }

  async function applyManualDiscount() {
    const rupees = Number(discountInput);
    if (!Number.isFinite(rupees) || rupees < 0) {
      setDiscountError('Enter a valid discount amount.');
      return;
    }
    const minor = Math.round(rupees * 100);

    // Prefer an order that already exists (a resumed held order, or the
    // exact bill already prepared on the confirm-payment screen). A brand
    // new walk-in sale has no order yet at cart-review time — stash the
    // amount and prepareCheckout() applies it the moment the order exists.
    const targetOrderId = resumingOrder?.id ?? checkoutRetry?.pendingOrderId;
    if (!targetOrderId) {
      setPendingCartDiscountMinor(minor);
      setDiscountInput('');
      setDiscountError(null);
      return;
    }

    setApplyingDiscount(true);
    setDiscountError(null);
    try {
      const order = await pos.applyDiscount(targetOrderId, minor, createOperationKey());
      if (resumingOrder && order.id === resumingOrder.id) {
        setResumingOrder(order);
      }
      if (checkoutRetry?.pendingOrderId === order.id) {
        const updated = applyCanonicalCheckoutBalance(checkoutRetry, order);
        setCheckoutRetry(updated);
        persistCheckoutRetry(updated);
      }
      setPendingCartDiscountMinor(0);
      setDiscountInput('');
    } catch (e) {
      setDiscountError((e as Error).message);
    } finally {
      setApplyingDiscount(false);
    }
  }

  // Voluntary tip, entered on the confirm-payment screen once the exact
  // server bill is known. Unlike the discount/points above, there is no
  // server-side "apply" call — it rides along with the final payment
  // submission as its own field (see buildCheckoutPaymentSubmission).
  function applyTip() {
    const rupees = Number(tipInput);
    if (!Number.isFinite(rupees) || rupees < 0) {
      setTipError('Enter a valid tip amount.');
      return;
    }
    if (!checkoutRetry) return;
    const minor = Math.round(rupees * 100);
    const updated: PosCheckoutRetry = { ...checkoutRetry, tipMinor: minor };
    setCheckoutRetry(updated);
    if (!persistCheckoutRetry(updated)) {
      setError(
        'Checkout recovery storage is unavailable. The tip was not saved; enable browser storage before trying again.',
      );
      return;
    }
    setTipInput('');
    setTipError(null);
  }

  async function redeemPoints() {
    const points = Math.trunc(Number(pointsInput));
    if (!Number.isFinite(points) || points < 0) {
      setPointsError('Enter a valid number of points.');
      return;
    }
    if (customer && points > customer.loyalty_points) {
      setPointsError(`Only ${customer.loyalty_points.toLocaleString('en-IN')} points available.`);
      return;
    }
    const minor = points * 10; // 10 points = ₹1 — keep in sync with backend MINOR_PER_POINT

    const targetOrderId = resumingOrder?.id ?? checkoutRetry?.pendingOrderId;
    if (!targetOrderId) {
      setPendingCartPointsMinor(minor);
      setPointsInput('');
      setPointsError(null);
      return;
    }

    setApplyingPoints(true);
    setPointsError(null);
    try {
      const order = await pos.redeemPoints(targetOrderId, points, createOperationKey());
      if (resumingOrder && order.id === resumingOrder.id) {
        setResumingOrder(order);
      }
      if (checkoutRetry?.pendingOrderId === order.id) {
        const updated = applyCanonicalCheckoutBalance(checkoutRetry, order);
        setCheckoutRetry(updated);
        persistCheckoutRetry(updated);
      }
      setPendingCartPointsMinor(0);
      setPointsInput('');
    } catch (e) {
      setPointsError((e as Error).message);
    } finally {
      setApplyingPoints(false);
    }
  }

  async function redeemReward(key: string) {
    const targetOrderId = resumingOrder?.id ?? checkoutRetry?.pendingOrderId;
    if (!targetOrderId) {
      setRewardError('Prepare the bill first, then redeem a reward against it.');
      return;
    }
    setRedeemingReward(key);
    setRewardError(null);
    try {
      const order = await pos.redeemReward(targetOrderId, key, createOperationKey());
      if (resumingOrder && order.id === resumingOrder.id) {
        setResumingOrder(order);
      }
      if (checkoutRetry?.pendingOrderId === order.id) {
        const updated = applyCanonicalCheckoutBalance(checkoutRetry, order);
        setCheckoutRetry(updated);
        persistCheckoutRetry(updated);
      }
    } catch (e) {
      setRewardError((e as Error).message);
    } finally {
      setRedeemingReward(null);
    }
  }

  function startAbandonPreparedCheckout() {
    const retry = checkoutRetry;
    if (!retry?.pendingOrderId || retry.phase !== 'awaiting_payment') return;
    setAbandonConfirmVariant(hasBenefitCoveredZeroBalance(retry) ? 'benefit_covered' : 'no_payment');
  }

  async function abandonPreparedCheckout() {
    setAbandonConfirmVariant(null);
    const retry = checkoutRetry;
    if (!retry?.pendingOrderId || retry.phase !== 'awaiting_payment') return;
    setPaying(true);
    setError(null);
    try {
      const order = await pos.getOrder(retry.pendingOrderId);
      if (order.status === 'paid' && order.invoice_no && order.invoice_issued_at) {
        finishCheckout(order);
        setError('This order was already paid. No cancellation or additional payment was recorded.');
        return;
      }
      if (order.status === 'void') {
        setCheckoutRetry(null);
        setCart([]);
        setResumingOrder(null);
        clearCustomer();
        if (draftKey) clearDraft(draftKey);
        setError('This prepared bill was already cancelled; no payment was recorded.');
        return;
      }
      if (order.status === 'refunded') {
        setCheckoutRetry(null);
        setCart([]);
        setResumingOrder(null);
        clearCustomer();
        setReceipt(order);
        if (draftKey) clearDraft(draftKey);
        setError('This order was already refunded; no new payment or cancellation was recorded.');
        return;
      }
      if (order.status !== 'open' && order.status !== 'held') {
        setError(`Order is ${order.status}; the prepared bill remains locked for protected-owner reconciliation.`);
        return;
      }
      if (retry.resumingOrderId) {
        // Any appended cart lines are already part of this canonical held
        // order. Leave it at POS, but never append the local cart again.
        if (!draftKey || !saveDraft<PosRetryDraft>(draftKey, {
          version: 2,
          shiftId: retry.snapshot.shiftId,
          resumingOrderId: order.id,
          cart: [],
          orderType: retry.snapshot.orderType,
          deliveryVia: retry.snapshot.deliveryVia,
          deliveryStateCode: retry.snapshot.deliveryStateCode,
          customerName: retry.snapshot.customerName,
          customerPhone: retry.snapshot.customerPhone,
        })) {
          setError(
            'The held order is still unpaid, but recovery storage could not be updated. '
            + 'It remains locked here; restore browser storage before releasing it back to POS.',
          );
          return;
        }
        setCart([]);
        setResumingOrder(order);
        setCheckoutRetry(null);
        setError('Payment was not recorded. The prepared held order remains available in POS.');
      } else {
        // Needs a reason before it can be voided — hand off to the reason
        // modal rather than blocking here; finishAbandonWithReason() below
        // picks up from this exact order once the staff member submits one.
        setAbandonReasonOrder(order);
        return;
      }
      void loadHeldOrders(true);
    } catch (e) {
      setError(`${(e as Error).message} The prepared bill remains locked; do not start another bill.`);
    } finally {
      setPaying(false);
    }
  }

  async function finishAbandonWithReason(reason: string) {
    const order = abandonReasonOrder;
    setAbandonReasonOrder(null);
    if (!order) return;
    setPaying(true);
    setError(null);
    try {
      await pos.voidOrder(order.id, reason);
      setCheckoutRetry(null);
      if (draftKey) clearDraft(draftKey);
      setCart([]);
      setResumingOrder(null);
      clearCustomer();
      setError('Prepared bill cancelled with an audit reason; no payment was recorded.');
      void loadHeldOrders(true);
    } catch (e) {
      setError(`${(e as Error).message} The prepared bill remains locked; do not start another bill.`);
    } finally {
      setPaying(false);
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96 text-fg-muted">
        <Loader2 className="animate-spin mr-2" /> Connecting to D Company backend…
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
              {shiftId ? 'Shift open' : 'Shift unavailable'} · {items.length} items · backend live
            </p>
          </div>
          <div className="flex items-center gap-2">
            {shiftId && todaysSalesMinor !== null && (
              <div className="chip text-xs !py-1.5" title="Total sales on this shift — resets when a new shift opens">
                Today: <span className="font-bold font-mono ml-1">{inr(todaysSalesMinor)}</span>
              </div>
            )}
            <button className="btn btn-ghost relative" onClick={() => { setShowHeldPicker(true); loadHeldOrders(); }} title="Orders sent here from Tables and Gaming">
              <Inbox size={14}/> Incoming orders
              {heldOrders.length > 0 && (
                <span className="absolute -top-1.5 -right-1.5 min-w-[18px] h-[18px] px-1 rounded-full bg-accent text-bg text-[10px] font-bold flex items-center justify-center">
                  {heldOrders.length}
                </span>
              )}
            </button>
            {!resumingOrder && (
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
            )}
          </div>
        </header>

        {resumingOrder && (
          <div className="card !p-3 mb-4 max-w-3xl flex items-center justify-between gap-3 border-accent/40">
            <div className="text-sm">
              <span className="font-semibold">Resuming</span>{' '}
              {resumingOrder.source_label || `order ${resumingOrder.id.slice(0, 8)}`} · already {inr(resumingOrder.total_minor)}
            </div>
            <button className="btn btn-ghost !py-1.5 text-xs" onClick={cancelResume}>
              <XCircle size={13}/> Cancel
            </button>
          </div>
        )}

        {!resumingOrder && orderType === 'delivery' && (
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

        {receiptSettingsError && (
          <div className="mb-4 flex max-w-3xl items-start gap-2 rounded-xl border border-accent-bad/40 bg-accent-bad/10 px-3 py-2 text-sm text-accent-bad">
            <AlertCircle size={16} className="mt-0.5 shrink-0"/>
            <span>{receiptSettingsError}</span>
          </div>
        )}

        {shiftError && (
          <div className="mb-4 flex max-w-3xl items-start gap-2 rounded-xl border border-accent-bad/40 bg-accent-bad/10 px-3 py-2 text-sm text-accent-bad">
            <AlertCircle size={16} className="mt-0.5 shrink-0"/>
            <span>{shiftError}</span>
          </div>
        )}

        {overdueHeldOrders.length > 0 && (
          <div className="mb-4 flex max-w-3xl items-center justify-between gap-3 rounded-xl border border-accent-bad/40 bg-accent-bad/10 px-3 py-2 text-sm text-accent-bad">
            <div className="flex items-center gap-2">
              <AlertCircle size={16} className="shrink-0"/>
              <span>
                {overdueHeldOrders.length} queued order{overdueHeldOrders.length === 1 ? '' : 's'} unbilled
                for {HELD_ORDER_ALARM_MINUTES}+ min — bill or clear{overdueHeldOrders.length === 1 ? ' it' : ' them'}.
              </span>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <button className="btn btn-ghost !py-1 text-xs" onClick={() => setShowHeldPicker(true)}>
                View
              </button>
              <button className="text-accent-bad hover:opacity-70 p-1"
                onClick={() => setHeldAlarmMuted((m) => !m)}
                title={heldAlarmMuted ? 'Unmute alarm' : 'Mute alarm'}>
                {heldAlarmMuted ? <BellOff size={14}/> : <Bell size={14}/>}
              </button>
            </div>
          </div>
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

        {customer && customer.loyalty_points > 0 && (
          <div className="mb-4 max-w-xl space-y-2 rounded-xl border border-bg-border px-3 py-2">
            <div className="flex items-center justify-between text-xs text-fg-muted">
              <span>Redeem points · {customer.loyalty_points.toLocaleString('en-IN')} available (10 pts = ₹1)</span>
              {pendingCartPointsMinor > 0 && (
                <span className="text-accent-good">Pending: -{inr(pendingCartPointsMinor)}</span>
              )}
            </div>
            <div className="flex gap-2">
              <input
                type="number"
                min="0"
                step="1"
                max={customer.loyalty_points}
                inputMode="numeric"
                placeholder="Points to redeem"
                value={pointsInput}
                onChange={(e) => setPointsInput(e.target.value)}
                disabled={applyingPoints}
                className="input flex-1"
              />
              <button
                className="btn btn-ghost disabled:opacity-40"
                disabled={applyingPoints || !pointsInput}
                onClick={redeemPoints}
              >
                {applyingPoints ? <Loader2 size={16} className="animate-spin"/> : 'Redeem'}
              </button>
            </div>
            {pointsError && <p className="text-xs text-accent-bad">{pointsError}</p>}
          </div>
        )}

        {customer && rewards.length > 0 && (
          <div className="mb-4 max-w-xl space-y-2 rounded-xl border border-bg-border px-3 py-2">
            <div className="text-xs text-fg-muted">
              Rewards · {customer.gaming_rank} rank
            </div>
            <div className="grid grid-cols-1 gap-1.5">
              {rewards.map((r) => (
                <button
                  key={r.key}
                  className="btn btn-ghost !justify-between !py-1.5 text-xs disabled:opacity-40"
                  disabled={!r.affordable || redeemingReward === r.key}
                  onClick={() => redeemReward(r.key)}
                  title={r.description}
                >
                  <span className="text-left">
                    <span className="block font-semibold">{r.name}</span>
                    <span className="block text-fg-muted">{r.description}</span>
                  </span>
                  <span className="shrink-0 font-mono font-bold text-accent-gold">
                    {redeemingReward === r.key
                      ? <Loader2 size={14} className="animate-spin"/>
                      : `${r.points_cost} pts`}
                  </span>
                </button>
              ))}
            </div>
            {rewardError && <p className="text-xs text-accent-bad">{rewardError}</p>}
          </div>
        )}

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
          {cart.length > 0 && (
            <button
              onClick={() => { setCart([]); setPendingCartDiscountMinor(0); setPendingCartPointsMinor(0); }}
              className="text-xs text-fg-muted hover:text-accent-bad"
            >
              Clear
            </button>
          )}
        </header>

        <div className="flex-1 space-y-2 overflow-auto min-h-[120px]">
          {resumingOrder && (
            <div className="mb-2 pb-2 border-b border-bg-border">
              <div className="text-xs text-fg-muted mb-1">Already sent</div>
              {resumingOrder.lines.map((l, i) => (
                <div key={i} className="text-sm text-fg-muted py-0.5">
                  <div className="flex justify-between">
                    <span>{l.qty} × {l.name}</span>
                    <span>{inr(l.line_total_minor)}</span>
                  </div>
                  {l.note && <div className="text-xs text-accent-gold">Note: {l.note}</div>}
                </div>
              ))}
            </div>
          )}
          {!cart.length && (
            <p className="text-fg-muted text-sm text-center py-8">
              {resumingOrder ? 'Tap items to add more before billing.' : 'Tap items to build the order.'}
            </p>
          )}
          {cart.map((l) => (
            <div key={l.item.id} className="flex items-center gap-2 py-1">
              <div className="flex-1 min-w-0">
                <div className="font-medium text-sm truncate">{l.item.name}</div>
                <div className="text-xs text-fg-muted">{inr(l.item.base_price_minor)} ea</div>
              </div>
              <div className="w-20 text-right font-mono text-sm">{inr(l.item.base_price_minor * l.qty)}</div>
              <div className="flex items-center gap-1">
                <button
                  onClick={() => adjust(l.item.id, -1)}
                  className="btn btn-ghost !min-h-[36px] !p-2"
                  aria-label={`Decrease ${l.item.name} quantity`}
                  title="Decrease quantity"
                ><Minus size={12} /></button>
                <span className="w-6 text-center font-mono text-sm">{l.qty}</span>
                <button
                  onClick={() => adjust(l.item.id, 1)}
                  className="btn btn-ghost !min-h-[36px] !p-2"
                  aria-label={`Increase ${l.item.name} quantity`}
                  title="Increase quantity"
                ><Plus size={12} /></button>
                <button
                  onClick={() => adjust(l.item.id, -l.qty)}
                  className="btn btn-ghost !min-h-[36px] !p-2"
                  aria-label={`Remove ${l.item.name} from cart`}
                  title="Remove item"
                ><Trash2 size={12} /></button>
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
          {pendingCartDiscountMinor > 0 && (
            <div className="flex justify-between text-accent-good mb-1">
              <span>Custom discount</span><span>-{inr(pendingCartDiscountMinor)}</span>
            </div>
          )}
          {!!resumingOrder?.manual_discount_minor && (
            <div className="flex justify-between text-accent-good mb-1">
              <span>Custom discount</span><span>-{inr(resumingOrder.manual_discount_minor)}</span>
            </div>
          )}
          {pendingCartPointsMinor > 0 && (
            <div className="flex justify-between text-accent-good mb-1">
              <span>Points redeemed</span><span>-{inr(pendingCartPointsMinor)}</span>
            </div>
          )}
          {!!resumingOrder?.points_redeemed_minor && (
            <div className="flex justify-between text-accent-good mb-1">
              <span>Points redeemed</span><span>-{inr(resumingOrder.points_redeemed_minor)}</span>
            </div>
          )}
          <div className="flex justify-between text-lg font-bold">
            <span>Total (est.)</span><span>{inr(Math.max(0, estimatedPayable - pendingCartDiscountMinor - pendingCartPointsMinor))}</span>
          </div>
          <p className="text-xs text-fg-muted mt-1">Final GST split &amp; round-off computed by backend on charge.</p>
        </div>

        <div className="mt-3 space-y-2 rounded-xl border border-bg-border px-3 py-2">
          <span className="text-xs text-fg-muted">Custom discount</span>
          <div className="flex gap-2">
            <input
              type="number"
              min="0"
              step="1"
              inputMode="decimal"
              placeholder="₹ off"
              value={discountInput}
              onChange={(e) => setDiscountInput(e.target.value)}
              disabled={applyingDiscount}
              className="input flex-1"
            />
            <button
              className="btn btn-ghost disabled:opacity-40"
              disabled={applyingDiscount || !discountInput}
              onClick={applyManualDiscount}
            >
              {applyingDiscount ? <Loader2 size={16} className="animate-spin"/> : 'Apply'}
            </button>
          </div>
          {discountError && <p className="text-xs text-accent-bad">{discountError}</p>}
        </div>

        <button onClick={() => setShowPay(true)}
          disabled={(!cart.length && !resumingOrder) || !shiftId || paying || !receiptBusiness || !!receiptSettingsError}
          className="btn btn-primary mt-3 disabled:opacity-40 disabled:cursor-not-allowed">
          <ReceiptIcon size={16} /> Prepare bill · est. {inr(Math.max(0, estimatedPayable - pendingCartDiscountMinor - pendingCartPointsMinor))}
        </button>
        {error && <p className="text-accent-bad text-xs mt-2">{error}</p>}
      </aside>

      {/* Mobile sticky charge bar (live mode) */}
      {(cart.length > 0 || resumingOrder) && (
        <button
          onClick={() => (cart.length ? setShowCart(true) : setShowPay(true))}
          className="xl:hidden fixed left-3 right-3 bottom-3 z-30 btn btn-primary !min-h-[60px] min-w-0 text-sm font-bold shadow-2xl sm:text-base"
          style={{ bottom: 'max(0.75rem, calc(env(safe-area-inset-bottom) + 0.5rem))' }}
        >
          <ShoppingCart size={18}/>
          <span className="shrink-0">{cartQty} items</span>
          <span className="opacity-80">·</span>
          <span className="min-w-0 truncate">
            {cart.length ? 'Review cart' : 'Prepare bill'} · est. {inr(Math.max(0, estimatedPayable - pendingCartDiscountMinor - pendingCartPointsMinor))}
          </span>
        </button>
      )}

      {showCart && (
        <Modal title={`Cart · ${cartQty}`} onClose={() => setShowCart(false)}>
          <div className="space-y-3">
            {cart.map((line) => (
              <div key={line.item.id} className="border-b border-bg-border pb-3 last:border-0 last:pb-0">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="font-semibold text-sm break-words">{line.item.name}</div>
                    <div className="text-xs text-fg-muted mt-1">{inr(line.item.base_price_minor)} each</div>
                  </div>
                  <div className="font-mono text-sm shrink-0">{inr(line.item.base_price_minor * line.qty)}</div>
                </div>
                <div className="mt-3 flex items-center justify-end gap-2">
                  <button
                    onClick={() => adjust(line.item.id, -1)}
                    className="btn btn-ghost !min-h-[44px] !min-w-[44px] !p-2"
                    aria-label={`Decrease ${line.item.name} quantity`}
                    title="Decrease quantity"
                  ><Minus size={16}/></button>
                  <span className="w-8 text-center font-mono text-sm" aria-label={`${line.item.name} quantity`}>{line.qty}</span>
                  <button
                    onClick={() => adjust(line.item.id, 1)}
                    className="btn btn-ghost !min-h-[44px] !min-w-[44px] !p-2"
                    aria-label={`Increase ${line.item.name} quantity`}
                    title="Increase quantity"
                  ><Plus size={16}/></button>
                  <button
                    onClick={() => adjust(line.item.id, -line.qty)}
                    className="btn btn-ghost !min-h-[44px] !min-w-[44px] !p-2 text-accent-bad"
                    aria-label={`Remove ${line.item.name} from cart`}
                    title="Remove item"
                  ><Trash2 size={16}/></button>
                </div>
              </div>
            ))}
          </div>

          <div className="mt-4 border-t border-bg-border pt-3 text-sm">
            {estimatedMembershipDiscount > 0 && (
              <div className="mb-1 flex justify-between gap-3 text-accent-good">
                <span>Membership discount (est.)</span>
                <span>-{inr(estimatedMembershipDiscount)}</span>
              </div>
            )}
            {pendingCartDiscountMinor > 0 && (
              <div className="mb-1 flex justify-between gap-3 text-accent-good">
                <span>Custom discount</span>
                <span>-{inr(pendingCartDiscountMinor)}</span>
              </div>
            )}
            {!!resumingOrder?.manual_discount_minor && (
              <div className="mb-1 flex justify-between gap-3 text-accent-good">
                <span>Custom discount</span>
                <span>-{inr(resumingOrder.manual_discount_minor)}</span>
              </div>
            )}
            {pendingCartPointsMinor > 0 && (
              <div className="mb-1 flex justify-between gap-3 text-accent-good">
                <span>Points redeemed</span>
                <span>-{inr(pendingCartPointsMinor)}</span>
              </div>
            )}
            {!!resumingOrder?.points_redeemed_minor && (
              <div className="mb-1 flex justify-between gap-3 text-accent-good">
                <span>Points redeemed</span>
                <span>-{inr(resumingOrder.points_redeemed_minor)}</span>
              </div>
            )}
            <div className="flex justify-between gap-3 text-lg font-bold">
              <span>Total (est.)</span>
              <span>{inr(Math.max(0, estimatedPayable - pendingCartDiscountMinor - pendingCartPointsMinor))}</span>
            </div>
            <p className="mt-1 text-xs text-fg-muted">Final GST split and round-off are computed by the backend.</p>
          </div>

          <div className="mt-3 space-y-2 rounded-xl border border-border-base px-3 py-2">
            <span className="text-xs text-fg-muted">Custom discount</span>
            <div className="flex gap-2">
              <input
                type="number"
                min="0"
                step="1"
                inputMode="decimal"
                placeholder="₹ off"
                value={discountInput}
                onChange={(e) => setDiscountInput(e.target.value)}
                disabled={applyingDiscount}
                className="input flex-1"
              />
              <button
                className="btn btn-ghost disabled:opacity-40"
                disabled={applyingDiscount || !discountInput}
                onClick={applyManualDiscount}
              >
                {applyingDiscount ? <Loader2 size={16} className="animate-spin"/> : 'Apply'}
              </button>
            </div>
            {discountError && <p className="text-xs text-accent-bad">{discountError}</p>}
          </div>

          <button
            onClick={() => {
              setShowCart(false);
              setShowPay(true);
            }}
            disabled={!shiftId || !receiptBusiness || !!receiptSettingsError}
            className="btn btn-primary mt-4 w-full disabled:cursor-not-allowed disabled:opacity-40"
          >
            <ReceiptIcon size={16}/> Choose payment method · est. {inr(estimatedPayable)}
          </button>
        </Modal>
      )}

      {showPay && (
        <Modal title="Choose payment method" onClose={() => { if (!paying) setShowPay(false); }}>
          {estimatedMembershipDiscount > 0 && (
            <div className="mb-3 rounded-xl border border-accent-good/30 bg-accent-good/10 px-3 py-2 text-xs text-accent-good">
              Estimated membership discount: {inr(estimatedMembershipDiscount)}.
            </div>
          )}
          <p className="mb-3 text-xs text-fg-muted">
            The server will create or update the order first. The exact discounted, taxed,
            and rounded amount appears before you collect money.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <PayButton icon={<Banknote size={28}/>}   label="Cash" sub="Prepare exact bill" disabled={paying} onClick={() => prepareCheckout('cash')} />
            <PayButton icon={<Smartphone size={28}/>} label="UPI"  sub="Prepare exact QR"   disabled={paying} onClick={() => prepareCheckout('upi')} />
            <PayButton icon={<CreditCard size={28}/>} label="Card" sub="Prepare exact bill" disabled={paying} onClick={() => prepareCheckout('card')} />
            <PayButton icon={<QrCode size={28}/>}     label="QR"   sub="Prepare exact QR"   disabled={paying} onClick={() => prepareCheckout('qr')} />
          </div>
        </Modal>
      )}

      {checkoutRetry && (
        <Modal
          title={
            checkoutRetry.key === restoredRetryKey
              ? 'Recover checkout'
              : checkoutRetry.phase === 'awaiting_payment'
                ? 'Confirm payment'
                : 'Processing payment'
          }
          onClose={() => undefined}
          locked
        >
          {(() => {
            const amount = checkoutRetry.paymentAmountMinor;
            const tipMinor = checkoutRetry.tipMinor ?? 0;
            const collectibleBalance = hasCollectibleCheckoutBalance(checkoutRetry);
            const benefitCoveredZero = hasBenefitCoveredZeroBalance(checkoutRetry);
            const isGenuineRestore = checkoutRetry.key === restoredRetryKey;
            const scanMethod = checkoutRetry.paymentMethod === 'upi'
              || checkoutRetry.paymentMethod === 'qr';
            const upiLink = checkoutRetry.phase === 'awaiting_payment'
              && scanMethod
              && collectibleBalance
              && receiptBusiness
              && amount !== undefined
              ? buildUpiPayLink(receiptBusiness, amount + tipMinor, receiptBusiness.brandName)
              : null;
            const methodLabel = benefitCoveredZero
              ? 'Membership benefit · no payment'
              : {
                cash: 'Cash',
                upi: 'UPI',
                card: 'Card',
                qr: 'QR',
              }[checkoutRetry.paymentMethod];
            return (
              <div className="space-y-4">
                <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 px-3 py-2 text-sm text-accent-gold">
                  {checkoutRetry.phase === 'preparing_order'
                    ? 'The server bill preparation was interrupted. No payment should be collected yet; resume the same request key.'
                    : checkoutRetry.phase === 'awaiting_payment'
                      ? benefitCoveredZero
                        ? 'The member allowance covers this exact server bill. Collect no money; complete the allowance to issue the final invoice.'
                        : isGenuineRestore
                          ? 'This is the exact server balance, restored after this screen was reopened. Verify payment was not already received before asking the customer to pay again.'
                          : 'This is the exact server balance. Collect payment from the customer now.'
                      : checkoutRetry.phase === 'finalizing_zero'
                        ? 'The no-payment membership settlement is unresolved. Resume only this same key; do not collect money.'
                        : 'Payment was confirmed and the response is unresolved. Replay only this same payment key; never collect money again.'}
                </div>
                <div className="text-center text-sm text-fg-muted">
                  Method: <b className="text-fg">{methodLabel}</b>
                  {amount !== undefined && (
                    <div className="mt-1 text-2xl font-bold text-fg">{inr(amount + tipMinor)}</div>
                  )}
                  {!!tipMinor && amount !== undefined && (
                    <div className="mt-0.5 text-xs">Bill {inr(amount)} + tip {inr(tipMinor)}</div>
                  )}
                </div>
                {checkoutRetry.phase === 'awaiting_payment' && !benefitCoveredZero && (
                  <div className="space-y-2 rounded-xl border border-border-base px-3 py-2">
                    <div className="flex items-center justify-between text-xs text-fg-muted">
                      <span>Custom discount</span>
                      {!!checkoutRetry.orderManualDiscountMinor && (
                        <span>Applied so far: {inr(checkoutRetry.orderManualDiscountMinor)}</span>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <input
                        type="number"
                        min="0"
                        step="1"
                        inputMode="decimal"
                        placeholder="₹ off"
                        value={discountInput}
                        onChange={(e) => setDiscountInput(e.target.value)}
                        disabled={applyingDiscount || paying}
                        className="input flex-1"
                      />
                      <button
                        className="btn btn-ghost disabled:opacity-40"
                        disabled={applyingDiscount || paying || !discountInput}
                        onClick={applyManualDiscount}
                      >
                        {applyingDiscount ? <Loader2 size={16} className="animate-spin"/> : 'Apply'}
                      </button>
                    </div>
                    {discountError && <p className="text-xs text-accent-bad">{discountError}</p>}
                  </div>
                )}
                {checkoutRetry.phase === 'awaiting_payment' && !benefitCoveredZero && collectibleBalance && (
                  <div className="space-y-2 rounded-xl border border-border-base px-3 py-2">
                    <div className="flex items-center justify-between text-xs text-fg-muted">
                      <span>Tip</span>
                      {!!checkoutRetry.tipMinor && (
                        <span>Added: {inr(checkoutRetry.tipMinor)}</span>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <input
                        type="number"
                        min="0"
                        step="1"
                        inputMode="decimal"
                        placeholder="₹ tip"
                        value={tipInput}
                        onChange={(e) => setTipInput(e.target.value)}
                        disabled={paying}
                        className="input flex-1"
                      />
                      <button
                        className="btn btn-ghost disabled:opacity-40"
                        disabled={paying || !tipInput}
                        onClick={applyTip}
                      >
                        Add
                      </button>
                    </div>
                    {tipError && <p className="text-xs text-accent-bad">{tipError}</p>}
                  </div>
                )}
                {checkoutRetry.phase === 'awaiting_payment' && !benefitCoveredZero && collectibleBalance
                  && checkoutRetry.paymentMethod === 'cash' && amount !== undefined && (() => {
                  const dueMinor = amount + tipMinor;
                  const trimmedTendered = cashTenderedInput.trim();
                  const tenderedRupees = trimmedTendered ? Number(trimmedTendered) : NaN;
                  const tenderedMinor = trimmedTendered && Number.isFinite(tenderedRupees) && tenderedRupees >= 0
                    ? Math.round(tenderedRupees * 100)
                    : null;
                  const changeMinor = tenderedMinor !== null ? tenderedMinor - dueMinor : null;
                  // Dedupe — at larger due amounts, "next ₹500 above" and
                  // "next ₹1000 above" can land on the same round number.
                  const quickTenders = [100, 500, 1000]
                    .map((denomination) => nextRoundTenderMinor(dueMinor, denomination))
                    .filter((value, index, all) => all.indexOf(value) === index);
                  return (
                    <div className="space-y-2 rounded-xl border border-border-base px-3 py-2">
                      <div className="text-xs text-fg-muted">Cash tendered</div>
                      <input
                        type="number"
                        min="0"
                        step="0.01"
                        inputMode="decimal"
                        placeholder="₹ received from customer"
                        value={cashTenderedInput}
                        onChange={(e) => setCashTenderedInput(e.target.value)}
                        disabled={paying}
                        className="input font-mono text-lg"
                      />
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          className="btn btn-ghost !min-h-[40px] !py-1.5 !px-3 text-xs disabled:opacity-40"
                          disabled={paying}
                          onClick={() => setCashTenderedInput((dueMinor / 100).toFixed(2))}
                        >
                          Exact amount
                        </button>
                        {quickTenders.map((quickMinor) => (
                          <button
                            key={quickMinor}
                            type="button"
                            className="btn btn-ghost !min-h-[40px] !py-1.5 !px-3 text-xs disabled:opacity-40"
                            disabled={paying}
                            onClick={() => setCashTenderedInput((quickMinor / 100).toFixed(2))}
                          >
                            {inr(quickMinor, { decimals: 0 })}
                          </button>
                        ))}
                      </div>
                      {changeMinor !== null && (
                        changeMinor >= 0 ? (
                          <div className="rounded-lg border border-accent-good/40 bg-accent-good/15 px-3 py-2 text-center text-accent-good">
                            <div className="text-xs uppercase tracking-wide">Change to return</div>
                            <div className="text-3xl font-bold">{inr(changeMinor)}</div>
                          </div>
                        ) : (
                          <div className="rounded-lg border border-accent-bad/40 bg-accent-bad/15 px-3 py-2 text-center text-accent-bad">
                            <div className="text-xs uppercase tracking-wide">More needed</div>
                            <div className="text-3xl font-bold">{inr(Math.abs(changeMinor))}</div>
                          </div>
                        )
                      )}
                    </div>
                  );
                })()}
                {checkoutRetry.phase === 'awaiting_payment' && !collectibleBalance && !benefitCoveredZero && (
                  <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 px-3 py-2 text-xs text-accent-bad">
                    This saved bill has no valid positive server balance. Do not collect money; ask a protected owner to reconcile it.
                  </div>
                )}
                {checkoutRetry.phase === 'awaiting_payment' && scanMethod && collectibleBalance && (
                  upiLink ? (
                    <div className="flex flex-col items-center gap-2">
                      <div className="rounded-lg bg-white p-2">
                        <QRCodeSVG value={upiLink} size={192} marginSize={2} level="M" />
                      </div>
                      <div className="font-mono text-[10px] text-fg-muted">
                        {receiptBusiness?.upiVpa}
                      </div>
                    </div>
                  ) : (
                    <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 px-3 py-2 text-xs text-accent-bad">
                      The exact amount is available, but the configured UPI QR could not be loaded. Verify payment externally before confirming.
                    </div>
                  )
                )}
                {error && <p className="text-xs text-accent-bad">{error}</p>}
                {checkoutRetry.phase === 'awaiting_payment' ? (
                  <div className="grid grid-cols-2 gap-3">
                    <button className="btn btn-ghost disabled:opacity-40"
                      disabled={paying} onClick={startAbandonPreparedCheckout}>
                      {benefitCoveredZero ? 'Cancel prepared bill' : 'No payment · Cancel'}
                    </button>
                    <button className="btn btn-primary disabled:opacity-40"
                      disabled={paying || (!collectibleBalance && !benefitCoveredZero)} onClick={completeCheckout}>
                      {paying ? <Loader2 size={16} className="animate-spin"/> : <Check size={16}/>} {benefitCoveredZero ? 'Complete member benefit' : 'Payment received'}
                    </button>
                  </div>
                ) : (
                  <button
                    className="btn btn-primary w-full disabled:opacity-40"
                    disabled={paying}
                    onClick={() => checkoutRetry.phase === 'preparing_order'
                      ? prepareCheckout(checkoutRetry.paymentMethod)
                      : completeCheckout()}
                  >
                    {paying ? <Loader2 size={16} className="animate-spin"/> : <RefreshCwIcon/>}
                    {paying ? 'Reconciling safely…' : 'Resume same attempt'}
                  </button>
                )}
              </div>
            );
          })()}
        </Modal>
      )}

      {receipt && receiptBusiness && (
        <Modal title="Receipt" onClose={() => setReceipt(null)} wide>
          <LiveReceipt order={receipt} business={receiptBusiness} />
          <div className="flex gap-2 mt-4 print:hidden">
            <button onClick={() => window.print()} className="btn btn-primary flex-1"><ReceiptIcon size={16}/> Print</button>
            <button onClick={() => setReceipt(null)} className="btn btn-ghost"><Check size={16}/> Done</button>
          </div>
        </Modal>
      )}

      {showHeldPicker && (
        <Modal title="Incoming orders (sent from Tables & Gaming)" onClose={() => setShowHeldPicker(false)} wide>
          <div className="relative mb-3">
            <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-fg-muted"/>
            <input
              className="input !pl-9 !min-h-[42px] !py-2"
              value={heldSearch}
              onChange={(e) => setHeldSearch(e.target.value)}
              placeholder="Search table or station"
              autoFocus
            />
          </div>
          {heldError && <p className="text-accent-bad text-xs mb-2">{heldError}</p>}
          {heldLoading ? (
            <div className="flex items-center gap-2 text-fg-muted text-sm py-6 justify-center">
              <Loader2 className="animate-spin" size={16}/> Loading…
            </div>
          ) : (
            <div className="space-y-2 max-h-[50vh] overflow-y-auto">
              {heldOrders
                .filter((o) => {
                  const q = heldSearch.trim().toLowerCase();
                  if (!q) return true;
                  return [o.source_label, o.customer_name, o.invoice_no]
                    .some((v) => v?.toLowerCase().includes(q));
                })
                .map((o) => {
                  const heldSince = o.held_at ?? o.created_at;
                  const ageMin = Math.floor((Date.now() - new Date(heldSince).getTime()) / 60_000);
                  const overdue = ageMin >= HELD_ORDER_ALARM_MINUTES;
                  return (
                    <div key={o.id} className="card flex items-center gap-3">
                      <button onClick={() => resumeOrder(o)} className="flex-1 min-w-0 text-left">
                        <div className="font-semibold text-sm truncate">
                          {o.source_label || `Order ${o.id.slice(0, 8)}`}
                          {o.status === 'open' && (
                            <span className="ml-2 chip !py-0 !px-1.5 !text-[9px]">Recovered POS bill</span>
                          )}
                        </div>
                        <div className={`text-xs ${overdue ? 'text-accent-bad' : 'text-fg-muted'}`}>
                          {o.items_count} item{o.items_count === 1 ? '' : 's'}
                          {o.customer_name ? ` · ${o.customer_name}` : ''}
                          {' · '}{ageMin} min ago
                        </div>
                      </button>
                      <div className="font-mono font-bold shrink-0">{inr(o.total_minor)}</div>
                      <button
                        className="btn btn-ghost !p-2 text-accent-bad shrink-0"
                        disabled={voidingId === o.id}
                        onClick={() => setVoidPromptRow(o)}
                        title="Void this queued order with a reason">
                        {voidingId === o.id ? <Loader2 className="animate-spin" size={14}/> : <Trash2 size={14}/>}
                      </button>
                    </div>
                  );
                })}
              {!heldOrders.length && (
                <p className="text-fg-muted text-sm text-center py-6">
                  Nothing waiting — Tables/Gaming sends and recoverable direct POS bills appear here.
                </p>
              )}
            </div>
          )}
        </Modal>
      )}
      {voidPromptRow && (
        <PromptModal
          title="Void queued order"
          label={`Why are you clearing ${voidPromptRow.source_label || 'this order'}?`}
          confirmLabel="Void"
          busy={voidingId === voidPromptRow.id}
          onSubmit={(reason) => { const row = voidPromptRow; setVoidPromptRow(null); void voidOrder(row, reason); }}
          onCancel={() => setVoidPromptRow(null)}
        />
      )}
      {abandonConfirmVariant && (
        <ConfirmModal
          title="Cancel prepared bill"
          message={abandonConfirmVariant === 'benefit_covered'
            ? 'Cancel this prepared membership-covered bill? No money is due and the allowance has not been consumed yet.'
            : 'Confirm that NO cash, UPI, QR, or card payment was received for this bill.'}
          confirmLabel="Cancel bill"
          danger
          busy={paying}
          onConfirm={abandonPreparedCheckout}
          onCancel={() => setAbandonConfirmVariant(null)}
        />
      )}
      {abandonReasonOrder && (
        <PromptModal
          title="Cancel prepared bill"
          label="Why are you cancelling this prepared direct POS bill?"
          confirmLabel="Cancel bill"
          busy={paying}
          onSubmit={finishAbandonWithReason}
          onCancel={() => setAbandonReasonOrder(null)}
        />
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
                Visits: {customer.visit_count} · Points: {customer.loyalty_points.toLocaleString('en-IN')} · {customer.gaming_rank} rank
                {customer.next_gaming_rank && ` (${customer.points_to_next_gaming_rank} pts to ${customer.next_gaming_rank})`}
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
  if (item.type === 'gaming' || item.type === 'streaming') return tier.gaming_discount_pct;
  if (item.type === 'hookah') return tier.hookah_discount_pct;
  return 0;
}

// Smallest amount, strictly greater than dueMinor, that is an exact multiple
// of `denominationRupees` — used for the cash quick-tender round-up
// shortcuts (e.g. "next ₹100 above the amount due"). Pure integer (paise)
// arithmetic throughout, no floating-point division of money.
function nextRoundTenderMinor(dueMinor: number, denominationRupees: number): number {
  const stepMinor = denominationRupees * 100;
  return (Math.floor(dueMinor / stepMinor) + 1) * stepMinor;
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

function RefreshCwIcon() {
  return <ReceiptIcon size={16}/>;
}

function Modal({
  title,
  children,
  onClose,
  wide,
  locked = false,
}: {
  title: string;
  children: React.ReactNode;
  onClose: () => void;
  wide?: boolean;
  locked?: boolean;
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-end md:items-center justify-center bg-bg/80 backdrop-blur-sm md:p-4 print:p-0 print:bg-white"
      onClick={locked ? undefined : onClose}
    >
      <div
        className={`bg-bg-surface border border-bg-border rounded-t-2xl md:rounded-2xl shadow-glow w-full ${wide ? 'md:max-w-md' : 'md:max-w-sm'} max-h-[calc(100dvh-1rem)] overflow-auto print:max-w-none print:w-auto print:bg-white print:text-black print:border-none print:shadow-none print:overflow-visible`}
        onClick={(e) => e.stopPropagation()}
        style={{ paddingBottom: 'max(0.5rem, env(safe-area-inset-bottom))' }}
      >
        <div className="flex items-center justify-between p-4 border-b border-bg-border print:hidden sticky top-0 bg-bg-surface">
          <h3 className="font-semibold">{title}</h3>
          {locked ? null : (
            <button
              onClick={onClose}
              className="text-fg-muted hover:text-fg p-1 -m-1"
              aria-label={`Close ${title}`}
              title="Close"
            ><X size={20}/></button>
          )}
        </div>
        <div className="p-4">{children}</div>
      </div>
    </div>
  );
}
