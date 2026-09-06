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
