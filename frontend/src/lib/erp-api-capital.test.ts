import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { finance, type CapitalEntryDTO } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const row: CapitalEntryDTO = {
  id: 'capital-1',
  partner_id: 'partner-1',
  type: 'invest',
  amount_minor: 100_000,
  effective_at: '2026-07-16T10:00:00Z',
  settlement_account: 'bank',
  source_ref: 'UTR-20260716-001',
  note: 'Partner contribution',
  created_by: 'user-1',
  created_by_name: 'Owner',
  created_at: '2026-07-16T10:01:00Z',
  voided_at: null,
  voided_by: null,
  voided_by_name: null,
  void_reason: null,
  is_voided: false,
};

describe('capital ledger API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('requests void history for the auditable ledger', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [row] });

    await expect(finance.listCapital('partner-1', true)).resolves.toEqual([row]);
    expect(api.get).toHaveBeenCalledWith('/finance/partners/partner-1/capital', {
      params: { include_voided: true },
    });
  });

  it('creates only invest or withdraw movements with settlement evidence', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: row });
    const body = {
      partner_id: 'partner-1',
      type: 'invest' as const,
      amount_minor: 100_000,
      effective_at: '2026-07-16T10:00:00Z',
      settlement_account: 'bank' as const,
      source_ref: 'UTR-20260716-001',
      note: 'Partner contribution',
    };

    await expect(finance.createCapitalEntry(body)).resolves.toEqual(row);
    expect(api.post).toHaveBeenCalledWith('/finance/capital-entries', body);
  });

  it('voids by reason without exposing edit or delete operations', async () => {
    const voided = {
      ...row,
      is_voided: true,
      void_reason: 'Duplicate bank entry',
      voided_at: '2026-07-16T10:10:00Z',
      voided_by: 'user-1',
      voided_by_name: 'Owner',
    };
    vi.mocked(api.post).mockResolvedValue({ data: voided });

    await expect(finance.voidCapitalEntry(row.id, 'Duplicate bank entry')).resolves.toEqual(voided);
    expect(api.post).toHaveBeenCalledWith(
      '/finance/capital-entries/capital-1/void',
      { reason: 'Duplicate bank entry' },
    );
  });
});
