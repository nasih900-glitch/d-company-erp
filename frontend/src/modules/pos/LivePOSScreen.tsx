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
 * it remains attributed for that shift's cash and payment activity.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Plus, Minus, Trash2, ShoppingCart, Receipt as ReceiptIcon,
  Banknote, CreditCard, Smartphone, QrCode, X, Check, Loader2,
  Search, UserRound, Crown, AlertCircle, Inbox, XCircle, BellOff, Bell,
} from 'lucide-react';

import { ALARM_REPEAT_MS, notifyAlarm, playAlarmTone } from '@/lib/alarm';
import {
  clearDraftIfUnchanged,
  loadDraftSnapshot,
  saveDraftIfUnchanged,
} from '@/lib/draft-storage';
import { inr } from '@/lib/inr';
import {
  POS_CART_CLEARED_FEEDBACK,
  POS_PREPARED_BILL_CANCELLED_FEEDBACK,
  queuedOrderVoidedFeedback,
} from '@/lib/action-feedback';
import { parseRupeesToMinor } from '@/lib/money-input';
import {
  customers,
  memberships,
  menu,
  orders,
  pos,
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
import {
  beginRealtimeShiftRefresh,
  bindPosLocalWorkShift,
  canCompletePosDraftHydrationAfterLoadFailure,
  canReconcileRealtimePosShift,
  clearStoredShift,
  hasOrdinaryPosDraftShiftConflict,
  invalidateRealtimeShiftRefresh,
  resolvePosAccountableShiftId,
  resolveRealtimeOpenShift,
  resolveRequiredOpenShift,
  shiftResolutionMessage,
  storeShiftId,
} from '@/lib/operational-context';
import {
  GAMING_CENTRE_FEATURES,
  profileOperationalCatalogItems,
  profileMembershipMoneyLabel,
  profilePosCheckoutSource,
  profilePosOrderType,
} from '@/lib/product-profile';
import {
  applyCanonicalCheckoutBalance,
  buildCheckoutPaymentSubmission,
  buildCheckoutZeroFinalization,
  createOperationKey,
  hasBenefitCoveredZeroBalance,
  hasCollectibleCheckoutBalance,
  isAmbiguousApiError,
  isBusinessRuleApiError,
  isCartStageDiscountAlreadyApplied,
  isCartStagePointsAlreadyApplied,
  isCheckoutClaimRejection,
  normalizePosRetryDraft,
  reconcileCartStageBenefitsAfterReload,
  retireAppliedCartStageBenefit,
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
import { useNotifications } from '@/components/ui/Notifications';
import { subscribeRealtime } from '@/lib/realtime';

const MEMBERSHIP_UI_ENABLED = GAMING_CENTRE_FEATURES.memberships;
const RESTAURANT_ORDER_TYPES_UI_ENABLED = GAMING_CENTRE_FEATURES.restaurantOrderTypes;
const TAX_COMPLIANCE_UI_ENABLED = GAMING_CENTRE_FEATURES.taxCompliance;
const DEFAULT_POS_ORDER_TYPE = profilePosOrderType();
const PREPAID_DISCOUNT_LABEL = profileMembershipMoneyLabel('discount', 1)
  ?? 'Prepaid programme discount';
const PREPAID_ALLOWANCE_LABEL = profileMembershipMoneyLabel('allowance', 1)
  ?? 'Prepaid allowance';

import LiveReceipt from './LiveReceipt';
import {
  buildReceiptBusinessDetails,
  buildUpiPayLink,
  receiptConfigurationIssue,
  type ReceiptBusinessDetails,
} from './receipt-business';
import { PosMoneyInput } from './PosMoneyInput';
import {
  adjustPosCart,
  canStageManualPosDiscount,
  enterSynchronousPosFlow,
  isIncomingSharedPosOrder,
  isCurrentPosCustomerLookup,
  leaveSynchronousPosFlow,
  mayReleaseCancelledPreparedBill,
  mayClaimCheckoutDuringHydration,
  posDraftNeedsReconciliation,
} from './pos-draft-policy';
import { Skeleton } from '@/components/ui/Skeleton';

type CartLine = { item: MenuItemDTO; qty: number; unavailable?: boolean };
type PayMethod = CheckoutPaymentMethod;
type OrderType = CheckoutOrderType;
type DeliveryVia = CheckoutDeliveryVia;
type CustomerLookupState = 'idle' | 'found' | 'new' | 'error';
type DraftLeaseState = {
  key: string | null;
  status: 'checking' | 'owned' | 'blocked' | 'unsupported';
};

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

function unavailableSavedMenuItem(itemId: string): MenuItemDTO {
  return {
    id: itemId,
    category_id: '',
    sku: itemId,
    name: 'Unavailable saved item',
    type: 'unavailable',
    base_price_minor: 0,
    tax_rate: 0,
    hsn_code: null,
    price_includes_tax: false,
    is_available: false,
    description: 'This item no longer exists in the current catalogue.',
  };
}

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

// Phases where a server order exists but no money has been collected yet, so
// the locked recovery can safely be cancelled and the terminal freed for the
// next customer. 'recording_payment' / 'finalizing_zero' stay strictly locked,
// because a payment may genuinely be in flight there.
function canAbandonCheckoutRetry(retry: PosCheckoutRetry | null): boolean {
  return Boolean(
    retry?.pendingOrderId
    && (retry.phase === 'awaiting_payment' || retry.phase === 'preparing_order'),
  );
}

function hasUsableCheckoutClaim(retry: PosCheckoutRetry, now = Date.now()): boolean {
  if (!retry.checkoutClaimRequired) return true;
  if (!retry.checkoutClaimToken || !retry.checkoutClaimExpiresAt) return false;
  const expiresAt = Date.parse(retry.checkoutClaimExpiresAt);
  // Leave enough time for the payment request to reach a congested cafe link.
  return Number.isFinite(expiresAt) && expiresAt > now + 15_000;
}

/**
 * Once staff confirm that money was physically received, the journaled bill
 * must never be silently replaced by a newer server snapshot. A fresh claim
 * is safe only when every settlement-affecting value is still identical.
 */
function matchesConfirmedSettlement(retry: PosCheckoutRetry, order: OrderDTO): boolean {
  return retry.pendingOrderId === order.id
    && retry.checkoutClaimOrderVersion === order.checkout_version
    && retry.orderTotalMinor === order.total_minor
    && retry.paymentAmountMinor === order.due_minor
    && (retry.orderDiscountMinor ?? 0) === order.discount_minor
    && (retry.orderManualDiscountMinor ?? 0) === order.manual_discount_minor
    && (retry.orderPointsRedeemedMinor ?? 0) === order.points_redeemed_minor
    && (retry.freeGamingMinutesApplied ?? 0) === order.free_gaming_minutes_applied
    && (retry.freeHookahCountApplied ?? 0) === order.free_hookah_count_applied;
}

export default function LivePOSScreen() {
  const { me, terminalId, terminalReady } = useAuth();
  const notifications = useNotifications();
  const canManualDiscount = canStageManualPosDiscount(me?.effective_permissions);
  const draftKey = me?.company_id && me.branch_id && me.user_id && terminalId
    ? posDraftKey(me.company_id, me.branch_id, me.user_id, terminalId)
    : null;
  const [items, setItems] = useState<MenuItemDTO[]>([]);
  const [shiftId, setShiftId] = useState<string | null>(null);
  // Immutable while an ordinary cart or resumed bill exists. Realtime may
  // clear/change the currently open shift, but it must never erase or rewrite
  // which shift owns already-started local work.
  const [localWorkShiftId, setLocalWorkShiftId] = useState<string | null>(null);
  const [shiftError, setShiftError] = useState<string | null>(null);
  const [shiftCollections, setShiftCollections] = useState<{
    posMinor: number;
    membershipMinor: number;
    grossMinor: number;
  } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [draftStorageConflict, setDraftStorageConflict] = useState(false);
  const [draftLeaseState, setDraftLeaseState] = useState<DraftLeaseState>(() => ({
    key: draftKey,
    status: draftKey ? 'checking' : 'unsupported',
  }));

  const [cart, setCart] = useState<CartLine[]>([]);
  const [orderType, setOrderType] = useState<OrderType>(DEFAULT_POS_ORDER_TYPE);
  const [deliveryVia, setDeliveryVia] = useState<DeliveryVia>('inhouse');
  const [deliveryStateCode, setDeliveryStateCode] = useState('32');
  const [query, setQuery] = useState('');
  const [customerPhone, setCustomerPhone] = useState('');
  const customerPhoneRef = useRef('');
  const customerLookupGenerationRef = useRef(0);
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
  const checkoutFlowInFlightRef = useRef(false);
  const checkoutMutationInFlightRef = useRef(false);
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
  const [unresolvedResumingOrderId, setUnresolvedResumingOrderId] = useState<string | null>(null);
  const [heldAlarmMuted, setHeldAlarmMuted] = useState(false);
  const [voidingId, setVoidingId] = useState<string | null>(null);
  const [voidPromptRow, setVoidPromptRow] = useState<OrderListItemDTO | null>(null);
  const [showCartClearConfirm, setShowCartClearConfirm] = useState(false);
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
  const [pendingCartPointsCustomerPhone, setPendingCartPointsCustomerPhone] = useState<string | null>(null);
  const [rewards, setRewards] = useState<RewardDTO[]>([]);
  const [redeemingReward, setRedeemingReward] = useState<string | null>(null);
  const [rewardError, setRewardError] = useState<string | null>(null);
  const checkoutAdjustmentBusy = applyingDiscount || applyingPoints || redeemingReward !== null;
  // Tip is never applied to the server ahead of time — unlike the discount
  // above, it only travels with the final payment call (see
  // buildCheckoutPaymentSubmission in retry-drafts.ts), so entering it just
  // updates checkoutRetry.tipMinor locally.
  const [tipInput, setTipInput] = useState('');
  const [tipError, setTipError] = useState<string | null>(null);
  // Cash handed over by the customer. It is parsed into exact integer paise,
  // persisted before submission, and sent as tendered_minor so the receipt and
  // reconciliation record the same change the cashier sees here.
  const [cashTenderedInput, setCashTenderedInput] = useState('');
  const lastHeldAlarmAtRef = useRef(0);
  const shiftRefreshGenerationRef = useRef(0);
  const shiftIdRef = useRef(shiftId);
  shiftIdRef.current = shiftId;
  const draftStorageKeyRef = useRef<string | null>(null);
  const draftStorageTokenRef = useRef<string | null>(null);
  const draftLeaseOwnedKeyRef = useRef<string | null>(null);
  const draftLeaseReleaseRef = useRef<(() => void) | null>(null);
  const heldOrderScopeRef = useRef(draftKey);
  heldOrderScopeRef.current = draftKey;

  // Keep one POS screen as the sole writer for this employee/terminal draft.
  // Web Locks provides actual mutual exclusion; the token checks below remain
  // a second line of defence and the fallback for older browsers. Holding the
  // lock for the screen lifetime lets financial checkpoints stay synchronous
  // immediately before their API request.
  useEffect(() => {
    draftLeaseReleaseRef.current?.();
    draftLeaseReleaseRef.current = null;
    draftLeaseOwnedKeyRef.current = null;

    if (!draftKey) {
      setDraftLeaseState({ key: null, status: 'unsupported' });
      return;
    }
    if (!navigator.locks) {
      setDraftLeaseState({ key: draftKey, status: 'unsupported' });
      return;
    }

    let cancelled = false;
    setDraftLeaseState({ key: draftKey, status: 'checking' });
    const requestLease = (attempt: number) => {
      void navigator.locks.request(
        `dcompany-pos-draft:${draftKey}`,
        { mode: 'exclusive', ifAvailable: true },
        async (lock) => {
          if (cancelled) return;
          if (!lock) {
            // React's development safety pass mounts, releases, and remounts
            // effects in quick succession. The second non-blocking request can
            // briefly race the first request's release and falsely report that
            // another tab owns POS. Retry that transient once; a genuine other
            // tab still fails the second probe and remains safely read-only.
            if (attempt === 0) {
              await new Promise<void>((resolve) => window.setTimeout(resolve, 50));
              if (!cancelled) requestLease(1);
            } else {
              setDraftLeaseState({ key: draftKey, status: 'blocked' });
            }
            return;
          }
          draftLeaseOwnedKeyRef.current = draftKey;
          setDraftLeaseState({ key: draftKey, status: 'owned' });
          await new Promise<void>((resolve) => {
            draftLeaseReleaseRef.current = resolve;
          });
          if (draftLeaseOwnedKeyRef.current === draftKey) {
            draftLeaseOwnedKeyRef.current = null;
          }
        },
      ).catch(() => {
        if (!cancelled) setDraftLeaseState({ key: draftKey, status: 'blocked' });
      });
    };
    requestLease(0);

    return () => {
      cancelled = true;
      if (draftLeaseOwnedKeyRef.current === draftKey) {
        draftLeaseReleaseRef.current?.();
        draftLeaseReleaseRef.current = null;
        draftLeaseOwnedKeyRef.current = null;
      }
    };
  }, [draftKey]);

  function savePosDraft(key: string, value: PosRetryDraft): boolean {
    if (draftStorageKeyRef.current !== key) return false;
    if (!navigator.locks || draftLeaseOwnedKeyRef.current !== key) {
      setDraftStorageConflict(true);
      setError(
        !navigator.locks
          ? 'This browser cannot provide the protected single-writer storage required by POS. Nothing was saved; update the browser or use the supported app.'
          : 'This POS draft is open in another browser tab. Nothing here was saved or overwritten. '
            + 'Close the other POS tab, then reload this one before continuing.',
      );
      return false;
    }
    const result = saveDraftIfUnchanged(key, value, draftStorageTokenRef.current);
    if (result.ok) {
      draftStorageTokenRef.current = result.token;
      return true;
    }
    setDraftStorageConflict(true);
    setError(result.reason === 'conflict'
      ? 'This POS draft changed in another browser tab. Nothing here was overwritten. Reload POS and reconcile the saved bill before continuing.'
      : 'POS recovery storage is unavailable. Keep this page open and restore browser storage before continuing.');
    return false;
  }

  function clearPosDraft(key: string): boolean {
    if (draftStorageKeyRef.current !== key) return false;
    if (!navigator.locks || draftLeaseOwnedKeyRef.current !== key) {
      setDraftStorageConflict(true);
      setError(
        !navigator.locks
          ? 'This browser cannot safely own POS recovery storage, so the saved bill was not discarded. Update the browser or use the supported app.'
          : 'This POS draft is open in another browser tab and was not discarded. '
            + 'Close the other POS tab, reload this one, and review the latest saved bill.',
      );
      return false;
    }
    const result = clearDraftIfUnchanged(key, draftStorageTokenRef.current);
    if (result.ok) {
      draftStorageTokenRef.current = result.token;
      setDraftStorageConflict(false);
      return true;
    }
    setDraftStorageConflict(true);
    setError(result.reason === 'conflict'
      ? 'This POS draft changed in another browser tab and was not discarded. Reload POS and reconcile the saved bill.'
      : 'POS recovery storage could not be cleared. The saved bill remains in place; restore browser storage and try again.');
    return false;
  }

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
    if (
      draftKey
      && (draftLeaseState.key !== draftKey || draftLeaseState.status === 'checking')
    ) {
      setLoading(true);
      return;
    }
    let cancelled = false;
    const storageSnapshot = draftKey
      ? loadDraftSnapshot<unknown>(draftKey)
      : { value: null, token: null };
    const storedDraft = normalizePosRetryDraft(storageSnapshot.value);
    const malformedStoredDraft = Boolean(storageSnapshot.token && !storedDraft);
    draftStorageKeyRef.current = draftKey;
    draftStorageTokenRef.current = storageSnapshot.token;
    const draftWriterBlocked = Boolean(draftKey)
      && draftLeaseState.key === draftKey
      && (draftLeaseState.status === 'blocked' || draftLeaseState.status === 'unsupported');
    setDraftStorageConflict(malformedStoredDraft || draftWriterBlocked);
    setHydratedDraftKey(null);
    setLocalWorkShiftId(
      storedDraft?.retry?.snapshot.shiftId ?? storedDraft?.shiftId ?? null,
    );
    setItems([]);
    setCart([]);
    setPendingCartDiscountMinor(0);
    setPendingCartPointsMinor(0);
    setPendingCartPointsCustomerPhone(null);
    setResumingOrder(null);
    setUnresolvedResumingOrderId(null);
    setHeldOrders([]);
    setHeldError(null);
    setOrderType(DEFAULT_POS_ORDER_TYPE);
    setDeliveryVia('inhouse');
    setDeliveryStateCode('32');
    customerLookupGenerationRef.current += 1;
    customerPhoneRef.current = '';
    setCustomerPhone('');
    setCustomerName('');
    setCustomer(null);
    setSubscription(null);
    setMembershipTier(null);
    setCustomerLookupState('idle');
    setCustomerMessage(null);
    setCustomerBusy(false);
    setShowCart(false);
    setShowPay(false);
    setReceipt(null);
    setError(malformedStoredDraft
      ? 'A saved POS draft could not be read. It was preserved and locked; explicitly discard it only after reconciliation.'
      : draftWriterBlocked
        ? draftLeaseState.status === 'unsupported'
          ? 'This browser does not support protected POS draft ownership. POS is read-only; update the browser or use the supported app.'
          : 'POS is already open in another browser tab for this employee and till. This tab is read-only so it cannot overwrite that bill.'
        : null);
    setCheckoutRetry(storedDraft?.retry ?? null);
    setRestoredRetryKey(storedDraft?.retry?.key ?? null);
    if (storedDraft) {
      // Restore the only durable evidence immediately, before any catalogue
      // request. If the menu service is unavailable, staff must still see the
      // item ids and quantities that were served; placeholders deliberately
      // make an ordinary draft read-only until the catalogue can price it.
      setCart(storedDraft.cart.map((line) => ({
        item: unavailableSavedMenuItem(line.itemId),
        qty: line.qty,
        unavailable: true,
      })));
      setOrderType(profilePosOrderType({
        orderType: storedDraft.orderType,
        hasCheckoutRetry: Boolean(storedDraft.retry),
        resumingOrderId: storedDraft.retry?.resumingOrderId ?? storedDraft.resumingOrderId,
      }));
      setDeliveryVia(storedDraft.deliveryVia);
      setDeliveryStateCode(storedDraft.deliveryStateCode);
      setCustomerName(storedDraft.customerName);
      customerPhoneRef.current = storedDraft.customerPhone;
      setCustomerPhone(storedDraft.customerPhone);
      setPendingCartDiscountMinor(storedDraft.pendingCartDiscountMinor ?? 0);
      setPendingCartPointsMinor(storedDraft.pendingCartPointsMinor ?? 0);
      setPendingCartPointsCustomerPhone(storedDraft.pendingCartPointsCustomerPhone ?? null);
    }
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
          throw new Error('This device is not ready for POS. Refresh it; if the problem remains, ask an owner to check the device setup.');
        }
        if (!draftKey) {
          throw new Error('The POS recovery context could not be verified. Refresh before starting a bill.');
        }
        const activeDraftKey = draftKey;
        const [menuResult, shiftResult] = await Promise.allSettled([
          Promise.all([menu.items(), menu.categories()]),
          resolveRequiredOpenShift({
            scope: { companyId, branchId, terminalId },
            listOpenShifts: () => shifts.list(true),
          }),
        ]);
        if (cancelled) return;
        if (menuResult.status === 'rejected') throw menuResult.reason;
        const [all, menuCategories] = menuResult.value;
        let resolvedShiftId: string | null = null;
        if (shiftResult.status === 'fulfilled') {
          resolvedShiftId = shiftResult.value;
        } else {
          const hasStoredWork = Boolean(
            storedDraft?.retry
            || storedDraft?.resumingOrderId
            || storedDraft?.cart.length,
          );
          if (!hasStoredWork) throw shiftResult.reason;
          // Any durable work must remain visible even if the original shift
          // subsequently closed. Checkout recovery remains actionable under
          // its journal; an ordinary cart is restored read-only for explicit
          // reconciliation instead of deleting the only copy of its items.
          setShiftError((shiftResult.reason as Error).message);
        }
        const available = profileOperationalCatalogItems(all, menuCategories)
          .filter((i) => isAppStoreAllowedType(i.type));
        const restorable = all.filter((i) => isAppStoreAllowedType(i.type));
        setItems(available);

        let ordinaryDraftAccountabilityError: string | null = null;

        if (storedDraft) {
          if (
            !storedDraft.retry
            && !storedDraft.shiftId
            && (storedDraft.cart.length > 0 || storedDraft.resumingOrderId)
          ) {
            ordinaryDraftAccountabilityError =
              'This saved bill has no verified shift identity. Its items were preserved but locked. '
              + 'Review them, then explicitly discard and rebuild the bill under the current shift.';
          }
          if (hasOrdinaryPosDraftShiftConflict({
            hasCheckoutRecovery: Boolean(storedDraft.retry),
            storedShiftId: storedDraft.shiftId ?? null,
            resolvedShiftId,
          })) {
            ordinaryDraftAccountabilityError =
              'This saved bill belongs to a shift that is no longer open. Its items were preserved but locked, '
              + 'not moved to the current shift. Review them, then explicitly discard and rebuild only after reconciliation.';
          }
          const restoredCart = storedDraft.cart.map((d): CartLine => {
            // Soft-delete/reprofiling removes items from /menu/items, but the
            // saved id/quantity may be the only record that they were served.
            // Keep an explicit placeholder and lock ordinary checkout instead
            // of silently filtering the line and rewriting the durable draft.
            const item = restorable.find((i) => i.id === d.itemId);
            return item
              ? { item, qty: d.qty }
              : { item: unavailableSavedMenuItem(d.itemId), qty: d.qty, unavailable: true };
          });
          const unavailableLineCount = restoredCart.filter((line) => line.unavailable).length;
          if (unavailableLineCount > 0 && !storedDraft.retry?.pendingOrderId) {
            ordinaryDraftAccountabilityError =
              `${unavailableLineCount} saved item${unavailableLineCount === 1 ? '' : 's'} no longer exist in the catalogue. `
              + 'The original ids and quantities were preserved, but this bill is locked for owner reconciliation.';
          }
          setCart(restoredCart);
          setOrderType(profilePosOrderType({
            orderType: storedDraft.orderType,
            hasCheckoutRetry: Boolean(storedDraft.retry),
            resumingOrderId: storedDraft.retry?.resumingOrderId ?? storedDraft.resumingOrderId,
          }));
          setDeliveryVia(storedDraft.deliveryVia);
          setDeliveryStateCode(storedDraft.deliveryStateCode);
          setCustomerName(storedDraft.customerName);
          customerPhoneRef.current = storedDraft.customerPhone;
          setCustomerPhone(storedDraft.customerPhone);

          let retry = storedDraft.retry;
          let restoredPendingDiscountMinor = storedDraft.pendingCartDiscountMinor ?? 0;
          let restoredPendingPointsMinor = storedDraft.pendingCartPointsMinor ?? 0;
          let restoredPendingPointsCustomerPhone = storedDraft.pendingCartPointsCustomerPhone;
          let pendingOrder: OrderDTO | null = null;
          // Set when the saved cart is thrown away below. A cart-stage
          // discount/points redemption belongs to that exact cart, so it must
          // go with it instead of reattaching to whatever is billed next.
          let savedCartDiscarded = false;
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
                setUnresolvedResumingOrderId(null);
                setCheckoutRetry(null);
                setLocalWorkShiftId(null);
                clearPosDraft(activeDraftKey);
                setHydratedDraftKey(activeDraftKey);
                return;
              }
              if (pendingOrder.status === 'void' && retry.phase !== 'recording_payment') {
                setCart([]);
                setResumingOrder(null);
                setUnresolvedResumingOrderId(null);
                setCheckoutRetry(null);
                setLocalWorkShiftId(null);
                clearPosDraft(activeDraftKey);
                setError('The prepared order was voided, so this local checkout recovery was closed.');
                setHydratedDraftKey(activeDraftKey);
                return;
              }
              if (pendingOrder.status === 'refunded' && retry.phase !== 'recording_payment') {
                setCart([]);
                setResumingOrder(null);
                setUnresolvedResumingOrderId(null);
                setCheckoutRetry(null);
                setLocalWorkShiftId(null);
                clearPosDraft(activeDraftKey);
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
              const reconciledBenefits = reconcileCartStageBenefitsAfterReload(
                retry,
                pendingOrder,
                restoredPendingDiscountMinor,
                restoredPendingPointsMinor,
                restoredPendingPointsCustomerPhone,
              );
              retry = reconciledBenefits.retry;
              restoredPendingDiscountMinor = reconciledBenefits.pendingDiscountMinor;
              restoredPendingPointsMinor = reconciledBenefits.pendingPointsMinor;
              restoredPendingPointsCustomerPhone = reconciledBenefits.pendingPointsCustomerPhone;
              const reconciledStoredDraft: PosRetryDraft = {
                ...storedDraft,
                pendingCartDiscountMinor: restoredPendingDiscountMinor || undefined,
                pendingCartPointsMinor: restoredPendingPointsMinor || undefined,
                pendingCartPointsCustomerPhone:
                  restoredPendingPointsMinor > 0
                    ? restoredPendingPointsCustomerPhone
                    : undefined,
                retry,
              };
              const hasUnappliedCartBenefit = reconciledBenefits.hasUnappliedBenefit;
              if (
                mayClaimCheckoutDuringHydration(draftWriterBlocked)
                && retry.phase !== 'recording_payment'
                && retry.phase !== 'finalizing_zero'
                && !hasUnappliedCartBenefit
              ) {
                try {
                  retry = await canonicalizeAndClaim(retry, pendingOrder);
                  if (cancelled) {
                    if (retry.pendingOrderId && retry.checkoutClaimToken) {
                      await pos.releaseCheckout(retry.pendingOrderId, retry.checkoutClaimToken)
                        .catch(() => undefined);
                    }
                    return;
                  }
                  const claimedDraft: PosRetryDraft = {
                    ...reconciledStoredDraft,
                    pendingCartDiscountMinor: undefined,
                    pendingCartPointsMinor: undefined,
                    pendingCartPointsCustomerPhone: undefined,
                    retry,
                  };
                  if (!savePosDraft(activeDraftKey, claimedDraft)) {
                    if (retry.pendingOrderId && retry.checkoutClaimToken) {
                      await pos.releaseCheckout(retry.pendingOrderId, retry.checkoutClaimToken)
                        .catch(() => undefined);
                    }
                    retry = withoutCheckoutClaim({ ...retry, phase: 'preparing_order' });
                    setError(
                      'The bill lock was acquired but could not be saved. It was released; do not collect payment until checkout is resumed.',
                    );
                  }
                } catch (claimError) {
                  if (cancelled) return;
                  // The server has not accepted a payment at this phase. Keep
                  // the order journal, but prevent the restored screen from
                  // asking staff to collect until it owns a fresh lease.
                  retry = withoutCheckoutClaim({
                    ...applyCanonicalCheckoutBalance(retry, pendingOrder),
                    phase: 'preparing_order',
                  });
                  savePosDraft(activeDraftKey, { ...reconciledStoredDraft, retry });
                  setError(
                    `${(claimError as Error).message} No payment should be collected. ` +
                    'Resume this checkout to claim the bill safely.',
                  );
                }
              } else if (hasUnappliedCartBenefit) {
                if (reconciledBenefits.changed) {
                  savePosDraft(activeDraftKey, { ...reconciledStoredDraft, retry });
                }
                setError(
                  'This saved bill still has a discount or points change to finish. It was kept out of the shared payment queue; resume the same checkout before collecting money.',
                );
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
                setUnresolvedResumingOrderId(null);
              } else if (!retry && storedDraft.cart.length === 0) {
                // It was paid/cleared elsewhere and there is no interrupted
                // checkout or local cart to recover, so the ordinary stale
                // resume pointer can go.
                setCart([]);
                savedCartDiscarded = true;
                setLocalWorkShiftId(null);
                setUnresolvedResumingOrderId(null);
                clearPosDraft(activeDraftKey);
              } else if (!retry) {
                // The server order is terminal, but these local cart lines may
                // never have been appended to it. A paid/void status is not
                // evidence that the locally saved products were accounted for.
                setUnresolvedResumingOrderId(resumingOrderId);
                ordinaryDraftAccountabilityError =
                  `Incoming order ${resumingOrderId.slice(0, 8)} is ${resumed.status}, but this browser also holds unsent local items. `
                  + 'Those items were preserved and locked for explicit reconciliation.';
              }
            } catch (e) {
              if (cancelled) return;
              if (retry) {
                setError(`${(e as Error).message} The interrupted checkout remains saved for a safe retry.`);
              } else {
                setUnresolvedResumingOrderId(resumingOrderId);
                ordinaryDraftAccountabilityError =
                  `${(e as Error).message} The saved incoming bill and any local items were preserved but locked. `
                  + 'Retry when online or ask a protected owner to reconcile it.';
              }
            }
          }
          if (!savedCartDiscarded) {
            // Entered on the cart-review screen before any order existed, so
            // the draft is the only record of it anywhere — restoring the cart
            // without it silently rebills the customer at full price. Values
            // are re-checked against the live bill when prepareCheckout()
            // applies them, exactly like a freshly typed one.
            setPendingCartDiscountMinor(restoredPendingDiscountMinor);
            // Points were counted against one specific customer's balance, so
            // they must not come back without that customer attached — the
            // same invariant clearCustomer() enforces.
            setPendingCartPointsMinor(restoredPendingPointsMinor);
            setPendingCartPointsCustomerPhone(
              restoredPendingPointsCustomerPhone ?? null,
            );
          }
          setCheckoutRetry(retry ?? null);
        }
        if (ordinaryDraftAccountabilityError) {
          // Keep the draft's only durable copy and its original shift binding.
          // A null screen shift prevents add/checkout while realtime continues
          // to discover the current server state in the background.
          setShiftId(null);
          setShiftError(ordinaryDraftAccountabilityError);
          setError(ordinaryDraftAccountabilityError);
        } else {
          setShiftId(resolvedShiftId);
        }
        setHydratedDraftKey(activeDraftKey);
      } catch (e) {
        if (!cancelled) {
          const recoveryCanContinue = Boolean(draftKey) && canCompletePosDraftHydrationAfterLoadFailure({
            hasStoredDraft: Boolean(storedDraft),
            hasCheckoutRecovery: Boolean(storedDraft?.retry),
          });
          setShiftError(
            recoveryCanContinue
              ? (e as Error).message
              : `${(e as Error).message} Your saved local bill remains untouched; refresh POS to recover it before taking another bill.`,
          );
          setError(
            storedDraft
              ? `${(e as Error).message} The saved bill is shown below and remains locked until its catalogue and server state can be verified.`
              : (e as Error).message,
          );
          if (draftKey && recoveryCanContinue) {
            // No unsaved ordinary cart can be overwritten in these cases.
            // Realtime may now recover a shift opened after the failed fetch;
            // a checkout journal carries its own immutable cart/shift snapshot.
            setHydratedDraftKey(draftKey);
          }
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [
    draftKey,
    draftLeaseState.key,
    draftLeaseState.status,
    me?.branch_id,
    me?.company_id,
    terminalId,
    terminalReady,
  ]);

  useEffect(() => {
    if (!draftKey) return;
    const onStorage = (event: StorageEvent) => {
      if (event.key !== draftKey || event.newValue === draftStorageTokenRef.current) return;
      setDraftStorageConflict(true);
      setError(
        'This POS draft changed in another browser tab. This tab is now read-only; reload POS and reconcile the saved bill before continuing.',
      );
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [draftKey]);

  const loadHeldOrders = useCallback(async (silent = false) => {
    const requestScope = draftKey;
    if (!silent) { setHeldLoading(true); setHeldError(null); }
    try {
      const result = await orders.list({
        status: ['held'],
        limit: UNBILLED_QUEUE_LIMIT,
      });
      // An `open` counter order is private recovery work owned by the client
      // that created it. Showing it here would let a second cashier collect the
      // same physical payment. Only explicitly shared, claim-protected bills
      // are payable from this queue.
      const queue = result.filter((order) => isIncomingSharedPosOrder(order.status));
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

  // Gross shift collections include itemized POS and separately identified
  // membership payments. They exclude float, refunds and off-shift collections.
  const hasLocalShiftWork = cart.length > 0 || Boolean(
    resumingOrder || unresolvedResumingOrderId || checkoutRetry,
  );
  const accountableShiftId = resolvePosAccountableShiftId({
    checkoutRecoveryShiftId: checkoutRetry?.snapshot.shiftId ?? null,
    localWorkShiftId,
    currentShiftId: shiftId,
  });
  const hasUnavailableDraftItems = cart.some((line) => line.unavailable);
  const localWorkNeedsReconciliation = posDraftNeedsReconciliation({
    storageConflict: draftStorageConflict,
    unresolvedResumingOrderId,
    hasUnavailableItems: hasUnavailableDraftItems,
    hasCheckoutRecovery: Boolean(checkoutRetry?.pendingOrderId),
    hasResumingOrder: Boolean(resumingOrder),
    cartLength: cart.length,
    localWorkShiftId,
    currentShiftId: shiftId,
  });
  const realtimeShiftContextReady = canReconcileRealtimePosShift({
    draftHydrated,
    companyId: me?.company_id ?? null,
    branchId: me?.branch_id ?? null,
    terminalReady,
    terminalId,
  });
  const loadShiftCollections = useCallback(async () => {
    const refresh = beginRealtimeShiftRefresh(shiftRefreshGenerationRef);
    const companyId = me?.company_id;
    const branchId = me?.branch_id;
    if (!companyId || !branchId || !terminalReady || !terminalId) {
      if (refresh.isCurrent()) setShiftCollections(null);
      return;
    }
    try {
      const rows = await shifts.list(true);
      if (!refresh.isCurrent()) return;
      const resolution = resolveRealtimeOpenShift({
        currentShiftId: shiftId,
        hasLocalShiftWork,
        accountableLocalShiftId: accountableShiftId,
        branchId,
        terminalId,
        openShifts: rows,
      });
      if (resolution.kind === 'local_work_conflict') {
        // A cart or checkout journal is accountable to the shift under which
        // it was created. Never move it silently merely because another
        // employee closed that shift and opened a new one on this terminal.
        clearStoredShift();
        setShiftId(null);
        setShiftCollections(null);
        setShiftError(
          'The shift changed on another device. Your unfinished local bill was not moved to the new shift. '
          + 'Finish its saved recovery or clear it before taking a new bill.',
        );
        return;
      }
      if (resolution.kind !== 'ready') {
        clearStoredShift();
        setShiftId(null);
        setShiftCollections(null);
        setShiftError(shiftResolutionMessage(resolution));
        return;
      }

      const nextShiftId = resolution.shift.id;
      storeShiftId({ companyId, branchId, terminalId }, nextShiftId);
      setShiftId(nextShiftId);
      setShiftError(null);
      setShiftCollections({
        posMinor: resolution.shift.pos_sales_minor ?? 0,
        membershipMinor: resolution.shift.membership_sales_minor ?? 0,
        grossMinor: resolution.shift.total_sales_minor ?? 0,
      });
    } catch {
      // A transient network failure is not proof that the accountable shift
      // closed. Keep the last verified state; connectivity UI owns the retry
      // message and the backend still validates every financial mutation.
    }
  }, [
    hasLocalShiftWork,
    accountableShiftId,
    me?.branch_id,
    me?.company_id,
    shiftId,
    terminalId,
    terminalReady,
  ]);
  useEffect(() => {
    if (draftHydrated && !hasLocalShiftWork && localWorkShiftId !== null) {
      setLocalWorkShiftId(null);
    }
  }, [draftHydrated, hasLocalShiftWork, localWorkShiftId]);
  useEffect(() => {
    // Do not race a realtime shift event against the asynchronous durable-draft
    // restore above. A saved payment recovery is accountable to its original
    // shift and must be mounted before this effect may compare shift identities.
    if (!realtimeShiftContextReady) {
      invalidateRealtimeShiftRefresh(shiftRefreshGenerationRef);
      setShiftCollections(null);
      return;
    }
    loadShiftCollections();
    const unsubscribe = subscribeRealtime('shifts', loadShiftCollections);
    const id = setInterval(loadShiftCollections, HELD_ORDERS_POLL_MS);
    return () => {
      unsubscribe();
      clearInterval(id);
      invalidateRealtimeShiftRefresh(shiftRefreshGenerationRef);
    };
  }, [loadShiftCollections, realtimeShiftContextReady]);

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
      notifyAlarm(
        '⏰ Incoming orders waiting',
        'One or more sent or recovered orders have been unbilled for a while. Bill or void them.',
        'dcompany-held-orders',
      );
    }, 1000);
    return () => clearInterval(id);
  }, [heldOrders, heldAlarmMuted]);

  async function resumeOrder(row: OrderListItemDTO) {
    if (localWorkNeedsReconciliation) {
      setHeldError(
        'This POS tab has a locked saved bill. Reload and reconcile it before opening another incoming order.',
      );
      return;
    }
    const resumeShiftId = shiftIdRef.current;
    if (!resumeShiftId) {
      setHeldError(shiftError || 'No validated shift is open. Open or refresh the shift before selecting an incoming bill.');
      return;
    }
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
    if (!enterSynchronousPosFlow(checkoutFlowInFlightRef)) {
      setHeldError(
        'Another bill is already loading or being prepared. Wait for it to finish; this order was not selected.',
      );
      return;
    }
    setShowHeldPicker(false);
    setError(null);
    try {
      const full = await orders.get(row.id);
      if (shiftIdRef.current !== resumeShiftId) {
        setError(
          'The open shift changed while this bill was loading. Nothing was selected; review the shift and try again.',
        );
        return;
      }
      if (full.status !== 'open' && full.status !== 'held') {
        setHeldError(
          `This incoming bill is already ${full.status}. The queue will refresh; no local draft was opened.`,
        );
        void loadHeldOrders(true);
        return;
      }
      const nextCustomerName = full.customer_name ?? '';
      const nextCustomerPhone = full.customer_phone ?? '';
      if (!draftKey || !savePosDraft(draftKey, {
        version: 2,
        shiftId: resumeShiftId,
        resumingOrderId: full.id,
        cart: [],
        orderType,
        deliveryVia,
        deliveryStateCode,
        customerName: nextCustomerName,
        customerPhone: nextCustomerPhone,
      })) {
        setError(
          'The incoming bill could not be checkpointed safely. It was not opened; restore POS recovery storage and try again.',
        );
        return;
      }
      setResumingOrder(full);
      setUnresolvedResumingOrderId(null);
      setLocalWorkShiftId((current) => bindPosLocalWorkShift(current, resumeShiftId));
      setCart([]);
      setCheckoutRetry(null);
      setPendingCartDiscountMinor(0);
      setPendingCartPointsMinor(0);
      setPendingCartPointsCustomerPhone(null);
      setCustomerName(nextCustomerName);
      customerLookupGenerationRef.current += 1;
      customerPhoneRef.current = nextCustomerPhone;
      setCustomerPhone(nextCustomerPhone);
      setCustomer(null);
      setSubscription(null);
      setMembershipTier(null);
      setCustomerLookupState('idle');
      setCustomerMessage(nextCustomerPhone
        ? 'Customer loaded from this order. Use Find to refresh the customer profile before billing.'
        : null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      leaveSynchronousPosFlow(checkoutFlowInFlightRef);
    }
  }

  async function voidOrder(row: OrderListItemDTO, reason: string) {
    if (localWorkNeedsReconciliation) {
      setHeldError('This POS tab is read-only until its saved bill is reconciled. No queued order was voided.');
      return;
    }
    if (!enterSynchronousPosFlow(checkoutFlowInFlightRef)) {
      setHeldError('Another bill is being processed. Wait for it to finish; this order was not voided.');
      return;
    }
    setHeldError(null);
    setVoidingId(row.id);
    try {
      await pos.voidOrder(row.id, reason);
      await loadHeldOrders(true);
      const feedback = queuedOrderVoidedFeedback(row.source_label);
      notifications.success(feedback.message, { title: feedback.title });
    } catch (e) { setHeldError((e as Error).message); }
    finally {
      setVoidingId(null);
      leaveSynchronousPosFlow(checkoutFlowInFlightRef);
    }
  }
  function cancelResume() {
    if (checkoutFlowInFlightRef.current) {
      setError('Another bill action is still running. Wait for it to finish before cancelling this selection.');
      return;
    }
    if (checkoutRetry) {
      if (canAbandonCheckoutRetry(checkoutRetry)) {
        startAbandonPreparedCheckout();
      } else {
        setError(
          'This payment result may be unresolved. Resume the same checkout or ask a protected owner to reconcile it; it cannot be cleared here.',
        );
      }
      return;
    }
    if (!draftKey || !clearPosDraft(draftKey)) return;
    setResumingOrder(null);
    setUnresolvedResumingOrderId(null);
    setCart([]);
    setCheckoutRetry(null);
    setLocalWorkShiftId(null);
    setPendingCartDiscountMinor(0);
    setPendingCartPointsMinor(0);
    clearCustomer();
    notifications.info('The selected incoming bill was released without collecting payment.', {
      title: 'Selection cancelled',
    });
  }

  useEffect(() => {
    let cancelled = false;
    setReceiptSettingsError(null);
    pos.receiptBusiness()
      .then((receiptIdentity) => {
        if (cancelled) return;
        const details = buildReceiptBusinessDetails(receiptIdentity, me?.name);
        setReceiptBusiness(details);
        setReceiptSettingsError(receiptConfigurationIssue(details));
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setReceiptBusiness(null);
        const message = (error as Error).message?.trim();
        setReceiptSettingsError(
          message && message !== 'Network Error'
            ? message
            : 'Receipt settings could not be loaded. Refresh before charging an order.',
        );
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
    if (checkoutFlowInFlightRef.current) {
      setError('The current bill is being prepared. This item was not added; wait for the result.');
      return;
    }
    if (checkoutRetry) {
      setError('This bill is already prepared. Complete or safely cancel that checkout before changing items.');
      return;
    }
    if (resumingOrder) {
      setError(
        'Items are locked after Send to POS. Change only the customer or authorised adjustments here; edit items in the source workflow before sending the bill.',
      );
      return;
    }
    if (localWorkNeedsReconciliation) {
      setError(
        'This saved bill is locked to its original shift. Review it, then explicitly discard and rebuild it after reconciliation.',
      );
      return;
    }
    if (!shiftId) {
      setError(shiftError || 'No validated shift is available. Open or refresh the shift before adding an item.');
      return;
    }
    const existing = cart.find((line) => line.item.id === item.id);
    const next = existing
      ? cart.map((line) => line.item.id === item.id ? { ...line, qty: line.qty + 1 } : line)
      : [...cart, { item, qty: 1 }];
    if (!draftKey || !savePosDraft(draftKey, buildPosDraft(null, undefined, next))) {
      setError('The item was not added because the POS recovery draft could not be saved safely.');
      return;
    }
    setLocalWorkShiftId((current) => bindPosLocalWorkShift(current, shiftId));
    setCart(next);
  }
  function adjust(id: string, delta: number) {
    if (checkoutFlowInFlightRef.current) {
      setError('The current bill is being prepared. Its quantity was not changed; wait for the result.');
      return;
    }
    if (checkoutRetry || resumingOrder) {
      setError(
        checkoutRetry
          ? 'This bill is already prepared. Complete or safely cancel that checkout before changing items.'
          : 'Items are locked after Send to POS. Edit them in the source workflow before sending the bill again.',
      );
      return;
    }
    if (localWorkNeedsReconciliation) {
      setError(
        'This saved bill is locked to its original shift. Its quantities were not changed. '
        + 'Review it, then explicitly discard and rebuild it after reconciliation.',
      );
      return;
    }
    const next = adjustPosCart(cart, id, delta);
    if (next.length === 0 && cart.length > 0) {
      // This explicit final-line removal is the cart's deletion commit. Clear
      // the durable copy first; otherwise a reload resurrects items that staff
      // deliberately removed. On a CAS/storage failure the visible cart stays.
      if (!draftKey || !clearPosDraft(draftKey)) return;
      setCart([]);
      setLocalWorkShiftId(null);
      setPendingCartDiscountMinor(0);
      setPendingCartPointsMinor(0);
      clearCustomer();
      return;
    }
    if (!draftKey || !savePosDraft(draftKey, buildPosDraft(null, undefined, next))) {
      setError('The quantity was not changed because the POS recovery draft could not be saved safely.');
      return;
    }
    setCart(next);
  }

  function clearLocalCart() {
    if (checkoutFlowInFlightRef.current) {
      setShowCartClearConfirm(false);
      setError('The current bill is being prepared. It was not cleared; wait for the result.');
      return;
    }
    // This is deliberately the only destructive path for an unsent local
    // cart. Persist the operator's explicit decision immediately so a reload
    // cannot resurrect or half-clear the bill.
    if (checkoutRetry) {
      setShowCartClearConfirm(false);
      startAbandonPreparedCheckout();
      return;
    }
    if (!draftKey || !clearPosDraft(draftKey)) {
      setShowCartClearConfirm(false);
      return;
    }
    setShowCartClearConfirm(false);
    setCart([]);
    setResumingOrder(null);
    setUnresolvedResumingOrderId(null);
    setLocalWorkShiftId(null);
    setPendingCartDiscountMinor(0);
    setPendingCartPointsMinor(0);
    clearCustomer();
    notifications.success(POS_CART_CLEARED_FEEDBACK.message, {
      title: POS_CART_CLEARED_FEEDBACK.title,
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
    // Keep the exact serialized evidence byte-for-byte while ownership,
    // catalogue, shift, or server-order recovery is unresolved. Normalizing
    // it here could erase data that exists nowhere else.
    if (localWorkNeedsReconciliation) return;
    const key = draftKey;
    if (!cart.length && !resumingOrder && !checkoutRetry) {
      return;
    }
    savePosDraft(key, {
      version: 2,
      shiftId: accountableShiftId ?? undefined,
      resumingOrderId: resumingOrder?.id ?? unresolvedResumingOrderId ?? undefined,
      cart: cart.map((l) => ({ itemId: l.item.id, qty: l.qty })),
      orderType, deliveryVia, deliveryStateCode, customerName, customerPhone,
      ...(pendingCartDiscountMinor ? { pendingCartDiscountMinor } : {}),
      ...(pendingCartPointsMinor ? { pendingCartPointsMinor } : {}),
      ...(pendingCartPointsMinor && pendingCartPointsCustomerPhone
        ? { pendingCartPointsCustomerPhone }
        : {}),
      retry: checkoutRetry ?? undefined,
    });
  }, [
    draftKey, draftHydrated, cart, resumingOrder, unresolvedResumingOrderId,
    localWorkNeedsReconciliation, orderType, deliveryVia,
    deliveryStateCode, customerName, customerPhone, checkoutRetry, accountableShiftId,
    pendingCartDiscountMinor, pendingCartPointsMinor, pendingCartPointsCustomerPhone,
  ]);

  // A caller that has just consumed or dropped the cart-stage benefits must say
  // so explicitly. setPendingCart*Minor(0) does not update the value captured by
  // this render's closure, so without an override the synchronous save below
  // would write the stale amount straight back into the draft and a reload
  // would resurrect a discount that is already on the bill — or one the server
  // has just refused.
  interface PendingCartBenefits {
    discountMinor: number;
    pointsMinor: number;
    pointsCustomerPhone?: string | null;
  }

  function buildPosDraft(
    retry: PosCheckoutRetry | null,
    benefits?: PendingCartBenefits,
    cartOverride?: readonly CartLine[],
  ): PosRetryDraft {
    const discountMinor = benefits ? benefits.discountMinor : pendingCartDiscountMinor;
    const pointsMinor = benefits ? benefits.pointsMinor : pendingCartPointsMinor;
    const pointsCustomerPhone = benefits?.pointsCustomerPhone
      ?? pendingCartPointsCustomerPhone;
    const draftCart = cartOverride ?? cart;
    return {
      version: 2,
      shiftId: resolvePosAccountableShiftId({
        checkoutRecoveryShiftId: retry?.snapshot.shiftId ?? null,
        localWorkShiftId,
        currentShiftId: shiftId,
      }) ?? undefined,
      resumingOrderId: resumingOrder?.id ?? unresolvedResumingOrderId ?? undefined,
      cart: draftCart.map((line) => ({ itemId: line.item.id, qty: line.qty })),
      orderType,
      deliveryVia,
      deliveryStateCode,
      customerName,
      customerPhone,
      // Written straight through from state: this synchronous save runs before
      // the next API call, so a cart-stage discount survives even a crash
      // between entering it and the order being created.
      ...(discountMinor ? { pendingCartDiscountMinor: discountMinor } : {}),
      ...(pointsMinor ? { pendingCartPointsMinor: pointsMinor } : {}),
      ...(pointsMinor && pointsCustomerPhone
        ? { pendingCartPointsCustomerPhone: pointsCustomerPhone }
        : {}),
      retry: retry ?? undefined,
    };
  }

  function persistCheckoutRetry(
    retry: PosCheckoutRetry | null,
    benefits?: PendingCartBenefits,
  ): boolean {
    if (!draftKey) {
      if (retry === null) setCheckoutRetry(null);
      return retry === null;
    }
    const draft = buildPosDraft(retry, benefits);
    const persisted = !draft.cart.length && !draft.resumingOrderId && !retry
      ? clearPosDraft(draftKey)
      : savePosDraft(draftKey, draft);
    if (persisted) {
      setCheckoutRetry(retry);
    }
    if (retry) {
      // This synchronous write must happen before the next API request. React
      // effects alone are too late if the page refreshes while a request is in flight.
      return persisted;
    }
    return persisted;
  }

  /**
   * Acquire the exclusive checkout lease only after all server-side edits.
   * Discounts, points, rewards and lines bump the bill version, so each such
   * mutation must be followed by a fresh claim before staff collect money.
   */
  async function canonicalizeAndClaim(
    retry: PosCheckoutRetry,
    order: OrderDTO,
  ): Promise<PosCheckoutRetry> {
    if (order.status !== 'open' && order.status !== 'held') {
      throw new Error(`Order is ${order.status} and cannot be prepared for payment.`);
    }
    let canonicalOrder = order;
    let claim: Awaited<ReturnType<typeof pos.claimCheckout>> | null = null;
    try {
      // A new web counter bill stays private while its lines, customer and
      // benefits are edited. This one atomic server operation publishes the
      // final snapshot as held and grants its lease; another cashier can never
      // collect an undiscounted intermediate total.
      claim = order.status === 'open'
        ? await pos.publishCheckout(
          order.id,
          order.checkout_version,
          `publish-checkout:${retry.key}`,
        )
        : await pos.claimCheckout(order.id);
      if (claim.order_id !== canonicalOrder.id) {
        throw new Error('The checkout lock was issued for a different order. Reload the POS queue.');
      }
      // The OrderDTO may have been loaded just before another terminal changed
      // the bill. A claim carries the authoritative checkout version; if it is
      // newer, reload the complete bill under this same lease (benefits and
      // customer metadata included). Never rotate a second token or display a
      // hybrid of stale metadata and current totals.
      if (claim.order_version !== canonicalOrder.checkout_version) {
        canonicalOrder = await pos.getOrder(order.id);
        if (canonicalOrder.status !== 'held') {
          throw new Error('This bill left the unpaid POS queue while checkout was opening. Refresh POS.');
        }
      }
      if (
        claim.order_id !== canonicalOrder.id
        || claim.order_version !== canonicalOrder.checkout_version
        || claim.order_total_minor !== canonicalOrder.total_minor
        || claim.due_minor !== canonicalOrder.due_minor
      ) {
        throw new Error('This shared bill is changing on another device. Do not collect money; wait a moment and refresh it.');
      }
      const canonical = applyCanonicalCheckoutBalance(retry, canonicalOrder);
      return {
        ...canonical,
        orderTotalMinor: claim.order_total_minor,
        paymentAmountMinor: claim.due_minor,
        checkoutClaimRequired: true,
        checkoutClaimToken: claim.claim_token,
        checkoutClaimExpiresAt: claim.expires_at,
        checkoutClaimOrderVersion: claim.order_version,
      };
    } catch (error) {
      // A response that cannot be durably tied to the displayed bill must not
      // leave an invisible ten-minute lock. Release is best-effort; expiry is
      // still the safe fallback when the network itself is unavailable.
      if (claim?.claim_token) {
        await pos.releaseCheckout(order.id, claim.claim_token).catch(() => undefined);
      }
      throw error;
    }
  }

  function withoutCheckoutClaim(retry: PosCheckoutRetry): PosCheckoutRetry {
    return {
      ...retry,
      checkoutClaimToken: undefined,
      checkoutClaimExpiresAt: undefined,
      checkoutClaimOrderVersion: undefined,
    };
  }

  async function releaseClaimBeforeCheckoutMutation(
    retry: PosCheckoutRetry,
  ): Promise<PosCheckoutRetry> {
    if (!retry.checkoutClaimRequired || !retry.checkoutClaimToken || !retry.pendingOrderId) {
      return retry;
    }
    const unlocked = withoutCheckoutClaim({ ...retry, phase: 'preparing_order' });
    // Recovery must stop claiming ownership before the server lease is
    // released. If the tab dies after this write, another cashier can safely
    // take over after release/expiry without this browser resurrecting a token.
    if (!persistCheckoutRetry(unlocked)) {
      throw new Error(
        'Checkout recovery storage could not be updated, so the bill lock was kept and nothing changed.',
      );
    }
    try {
      await pos.releaseCheckout(retry.pendingOrderId, retry.checkoutClaimToken);
    } catch (error) {
      throw new Error(
        `${(error as Error).message} The bill may still be locked; no discount or benefit was changed. Wait briefly and retry.`,
      );
    }
    return unlocked;
  }

  async function persistFreshCheckoutClaim(
    retry: PosCheckoutRetry,
    failureMessage: string,
  ): Promise<void> {
    if (persistCheckoutRetry(retry)) return;
    if (retry.pendingOrderId && retry.checkoutClaimToken) {
      await pos.releaseCheckout(retry.pendingOrderId, retry.checkoutClaimToken)
        .catch(() => undefined);
    }
    throw new Error(failureMessage);
  }

  function finishCheckout(paidOrder: OrderDTO) {
    setReceipt(paidOrder);
    setCheckoutRetry(null);
    setShowPay(false);
    setCart([]);
    setResumingOrder(null);
    setUnresolvedResumingOrderId(null);
    setLocalWorkShiftId(null);
    setPendingCartDiscountMinor(0);
    setPendingCartPointsMinor(0);
    clearCustomer();
    if (draftKey) clearPosDraft(draftKey);
    void loadHeldOrders(true);
    void loadShiftCollections();
  }

  async function lookupCustomer() {
    if (checkoutMutationInFlightRef.current) {
      setCustomerMessage('Wait for the current bill adjustment to finish before changing the customer.');
      return;
    }
    const phone = customerPhone.trim();
    const generation = ++customerLookupGenerationRef.current;
    customerPhoneRef.current = customerPhone;
    const isCurrentLookup = () => isCurrentPosCustomerLookup({
      requestGeneration: generation,
      currentGeneration: customerLookupGenerationRef.current,
      requestedPhone: phone,
      currentPhone: customerPhoneRef.current,
    });
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
      if (!isCurrentLookup()) return;
      if (!found) {
        setCustomerLookupState('new');
        setCustomerMessage('New customer. The sale will create the profile.');
        return;
      }
      setCustomer(found);
      if (!customerName.trim() && found.name) setCustomerName(found.name);
      if (!MEMBERSHIP_UI_ENABLED) {
        setCustomerLookupState('found');
        setCustomerMessage(`${found.name || found.phone} found.`);
        return;
      }
      const sub = await memberships.getCustomerSubscription(found.id);
      if (!isCurrentLookup()) return;
      setSubscription(sub);
      if (sub) {
        const tiers = await memberships.listTiers();
        if (!isCurrentLookup()) return;
        setMembershipTier(tiers.find((tier) => tier.id === sub.tier_id) ?? null);
        setCustomerLookupState('found');
        setCustomerMessage(`${sub.tier_name} membership active. Discounts apply automatically.`);
      } else {
        setCustomerLookupState('found');
        setCustomerMessage(`${found.name || found.phone} found. No active membership.`);
      }
    } catch (e) {
      if (!isCurrentLookup()) return;
      setCustomerLookupState('error');
      setCustomerMessage((e as Error).message);
    } finally {
      if (isCurrentLookup()) setCustomerBusy(false);
    }
  }

  function changeCustomerPhone(value: string) {
    if (checkoutMutationInFlightRef.current || checkoutRetry) {
      setCustomerMessage('Finish or cancel the prepared checkout before changing the customer.');
      return;
    }
    const normalized = value.trim();
    if (
      pendingCartPointsMinor > 0
      && pendingCartPointsCustomerPhone !== normalized
    ) {
      const cleared = buildPosDraft(null, {
        discountMinor: pendingCartDiscountMinor,
        pointsMinor: 0,
        pointsCustomerPhone: null,
      });
      cleared.customerPhone = value;
      if (!draftKey || !savePosDraft(draftKey, cleared)) {
        setCustomerMessage(
          'The customer was not changed because the saved points redemption could not be cleared safely.',
        );
        return;
      }
      setPendingCartPointsMinor(0);
      setPendingCartPointsCustomerPhone(null);
      setPointsInput('');
    }
    customerLookupGenerationRef.current += 1;
    customerPhoneRef.current = value;
    setCustomerBusy(false);
    setCustomerPhone(value);
    setCustomer(null);
    setSubscription(null);
    setMembershipTier(null);
    setCustomerLookupState('idle');
    setCustomerMessage(null);
  }

  function clearCustomerFromForm() {
    if (checkoutMutationInFlightRef.current || checkoutRetry) {
      setCustomerMessage('Finish or cancel the prepared checkout before clearing the customer.');
      return;
    }
    if (pendingCartPointsMinor > 0) {
      const cleared = buildPosDraft(null, {
        discountMinor: pendingCartDiscountMinor,
        pointsMinor: 0,
        pointsCustomerPhone: null,
      });
      cleared.customerPhone = '';
      cleared.customerName = '';
      if (!draftKey || !savePosDraft(draftKey, cleared)) {
        setCustomerMessage(
          'The customer was not cleared because the saved points redemption could not be cleared safely.',
        );
        return;
      }
    }
    clearCustomer();
  }

  function clearCustomer() {
    customerLookupGenerationRef.current += 1;
    customerPhoneRef.current = '';
    setCustomerBusy(false);
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
    setPendingCartPointsCustomerPhone(null);
  }

  async function runCheckoutFlow(
    operation: () => Promise<void>,
    busyMessage: string,
  ): Promise<void> {
    if (!enterSynchronousPosFlow(checkoutFlowInFlightRef)) {
      setError(busyMessage);
      return;
    }
    try {
      await operation();
    } finally {
      leaveSynchronousPosFlow(checkoutFlowInFlightRef);
    }
  }

  async function prepareCheckout(method: PayMethod) {
    await runCheckoutFlow(
      () => prepareCheckoutInternal(method),
      'A bill is already being prepared. Wait for it to finish; no second bill was created.',
    );
  }

  async function prepareCheckoutInternal(method: PayMethod) {
    if (checkoutMutationInFlightRef.current) {
      setError('Wait for the current discount or benefit adjustment to finish before continuing to payment.');
      return;
    }
    if (localWorkNeedsReconciliation) {
      setError(
        'This saved bill is locked for reconciliation. No bill or payment was sent. Reload POS or explicitly discard it after review.',
      );
      return;
    }
    if (pendingCartDiscountMinor > 0 && !canManualDiscount) {
      const cleared = persistCheckoutRetry(checkoutRetry, {
        discountMinor: 0,
        pointsMinor: pendingCartPointsMinor,
        pointsCustomerPhone: pendingCartPointsCustomerPhone,
      });
      if (!cleared) {
        setError(
          'This account cannot apply manual discounts, and the saved discount could not be cleared safely. No bill was sent; ask a protected owner to reconcile the saved draft.',
        );
        return;
      }
      setPendingCartDiscountMinor(0);
      setDiscountInput('');
      setError(
        'This account is not authorised for manual discounts. The saved discount was removed safely; review the total, then prepare payment again.',
      );
      return;
    }
    if ((!shiftId && !checkoutRetry) || !receiptBusiness || receiptSettingsError) {
      if (receiptSettingsError) setError(receiptSettingsError);
      else if (!shiftId && !checkoutRetry) setError(shiftError || 'No validated shift is available. Open or refresh the shift before continuing.');
      else if (!receiptBusiness) setError('Receipt configuration is still loading. Wait a moment and try again.');
      return;
    }
    if (!checkoutRetry && !resumingOrder && !cart.length) return;
    if (
      !checkoutRetry
      && localWorkShiftId
      && localWorkShiftId !== shiftId
    ) {
      setError(
        'This local bill belongs to a shift that is no longer open. It was not moved to the current shift. '
        + 'Clear it and rebuild only after confirming the previous shift was closed correctly.',
      );
      return;
    }
    let retry: PosCheckoutRetry = checkoutRetry ?? {
      key: createOperationKey(),
      phase: 'preparing_order',
      paymentMethod: method,
      resumingOrderId: resumingOrder?.id,
      snapshot: {
        shiftId: resolvePosAccountableShiftId({
          checkoutRecoveryShiftId: null,
          localWorkShiftId,
          currentShiftId: shiftId,
        })!,
        cart: cart.map((line) => ({ itemId: line.item.id, qty: line.qty })),
        orderType,
        deliveryVia,
        deliveryStateCode,
        customerName,
        customerPhone,
      },
    };
    if (retry.phase === 'recording_payment' || retry.phase === 'finalizing_zero') {
      await completeCheckoutInternal();
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
    let cartBenefitInFlight: 'discount' | 'points' | null = null;
    // React state is intentionally not the authority inside this asynchronous
    // transaction. Each benefit is durably retired as soon as the server has
    // confirmed it, before the next await can fail or another device can
    // change the now-unclaimed bill.
    let remainingDiscountMinor = pendingCartDiscountMinor;
    let remainingPointsMinor = pendingCartPointsMinor;
    let remainingPointsCustomerPhone = pendingCartPointsCustomerPhone;
    try {
      let order: OrderDTO;
      if (retry.pendingOrderId) {
        order = await pos.getOrder(retry.pendingOrderId);
        if (order.status === 'paid' && order.invoice_no && order.invoice_issued_at) {
          finishCheckout(order);
          return;
        }
      } else {
        const checkoutSource = profilePosCheckoutSource(
          retry.resumingOrderId,
          retry.snapshot.orderType,
        );
        if (checkoutSource.kind === 'incoming') {
          order = await pos.getOrder(checkoutSource.orderId);
          if (retry.snapshot.cart.length) {
            throw new Error(
              'This sent bill has locked items. Clear the locally added items and edit the original source bill before sending it to POS again.',
            );
          }
          const desiredCustomerName = retry.snapshot.customerName.trim();
          const desiredCustomerPhone = retry.snapshot.customerPhone.trim();
          if (
            (order.customer_name ?? '') !== desiredCustomerName
            || (order.customer_phone ?? '') !== desiredCustomerPhone
          ) {
            order = await pos.attachCustomer(
              checkoutSource.orderId,
              {
                customer_name: desiredCustomerName || undefined,
                customer_phone: desiredCustomerPhone || undefined,
              },
              `order-customer:${retry.key}`,
              order.checkout_version,
            );
          }
        } else {
          order = await pos.createOrder(
            {
              type: checkoutSource.orderType,
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
        const draftCleared = draftKey ? clearPosDraft(draftKey) : false;
        if (!mayReleaseCancelledPreparedBill(Boolean(draftKey), draftCleared)) {
          const message =
            'This bill is already cancelled on the server, but this browser could not clear its saved recovery copy. '
            + 'This POS tab remains locked: close any other POS tab, reload this one, and confirm the bill shows Cancelled. '
            + 'Do not recreate or collect payment for it.';
          setError(message);
          notifications.error(message, { title: 'Cancelled bill needs local reconciliation' });
          void loadHeldOrders(true);
          return;
        }
        setCheckoutRetry(null);
        setCart([]);
        setResumingOrder(null);
        setUnresolvedResumingOrderId(null);
        if (draftKey) clearPosDraft(draftKey);
        setError('This order was voided and cannot be charged.');
        return;
      }
      if (order.status === 'refunded') {
        setCheckoutRetry(null);
        setCart([]);
        setResumingOrder(null);
        setUnresolvedResumingOrderId(null);
        setReceipt(order);
        if (draftKey) clearPosDraft(draftKey);
        setError('This order was already refunded; no new payment was recorded.');
        return;
      }
      if (order.status !== 'open' && order.status !== 'held') {
        throw new Error(`Order is ${order.status} and cannot be prepared for payment.`);
      }
      if (
        (remainingDiscountMinor > 0 || remainingPointsMinor > 0)
        && retry.checkoutClaimToken
      ) {
        retry = await releaseClaimBeforeCheckoutMutation(retry);
        // Re-read after releasing: another till may legitimately acquire or
        // alter the held bill at this boundary, so no stale optimistic version
        // is ever used for the pending benefit mutation.
        order = await pos.getOrder(order.id);
        if (order.status !== 'open' && order.status !== 'held') {
          throw new Error(`Order is ${order.status} and cannot be adjusted for payment.`);
        }
      }
      // A discount entered on the cart-review screen, before this order
      // existed — apply it now so the confirm-payment screen already shows
      // the discounted total instead of asking the cashier to re-enter it.
      if (remainingDiscountMinor > 0) {
        if (!isCartStageDiscountAlreadyApplied(
          order.manual_discount_minor,
          remainingDiscountMinor,
        )) {
          const expectedVersion = retry.cartDiscountExpectedVersion
            ?? order.checkout_version;
          if (retry.cartDiscountExpectedVersion === undefined) {
            retry = { ...retry, cartDiscountExpectedVersion: expectedVersion };
            if (!persistCheckoutRetry(retry)) {
              throw new Error(
                'The discount retry version could not be saved, so the discount was not sent. Restore POS recovery storage and resume this bill.',
              );
            }
          }
          cartBenefitInFlight = 'discount';
          order = await pos.applyDiscount(
            order.id,
            remainingDiscountMinor,
            `cart-discount:${retry.key}`,
            expectedVersion,
          );
          cartBenefitInFlight = null;
        }
        const discountCheckpoint = retireAppliedCartStageBenefit(
          retry,
          'discount',
          remainingDiscountMinor,
          remainingPointsMinor,
          remainingPointsCustomerPhone ?? undefined,
        );
        retry = discountCheckpoint.retry;
        remainingDiscountMinor = discountCheckpoint.pendingDiscountMinor;
        remainingPointsMinor = discountCheckpoint.pendingPointsMinor;
        remainingPointsCustomerPhone =
          discountCheckpoint.pendingPointsCustomerPhone ?? null;
        setPendingCartDiscountMinor(0);
        if (!persistCheckoutRetry(retry, {
          discountMinor: remainingDiscountMinor,
          pointsMinor: remainingPointsMinor,
          pointsCustomerPhone: remainingPointsCustomerPhone,
        })) {
          throw new Error(
            'The applied discount could not be checkpointed locally. Do not continue to payment; restore POS recovery storage and resume this exact bill.',
          );
        }
      }
      // Same idea as the discount above, but for points redeemed before this
      // order existed — requires a customer to already be attached.
      if (remainingPointsMinor > 0) {
        if (
          !remainingPointsCustomerPhone
          || remainingPointsCustomerPhone !== retry.snapshot.customerPhone.trim()
        ) {
          throw new Error(
            'The saved points redemption belongs to a different customer. It was not applied; review the customer and points before payment.',
          );
        }
        if (!isCartStagePointsAlreadyApplied(
          order.points_redeemed_minor,
          remainingPointsMinor,
        )) {
          const expectedVersion = retry.cartPointsExpectedVersion
            ?? order.checkout_version;
          if (retry.cartPointsExpectedVersion === undefined) {
            retry = { ...retry, cartPointsExpectedVersion: expectedVersion };
            if (!persistCheckoutRetry(retry)) {
              throw new Error(
                'The points retry version could not be saved, so points were not sent. Restore POS recovery storage and resume this bill.',
              );
            }
          }
          cartBenefitInFlight = 'points';
          order = await pos.redeemPoints(
            order.id,
            remainingPointsMinor / 10,
            `cart-points:${retry.key}`,
            expectedVersion,
          );
          cartBenefitInFlight = null;
        }
        const pointsCheckpoint = retireAppliedCartStageBenefit(
          retry,
          'points',
          remainingDiscountMinor,
          remainingPointsMinor,
          remainingPointsCustomerPhone ?? undefined,
        );
        retry = pointsCheckpoint.retry;
        remainingDiscountMinor = pointsCheckpoint.pendingDiscountMinor;
        remainingPointsMinor = pointsCheckpoint.pendingPointsMinor;
        remainingPointsCustomerPhone =
          pointsCheckpoint.pendingPointsCustomerPhone ?? null;
        setPendingCartPointsMinor(0);
        setPendingCartPointsCustomerPhone(null);
        if (!persistCheckoutRetry(retry, {
          discountMinor: remainingDiscountMinor,
          pointsMinor: remainingPointsMinor,
          pointsCustomerPhone: remainingPointsCustomerPhone,
        })) {
          throw new Error(
            'The applied points redemption could not be checkpointed locally. Do not continue to payment; restore POS recovery storage and resume this exact bill.',
          );
        }
      }
      retry = await canonicalizeAndClaim(retry, order);
      if (order.due_minor <= 0 && !hasBenefitCoveredZeroBalance(retry)) {
        throw new Error('The server reports no amount due, but no final invoice is available. Ask a protected owner to reconcile this order.');
      }
      // Both cart-stage benefits are now either untouched-at-zero or applied to
      // the order above, so the draft must record none pending. Persisting the
      // pre-apply closure values instead would restore them on reload and show
      // the discount subtracted twice.
      if (!persistCheckoutRetry(retry, { discountMinor: 0, pointsMinor: 0 })) {
        if (retry.pendingOrderId && retry.checkoutClaimToken) {
          await pos.releaseCheckout(retry.pendingOrderId, retry.checkoutClaimToken)
            .catch(() => undefined);
        }
        setError(
          'The exact server bill was prepared, but recovery storage failed. Keep this page open and restore browser storage before collecting payment.',
        );
        return;
      }
      setError(null);
    } catch (e) {
      // A deterministic refusal happened before the relevant mutation wrote.
      // Clear only the exact cart-stage adjustment that was being submitted;
      // an unrelated valid points/discount instruction must remain attached.
      const errorCode = (e as Error & { code?: string }).code;
      const clearPendingDiscount = cartBenefitInFlight === 'discount'
        && (isBusinessRuleApiError(e) || errorCode === 'forbidden');
      const clearPendingPoints = cartBenefitInFlight === 'points'
        && isBusinessRuleApiError(e);
      const clearedCartBenefits = clearPendingDiscount || clearPendingPoints;
      if (clearPendingDiscount) {
        retry = { ...retry, cartDiscountExpectedVersion: undefined };
        remainingDiscountMinor = 0;
        setPendingCartDiscountMinor(0);
        setDiscountInput('');
      }
      if (clearPendingPoints) {
        retry = { ...retry, cartPointsExpectedVersion: undefined };
        remainingPointsMinor = 0;
        remainingPointsCustomerPhone = null;
        setPendingCartPointsMinor(0);
        setPendingCartPointsCustomerPhone(null);
        setPointsInput('');
      }
      // Deliberately not worded as "refused": the same clearing runs when the
      // discount applied cleanly and a later step (the points redemption) was
      // the one refused. Telling the cashier to check the bill's own total is
      // true in both cases; telling them it was rejected would not be.
      const clearedNote = clearedCartBenefits
        ? clearPendingDiscount
          ? errorCode === 'forbidden'
            ? ' This account no longer has manual-discount access. The pending discount was cleared safely; review the bill before resuming.'
            : ' The rejected cart discount was cleared; review the prepared bill before entering another amount.'
          : ' The rejected points redemption was cleared; review the customer and prepared bill before trying again.'
        : '';
      // Same stale-closure hazard as the success path: the setState above has
      // not landed yet, so the cleared values must be passed explicitly or the
      // refused amount is written back and re-armed on the next reload.
      const checkpointBenefits = {
        discountMinor: remainingDiscountMinor,
        pointsMinor: remainingPointsMinor,
        pointsCustomerPhone: remainingPointsCustomerPhone,
      };
      if (shouldPreserveCheckoutRetry(e, retry)) {
        if (!persistCheckoutRetry(retry, checkpointBenefits)) {
          setError(
            'The interrupted checkout could not be checkpointed. Keep this tab open, do not collect money, and reconcile its saved bill before continuing.',
          );
          return;
        }
        const recoveryHint = isAmbiguousApiError(e)
          ? 'The server result is unknown. Resume the same preparation key; do not start another bill.'
          : 'The prepared order remains saved and must be reconciled.';
        setError(`${(e as Error).message} ${recoveryHint}${clearedNote}`);
      } else {
        if (!persistCheckoutRetry(null, checkpointBenefits)) {
          setError(
            'The rejected preparation could not be removed from recovery storage. It remains locked; reload and reconcile it before trying again.',
          );
          return;
        }
        setShowPay(true);
        setError(`${(e as Error).message}${clearedNote}`);
      }
    } finally {
      setPaying(false);
    }
  }

  async function completeCheckout() {
    await runCheckoutFlow(
      completeCheckoutInternal,
      'This payment action is already being verified. Wait for the result; do not collect or submit it again.',
    );
  }

  async function completeCheckoutInternal() {
    if (checkoutMutationInFlightRef.current) {
      setError('The bill is still being adjusted. Wait for the refreshed total before confirming payment.');
      return;
    }
    if (localWorkNeedsReconciliation) {
      setError(
        'This checkout changed in another tab or has unresolved recovery data. Nothing was submitted; reload and reconcile it first.',
      );
      return;
    }
    const current = checkoutRetry;
    if (!current) return;
    if (current.phase === 'preparing_order') {
      await prepareCheckoutInternal(current.paymentMethod);
      return;
    }
    // Before staff confirm receipt, an expiring lease must be renewed. Once
    // the phase is recording/finalizing, however, replay the same settlement
    // key first: the backend deliberately returns a committed idempotent result
    // before looking for the already-consumed claim.
    if (current.phase === 'awaiting_payment' && !hasUsableCheckoutClaim(current)) {
      setError(
        'This shared bill is no longer locked to this till. No payment should be collected yet; refreshing the exact bill now.',
      );
      await prepareCheckoutInternal(current.paymentMethod);
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
    let confirmed = current;
    if (
      current.phase === 'awaiting_payment'
      && current.paymentMethod === 'cash'
      && hasCollectibleCheckoutBalance(current)
    ) {
      const tenderedMinor = parseRupeesToMinor(cashTenderedInput);
      const collectedMinor = current.paymentAmountMinor + (current.tipMinor ?? 0);
      if (tenderedMinor === null || tenderedMinor < collectedMinor) {
        setError(`Enter cash received of at least ${inr(collectedMinor)} before confirming payment.`);
        return;
      }
      confirmed = { ...current, cashTenderedMinor: tenderedMinor };
    }
    const benefitCoveredZero = hasBenefitCoveredZeroBalance(confirmed);
    const retry: PosCheckoutRetry = benefitCoveredZero
      ? confirmed.phase === 'finalizing_zero'
        ? confirmed
        : { ...confirmed, phase: 'finalizing_zero' }
      : confirmed.phase === 'recording_payment'
        ? confirmed
        : { ...confirmed, phase: 'recording_payment' };
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

    async function submitSettlement(attempt: PosCheckoutRetry): Promise<OrderDTO> {
      const payment = buildCheckoutPaymentSubmission(attempt);
      const zero = buildCheckoutZeroFinalization(attempt);
      if (!payment && !zero) {
        throw new Error(
          'The checkout journal has an invalid settlement amount. Ask a protected owner to reconcile it before collecting money.',
        );
      }
      const settlement = zero
        ? await pos.finalizeZero(
          zero.orderId,
          zero.idempotencyKey,
          attempt.checkoutClaimToken,
        )
        : await pos.recordPayment(
          payment!.orderId,
          payment!.body,
          payment!.idempotencyKey,
          attempt.checkoutClaimToken,
        );
      if (settlement.order_status !== 'paid' || !settlement.invoice_no) {
        throw new Error(
          zero
            ? `The ${PREPAID_ALLOWANCE_LABEL.toLocaleLowerCase('en-IN')} did not finalize an invoice.`
            : 'The payment attempt did not finalize an invoice. Do not collect payment again.',
        );
      }
      const paidOrder = await pos.getOrder(zero?.orderId ?? payment!.orderId);
      if (
        paidOrder.status !== 'paid'
        || !paidOrder.invoice_no
        || !paidOrder.invoice_issued_at
      ) {
        throw new Error(
          zero
            ? `The ${PREPAID_ALLOWANCE_LABEL.toLocaleLowerCase('en-IN')} was accepted, but the final invoice could not be loaded. Resume this same recovery.`
            : 'Payment was accepted, but the final invoice could not be loaded. Resume this same recovery; do not charge again.',
        );
      }
      return paidOrder;
    }

    try {
      finishCheckout(await submitSettlement(retry));
    } catch (caught) {
      let error = caught;
      let activeRetry = retry;

      // A deterministic claim refusal records no server payment, but staff may
      // already be holding cash or have verified UPI. Reacquire only if the
      // complete canonical settlement fingerprint is unchanged, then replay
      // the original idempotency key automatically. Any changed bill remains
      // locked for protected-owner reconciliation; the UI never asks the
      // customer to pay twice.
      if (isCheckoutClaimRejection(error)) {
        try {
          const currentOrder = await pos.getOrder(activeRetry.pendingOrderId!);
          if (
            currentOrder.status === 'paid'
            && currentOrder.invoice_no
            && currentOrder.invoice_issued_at
          ) {
            if (zeroFinalization) {
              finishCheckout(currentOrder);
              return;
            }
            // A checkout-claim refusal is definitive: this payment key did
            // not write anything. If the bill is now paid, another settlement
            // won the race, while this cashier may still hold physical money.
            throw new Error(
              'This bill was paid by another settlement after this till confirmed payment. Do not discard this recovery or collect again; a protected owner must reconcile the duplicate physical collection.',
            );
          }
          if (currentOrder.status !== 'held' || !matchesConfirmedSettlement(activeRetry, currentOrder)) {
            throw new Error(
              'The shared bill changed after payment was confirmed. Do not collect again or alter this recovery; ask a protected owner to reconcile the physical payment against the current bill.',
            );
          }
          const claim = await pos.claimCheckout(currentOrder.id);
          if (
            claim.order_id !== currentOrder.id
            || claim.order_total_minor !== activeRetry.orderTotalMinor
            || claim.due_minor !== activeRetry.paymentAmountMinor
            || claim.order_version !== activeRetry.checkoutClaimOrderVersion
          ) {
            throw new Error(
              'The bill changed while its checkout lock was being renewed. Do not collect again; ask a protected owner to reconcile it.',
            );
          }
          activeRetry = {
            ...activeRetry,
            checkoutClaimToken: claim.claim_token,
            checkoutClaimExpiresAt: claim.expires_at,
            checkoutClaimOrderVersion: claim.order_version,
          };
          if (!persistCheckoutRetry(activeRetry)) {
            await pos.releaseCheckout(currentOrder.id, claim.claim_token)
              .catch(() => undefined);
            setError(
              'A fresh bill lock was issued, but recovery storage failed. Nothing was resubmitted. Keep this page open and restore browser storage; do not collect money again.',
            );
            return;
          }
          finishCheckout(await submitSettlement(activeRetry));
          return;
        } catch (recoveryError) {
          error = recoveryError;
        }
      }

      if (!persistCheckoutRetry(activeRetry)) {
        setError(
          'The unresolved payment result could not be re-checkpointed. Keep this tab open and do not collect money again; a protected owner must reconcile it.',
        );
        return;
      }
      setError(zeroFinalization
        ? `${(error as Error).message} The no-payment ${PREPAID_ALLOWANCE_LABEL.toLocaleLowerCase('en-IN')} settlement remains locked to the same key. Resume it; do not collect money.`
        : `${(error as Error).message} The payment attempt remains locked to the same key. `
          + 'Do not collect money again; resume or ask a protected owner to reconcile it.');
    } finally {
      setPaying(false);
    }
  }

  async function applyManualDiscount() {
    if (checkoutFlowInFlightRef.current) {
      setDiscountError('The bill is being prepared or paid. Wait for that result before changing its discount.');
      return;
    }
    if (!canManualDiscount) {
      setDiscountError('This account is not authorised to apply manual discounts. Ask an owner with discount access.');
      return;
    }
    if (localWorkNeedsReconciliation) {
      setDiscountError('This saved bill is locked to its original shift. No discount was changed.');
      return;
    }
    const minor = parseRupeesToMinor(discountInput);
    if (minor === null) {
      setDiscountError('Enter a valid discount amount.');
      return;
    }

    // Prefer an order that already exists (a resumed held order, or the
    // exact bill already prepared on the confirm-payment screen). A brand
    // new walk-in sale has no order yet at cart-review time — stash the
    // amount and prepareCheckout() applies it the moment the order exists.
    const targetOrderId = resumingOrder?.id ?? checkoutRetry?.pendingOrderId;
    if (!targetOrderId) {
      // Nothing exists server-side yet to reject an oversized amount, so clamp
      // it here against the same estimate the cart shows. Otherwise the bill is
      // prepared first and only then rejected, locking the checkout.
      const roomMinor = Math.max(0, estimatedPayable - pendingCartPointsMinor);
      if (minor > roomMinor) {
        setDiscountError(`Discount cannot exceed the order total (${inr(roomMinor)}).`);
        return;
      }
      if (!draftKey || !savePosDraft(draftKey, buildPosDraft(
        null,
        { discountMinor: minor, pointsMinor: pendingCartPointsMinor },
      ))) {
        setDiscountError('The discount was not applied because POS recovery storage could not save it safely.');
        return;
      }
      setPendingCartDiscountMinor(minor);
      setDiscountInput('');
      setDiscountError(null);
      return;
    }
    if (checkoutMutationInFlightRef.current) {
      setDiscountError('Another bill adjustment is still being verified. Wait for it to finish.');
      return;
    }

    checkoutMutationInFlightRef.current = true;
    setApplyingDiscount(true);
    setDiscountError(null);
    try {
      const activeRetry = checkoutRetry?.pendingOrderId === targetOrderId
        ? await releaseClaimBeforeCheckoutMutation(checkoutRetry)
        : null;
      const canonical = await pos.getOrder(targetOrderId);
      const order = await pos.applyDiscount(
        targetOrderId,
        minor,
        createOperationKey(),
        canonical.checkout_version,
      );
      if (resumingOrder && order.id === resumingOrder.id) {
        setResumingOrder(order);
      }
      if (activeRetry?.pendingOrderId === order.id) {
        const updated = await canonicalizeAndClaim(activeRetry, order);
        await persistFreshCheckoutClaim(
          updated,
          'The server accepted the discount, but the updated recovery checkpoint could not be saved. Do not collect payment; reload and reconcile this bill.',
        );
      }
      setPendingCartDiscountMinor(0);
      setDiscountInput('');
    } catch (e) {
      setDiscountError((e as Error).message);
    } finally {
      checkoutMutationInFlightRef.current = false;
      setApplyingDiscount(false);
    }
  }

  // Voluntary tip, entered on the confirm-payment screen once the exact
  // server bill is known. Unlike the discount/points above, there is no
  // server-side "apply" call — it rides along with the final payment
  // submission as its own field (see buildCheckoutPaymentSubmission).
  function applyTip() {
    if (checkoutFlowInFlightRef.current) {
      setTipError('The bill is being prepared or paid. Wait for that result before changing its tip.');
      return;
    }
    if (checkoutMutationInFlightRef.current) {
      setTipError('Wait for the current bill adjustment to finish before adding a tip.');
      return;
    }
    if (localWorkNeedsReconciliation) {
      setTipError('This saved bill is locked for reconciliation. No tip was changed.');
      return;
    }
    const minor = parseRupeesToMinor(tipInput);
    if (minor === null) {
      setTipError('Enter a valid tip amount.');
      return;
    }
    if (!checkoutRetry) return;
    const updated: PosCheckoutRetry = { ...checkoutRetry, tipMinor: minor };
    if (!persistCheckoutRetry(updated)) {
      setTipError(
        'Checkout recovery storage is unavailable. The tip was not saved; enable browser storage before trying again.',
      );
      return;
    }
    setTipInput('');
    setTipError(null);
  }

  async function redeemPoints() {
    if (checkoutFlowInFlightRef.current) {
      setPointsError('The bill is being prepared or paid. Wait for that result before redeeming points.');
      return;
    }
    if (localWorkNeedsReconciliation) {
      setPointsError('This saved bill is locked to its original shift. No points were changed.');
      return;
    }
    const points = Math.trunc(Number(pointsInput));
    if (!Number.isFinite(points) || points < 0) {
      setPointsError('Enter a valid number of points.');
      return;
    }
    const pointsCustomerPhone = customer?.phone.trim();
    if (!pointsCustomerPhone || pointsCustomerPhone !== customerPhone.trim()) {
      setPointsError('Find and verify this exact customer again before redeeming points.');
      return;
    }
    if (customer && points > customer.loyalty_points) {
      setPointsError(`Only ${customer.loyalty_points.toLocaleString('en-IN')} points available.`);
      return;
    }
    const minor = points * 10; // 10 points = ₹1 — keep in sync with backend MINOR_PER_POINT

    const targetOrderId = resumingOrder?.id ?? checkoutRetry?.pendingOrderId;
    if (!targetOrderId) {
      // Same reasoning as the discount above: with no order yet, this is the
      // only place a redemption larger than the bill can be caught before it
      // gets stashed and then permanently rejected at bill preparation.
      const roomMinor = Math.max(0, estimatedPayable - pendingCartDiscountMinor);
      if (minor > roomMinor) {
        setPointsError(
          `This bill can only absorb ${Math.floor(roomMinor / 10).toLocaleString('en-IN')} points (${inr(roomMinor)}).`,
        );
        return;
      }
      if (!draftKey || !savePosDraft(draftKey, buildPosDraft(
        null,
        {
          discountMinor: pendingCartDiscountMinor,
          pointsMinor: minor,
          pointsCustomerPhone,
        },
      ))) {
        setPointsError('The points were not applied because POS recovery storage could not save them safely.');
        return;
      }
      setPendingCartPointsMinor(minor);
      setPendingCartPointsCustomerPhone(pointsCustomerPhone);
      setPointsInput('');
      setPointsError(null);
      return;
    }
    if (checkoutMutationInFlightRef.current) {
      setPointsError('Another bill adjustment is still being verified. Wait for it to finish.');
      return;
    }

    checkoutMutationInFlightRef.current = true;
    setApplyingPoints(true);
    setPointsError(null);
    try {
      const activeRetry = checkoutRetry?.pendingOrderId === targetOrderId
        ? await releaseClaimBeforeCheckoutMutation(checkoutRetry)
        : null;
      const canonical = await pos.getOrder(targetOrderId);
      const order = await pos.redeemPoints(
        targetOrderId,
        points,
        createOperationKey(),
        canonical.checkout_version,
      );
      if (resumingOrder && order.id === resumingOrder.id) {
        setResumingOrder(order);
      }
      if (activeRetry?.pendingOrderId === order.id) {
        const updated = await canonicalizeAndClaim(activeRetry, order);
        await persistFreshCheckoutClaim(
          updated,
          'The server accepted the points, but the updated recovery checkpoint could not be saved. Do not collect payment; reload and reconcile this bill.',
        );
      }
      setPendingCartPointsMinor(0);
      setPendingCartPointsCustomerPhone(null);
      setPointsInput('');
    } catch (e) {
      setPointsError((e as Error).message);
    } finally {
      checkoutMutationInFlightRef.current = false;
      setApplyingPoints(false);
    }
  }

  async function redeemReward(key: string) {
    if (checkoutFlowInFlightRef.current) {
      setRewardError('The bill is being prepared or paid. Wait for that result before redeeming a reward.');
      return;
    }
    if (localWorkNeedsReconciliation) {
      setRewardError('This saved bill is locked to its original shift. No reward was changed.');
      return;
    }
    const targetOrderId = resumingOrder?.id ?? checkoutRetry?.pendingOrderId;
    if (!targetOrderId) {
      setRewardError('Prepare the bill first, then redeem a reward against it.');
      return;
    }
    if (checkoutMutationInFlightRef.current) {
      setRewardError('Another bill adjustment is still being verified. Wait for it to finish.');
      return;
    }
    checkoutMutationInFlightRef.current = true;
    setRedeemingReward(key);
    setRewardError(null);
    try {
      const activeRetry = checkoutRetry?.pendingOrderId === targetOrderId
        ? await releaseClaimBeforeCheckoutMutation(checkoutRetry)
        : null;
      const canonical = await pos.getOrder(targetOrderId);
      const order = await pos.redeemReward(
        targetOrderId,
        key,
        createOperationKey(),
        canonical.checkout_version,
      );
      if (resumingOrder && order.id === resumingOrder.id) {
        setResumingOrder(order);
      }
      if (activeRetry?.pendingOrderId === order.id) {
        const updated = await canonicalizeAndClaim(activeRetry, order);
        await persistFreshCheckoutClaim(
          updated,
          'The server accepted the reward, but the updated recovery checkpoint could not be saved. Do not collect payment; reload and reconcile this bill.',
        );
      }
    } catch (e) {
      setRewardError((e as Error).message);
    } finally {
      checkoutMutationInFlightRef.current = false;
      setRedeemingReward(null);
    }
  }

  function startAbandonPreparedCheckout() {
    if (checkoutFlowInFlightRef.current) {
      setError('Another checkout action is still running. Wait for it to finish before cancelling.');
      return;
    }
    if (checkoutMutationInFlightRef.current) {
      setError('Wait for the current bill adjustment to finish before cancelling checkout.');
      return;
    }
    if (localWorkNeedsReconciliation) {
      setError('This saved checkout is locked for reconciliation and cannot be cancelled from this tab.');
      return;
    }
    const retry = checkoutRetry;
    if (!canAbandonCheckoutRetry(retry) || !retry) return;
    setAbandonConfirmVariant(hasBenefitCoveredZeroBalance(retry) ? 'benefit_covered' : 'no_payment');
  }

  async function abandonPreparedCheckout() {
    await runCheckoutFlow(
      abandonPreparedCheckoutInternal,
      'This cancellation is already being verified. Wait for the result; it was not submitted twice.',
    );
  }

  async function abandonPreparedCheckoutInternal() {
    if (checkoutMutationInFlightRef.current) {
      setError('Wait for the current bill adjustment to finish before cancelling checkout.');
      return;
    }
    setAbandonConfirmVariant(null);
    const retry = checkoutRetry;
    if (!canAbandonCheckoutRetry(retry) || !retry?.pendingOrderId) return;
    if (localWorkNeedsReconciliation) {
      setError('This saved checkout is locked for reconciliation and was not changed.');
      return;
    }
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
        const draftCleared = draftKey ? clearPosDraft(draftKey) : false;
        if (!mayReleaseCancelledPreparedBill(Boolean(draftKey), draftCleared)) {
          const message =
            'This bill is already cancelled on the server, but this browser could not clear its saved recovery copy. '
            + 'This POS tab remains locked: close any other POS tab, reload this one, and confirm the bill shows Cancelled. '
            + 'Do not recreate or collect payment for it.';
          setError(message);
          notifications.error(message, { title: 'Cancelled bill needs local reconciliation' });
          void loadHeldOrders(true);
          return;
        }
        setCheckoutRetry(null);
        setCart([]);
        setPendingCartDiscountMinor(0);
        setPendingCartPointsMinor(0);
        setPendingCartPointsCustomerPhone(null);
        setResumingOrder(null);
        setUnresolvedResumingOrderId(null);
        clearCustomer();
        setError('This prepared bill was already cancelled; no payment was recorded.');
        return;
      }
      if (order.status === 'refunded') {
        const draftCleared = draftKey ? clearPosDraft(draftKey) : false;
        if (!mayReleaseCancelledPreparedBill(Boolean(draftKey), draftCleared)) {
          const message =
            'This order is already refunded on the server, but this browser could not clear its saved recovery copy. '
            + 'This POS tab remains locked: close any other POS tab, reload this one, and confirm the refund before continuing. '
            + 'Do not recreate the bill or collect another payment.';
          setError(message);
          notifications.error(message, { title: 'Refunded bill needs local reconciliation' });
          void loadHeldOrders(true);
          return;
        }
        setCheckoutRetry(null);
        setCart([]);
        setPendingCartDiscountMinor(0);
        setPendingCartPointsMinor(0);
        setPendingCartPointsCustomerPhone(null);
        setResumingOrder(null);
        setUnresolvedResumingOrderId(null);
        clearCustomer();
        setReceipt(order);
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
        if (!draftKey || !savePosDraft(draftKey, {
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
        // Only release after the durable draft no longer says this browser
        // owns checkout. If release succeeds and the process dies immediately,
        // another cashier can safely claim it without this browser resurrecting
        // the old token on reload.
        if (retry.checkoutClaimRequired && retry.checkoutClaimToken) {
          await pos.releaseCheckout(order.id, retry.checkoutClaimToken);
        }
        setCart([]);
        setResumingOrder(order);
        setCheckoutRetry(null);
        setError(null);
        notifications.info(
          'Payment was not recorded. The prepared held order remains available in POS for another cashier.',
          { title: 'Held bill released' },
        );
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
    await runCheckoutFlow(
      () => finishAbandonWithReasonInternal(reason),
      'This cancellation is already being verified. Wait for the result; it was not submitted twice.',
    );
  }

  async function finishAbandonWithReasonInternal(reason: string) {
    if (checkoutMutationInFlightRef.current) {
      setError('Wait for the current bill adjustment to finish before cancelling checkout.');
      return;
    }
    const order = abandonReasonOrder;
    setAbandonReasonOrder(null);
    if (!order) return;
    if (localWorkNeedsReconciliation) {
      setError('This saved checkout is locked for reconciliation and was not cancelled.');
      return;
    }
    setPaying(true);
    setError(null);
    try {
      const retry = checkoutRetry?.pendingOrderId === order.id ? checkoutRetry : null;
      await pos.voidOrder(order.id, reason, retry?.checkoutClaimToken ?? undefined);
      const draftCleared = draftKey ? clearPosDraft(draftKey) : false;
      if (!mayReleaseCancelledPreparedBill(Boolean(draftKey), draftCleared)) {
        const message =
          'The prepared bill was cancelled on the server, but this browser could not clear its saved recovery copy. '
          + 'This POS tab is locked: close any other POS tab, reload this one, and confirm the bill shows Cancelled. '
          + 'Do not recreate or collect payment for it.';
        setError(message);
        notifications.error(message, { title: 'Cancelled bill needs local reconciliation' });
        void loadHeldOrders(true);
        return;
      }
      setCheckoutRetry(null);
      setCart([]);
      setResumingOrder(null);
      setUnresolvedResumingOrderId(null);
      clearCustomer();
      setError(null);
      notifications.success(POS_PREPARED_BILL_CANCELLED_FEEDBACK.message, {
        title: POS_PREPARED_BILL_CANCELLED_FEEDBACK.title,
      });
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
  if (
    error
    && !items.length
    && !cart.length
    && !resumingOrder
    && !checkoutRetry
    && !localWorkNeedsReconciliation
  ) {
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
  const shiftPrepaidRevenueLabel = shiftCollections
    ? profileMembershipMoneyLabel('revenue', shiftCollections.membershipMinor)
    : null;
  const recoveringLegacyOrderMode = Boolean(
    checkoutRetry && checkoutRetry.snapshot.orderType !== DEFAULT_POS_ORDER_TYPE,
  );
  const draftLockedByOtherTab = Boolean(draftKey)
    && draftLeaseState.key === draftKey
    && draftLeaseState.status === 'blocked';
  const draftWriteLeaseUnavailable = Boolean(draftKey)
    && draftLeaseState.key === draftKey
    && (draftLeaseState.status === 'blocked' || draftLeaseState.status === 'unsupported');
  const canDiscardLockedLocalDraft = localWorkNeedsReconciliation
    && !checkoutRetry
    && !draftWriteLeaseUnavailable;

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
            {shiftId && shiftCollections !== null && (
              <div
                className="chip text-xs !py-1.5 flex-wrap"
                title="Payment receipts on this shift before refunds; opening float is excluded"
              >
                Gross collections:
                <span className="font-bold font-mono">{inr(shiftCollections.grossMinor)}</span>
                <span className="text-fg-muted">
                  POS {inr(shiftCollections.posMinor)}
                  {shiftPrepaidRevenueLabel && (
                    <> · {shiftPrepaidRevenueLabel} {inr(shiftCollections.membershipMinor)}</>
                  )}
                </span>
              </div>
            )}
            <button className="btn btn-ghost relative" onClick={() => { setShowHeldPicker(true); loadHeldOrders(); }} title="Bills sent here from Gaming">
              <Inbox size={14}/> Incoming orders
              {heldOrders.length > 0 && (
                <span className="absolute -top-1.5 -right-1.5 min-w-[18px] h-[18px] px-1 rounded-full bg-accent text-bg text-[10px] font-bold flex items-center justify-center">
                  {heldOrders.length}
                </span>
              )}
            </button>
            {!resumingOrder && (RESTAURANT_ORDER_TYPES_UI_ENABLED ? (
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
            ) : (
              <div
                className="chip text-xs !py-1.5"
                title={recoveringLegacyOrderMode ? 'Existing checkout recovery' : 'Manual drinks and snacks sale'}
              >
                {recoveringLegacyOrderMode ? 'Recovering saved bill' : 'Counter sale'}
              </div>
            ))}
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

        {RESTAURANT_ORDER_TYPES_UI_ENABLED && !resumingOrder && orderType === 'delivery' && (
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

        {localWorkNeedsReconciliation && (
          <section className="mb-4 max-w-3xl rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-4">
            <div className="flex items-start gap-3">
              <AlertCircle size={18} className="mt-0.5 shrink-0 text-accent-bad"/>
              <div className="min-w-0 flex-1">
                <h3 className="text-sm font-semibold text-fg">Saved bill needs attention</h3>
                <p className="mt-1 text-xs leading-relaxed text-fg-muted">
                  {draftLockedByOtherTab
                    ? 'Another POS tab owns this employee and till draft. This tab is read-only and cannot claim, change, pay, or delete that bill.'
                    : draftLeaseState.status === 'unsupported'
                      ? 'This browser cannot provide protected single-writer POS storage. Update it or use the supported Android app before taking a bill.'
                    : unresolvedResumingOrderId
                      ? `Incoming order ${unresolvedResumingOrderId.slice(0, 8)} could not be verified. Its saved reference is preserved; retry online before discarding it.`
                      : hasUnavailableDraftItems
                        ? 'One or more saved items are no longer in the catalogue. Their ids and quantities are preserved, but their price cannot be reconstructed safely.'
                        : checkoutRetry
                          ? 'This checkout has conflicting recovery data. Do not collect or retry payment until a protected owner reconciles it.'
                          : 'This local bill cannot be safely attached to the current shift. Its saved copy remains unchanged.'}
                </p>
                {hasUnavailableDraftItems && (
                  <ul className="mt-2 space-y-1 text-xs text-accent-bad">
                    {cart.filter((line) => line.unavailable).map((line) => (
                      <li key={line.item.id} className="break-all">
                        {line.qty} × unavailable item · id {line.item.id}
                      </li>
                    ))}
                  </ul>
                )}
                <div className="mt-3 flex flex-wrap gap-2">
                  <button
                    type="button"
                    className="btn btn-ghost !py-1.5 text-xs"
                    onClick={() => window.location.reload()}
                  >
                    Reload POS
                  </button>
                  {canDiscardLockedLocalDraft && (
                    <button
                      type="button"
                      className="btn btn-ghost !py-1.5 text-xs text-accent-bad"
                      onClick={() => setShowCartClearConfirm(true)}
                    >
                      <Trash2 size={14}/> Discard after reconciliation
                    </button>
                  )}
                </div>
                {draftLockedByOtherTab && (
                  <p className="mt-2 text-xs text-accent-bad">
                    Close the other POS tab first, then reload this one. Its active bill will not be overwritten.
                  </p>
                )}
                {draftLeaseState.status === 'unsupported' && (
                  <p className="mt-2 text-xs text-accent-bad">
                    POS requires Web Locks support so two tabs cannot overwrite the same bill.
                  </p>
                )}
              </div>
            </div>
          </section>
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
            disabled={localWorkNeedsReconciliation || checkoutAdjustmentBusy || paying || Boolean(checkoutRetry)}
            onPhoneChange={changeCustomerPhone}
            onNameChange={setCustomerName}
            onLookup={lookupCustomer}
            onClear={clearCustomerFromForm}
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
                disabled={checkoutAdjustmentBusy || localWorkNeedsReconciliation || paying}
                className="input flex-1"
              />
              <button
                className="btn btn-ghost disabled:opacity-40"
                disabled={checkoutAdjustmentBusy || localWorkNeedsReconciliation || paying || !pointsInput}
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
                  disabled={checkoutAdjustmentBusy || localWorkNeedsReconciliation || paying || !r.affordable}
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
            <button
              key={item.id}
              onClick={() => add(item)}
              disabled={!!resumingOrder || !!checkoutRetry || localWorkNeedsReconciliation}
              className="card text-left hover:border-accent transition group p-3 sm:p-4 disabled:cursor-not-allowed disabled:opacity-50"
              title={resumingOrder
                ? 'Sent bill items are locked; edit them in the source workflow.'
                : localWorkNeedsReconciliation
                  ? 'This saved bill is locked to its original shift.'
                  : undefined}
            >
              <div className="break-words text-sm font-semibold">{item.name}</div>
              <div className="text-fg-muted text-xs mt-1 flex items-center justify-between">
                <span>{inr(item.base_price_minor)}</span>
                {TAX_COMPLIANCE_UI_ENABLED && (
                  <span className="chip !py-0 !px-2 !text-[10px]">{(item.tax_rate * 100).toFixed(0)}%</span>
                )}
              </div>
            </button>
          ))}
        </div>
      </section>

      <aside className="hidden xl:flex card flex-col">
        <header className="flex items-center justify-between mb-3">
          <h3 className="text-lg font-semibold flex items-center gap-2"><ShoppingCart size={18} /> Cart · {cartQty}</h3>
              {cart.length > 0 && !checkoutRetry && (
            <button
              onClick={() => setShowCartClearConfirm(true)}
              disabled={draftLockedByOtherTab}
              className="text-xs text-fg-muted hover:text-accent-bad disabled:cursor-not-allowed disabled:opacity-40"
              title={draftLockedByOtherTab ? 'Close the other POS tab and reload before discarding anything.' : undefined}
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
              {resumingOrder
                ? 'Items are locked after Send to POS. Customer and authorised settlement benefits can still be changed.'
                : 'Tap items to build the order.'}
            </p>
          )}
          {cart.map((l) => (
            <div key={l.item.id} className="flex items-center gap-2 py-1">
              <div className="flex-1 min-w-0">
                <div className={`font-medium text-sm truncate ${l.unavailable ? 'text-accent-bad' : ''}`}>
                  {l.item.name}
                </div>
                <div className="text-xs text-fg-muted">
                  {l.unavailable ? `id ${l.item.id} · price unavailable` : `${inr(l.item.base_price_minor)} ea`}
                </div>
              </div>
              <div className="w-24 text-right font-mono text-sm">
                {l.unavailable ? 'Unknown' : inr(l.item.base_price_minor * l.qty)}
              </div>
              <div className="flex items-center gap-1">
                <button
                  onClick={() => adjust(l.item.id, -1)}
                  disabled={localWorkNeedsReconciliation || !!checkoutRetry || !!resumingOrder}
                  className="btn btn-ghost !min-h-[36px] !p-2"
                  aria-label={`Decrease ${l.item.name} quantity`}
                  title="Decrease quantity"
                ><Minus size={12} /></button>
                <span className="w-6 text-center font-mono text-sm">{l.qty}</span>
                <button
                  onClick={() => adjust(l.item.id, 1)}
                  disabled={localWorkNeedsReconciliation || !!checkoutRetry || !!resumingOrder}
                  className="btn btn-ghost !min-h-[36px] !p-2"
                  aria-label={`Increase ${l.item.name} quantity`}
                  title="Increase quantity"
                ><Plus size={12} /></button>
                <button
                  onClick={() => adjust(l.item.id, -l.qty)}
                  disabled={localWorkNeedsReconciliation || !!checkoutRetry || !!resumingOrder}
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
              <span>{PREPAID_DISCOUNT_LABEL} (est.)</span><span>-{inr(estimatedMembershipDiscount)}</span>
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
            <span>{hasUnavailableDraftItems ? 'Total' : 'Total (est.)'}</span>
            <span>{hasUnavailableDraftItems
              ? 'Needs reconciliation'
              : inr(Math.max(0, estimatedPayable - pendingCartDiscountMinor - pendingCartPointsMinor))}</span>
          </div>
          <p className="text-xs text-fg-muted mt-1">
            {TAX_COMPLIANCE_UI_ENABLED
              ? 'Final GST split and round-off are computed by the server on charge.'
              : 'Final total and round-off are confirmed by the server on charge.'}
          </p>
        </div>

        {canManualDiscount && <div className="mt-3 space-y-2 rounded-xl border border-bg-border px-3 py-2">
          <span className="text-xs text-fg-muted">Custom discount</span>
          <div className="flex gap-2">
            <PosMoneyInput
              purpose="discount"
              min="0"
              step="1"
              inputMode="decimal"
              placeholder="₹ off"
              value={discountInput}
              onChange={(e) => setDiscountInput(e.target.value)}
              disabled={checkoutAdjustmentBusy || localWorkNeedsReconciliation || paying}
              className="input flex-1"
            />
            <button
              className="btn btn-ghost disabled:opacity-40"
              disabled={checkoutAdjustmentBusy || localWorkNeedsReconciliation || paying || !discountInput}
              onClick={applyManualDiscount}
            >
              {applyingDiscount ? <Loader2 size={16} className="animate-spin"/> : 'Apply'}
            </button>
          </div>
          {discountError && <p className="text-xs text-accent-bad">{discountError}</p>}
        </div>}

        <button onClick={() => setShowPay(true)}
          disabled={
            (!cart.length && !resumingOrder)
            || !shiftId
            || paying
            || checkoutAdjustmentBusy
            || !receiptBusiness
            || !!receiptSettingsError
            || localWorkNeedsReconciliation
          }
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
            {localWorkNeedsReconciliation
              ? 'Review locked saved bill'
              : `${cart.length ? 'Review cart' : 'Prepare bill'} · est. ${inr(Math.max(0, estimatedPayable - pendingCartDiscountMinor - pendingCartPointsMinor))}`}
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
                    <div className={`font-semibold text-sm break-words ${line.unavailable ? 'text-accent-bad' : ''}`}>
                      {line.item.name}
                    </div>
                    <div className="text-xs text-fg-muted mt-1">
                      {line.unavailable
                        ? `id ${line.item.id} · original price unavailable`
                        : `${inr(line.item.base_price_minor)} each`}
                    </div>
                  </div>
                  <div className="font-mono text-sm shrink-0">
                    {line.unavailable ? 'Unknown' : inr(line.item.base_price_minor * line.qty)}
                  </div>
                </div>
                <div className="mt-3 flex items-center justify-end gap-2">
                  <button
                    onClick={() => adjust(line.item.id, -1)}
                    disabled={localWorkNeedsReconciliation || !!checkoutRetry || !!resumingOrder}
                    className="btn btn-ghost !min-h-[44px] !min-w-[44px] !p-2"
                    aria-label={`Decrease ${line.item.name} quantity`}
                    title="Decrease quantity"
                  ><Minus size={16}/></button>
                  <span className="w-8 text-center font-mono text-sm" aria-label={`${line.item.name} quantity`}>{line.qty}</span>
                  <button
                    onClick={() => adjust(line.item.id, 1)}
                    disabled={localWorkNeedsReconciliation || !!checkoutRetry || !!resumingOrder}
                    className="btn btn-ghost !min-h-[44px] !min-w-[44px] !p-2"
                    aria-label={`Increase ${line.item.name} quantity`}
                    title="Increase quantity"
                  ><Plus size={16}/></button>
                  <button
                    onClick={() => adjust(line.item.id, -line.qty)}
                    disabled={localWorkNeedsReconciliation || !!checkoutRetry || !!resumingOrder}
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
                <span>{PREPAID_DISCOUNT_LABEL} (est.)</span>
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
              <span>{hasUnavailableDraftItems ? 'Total' : 'Total (est.)'}</span>
              <span>{hasUnavailableDraftItems
                ? 'Needs reconciliation'
                : inr(Math.max(0, estimatedPayable - pendingCartDiscountMinor - pendingCartPointsMinor))}</span>
            </div>
            <p className="mt-1 text-xs text-fg-muted">
              {TAX_COMPLIANCE_UI_ENABLED
                ? 'Final GST split and round-off are computed by the server.'
                : 'Final total and round-off are confirmed by the server.'}
            </p>
          </div>

          {canManualDiscount && <div className="mt-3 space-y-2 rounded-xl border border-border-base px-3 py-2">
            <span className="text-xs text-fg-muted">Custom discount</span>
            <div className="flex gap-2">
              <PosMoneyInput
                purpose="discount"
                min="0"
                step="1"
                inputMode="decimal"
                placeholder="₹ off"
                value={discountInput}
                onChange={(e) => setDiscountInput(e.target.value)}
                disabled={checkoutAdjustmentBusy || localWorkNeedsReconciliation || paying}
                className="input flex-1"
              />
              <button
                className="btn btn-ghost disabled:opacity-40"
                disabled={checkoutAdjustmentBusy || localWorkNeedsReconciliation || paying || !discountInput}
                onClick={applyManualDiscount}
              >
                {applyingDiscount ? <Loader2 size={16} className="animate-spin"/> : 'Apply'}
              </button>
            </div>
            {discountError && <p className="text-xs text-accent-bad">{discountError}</p>}
          </div>}

          {canDiscardLockedLocalDraft && (
            <button
              type="button"
              className="btn btn-ghost mt-4 w-full text-accent-bad"
              onClick={() => setShowCartClearConfirm(true)}
            >
              <Trash2 size={16}/> Discard saved bill after reconciliation
            </button>
          )}

          <button
            onClick={() => {
              setShowCart(false);
              setShowPay(true);
            }}
            disabled={!shiftId || !receiptBusiness || !!receiptSettingsError || localWorkNeedsReconciliation || checkoutAdjustmentBusy || paying}
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
              Estimated {PREPAID_DISCOUNT_LABEL.toLocaleLowerCase('en-IN')}: {inr(estimatedMembershipDiscount)}.
            </div>
          )}
          <p className="mb-3 text-xs text-fg-muted">
            The server will create or update the order first. The exact discounted, taxed,
            and rounded amount appears before you collect money.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <PayButton icon={<Banknote size={28}/>}   label="Cash" sub="Prepare exact bill" disabled={paying || checkoutAdjustmentBusy || localWorkNeedsReconciliation} onClick={() => prepareCheckout('cash')} />
            <PayButton icon={<Smartphone size={28}/>} label="UPI"  sub="Prepare exact QR"   disabled={paying || checkoutAdjustmentBusy || localWorkNeedsReconciliation} onClick={() => prepareCheckout('upi')} />
            <PayButton icon={<CreditCard size={28}/>} label="Card" sub="Prepare exact bill" disabled={paying || checkoutAdjustmentBusy || localWorkNeedsReconciliation} onClick={() => prepareCheckout('card')} />
            <PayButton icon={<QrCode size={28}/>}     label="QR"   sub="Prepare exact QR"   disabled={paying || checkoutAdjustmentBusy || localWorkNeedsReconciliation} onClick={() => prepareCheckout('qr')} />
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
            const parsedCashTendered = parseRupeesToMinor(cashTenderedInput);
            const cashCollectedMinor = collectibleBalance
              ? checkoutRetry.paymentAmountMinor + tipMinor
              : 0;
            const cashTenderReady = checkoutRetry.paymentMethod !== 'cash'
              || benefitCoveredZero
              || (parsedCashTendered !== null && parsedCashTendered >= cashCollectedMinor);
            const isGenuineRestore = checkoutRetry.key === restoredRetryKey;
            const checkoutClaimReady = hasUsableCheckoutClaim(checkoutRetry);
            const scanMethod = checkoutRetry.paymentMethod === 'upi'
              || checkoutRetry.paymentMethod === 'qr';
            const upiLink = checkoutRetry.phase === 'awaiting_payment'
              && checkoutClaimReady
              && scanMethod
              && collectibleBalance
              && receiptBusiness
              && amount !== undefined
              ? buildUpiPayLink(receiptBusiness, amount + tipMinor, receiptBusiness.brandName)
              : null;
            const methodLabel = benefitCoveredZero
              ? `${PREPAID_ALLOWANCE_LABEL} · no payment`
              : {
                cash: 'Cash',
                upi: 'UPI',
                card: 'Card',
                qr: 'QR',
              }[checkoutRetry.paymentMethod];
            return (
              <div className="space-y-4">
                {localWorkNeedsReconciliation && (
                  <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 px-3 py-2 text-sm text-accent-bad">
                    This checkout is read-only because its saved recovery changed or cannot be verified. Do not collect money. Reload and reconcile it first.
                  </div>
                )}
                <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 px-3 py-2 text-sm text-accent-gold">
                  {checkoutRetry.phase === 'preparing_order'
                    ? 'The server bill preparation was interrupted. No payment should be collected yet; resume the same request key.'
                    : checkoutRetry.phase === 'awaiting_payment' && !checkoutClaimReady
                      ? 'This shared bill lock expired or is unavailable. Do not collect money; refresh the exact bill lock first.'
                    : checkoutRetry.phase === 'awaiting_payment'
                      ? benefitCoveredZero
                        ? `The ${PREPAID_ALLOWANCE_LABEL.toLocaleLowerCase('en-IN')} covers this exact server bill. Collect no money; complete the allowance to issue the final invoice.`
                        : isGenuineRestore
                          ? 'This is the exact server balance, restored after this screen was reopened. Verify payment was not already received before asking the customer to pay again.'
                          : 'This is the exact server balance. Collect payment from the customer now.'
                      : checkoutRetry.phase === 'finalizing_zero'
                        ? `The no-payment ${PREPAID_ALLOWANCE_LABEL.toLocaleLowerCase('en-IN')} settlement is unresolved. Resume only this same key; do not collect money.`
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
                {canManualDiscount && checkoutRetry.phase === 'awaiting_payment' && !benefitCoveredZero && !localWorkNeedsReconciliation && (
                  <div className="space-y-2 rounded-xl border border-border-base px-3 py-2">
                    <div className="flex items-center justify-between text-xs text-fg-muted">
                      <span>Custom discount</span>
                      {!!checkoutRetry.orderManualDiscountMinor && (
                        <span>Applied so far: {inr(checkoutRetry.orderManualDiscountMinor)}</span>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <PosMoneyInput
                        purpose="discount"
                        min="0"
                        step="1"
                        inputMode="decimal"
                        placeholder="₹ off"
                        value={discountInput}
                        onChange={(e) => setDiscountInput(e.target.value)}
                        disabled={checkoutAdjustmentBusy || paying}
                        className="input flex-1"
                      />
                      <button
                        className="btn btn-ghost disabled:opacity-40"
                        disabled={checkoutAdjustmentBusy || paying || !discountInput}
                        onClick={applyManualDiscount}
                      >
                        {applyingDiscount ? <Loader2 size={16} className="animate-spin"/> : 'Apply'}
                      </button>
                    </div>
                    {discountError && <p className="text-xs text-accent-bad">{discountError}</p>}
                  </div>
                )}
                {checkoutRetry.phase === 'awaiting_payment' && !benefitCoveredZero && collectibleBalance && !localWorkNeedsReconciliation && (
                  <div className="space-y-2 rounded-xl border border-border-base px-3 py-2">
                    <div className="flex items-center justify-between text-xs text-fg-muted">
                      <span>Tip</span>
                      {!!checkoutRetry.tipMinor && (
                        <span>Added: {inr(checkoutRetry.tipMinor)}</span>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <PosMoneyInput
                        purpose="tip"
                        min="0"
                        step="1"
                        inputMode="decimal"
                        placeholder="₹ tip"
                        value={tipInput}
                        onChange={(e) => setTipInput(e.target.value)}
                        disabled={paying || checkoutAdjustmentBusy}
                        className="input flex-1"
                      />
                      <button
                        className="btn btn-ghost disabled:opacity-40"
                        disabled={paying || checkoutAdjustmentBusy || !tipInput}
                        onClick={applyTip}
                      >
                        Add
                      </button>
                    </div>
                    {tipError && <p className="text-xs text-accent-bad">{tipError}</p>}
                  </div>
                )}
                {checkoutRetry.phase === 'awaiting_payment' && !benefitCoveredZero && collectibleBalance
                  && !localWorkNeedsReconciliation
                  && checkoutRetry.paymentMethod === 'cash' && amount !== undefined && (() => {
                  const dueMinor = amount + tipMinor;
                  const tenderedMinor = parsedCashTendered;
                  const changeMinor = tenderedMinor !== null ? tenderedMinor - dueMinor : null;
                  // Dedupe — at larger due amounts, "next ₹500 above" and
                  // "next ₹1000 above" can land on the same round number.
                  const quickTenders = [100, 500, 1000]
                    .map((denomination) => nextRoundTenderMinor(dueMinor, denomination))
                    .filter((value, index, all) => all.indexOf(value) === index);
                  return (
                    <div className="space-y-2 rounded-xl border border-border-base px-3 py-2">
                      <div className="text-xs text-fg-muted">Cash tendered</div>
                      <PosMoneyInput
                        purpose="cashTendered"
                        min="0"
                        step="0.01"
                        inputMode="decimal"
                        placeholder="₹ received from customer"
                        value={cashTenderedInput}
                        onChange={(e) => setCashTenderedInput(e.target.value)}
                        disabled={paying || checkoutAdjustmentBusy}
                        className="input font-mono text-lg"
                      />
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          className="btn btn-ghost !min-h-[40px] !py-1.5 !px-3 text-xs disabled:opacity-40"
                          disabled={paying || checkoutAdjustmentBusy}
                          onClick={() => setCashTenderedInput((dueMinor / 100).toFixed(2))}
                        >
                          Exact amount
                        </button>
                        {quickTenders.map((quickMinor) => (
                          <button
                            key={quickMinor}
                            type="button"
                            className="btn btn-ghost !min-h-[40px] !py-1.5 !px-3 text-xs disabled:opacity-40"
                            disabled={paying || checkoutAdjustmentBusy}
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
                {checkoutRetry.phase === 'awaiting_payment' && scanMethod && collectibleBalance && !localWorkNeedsReconciliation && (
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
                      disabled={paying || checkoutAdjustmentBusy || localWorkNeedsReconciliation} onClick={startAbandonPreparedCheckout}>
                      {benefitCoveredZero ? 'Cancel prepared bill' : 'No payment · Cancel'}
                    </button>
                    <button className="btn btn-primary disabled:opacity-40"
                      disabled={
                        paying
                        || checkoutAdjustmentBusy
                        || localWorkNeedsReconciliation
                        || (checkoutClaimReady && !collectibleBalance && !benefitCoveredZero)
                        || (checkoutClaimReady && collectibleBalance && !cashTenderReady)
                      }
                      onClick={() => checkoutClaimReady
                        ? completeCheckout()
                        : prepareCheckout(checkoutRetry.paymentMethod)}>
                      {paying ? <Loader2 size={16} className="animate-spin"/> : checkoutClaimReady ? <Check size={16}/> : <RefreshCwIcon/>}
                      {checkoutClaimReady
                        ? benefitCoveredZero ? 'Complete member benefit' : 'Payment received'
                        : 'Refresh bill lock'}
                    </button>
                  </div>
                ) : canAbandonCheckoutRetry(checkoutRetry) ? (
                  // Nothing has been collected at this phase, so the cashier
                  // must always be able to walk away — a resume that the server
                  // keeps refusing would otherwise trap this terminal.
                  <div className="grid grid-cols-2 gap-3">
                    <button className="btn btn-ghost disabled:opacity-40"
                      disabled={paying || checkoutAdjustmentBusy || localWorkNeedsReconciliation} onClick={startAbandonPreparedCheckout}>
                      No payment · Cancel
                    </button>
                    <button
                      className="btn btn-primary disabled:opacity-40"
                      disabled={paying || checkoutAdjustmentBusy || localWorkNeedsReconciliation}
                      onClick={() => prepareCheckout(checkoutRetry.paymentMethod)}
                    >
                      {paying ? <Loader2 size={16} className="animate-spin"/> : <RefreshCwIcon/>}
                      {paying ? 'Reconciling safely…' : 'Resume same attempt'}
                    </button>
                  </div>
                ) : (
                  <button
                    className="btn btn-primary w-full disabled:opacity-40"
                    disabled={paying || checkoutAdjustmentBusy || localWorkNeedsReconciliation}
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
        <Modal title="Incoming bills from Gaming" onClose={() => setShowHeldPicker(false)} wide>
          <div className="relative mb-3">
            <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-fg-muted"/>
            <input
              className="input !pl-9 !min-h-[42px] !py-2"
              value={heldSearch}
              onChange={(e) => setHeldSearch(e.target.value)}
              placeholder="Search station or bill"
              autoFocus
            />
          </div>
          {heldError && <p className="text-accent-bad text-xs mb-2">{heldError}</p>}
          {heldLoading ? (
            <div className="space-y-2 py-2">
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-4 w-2/3" />
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
                        disabled={voidingId === o.id || localWorkNeedsReconciliation}
                        onClick={() => setVoidPromptRow(o)}
                        title="Void this queued order with a reason">
                        {voidingId === o.id ? <Loader2 className="animate-spin" size={14}/> : <Trash2 size={14}/>}
                      </button>
                    </div>
                  );
                })}
              {!heldOrders.length && (
                <p className="text-fg-muted text-sm text-center py-6">
                  Nothing waiting — Gaming and other shared bills appear here after they are sent to POS.
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
      {showCartClearConfirm && (
        <ConfirmModal
          title={localWorkNeedsReconciliation ? 'Discard locked saved bill' : 'Clear POS cart'}
          message={localWorkNeedsReconciliation
            ? 'This is the only saved copy of a bill from a previous or unverified shift. Confirm only after reviewing the items and reconciling what was served. This cannot be undone.'
            : 'Remove every unsent item and cart discount from this bill? This cannot be undone.'}
          confirmLabel={localWorkNeedsReconciliation ? 'Discard saved bill' : 'Clear cart'}
          danger
          onConfirm={clearLocalCart}
          onCancel={() => setShowCartClearConfirm(false)}
        />
      )}
      {abandonConfirmVariant && (
        <ConfirmModal
          title="Cancel prepared bill"
          message={abandonConfirmVariant === 'benefit_covered'
            ? `Cancel this bill covered by the ${PREPAID_ALLOWANCE_LABEL.toLocaleLowerCase('en-IN')}? No money is due and the allowance has not been consumed yet.`
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
  disabled,
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
  disabled?: boolean;
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
          <button
            className="text-xs text-fg-muted hover:text-accent-bad disabled:opacity-40"
            disabled={disabled}
            onClick={onClear}
          >
            Clear
          </button>
        )}
      </div>
      <div className="grid grid-cols-1 md:grid-cols-[180px_1fr_auto] gap-2">
        <input
          className="input !min-h-[40px] !py-2"
          value={phone}
          disabled={disabled}
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
          disabled={disabled}
          placeholder="Name"
          onChange={(e) => onNameChange(e.target.value)}
        />
        <button className="btn btn-ghost !min-h-[40px] !py-2 !px-4" onClick={onLookup} disabled={disabled || busy || !phone.trim()}>
          {busy ? <Loader2 size={13} className="animate-spin"/> : <Search size={13}/>}
          Find
        </button>
      </div>

      {message && (
        <div className={`mt-3 rounded-xl border px-3 py-2 text-xs flex items-start gap-2 ${tone}`}>
          {lookupState === 'error'
            ? <AlertCircle size={13} className="mt-0.5 shrink-0"/>
            : MEMBERSHIP_UI_ENABLED && subscription
              ? <Crown size={13} className="mt-0.5 shrink-0"/>
              : <UserRound size={13} className="mt-0.5 shrink-0"/>}
          <div>
            <div>{message}</div>
            {customer && (
              <div className="mt-1 opacity-80">
                Visits: {customer.visit_count} · Points: {customer.loyalty_points.toLocaleString('en-IN')} · {customer.gaming_rank} rank
                {customer.next_gaming_rank && ` (${customer.points_to_next_gaming_rank} pts to ${customer.next_gaming_rank})`}
              </div>
            )}
            {MEMBERSHIP_UI_ENABLED && tier && (
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
