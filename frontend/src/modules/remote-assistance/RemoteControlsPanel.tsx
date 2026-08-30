import {
  CircleStop,
  Clock3,
  CloudCog,
  Gamepad2,
  HelpCircle,
  LayoutDashboard,
  Loader2,
  Navigation,
  RotateCw,
  ShieldOff,
  Store,
  UserCheck,
  Wifi,
  WifiOff,
  Wrench,
  type LucideIcon,
} from 'lucide-react';

import type { RemoteAssistanceGrantKind } from '@/lib/erp-api';
import {
  REMOTE_ASSISTANCE_MODULES,
  type RemoteAssistanceModule,
  type SafeRemoteAssistanceCommand,
} from '@/lib/remote-assistance-policy';
import { InlineNotice } from './DeviceCentrePrimitives';
import {
  isRemoteCommandBusyAction,
  type BusyAction,
  type DeviceCentreRow,
} from './remote-assistance-state';

const MODULE_PRESENTATION: Record<RemoteAssistanceModule, {
  label: string;
  Icon: LucideIcon;
}> = {
  dashboard: { label: 'Dashboard', Icon: LayoutDashboard },
  gaming: { label: 'Gaming', Icon: Gamepad2 },
  pos: { label: 'POS', Icon: Store },
  shift: { label: 'Shift', Icon: Clock3 },
  help: { label: 'Help', Icon: HelpCircle },
};

export function RemoteControls({
  row,
  grantKind,
  selectedModule,
  busyAction,
  canRequest,
  canConnect,
  canRevoke,
  onGrantKindChange,
  onRequestGrant,
  onConnect,
  onEnd,
  onRevoke,
  onModuleChange,
  onCommand,
}: {
  row: DeviceCentreRow;
  grantKind: RemoteAssistanceGrantKind;
  selectedModule: RemoteAssistanceModule;
  busyAction: BusyAction;
  canRequest: boolean;
  canConnect: boolean;
  canRevoke: boolean;
  onGrantKindChange: (kind: RemoteAssistanceGrantKind) => void;
  onRequestGrant: () => void;
  onConnect: () => void;
  onEnd: () => void;
  onRevoke: () => void;
  onModuleChange: (module: RemoteAssistanceModule) => void;
  onCommand: (command: SafeRemoteAssistanceCommand) => void;
}) {
  const sessionActive = row.session?.status === 'active';
  const pending = row.device.grant_status === 'requested';
  const unsupported = row.device.sharing_capability === 'unsupported';
  const capabilityUnknown = row.device.sharing_capability === null;
  const approvalRequired = row.device.sharing_capability === 'permission_required';
  const offline = !row.device.is_remote_online;
  const commandBusy = isRemoteCommandBusyAction(busyAction);

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

      {!sessionActive ? (
        <div className="mt-4 space-y-3">
          <div className="grid gap-2 sm:grid-cols-2">
            <GrantKindButton
              active={grantKind === 'one_time'}
              title="One-time"
              detail="One 15-minute support session"
              onClick={() => onGrantKindChange('one_time')}
            />
            <GrantKindButton
              active={grantKind === 'anytime'}
              title="Anytime access"
              detail="Up to 24 hours or until revoked"
              onClick={() => onGrantKindChange('anytime')}
            />
          </div>
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
      ) : (
        <div className="mt-4 space-y-3">
          <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_auto]">
            <label>
              <span className="mb-1.5 block text-[11px] font-semibold uppercase tracking-wider text-fg-muted">
                Navigate inside ERP
              </span>
              <select
                className="input !min-h-[46px] !py-2.5 text-sm"
                value={selectedModule}
                onChange={(event) => onModuleChange(event.target.value as RemoteAssistanceModule)}
                disabled={busyAction !== null}
              >
                {REMOTE_ASSISTANCE_MODULES.map((module) => (
                  <option key={module} value={module}>{MODULE_PRESENTATION[module].label}</option>
                ))}
              </select>
            </label>
            <ActionButton
              label={`Open ${MODULE_PRESENTATION[selectedModule].label}`}
              Icon={MODULE_PRESENTATION[selectedModule].Icon}
              busy={busyAction === 'navigate'}
              busyLabel="Waiting for tablet…"
              disabled={busyAction !== null}
              onClick={() => onCommand({ type: 'navigate', module: selectedModule })}
              compact
            />
          </div>
          <div className="grid gap-2 sm:grid-cols-3">
            <ActionButton
              label="Refresh current screen"
              Icon={RotateCw}
              busy={busyAction === 'refresh'}
              busyLabel="Waiting for tablet…"
              disabled={busyAction !== null}
              onClick={() => onCommand({ type: 'refresh' })}
              compact
            />
            <ActionButton
              label="Sync now"
              Icon={CloudCog}
              busy={busyAction === 'sync_now'}
              busyLabel="Waiting for tablet…"
              disabled={busyAction !== null}
              onClick={() => onCommand({ type: 'sync_now' })}
              compact
            />
            <ActionButton
              label="Collect diagnostics"
              Icon={Wrench}
              busy={busyAction === 'collect_diagnostics'}
              busyLabel="Waiting for tablet…"
              disabled={busyAction !== null}
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
  onClick,
}: {
  active: boolean;
  title: string;
  detail: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={`rounded-xl border px-3 py-3 text-left transition ${
        active
          ? 'border-accent/70 bg-accent/10 text-fg'
          : 'border-bg-border bg-bg-raised/50 text-fg-muted hover:border-accent/40 hover:text-fg'
      }`}
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
