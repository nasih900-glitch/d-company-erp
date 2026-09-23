import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AlertCircle, CheckCircle2, Copy, Loader2, RefreshCw, ShieldCheck } from 'lucide-react';

import { type ApiError } from '@/lib/api';
import {
  clientInstallations,
  type ClientInstallationDTO,
  type GamingCleanupReconciliationDTO,
  type StationDTO,
} from '@/lib/erp-api';
import { inr } from '@/lib/inr';
import { createOperationKey } from '@/lib/retry-drafts';

function when(value: string | null): string {
  return value ? new Date(value).toLocaleString() : 'Not reported';
}

export function gamingCleanupStationLabel(station: StationDTO | undefined): string {
  return station ? `${station.name} (${station.code})` : 'Station record unavailable';
}

export function gamingCleanupTerminalName(
  device: ClientInstallationDTO | undefined,
  candidateTerminalId: string,
): string {
  if (device?.terminal_id !== candidateTerminalId) return 'Unavailable for this candidate';
  return device.terminal_name?.trim() || 'Unavailable for this candidate';
}

export function gamingCleanupAppVersion(
  row: Pick<
    GamingCleanupReconciliationDTO,
    'reported_app_version_name' | 'reported_app_version_code'
  >,
): string {
  return `v${row.reported_app_version_name} · build ${row.reported_app_version_code}`;
}

function currentGamingCleanupAppVersion(device: ClientInstallationDTO | undefined): string {
  return device
    ? `v${device.version_name} · build ${device.version_code}`
    : 'Unavailable from installation report';
}

export function gamingCleanupIdentityRows(
  row: GamingCleanupReconciliationDTO,
  device: ClientInstallationDTO | undefined,
): Array<{ label: string; value: string }> {
  return [
    { label: 'Branch ID', value: row.branch_id },
    { label: 'Terminal name', value: gamingCleanupTerminalName(device, row.terminal_id) },
    { label: 'Terminal ID', value: row.terminal_id },
    { label: 'Tablet installation ID', value: row.installation_id },
    { label: 'Report-time app version / build', value: gamingCleanupAppVersion(row) },
    { label: 'Candidate record ID', value: row.id },
  ];
}

export function gamingCleanupStatusLabel(
  row: Pick<GamingCleanupReconciliationDTO, 'status' | 'unresolved_child_count'>,
): string {
  if (row.status === 'superseded') return 'Superseded by newer tablet evidence';
  if (row.unresolved_child_count > 0) return 'Blocked';
  if (row.status === 'reported') return 'Evidence verified · owner review required';
  if (row.status === 'approved') return 'Approved · waiting for tablet';
  return 'Applied · tablet acknowledged';
}

export function gamingCleanupApprovalConfirmation(
  row: GamingCleanupReconciliationDTO,
  station: StationDTO | undefined,
  device: ClientInstallationDTO | undefined,
): string {
  const amount = row.review.amount_minor == null
    ? 'tablet-reported amount unavailable'
    : `tablet-reported amount ${inr(row.review.amount_minor)}`;
  const duration = row.review.billable_minutes == null
    ? 'tablet-reported billable duration unavailable'
    : `tablet-reported billable duration ${row.review.billable_minutes} min`;
  return `Approve retiring only ${gamingCleanupStationLabel(station)} (${row.station_id}) `
    + `from branch ${row.branch_id}, terminal ${gamingCleanupTerminalName(device, row.terminal_id)} `
    + `(${row.terminal_id}), installation ${row.installation_id} reported by ${gamingCleanupAppVersion(row)}? `
    + `The local evidence is ${amount} and ${duration}. Candidate ${row.id}, revision ${row.revision}, `
    + `SHA-256 ${row.candidate_sha256}. The exact tablet action is ${row.local_action_id}; `
    + `the server session is ${row.server_session_id}.`;
}

export function canApproveGamingCleanupCandidate(
  row: Pick<GamingCleanupReconciliationDTO, 'status' | 'unresolved_child_count' | 'review'>,
  reason: string,
): boolean {
  const length = reason.trim().length;
  return row.status === 'reported'
    && row.unresolved_child_count === 0
    && row.review.amount_minor !== null
    && row.review.billable_minutes !== null
    && length >= 3
    && length <= 500;
}

export type GamingCleanupApprovalAttempt = { reason: string; key: string };

