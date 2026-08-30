import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { systemHealth, type SystemHealthDTO } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
  },
}));

const health: SystemHealthDTO = {
  status: 'degraded',
  server_time: '2026-08-30T12:00:00Z',
  retention_days: 90,
  dependencies: {
    api: 'operational',
    database: 'operational',
    redis: 'operational',
  },
  backups: {
    status: 'unknown',
    last_success_at: null,
    restore_tested_at: null,
    evidence_code: 'host_monitor_not_connected',
  },
  devices: {
    total: 1,
    seen_last_24h: 1,
    stale: 0,
    with_pending_sync: 0,
    sync_stalled: 0,
    max_pending_outbox_count: 0,
    latest_supported_version_code: 15,
    outdated_installations: 0,
  },
  diagnostics: {
    server_time: '2026-08-30T12:00:00Z',
    window_hours: 24,
    total: 0,
    critical_count: 0,
    affected_installations: 0,
    offline_event_count: 0,
    latest_event_at: null,
    counts_by_type: { crash: 0, anr: 0, api_failure: 0, sync_stall: 0 },
    counts_by_severity: { warning: 0, error: 0, critical: 0 },
    counts_by_component: {
      app: 0,
      auth: 0,
      gaming: 0,
      pos: 0,
      finance: 0,
      sync: 0,
      network: 0,
      updates: 0,
      storage: 0,
    },
  },
  recommendations: ['Connect verified backup monitoring to System Health.'],
};

describe('protected System Health API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('loads the no-store owner aggregate endpoint with request cancellation', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: health });
    const controller = new AbortController();

    await expect(systemHealth.get(controller.signal)).resolves.toEqual(health);

    expect(api.get).toHaveBeenCalledWith('/client-diagnostics/system-health', {
      signal: controller.signal,
    });
    expect(Object.keys(systemHealth)).toEqual(['get']);
  });
});
