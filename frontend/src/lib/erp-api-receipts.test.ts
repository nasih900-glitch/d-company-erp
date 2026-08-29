import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { receipts } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
  },
}));

describe('canonical receipt history API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('passes the opaque cursor through without parsing or rewriting it', async () => {
    const page = { items: [], next_cursor: 'opaque-next', has_more: true };
    vi.mocked(api.get).mockResolvedValue({ data: page });

    await expect(receipts.list({ cursor: 'opaque-current', limit: 25 })).resolves.toEqual(page);
    expect(api.get).toHaveBeenCalledWith('/pos/receipts', {
      params: { cursor: 'opaque-current', limit: 25 },
    });
  });

  it('loads one server-authoritative receipt by its order identity', async () => {
    const receipt = { order_id: 'order-1', invoice_no: 'INV-0001' };
    vi.mocked(api.get).mockResolvedValue({ data: receipt });

    await expect(receipts.get('order-1')).resolves.toEqual(receipt);
    expect(api.get).toHaveBeenCalledWith('/pos/receipts/order-1');
  });
});
