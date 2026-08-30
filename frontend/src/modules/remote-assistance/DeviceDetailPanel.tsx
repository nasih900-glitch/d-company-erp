import {
  Activity,
  Clock3,
  FileClock,
  LockKeyhole,
  ShieldCheck,
  UserCheck,
  type LucideIcon,
} from 'lucide-react';

import type { RemoteAssistanceGrantKind } from '@/lib/erp-api';
import type { SafeRemoteAssistanceCommand } from '@/lib/remote-assistance-policy';
import { RemoteFramePanel } from './RemoteFramePanel';
import { RemoteControls } from './RemoteControlsPanel';
import { DeviceKeyPairingPanel } from './DeviceKeyPairingPanel';
import {
  assistanceEvents,
  deviceKeyStatusPresentation,
  deviceName,
  formatDate,
  grantStatusPresentation,
} from './remote-assistance-presentation';
import { StatusPill } from './DeviceCentrePrimitives';
import {
  canConnectRemoteSession,
  canRequestRemoteGrant,
  type BusyAction,
  type DeviceCentreRow,
  type RemoteFrameViewModel,
} from './remote-assistance-state';

export function DeviceDetail({
  row,
  frame,
  grantKind,
  busyAction,
  onGrantKindChange,
  onRequestGrant,
  onConnect,
  onEnd,
  onRevoke,
  onReviewPairing,
  onRevokeKey,
  onCommand,
}: {
  row: DeviceCentreRow;
  frame: RemoteFrameViewModel;
  grantKind: RemoteAssistanceGrantKind;
  busyAction: BusyAction;
  onGrantKindChange: (kind: RemoteAssistanceGrantKind) => void;
  onRequestGrant: () => void;
  onConnect: () => void;
  onEnd: () => void;
  onRevoke: () => void;
  onReviewPairing: (keyId: string, replacement: boolean) => void;
  onRevokeKey: (keyId: string) => void;
  onCommand: (command: SafeRemoteAssistanceCommand) => void;
}) {
  const { device, grant, session } = row;
  const hasGrantId = Boolean(device.current_grant_id ?? grant?.id ?? session?.grant_id);
  const canRequest = canRequestRemoteGrant(row);
  const canConnect = canConnectRemoteSession(row);
  const canRevoke = hasGrantId && ['requested', 'active'].includes(device.grant_status ?? '');
  const grantPresentation = grantStatusPresentation(device.grant_status);

  return (
    <section className="min-w-0 space-y-4 rounded-2xl border border-bg-border bg-bg-surface/80 p-3 shadow-glow md:p-4">
      <div className="flex flex-col gap-3 border-b border-bg-border pb-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="truncate text-xl font-bold">{deviceName(device)}</h3>
            <StatusPill
              label={device.is_remote_online ? 'Connected' : 'Offline'}
              tone={device.is_remote_online ? 'good' : 'neutral'}
            />
          </div>
          <p className="mt-1 text-xs text-fg-muted">
            Last seen {formatDate(device.remote_support_last_seen_at ?? device.last_seen_at)} · App{' '}
            {device.version_name} ({device.version_code})
          </p>
        </div>
        <div className="text-left text-xs text-fg-muted sm:text-right">
          <p className="font-medium text-fg">{grantPresentation.label}</p>
          <p className="mt-1">{grantPresentation.detail}</p>
        </div>
      </div>

      <DeviceKeyPairingPanel
        device={device}
        busyAction={busyAction}
        onReviewPairing={onReviewPairing}
        onRevokeKey={onRevokeKey}
      />

      <RemoteFramePanel device={device} session={session} frame={frame} />

      <div className="grid min-w-0 gap-4 2xl:grid-cols-[minmax(0,1.45fr)_minmax(280px,0.75fr)]">
        <RemoteControls
          row={row}
          grantKind={grantKind}
          busyAction={busyAction}
          canRequest={canRequest}
          canConnect={canConnect}
          canRevoke={canRevoke}
          onGrantKindChange={onGrantKindChange}
          onRequestGrant={onRequestGrant}
          onConnect={onConnect}
          onEnd={onEnd}
          onRevoke={onRevoke}
          onCommand={onCommand}
        />
        <AuditProtection row={row} />
      </div>
    </section>
  );
}


