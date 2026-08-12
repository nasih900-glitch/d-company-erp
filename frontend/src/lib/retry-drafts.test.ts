import { describe, expect, it } from 'vitest';

import {
  applyCanonicalCheckoutBalance,
  beginTableRetryOperation,
  buildCheckoutPaymentSubmission,
  buildCheckoutZeroFinalization,
  clearTableRetryOperation,
  hasLockedTableRetryOperation,
  hasBenefitCoveredZeroBalance,
  hasCollectibleCheckoutBalance,
  isStaleCheckoutBalanceRejection,
  isTableDraftHydratedForKey,
  normalizePosRetryDraft,
  normalizeTableCartDraft,
  replaceTableDraftLines,
  shouldPreserveCheckoutRetry,
  tableIdempotencyKey,
  type PosRetryDraft,
} from './retry-drafts';

describe('table cart retry drafts', () => {
  it('hydrates writes only for the exact current table draft key', () => {
    expect(isTableDraftHydratedForKey('table-b', 'table-a')).toBe(false);
    expect(isTableDraftHydratedForKey('table-b', null)).toBe(false);
    expect(isTableDraftHydratedForKey('table-b', 'table-b')).toBe(true);
  });

  it('migrates a legacy cart and gives it one stable operation key', () => {
    const draft = normalizeTableCartDraft([{ itemId: 'tea', qty: 2 }], () => 'stable-key');

    expect(draft).toEqual({
      version: 2,
      lines: [{ itemId: 'tea', qty: 2 }],
      operationKey: 'stable-key',
    });
    expect(normalizeTableCartDraft(JSON.parse(JSON.stringify(draft)), () => 'wrong-key'))
      .toEqual(draft);
  });

  it('keeps the key for the same cart and rotates it only when the cart changes', () => {
    const original = normalizeTableCartDraft([{ itemId: 'tea', qty: 1 }], () => 'first')!;
    const inFlight = beginTableRetryOperation(original, { kind: 'create', shiftId: 'shift-1' });

    expect(replaceTableDraftLines(inFlight, [{ itemId: 'tea', qty: 1 }], () => 'second'))
      .toBe(inFlight);
    expect(replaceTableDraftLines(inFlight, [{ itemId: 'tea', qty: 2 }], () => 'second'))
      .toEqual({
        version: 2,
        lines: [{ itemId: 'tea', qty: 2 }],
        operationKey: 'second',
      });
  });

  it('persists preparation notes and rotates the key when a note changes', () => {
    const original = normalizeTableCartDraft(
      [{ itemId: 'tea', qty: 1, note: 'No sugar' }],
      () => 'first',
    )!;

    expect(original.lines).toEqual([{ itemId: 'tea', qty: 1, note: 'No sugar' }]);
    expect(replaceTableDraftLines(
      original,
      [{ itemId: 'tea', qty: 1, note: 'Oat milk' }],
      () => 'second',
    )).toMatchObject({
      operationKey: 'second',
      lines: [{ itemId: 'tea', qty: 1, note: 'Oat milk' }],
    });
  });

  it('replays the original create endpoint even if an order is discovered after refresh', () => {
    const original = normalizeTableCartDraft([{ itemId: 'tea', qty: 1 }], () => 'op-1')!;
    const createRetry = beginTableRetryOperation(original, { kind: 'create', shiftId: 'shift-1' });
    const restored = normalizeTableCartDraft(JSON.parse(JSON.stringify(createRetry)))!;
    const stillCreate = beginTableRetryOperation(restored, { kind: 'append', orderId: 'discovered-order' });

    expect(stillCreate.operation).toEqual({ kind: 'create', shiftId: 'shift-1' });
    expect(tableIdempotencyKey('table-1', stillCreate)).toBe('table:table-1:op-1');
    expect(clearTableRetryOperation(stillCreate).operation).toBeUndefined();
  });

  it('keeps every in-flight table operation locked even when no lines are visible', () => {
    const original = normalizeTableCartDraft(
      [{ itemId: 'removed-menu-item', qty: 1 }],
      () => 'op-1',
    )!;
    const inFlight = beginTableRetryOperation(
      original,
      { kind: 'append', orderId: 'table-order-1' },
    );

    expect(hasLockedTableRetryOperation(inFlight)).toBe(true);
    expect(hasLockedTableRetryOperation(clearTableRetryOperation(inFlight))).toBe(false);
    expect(hasLockedTableRetryOperation(null)).toBe(false);
  });

  it('keeps fully scoped append keys within the widened database contract', () => {
    const draft = beginTableRetryOperation(
      normalizeTableCartDraft(
        [{ itemId: 'tea', qty: 1 }],
        () => '12345678-1234-1234-1234-123456789012',
      )!,
      { kind: 'append', orderId: '87654321-4321-4321-4321-210987654321' },
    );
    const key = tableIdempotencyKey('table-1', draft);

    expect(key.length).toBeGreaterThan(80);
    expect(key.length).toBeLessThanOrEqual(160);
  });
});

