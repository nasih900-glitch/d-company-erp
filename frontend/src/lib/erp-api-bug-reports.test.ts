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

  it('keeps reporter views and protected inbox operations on separate endpoints', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { items: [] } });
    vi.mocked(api.post).mockResolvedValue({
      data: {
        id: 'reply-1',
        author_name: 'Owner',
        message: 'Please restart the payment screen.',
        created_at: '2026-08-28T12:00:00Z',
      },
    });
    const controller = new AbortController();

    await bugReports.mine({ limit: 20, offset: 0 }, controller.signal);
    await bugReports.getMine('report-1', controller.signal);
    await bugReports.inboxSummary(controller.signal);
    await bugReports.markRead('report-1');
    await bugReports.reply(
      'report-1',
      'Please restart the payment screen.',
      'bug-report-reply:reply-1',
    );

    expect(api.get).toHaveBeenNthCalledWith(1, '/bug-reports/mine', {
      params: { limit: 20, offset: 0 },
      signal: controller.signal,
    });
    expect(api.get).toHaveBeenNthCalledWith(2, '/bug-reports/mine/report-1', {
      signal: controller.signal,
    });
    expect(api.get).toHaveBeenNthCalledWith(3, '/bug-reports/inbox-summary', {
      signal: controller.signal,
    });
    expect(api.post).toHaveBeenCalledWith('/bug-reports/report-1/read');
    expect(api.post).toHaveBeenCalledWith(
      '/bug-reports/report-1/public-replies',
      { message: 'Please restart the payment screen.' },
      { headers: { 'Idempotency-Key': 'bug-report-reply:reply-1' } },
    );
  });

  it('uploads screenshots as authenticated multipart data with idempotency', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'attachment-1' } });
    const file = new File(['image'], 'screen.png', { type: 'image/png' });

    await bugReports.attachMine('report-1', file, 'bug-report-attachment:one');

    const [url, body, config] = vi.mocked(api.post).mock.calls[0];
    expect(url).toBe('/bug-reports/mine/report-1/attachments');
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('file')).toBe(file);
    expect(config).toEqual({
      headers: { 'Idempotency-Key': 'bug-report-attachment:one' },
    });
  });
});
