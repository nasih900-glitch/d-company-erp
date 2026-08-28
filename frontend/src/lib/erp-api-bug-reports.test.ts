import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import {
  bugReports,
  type BugReportCreateDTO,
  type BugReportListParams,
  type BugReportUpdateDTO,
} from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
  },
}));

describe('bug report API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('passes inbox filters, pagination, and request cancellation to the list endpoint', async () => {
    const params: BugReportListParams = {
      q: 'payment',
      status: 'open',
      severity: 'critical',
      category: 'payment',
      platform: 'android',
      limit: 25,
      offset: 50,
    };
    const controller = new AbortController();
    vi.mocked(api.get).mockResolvedValue({ data: { items: [] } });

    await bugReports.list(params, controller.signal);

    expect(api.get).toHaveBeenCalledWith('/bug-reports', {
      params,
      signal: controller.signal,
    });
  });

  it('loads and updates one report without exposing broader edit operations', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { id: 'report-1' } });
    vi.mocked(api.patch).mockResolvedValue({ data: { id: 'report-1', status: 'resolved' } });
    const controller = new AbortController();
    const update: BugReportUpdateDTO = {
      status: 'resolved',
      internal_resolution_note: 'Fixed in the current release.',
    };

    await bugReports.get('report-1', controller.signal);
    await bugReports.update('report-1', update);

    expect(api.get).toHaveBeenCalledWith('/bug-reports/report-1', {
      signal: controller.signal,
    });
    expect(api.patch).toHaveBeenCalledWith('/bug-reports/report-1', update);
  });

  it('requires an idempotency key for client submissions', async () => {
    const body: BugReportCreateDTO = {
      category: 'usability',
      severity: 'medium',
      title: 'Void reason keyboard does not open',
      description: 'The reason field cannot be completed from the tablet keyboard.',
      client_context: {
        platform: 'android',
        app_version: '3.0.5',
        version_code: 3000500,
        connectivity: 'online',
      },
    };
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'report-1' } });

    await bugReports.submit(body, 'bug-report:device-1:local-7');

    expect(api.post).toHaveBeenCalledWith('/bug-reports', body, {
      headers: { 'Idempotency-Key': 'bug-report:device-1:local-7' },
    });
  });
});
