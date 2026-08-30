import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import type {
  RemoteAssistanceDeviceDTO,
  RemoteAssistanceGrantDTO,
  RemoteAssistanceSessionDTO,
} from '@/lib/erp-api';
import {
  DeviceCentreView,
} from './DeviceCentreView';
import { DeviceCentreLoadError } from './DeviceCentrePrimitives';
import { RemoteFramePanel } from './RemoteFramePanel';
import {
  joinRemoteAssistanceState,
  remoteAssistanceErrorMessage,
  type BusyAction,
  type DeviceCentreRow,
  type RemoteFrameViewModel,
} from './remote-assistance-state';

const INSTALLATION_ID = '5b2d6639-5da5-4b2f-89fa-61a6b5a8b700';

function device(
  overrides: Partial<RemoteAssistanceDeviceDTO> = {},
): RemoteAssistanceDeviceDTO {
  return {
    installation_id: INSTALLATION_ID,
    terminal_id: '2e05212a-7f1c-4d33-a2bc-e918d0e84e4c',
    terminal_name: 'Front desk tablet',
    version_name: '3.1.6',
    version_code: 16,
    last_user_id: 'ae5c8060-b147-4837-92d8-61d174e34a53',
    last_user_name: 'Employee',
    last_seen_at: '2026-08-30T12:00:00Z',
    remote_support_last_seen_at: '2026-08-30T12:00:00Z',
    is_remote_online: true,
    protocol_version: 1,
    sharing_capability: 'available',
    grant_status: 'active',
    current_grant_id: '9cccd67f-1c98-4d73-8a20-5df6ccf35977',
    current_grant_kind: 'one_time',
    current_grant_expires_at: '2026-08-30T12:15:00Z',
    current_grant_responded_by_user_id: 'ae5c8060-b147-4837-92d8-61d174e34a53',
    current_grant_responded_by_name: 'Employee',
    current_grant_responded_at: '2026-08-30T11:56:00Z',
    session_status: 'active',
    current_session_id: 'b24e8bc8-8cb0-4a92-8557-3cb35ed77b65',
    current_session_expires_at: '2026-08-30T12:11:00Z',
    current_session_next_sequence: 4,
    ...overrides,
  };
}

function grant(overrides: Partial<RemoteAssistanceGrantDTO> = {}): RemoteAssistanceGrantDTO {
  return {
    id: '9cccd67f-1c98-4d73-8a20-5df6ccf35977',
    installation_id: INSTALLATION_ID,
    kind: 'one_time',
    status: 'active',
    requested_by_user_id: 'd33a2d44-8d08-45a4-a1c6-4217b1eae9de',
    requested_by_name: 'Owner',
    responded_by_user_id: 'ae5c8060-b147-4837-92d8-61d174e34a53',
    responded_by_name: 'Employee',
    requested_at: '2026-08-30T11:55:00Z',
    expires_at: '2026-08-30T12:15:00Z',
    responded_at: '2026-08-30T11:56:00Z',
    revoked_at: null,
    consumed_at: null,
    ...overrides,
  };
}

function session(
  overrides: Partial<RemoteAssistanceSessionDTO> = {},
): RemoteAssistanceSessionDTO {
  return {
    id: 'b24e8bc8-8cb0-4a92-8557-3cb35ed77b65',
    installation_id: INSTALLATION_ID,
    grant_id: '9cccd67f-1c98-4d73-8a20-5df6ccf35977',
    status: 'active',
    duration_seconds: 900,
    requested_by_user_id: 'd33a2d44-8d08-45a4-a1c6-4217b1eae9de',
    requested_by_name: 'Owner',
    started_by_user_id: 'd33a2d44-8d08-45a4-a1c6-4217b1eae9de',
    started_by_name: 'Owner',
    ended_by_user_id: null,
    ended_by_name: null,
    requested_at: '2026-08-30T11:55:00Z',
    request_expires_at: '2026-08-30T12:00:00Z',
    started_at: '2026-08-30T11:56:00Z',
    expires_at: '2026-08-30T12:11:00Z',
    ended_at: null,
    end_reason: null,
    next_sequence: 4,
    ...overrides,
  };
}

const freshFrame: RemoteFrameViewModel = {
  state: 'fresh',
  src: 'blob:frame-1',
  receivedAt: '2026-08-30T12:00:00Z',
  width: 1280,
  height: 800,
  message: null,
};

