import { describe, expect, it } from 'vitest';

import type { PosPaymentBundleDTO } from '@/lib/erp-api';
import type { CheckoutPaymentBundleSubmission } from '@/lib/retry-drafts';
import { verifiedSplitPaymentReceipt } from './split-payment-receipt';

const submission: CheckoutPaymentBundleSubmission = {
  orderId: 'order-1',
  idempotencyKey: 'payment-bundle:attempt-1',
  body: {
    payments: [
      { method: 'cash', amount_minor: 1_700, tendered_minor: 2_000 },
      { method: 'upi', amount_minor: 2_500, ref_external: 'upi-ref-1' },
    ],
    expected_order_total_minor: 4_200,
    expected_due_minor: 4_200,
  },
};

function bundle(overrides: Partial<PosPaymentBundleDTO> = {}): PosPaymentBundleDTO {
  return {
    order_id: 'order-1',
    shift_id: 'shift-1',
    payments: [
      {
        id: 'payment-cash',
        method: 'cash',
        amount_minor: 1_700,
        tendered_minor: 2_000,
        change_minor: 300,
        ref_external: null,
        paid_at: '2026-09-21T12:00:00Z',
      },
      {
        id: 'payment-upi',
        method: 'upi',
        amount_minor: 2_500,
        tendered_minor: null,
        change_minor: null,
        ref_external: 'upi-ref-1',
        paid_at: '2026-09-21T12:00:00Z',
      },
    ],
    total_amount_minor: 4_200,
    payment_breakdown_minor: {
      cash: 1_700,
      card: 0,
      upi: 2_500,
      qr: 0,
      wallet: 0,
    },
    order_status: 'paid',
    invoice_no: 'INV-1',
    fiscal_year: '2026-27',
    invoice_issued_at: '2026-09-21T12:00:00Z',
    ...overrides,
  };
}

describe('verified split-payment receipt', () => {
  it('returns cloned authoritative rails when they exactly match the durable plan', () => {
    const result = bundle();
    const receipt = verifiedSplitPaymentReceipt(result, submission, 'shift-1');

    expect(receipt).toEqual(result.payments);
    expect(receipt).not.toBe(result.payments);
    expect(receipt?.[0]).not.toBe(result.payments[0]);
  });

  it.each([
    ['wrong order', { order_id: 'order-2' }],
    ['wrong shift', { shift_id: 'shift-2' }],
    ['wrong total', { total_amount_minor: 4_201 }],
    ['not paid', { order_status: 'held' }],
    ['missing invoice', { invoice_no: null }],
  ] as const)('rejects %s metadata', (_label, override) => {
    expect(verifiedSplitPaymentReceipt(bundle(override), submission, 'shift-1')).toBeNull();
  });

  it('rejects altered, duplicate, or unsafe payment rails', () => {
    const canonical = bundle();
    const wrongAmount = canonical.payments.map((payment, index) => (
      index === 0 ? { ...payment, amount_minor: 1_701 } : payment
    ));
    const wrongReference = canonical.payments.map((payment, index) => (
      index === 1 ? { ...payment, ref_external: 'different' } : payment
    ));
    const duplicate = [canonical.payments[0], {
      ...canonical.payments[1],
      method: 'cash' as const,
    }];
    const unsafe = canonical.payments.map((payment, index) => (
      index === 0 ? { ...payment, amount_minor: Number.MAX_SAFE_INTEGER + 1 } : payment
    ));

    expect(verifiedSplitPaymentReceipt(bundle({ payments: wrongAmount }), submission, 'shift-1')).toBeNull();
    expect(verifiedSplitPaymentReceipt(bundle({ payments: wrongReference }), submission, 'shift-1')).toBeNull();
    expect(verifiedSplitPaymentReceipt(bundle({ payments: duplicate }), submission, 'shift-1')).toBeNull();
    expect(verifiedSplitPaymentReceipt(bundle({ payments: unsafe }), submission, 'shift-1')).toBeNull();
  });

  it('rejects incorrect cash tender or change and non-cash tender fields', () => {
    const canonical = bundle();
    const wrongTender = canonical.payments.map((payment, index) => (
      index === 0 ? { ...payment, tendered_minor: 1_900 } : payment
    ));
    const wrongChange = canonical.payments.map((payment, index) => (
      index === 0 ? { ...payment, change_minor: 299 } : payment
    ));
    const upiTender = canonical.payments.map((payment, index) => (
      index === 1 ? { ...payment, tendered_minor: 2_500 } : payment
    ));

    expect(verifiedSplitPaymentReceipt(bundle({ payments: wrongTender }), submission, 'shift-1')).toBeNull();
    expect(verifiedSplitPaymentReceipt(bundle({ payments: wrongChange }), submission, 'shift-1')).toBeNull();
    expect(verifiedSplitPaymentReceipt(bundle({ payments: upiTender }), submission, 'shift-1')).toBeNull();
  });
});
