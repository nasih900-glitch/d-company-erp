import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { memberships, type MembershipRefundDTO } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const acceptedCashRefund: MembershipRefundDTO = {
  id: 'refund-1',
  membership_id: 'membership-1',
  payment_id: 'payment-1',
  shift_id: 'shift-1',
  method: 'cash',
  amount_minor: 49_900,
  accepted_at: '2026-08-26T09:05:00Z',
  status: 'accepted_cash_due',
  settled_at: null,
  reason: 'Customer requested cancellation',
  external_reference: null,
  receipt_no: null,
  entitlement_restored: false,
};

describe('membership financial API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends the exact captured shift, amount, time, rail, and idempotency key when subscribing', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'membership-1' } });
    const body = {
      customer_id: 'customer-1',
      tier_id: 'tier-1',
      shift_id: 'shift-1',
      expected_amount_minor: 49_900,
      collected_at: '2026-08-26T09:00:00Z',
      billing_cycle: 'monthly' as const,
      paid_via: 'upi' as const,
    };

    await memberships.subscribe(body, 'membership-subscribe:attempt-1');

    expect(api.post).toHaveBeenCalledWith('/memberships/subscribe', body, {
      headers: { 'Idempotency-Key': 'membership-subscribe:attempt-1' },
    });
  });

  it('records non-cash refunds only with provider completion evidence', async () => {
    const settledRefund = {
      ...acceptedCashRefund,
      method: 'upi' as const,
      status: 'settled' as const,
      settled_at: '2026-08-26T09:10:00Z',
      external_reference: 'UPI-REFUND-123',
      receipt_no: 'R/NIL/26-27/000001',
    };
    vi.mocked(api.post).mockResolvedValue({ data: settledRefund });
    const body = {
      shift_id: 'shift-1',
      expected_amount_minor: 49_900,
      method: 'upi' as const,
      reason: 'Customer requested cancellation',
      settled_at: '2026-08-26T09:10:00Z',
      external_reference: 'UPI-REFUND-123',
    };

    await expect(
      memberships.refund('membership-1', body, 'membership-refund:attempt-1'),
    ).resolves.toEqual(settledRefund);
    expect(api.post).toHaveBeenCalledWith('/memberships/membership-1/refund', body, {
      headers: { 'Idempotency-Key': 'membership-refund:attempt-1' },
    });
  });

  it('routes cash acceptance and each mutually exclusive resolution to separate writes', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: acceptedCashRefund });

    await memberships.refund(
      'membership-1',
      {
        shift_id: 'shift-1',
        expected_amount_minor: 49_900,
        method: 'cash',
        reason: 'Customer requested cancellation',
      },
      'membership-refund:cash-accept',
    );
    await memberships.settleCashRefund(
      'refund-1',
      {
        shift_id: 'shift-1',
        expected_amount_minor: 49_900,
        settled_at: '2026-08-26T09:15:00Z',
        cash_handed_over: true,
      },
      'membership-refund:cash-settle',
    );
    await memberships.withdrawCashRefund(
      'refund-2',
      {
        shift_id: 'shift-1',
        cash_not_handed_over: true,
        reason: 'Customer left before collecting cash',
      },
      'membership-refund:cash-withdraw',
    );

    expect(api.post).toHaveBeenNthCalledWith(
      1,
      '/memberships/membership-1/refund',
      expect.objectContaining({ method: 'cash' }),
      { headers: { 'Idempotency-Key': 'membership-refund:cash-accept' } },
    );
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      '/memberships/refunds/refund-1/settle-cash',
      expect.objectContaining({ cash_handed_over: true }),
      { headers: { 'Idempotency-Key': 'membership-refund:cash-settle' } },
    );
    expect(api.post).toHaveBeenNthCalledWith(
      3,
      '/memberships/refunds/refund-2/withdraw-cash',
      expect.objectContaining({ cash_not_handed_over: true }),
      { headers: { 'Idempotency-Key': 'membership-refund:cash-withdraw' } },
    );
  });

  it('loads complete membership history, including revoked and refunded terms', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });

    await expect(memberships.getCustomerSubscriptionHistory('customer-1')).resolves.toEqual([]);
    expect(api.get).toHaveBeenCalledWith('/memberships/customer/customer-1/history');
  });
});