describe('POS checkout retry drafts', () => {
  const retryDraft: PosRetryDraft = {
    version: 2,
    resumingOrderId: 'held-1',
    cart: [{ itemId: 'tea', qty: 2 }],
    orderType: 'dine_in',
    deliveryVia: 'inhouse',
    deliveryStateCode: '32',
    customerName: 'Nasih',
    customerPhone: '9999999999',
    retry: {
      key: 'checkout-1',
      phase: 'awaiting_payment',
      paymentMethod: 'upi',
      resumingOrderId: 'held-1',
      pendingOrderId: 'order-1',
      orderTotalMinor: 42000,
      paymentAmountMinor: 42000,
      snapshot: {
        shiftId: 'shift-1',
        cart: [{ itemId: 'tea', qty: 2 }],
        orderType: 'dine_in',
        deliveryVia: 'inhouse',
        deliveryStateCode: '32',
        customerName: 'Nasih',
        customerPhone: '9999999999',
      },
    },
  };

  it('migrates a legacy draft without inventing a checkout in progress', () => {
    const restored = normalizePosRetryDraft({
      cart: [{ itemId: 'tea', qty: 1 }],
      orderType: 'takeaway',
      deliveryVia: 'inhouse',
      deliveryStateCode: '32',
      customerName: '',
      customerPhone: '',
    });

    expect(restored).toMatchObject({ version: 2, orderType: 'takeaway' });
    expect(restored?.retry).toBeUndefined();
  });

  it('round-trips the key, request snapshot, resuming order, and pending order', () => {
    expect(normalizePosRetryDraft(JSON.parse(JSON.stringify(retryDraft)))).toEqual(retryDraft);
  });

  it('round-trips a discount and points entered before the order existed', () => {
    const cartStageDraft: PosRetryDraft = {
      ...retryDraft,
      resumingOrderId: undefined,
      retry: undefined,
      pendingCartDiscountMinor: 5000,
      pendingCartPointsMinor: 2500,
    };

    expect(normalizePosRetryDraft(JSON.parse(JSON.stringify(cartStageDraft))))
      .toEqual(cartStageDraft);
  });

  it('restores a draft saved before cart-stage benefits were journalled', () => {
    const restored = normalizePosRetryDraft(JSON.parse(JSON.stringify(retryDraft)));

    expect(restored?.pendingCartDiscountMinor).toBeUndefined();
    expect(restored?.pendingCartPointsMinor).toBeUndefined();
    expect(restored?.retry?.key).toBe('checkout-1');
  });

  it('drops a corrupt cart-stage discount instead of restoring bad money', () => {
    const corrupt = normalizePosRetryDraft({
      ...JSON.parse(JSON.stringify(retryDraft)),
      pendingCartDiscountMinor: 12.5,
      pendingCartPointsMinor: -2500,
    });

    expect(corrupt?.pendingCartDiscountMinor).toBeUndefined();
    expect(corrupt?.pendingCartPointsMinor).toBeUndefined();
  });

  it('treats an older interrupted one-step checkout as payment-confirmed recovery', () => {
    const legacyRetry = JSON.parse(JSON.stringify(retryDraft));
    delete legacyRetry.retry.phase;

    expect(normalizePosRetryDraft(legacyRetry)?.retry?.phase).toBe('recording_payment');
  });

  it('preserves retry state for an ambiguous response or a known pending order', () => {
    const networkError = Object.assign(new Error('connection lost'), { code: 'network_error' });
    const serverError = Object.assign(new Error('server failed after processing'), { code: 'internal_error' });
    const inProgress = Object.assign(new Error('request is still committing'), {
      code: 'idempotency_in_progress',
    });
    const controlledError = Object.assign(new Error('invalid request'), { code: 'invalid_request' });

    expect(shouldPreserveCheckoutRetry(networkError, retryDraft.retry!)).toBe(true);
    expect(shouldPreserveCheckoutRetry(inProgress, {
      ...retryDraft.retry!,
      pendingOrderId: undefined,
    })).toBe(true);
    expect(shouldPreserveCheckoutRetry(serverError, {
      ...retryDraft.retry!,
      pendingOrderId: undefined,
    })).toBe(true);
    expect(shouldPreserveCheckoutRetry(controlledError, retryDraft.retry!)).toBe(true);
    expect(shouldPreserveCheckoutRetry(controlledError, {
      ...retryDraft.retry!,
      pendingOrderId: undefined,
    })).toBe(false);
  });

  it('recognises only the stale-bill refusals that recorded no payment', () => {
    const staleTotal = Object.assign(
      new Error('Order total changed before payment. Reload the exact bill before collecting money.'),
      { code: 'business_rule' },
    );
    const staleDue = Object.assign(
      new Error('Order balance changed before payment. Reload the exact amount due before collecting money.'),
      { code: 'business_rule' },
    );
    const otherRefusal = Object.assign(new Error('cannot pay an order in status=paid'), {
      code: 'business_rule',
    });
    const ambiguous = Object.assign(new Error('connection lost'), { code: 'network_error' });

    expect(isStaleCheckoutBalanceRejection(staleTotal)).toBe(true);
    expect(isStaleCheckoutBalanceRejection(staleDue)).toBe(true);
    expect(isStaleCheckoutBalanceRejection(otherRefusal)).toBe(false);
    expect(isStaleCheckoutBalanceRejection(ambiguous)).toBe(false);
    // Same text, but a transport failure never proves the server rejected it.
    expect(isStaleCheckoutBalanceRejection(
      Object.assign(new Error(staleTotal.message), { code: 'network_error' }),
    )).toBe(false);
    expect(isStaleCheckoutBalanceRejection(null)).toBe(false);
  });

  it('uses a fresh canonical balance before collection but never rewrites a confirmed attempt', () => {
    const awaiting = applyCanonicalCheckoutBalance(retryDraft.retry!, {
      id: 'order-1',
      total_minor: 50000,
      due_minor: 8000,
    });
    expect(awaiting).toMatchObject({
      phase: 'awaiting_payment',
      pendingOrderId: 'order-1',
      orderTotalMinor: 50000,
      paymentAmountMinor: 8000,
    });

    const recording = { ...awaiting, phase: 'recording_payment' as const };
    expect(applyCanonicalCheckoutBalance(recording, {
      id: 'order-1',
      total_minor: 50000,
      due_minor: 0,
    })).toBe(recording);

    const finalizingZero = {
      ...awaiting,
      phase: 'finalizing_zero' as const,
      orderTotalMinor: 0,
      paymentAmountMinor: 0,
      freeGamingMinutesApplied: 30,
    };
    expect(applyCanonicalCheckoutBalance(finalizingZero, {
      id: 'order-1',
      total_minor: 50000,
      due_minor: 50000,
    })).toBe(finalizingZero);
  });

  it('builds one exact full-balance payment replay and rejects incomplete journals', () => {
    const recording = {
      ...retryDraft.retry!,
      phase: 'recording_payment' as const,
      orderTotalMinor: 50000,
      paymentAmountMinor: 8000,
    };
    expect(buildCheckoutPaymentSubmission(recording)).toEqual({
      orderId: 'order-1',
      idempotencyKey: 'payment:checkout-1',
      body: {
        method: 'upi',
        amount_minor: 8000,
        expected_order_total_minor: 50000,
        expected_due_minor: 8000,
        tip_minor: 0,
      },
    });
    expect(buildCheckoutPaymentSubmission({
      ...recording,
      paymentMethod: 'cash',
    })?.body.tendered_minor).toBe(8000);
    // Tip is additional money on top of the bill, never folded into
    // amount_minor — but cash tendered must cover the full collected amount.
    expect(buildCheckoutPaymentSubmission({
      ...recording,
      tipMinor: 1500,
    })).toEqual({
      orderId: 'order-1',
      idempotencyKey: 'payment:checkout-1',
      body: {
        method: 'upi',
        amount_minor: 8000,
        expected_order_total_minor: 50000,
        expected_due_minor: 8000,
        tip_minor: 1500,
      },
    });
    expect(buildCheckoutPaymentSubmission({
      ...recording,
      paymentMethod: 'cash',
      tipMinor: 1500,
    })?.body.tendered_minor).toBe(9500);
    expect(buildCheckoutPaymentSubmission({
      ...recording,
      phase: 'awaiting_payment',
    })).toBeNull();
    expect(buildCheckoutPaymentSubmission({
      ...recording,
      paymentAmountMinor: 0,
    })).toBeNull();
    expect(buildCheckoutPaymentSubmission({
      ...recording,
      orderTotalMinor: 7000,
    })).toBeNull();
    expect(hasCollectibleCheckoutBalance(recording)).toBe(true);
    expect(hasCollectibleCheckoutBalance({
      ...recording,
      paymentAmountMinor: 0,
    })).toBe(false);
  });

  it('finalizes only a benefit-backed exact-zero bill and never invents a payment', () => {
    const canonical = applyCanonicalCheckoutBalance(retryDraft.retry!, {
      id: 'order-1',
      total_minor: 0,
      due_minor: 0,
      free_gaming_minutes_applied: 30,
      free_hookah_count_applied: 0,
    });
    expect(hasBenefitCoveredZeroBalance(canonical)).toBe(true);
    expect(buildCheckoutPaymentSubmission({
      ...canonical,
      phase: 'recording_payment',
    })).toBeNull();
    expect(buildCheckoutZeroFinalization({
      ...canonical,
      phase: 'finalizing_zero',
    })).toEqual({
      orderId: 'order-1',
      idempotencyKey: 'finalize-zero:checkout-1',
    });
    expect(buildCheckoutZeroFinalization({
      ...canonical,
      phase: 'awaiting_payment',
    })).toBeNull();
    expect(hasBenefitCoveredZeroBalance({
      ...canonical,
      freeGamingMinutesApplied: 0,
    })).toBe(false);

    const percentageCovered = applyCanonicalCheckoutBalance(retryDraft.retry!, {
      id: 'order-2',
      total_minor: 0,
      due_minor: 0,
      discount_minor: 12_000,
      free_gaming_minutes_applied: 0,
      free_hookah_count_applied: 0,
    });
    expect(hasBenefitCoveredZeroBalance(percentageCovered)).toBe(true);
    expect(buildCheckoutZeroFinalization({
      ...percentageCovered,
      phase: 'finalizing_zero',
    })).toEqual({
      orderId: 'order-2',
      idempotencyKey: 'finalize-zero:checkout-1',
    });
  });
});
