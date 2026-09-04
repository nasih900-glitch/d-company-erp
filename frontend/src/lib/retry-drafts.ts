export type DraftLine = { itemId: string; qty: number; note?: string };

export type CheckoutPaymentMethod = 'cash' | 'upi' | 'card' | 'qr';
export type CheckoutPhase =
  | 'preparing_order'
  | 'awaiting_payment'
  | 'recording_payment'
  | 'finalizing_zero';
export type CheckoutOrderType = 'dine_in' | 'takeaway' | 'delivery';
export type CheckoutDeliveryVia =
  | 'inhouse'
  | 'zomato'
  | 'swiggy'
  | 'ubereats'
  | 'other_aggregator';

export type TableRetryOperation =
  | { kind: 'create'; shiftId: string }
  | { kind: 'append'; orderId: string };

export interface TableCartDraft {
  version: 2;
  lines: DraftLine[];
  operationKey: string;
  shiftId?: string;
  operation?: TableRetryOperation;
}

export interface PosCheckoutSnapshot {
  shiftId: string;
  cart: DraftLine[];
  orderType: CheckoutOrderType;
  deliveryVia: CheckoutDeliveryVia;
  deliveryStateCode: string;
  customerName: string;
  customerPhone: string;
}

export interface PosCheckoutRetry {
  key: string;
  phase: CheckoutPhase;
  paymentMethod: CheckoutPaymentMethod;
  resumingOrderId?: string;
  pendingOrderId?: string;
  /** Frozen optimistic-lock version for exact idempotent cart-discount replay. */
  cartDiscountExpectedVersion?: number;
  /** Frozen optimistic-lock version for exact idempotent cart-points replay. */
  cartPointsExpectedVersion?: number;
  orderTotalMinor?: number;
  paymentAmountMinor?: number;
  // Voluntary tip, entered on the confirm-payment screen once the exact
  // server bill is known. Never applied server-side ahead of time — it only
  // travels with the final payment submission (see
  // buildCheckoutPaymentSubmission below), additional money on top of
  // paymentAmountMinor, never folded into it.
  tipMinor?: number;
  /** Actual cash handed to the cashier, used for receipt/change audit. */
  cashTenderedMinor?: number;
  // Combined membership + custom-discount + points figure — kept for the
  // final receipt/reconciliation math. For the cashier-facing "applied so
  // far" confirmation, use orderManualDiscountMinor/orderPointsRedeemedMinor
  // below instead: this one silently includes automatic membership savings
  // that the cashier never took an action for.
  orderDiscountMinor?: number;
  orderManualDiscountMinor?: number;
  orderPointsRedeemedMinor?: number;
  freeGamingMinutesApplied?: number;
  freeHookahCountApplied?: number;
  /** Required only for a shared Tables/Gaming held order. */
  checkoutClaimToken?: string;
  checkoutClaimExpiresAt?: string;
  checkoutClaimOrderVersion?: number;
  checkoutClaimRequired?: boolean;
  snapshot: PosCheckoutSnapshot;
}

export interface CheckoutPaymentSubmission {
  orderId: string;
  idempotencyKey: string;
  body: {
    method: CheckoutPaymentMethod;
    amount_minor: number;
    tendered_minor?: number;
    expected_order_total_minor: number;
    expected_due_minor: number;
    tip_minor: number;
  };
}

export interface CheckoutZeroFinalization {
  orderId: string;
  idempotencyKey: string;
}

export interface PosRetryDraft {
  version: 2;
  shiftId?: string;
  resumingOrderId?: string;
  cart: DraftLine[];
  orderType: CheckoutOrderType;
  deliveryVia: CheckoutDeliveryVia;
  deliveryStateCode: string;
  customerName: string;
  customerPhone: string;
  // A custom discount / points redemption the cashier entered at cart-review
  // time, before any order existed to carry it. Until prepareCheckout()
  // creates the order and applies it server-side there is no other record of
  // it anywhere, so it must ride along with the cart it was entered against —
  // losing it on a reload silently rebills the customer at full price.
  // Optional and absent when zero: an older stored draft simply restores as
  // "nothing pending", which is exactly the pre-upgrade behaviour, so the
  // draft version stays 2 rather than orphaning in-flight version-2 recoveries.
  pendingCartDiscountMinor?: number;
  pendingCartPointsMinor?: number;
  /** Exact customer identity whose balance authorised pending cart-stage points. */
  pendingCartPointsCustomerPhone?: string;
  retry?: PosCheckoutRetry;
}

