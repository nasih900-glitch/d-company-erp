import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { pos } from './erp-api';

vi.mock('./api', () => ({
  api: {
    patch: vi.fn(),
  },
}));

describe('POS settlement metadata API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends the reviewed checkout version with a held-order customer change', async () => {
    vi.mocked(api.patch).mockResolvedValue({ data: { id: 'order-1' } });

    await pos.attachCustomer(
      'order-1',
      { customer_name: 'Final payer', customer_phone: '9000000000' },
      'customer-change-1',
      7,
    );

    expect(api.patch).toHaveBeenCalledWith(
      '/pos/orders/order-1/customer',
      {
        customer_name: 'Final payer',
        customer_phone: '9000000000',
        expected_checkout_version: 7,
      },
      { headers: { 'Idempotency-Key': 'customer-change-1' } },
    );
  });

  it.each([
    ['discount', () => pos.applyDiscount('order-1', 500, 'discount-1', 8), { manual_discount_minor: 500 }],
    ['points', () => pos.redeemPoints('order-1', 20, 'points-1', 9), { points: 20 }],
    ['reward', () => pos.redeemReward('order-1', 'snack', 'reward-1', 10), { reward_key: 'snack' }],
  ] as const)(
    'sends the reviewed checkout version with a held-order %s change',
    async (path, invoke, payload) => {
      vi.mocked(api.patch).mockResolvedValue({ data: { id: 'order-1' } });

      await invoke();

      const expectedVersion = path === 'discount' ? 8 : path === 'points' ? 9 : 10;
      const expectedKey = `${path}-1`;
      expect(api.patch).toHaveBeenCalledWith(
        `/pos/orders/order-1/${path}`,
        { ...payload, expected_checkout_version: expectedVersion },
        { headers: { 'Idempotency-Key': expectedKey } },
      );
    },
  );
});
