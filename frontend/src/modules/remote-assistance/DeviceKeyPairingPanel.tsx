import { useState } from 'react';
import {
  Fingerprint,
  KeyRound,
  Loader2,
  LockKeyhole,
  RefreshCw,
  ShieldCheck,
  ShieldOff,
  UserRoundCheck,
} from 'lucide-react';

import Modal from '@/components/ui/Modal';
import type { RemoteAssistanceDeviceDTO } from '@/lib/erp-api';
import {
  deviceKeyStatusPresentation,
  formatDate,
} from './remote-assistance-presentation';
import { InlineNotice, StatusPill } from './DeviceCentrePrimitives';
import {
  hasActiveDeviceKey,
  isCompletePairingCode,
  isRemoteCommandBusyAction,
  normalizePairingCodeInput,
  PAIRING_CODE_LENGTH,
  type BusyAction,
} from './remote-assistance-state';

export function DeviceKeyPairingPanel({
  device,
  busyAction,
  onReviewPairing,
  onRevokeKey,
}: {
  device: RemoteAssistanceDeviceDTO;
  busyAction: BusyAction;
  onReviewPairing: (keyId: string, replacement: boolean) => void;
  onRevokeKey: (keyId: string) => void;
}) {
  const active = hasActiveDeviceKey(device);
  const pendingKeyId = device.pending_device_key_id;
  const replacement = active && Boolean(pendingKeyId);
  const status = deviceKeyStatusPresentation(device.device_key_status, replacement);
  const pairingBusy = busyAction === 'approve_key';
  const revokeBusy = busyAction === 'revoke_key';
  const commandBusy = isRemoteCommandBusyAction(busyAction);

  return (
    <section
      className="rounded-xl border border-bg-border bg-bg/35 p-4"
      aria-labelledby="device-key-title"
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 items-start gap-2.5">
          <KeyRound className="mt-0.5 shrink-0 text-accent" size={18} aria-hidden="true" />
          <div className="min-w-0">
            <h4 id="device-key-title" className="font-semibold">Trusted tablet identity</h4>
            <p className="mt-0.5 text-xs leading-5 text-fg-muted">
              A verified device key is required before consent requests or sessions can begin.
            </p>
          </div>
        </div>
        <StatusPill label={status.shortLabel} tone={status.tone} />
      </div>

      {active ? (
        <div className="mt-4 rounded-xl border border-accent-good/35 bg-accent-good/5 p-3">
          <div className="flex items-center gap-2 text-sm font-semibold">
            <ShieldCheck className="text-accent-good" size={17} aria-hidden="true" />
            Device key verified
          </div>
          <dl className="mt-3 grid gap-3 text-xs sm:grid-cols-2">
            <KeyMetadata label="Key ID" value={device.device_key_id ?? 'Unavailable'} monospace />
            <KeyMetadata label="Approved" value={formatDate(device.device_key_approved_at)} />
            {device.device_key_fingerprint_sha256 ? (
              <div className="sm:col-span-2">
                <dt className="flex items-center gap-1.5 text-fg-muted">
                  <Fingerprint size={13} aria-hidden="true" /> SHA-256 fingerprint
                </dt>
                <dd className="mt-1 break-all font-mono text-[11px] leading-5 text-fg">
                  {device.device_key_fingerprint_sha256}
                </dd>
              </div>
            ) : null}
          </dl>
          <button
            type="button"
            className="btn btn-ghost mt-3 !min-h-[42px] w-full text-sm text-accent-bad sm:w-auto"
            disabled={busyAction !== null && !commandBusy}
            onClick={() => {
              if (device.device_key_id) onRevokeKey(device.device_key_id);
            }}
          >
            {revokeBusy
              ? <Loader2 className="animate-spin" size={16} aria-hidden="true" />
              : <ShieldOff size={16} aria-hidden="true" />}
            Revoke device key
          </button>
          {!pendingKeyId ? (
            <p className="mt-2 text-[11px] leading-4 text-fg-subtle">
              To replace this key, revoke it here first. Remote support will stop, then the
              signed-in tablet will create a fresh pairing code for staff to read to you.
            </p>
          ) : null}
        </div>
      ) : null}

      {pendingKeyId ? (
        <div className={`mt-4 rounded-xl border p-3 ${
          replacement
            ? 'border-accent-gold/45 bg-accent-gold/5'
            : 'border-accent/40 bg-accent/5'
        }`}>
          <div className="flex items-center gap-2 text-sm font-semibold">
            {replacement
              ? <RefreshCw className="text-accent-gold" size={17} aria-hidden="true" />
              : <UserRoundCheck className="text-accent" size={17} aria-hidden="true" />}
            {replacement ? 'Replacement key waiting' : 'Pairing required'}
          </div>
          <p className="mt-2 text-xs leading-5 text-fg-muted">
            Ask the staff member at this physical tablet to read the 12-character pairing code
            shown in ERP. The code is never prefilled or displayed by this page.
          </p>
          <dl className="mt-3 grid gap-3 text-xs sm:grid-cols-2">
            <KeyMetadata label="Pending key ID" value={pendingKeyId} monospace />
            <KeyMetadata
              label="Enrolled by"
              value={device.pending_device_key_enrolled_by_name?.trim()
                || device.pending_device_key_enrolled_by_user_id
                || 'Actor unavailable'}
            />
            <KeyMetadata label="Enrolled" value={formatDate(device.pending_device_key_enrolled_at)} />
            <KeyMetadata label="Expires" value={formatDate(device.pending_device_key_expires_at)} />
          </dl>
          <button
            type="button"
            className="btn btn-primary mt-3 !min-h-[44px] w-full text-sm sm:w-auto"
            disabled={busyAction !== null}
            onClick={() => onReviewPairing(pendingKeyId, replacement)}
          >
            {pairingBusy
              ? <Loader2 className="animate-spin" size={16} aria-hidden="true" />
              : <LockKeyhole size={16} aria-hidden="true" />}
            {replacement ? 'Review replacement' : 'Enter pairing code'}
          </button>
        </div>
      ) : null}

      {!active && !pendingKeyId ? (
        <div className="mt-4">
          <InlineNotice
            tone={device.device_key_status === 'revoked' ? 'bad' : 'warning'}
            title={status.label}
            message={device.device_key_status === 'expired'
              ? 'The previous pairing request expired. Ask staff to start a new pairing request on the physical tablet.'
              : device.device_key_status === 'revoked'
                ? 'Remote access is blocked. Ask staff to enroll a new key on the physical tablet before pairing again.'
                : 'Ask staff to open Remote Assistance on the physical tablet and start device pairing.'}
          />
        </div>
      ) : null}
    </section>
  );
}

