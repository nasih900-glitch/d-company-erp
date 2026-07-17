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
  orderTotalMinor?: number;
  paymentAmountMinor?: number;
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
  const cart = normalizeDraftLines(value.cart);
  if (!shiftId) return undefined;
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
  const amount = value.paymentAmountMinor;
  const orderTotal = value.orderTotalMinor;
  const orderDiscount = value.orderDiscountMinor;
  const orderManualDiscount = value.orderManualDiscountMinor;
  const orderPointsRedeemed = value.orderPointsRedeemedMinor;
  const freeGamingMinutes = value.freeGamingMinutesApplied;
  const freeHookahCount = value.freeHookahCountApplied;
  const pendingOrderId = nonEmptyString(value.pendingOrderId);
  const phase = CHECKOUT_PHASES.has(value.phase as CheckoutPhase)
    ? value.phase as CheckoutPhase
    // Version-2 drafts created by the earlier one-step checkout were saved
    // only after the cashier confirmed receipt of money.
    : 'recording_payment';
  return {
    key,
    phase,
    paymentMethod: value.paymentMethod as CheckoutPaymentMethod,
    resumingOrderId: nonEmptyString(value.resumingOrderId),
    pendingOrderId,
    orderTotalMinor: typeof orderTotal === 'number'
      && Number.isInteger(orderTotal)
      && orderTotal >= 0
      ? orderTotal
      : undefined,
    paymentAmountMinor: typeof amount === 'number' && Number.isInteger(amount) && amount >= 0
      ? amount
      : undefined,
    ...(typeof orderDiscount === 'number'
      && Number.isInteger(orderDiscount)
      && orderDiscount >= 0
      ? { orderDiscountMinor: orderDiscount }
      : {}),
    ...(typeof orderManualDiscount === 'number'
      && Number.isInteger(orderManualDiscount)
      && orderManualDiscount >= 0
      ? { orderManualDiscountMinor: orderManualDiscount }
      : {}),
    ...(typeof orderPointsRedeemed === 'number'
      && Number.isInteger(orderPointsRedeemed)
      && orderPointsRedeemed >= 0
      ? { orderPointsRedeemedMinor: orderPointsRedeemed }
      : {}),
    ...(typeof freeGamingMinutes === 'number'
      && Number.isInteger(freeGamingMinutes)
      && freeGamingMinutes >= 0
      ? { freeGamingMinutesApplied: freeGamingMinutes }
      : {}),
    ...(typeof freeHookahCount === 'number'
      && Number.isInteger(freeHookahCount)
      && freeHookahCount >= 0
      ? { freeHookahCountApplied: freeHookahCount }
      : {}),
    snapshot,
  };
}

export function normalizePosRetryDraft(value: unknown): PosRetryDraft | null {
  if (!isRecord(value)) return null;
  const cart = normalizeDraftLines(value.cart);
  const normalized: PosRetryDraft = {
    version: 2,
    shiftId: nonEmptyString(value.shiftId),
    resumingOrderId: nonEmptyString(value.resumingOrderId),
    cart,
    orderType: normalizeOrderType(value.orderType),
    deliveryVia: normalizeDeliveryVia(value.deliveryVia),
    deliveryStateCode: typeof value.deliveryStateCode === 'string' ? value.deliveryStateCode : '32',
    customerName: typeof value.customerName === 'string' ? value.customerName : '',
    customerPhone: typeof value.customerPhone === 'string' ? value.customerPhone : '',
  };
  if (value.version === 2) normalized.retry = normalizeCheckoutRetry(value.retry);
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
  return {
    orderId,
    idempotencyKey: `payment:${retry.key}`,
    body: {
      method: retry.paymentMethod,
      amount_minor: amount,
      ...(retry.paymentMethod === 'cash' ? { tendered_minor: amount } : {}),
      expected_order_total_minor: total,
      expected_due_minor: amount,
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

export function shouldPreserveCheckoutRetry(
  error: unknown,
  retry: PosCheckoutRetry,
): boolean {
  return Boolean(retry.pendingOrderId) || isAmbiguousApiError(error);
}
