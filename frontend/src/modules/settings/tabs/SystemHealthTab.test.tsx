import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import type { ApiError } from '@/lib/api';
import type { SystemHealthDTO } from '@/lib/erp-api';
import { SystemHealthOverview, systemHealthErrorMessage } from './SystemHealthTab';

function fixture(overrides: Partial<SystemHealthDTO> = {}): SystemHealthDTO {
  return {
    status: 'action_required',
    server_time: '2026-08-30T12:00:00Z',
    retention_days: 90,
    dependencies: {
      api: 'operational',
      database: 'operational',
      redis: 'unavailable',
    },
    backups: {
      status: 'unknown',
      last_success_at: null,
      restore_tested_at: null,
      evidence_code: 'host_monitor_not_connected',
    },
    devices: {
      total: 3,
      seen_last_24h: 2,
      stale: 1,
      with_pending_sync: 1,
      sync_stalled: 1,
      max_pending_outbox_count: 7,
      latest_supported_version_code: 15,
      outdated_installations: 2,
    },
    diagnostics: {
      server_time: '2026-08-30T12:00:00Z',
      window_hours: 24,
      total: 5,
      critical_count: 1,
      affected_installations: 2,
      offline_event_count: 2,
      latest_event_at: '2026-08-30T11:30:00Z',
      counts_by_type: { crash: 1, anr: 1, api_failure: 2, sync_stall: 1 },
      counts_by_severity: { warning: 2, error: 2, critical: 1 },
      counts_by_component: {
        app: 1,
        auth: 0,
        gaming: 1,
        pos: 1,
        finance: 0,
        sync: 1,
        network: 1,
        updates: 0,
        storage: 0,
      },
    },
    recommendations: [
      'Review recent crash or unresponsive-app diagnostics.',
      '<script>unsafe recommendation</script>',
    ],
    ...overrides,
  };
}

describe('Owner System Health presentation', () => {
  it('renders aggregate service, backup, tablet, update and incident evidence without release actions', () => {
    const markup = renderToStaticMarkup(
      <SystemHealthOverview
        health={fixture()}
        stale={false}
        refreshError={null}
        refreshing={false}
        onRefresh={() => undefined}
      />,
    );

    expect(markup).toContain('Action required');
    expect(markup).toContain('ERP API');
    expect(markup).toContain('Database');
    expect(markup).toContain('Server protection');
    expect(markup).toContain('Backup proof');
    expect(markup).toContain('Not connected');
    expect(markup).toContain('Code 15');
    expect(markup).toContain('2 outdated');
    expect(markup).toContain('Crashes');
    expect(markup).toContain('App not responding');
    expect(markup).toContain('API failures');
    expect(markup).toContain('Sync stalls');
    expect(markup).not.toContain('Offer update');
    expect(markup).not.toContain('<script>');
    expect(markup).toContain('&lt;script&gt;unsafe recommendation&lt;/script&gt;');
  });

  it('keeps the last successful aggregate visible and clearly marked after refresh failure', () => {
    const markup = renderToStaticMarkup(
      <SystemHealthOverview
        health={fixture()}
        stale
        refreshError="The ERP server could not be reached."
        refreshing={false}
        onRefresh={() => undefined}
      />,
    );

    expect(markup).toContain('Saved status');
    expect(markup).toContain('Latest check could not finish');
    expect(markup).toContain('The last successful server status remains visible');
    expect(markup).toContain('Action required');
  });

  it('renders deliberate empty states without claiming physical tablet proof', () => {
    const base = fixture();
    const empty = fixture({
      status: 'degraded',
      devices: {
        ...base.devices,
        total: 0,
        seen_last_24h: 0,
        stale: 0,
        with_pending_sync: 0,
        sync_stalled: 0,
        max_pending_outbox_count: 0,
        outdated_installations: 0,
      },
      diagnostics: {
        ...base.diagnostics,
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
    });
    const markup = renderToStaticMarkup(
      <SystemHealthOverview
        health={empty}
        stale={false}
        refreshError={null}
        refreshing={false}
        onRefresh={() => undefined}
      />,
    );

    expect(markup).toContain('No tablet health received yet');
    expect(markup).toContain('No incidents received in this window');
    expect(markup).toContain('does not replace physical tablet testing');
  });
});

describe('System Health recovery copy', () => {
  it('maps authorization and connectivity failures to actionable non-technical messages', () => {
    const forbidden = Object.assign(new Error('raw forbidden'), { status: 403 }) as ApiError;
    const offline = Object.assign(new Error('socket closed'), { code: 'network_error' }) as ApiError;
    const server = Object.assign(new Error('internal trace'), { status: 500 }) as ApiError;

    expect(systemHealthErrorMessage(forbidden)).toContain('does not have protected');
    expect(systemHealthErrorMessage(offline)).toContain('could not be reached');
    expect(systemHealthErrorMessage(server)).toContain('did not complete');
    expect(systemHealthErrorMessage(server)).not.toContain('trace');
  });
});
