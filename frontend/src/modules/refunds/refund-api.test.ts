import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from '@/lib/api';
import { refundActionHeaders, refunds } from '@/lib/erp-api';

vi.mock('@/lib/api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('web refund API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('queries all paid/refunded orders and the explicit unresolved queue', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });

    await refunds.listOrders();
    await refunds.listRequests({ unresolved: true, limit: 200 });

    expect(api.get).toHaveBeenNthCalledWith(1, '/pos/orders', {
      params: { status: ['paid', 'refunded'], limit: 500 },
    });
    expect(api.get).toHaveBeenNthCalledWith(2, '/pos/refund-requests', {
      params: { unresolved: true, limit: 200 },
    });
  });

  it('uses the same request action identity in body, idempotency and provenance headers', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'refund-1' } });
    const actionId = 'web-refund:request:12345678';
    const body = {
      order_id: 'order-1',
      shift_id: 'shift-1',
      reason_code: 'wrong_item',
      amount_minor: 1_500,
      expected_paid_minor: 2_000,
      expected_refundable_minor: 2_000,
      mode: 'cash' as const,
      client_action_id: actionId,
      note: 'Wrong drink',
    };

    await refunds.request(body, actionId);

    expect(api.post).toHaveBeenCalledWith('/pos/refund-requests', body, {
      headers: refundActionHeaders(actionId),
    });
    expect(refundActionHeaders(actionId)).not.toHaveProperty('X-Offline-Captured');
  });

  it('records provider completion once with exact reference and timestamp provenance', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'refund-1' } });
    const occurredAt = '2026-08-28T12:34:56.000Z';
    const actionId = 'web-refund:settle-provider:12345678';

    await refunds.settleProvider(
      'refund-1', 'shift-1', 2_500, 'UPI-REF-991', occurredAt, actionId,
    );

    expect(api.post).toHaveBeenCalledWith(
      '/pos/refund-requests/refund-1/settle-provider',
      {
        shift_id: 'shift-1',
        expected_amount_minor: 2_500,
        provider_completed: true,
        external_reference: 'UPI-REF-991',
        provider_settled_at: occurredAt,
      },
      { headers: refundActionHeaders(actionId, occurredAt) },
    );
  });

  it('keeps protected provider resolution on its dedicated backend route', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'refund-1' } });
    const occurredAt = '2026-08-28T13:00:00.000Z';
    const actionId = 'web-refund:resolve-provider:12345678';

    await refunds.resolveProviderPayout(
      'refund-1',
      'shift-1',
      2_500,
      'provider_declined',
      'CASE-22',
      'Provider confirmed failure',
      occurredAt,
      actionId,
    );

    expect(api.post).toHaveBeenCalledWith(
      '/pos/refund-requests/refund-1/resolve-provider-payout',
      expect.objectContaining({
        provider_not_completed: true,
        provider_status: 'provider_declined',
        verification_reference: 'CASE-22',
      }),
      { headers: refundActionHeaders(actionId, occurredAt) },
    );
  });
});
