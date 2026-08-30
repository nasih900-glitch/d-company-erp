import type {
  RemoteAssistanceDeviceDTO,
  RemoteAssistanceGrantDTO,
  RemoteAssistanceGrantStatus,
  RemoteAssistanceDeviceKeyStatus,
  RemoteAssistanceSessionDTO,
  RemoteAssistanceSessionStatus,
} from '@/lib/erp-api';

const DATE_TIME = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

export type Tone = 'good' | 'warning' | 'bad' | 'neutral';

export function deviceKeyStatusPresentation(
  status: RemoteAssistanceDeviceKeyStatus | null,
  hasPendingReplacement = false,
): { label: string; shortLabel: string; detail: string; tone: Tone } {
  if (hasPendingReplacement) {
    return {
      label: 'Replacement pairing required',
      shortLabel: 'Pairing required',
      detail: 'A new tablet key is waiting for physical-code approval.',
      tone: 'warning',
    };
  }
  switch (status) {
    case 'active':
      return { label: 'Device key verified', shortLabel: 'Key verified', detail: 'Cryptographic device identity is active.', tone: 'good' };
    case 'pending':
      return { label: 'Pairing required', shortLabel: 'Pairing required', detail: 'Approve the code shown on the physical tablet.', tone: 'warning' };
    case 'revoked':
      return { label: 'Device key revoked', shortLabel: 'Key revoked', detail: 'A new tablet key must be paired.', tone: 'bad' };
    case 'expired':
      return { label: 'Pairing expired', shortLabel: 'Pairing expired', detail: 'Ask staff to start pairing again.', tone: 'neutral' };
    default:
      return { label: 'Pairing required', shortLabel: 'Pairing required', detail: 'No verified device key is active.', tone: 'warning' };
  }
}

export function grantStatusPresentation(status: RemoteAssistanceGrantStatus | null): {
  label: string;
  shortLabel: string;
  detail: string;
  tone: Tone;
} {
  switch (status) {
    case 'requested':
      return { label: 'Approval pending', shortLabel: 'Pending', detail: 'Waiting for the employee.', tone: 'warning' };
    case 'active':
      return { label: 'Access granted', shortLabel: 'Granted', detail: 'Employee approval is active.', tone: 'good' };
    case 'declined':
      return { label: 'Request declined', shortLabel: 'Declined', detail: 'No access was created.', tone: 'bad' };
    case 'revoked':
      return { label: 'Access revoked', shortLabel: 'Revoked', detail: 'A new approval is required.', tone: 'bad' };
    case 'expired':
      return { label: 'Approval expired', shortLabel: 'Expired', detail: 'A new request is required.', tone: 'neutral' };
    case 'consumed':
      return { label: 'One-time access used', shortLabel: 'Used', detail: 'A new approval is required.', tone: 'neutral' };
    default:
      return { label: 'No access grant', shortLabel: 'No grant', detail: 'Employee approval is required.', tone: 'neutral' };
  }
}

export function sessionStatusPresentation(status: RemoteAssistanceSessionStatus | null): {
  label: string;
  shortLabel: string;
  tone: Tone;
} {
  switch (status) {
    case 'requested': return { label: 'Session requested', shortLabel: 'Ready', tone: 'warning' };
    case 'active': return { label: 'Session active', shortLabel: 'Active', tone: 'good' };
    case 'ended': return { label: 'Session ended', shortLabel: 'Ended', tone: 'neutral' };
    case 'expired': return { label: 'Session expired', shortLabel: 'Expired', tone: 'neutral' };
    default: return { label: 'No session', shortLabel: 'No session', tone: 'neutral' };
  }
}


export function assistanceEvents(
  device: RemoteAssistanceDeviceDTO,
  grant: RemoteAssistanceGrantDTO | null,
  session: RemoteAssistanceSessionDTO | null,
): Array<{ title: string; at: string; dotClass: string }> {
  const events: Array<{ title: string; at: string; dotClass: string }> = [];
  const respondedAt = device.current_grant_responded_at ?? grant?.responded_at;
  const grantStatus = device.grant_status ?? grant?.status;
  if (device.device_key_approved_at) {
    events.push({
      title: 'Device key verified',
      at: device.device_key_approved_at,
      dotClass: 'bg-accent-good',
    });
  }
  if (grant?.requested_at) events.push({ title: 'Access requested', at: grant.requested_at, dotClass: 'bg-accent-gold' });
  if (respondedAt) {
    events.push({
      title: grantStatus === 'declined' ? 'Request declined by employee' : 'Access approved by employee',
      at: respondedAt,
      dotClass: grantStatus === 'declined' ? 'bg-accent-bad' : 'bg-accent-good',
    });
  }
  if (session?.started_at) events.push({ title: 'ERP-only session started', at: session.started_at, dotClass: 'bg-accent-good' });
  if (session?.ended_at) events.push({ title: 'Assistance session ended', at: session.ended_at, dotClass: 'bg-fg-subtle' });
  if (grant?.revoked_at) events.push({ title: 'Remote access revoked', at: grant.revoked_at, dotClass: 'bg-accent-bad' });
  return events.sort((a, b) => Date.parse(b.at) - Date.parse(a.at)).slice(0, 5);
}

export function deviceName(device: RemoteAssistanceDeviceDTO): string {
  return device.terminal_name?.trim()
    || `ERP tablet ${device.installation_id.slice(0, 8)}`;
}

export function relativeTime(value: string | null): string {
  if (!value) return 'never';
  const time = Date.parse(value);
  if (!Number.isFinite(time)) return 'unknown';
  const seconds = Math.max(0, Math.round((Date.now() - time) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

export function formatDate(value: string | null): string {
  if (!value) return 'not recorded';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'not recorded' : DATE_TIME.format(date);
}
