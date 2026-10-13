import { describe, expect, it } from 'vitest';

import type { OrderListItemDTO, ReceiptHistoryDTO } from '@/lib/erp-api';
import { matchesVerifiedExactRefundTarget, receiptToRefundOrder } from './RefundsScreen';

describe('exact receipt refund handoff', () => {
  it('preserves the authoritative reservation and exact bill identity', () => {
    const receipt = {
      order_id: 'order-older-than-500',
      invoice_no: 'DC/26-27/000042',
      order_type: 'session',
      status: 'paid',
      table_id: null,
      total_minor: 9_000,
      paid_minor: 9_000,
      refunded_minor: 2_000,
      pending_refund_minor: 3_000,
      refundable_minor: 4_000,
      customer_name: 'Asha',
      created_at: '2026-10-01T10:00:00Z',
      held_at: '2026-10-01T11:00:00Z',
      checkout_version: 12,
      lines: [
        { id: 'live', voided_at: null },
        { id: 'voided', voided_at: '2026-10-01T10:30:00Z' },
      ],
      payments: [
        { method: 'upi' },
        { method: 'upi' },
        { method: 'cash' },
      ],
      gaming_sessions: [{ station_name: 'Dual 1' }],
    } as ReceiptHistoryDTO;

    expect(receiptToRefundOrder(receipt)).toEqual(expect.objectContaining({
      id: 'order-older-than-500',
      invoice_no: 'DC/26-27/000042',
      source_label: 'Dual 1',
      checkout_version: 12,
      items_count: 1,
      paid_minor: 9_000,
      pending_refund_minor: 3_000,
      refundable_minor: 4_000,
      payment_methods: ['upi', 'cash'],
    }));
  });

  it('requires the same verified target, workspace and authoritative balance', () => {
    const order = {
      id: 'order-a',
      checkout_version: 7,
      refundable_minor: 4_000,
      pending_refund_minor: 0,
    } as OrderListItemDTO;
    const scope = { companyId: 'company-a', branchId: 'branch-a', terminalId: 'terminal-a' };
    const verified = {
      orderId: 'order-a',
      companyId: 'company-a',
      branchId: 'branch-a',
      terminalId: 'terminal-a',
      checkoutVersion: 7,
      refundableMinor: 4_000,
      pendingRefundMinor: 0,
    };

    expect(matchesVerifiedExactRefundTarget(order, verified, scope)).toBe(true);
    expect(matchesVerifiedExactRefundTarget(order, { ...verified, orderId: 'order-b' }, scope)).toBe(false);
    expect(matchesVerifiedExactRefundTarget(order, verified, { ...scope, terminalId: 'terminal-b' })).toBe(false);
    expect(matchesVerifiedExactRefundTarget(
      { ...order, pending_refund_minor: 1_000, refundable_minor: 3_000 },
      { ...verified, pendingRefundMinor: 1_000, refundableMinor: 3_000 },
      scope,
    )).toBe(false);
    expect(matchesVerifiedExactRefundTarget(
      { ...order, checkout_version: 8 },
      verified,
      scope,
    )).toBe(false);
  });
});
