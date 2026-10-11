// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { GoogleSheetsMirrorStatusDTO } from '@/lib/erp-api';

const api = vi.hoisted(() => ({
  getGoogleSheetsMirror: vi.fn(),
  listBranches: vi.fn(),
  getCompany: vi.fn(),
  configureGoogleSheetsMirror: vi.fn(),
  testGoogleSheetsMirror: vi.fn(),
  retryGoogleSheetsMirror: vi.fn(),
  disconnectGoogleSheetsMirror: vi.fn(),
}));

vi.mock('@/lib/erp-api', () => ({ settings: api }));

import SheetsTab from './SheetsTab';

Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });

const status: GoogleSheetsMirrorStatusDTO = {
  enabled: true,
  connection_verified: false,
  webhook_url: 'https://script.google.com/macros/s/test/exec',
  configured_at: '2026-10-11T09:00:00Z',
  secret_configured: true,
  pending_count: 1,
  held_count: 1,
  quarantined_count: 0,
  delivered_count: 4,
  last_delivered_at: '2026-10-11T09:05:00Z',
  last_error: null,
  export_mode: 'daily_collections',
  summary_branch_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  summary_branch_name: 'Main',
  summary_timezone: 'Asia/Kolkata',
  summary_start_date: '2026-10-01',
  summary_last_scanned_at: '2026-10-11T09:10:00Z',
  summary_last_window_blockers: [{ business_date: '2026-10-10', blockers: ['open_shift'] }],
  summary_last_error: 'One company scan failed; other companies continued.',
};

describe('Google Sheets settings daily collections controls', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(async () => {
    container = document.createElement('div');
    document.body.append(container);
    root = createRoot(container);
    api.getGoogleSheetsMirror.mockResolvedValue(status);
    api.listBranches.mockResolvedValue([{
      id: status.summary_branch_id,
      name: 'Main',
      code: 'MN',
      address: null,
      timezone: 'Asia/Kolkata',
      opens_at: null,
      closes_at: null,
      state_code: null,
      fssai_license_no: null,
      trade_license_no: null,
      branch_gstin: null,
    }]);
    api.getCompany.mockResolvedValue({ id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' });
    await act(async () => { root.render(<SheetsTab/>); });
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
    vi.clearAllMocks();
  });

  it('renders exact daily scope, verification state, blockers, scan error, and owner setup', () => {
    expect(container.textContent).toContain('Daily final Cash and UPI totals');
    expect(container.textContent).toContain('Configured · verification pending');
    expect(container.textContent).toContain('Business timezone: Asia/Kolkata');
    expect(container.textContent).toContain('2026-10-10: open_shift');
    expect(container.textContent).toContain('One company scan failed; other companies continued.');
    expect(container.textContent).toContain('getDailyCollectionsD2Fingerprint');
    expect(container.textContent).toContain('setupDailyCollectionsProjection');
    expect(container.textContent).toContain('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
    expect(container.textContent).toContain('cccccccc-cccc-4ccc-8ccc-cccccccccccc');
  });

  it('shows the backend generation-drain failure instead of claiming the scope changed', async () => {
    api.configureGoogleSheetsMirror.mockRejectedValue(
      new Error('Google Sheets has 2 undelivered business entries in the current verified connection.'),
    );
    const save = [...container.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('Save connection'));
    expect(save).toBeDefined();
    await act(async () => { save?.click(); });
    expect(container.textContent).toContain('2 undelivered business entries');
    expect(api.configureGoogleSheetsMirror).toHaveBeenCalledWith({
      webhook_url: status.webhook_url,
      rotate_secret: false,
      export_mode: 'daily_collections',
      summary_branch_id: status.summary_branch_id,
      summary_start_date: status.summary_start_date,
    });
  });
});
