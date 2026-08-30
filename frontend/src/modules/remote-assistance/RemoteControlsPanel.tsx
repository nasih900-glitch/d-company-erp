import {
  CircleStop,
  Clock3,
  Loader2,
  Navigation,
  RotateCw,
  ShieldOff,
  UserCheck,
  Wifi,
  WifiOff,
  Wrench,
  type LucideIcon,
} from 'lucide-react';

import type { RemoteAssistanceGrantKind } from '@/lib/erp-api';
import {
  type SafeRemoteAssistanceCommand,
} from '@/lib/remote-assistance-policy';
import { InlineNotice } from './DeviceCentrePrimitives';
import {
  hasActiveDeviceKey,
  isRemoteCommandBusyAction,
  type BusyAction,
  type DeviceCentreRow,
  type RemoteFrameViewModel,
} from './remote-assistance-state';

export function RemoteControls({
  row,
  frame,
  grantKind,
  busyAction,
  canRequest,
  canConnect,
  canRevoke,
  onGrantKindChange,
  onRequestGrant,
  onConnect,
  onEnd,
  onRevoke,
  onCommand,
}: {
  row: DeviceCentreRow;
  frame: RemoteFrameViewModel;
  grantKind: RemoteAssistanceGrantKind;
  busyAction: BusyAction;
  canRequest: boolean;
  canConnect: boolean;
  canRevoke: boolean;
  onGrantKindChange: (kind: RemoteAssistanceGrantKind) => void;
  onRequestGrant: () => void;
  onConnect: () => void;
  onEnd: () => void;
  onRevoke: () => void;
  onCommand: (command: SafeRemoteAssistanceCommand) => void;
}) {
  const reportedSessionActive = row.session?.status === 'active';
  const keyActive = hasActiveDeviceKey(row.device);
  const sessionActive = reportedSessionActive && keyActive;
  const pending = row.device.grant_status === 'requested';
  const unsupported = row.device.sharing_capability === 'unsupported';
  const capabilityUnknown = row.device.sharing_capability === null;
  const approvalRequired = row.device.sharing_capability === 'permission_required';
  const offline = !row.device.is_remote_online;
  const commandBusy = isRemoteCommandBusyAction(busyAction);
  const helpFrameLive = !offline && frame.state === 'fresh' && Boolean(frame.src);

  return (
    <section className="rounded-xl border border-bg-border bg-bg/35 p-4" aria-labelledby="remote-controls-title">
      <div className="flex items-start gap-2.5">
        <Navigation className="mt-0.5 shrink-0 text-accent" size={18} aria-hidden="true" />
        <div>
          <h4 id="remote-controls-title" className="font-semibold">Remote assistance controls</h4>
          <p className="mt-0.5 text-xs leading-5 text-fg-muted">
            Safe, time-limited ERP commands only. Every request records the owner and device.
          </p>
        </div>
      </div>

      {!reportedSessionActive ? (
        <div className="mt-4 space-y-3">
          <div className="grid gap-2 sm:grid-cols-2">
            <GrantKindButton
              active={grantKind === 'one_time'}
              title="One-time"
              detail="One 15-minute support session"
              disabled={!keyActive || busyAction !== null}
              onClick={() => onGrantKindChange('one_time')}
            />
            <GrantKindButton
              active={grantKind === 'anytime'}
              title="Anytime access"
              detail="Up to 24 hours or until revoked"
              disabled={!keyActive || busyAction !== null}
              onClick={() => onGrantKindChange('anytime')}
            />
          </div>
          {!keyActive ? (
            <InlineNotice
              tone="warning"
              title="Pairing required"
              message="Approve a device key from the physical tablet before requesting access or connecting."
            />
          ) : null}
          <div className="flex flex-col gap-2 sm:flex-row">
            <ActionButton
              label={offline
                ? 'Tablet offline'
                : pending ? 'Awaiting employee approval' : 'Request employee approval'}
              Icon={offline ? WifiOff : pending ? Clock3 : UserCheck}
              busy={busyAction === 'request'}
              disabled={!canRequest || unsupported || capabilityUnknown || busyAction !== null}
              onClick={onRequestGrant}
              primary
            />
            <ActionButton
              label={row.device.is_remote_online ? 'Connect' : 'Tablet offline'}
              Icon={row.device.is_remote_online ? Wifi : WifiOff}
              busy={busyAction === 'connect'}
              disabled={!canConnect || busyAction !== null}
              onClick={onConnect}
            />
          </div>
          {unsupported ? (
            <InlineNotice
              tone="bad"
              title="Screen sharing is not supported"
              message="Update this tablet to a supported ERP build before requesting assistance."
            />
          ) : null}
          {capabilityUnknown ? (
            <InlineNotice
              tone="warning"
              title="Tablet capability has not reported"
              message="Wait for this ERP build to report its screen-sharing capability before requesting access."
            />
          ) : null}
          {approvalRequired && !offline ? (
            <InlineNotice
              tone="warning"
              title="Tablet connected · approval required"
              message="An employee must approve ERP screen sharing on the tablet before Connect becomes available."
            />
          ) : null}
          {offline ? (
            <InlineNotice
              tone="neutral"
              title="Tablet is offline or stale"
              message="Wait for a recent remote-support heartbeat before sending an access request."
            />
          ) : null}
          {row.device.grant_status === 'declined' ? (
            <InlineNotice
              tone="warning"
              title="The employee declined this request"
              message="No access was created. Confirm they are available before sending a new request."
            />
          ) : null}
          {row.device.grant_status === 'revoked' ? (
            <InlineNotice
              tone="neutral"
              title="Remote access was revoked"
              message="A fresh explicit employee approval is required before another session."
            />
          ) : null}
          {row.device.grant_status === 'expired' || row.device.grant_status === 'consumed' ? (
            <InlineNotice
              tone="neutral"
              title={row.device.grant_status === 'consumed' ? 'One-time access was used' : 'Approval expired'}
              message="Send a new request when the employee is ready for assistance."
            />
          ) : null}
        </div>
      ) : sessionActive ? (
        <div className="mt-4 space-y-3">
          <InlineNotice
            tone="neutral"
            title="Operational screens stay private"
            message="Code 17 cannot remotely open or control Gaming, POS, Shift, payments, settings or financial records."
          />
          <InlineNotice
            tone={helpFrameLive ? 'neutral' : 'warning'}
            title="Ask staff to open Help"
            message={helpFrameLive
              ? 'Help is visible. Refresh and safe diagnostics are available below.'
              : 'Remote assistance cannot move staff away from an operational screen. Help commands stay disabled until a live redacted Help view is visible.'}
          />
          <div className="grid gap-2 sm:grid-cols-2">
            <ActionButton
              label="Refresh Help"
              Icon={RotateCw}
              busy={busyAction === 'refresh'}
              busyLabel="Waiting for tablet…"
              disabled={!helpFrameLive || busyAction !== null}
              onClick={() => onCommand({ type: 'refresh' })}
              compact
            />
            <ActionButton
              label="Collect diagnostics"
              Icon={Wrench}
              busy={busyAction === 'collect_diagnostics'}
              busyLabel="Waiting for tablet…"
              disabled={!helpFrameLive || busyAction !== null}
              onClick={() => onCommand({ type: 'collect_diagnostics' })}
              compact
            />
          </div>
          <ActionButton
            label="End session"
            Icon={CircleStop}
            busy={busyAction === 'end'}
            disabled={busyAction !== null && !commandBusy}
            onClick={onEnd}
            danger
          />
        </div>
      ) : (
        <div className="mt-4 space-y-3">
          <InlineNotice
            tone="bad"
            title="Session controls locked · device key is not active"
            message="The reported session may be stale. No view or ERP command is available until the tablet has a verified key."
          />
          <ActionButton
            label="End reported session"
            Icon={CircleStop}
            busy={busyAction === 'end'}
            disabled={busyAction !== null && !commandBusy}
            onClick={onEnd}
            danger
          />
        </div>
      )}

      <div className="mt-4 border-t border-bg-border pt-4">
        <button
          type="button"
          onClick={onRevoke}
          disabled={!canRevoke || (busyAction !== null && !commandBusy)}
          className="flex min-h-[52px] w-full items-center justify-center gap-2 rounded-xl border border-accent-bad/65 bg-accent-bad/5 px-4 py-3 text-sm font-semibold text-accent-bad transition hover:bg-accent-bad/10 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {busyAction === 'revoke'
            ? <Loader2 className="animate-spin" size={18} aria-hidden="true" />
            : <ShieldOff size={18} aria-hidden="true" />}
          Emergency stop &amp; revoke
        </button>
        <p className="mt-2 text-center text-[11px] leading-4 text-fg-subtle">
          Revoking access also ends any requested or active session on this tablet.
        </p>
      </div>
    </section>
  );
}


