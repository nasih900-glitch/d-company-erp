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
      gaming.extendSessionWithPackage(
        'session-1',
        { id: 'package-1', price_minor: 7_500, duration_minutes: 30, variant: 'solo' },
        { timer_minutes: 60, amount_minor: 12_000 },
        'gaming-extension:attempt-1',
      ),
    ).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledWith(
      '/gaming/sessions/session-1/extend',
      {
        package_id: 'package-1',
        expected_package_price_minor: 7_500,
        expected_package_duration_minutes: 30,
        expected_package_variant: 'solo',
        expected_timer_minutes: 60,
        expected_amount_minor: 12_000,
      },
      { headers: { 'Idempotency-Key': 'gaming-extension:attempt-1' } },
    );
  });

  it('sends immutable start pricing snapshots and a caller-owned key', async () => {
    const response = {
      id: 'session-1',
      station_id: 'station-1',
      shift_id: 'shift-1',
      status: 'active',
      start_at: '2026-08-25T10:00:00Z',
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });

    await gaming.startSession({
      station_id: 'station-1',
      shift_id: 'shift-1',
      package_id: 'package-1',
      expected_rate_per_hour_minor: 15_000,
      expected_package_price_minor: 12_000,
      expected_package_duration_minutes: 60,
      expected_package_variant: 'solo',
    }, 'gaming-start:attempt-1');

    expect(api.post).toHaveBeenCalledWith(
      '/gaming/sessions/start',
      expect.objectContaining({
        expected_rate_per_hour_minor: 15_000,
        expected_package_price_minor: 12_000,
        expected_package_duration_minutes: 60,
        expected_package_variant: 'solo',
      }),
      { headers: { 'Idempotency-Key': 'gaming-start:attempt-1' } },
    );
  });

  it('sends a bodyless stop with an explicit idempotency key', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'session-1', status: 'ended' } });

    await gaming.stopSession('session-1', 'gaming-stop:attempt-1');

    expect(api.post).toHaveBeenCalledWith(
      '/gaming/sessions/session-1/stop',
      undefined,
      { headers: { 'Idempotency-Key': 'gaming-stop:attempt-1' } },
    );
  });

  it('sends an explicit null timer snapshot for conflict-safe relative extension', async () => {
    const response = {
      id: 'session-1',
      station_id: 'station-1',
      status: 'active',
      start_at: '2026-08-25T10:00:00Z',
      timer_minutes: 30,
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });

    await expect(
      gaming.extendSessionTimer('session-1', null, 30, 'gaming-timer:attempt-1'),
    ).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledWith(
      '/gaming/sessions/session-1/extend-timer',
      {
        expected_timer_minutes: null,
        additional_minutes: 30,
      },
      { headers: { 'Idempotency-Key': 'gaming-timer:attempt-1' } },
    );
  });

  it('sends protected missing-bill repair with an explicit null CAS snapshot', async () => {
    const response = {
      id: 'session-1',
      station_id: 'station-1',
      status: 'ended',
      start_at: '2026-08-25T10:00:00Z',
      amount_minor: 9_200,
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });

    await expect(
      gaming.repairSessionBilling(
        'session-1',
        9_200,
        'Verified against the original written package docket',
        'gaming-repair:attempt-1',
      ),
    ).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledWith(
      '/gaming/sessions/session-1/repair-billing',
      {
        expected_amount_minor: null,
        amount_minor: 9_200,
        reason: 'Verified against the original written package docket',
      },
      { headers: { 'Idempotency-Key': 'gaming-repair:attempt-1' } },
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
