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
import { DeviceKeyApprovalModal } from './DeviceKeyPairingPanel';
import { RemoteFramePanel } from './RemoteFramePanel';
import {
  canConnectRemoteSession,
  canRequestRemoteGrant,
  deviceKeyActionErrorMessage,
  joinRemoteAssistanceState,
  remoteAssistanceErrorMessage,
  shouldRetainRemoteFrameAfterError,
  type BusyAction,
  type DeviceCentreRow,
  type RemoteFrameViewModel,
} from './remote-assistance-state';

const INSTALLATION_ID = '5b2d6639-5da5-4b2f-89fa-61a6b5a8b700';
const PENDING_KEY_ID = 'c7345b8e-1c2c-4385-ad55-c3d8d540b544';

function device(
  overrides: Partial<RemoteAssistanceDeviceDTO> = {},
): RemoteAssistanceDeviceDTO {
  return {
    installation_id: INSTALLATION_ID,
    terminal_id: '2e05212a-7f1c-4d33-a2bc-e918d0e84e4c',
    terminal_name: 'Front desk tablet',
    version_name: '3.1.7',
    version_code: 18,
    last_user_id: 'ae5c8060-b147-4837-92d8-61d174e34a53',
    last_user_name: 'Employee',
    last_seen_at: '2026-08-30T12:00:00Z',
    remote_support_last_seen_at: '2026-08-30T12:00:00Z',
    is_remote_online: true,
    protocol_version: 1,
    sharing_capability: 'available',
    device_key_id: '7f144289-1b3a-4fc1-99e7-02d8d90bb911',
    device_key_status: 'active',
    device_key_fingerprint_sha256: 'a'.repeat(64),
    device_key_approved_at: '2026-08-30T11:50:00Z',
    pending_device_key_id: null,
    pending_device_key_enrolled_by_user_id: null,
    pending_device_key_enrolled_by_name: null,
    pending_device_key_enrolled_at: null,
    pending_device_key_expires_at: null,
    pairing_required: false,
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
      busyAction={busyAction}
      onSelect={() => undefined}
      onRefresh={() => undefined}
      onGrantKindChange={() => undefined}
      onRequestGrant={() => undefined}
      onConnect={() => undefined}
      onEnd={() => undefined}
      onRevoke={() => undefined}
      onReviewPairing={() => undefined}
      onRevokeKey={() => undefined}
      onCommand={() => undefined}
    />,
  );
}

