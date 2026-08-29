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
  handoff_started_at: null,
  payout_completed_at: null,
  payout_completed_by: null,
  payout_completed_by_name: null,
  accepted_by: 'owner-1',
  accepted_by_name: 'Owner',
  action_started_by: null,
  action_started_by_name: null,
  action_kind: null,
  settled_at: null,
  settled_by: null,
  settled_by_name: null,
  reason: 'Customer requested cancellation',
  external_reference: null,
  receipt_no: null,
  entitlement_restored: false,
  customer_id: 'customer-1',
  customer_name: 'Customer',
  customer_phone: '9999999999',
  tier_name: 'Gold',
  original_payment_receipt_no: 'M/26-27/000001',
  resolution: null,
  resolution_reason: null,
  resolved_at: null,
  resolved_by: null,
  resolved_by_name: null,
  evidence_occurred_at: null,
  evidence_time_untrusted: false,
  provider_evidence_reconciled: true,
  customer_spend_reconciled: true,
  action_state_verified: false,
  provider_verification_status: null,
  provider_verification_reference: null,
  provider_checked_at: null,
  cash_return_confirmed: false,
  action_takeover_confirmed: false,
  action_takeover_reason: null,
};

describe('membership financial API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reserves a non-cash refund before starting and completing the provider payout', async () => {
    const settledRefund = {
      ...acceptedCashRefund,
      method: 'upi' as const,
      status: 'accepted_provider_due' as const,
    };
    vi.mocked(api.post).mockResolvedValue({ data: settledRefund });
    const body = {
      shift_id: 'shift-1',
      expected_amount_minor: 49_900,
      method: 'upi' as const,
      reason: 'Customer requested cancellation',
    };

    await expect(
      memberships.refund('membership-1', body, 'membership-refund:attempt-1'),
    ).resolves.toEqual(settledRefund);
    expect(api.post).toHaveBeenCalledWith('/memberships/membership-1/refund', body, {
      headers: {
        'Idempotency-Key': 'membership-refund:attempt-1',
        'X-Client-Action-Id': 'membership-refund:attempt-1',
      },
    });
  });

  it('prepares membership collection with matching client-action provenance', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'task-1' } });
    const body = {
      customer_id: 'customer-1',
      tier_id: 'tier-1',
      shift_id: 'shift-1',
      expected_amount_minor: 49_900,
      billing_cycle: 'monthly' as const,
      paid_via: 'cash' as const,
      client_action_id: 'membership-payment:attempt-1',
    };

    await memberships.preparePayment(body, body.client_action_id);

    expect(api.post).toHaveBeenCalledWith('/memberships/payment-requests', body, {
      headers: {
        'Idempotency-Key': body.client_action_id,
        'X-Client-Action-Id': body.client_action_id,
      },
    });
  });

  it('lists unresolved terminal-scoped payment and refund recovery tasks', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });

    await memberships.listPaymentRequests();
    await memberships.listRefundTasks();

    expect(api.get).toHaveBeenNthCalledWith(1, '/memberships/payment-requests', {
      params: { unresolved: true, limit: 200 },
    });
    expect(api.get).toHaveBeenNthCalledWith(2, '/memberships/refunds', {
      params: { unresolved: true, limit: 200 },
    });
  });

  it('records provider payment completion separately from receipt finalization', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'task-1' } });
    const task = { id: 'task-1', shift_id: 'shift-1', amount_minor: 49_900 };

    await memberships.beginProviderPayment(task, 'membership-payment-begin:1');
    await memberships.settlePayment(task, {
      collected_at: '2026-08-26T09:10:00Z',
      payment_received: true,
      external_reference: 'UPI-PAY-123',
    }, 'membership-payment-complete:1');
    await memberships.finalizePayment(task, 'membership-payment-finalize:1');

    expect(api.post).toHaveBeenNthCalledWith(
      1,
      '/memberships/payment-requests/task-1/begin-provider-action',
      { shift_id: 'shift-1', expected_amount_minor: 49_900, ready_to_start: true },
      { headers: {
        'Idempotency-Key': 'membership-payment-begin:1',
        'X-Client-Action-Id': 'membership-payment-begin:1',
      } },
    );
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      '/memberships/payment-requests/task-1/settle',
      {
        shift_id: 'shift-1',
        expected_amount_minor: 49_900,
        collected_at: '2026-08-26T09:10:00Z',
        payment_received: true,
        external_reference: 'UPI-PAY-123',
      },
      { headers: {
        'Idempotency-Key': 'membership-payment-complete:1',
        'X-Client-Action-Id': 'membership-payment-complete:1',
        'X-Client-Occurred-At': '2026-08-26T09:10:00Z',
      } },
    );
    expect(api.post).toHaveBeenNthCalledWith(
      3,
      '/memberships/payment-requests/task-1/finalize',
      { shift_id: 'shift-1', expected_amount_minor: 49_900 },
      { headers: {
        'Idempotency-Key': 'membership-payment-finalize:1',
        'X-Client-Action-Id': 'membership-payment-finalize:1',
      } },
    );
  });

  it('records provider refund completion separately from refund accounting', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: acceptedCashRefund });
    const task = { id: 'refund-1', shift_id: 'shift-1', amount_minor: 49_900 };

    await memberships.beginRefundProviderAction(task, 'membership-refund-begin:1');
    await memberships.settleProviderRefund(task, {
      settled_at: '2026-08-26T09:20:00Z',
      provider_refund_completed: true,
      external_reference: 'UPI-REFUND-123',
    }, 'membership-refund-complete:1');
    await memberships.finalizeRefund(task, 'membership-refund-finalize:1');

    expect(api.post).toHaveBeenNthCalledWith(
      1,
      '/memberships/refunds/refund-1/begin-provider-action',
      { shift_id: 'shift-1', expected_amount_minor: 49_900, ready_to_start: true },
      expect.any(Object),
    );
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      '/memberships/refunds/refund-1/settle-provider',
      expect.objectContaining({
        provider_refund_completed: true,
        external_reference: 'UPI-REFUND-123',
      }),
      expect.any(Object),
    );
    expect(api.post).toHaveBeenNthCalledWith(
      3,
      '/memberships/refunds/refund-1/finalize',
      { shift_id: 'shift-1', expected_amount_minor: 49_900 },
      expect.any(Object),
    );
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
      { headers: {
        'Idempotency-Key': 'membership-refund:cash-accept',
        'X-Client-Action-Id': 'membership-refund:cash-accept',
      } },
    );
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      '/memberships/refunds/refund-1/settle-cash',
      expect.objectContaining({ cash_handed_over: true }),
      { headers: {
        'Idempotency-Key': 'membership-refund:cash-settle',
        'X-Client-Action-Id': 'membership-refund:cash-settle',
        'X-Client-Occurred-At': '2026-08-26T09:15:00Z',
      } },
    );
    expect(api.post).toHaveBeenNthCalledWith(
      3,
      '/memberships/refunds/refund-2/withdraw-cash',
      expect.objectContaining({ cash_not_handed_over: true }),
      { headers: {
        'Idempotency-Key': 'membership-refund:cash-withdraw',
        'X-Client-Action-Id': 'membership-refund:cash-withdraw',
      } },
    );
  });

  it('loads complete membership history, including revoked and refunded terms', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });

    await expect(memberships.getCustomerSubscriptionHistory('customer-1')).resolves.toEqual([]);
    expect(api.get).toHaveBeenCalledWith('/memberships/customer/customer-1/history');
  });
});
