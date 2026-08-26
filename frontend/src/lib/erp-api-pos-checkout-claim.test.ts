import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { pos, type CheckoutClaimDTO } from './erp-api';

vi.mock('./api', () => ({
  api: {
    delete: vi.fn(),
    post: vi.fn(),
  },
}));

const claim: CheckoutClaimDTO = {
  claim_id: 'claim-1',
  order_id: 'order-1',
  claim_token: 'checkout-claim-token',
  expires_at: '2026-08-25T20:30:00Z',
  order_total_minor: 4_200,
  paid_minor: 0,
  due_minor: 4_200,
  order_version: 7,
  claimant_user_id: 'user-1',
  terminal_id: 'terminal-1',
  reused: false,
};

describe('POS checkout-claim API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('acquires the claim from the held-order endpoint', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: claim });

    await expect(pos.claimCheckout('order-1')).resolves.toEqual(claim);
    expect(api.post).toHaveBeenCalledWith('/pos/orders/order-1/checkout-claim');
  });

  it('releases only the claim identified by its bearer token', async () => {
    vi.mocked(api.delete).mockResolvedValue({ data: undefined });

    await expect(pos.releaseCheckout('order-1', claim.claim_token)).resolves.toBeUndefined();
    expect(api.delete).toHaveBeenCalledWith('/pos/orders/order-1/checkout-claim', {
      headers: { 'X-Checkout-Claim': claim.claim_token },
    });
  });

  it('submits payment with both the stable operation key and checkout claim', async () => {
    const response = {
      id: 'payment-1',
      amount_minor: 4_200,
      tip_minor: 0,
      order_status: 'paid',
      invoice_no: 'INV-1',
      fiscal_year: '2026-27',
      invoice_issued_at: '2026-08-25T20:20:00Z',
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });
    const body = {
      method: 'upi' as const,
      amount_minor: 4_200,
      expected_order_total_minor: 4_200,
      expected_due_minor: 4_200,
      tip_minor: 0,
    };

    await expect(
      pos.recordPayment('order-1', body, 'payment:attempt-1', claim.claim_token),
    ).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledWith('/pos/orders/order-1/payments', body, {
      headers: {
        'Idempotency-Key': 'payment:attempt-1',
        'X-Checkout-Claim': claim.claim_token,
      },
    });
  });

  it('submits zero-value finalization with the same two recovery credentials', async () => {
    const response = {
      order_id: 'order-1',
      amount_minor: 0 as const,
      order_status: 'paid',
      invoice_no: 'INV-2',
      fiscal_year: '2026-27',
      invoice_issued_at: '2026-08-25T20:20:00Z',
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });

    await expect(
      pos.finalizeZero('order-1', 'finalize-zero:attempt-1', claim.claim_token),
    ).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledWith('/pos/orders/order-1/finalize-zero', undefined, {
      headers: {
        'Idempotency-Key': 'finalize-zero:attempt-1',
        'X-Checkout-Claim': claim.claim_token,
      },
    });
  });
});
