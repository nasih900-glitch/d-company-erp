import { describe, expect, it } from 'vitest';

import {
  adjustPosCart,
  canStageManualPosDiscount,
  enterSynchronousPosFlow,
  isIncomingSharedPosOrder,
  isCurrentPosCustomerLookup,
  leaveSynchronousPosFlow,
  mayClaimCheckoutDuringHydration,
  posDraftNeedsReconciliation,
} from './pos-draft-policy';

const safeState = {
  storageConflict: false,
  unresolvedResumingOrderId: null,
  hasUnavailableItems: false,
  hasCheckoutRecovery: false,
  hasResumingOrder: false,
  cartLength: 1,
  localWorkShiftId: 'shift-a',
  currentShiftId: 'shift-a',
};

describe('POS draft safety policy', () => {
  it('locks cross-tab, unresolved-order, deleted-item, and shift-conflict drafts', () => {
    expect(posDraftNeedsReconciliation({ ...safeState, storageConflict: true })).toBe(true);
    expect(posDraftNeedsReconciliation({
      ...safeState,
      unresolvedResumingOrderId: 'order-a',
      cartLength: 0,
    })).toBe(true);
    expect(posDraftNeedsReconciliation({ ...safeState, hasUnavailableItems: true })).toBe(true);
    expect(posDraftNeedsReconciliation({ ...safeState, currentShiftId: 'shift-b' })).toBe(true);
    expect(posDraftNeedsReconciliation({
      ...safeState,
      hasResumingOrder: true,
      cartLength: 1,
    })).toBe(true);
    expect(posDraftNeedsReconciliation(safeState)).toBe(false);
  });

  it('allows a server-backed checkout recovery to retain unavailable snapshots', () => {
    expect(posDraftNeedsReconciliation({
      ...safeState,
      hasUnavailableItems: true,
      hasCheckoutRecovery: true,
      currentShiftId: null,
    })).toBe(false);
  });

  it('forbids a read-only second tab from claiming or rotating checkout ownership', () => {
    expect(mayClaimCheckoutDuringHydration(true)).toBe(false);
    expect(mayClaimCheckoutDuringHydration(false)).toBe(true);
  });

  it('admits exactly one financial flow before React can rerender', () => {
    const gate = { current: false };

    expect(enterSynchronousPosFlow(gate)).toBe(true);
    expect(enterSynchronousPosFlow(gate)).toBe(false);
    leaveSynchronousPosFlow(gate);
    expect(enterSynchronousPosFlow(gate)).toBe(true);
  });

  it('stages manual discounts only with the exact backend permission', () => {
    expect(canStageManualPosDiscount(['pos.read', 'pos.write'])).toBe(false);
    expect(canStageManualPosDiscount(undefined)).toBe(false);
    expect(canStageManualPosDiscount(['pos.discount.large'])).toBe(true);
  });

  it('ignores a customer lookup after the phone or generation changes', () => {
    expect(isCurrentPosCustomerLookup({
      requestGeneration: 4,
      currentGeneration: 4,
      requestedPhone: ' 9999999999 ',
      currentPhone: '9999999999',
    })).toBe(true);
    expect(isCurrentPosCustomerLookup({
      requestGeneration: 4,
      currentGeneration: 5,
      requestedPhone: '9999999999',
      currentPhone: '8888888888',
    })).toBe(false);
  });

  it('never exposes a private open counter bill to another cashier', () => {
    expect(isIncomingSharedPosOrder('held')).toBe(true);
    expect(isIncomingSharedPosOrder('open')).toBe(false);
    expect(isIncomingSharedPosOrder('paid')).toBe(false);
    expect(isIncomingSharedPosOrder('void')).toBe(false);
  });

  it('identifies the normal minus/trash transition that empties the cart', () => {
    const oneLine = [{ item: { id: 'drink' }, qty: 1 }];
    expect(adjustPosCart(oneLine, 'drink', -1)).toEqual([]);
    expect(adjustPosCart(oneLine, 'drink', 1)).toEqual([
      { item: { id: 'drink' }, qty: 2 },
    ]);
  });
});