type KeyFactory = () => string;

const ORDER_TYPES = new Set<CheckoutOrderType>(['dine_in', 'takeaway', 'delivery']);
const DELIVERY_VIAS = new Set<CheckoutDeliveryVia>([
  'inhouse',
  'zomato',
  'swiggy',
  'ubereats',
  'other_aggregator',
]);
const PAYMENT_METHODS = new Set<CheckoutPaymentMethod>(['cash', 'upi', 'card', 'qr']);
const CHECKOUT_PHASES = new Set<CheckoutPhase>([
  'preparing_order',
  'awaiting_payment',
  'recording_payment',
  'finalizing_zero',
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonEmptyString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

// Money is whole paise: a stored draft carrying a float, a negative, or
// anything non-numeric is corrupt and restores as "nothing pending".
function positiveMinor(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : undefined;
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function hasInvalidOptionalNonEmptyString(value: unknown): boolean {
  return value !== undefined && !nonEmptyString(value);
}

function hasInvalidOptionalNonNegativeInteger(value: unknown): boolean {
  return value !== undefined && !isNonNegativeInteger(value);
}

export function createOperationKey(): string {
  const fallback = `${Date.now()}:${Math.random().toString(36).slice(2)}`;
  return globalThis.crypto?.randomUUID?.() ?? fallback;
}

export function isTableDraftHydratedForKey(
  draftKey: string | null,
  hydratedDraftKey: string | null,
): boolean {
  return Boolean(draftKey && draftKey === hydratedDraftKey);
}

export function hasLockedTableRetryOperation(draft: TableCartDraft | null): boolean {
  return Boolean(draft?.operation);
}

export function normalizeDraftLines(value: unknown): DraftLine[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((candidate) => {
    if (!isRecord(candidate)) return [];
    const itemId = nonEmptyString(candidate.itemId);
    const qty = candidate.qty;
    if (!itemId || typeof qty !== 'number' || !Number.isInteger(qty) || qty <= 0) return [];
    const note = typeof candidate.note === 'string' && candidate.note.trim()
      ? candidate.note.trim().slice(0, 500)
      : undefined;
    return [{ itemId, qty, note }];
  });
}

function sameLines(left: DraftLine[], right: DraftLine[]): boolean {
  return left.length === right.length && left.every((line, index) => (
    line.itemId === right[index]?.itemId
    && line.qty === right[index]?.qty
    && line.note === right[index]?.note
  ));
}

function normalizeTableOperation(value: unknown): TableRetryOperation | undefined {
  if (!isRecord(value)) return undefined;
  if (value.kind === 'create') {
    const shiftId = nonEmptyString(value.shiftId);
    return shiftId ? { kind: 'create', shiftId } : undefined;
  }
  if (value.kind === 'append') {
    const orderId = nonEmptyString(value.orderId);
    return orderId ? { kind: 'append', orderId } : undefined;
  }
  return undefined;
}

export function normalizeTableCartDraft(
  value: unknown,
  keyFactory: KeyFactory = createOperationKey,
): TableCartDraft | null {
  if (Array.isArray(value)) {
    const lines = normalizeDraftLines(value);
    return lines.length ? { version: 2, lines, operationKey: keyFactory() } : null;
  }
  if (!isRecord(value) || value.version !== 2) return null;
  const lines = normalizeDraftLines(value.lines);
  if (!lines.length) return null;
  const shiftId = nonEmptyString(value.shiftId);
  return {
    version: 2,
    lines,
    operationKey: nonEmptyString(value.operationKey) ?? keyFactory(),
    ...(shiftId ? { shiftId } : {}),
    operation: normalizeTableOperation(value.operation),
  };
}

export function replaceTableDraftLines(
  current: TableCartDraft | null,
  lines: DraftLine[],
  keyFactory: KeyFactory = createOperationKey,
): TableCartDraft | null {
  const normalized = normalizeDraftLines(lines);
  if (!normalized.length) return null;
  if (current && sameLines(current.lines, normalized)) return current;
  return {
    version: 2,
    lines: normalized,
    operationKey: keyFactory(),
    ...(current?.shiftId ? { shiftId: current.shiftId } : {}),
  };
}

export function beginTableRetryOperation(
  draft: TableCartDraft,
  operation: TableRetryOperation,
): TableCartDraft {
  return draft.operation ? draft : { ...draft, operation };
}

export function clearTableRetryOperation(draft: TableCartDraft): TableCartDraft {
  const editable = { ...draft };
  delete editable.operation;
  return editable;
}

export function tableIdempotencyKey(tableId: string, draft: TableCartDraft): string {
  if (draft.operation?.kind === 'append') {
    return `table-lines:${draft.operation.orderId}:${draft.operationKey}`;
  }
  return `table:${tableId}:${draft.operationKey}`;
}

function normalizeOrderType(value: unknown): CheckoutOrderType {
  return ORDER_TYPES.has(value as CheckoutOrderType) ? value as CheckoutOrderType : 'dine_in';
}

function normalizeDeliveryVia(value: unknown): CheckoutDeliveryVia {
  return DELIVERY_VIAS.has(value as CheckoutDeliveryVia) ? value as CheckoutDeliveryVia : 'inhouse';
}

function normalizeSnapshot(value: unknown): PosCheckoutSnapshot | undefined {
  if (!isRecord(value)) return undefined;
  const shiftId = nonEmptyString(value.shiftId);
  if (!Array.isArray(value.cart)) return undefined;
  const cart = normalizeDraftLines(value.cart);
  if (!shiftId || cart.length !== value.cart.length) return undefined;
  if (value.orderType !== undefined && !ORDER_TYPES.has(value.orderType as CheckoutOrderType)) {
    return undefined;
  }
  if (value.deliveryVia !== undefined && !DELIVERY_VIAS.has(value.deliveryVia as CheckoutDeliveryVia)) {
    return undefined;
  }
  if (value.deliveryStateCode !== undefined && typeof value.deliveryStateCode !== 'string') {
    return undefined;
  }
  if (value.customerName !== undefined && typeof value.customerName !== 'string') return undefined;
  if (value.customerPhone !== undefined && typeof value.customerPhone !== 'string') return undefined;
  return {
    shiftId,
    cart,
    orderType: normalizeOrderType(value.orderType),
    deliveryVia: normalizeDeliveryVia(value.deliveryVia),
    deliveryStateCode: typeof value.deliveryStateCode === 'string' ? value.deliveryStateCode : '32',
    customerName: typeof value.customerName === 'string' ? value.customerName : '',
    customerPhone: typeof value.customerPhone === 'string' ? value.customerPhone : '',
  };
}

function normalizeCheckoutRetry(value: unknown): PosCheckoutRetry | undefined {
  if (!isRecord(value)) return undefined;
  const key = nonEmptyString(value.key);
  const snapshot = normalizeSnapshot(value.snapshot);
  if (!key || !snapshot || !PAYMENT_METHODS.has(value.paymentMethod as CheckoutPaymentMethod)) {
    return undefined;
  }
  const integerFields = [
    value.paymentAmountMinor,
    value.orderTotalMinor,
    value.tipMinor,
    value.cashTenderedMinor,
    value.orderDiscountMinor,
    value.orderManualDiscountMinor,
    value.orderPointsRedeemedMinor,
    value.freeGamingMinutesApplied,
    value.freeHookahCountApplied,
    value.cartDiscountExpectedVersion,
    value.cartPointsExpectedVersion,
  ];
  if (integerFields.some(hasInvalidOptionalNonNegativeInteger)) return undefined;
  if (
    hasInvalidOptionalNonEmptyString(value.resumingOrderId)
    || hasInvalidOptionalNonEmptyString(value.pendingOrderId)
    || hasInvalidOptionalNonEmptyString(value.checkoutClaimToken)
    || hasInvalidOptionalNonEmptyString(value.checkoutClaimExpiresAt)
  ) return undefined;
  if (
    value.checkoutClaimOrderVersion !== undefined
    && (!Number.isInteger(value.checkoutClaimOrderVersion) || (value.checkoutClaimOrderVersion as number) <= 0)
  ) return undefined;
  if (
    value.checkoutClaimRequired !== undefined
    && typeof value.checkoutClaimRequired !== 'boolean'
  ) return undefined;
  const phase = value.phase === undefined
    // Version-2 drafts created by the earlier one-step checkout were saved
    // only after the cashier confirmed receipt of money.
    ? 'recording_payment'
    : CHECKOUT_PHASES.has(value.phase as CheckoutPhase)
      ? value.phase as CheckoutPhase
      : undefined;
  if (!phase) return undefined;

  const amount = value.paymentAmountMinor as number | undefined;
  const orderTotal = value.orderTotalMinor as number | undefined;
  const tip = value.tipMinor as number | undefined;
  const cashTendered = value.cashTenderedMinor as number | undefined;
  const orderDiscount = value.orderDiscountMinor as number | undefined;
  const orderManualDiscount = value.orderManualDiscountMinor as number | undefined;
  const orderPointsRedeemed = value.orderPointsRedeemedMinor as number | undefined;
  const freeGamingMinutes = value.freeGamingMinutesApplied as number | undefined;
  const freeHookahCount = value.freeHookahCountApplied as number | undefined;
  const pendingOrderId = nonEmptyString(value.pendingOrderId);
  const cartDiscountExpectedVersion = value.cartDiscountExpectedVersion as number | undefined;
  const cartPointsExpectedVersion = value.cartPointsExpectedVersion as number | undefined;
  const checkoutClaimToken = nonEmptyString(value.checkoutClaimToken);
  const checkoutClaimExpiresAt = nonEmptyString(value.checkoutClaimExpiresAt);
  const checkoutClaimOrderVersion = value.checkoutClaimOrderVersion as number | undefined;
  const hasAnyClaimCredential = Boolean(
    checkoutClaimToken || checkoutClaimExpiresAt || checkoutClaimOrderVersion,
  );
  const hasCompleteClaimCredential = Boolean(
    checkoutClaimToken && checkoutClaimExpiresAt && checkoutClaimOrderVersion,
  );
  if (checkoutClaimExpiresAt && !Number.isFinite(Date.parse(checkoutClaimExpiresAt))) return undefined;
  if (hasAnyClaimCredential && (!hasCompleteClaimCredential || value.checkoutClaimRequired !== true)) {
    return undefined;
  }
  if (
    value.checkoutClaimRequired === true
    && phase !== 'preparing_order'
    && !hasCompleteClaimCredential
  ) return undefined;

  const normalized: PosCheckoutRetry = {
    key,
    phase,
    paymentMethod: value.paymentMethod as CheckoutPaymentMethod,
    resumingOrderId: nonEmptyString(value.resumingOrderId),
    pendingOrderId,
    ...(cartDiscountExpectedVersion !== undefined ? { cartDiscountExpectedVersion } : {}),
    ...(cartPointsExpectedVersion !== undefined ? { cartPointsExpectedVersion } : {}),
    ...(orderTotal !== undefined ? { orderTotalMinor: orderTotal } : {}),
    ...(amount !== undefined ? { paymentAmountMinor: amount } : {}),
    ...(tip !== undefined ? { tipMinor: tip } : {}),
    ...(cashTendered !== undefined ? { cashTenderedMinor: cashTendered } : {}),
    ...(orderDiscount !== undefined ? { orderDiscountMinor: orderDiscount } : {}),
    ...(orderManualDiscount !== undefined ? { orderManualDiscountMinor: orderManualDiscount } : {}),
    ...(orderPointsRedeemed !== undefined ? { orderPointsRedeemedMinor: orderPointsRedeemed } : {}),
    ...(freeGamingMinutes !== undefined ? { freeGamingMinutesApplied: freeGamingMinutes } : {}),
    ...(freeHookahCount !== undefined ? { freeHookahCountApplied: freeHookahCount } : {}),
    ...(checkoutClaimToken ? { checkoutClaimToken } : {}),
    ...(checkoutClaimExpiresAt ? { checkoutClaimExpiresAt } : {}),
    ...(checkoutClaimOrderVersion ? { checkoutClaimOrderVersion } : {}),
    checkoutClaimRequired: value.checkoutClaimRequired === true,
    snapshot,
  };

  const hasCanonicalBalance = pendingOrderId !== undefined
    && orderTotal !== undefined
    && amount !== undefined
    && amount <= orderTotal;
  if (phase !== 'preparing_order' && !hasCanonicalBalance) return undefined;
  if (
    phase !== 'preparing_order'
    && (cartDiscountExpectedVersion !== undefined || cartPointsExpectedVersion !== undefined)
  ) return undefined;
  if (phase === 'recording_payment') {
    if (!amount || amount <= 0) return undefined;
    if (normalized.paymentMethod === 'cash' && cashTendered !== undefined) {
      if (cashTendered < amount + (tip ?? 0)) return undefined;
    } else if (normalized.paymentMethod !== 'cash' && cashTendered !== undefined) {
      return undefined;
    }
  }
  if (phase === 'finalizing_zero') {
    if (
      orderTotal !== 0
      || amount !== 0
      || !(
        (freeGamingMinutes ?? 0) > 0
        || (freeHookahCount ?? 0) > 0
        || (orderDiscount ?? 0) > 0
      )
    ) return undefined;
  }
  if (phase === 'awaiting_payment' && amount === 0) {
    if (
      orderTotal !== 0
      || !(
        (freeGamingMinutes ?? 0) > 0
        || (freeHookahCount ?? 0) > 0
        || (orderDiscount ?? 0) > 0
      )
    ) return undefined;
  }
  return normalized;
}

export function normalizePosRetryDraft(value: unknown): PosRetryDraft | null {
  if (!isRecord(value)) return null;
  // Versionless ordinary carts are the one supported legacy shape. A version
  // we do not understand, or a versionless financial retry, must stay locked
  // byte-for-byte instead of being rewritten without its settlement journal.
  if (value.version !== undefined && value.version !== 2) return null;
  if (
    value.version === undefined
    && (
      value.retry !== undefined
      || value.pendingCartDiscountMinor !== undefined
      || value.pendingCartPointsMinor !== undefined
      || value.pendingCartPointsCustomerPhone !== undefined
    )
  ) return null;
  if (!Array.isArray(value.cart)) return null;
  const cart = normalizeDraftLines(value.cart);
  // A filtered line may be the only evidence that an item was served. Never
  // normalize a damaged financial draft into an apparently empty safe cart.
  if (cart.length !== value.cart.length) return null;
  const pendingCartDiscountMinor = positiveMinor(value.pendingCartDiscountMinor);
  const pendingCartPointsMinor = positiveMinor(value.pendingCartPointsMinor);
  if (
    value.pendingCartDiscountMinor !== undefined
    && value.pendingCartDiscountMinor !== 0
    && pendingCartDiscountMinor === undefined
  ) return null;
  if (
    value.pendingCartPointsMinor !== undefined
    && value.pendingCartPointsMinor !== 0
    && pendingCartPointsMinor === undefined
  ) return null;
  const customerPhone = typeof value.customerPhone === 'string' ? value.customerPhone : '';
  if (value.customerPhone !== undefined && typeof value.customerPhone !== 'string') return null;
  if (value.customerName !== undefined && typeof value.customerName !== 'string') return null;
  if (value.deliveryStateCode !== undefined && typeof value.deliveryStateCode !== 'string') return null;
  if (value.orderType !== undefined && !ORDER_TYPES.has(value.orderType as CheckoutOrderType)) return null;
  if (value.deliveryVia !== undefined && !DELIVERY_VIAS.has(value.deliveryVia as CheckoutDeliveryVia)) return null;
  if (
    hasInvalidOptionalNonEmptyString(value.shiftId)
    || hasInvalidOptionalNonEmptyString(value.resumingOrderId)
  ) return null;
  const pendingCartPointsCustomerPhone = nonEmptyString(value.pendingCartPointsCustomerPhone);
  if (
    pendingCartPointsMinor
    && (
      !pendingCartPointsCustomerPhone
      || pendingCartPointsCustomerPhone.trim() !== customerPhone.trim()
    )
  ) return null;
  const normalized: PosRetryDraft = {
    version: 2,
    shiftId: nonEmptyString(value.shiftId),
    resumingOrderId: nonEmptyString(value.resumingOrderId),
    cart,
    orderType: normalizeOrderType(value.orderType),
    deliveryVia: normalizeDeliveryVia(value.deliveryVia),
    deliveryStateCode: typeof value.deliveryStateCode === 'string' ? value.deliveryStateCode : '32',
    customerName: typeof value.customerName === 'string' ? value.customerName : '',
    customerPhone,
    // Absent on every draft written before this field existed, which restores
    // as "nothing pending" — the same result those drafts produced before the
    // upgrade, so no version bump is needed to read them safely.
    ...(pendingCartDiscountMinor ? { pendingCartDiscountMinor } : {}),
    ...(pendingCartPointsMinor ? { pendingCartPointsMinor } : {}),
    ...(pendingCartPointsMinor && pendingCartPointsCustomerPhone
      ? { pendingCartPointsCustomerPhone }
      : {}),
  };
  if (value.version === 2 && value.retry !== undefined) {
    const retry = normalizeCheckoutRetry(value.retry);
    if (!retry) return null;
    if (
      retry.phase !== 'preparing_order'
      && (pendingCartDiscountMinor || pendingCartPointsMinor)
    ) return null;
    if (
      (retry.cartDiscountExpectedVersion !== undefined && !pendingCartDiscountMinor)
      || (retry.cartPointsExpectedVersion !== undefined && !pendingCartPointsMinor)
    ) return null;
    normalized.retry = retry;
  }
  return normalized;
}

export function applyCanonicalCheckoutBalance(
  retry: PosCheckoutRetry,
  order: {
    id: string;
    total_minor: number;
    due_minor: number;
    discount_minor?: number;
    manual_discount_minor?: number;
    points_redeemed_minor?: number;
    free_gaming_minutes_applied?: number;
    free_hookah_count_applied?: number;
  },
): PosCheckoutRetry {
  // Once the cashier has confirmed receipt of money, never replace the
  // journaled amount from a later GET. Only replaying the exact payment key
  // can establish whether that already-confirmed attempt committed.
  if (retry.phase === 'recording_payment' || retry.phase === 'finalizing_zero') return retry;
  return {
    ...retry,
    phase: 'awaiting_payment',
    pendingOrderId: order.id,
    orderTotalMinor: order.total_minor,
    paymentAmountMinor: order.due_minor,
    orderDiscountMinor: order.discount_minor ?? 0,
    orderManualDiscountMinor: order.manual_discount_minor ?? 0,
    orderPointsRedeemedMinor: order.points_redeemed_minor ?? 0,
    freeGamingMinutesApplied: order.free_gaming_minutes_applied ?? 0,
    freeHookahCountApplied: order.free_hookah_count_applied ?? 0,
  };
}

/**
 * Detect the committed result after a cart-stage adjustment response was lost.
 * The direct order starts with no manual adjustment, so an exact server value
 * proves the stable operation already committed and must not be replayed with
 * a newer optimistic-lock version.
 */
export function isCartStageDiscountAlreadyApplied(
  manualDiscountMinor: number | undefined,
  pendingDiscountMinor: number,
): boolean {
  return pendingDiscountMinor > 0 && manualDiscountMinor === pendingDiscountMinor;
}

export function isCartStagePointsAlreadyApplied(
  pointsRedeemedMinor: number | undefined,
  pendingPointsMinor: number,
): boolean {
  return pendingPointsMinor > 0 && pointsRedeemedMinor === pendingPointsMinor;
}

export function hasUnappliedCartStageBenefit(
  order: { manual_discount_minor?: number; points_redeemed_minor?: number },
  pendingDiscountMinor: number,
  pendingPointsMinor: number,
): boolean {
  return (
    pendingDiscountMinor > 0
    && !isCartStageDiscountAlreadyApplied(order.manual_discount_minor, pendingDiscountMinor)
  ) || (
    pendingPointsMinor > 0
    && !isCartStagePointsAlreadyApplied(order.points_redeemed_minor, pendingPointsMinor)
  );
}

export interface ReconciledCartStageBenefits {
  retry: PosCheckoutRetry;
  pendingDiscountMinor: number;
  pendingPointsMinor: number;
  pendingPointsCustomerPhone?: string;
  hasUnappliedBenefit: boolean;
  changed: boolean;
}

export type CartStageBenefitKind = 'discount' | 'points';

/**
 * Retire one cart-stage instruction immediately after its server response is
 * known to have committed. Callers persist this result before their next
 * network await so a later failure cannot resurrect the completed mutation.
 */
export function retireAppliedCartStageBenefit(
  retry: PosCheckoutRetry,
  kind: CartStageBenefitKind,
  pendingDiscountMinor: number,
  pendingPointsMinor: number,
  pendingPointsCustomerPhone?: string,
): ReconciledCartStageBenefits {
  const remainingDiscountMinor = kind === 'discount' ? 0 : pendingDiscountMinor;
  const remainingPointsMinor = kind === 'points' ? 0 : pendingPointsMinor;
  const reconciledRetry: PosCheckoutRetry = kind === 'discount'
    ? { ...retry, cartDiscountExpectedVersion: undefined }
    : { ...retry, cartPointsExpectedVersion: undefined };
  return {
    retry: reconciledRetry,
    pendingDiscountMinor: remainingDiscountMinor,
    pendingPointsMinor: remainingPointsMinor,
    ...(remainingPointsMinor > 0 && pendingPointsCustomerPhone
      ? { pendingPointsCustomerPhone }
      : {}),
    hasUnappliedBenefit: remainingDiscountMinor > 0 || remainingPointsMinor > 0,
    changed: true,
  };
}

/**
 * Reconcile a preparing journal after a discount/points response was lost.
 *
 * A matching value on the canonical order is proof that the stable mutation
 * committed.  Remove both its frozen optimistic version and its cart-stage
 * instruction before the order is claimed for payment.  Keeping either field
 * would make the resulting awaiting-payment draft invalid on the next reload,
 * which is exactly when recovery is most important.
 */
export function reconcileCartStageBenefitsAfterReload(
  retry: PosCheckoutRetry,
  order: { manual_discount_minor?: number; points_redeemed_minor?: number },
  pendingDiscountMinor: number,
  pendingPointsMinor: number,
  pendingPointsCustomerPhone?: string,
): ReconciledCartStageBenefits {
  if (retry.phase !== 'preparing_order') {
    return {
      retry,
      pendingDiscountMinor,
      pendingPointsMinor,
      ...(pendingPointsMinor > 0 && pendingPointsCustomerPhone
        ? { pendingPointsCustomerPhone }
        : {}),
      hasUnappliedBenefit: false,
      changed: false,
    };
  }

  const discountSettled = isCartStageDiscountAlreadyApplied(
    order.manual_discount_minor,
    pendingDiscountMinor,
  );
  const pointsSettled = isCartStagePointsAlreadyApplied(
    order.points_redeemed_minor,
    pendingPointsMinor,
  );
  const remainingDiscountMinor = discountSettled ? 0 : pendingDiscountMinor;
  const remainingPointsMinor = pointsSettled ? 0 : pendingPointsMinor;
  const reconciledRetry: PosCheckoutRetry = {
    ...retry,
    ...(discountSettled ? { cartDiscountExpectedVersion: undefined } : {}),
    ...(pointsSettled ? { cartPointsExpectedVersion: undefined } : {}),
  };

  return {
    retry: reconciledRetry,
    pendingDiscountMinor: remainingDiscountMinor,
    pendingPointsMinor: remainingPointsMinor,
    ...(remainingPointsMinor > 0 && pendingPointsCustomerPhone
      ? { pendingPointsCustomerPhone }
      : {}),
    hasUnappliedBenefit: hasUnappliedCartStageBenefit(
      order,
      remainingDiscountMinor,
      remainingPointsMinor,
    ),
    changed: discountSettled || pointsSettled,
  };
}

export function hasBenefitCoveredZeroBalance(
  retry: PosCheckoutRetry,
): retry is PosCheckoutRetry & {
  pendingOrderId: string;
  orderTotalMinor: 0;
  paymentAmountMinor: 0;
} {
  return Boolean(
    nonEmptyString(retry.pendingOrderId)
    && retry.orderTotalMinor === 0
    && retry.paymentAmountMinor === 0
    && (
      (retry.freeGamingMinutesApplied ?? 0) > 0
      || (retry.freeHookahCountApplied ?? 0) > 0
      || (retry.orderDiscountMinor ?? 0) > 0
    ),
  );
}

export function hasCollectibleCheckoutBalance(
  retry: PosCheckoutRetry,
): retry is PosCheckoutRetry & {
  pendingOrderId: string;
  orderTotalMinor: number;
  paymentAmountMinor: number;
} {
  const amount = retry.paymentAmountMinor;
  const total = retry.orderTotalMinor;
  return Boolean(
    nonEmptyString(retry.pendingOrderId)
    && typeof amount === 'number'
    && Number.isInteger(amount)
    && amount > 0
    && typeof total === 'number'
    && Number.isInteger(total)
    && total >= amount,
  );
}

export function buildCheckoutPaymentSubmission(
  retry: PosCheckoutRetry,
): CheckoutPaymentSubmission | null {
  const orderId = nonEmptyString(retry.pendingOrderId);
  if (
    retry.phase !== 'recording_payment'
    || !orderId
    || !hasCollectibleCheckoutBalance(retry)
  ) {
    return null;
  }
  const amount = retry.paymentAmountMinor;
  const total = retry.orderTotalMinor;
  // Additional money on top of the bill, never folded into amount_minor —
  // the server only matches amount_minor against the exact bill due (see
  // _validate_confirmed_payment_balance in app/api/v1/pos/router.py).
  const tip = Number.isInteger(retry.tipMinor) && (retry.tipMinor as number) >= 0
    ? (retry.tipMinor as number)
    : 0;
  const tendered = retry.paymentMethod === 'cash'
    ? retry.cashTenderedMinor ?? amount + tip
    : undefined;
  if (
    retry.paymentMethod === 'cash'
    && (!Number.isInteger(tendered) || (tendered as number) < amount + tip)
  ) {
    return null;
  }
  return {
    orderId,
    idempotencyKey: `payment:${retry.key}`,
    body: {
      method: retry.paymentMethod,
      amount_minor: amount,
      // Cash tendered must cover what is actually collected — bill + tip —
      // or the server's tendered-vs-collected check rejects it.
      ...(retry.paymentMethod === 'cash' ? { tendered_minor: tendered } : {}),
      expected_order_total_minor: total,
      expected_due_minor: amount,
      tip_minor: tip,
    },
  };
}

export function buildCheckoutZeroFinalization(
  retry: PosCheckoutRetry,
): CheckoutZeroFinalization | null {
  const orderId = nonEmptyString(retry.pendingOrderId);
  if (
    retry.phase !== 'finalizing_zero'
    || !orderId
    || !hasBenefitCoveredZeroBalance(retry)
  ) {
    return null;
  }
  return {
    orderId,
    idempotencyKey: `finalize-zero:${retry.key}`,
  };
}

export function isAmbiguousApiError(error: unknown): boolean {
  const code = (error as Error & { code?: string } | null)?.code;
  return code === 'network_error'
    || code === 'internal_error'
    || code === 'idempotency_in_progress';
}

/**
 * A deterministic server refusal (an oversized discount, a redemption larger
 * than the bill, …). Unlike an ambiguous failure, replaying the exact same
 * request can only fail the exact same way.
 */
export function isBusinessRuleApiError(error: unknown): boolean {
  return (error as Error & { code?: string } | null)?.code === 'business_rule';
}

const CHECKOUT_CLAIM_ERROR_CODES = new Set([
  'checkout_claim_required',
  'checkout_claim_conflict',
  'checkout_claim_expired',
  'checkout_claim_invalid',
  'checkout_claim_stale',
  'checkout_claim_unavailable',
]);

/**
 * A deterministic refusal that happened before the server wrote a Payment or
 * finalized a zero-value invoice. The cashier may already have confirmed the
 * physical payment, so callers must retain the original settlement key and
 * reconcile/reacquire the bill instead of asking the customer to pay again.
 */
export function isCheckoutClaimRejection(error: unknown): boolean {
  const code = (error as Error & { code?: string } | null)?.code;
  return typeof code === 'string' && CHECKOUT_CLAIM_ERROR_CODES.has(code);
}

// The two stale-bill guards in _validate_confirmed_payment_balance
// (app/api/v1/pos/router.py). Both are raised before the Payment row is
// created, and the request session rolls back on any error, so a payment
// refused this way recorded nothing at all — not even its idempotency
// reservation. Matched on the exact server message because every domain
// refusal shares the generic `business_rule` code; if the backend ever
// rewords them this stops matching and the caller falls back to the strict
// "result unknown" lock, never the other way around.
const STALE_CHECKOUT_BALANCE_MESSAGES = new Set([
  'Order total changed before payment. Reload the exact bill before collecting money.',
  'Order balance changed before payment. Reload the exact amount due before collecting money.',
]);

/**
 * A definitive refusal to record the payment because the submitted expected
 * bill no longer matches the server's. No money was taken, nothing is in
 * flight, and the same key can be replayed once the balance is refreshed.
 */
export function isStaleCheckoutBalanceRejection(error: unknown): boolean {
  const err = error as (Error & { code?: string }) | null;
  if (!err || !isBusinessRuleApiError(err)) return false;
  return STALE_CHECKOUT_BALANCE_MESSAGES.has(err.message);
}

export function shouldPreserveCheckoutRetry(
  error: unknown,
  retry: PosCheckoutRetry,
): boolean {
  return Boolean(retry.pendingOrderId) || isAmbiguousApiError(error);
}