function view(
  rows: DeviceCentreRow[],
  frame = freshFrame,
  busyAction: BusyAction = null,
): string {
  return renderToStaticMarkup(
    <DeviceCentreView
      rows={rows}
      selectedId={rows[0]?.device.installation_id ?? null}
      refreshing={false}
      refreshError={null}
      actionError={null}
      frame={frame}
      grantKind="one_time"
      selectedModule="dashboard"
      busyAction={busyAction}
      onSelect={() => undefined}
      onRefresh={() => undefined}
      onGrantKindChange={() => undefined}
      onRequestGrant={() => undefined}
      onConnect={() => undefined}
      onEnd={() => undefined}
      onRevoke={() => undefined}
      onModuleChange={() => undefined}
      onCommand={() => undefined}
    />,
  );
}

describe('Device Centre protected presentation', () => {
  it('shows the active redacted view and only the approved ERP command surface', () => {
    const markup = view([{ device: device(), grant: grant(), session: session() }]);

    expect(markup).toContain('Protected owner access');
    expect(markup).toContain('Sensitive fields are hidden');
    expect(markup).toContain('Open Dashboard');
    expect(markup).toContain('Refresh current screen');
    expect(markup).toContain('Sync now');
    expect(markup).toContain('Collect diagnostics');
    expect(markup).toContain('End session');
    expect(markup).toContain('Emergency stop &amp; revoke');
    expect(markup).toContain('Every action is recorded');
    expect(markup).not.toContain('Raw tap');
    expect(markup).not.toContain('Send text');
    expect(markup).not.toContain('Open Finance');
  });

  it('shows a queued command as waiting while stop and revoke remain available', () => {
    const markup = view(
      [{ device: device(), grant: grant(), session: session() }],
      freshFrame,
      'refresh',
    );

    expect(markup).toContain('Waiting for tablet…');
    for (const label of ['End session</button>', 'Emergency stop &amp; revoke</button>']) {
      const labelIndex = markup.indexOf(label);
      expect(labelIndex).toBeGreaterThan(-1);
      const buttonStart = markup.lastIndexOf('<button', labelIndex);
      expect(markup.slice(buttonStart, markup.indexOf('>', buttonStart)))
        .not.toContain('disabled=""');
    }
  });

  it('renders clear pending, declined and revoked consent states', () => {
    const pending = view([{
      device: device({ grant_status: 'requested', session_status: 'requested' }),
      grant: grant({ status: 'requested', responded_at: null, responded_by_name: null }),
      session: session({ status: 'requested', started_at: null, started_by_name: null }),
    }], { ...freshFrame, state: 'inactive', src: null });
    expect(pending).toContain('Approval pending');
    expect(pending).toContain('Awaiting employee approval');

    const declined = view([{
      device: device({ grant_status: 'declined', session_status: 'ended' }),
      grant: grant({ status: 'declined' }),
      session: session({ status: 'ended', ended_at: '2026-08-30T11:57:00Z' }),
    }], { ...freshFrame, state: 'inactive', src: null });
    expect(declined).toContain('Request declined');
    expect(declined).toContain('The employee declined this request');

    const revoked = view([{
      device: device({ grant_status: 'revoked', session_status: 'ended' }),
      grant: grant({ status: 'revoked', revoked_at: '2026-08-30T11:58:00Z' }),
      session: session({ status: 'ended', ended_at: '2026-08-30T11:58:00Z' }),
    }], { ...freshFrame, state: 'inactive', src: null });
    expect(revoked).toContain('Access revoked');
    expect(revoked).toContain('Remote access was revoked');
  });

  it('keeps a stale image temporarily visible without implying durable storage', () => {
    const markup = renderToStaticMarkup(
      <RemoteFramePanel
        device={device()}
        session={session()}
        frame={{
          ...freshFrame,
          state: 'stale',
          receivedAt: '2026-08-30T11:59:00Z',
        }}
      />,
    );

    expect(markup).toContain('src="blob:frame-1"');
    expect(markup).toContain('Last received frame (stale)');
    expect(markup).toContain('This ERP frame is stale');
    expect(markup).toContain('remains on this screen temporarily');
    expect(markup).toContain('Do not treat it as the current tablet screen');
    expect(markup).not.toContain('Saved frame');
  });

  it('uses privacy and offline placeholders without exposing a guessed screen', () => {
    const privacy = renderToStaticMarkup(
      <RemoteFramePanel
        device={device()}
        session={session()}
        frame={{ ...freshFrame, state: 'privacy', src: null, message: null }}
      />,
    );
    expect(privacy).toContain('No ERP frame is available');
    expect(privacy).toContain('protected');
    expect(privacy).not.toContain('<img');

    const offline = renderToStaticMarkup(
      <RemoteFramePanel
        device={device({ is_remote_online: false })}
        session={session()}
        frame={{ ...freshFrame, state: 'offline', src: null, message: null }}
      />,
    );
    expect(offline).toContain('Tablet is offline');
    expect(offline).not.toContain('<img');
  });

  it('separates heartbeat presence from sharing approval and blocks stale requests', () => {
    const approvalMarkup = view([{
      device: device({
        sharing_capability: 'permission_required',
        session_status: null,
        current_session_id: null,
        current_session_expires_at: null,
        current_session_next_sequence: null,
      }),
      grant: grant(),
      session: null,
    }], { ...freshFrame, state: 'inactive', src: null });

    expect(approvalMarkup).toContain('Connected');
    expect(approvalMarkup).toContain('approval required');
    const connectLabel = approvalMarkup.indexOf('Connect</button>');
    expect(connectLabel).toBeGreaterThan(-1);
    const connectButton = approvalMarkup.lastIndexOf('<button', connectLabel);
    expect(approvalMarkup.slice(connectButton, approvalMarkup.indexOf('>', connectButton)))
      .toContain('disabled=""');

    const staleMarkup = view([{
      device: device({
        is_remote_online: false,
        sharing_capability: 'available',
        grant_status: null,
        current_grant_id: null,
        current_grant_kind: null,
        current_grant_expires_at: null,
        session_status: null,
        current_session_id: null,
        current_session_expires_at: null,
        current_session_next_sequence: null,
      }),
      grant: null,
      session: null,
    }], { ...freshFrame, state: 'inactive', src: null });

    expect(staleMarkup).toContain('Tablet is offline or stale');
    const offlineLabel = staleMarkup.indexOf('Tablet offline</button>');
    expect(offlineLabel).toBeGreaterThan(-1);
    const requestButton = staleMarkup.lastIndexOf('<button', offlineLabel);
    expect(staleMarkup.slice(requestButton, staleMarkup.indexOf('>', requestButton)))
      .toContain('disabled=""');
  });

  it('recovers the authoritative consent actor without guessing from the last tablet user', () => {
    const recovered = view([{
      device: device({
        last_user_name: 'Different shift operator',
        current_grant_responded_by_name: 'Recorded approver',
        current_grant_responded_at: '2026-08-30T11:56:00Z',
      }),
      grant: null,
      session: session(),
    }]);

    expect(recovered).toContain('Recorded · Recorded approver');
    expect(recovered).not.toContain('Recorded · Different shift operator');
    expect(recovered).toContain('Access approved by employee');

    const unavailable = view([{
      device: device({
        last_user_name: 'Different shift operator',
        current_grant_responded_by_user_id: null,
        current_grant_responded_by_name: null,
        current_grant_responded_at: null,
      }),
      grant: null,
      session: session(),
    }]);
    expect(unavailable).toContain('Recorded · actor unavailable');
    expect(unavailable).not.toContain('Recorded · Different shift operator');
  });
});

