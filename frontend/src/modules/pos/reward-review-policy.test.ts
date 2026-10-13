import { describe, expect, it } from 'vitest';

import type { CustomerDTO, OrderDTO, RewardDTO } from '@/lib/erp-api';
import { captureRewardReview, matchesRewardReview } from './reward-review-policy';

const reward = { key: 'credit', program_revision: 4 } as RewardDTO;
const customer = { id: 'customer-a', phone: '9876543210' } as CustomerDTO;
const order = {
  id: 'order-a', checkout_version: 7, customer_phone: '9876543210',
} as OrderDTO;

describe('reward review binding', () => {
  it('rejects a customer or version swap after the modal opens', () => {
    const review = captureRewardReview(reward, order.id, order.checkout_version, customer);
    expect(review).not.toBeNull();
    expect(matchesRewardReview(review!, order, customer)).toBe(true);
    expect(matchesRewardReview(review!, order, { ...customer, id: 'customer-b' })).toBe(false);
    expect(matchesRewardReview(review!, { ...order, customer_phone: '9123456780' }, customer)).toBe(false);
    expect(matchesRewardReview(review!, { ...order, checkout_version: 8 }, customer)).toBe(false);
  });
});
