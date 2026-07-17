import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { finance, type ManualCollectionDTO } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const row: ManualCollectionDTO = {
  id: 'collection-1',
  company_id: 'company-1',
  branch_id: 'branch-1',
  business_date: '2026-07-16',
  method: 'cash',
  amount_minor: 21_000,
  source_kind: 'manual_daily',
  source_ref: 'Daily collection 2026-07-16 Cash',
  idempotency_key: 'manual-collection:test',
  note: 'Off-POS daily total',
  created_by: 'user-1',
  created_by_name: 'Cashier',
  created_at: '2026-07-16T12:00:00Z',
  voided_at: null,
  voided_by: null,
  voided_by_name: null,
  void_reason: null,
  is_voided: false,
};

describe('manual collection API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('lists the immutable register with explicit void-history controls', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [row] });

    await expect(finance.listManualCollections({ include_voided: true, limit: 500 }))
      .resolves.toEqual([row]);
    expect(api.get).toHaveBeenCalledWith('/finance/manual-collections', {
      params: { include_voided: true, limit: 500 },
    });
  });

  it('sends a stable idempotency key when creating a collection', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: row });
    const body = {
      branch_id: 'branch-1',
      business_date: '2026-07-16',
      method: 'cash' as const,
      amount_minor: 21_000,
      source_ref: 'Daily collection 2026-07-16 Cash',
      note: 'Off-POS daily total',
    };

    await expect(finance.createManualCollection(body, 'manual-collection:test')).resolves.toEqual(row);
    expect(api.post).toHaveBeenCalledWith('/finance/manual-collections', body, {
      headers: { 'Idempotency-Key': 'manual-collection:test' },
    });
  });

  it('voids by reason without exposing edit or delete operations', async () => {
    const voided = {
      ...row,
      is_voided: true,
      void_reason: 'Wrong payment method',
      voided_at: '2026-07-16T12:10:00Z',
    };
    vi.mocked(api.post).mockResolvedValue({ data: voided });

    await expect(finance.voidManualCollection(row.id, 'Wrong payment method')).resolves.toEqual(voided);
    expect(api.post).toHaveBeenCalledWith(
      '/finance/manual-collections/collection-1/void',
      { reason: 'Wrong payment method' },
    );
  });
});