export function DeviceKeyApprovalModal({
  replacement,
  busy,
  error,
  retryAvailable,
  onSubmit,
  onCancel,
}: {
  replacement: boolean;
  busy: boolean;
  error: string | null;
  retryAvailable: boolean;
  onSubmit: (pairingCode: string | null) => void;
  onCancel: () => void;
}) {
  const [pairingCode, setPairingCode] = useState('');
  const complete = isCompletePairingCode(pairingCode);
  const descriptionId = 'device-key-pairing-description';

  return (
    <Modal
      open
      onClose={() => {
        if (!busy) onCancel();
      }}
      title={replacement ? 'Approve replacement key?' : 'Approve this tablet key?'}
      size="sm"
    >
      <form
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          if ((!complete && !retryAvailable) || busy) return;
          if (retryAvailable) {
            onSubmit(null);
            return;
          }
          const submittedCode = pairingCode;
          setPairingCode('');
          onSubmit(submittedCode);
        }}
      >
        <div id={descriptionId} className="space-y-2 text-sm leading-6 text-fg-muted">
          <p>
            Ask staff to read the 12-character code currently shown in ERP on the physical
            tablet, then type it below. This page cannot retrieve or derive that code.
          </p>
          {replacement ? (
            <p className="rounded-lg border border-accent-gold/45 bg-accent-gold/10 p-3 font-medium text-fg">
              Approval replaces the currently trusted key and immediately stops existing remote
              access and sessions. Fresh employee consent is required before reconnecting.
            </p>
          ) : null}
        </div>

        {error ? (
          <div
            className="rounded-lg border border-accent-bad/45 bg-accent-bad/10 p-3 text-sm text-accent-bad"
            role="alert"
          >
            {error}
          </div>
        ) : null}

        {retryAvailable ? (
          <InlineNotice
            tone="warning"
            title="Approval outcome is unknown"
            message="Retry sends the exact same hidden code and approval ID. Cancel to discard it and refresh device state."
          />
        ) : (
          <label className="block">
            <span className="text-xs font-semibold text-fg">Pairing code</span>
            <input
              className="input mt-1 font-mono tracking-[0.24em]"
              type="password"
              value={pairingCode}
              onChange={(event) => setPairingCode(normalizePairingCodeInput(event.target.value))}
              autoComplete="off"
              autoCapitalize="characters"
              spellCheck={false}
              inputMode="text"
              minLength={PAIRING_CODE_LENGTH}
              maxLength={PAIRING_CODE_LENGTH}
              pattern="[0-9A-HJKMNP-TV-Z]{12}"
              aria-describedby={descriptionId}
              aria-invalid={Boolean(error)}
              autoFocus
              required
            />
            <span className="mt-1 block text-[11px] text-fg-subtle">
              {pairingCode.length} of {PAIRING_CODE_LENGTH} characters entered · hidden for privacy
            </span>
            {pairingCode.length === PAIRING_CODE_LENGTH && !complete ? (
              <span className="mt-1 block text-[11px] text-accent-bad">
                Check each letter or number against the code on the tablet.
              </span>
            ) : null}
          </label>
        )}

        <div className="flex justify-end gap-2">
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onCancel}>
            Cancel
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={busy || (!retryAvailable && !complete)}
          >
            {busy ? <Loader2 className="animate-spin" size={14} aria-hidden="true" /> : null}
            {retryAvailable ? 'Retry approval' : 'Approve key'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function KeyMetadata({
  label,
  value,
  monospace = false,
}: {
  label: string;
  value: string;
  monospace?: boolean;
}) {
  return (
    <div className="min-w-0">
      <dt className="text-fg-muted">{label}</dt>
      <dd className={`mt-0.5 break-all font-medium text-fg ${monospace ? 'font-mono text-[11px]' : ''}`}>
        {value}
      </dd>
    </div>
  );
}