export function approvalAttemptFor(
  attempts: Map<string, GamingCleanupApprovalAttempt>,
  candidateId: string,
  normalizedReason: string,
  createKey: () => string,
): GamingCleanupApprovalAttempt | null {
  const previous = attempts.get(candidateId);
  if (previous?.reason !== undefined && previous.reason !== normalizedReason) return null;
  const attempt = previous ?? { reason: normalizedReason, key: createKey() };
  attempts.set(candidateId, attempt);
  return attempt;
}

type GamingCleanupRecoveryPanelProps = {
  stations: StationDTO[];
  confirmApproval?: (message: string) => boolean;
};

function CopyableIdentifier({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-fg-muted">{label}</dt>
      <dd className="flex items-start gap-1 font-mono">
        <span className="min-w-0 break-all select-all">{value}</span>
        <button
          type="button"
          className="btn btn-ghost shrink-0 !p-1"
          aria-label={`Copy ${label}`}
          onClick={() => void navigator.clipboard?.writeText(value)}
        >
          <Copy size={12} aria-hidden="true" />
        </button>
      </dd>
    </div>
  );
}

export default function GamingCleanupRecoveryPanel({
  stations,
  confirmApproval,
}: GamingCleanupRecoveryPanelProps) {
  const [rows, setRows] = useState<GamingCleanupReconciliationDTO[]>([]);
  const [devices, setDevices] = useState<ClientInstallationDTO[]>([]);
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const approvalAttempts = useRef(new Map<string, GamingCleanupApprovalAttempt>());

  const load = useCallback(async () => {
    setError(null);
    try {
      const [reconciliations, installations] = await Promise.all([
        clientInstallations.listGamingCleanupReconciliations(),
        clientInstallations.list({ stale_after_hours: 24, limit: 200 }),
      ]);
      setRows(reconciliations.items);
      setDevices(installations.items);
      approvalAttempts.current.clear();
    } catch (cause) {
      setError((cause as ApiError).message || 'Cleanup recovery evidence could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);
  const devicesById = useMemo(
    () => new Map(devices.map((device) => [device.installation_id, device])),
    [devices],
  );
  const stationsById = useMemo(
    () => new Map(stations.map((station) => [station.id, station])),
    [stations],
  );

  async function approve(row: GamingCleanupReconciliationDTO) {
    const reason = (reasons[row.id] ?? '').trim();
    if (row.review.amount_minor === null || row.review.billable_minutes === null) {
      setError('Approval requires a tablet-reported amount and billable duration. Refresh after the tablet reports complete evidence.');
      return;
    }
    if (!canApproveGamingCleanupCandidate(row, reason)) {
      setError('Enter a review reason between 3 and 500 characters.');
      return;
    }
    const confirmation = gamingCleanupApprovalConfirmation(
      row,
      stationsById.get(row.station_id),
      devicesById.get(row.installation_id),
    );
    const confirmed = confirmApproval
      ? confirmApproval(confirmation)
      : globalThis.confirm(confirmation);
    if (!confirmed) return;
    setBusy(row.id);
    setError(null);
    const attempt = approvalAttemptFor(
      approvalAttempts.current, row.id, reason, createOperationKey,
    );
    if (!attempt) {
      setBusy(null);
      setError('Refresh this candidate before changing the approval reason.');
      return;
    }
    try {
      const approved = await clientInstallations.approveGamingCleanupReconciliation(
        row.id,
        row.candidate_sha256,
        reason,
        attempt.key,
      );
      approvalAttempts.current.delete(row.id);
      setRows((current) => current.map((item) => item.id === approved.id ? approved : item));
    } catch (cause) {
      setError((cause as ApiError).message || 'The candidate could not be approved.');
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="card mb-4 border-accent/30" aria-labelledby="gaming-cleanup-title">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          <ShieldCheck size={18} className="mt-0.5 text-accent" aria-hidden="true" />
          <div>
            <h3 id="gaming-cleanup-title" className="font-semibold">Tablet Gaming cleanup recovery</h3>
            <p className="mt-1 text-sm text-fg-muted">
              Only candidates reported by the exact tablet and verified against the immutable production cleanup receipt appear here. Approval retires that one local overlay after the tablet refreshes.
            </p>
          </div>
        </div>
        <button type="button" className="btn btn-ghost !py-1.5" onClick={() => void load()} disabled={loading || busy !== null}>
          {loading ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />} Refresh
        </button>
      </div>
      {error && <p className="mt-3 flex items-center gap-2 text-sm text-accent-bad"><AlertCircle size={14} />{error}</p>}
      {!loading && rows.length === 0 && (
        <p className="mt-4 text-sm text-fg-muted">No tablet has reported an eligible stale Gaming overlay.</p>
      )}
      <div className="mt-4 space-y-3">
        {rows.map((row) => {
          const device = devicesById.get(row.installation_id);
          const station = stationsById.get(row.station_id);
          const blocked = row.unresolved_child_count > 0;
          const incompleteBillingEvidence = row.review.amount_minor === null
            || row.review.billable_minutes === null;
          return (
            <article key={row.id} className="rounded-xl border border-bg-border bg-bg-raised/40 p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <p className="font-medium">{gamingCleanupStationLabel(station)}</p>
                  <p className="mt-1 text-xs text-fg-muted">
                    Reported by {gamingCleanupAppVersion(row)} · current device {currentGamingCleanupAppVersion(device)} · last seen {when(device?.last_seen_at ?? null)} · last sync {when(device?.last_successful_sync_at ?? null)}
                  </p>
                </div>
                <span className={`chip ${row.status === 'applied' ? 'text-accent-good' : row.status === 'approved' ? 'text-accent' : 'text-accent-gold'}`}>
                  {gamingCleanupStatusLabel(row)}
                </span>
              </div>
              <dl className="mt-3 grid gap-2 text-xs sm:grid-cols-2">
                {gamingCleanupIdentityRows(row, device).map(({ label, value }) => (
                  <CopyableIdentifier key={label} label={label} value={value} />
                ))}
                <CopyableIdentifier label="Station ID" value={row.station_id} />
                <CopyableIdentifier label="Server session ID" value={row.server_session_id} />
                <CopyableIdentifier label="Local action ID" value={row.local_action_id} />
                <div><dt className="text-fg-muted">Candidate revision / local state</dt><dd className="font-mono">revision {row.revision} · {row.reported_local_state}</dd></div>
                <div><dt className="text-fg-muted">Verified receipt</dt><dd className="font-mono">audit #{row.cleanup_receipt_audit_id}</dd></div>
                <div><dt className="text-fg-muted">Tablet-reported local amount</dt><dd>{row.review.amount_minor == null ? 'Unavailable' : inr(row.review.amount_minor)}</dd></div>
                <div><dt className="text-fg-muted">Tablet-reported billable duration</dt><dd>{row.review.billable_minutes == null ? 'Unavailable' : `${row.review.billable_minutes} min`}</dd></div>
                <div><dt className="text-fg-muted">Started / ended</dt><dd>{when(row.review.started_at)} / {when(row.review.ended_at)}</dd></div>
                <CopyableIdentifier label="Original action actor" value={row.original_action_user_id} />
                <div><dt className="text-fg-muted">Candidate hash</dt><dd className="break-all font-mono">{row.candidate_sha256}</dd></div>
                <div><dt className="text-fg-muted">Saved child work</dt><dd>{row.unresolved_child_count || 'None'}</dd></div>
              </dl>
              {blocked && <p className="mt-3 text-sm text-accent-bad">Resolve the tablet's saved add-on or extension work before approval.</p>}
              {incompleteBillingEvidence && <p className="mt-3 text-sm text-accent-bad">Approval is blocked until the tablet reports both the local amount and billable duration.</p>}
              {row.status === 'reported' && !blocked && (
                <div className="mt-3 flex flex-col gap-2 sm:flex-row">
                  <input
                    className="input flex-1"
                    value={reasons[row.id] ?? ''}
                    maxLength={500}
                    placeholder="Reviewed reason for retiring this exact stale overlay"
                    onChange={(event) => setReasons((current) => ({ ...current, [row.id]: event.target.value }))}
                  />
                  <button type="button" className="btn btn-primary" disabled={busy !== null || incompleteBillingEvidence} onClick={() => void approve(row)}>
                    {busy === row.id ? <Loader2 size={14} className="animate-spin" /> : <CheckCircle2 size={14} />} Approve exact candidate
                  </button>
                </div>
              )}
              {row.status === 'approved' && <p className="mt-3 text-sm text-accent">Foreground the tablet and refresh Gaming. It will apply and acknowledge this exact directive.</p>}
              {row.status === 'applied' && <p className="mt-3 text-sm text-accent-good">The tablet acknowledged the retired overlay at {when(row.applied_at)}.</p>}
              {row.status === 'superseded' && <p className="mt-3 text-sm text-fg-muted">This immutable revision remains as audit history. Only the current revision can be approved.</p>}
            </article>
          );
        })}
      </div>
    </section>
  );
}
