export interface PosDraftReconciliationState {
  storageConflict: boolean;
  unresolvedResumingOrderId: string | null;
  hasUnavailableItems: boolean;
  hasCheckoutRecovery: boolean;
  hasResumingOrder: boolean;
  cartLength: number;
  localWorkShiftId: string | null;
  currentShiftId: string | null;
}

/**
 * A true result means the POS may display the saved work, but must not mutate,
 * claim, prepare, discount, or settle it until an operator reconciles it.
 */
export function posDraftNeedsReconciliation({
  storageConflict,
  unresolvedResumingOrderId,
  hasUnavailableItems,
  hasCheckoutRecovery,
  hasResumingOrder,
  cartLength,
  localWorkShiftId,
  currentShiftId,
}: PosDraftReconciliationState): boolean {
  return Boolean(
    storageConflict
    || unresolvedResumingOrderId
    || (hasUnavailableItems && !hasCheckoutRecovery)
    || (hasResumingOrder && cartLength > 0)
    || (
      !hasCheckoutRecovery
      && (cartLength > 0 || hasResumingOrder)
      && (!localWorkShiftId || localWorkShiftId !== currentShiftId)
    ),
  );
}

/** A non-owner tab may perform GET hydration, but never rotate a checkout lease. */
export function mayClaimCheckoutDuringHydration(writerBlocked: boolean): boolean {
  return !writerBlocked;
}

/**
 * Only server-held bills are shared checkout work. A table-less `open` order
 * belongs to the client that created it (including older Android releases) and
 * must never be offered to another cashier as a payable recovery bill.
 */
export function isIncomingSharedPosOrder(status: string): boolean {
  return status === 'held';
}

export function canStageManualPosDiscount(
  effectivePermissions: readonly string[] | null | undefined,
): boolean {
  return effectivePermissions?.includes('pos.discount.large') === true;
}

export interface SynchronousPosFlowGate {
  current: boolean;
}

/** Acquire before generating an operation key; React state updates are not synchronous. */
export function enterSynchronousPosFlow(gate: SynchronousPosFlowGate): boolean {
  if (gate.current) return false;
  gate.current = true;
  return true;
}

export function leaveSynchronousPosFlow(gate: SynchronousPosFlowGate): void {
  gate.current = false;
}

/**
 * A canonical server cancellation does not make a stale browser recovery copy
 * safe to forget. Release the visible checkout only after its owned recovery
 * draft was removed, otherwise a reload can resurrect misleading local work.
 */
export function mayReleaseCancelledPreparedBill(
  hasDraftKey: boolean,
  draftClearSucceeded: boolean,
): boolean {
  return !hasDraftKey || draftClearSucceeded;
}

export function isCurrentPosCustomerLookup({
  requestGeneration,
  currentGeneration,
  requestedPhone,
  currentPhone,
}: {
  requestGeneration: number;
  currentGeneration: number;
  requestedPhone: string;
  currentPhone: string;
}): boolean {
  return requestGeneration === currentGeneration
    && requestedPhone.trim() === currentPhone.trim();
}

interface CartQuantityLine {
  item: { id: string };
  qty: number;
}

/** Pure cart transition so the caller can durably commit an empty-cart delete first. */
export function adjustPosCart<T extends CartQuantityLine>(
  cart: readonly T[],
  itemId: string,
  delta: number,
): T[] {
  return cart
    .map((line) => line.item.id === itemId
      ? { ...line, qty: Math.max(0, line.qty + delta) }
      : line)
    .filter((line) => line.qty > 0) as T[];
}