describe('Device Centre protected presentation', () => {
  it('shows the active redacted view and only the approved ERP command surface', () => {
    const markup = view([{ device: device(), grant: grant(), session: session() }]);

    expect(markup).toContain('Protected owner access');
    expect(markup).toContain('Sensitive fields are hidden');
    expect(markup).toContain('Ask staff to open Help');
    expect(markup).not.toContain('>Open Help<');
    expect(markup).toContain('Refresh Help');
    expect(markup).toContain('Operational screens stay private');
    expect(markup).toContain('Remote assistance cannot open or control');
    expect(markup).not.toContain('Code 17 cannot remotely open or control');
    expect(markup).toContain('Collect diagnostics');
    expect(markup).toContain('Device key verified');
    expect(markup).toContain('a'.repeat(64));
    expect(markup).toContain('End session');
    expect(markup).toContain('Emergency stop &amp; revoke');
    expect(markup).toContain('Every action is recorded');
    expect(markup).not.toContain('Raw tap');
    expect(markup).not.toContain('Send text');
    expect(markup).not.toContain('Open Finance');
    expect(markup).not.toContain('Open Gaming');
    expect(markup).not.toContain('Open POS');
    expect(markup).not.toContain('Open Shift');
  });

  it('disables Help commands while the view is privacy protected or unavailable', () => {
    for (const state of ['privacy', 'offline', 'loading'] as const) {
      const markup = view(
        [{
          device: device({ is_remote_online: state !== 'offline' }),
          grant: grant(),
          session: session(),
        }],
        { ...freshFrame, state, src: null },
      );

      expect(markup).toContain('Ask staff to open Help');
      expect(markup).toContain('Help commands stay disabled');
      for (const label of ['Refresh Help</button>', 'Collect diagnostics</button>']) {
        const labelIndex = markup.indexOf(label);
        expect(labelIndex).toBeGreaterThan(-1);
        const buttonStart = markup.lastIndexOf('<button', labelIndex);
        expect(markup.slice(buttonStart, markup.indexOf('>', buttonStart)))
          .toContain('disabled=""');
      }
      expect(markup).toContain('End session');
      expect(markup).toContain('Emergency stop &amp; revoke');
    }
  });

  it('enables only safe Help refresh and diagnostics when a live frame is visible', () => {
    const markup = view([{ device: device(), grant: grant(), session: session() }]);

    expect(markup).toContain('Help is visible');
    for (const label of ['Refresh Help</button>', 'Collect diagnostics</button>']) {
      const labelIndex = markup.indexOf(label);
      expect(labelIndex).toBeGreaterThan(-1);
      const buttonStart = markup.lastIndexOf('<button', labelIndex);
      expect(markup.slice(buttonStart, markup.indexOf('>', buttonStart)))
        .not.toContain('disabled=""');
    }
    expect(markup).not.toContain('type="navigate"');
  });

  it('shows pending pairing evidence without leaking a code or unapproved fingerprint', () => {
    const unapprovedFingerprint = 'f'.repeat(64);
    const pendingDevice = device({
      device_key_id: PENDING_KEY_ID,
      device_key_status: 'pending',
      device_key_fingerprint_sha256: unapprovedFingerprint,
      device_key_approved_at: null,
      pending_device_key_id: PENDING_KEY_ID,
      pending_device_key_enrolled_by_user_id: 'b5589e6e-6842-4c61-b6f6-c9693879b7a6',
      pending_device_key_enrolled_by_name: 'Front desk employee',
      pending_device_key_enrolled_at: '2026-08-30T12:01:00Z',
      pending_device_key_expires_at: '2026-08-30T12:11:00Z',
      pairing_required: true,
      grant_status: null,
      current_grant_id: null,
      current_grant_kind: null,
      current_grant_expires_at: null,
      session_status: null,
      current_session_id: null,
      current_session_expires_at: null,
      current_session_next_sequence: null,
    });
    const row = { device: pendingDevice, grant: null, session: null };
    const markup = view([row], { ...freshFrame, state: 'inactive', src: null });

    expect(markup).toContain('Pairing required');
    expect(markup).toContain(PENDING_KEY_ID);
    expect(markup).toContain('Front desk employee');
    expect(markup).toContain('Enter pairing code');
    expect(markup).not.toContain(unapprovedFingerprint);
    expect(markup).not.toContain('pairing_code');
    expect(canRequestRemoteGrant(row)).toBe(false);
    expect(canConnectRemoteSession(row)).toBe(false);

    for (const label of ['Request employee approval</button>', 'Connect</button>']) {
      const labelIndex = markup.indexOf(label);
      expect(labelIndex).toBeGreaterThan(-1);
      const buttonStart = markup.lastIndexOf('<button', labelIndex);
      expect(markup.slice(buttonStart, markup.indexOf('>', buttonStart)))
        .toContain('disabled=""');
    }
  });

  it('renders active, revoked and expired key states with fail-closed recovery copy', () => {
    const active = view([{
      device: device({
        grant_status: null,
        current_grant_id: null,
        current_grant_kind: null,
        current_grant_expires_at: null,
        session_status: null,
      }),
      grant: null,
      session: null,
    }]);
    expect(active).toContain('Key verified');
    expect(active).toContain('Revoke device key');
    expect(active).toContain('revoke it here first');

    const revokedFingerprint = 'e'.repeat(64);
    const revoked = view([{
      device: device({
        device_key_status: 'revoked',
        device_key_fingerprint_sha256: revokedFingerprint,
        device_key_approved_at: '2026-08-30T11:45:00Z',
        pairing_required: true,
        grant_status: null,
        current_grant_id: null,
        current_grant_kind: null,
        current_grant_expires_at: null,
        session_status: null,
      }),
      grant: null,
      session: null,
    }], { ...freshFrame, state: 'inactive', src: null });
    expect(revoked).toContain('Device key revoked');
    expect(revoked).toContain('Remote access is blocked');
    expect(revoked).not.toContain(revokedFingerprint);

    const expired = view([{
      device: device({
        device_key_status: 'expired',
        device_key_fingerprint_sha256: null,
        device_key_approved_at: null,
        pairing_required: true,
        grant_status: null,
        current_grant_id: null,
        current_grant_kind: null,
        current_grant_expires_at: null,
        session_status: null,
      }),
      grant: null,
      session: null,
    }], { ...freshFrame, state: 'inactive', src: null });
    expect(expired).toContain('Pairing expired');
    expect(expired).toContain('start a new pairing request');
  });

  it('treats a pending replacement as explicit rotation while the active key stays visible', () => {
    const markup = view([{
      device: device({
        pending_device_key_id: PENDING_KEY_ID,
        pending_device_key_enrolled_by_user_id: 'b5589e6e-6842-4c61-b6f6-c9693879b7a6',
        pending_device_key_enrolled_by_name: 'Shift lead',
        pending_device_key_enrolled_at: '2026-08-30T12:01:00Z',
        pending_device_key_expires_at: '2026-08-30T12:11:00Z',
      }),
      grant: grant(),
      session: session(),
    }]);

    expect(markup).toContain('Replacement key waiting');
    expect(markup).toContain('Review replacement');
    expect(markup).toContain('a'.repeat(64));
  });

  it('shows a queued command as waiting while stop and revoke remain available', () => {
    const markup = view(
      [{ device: device(), grant: grant(), session: session() }],
      freshFrame,
      'refresh',
    );

    expect(markup).toContain('Waiting for tablet…');
    for (const label of [
      'Revoke device key</button>',
      'End session</button>',
      'Emergency stop &amp; revoke</button>',
    ]) {
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

  it('never displays a frame when the reported session lacks an active device key', () => {
    const markup = renderToStaticMarkup(
      <RemoteFramePanel
        device={device({
          device_key_id: PENDING_KEY_ID,
          device_key_status: 'pending',
          device_key_fingerprint_sha256: null,
          device_key_approved_at: null,
          pending_device_key_id: PENDING_KEY_ID,
          pairing_required: true,
        })}
        session={session()}
        frame={freshFrame}
      />,
    );

    expect(markup).toContain('Remote view is locked');
    expect(markup).toContain('verified device key');
    expect(markup).not.toContain('<img');
    expect(markup).not.toContain('blob:frame-1');
  });

  it('keeps pairing entry masked and supports truthful wrong-code and unknown-outcome states', () => {
    const wrongCode = renderToStaticMarkup(
      <DeviceKeyApprovalModal
        replacement={false}
        busy={false}
        error="The pairing code was not accepted."
        retryAvailable={false}
        onSubmit={() => undefined}
        onCancel={() => undefined}
      />,
    );
    expect(wrongCode).toContain('type="password"');
    expect(wrongCode).toContain('value=""');
    expect(wrongCode).toContain('0 of 12 characters entered');
    expect(wrongCode).toContain('The pairing code was not accepted.');
    expect(wrongCode).not.toContain('AB3DEF5G7H9J');

    const retry = renderToStaticMarkup(
      <DeviceKeyApprovalModal
        replacement
        busy={false}
        error="The ERP server could not be reached."
        retryAvailable
        onSubmit={() => undefined}
        onCancel={() => undefined}
      />,
    );
    expect(retry).toContain('Approval outcome is unknown');
    expect(retry).toContain('exact same hidden code and approval ID');
    expect(retry).toContain('Retry approval');
    expect(retry).not.toContain('type="password"');

    const loading = renderToStaticMarkup(
      <DeviceKeyApprovalModal
        replacement={false}
        busy
        error={null}
        retryAvailable
        onSubmit={() => undefined}
        onCancel={() => undefined}
      />,
    );
    expect(loading).toContain('animate-spin');
    expect(loading).toContain('disabled=""');
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

describe('remote frame privacy retention', () => {
  const now = Date.parse('2026-08-30T12:00:15Z');

  it('clears pixels immediately when privacy or authority is no longer confirmed', () => {
    const fresh = '2026-08-30T12:00:10Z';
    expect(shouldRetainRemoteFrameAfterError(401, fresh, now)).toBe(false);
    expect(shouldRetainRemoteFrameAfterError(403, fresh, now)).toBe(false);
    expect(shouldRetainRemoteFrameAfterError(404, fresh, now)).toBe(false);
  });

  it('retains only a very recent frame for transient server or network failures', () => {
    const recent = '2026-08-30T12:00:05Z';
    const expired = '2026-08-30T11:59:59Z';
    expect(shouldRetainRemoteFrameAfterError(503, recent, now)).toBe(true);
    expect(shouldRetainRemoteFrameAfterError(undefined, recent, now)).toBe(true);
    expect(shouldRetainRemoteFrameAfterError(500, expired, now)).toBe(false);
    expect(shouldRetainRemoteFrameAfterError(503, null, now)).toBe(false);
    expect(shouldRetainRemoteFrameAfterError(503, 'not-a-date', now)).toBe(false);
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

    const mismatch = Object.assign(new Error('raw mismatch trace'), {
      status: 403,
      code: 'remote_pairing_code_mismatch',
    });
    const pairingForbidden = Object.assign(new Error('raw permission trace'), {
      status: 403,
      code: 'forbidden',
    });
    expect(deviceKeyActionErrorMessage(mismatch, 'approve')).toContain('not accepted');
    expect(deviceKeyActionErrorMessage(mismatch, 'approve')).not.toContain('trace');
    expect(deviceKeyActionErrorMessage(pairingForbidden, 'approve'))
      .toContain('Protected System Health access');

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