describe('Device Centre state recovery', () => {
  it('chooses active state ahead of newer ended history for each device', () => {
    const active = session({ id: 'active', status: 'active', requested_at: '2026-08-30T11:00:00Z' });
    const ended = session({
      id: 'ended',
      status: 'ended',
      requested_at: '2026-08-30T12:00:00Z',
      ended_at: '2026-08-30T12:01:00Z',
    });

    const rows = joinRemoteAssistanceState([device()], [ended, active], new Map());
    expect(rows[0]?.session?.id).toBe('active');
  });

  it('shows protected-access and retry-safe errors without raw server details', () => {
    const forbidden = Object.assign(new Error('raw permission trace'), { status: 403 });
    const server = Object.assign(new Error('internal SQL trace'), { status: 500 });
    expect(remoteAssistanceErrorMessage(forbidden)).toContain('protected System Health access');
    expect(remoteAssistanceErrorMessage(server)).toContain('did not complete');
    expect(remoteAssistanceErrorMessage(server)).not.toContain('SQL');

    const markup = renderToStaticMarkup(
      <DeviceCentreLoadError
        message="The ERP server could not be reached."
        onRetry={() => undefined}
      />,
    );
    expect(markup).toContain('Device Centre could not be loaded');
    expect(markup).toContain('No remote session or access request was started');
    expect(markup).toContain('Try again');
  });
});