export function AuditProtection({ row }: { row: DeviceCentreRow }) {
  const { grant, session, device } = row;
  const actor = session?.requested_by_name ?? grant?.requested_by_name ?? 'Protected owner';
  const employee = device.current_grant_responded_by_name ?? grant?.responded_by_name;
  const respondedAt = device.current_grant_responded_at ?? grant?.responded_at;
  const deviceKey = deviceKeyStatusPresentation(
    device.device_key_status,
    Boolean(device.pending_device_key_id && device.device_key_status === 'active'),
  );
  const events = assistanceEvents(device, grant, session);

  return (
    <aside className="space-y-4">
      <section className="rounded-xl border border-bg-border bg-bg/35 p-4">
        <div className="flex items-center gap-2">
          <ShieldCheck className="text-accent" size={18} aria-hidden="true" />
          <h4 className="font-semibold">Audit &amp; protection</h4>
        </div>
        <dl className="mt-4 space-y-3 text-xs">
          <AuditRow icon={UserCheck} label="Requester" value={actor} />
          <AuditRow icon={LockKeyhole} label="Device identity" value={deviceKey.label} />
          <AuditRow
            icon={ShieldCheck}
            label="Employee consent"
            value={device.grant_status === 'active'
              ? `Recorded · ${employee?.trim() || 'actor unavailable'}${respondedAt ? ` · ${formatDate(respondedAt)}` : ''}`
              : grantStatusPresentation(device.grant_status).label}
          />
          <AuditRow
            icon={LockKeyhole}
            label="Access window"
            value={device.current_grant_kind
              ? `${device.current_grant_kind === 'one_time' ? 'One-time' : 'Anytime'} · expires ${formatDate(device.current_grant_expires_at)}`
              : 'No active grant'}
          />
          <AuditRow icon={Clock3} label="Session limit" value="15 minutes" />
          <AuditRow icon={FileClock} label="Audit trail" value="Every action is recorded" />
        </dl>
        <p className="mt-4 border-t border-bg-border pt-3 text-[11px] leading-5 text-fg-muted">
          Controls cannot tap arbitrary coordinates, type text, open other apps, or change payments
          and financial records. Sensitive fields are redacted on the tablet before upload.
        </p>
      </section>

      <section className="rounded-xl border border-bg-border bg-bg/35 p-4">
        <div className="flex items-center gap-2">
          <Activity className="text-accent" size={17} aria-hidden="true" />
          <h4 className="font-semibold">Recent assistance activity</h4>
        </div>
        {events.length ? (
          <ol className="mt-4 space-y-3">
            {events.map((event) => (
              <li key={`${event.title}:${event.at}`} className="grid grid-cols-[9px_1fr] gap-2.5 text-xs">
                <span className={`mt-1.5 h-2 w-2 rounded-full ${event.dotClass}`} aria-hidden="true" />
                <div>
                  <p className="font-medium">{event.title}</p>
                  <p className="mt-0.5 text-fg-muted">{formatDate(event.at)}</p>
                </div>
              </li>
            ))}
          </ol>
        ) : (
          <p className="mt-3 text-xs leading-5 text-fg-muted">
            No assistance request has been recorded for this tablet yet.
          </p>
        )}
      </section>
    </aside>
  );
}


function AuditRow({
  icon: Icon,
  label,
  value,
}: {
  icon: LucideIcon;
  label: string;
  value: string;
}) {
  return (
    <div className="grid grid-cols-[18px_1fr] gap-2">
      <Icon className="mt-0.5 text-fg-subtle" size={15} aria-hidden="true" />
      <div>
        <dt className="text-fg-muted">{label}</dt>
        <dd className="mt-0.5 font-medium text-fg">{value}</dd>
      </div>
    </div>
  );
}