function GrantKindButton({
  active,
  title,
  detail,
  disabled,
  onClick,
}: {
  active: boolean;
  title: string;
  detail: string;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      disabled={disabled}
      className={`rounded-xl border px-3 py-3 text-left transition ${
        active
          ? 'border-accent/70 bg-accent/10 text-fg'
          : 'border-bg-border bg-bg-raised/50 text-fg-muted hover:border-accent/40 hover:text-fg'
      } disabled:cursor-not-allowed disabled:opacity-40`}
    >
      <span className="block text-sm font-semibold">{title}</span>
      <span className="mt-0.5 block text-[11px] leading-4 text-fg-muted">{detail}</span>
    </button>
  );
}

function ActionButton({
  label,
  Icon,
  busy,
  busyLabel,
  disabled,
  onClick,
  primary,
  danger,
  compact,
}: {
  label: string;
  Icon: LucideIcon;
  busy: boolean;
  busyLabel?: string;
  disabled: boolean;
  onClick: () => void;
  primary?: boolean;
  danger?: boolean;
  compact?: boolean;
}) {
  const variant = danger ? 'btn-danger' : primary ? 'btn-primary' : 'btn-ghost';
  return (
    <button
      type="button"
      className={`btn ${variant} w-full text-sm ${compact ? '!min-h-[46px] !px-3 !py-2.5' : '!min-h-[50px] !px-4 !py-3'}`}
      disabled={disabled}
      onClick={onClick}
    >
      {busy
        ? <Loader2 className="animate-spin" size={16} aria-hidden="true" />
        : <Icon size={16} aria-hidden="true" />}
      {busy ? busyLabel ?? label : label}
    </button>
  );
}
