import type { ApiError } from '@/lib/api';
import type {
  RemoteAssistanceDeviceDTO,
  RemoteAssistanceGrantDTO,
  RemoteAssistanceSessionDTO,
} from '@/lib/erp-api';
import type {
  RemoteAssistanceModule,
  SafeRemoteAssistanceCommand,
} from '@/lib/remote-assistance-policy';

export type BusyAction =
  | 'request'
  | 'connect'
  | 'end'
  | 'revoke'
  | 'navigate'
  | 'refresh'
  | 'sync_now'
  | 'collect_diagnostics'
  | null;

export function isRemoteCommandBusyAction(action: BusyAction): boolean {
  return action === 'navigate'
    || action === 'refresh'
    || action === 'sync_now'
    || action === 'collect_diagnostics';
}

export interface DeviceCentreRow {
  device: RemoteAssistanceDeviceDTO;
  grant: RemoteAssistanceGrantDTO | null;
  session: RemoteAssistanceSessionDTO | null;
}

export function canRequestRemoteGrant(row: DeviceCentreRow): boolean {
  const { device, session } = row;
  const sharingCanBeApproved = device.sharing_capability === 'available'
    || device.sharing_capability === 'permission_required';

  return device.is_remote_online
    && sharingCanBeApproved
    && device.grant_status !== 'requested'
    && device.grant_status !== 'active'
    && session?.status !== 'active';
}

export function canConnectRemoteSession(row: DeviceCentreRow): boolean {
  const { device, grant, session } = row;
  const hasGrantId = Boolean(device.current_grant_id ?? grant?.id ?? session?.grant_id);

  return device.is_remote_online
    && device.sharing_capability === 'available'
    && device.grant_status === 'active'
    && hasGrantId
    && session?.status !== 'active';
}

export type RemoteFrameState =
  | 'inactive'
  | 'loading'
  | 'fresh'
  | 'stale'
  | 'privacy'
  | 'offline'
  | 'error';

export interface RemoteFrameViewModel {
  state: RemoteFrameState;
  src: string | null;
  receivedAt: string | null;
  width: number | null;
  height: number | null;
  message: string | null;
}

export const EMPTY_REMOTE_FRAME: RemoteFrameViewModel = {
  state: 'inactive',
  src: null,
  receivedAt: null,
  width: null,
  height: null,
  message: null,
};

export function joinRemoteAssistanceState(
  devices: RemoteAssistanceDeviceDTO[],
  sessions: RemoteAssistanceSessionDTO[],
  knownGrants: ReadonlyMap<string, RemoteAssistanceGrantDTO>,
): DeviceCentreRow[] {
  const latestByDevice = new Map<string, RemoteAssistanceSessionDTO>();
  for (const session of sessions) {
    const current = latestByDevice.get(session.installation_id);
    if (!current || sessionPriority(session) > sessionPriority(current)) {
      latestByDevice.set(session.installation_id, session);
    }
  }
  return devices.map((device) => ({
    device,
    grant: knownGrants.get(device.installation_id) ?? null,
    session: latestByDevice.get(device.installation_id) ?? null,
  }));
}

function sessionPriority(session: RemoteAssistanceSessionDTO): number {
  const status = session.status === 'active' ? 3 : session.status === 'requested' ? 2 : 1;
  const time = Date.parse(session.started_at ?? session.requested_at);
  return status * 10 ** 15 + (Number.isFinite(time) ? time : 0);
}


const MODULE_LABELS: Record<RemoteAssistanceModule, string> = {
  dashboard: 'Dashboard',
  gaming: 'Gaming',
  pos: 'POS',
  shift: 'Shift',
  help: 'Help',
};

export function commandSuccessMessage(command: SafeRemoteAssistanceCommand): string {
  switch (command.type) {
    case 'navigate': return `The tablet acknowledged opening ${MODULE_LABELS[command.module]} inside ERP.`;
    case 'refresh': return 'The tablet acknowledged the current-screen refresh.';
    case 'sync_now': return 'The tablet acknowledged the safe ERP sync.';
    case 'collect_diagnostics': return 'The tablet acknowledged the redacted diagnostics request.';
  }
}

export function commandRejectionMessage(reasonCode: string | null): string {
  switch (reasonCode) {
    case 'unsupported_command':
      return 'This tablet build does not support that safe ERP command.';
    case 'module_unavailable':
      return 'That ERP module is not available on the tablet.';
    case 'permission_denied':
      return 'The tablet denied this command under the current employee-approved session.';
    case 'not_in_foreground':
      return 'The ERP app is not in the foreground on the tablet.';
    case 'session_inactive':
    case 'session_ended':
      return 'The assistance session ended before the tablet could complete this command.';
    case 'execution_failed':
      return 'The tablet could not complete this safe ERP command.';
    default:
      return 'The tablet rejected this safe ERP command.';
  }
}

export function remoteAssistanceErrorMessage(error: unknown): string {
  const apiError = error as ApiError;
  if (apiError.status === 401 || apiError.status === 403) {
    return 'This account does not have protected System Health access.';
  }
  if (apiError.status === 409) {
    return 'Device state changed while this action was being completed.';
  }
  if (apiError.status === 404) {
    return 'The selected device, grant or session is no longer available.';
  }
  if (apiError.status === 422) {
    return 'The server rejected this request because its safe-session details were invalid.';
  }
  if (apiError.code === 'command_rejected' || apiError.code?.startsWith('command_')) {
    return 'The tablet rejected that safe ERP command. Device state may have changed.';
  }
  if (apiError.status && apiError.status >= 500) {
    return 'The protected remote-assistance service did not complete the request.';
  }
  if (apiError.code === 'network_error') {
    return 'The ERP server could not be reached. No action can be assumed complete.';
  }
  return 'Remote assistance did not complete. Refresh device state before retrying.';
}

export function frameErrorMessage(error: unknown): string {
  const apiError = error as ApiError;
  if (apiError.status === 404) {
    return 'The tablet has not supplied a redacted ERP frame, or sharing is paused for privacy.';
  }
  if (apiError.status === 503) {
    return 'The tablet is offline or the live ERP capture has stopped.';
  }
  if (apiError.status === 401 || apiError.status === 403) {
    return 'Protected owner access is required to view redacted ERP frames.';
  }
  return 'The latest redacted ERP frame could not be retrieved.';
}
