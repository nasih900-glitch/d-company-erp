import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { shifts, type ShiftRecoveryCloseDTO } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('shift recovery API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('loads the maximum supported shift history for complete business-day summaries', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });

    await expect(shifts.list()).resolves.toEqual([]);

    expect(api.get).toHaveBeenCalledExactlyOnceWith(
      '/pos/shifts',
      { params: { only_open: false, limit: 200 } },
    );
  });

  it('loads protected branch-scoped Android recovery candidates from the separate route', async () => {
    const response = [{
      id: 'shift-other-terminal',
      branch_id: 'branch-1',
      terminal_id: 'terminal-2',
      terminal_name: 'Gaming tablet',
      terminal_device_id: 'tablet-device-2',
      terminal_is_active: true,
      opened_at: '2026-09-21T09:00:00Z',
      opened_by: 'staff-1',
      opened_by_name: 'Sameer',
      opening_float_minor: 50_000,
      expected_minor: 82_000,
      opening_protocol_revision: 1,
      opening_client_platform: 'android' as const,
      opening_client_installation_recorded: true,
    }];
    vi.mocked(api.get).mockResolvedValue({ data: response });

    await expect(shifts.listRecoveryCandidates()).resolves.toEqual(response);
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/pos/shifts/recovery-candidates');
  });

  it('uses the separate recovery route with the caller-retained identity and attestations', async () => {
    const body: ShiftRecoveryCloseDTO = {
      counted_minor: 50_000,
      reason: 'Origin tablet was isolated after its close could not sync.',
      acknowledge_origin_tablet_quarantined: true,
    };
    const response = {
      id: 'shift-1',
      status: 'closed',
      variance_minor: 0,
      recovery_close: true,
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });

    await expect(
      shifts.recoverAndroidClose(
        'shift-1',
        body,
        'shift-recovery-close:web:attempt-1',
      ),
    ).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledExactlyOnceWith(
      '/pos/shifts/shift-1/recover-close',
      body,
      { headers: { 'Idempotency-Key': 'shift-recovery-close:web:attempt-1' } },
    );
  });
});
