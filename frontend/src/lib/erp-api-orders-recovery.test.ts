import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { orders } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    patch: vi.fn(),
  },
}));

describe('direct-order recovery API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends the exact version, reason and stable idempotency key', async () => {
    const response = { id: 'order-1', status: 'held', checkout_version: 8 };
    vi.mocked(api.patch).mockResolvedValue({ data: response });
    const body = {
      expected_checkout_version: 7,
      reason: 'Original tablet checkout was abandoned',
    };

    await expect(
      orders.holdForCheckout('order-1', body, 'pos-direct-recovery:web:attempt-1'),
    ).resolves.toEqual(response);
    expect(api.patch).toHaveBeenCalledWith(
      '/pos/orders/order-1/hold-for-checkout',
      body,
      { headers: { 'Idempotency-Key': 'pos-direct-recovery:web:attempt-1' } },
    );
  });
});
