import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { analytics } from './erp-api';

vi.mock('./api', () => ({ api: { get: vi.fn() } }));

describe('Analytics business-day selection', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.get).mockResolvedValue({data:{}}); });
  it('lets the server use the shop business day instead of the owner device timezone', async () => {
    await analytics.dashboard();
    expect(api.get).toHaveBeenCalledWith('/analytics/dashboard', {params:{}});
  });
  it('retains intentional historical-date queries', async () => {
    await analytics.dashboard('2026-09-01');
    expect(api.get).toHaveBeenCalledWith('/analytics/dashboard', {params:{on_date:'2026-09-01'}});
  });
});
