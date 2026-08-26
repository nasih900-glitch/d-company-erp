import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { gaming } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('gaming paid-extension API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends the caller-retained idempotency key', async () => {
    const response = {
      id: 'session-1',
      station_id: 'station-1',
      status: 'active',
      start_at: '2026-08-25T10:00:00Z',
      timer_minutes: 90,
      amount_minor: 15_000,
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });

    await expect(
      gaming.extendSessionWithPackage('session-1', 'package-1', 'gaming-extension:attempt-1'),
    ).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledWith(
      '/gaming/sessions/session-1/extend',
      { package_id: 'package-1' },
      { headers: { 'Idempotency-Key': 'gaming-extension:attempt-1' } },
    );
  });

  it('sends the explicit target shift and human reconciliation reason', async () => {
    const response = {
      order_id: 'order-1',
      amount_minor: 15_700,
      source_shift_id: 'source-shift',
      target_shift_id: 'target-shift',
      already_linked: false,
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });

    await expect(
      gaming.reconcileToPos(
        'session-1',
        'target-shift',
        'Original shift was closed before the bill reached POS',
      ),
    ).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledWith(
      '/gaming/sessions/session-1/reconcile-to-pos',
      {
        target_shift_id: 'target-shift',
        reason: 'Original shift was closed before the bill reached POS',
      },
    );
  });
});
