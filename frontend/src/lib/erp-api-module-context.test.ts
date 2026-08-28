import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import {
  finance,
  inventory,
  ocr,
  reports,
  type BranchReferenceDTO,
  type ReportDataDTO,
} from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
  },
}));

const branches: BranchReferenceDTO[] = [
  { id: 'branch-1', name: 'Main Shop', code: 'MAIN' },
];

describe('least-privilege module context API contracts', () => {
  beforeEach(() => vi.clearAllMocks());

  it('loads finance branch references without admin Settings access', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: branches });

    await expect(finance.listBranches()).resolves.toEqual(branches);
    expect(api.get).toHaveBeenCalledWith('/finance/branches');
  });

  it('loads inventory branch references without admin Settings access', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: branches });

    await expect(inventory.listBranches()).resolves.toEqual(branches);
    expect(api.get).toHaveBeenCalledWith('/inventory/branches');
  });

  it('loads OCR branch references from the OCR permission boundary', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: branches });

    await expect(ocr.listBranches()).resolves.toEqual(branches);
    expect(api.get).toHaveBeenCalledWith('/ocr/branches');
  });

  it('lets the backend choose the company-local date for a current daily report', async () => {
    const report = { period: 'daily', period_start: '2026-08-28' } as ReportDataDTO;
    vi.mocked(api.get).mockResolvedValue({ data: report });

    await expect(reports.daily()).resolves.toEqual(report);
    expect(api.get).toHaveBeenCalledWith('/reports/daily', { params: {} });
  });
});
