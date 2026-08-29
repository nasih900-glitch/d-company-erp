import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AlertCircle,
  Cloud,
  Copy,
  HardDrive,
  Loader2,
  PackageCheck,
  RefreshCw,
  Rocket,
  ShieldCheck,
  TabletSmartphone,
  WifiOff,
  XCircle,
} from 'lucide-react';

import { ConfirmModal } from '@/components/ui/ConfirmDialog';
import { Skeleton, SkeletonCard } from '@/components/ui/Skeleton';
import { useNotifications } from '@/components/ui/Notifications';
import {
  androidReleases,
  clientInstallations,
  type AndroidReleaseDTO,
  type AndroidReleaseStatus,
  type ClientDistributionChannel,
  type ClientInstallationDTO,
  type ClientInstallationListDTO,
  type ClientUpdateErrorCode,
  type ClientUpdateState,
} from '@/lib/erp-api';

type ReleaseAction = 'activate' | 'withdraw';
type PendingReleaseAction = { action: ReleaseAction; release: AndroidReleaseDTO };

export interface ReleaseEvidenceRow {
  key: string;
  label: string;
  value: string;
  wide?: boolean;
}

const DATE_TIME = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

const CHANNEL_LABELS: Record<ClientDistributionChannel, string> = {
  direct: 'Direct update',
  play: 'Play Store',
  managed: 'Managed / test',
};

const UPDATE_STATES: Record<ClientUpdateState, { label: string; className: string }> = {
  idle: { label: 'Ready', className: 'border-bg-border bg-bg-raised text-fg-muted' },
  update_available: {
    label: 'Update available',
    className: 'border-accent-gold/40 bg-accent-gold/10 text-accent-gold',
  },
  downloading: {
    label: 'Downloading',
    className: 'border-accent/40 bg-accent/10 text-accent',
  },
  verifying: {
    label: 'Verifying',
    className: 'border-accent/40 bg-accent/10 text-accent',
  },
  verified: {
    label: 'Ready to install',
    className: 'border-accent-good/40 bg-accent-good/10 text-accent-good',
  },
  installer_opened: {
    label: 'Installer opened',
    className: 'border-accent/40 bg-accent/10 text-accent',
  },
  failed: {
    label: 'Update needs attention',
    className: 'border-accent-bad/50 bg-accent-bad/10 text-accent-bad',
  },
};

const UPDATE_ERRORS: Record<ClientUpdateErrorCode, string> = {
  network_error: 'Network connection failed',
  http_error: 'Update server rejected the download',
  insufficient_storage: 'Not enough tablet storage',
  invalid_metadata: 'Release details are incomplete',
  size_mismatch: 'Downloaded file size did not match',
  checksum_mismatch: 'Security checksum did not match',
  archive_unreadable: 'Android could not read the APK',
  package_mismatch: 'APK belongs to a different app',
  version_mismatch: 'APK version did not match the offer',
  signer_mismatch: 'APK signing identity did not match',
  installer_permission_denied: 'Install permission was not granted',
  installer_unavailable: 'Android Package Installer was unavailable',
  installer_not_completed: 'Installation was not completed',
  unknown: 'The update could not be completed',
};

const RELEASE_STATUS: Record<AndroidReleaseStatus, { label: string; className: string }> = {
  active: {
    label: 'Active offer',
    className: 'border-accent-good/40 bg-accent-good/10 text-accent-good',
  },
  staged: {
    label: 'Staged',
    className: 'border-accent-gold/40 bg-accent-gold/10 text-accent-gold',
  },
  withdrawn: {
    label: 'Withdrawn',
    className: 'border-bg-border bg-bg-raised text-fg-muted',
  },
};

export default function DevicesUpdatesTab() {
  const notifications = useNotifications();
  const requestSequence = useRef(0);
  const [installations, setInstallations] = useState<ClientInstallationListDTO | null>(null);
  const [releases, setReleases] = useState<AndroidReleaseDTO[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingReleaseAction | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [evidenceReviewed, setEvidenceReviewed] = useState(false);

  const load = useCallback(async (background = false) => {
    const request = ++requestSequence.current;
    if (background) setRefreshing(true);
    else setLoading(true);
    setLoadError(null);
    try {
      const [installationData, releaseData] = await Promise.all([
        clientInstallations.list({ stale_after_hours: 24, limit: 200, offset: 0 }),
        androidReleases.list(200),
      ]);
      if (request !== requestSequence.current) return;
      setInstallations(installationData);
      setReleases(releaseData.items);
    } catch (error) {
      if (request === requestSequence.current) {
        setLoadError(errorMessage(error));
      }
    } finally {
      if (request === requestSequence.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, []);

  useEffect(() => {
    void load();
    return () => { requestSequence.current += 1; };
  }, [load]);

  async function runReleaseAction() {
    if (!pendingAction || !canConfirmReleaseAction(pendingAction, evidenceReviewed, busyAction)) {
      return;
    }
    const { action, release } = pendingAction;
    const actionKey = `${action}:${release.id}`;
    setBusyAction(actionKey);
    setActionError(null);
    try {
      if (action === 'activate') await androidReleases.activate(release.id);
      else await androidReleases.withdraw(release.id);
      setPendingAction(null);
      setEvidenceReviewed(false);
      notifications.success(
        action === 'activate'
          ? `Version ${release.version_name} is now the active direct-update offer.`
          : `Version ${release.version_name} is no longer being offered to tablets.`,
        { title: action === 'activate' ? 'Update offered' : 'Update withdrawn' },
      );
      await load(true);
    } catch (error) {
      const message = errorMessage(error);
      setActionError(message);
      notifications.error(message, {
        title: action === 'activate' ? 'Update was not offered' : 'Update was not withdrawn',
      });
    } finally {
      setBusyAction(null);
    }
  }

  function requestReleaseAction(action: PendingReleaseAction) {
    setActionError(null);
    setEvidenceReviewed(false);
    setPendingAction(action);
  }

  async function copyEvidence(label: string, value: string) {
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('Clipboard access is unavailable');
      }
      await navigator.clipboard.writeText(value);
      notifications.success(`${label} copied to the clipboard.`, {
        title: 'Release evidence copied',
      });
    } catch {
      notifications.error(
        `Could not copy ${label}. The complete value remains visible so you can select it manually.`,
        { title: 'Clipboard unavailable' },
      );
    }
  }

  if (loading && (!installations || !releases)) return <DevicesUpdatesSkeleton />;

  if (!installations || !releases) {
    return (
      <div className="card max-w-3xl">
        <ErrorPanel
          title="Devices & Updates could not be loaded"
          message={loadError || 'The server did not return the owner update controls.'}
        />
        <button type="button" className="btn btn-primary mt-4" onClick={() => void load()}>
          <RefreshCw size={16} aria-hidden="true" /> Try again
        </button>
      </div>
    );
  }

  const liveInstallations = installations.items.filter((item) => !item.is_stale).length;
  const pendingOutbox = installations.items.reduce(
    (total, item) => total + item.pending_outbox_count,
    0,
  );
  const activeRelease = releases.find((release) => release.status === 'active') ?? null;
  const stagedReleases = releases.filter((release) => release.status === 'staged');
  const withdrawnReleases = releases.filter((release) => release.status === 'withdrawn');

  return (
    <div className="max-w-6xl space-y-4">
      <section className="card flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div className="flex min-w-0 items-start gap-3">
          <div className="rounded-xl border border-accent/25 bg-accent/10 p-2.5 text-accent">
            <ShieldCheck size={20} aria-hidden="true" />
          </div>
          <div>
            <h3 className="font-bold">Tablet health and Android release control</h3>
            <p className="mt-1 max-w-3xl text-sm text-fg-muted">
              Tablets report after an authenticated sign-in. Offering a release never installs it
              silently: Android still asks the employee to approve the update, and the tablet's
              saved sales and Sync queue are left untouched. The owner must compare the recorded
              artifact evidence with the approved CI run before offering it.
            </p>
          </div>
        </div>
        <button
          type="button"
          className="btn btn-ghost shrink-0"
          onClick={() => void load(true)}
          disabled={refreshing || busyAction !== null}
        >
          <RefreshCw
            size={16}
            className={refreshing ? 'animate-spin' : ''}
            aria-hidden="true"
          />
          {refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </section>

      {loadError ? (
        <ErrorPanel
          title="Latest status could not be refreshed"
          message={`${loadError} The last successfully loaded status remains visible below.`}
        />
      ) : null}
      {actionError ? (
        <ErrorPanel title="Release action needs attention" message={actionError} />
      ) : null}

      <section className="grid grid-cols-2 gap-3 lg:grid-cols-4" aria-label="Device update summary">
        <SummaryCard
          icon={<TabletSmartphone size={18} aria-hidden="true" />}
          label="Known tablets"
          value={String(installations.total)}
          detail={`${liveInstallations} seen in the last ${installations.stale_after_hours} hours`}
        />
        <SummaryCard
          icon={<Cloud size={18} aria-hidden="true" />}
          label="Recently online"
          value={String(liveInstallations)}
          detail={installations.items.length ? 'Authenticated heartbeat received' : 'No heartbeat yet'}
          tone={liveInstallations > 0 ? 'good' : 'neutral'}
        />
        <SummaryCard
          icon={<HardDrive size={18} aria-hidden="true" />}
          label="Waiting to sync"
          value={String(pendingOutbox)}
          detail={pendingOutbox ? 'Saved tablet actions still pending' : 'Reported queues are clear'}
          tone={pendingOutbox ? 'warning' : 'good'}
        />
        <SummaryCard
          icon={<PackageCheck size={18} aria-hidden="true" />}
          label="Active release"
          value={activeRelease ? `v${activeRelease.version_name}` : 'None'}
          detail={activeRelease ? `Android code ${activeRelease.version_code}` : 'No update is being offered'}
          tone={activeRelease ? 'good' : 'neutral'}
        />
      </section>

      <section className="card">
        <SectionHeading
          icon={<TabletSmartphone size={18} aria-hidden="true" />}
          title="Android installations"
          detail="Version, update progress, employee context and offline Sync health."
        />
        {installations.items.length ? (
          <div className="mt-4 grid gap-3 xl:grid-cols-2">
            {installations.items.map((installation) => (
              <InstallationCard
                key={installation.installation_id}
                installation={installation}
                serverTime={installations.server_time}
              />
            ))}
          </div>
        ) : (
          <EmptyPanel
            icon={<TabletSmartphone size={24} aria-hidden="true" />}
            title="No tablet has reported yet"
            detail="A signed update-capable Android build appears here after it is installed and a staff member signs in successfully."
          />
        )}
        {installations.total > installations.items.length ? (
          <p className="mt-3 text-xs text-fg-muted">
            Showing the newest {installations.items.length} of {installations.total} installations.
          </p>
        ) : null}
      </section>

      <section className="space-y-3">
        <div className="card">
          <SectionHeading
            icon={<PackageCheck size={18} aria-hidden="true" />}
            title="Direct Android releases"
            detail="Only records staged by the protected server release workflow appear here; the browser does not independently verify their APK bytes."
          />
        </div>

        {!releases.length ? (
          <div className="card">
            <EmptyPanel
              icon={<PackageCheck size={24} aria-hidden="true" />}
              title="No release is staged"
              detail="There is no browser upload here. A release operator must register immutable APK metadata and CI provenance before the owner can review an offer."
            />
          </div>
        ) : (
          <>
            <ReleaseGroup
              title="Active offer"
              detail="The one release currently announced to eligible direct-install tablets."
              releases={activeRelease ? [activeRelease] : []}
              emptyText="No Android update is currently being offered."
              busyAction={busyAction}
              onAction={requestReleaseAction}
              onCopy={copyEvidence}
            />
            <ReleaseGroup
              title="Staged releases"
              detail="Release records waiting for evidence review and an owner decision."
              releases={stagedReleases}
              emptyText="No staged release is waiting for review."
              busyAction={busyAction}
              onAction={requestReleaseAction}
              onCopy={copyEvidence}
            />
            <ReleaseGroup
              title="Withdrawn history"
              detail="Retained evidence of releases that are no longer offered."
              releases={withdrawnReleases}
              emptyText="No release has been withdrawn."
              busyAction={busyAction}
              onAction={requestReleaseAction}
              onCopy={copyEvidence}
            />
          </>
        )}
      </section>

      {pendingAction ? (
        <ConfirmModal
          title={pendingAction.action === 'activate' ? 'Offer this Android update?' : 'Withdraw this Android update?'}
          message={(
            <ReleaseActionConfirmation
              pending={pendingAction}
              previousError={actionError}
              evidenceReviewed={evidenceReviewed}
              onEvidenceReviewed={setEvidenceReviewed}
              onCopy={copyEvidence}
            />
          )}
          confirmLabel={pendingAction.action === 'activate' ? 'Offer update' : 'Withdraw offer'}
          danger={pendingAction.action === 'withdraw'}
          busy={busyAction === `${pendingAction.action}:${pendingAction.release.id}`}
          confirmDisabled={!canConfirmReleaseAction(pendingAction, evidenceReviewed, busyAction)}
          size="lg"
          onCancel={() => {
            setPendingAction(null);
            setActionError(null);
            setEvidenceReviewed(false);
          }}
          onConfirm={() => void runReleaseAction()}
        />
      ) : null}
    </div>
  );
}

function DevicesUpdatesSkeleton() {
  return (
    <div className="max-w-6xl space-y-4" aria-label="Loading Devices & Updates">
      <SkeletonCard lines={3} />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, index) => (
          <div key={index} className="card space-y-3">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-8 w-1/3" />
            <Skeleton className="h-3 w-4/5" />
          </div>
        ))}
      </div>
      <SkeletonCard lines={5} />
    </div>
  );
}

function SummaryCard({
  icon,
  label,
  value,
  detail,
  tone = 'neutral',
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  detail: string;
  tone?: 'neutral' | 'good' | 'warning';
}) {
  const toneClass = tone === 'good'
    ? 'text-accent-good'
    : tone === 'warning' ? 'text-accent-gold' : 'text-fg-muted';
  return (
    <div className="card min-w-0 !p-4">
      <div className={`flex items-center gap-2 text-xs font-medium ${toneClass}`}>
        {icon} <span>{label}</span>
      </div>
      <p className="mt-2 truncate font-mono text-2xl font-semibold tabular-nums text-fg">{value}</p>
      <p className="mt-1 text-xs text-fg-muted">{detail}</p>
    </div>
  );
}

function SectionHeading({
  icon,
  title,
  detail,
}: {
  icon: React.ReactNode;
  title: string;
  detail: string;
}) {
  return (
    <div className="flex items-start gap-3">
      <div className="rounded-lg bg-bg-raised p-2 text-fg-muted">{icon}</div>
      <div>
        <h3 className="font-bold">{title}</h3>
        <p className="mt-0.5 text-sm text-fg-muted">{detail}</p>
      </div>
    </div>
  );
}

function InstallationCard({
  installation,
  serverTime,
}: {
  installation: ClientInstallationDTO;
  serverTime: string;
}) {
  const updateState = UPDATE_STATES[installation.update_state];
  return (
    <article className="rounded-xl border border-bg-border bg-bg-raised/35 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <div className={`rounded-xl p-2.5 ${installation.is_stale ? 'bg-accent-bad/10 text-accent-bad' : 'bg-accent-good/10 text-accent-good'}`}>
            {installation.is_stale
              ? <WifiOff size={19} aria-hidden="true" />
              : <TabletSmartphone size={19} aria-hidden="true" />}
          </div>
          <div className="min-w-0">
            <h4 className="truncate font-semibold">
              {installation.terminal_name || 'Android tablet'}
            </h4>
            <p className="mt-0.5 text-xs text-fg-muted">
              {CHANNEL_LABELS[installation.distribution_channel]} · installation{' '}
              <span className="font-mono">{shortId(installation.installation_id)}</span>
            </p>
          </div>
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <StatusPill
            label={installation.is_stale ? 'Not reporting' : 'Recently online'}
            className={installation.is_stale
              ? 'border-accent-bad/40 bg-accent-bad/10 text-accent-bad'
              : 'border-accent-good/40 bg-accent-good/10 text-accent-good'}
          />
          <StatusPill label={updateState.label} className={updateState.className} />
        </div>
      </div>

      <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
        <InfoItem label="Installed version" value={`v${installation.version_name} · code ${installation.version_code}`} mono />
        <InfoItem
          label="Last seen"
          value={`${relativeTime(installation.last_seen_at, serverTime)} · ${formatDate(installation.last_seen_at)}`}
        />
        <InfoItem
          label="Last successful Sync"
          value={installation.last_successful_sync_at
            ? formatDate(installation.last_successful_sync_at)
            : 'Not reported yet'}
        />
        <InfoItem
          label="Saved actions waiting"
          value={installation.pending_outbox_count
            ? `${installation.pending_outbox_count} pending`
            : 'Queue clear'}
          valueClass={installation.pending_outbox_count ? 'text-accent-gold' : 'text-accent-good'}
          mono
        />
        <InfoItem label="Last employee" value={installation.last_user_name || 'Not reported'} />
        <InfoItem label="Workspace" value={installation.terminal_name || 'No workspace header'} />
      </dl>

      {installation.update_error_code ? (
        <div className="mt-4 flex items-start gap-2 rounded-lg border border-accent-bad/35 bg-accent-bad/10 p-3 text-sm">
          <AlertCircle className="mt-0.5 shrink-0 text-accent-bad" size={15} aria-hidden="true" />
          <div>
            <p className="font-medium text-accent-bad">Update failed on this tablet</p>
            <p className="mt-0.5 text-xs text-fg-muted">
              {UPDATE_ERRORS[installation.update_error_code]}
            </p>
          </div>
        </div>
      ) : null}
    </article>
  );
}

function InfoItem({
  label,
  value,
  mono,
  valueClass = 'text-fg',
}: {
  label: string;
  value: string;
  mono?: boolean;
  valueClass?: string;
}) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-fg-muted">{label}</dt>
      <dd className={`mt-0.5 break-words font-medium ${mono ? 'font-mono tabular-nums' : ''} ${valueClass}`}>
        {value}
      </dd>
    </div>
  );
}

function ReleaseGroup({
  title,
  detail,
  releases,
  emptyText,
  busyAction,
  onAction,
  onCopy,
}: {
  title: string;
  detail: string;
  releases: AndroidReleaseDTO[];
  emptyText: string;
  busyAction: string | null;
  onAction: (action: PendingReleaseAction) => void;
  onCopy: (label: string, value: string) => void;
}) {
  return (
    <section className="card">
      <div>
        <h4 className="font-semibold">{title}</h4>
        <p className="mt-0.5 text-xs text-fg-muted">{detail}</p>
      </div>
      {releases.length ? (
        <div className="mt-4 grid gap-3">
          {releases.map((release) => (
            <ReleaseCard
              key={release.id}
              release={release}
              busyAction={busyAction}
              onAction={onAction}
              onCopy={onCopy}
            />
          ))}
        </div>
      ) : (
        <div className="mt-4 rounded-xl border border-dashed border-bg-border px-4 py-5 text-sm text-fg-muted">
          {emptyText}
        </div>
      )}
    </section>
  );
}

function ReleaseCard({
  release,
  busyAction,
  onAction,
  onCopy,
}: {
  release: AndroidReleaseDTO;
  busyAction: string | null;
  onAction: (action: PendingReleaseAction) => void;
  onCopy: (label: string, value: string) => void;
}) {
  const status = RELEASE_STATUS[release.status];
  const [archivedEvidenceOpen, setArchivedEvidenceOpen] = useState(false);
  const activating = busyAction === `activate:${release.id}`;
  const withdrawing = busyAction === `withdraw:${release.id}`;
  const eventDate = release.status === 'active'
    ? release.activated_at
    : release.status === 'withdrawn' ? release.withdrawn_at : release.registered_at;
  const reviewableEvidence = hasReviewableReleaseEvidence(release);
  const showEvidence = release.status !== 'withdrawn' || archivedEvidenceOpen;

  return (
    <article className="rounded-xl border border-bg-border bg-bg-raised/35 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-fg-muted">Android direct</p>
          <h5 className="mt-1 font-mono text-xl font-semibold tabular-nums">
            v{release.version_name} <span className="text-sm text-fg-muted">· code {release.version_code}</span>
          </h5>
        </div>
        <StatusPill label={status.label} className={status.className} />
      </div>

      <p className="mt-3 whitespace-pre-line text-sm text-fg-muted">
        {release.release_notes || 'No release notes were supplied.'}
      </p>

      {release.status === 'withdrawn' ? (
        <button
          type="button"
          className="mt-4 inline-flex min-h-[44px] items-center rounded-lg border border-bg-border px-3 py-2 text-sm font-medium text-fg-muted hover:bg-bg-raised hover:text-fg"
          aria-expanded={archivedEvidenceOpen}
          onClick={() => setArchivedEvidenceOpen((open) => !open)}
        >
          {archivedEvidenceOpen ? 'Hide archived evidence' : 'Review archived evidence'}
        </button>
      ) : null}

      {showEvidence ? (
        <section className="mt-4 rounded-xl border border-bg-border bg-bg-surface/50 p-3" aria-label={`Release evidence for version ${release.version_name}`}>
          <h6 className="text-sm font-semibold">Artifact and CI provenance</h6>
          <p className="mt-0.5 text-xs text-fg-muted">
            Full values recorded by the staging workflow. This browser displays the record; it does
            not independently download or verify the APK or CI run.
          </p>
          <ReleaseEvidence release={release} onCopy={onCopy} />
        </section>
      ) : null}

      {!reviewableEvidence ? (
        <div className="mt-3 flex items-start gap-2 rounded-lg border border-accent-bad/35 bg-accent-bad/10 p-3 text-sm" role="alert">
          <AlertCircle className="mt-0.5 shrink-0 text-accent-bad" size={15} aria-hidden="true" />
          <p className="text-fg-muted">
            Required release evidence is missing or malformed. This record cannot be offered until
            the protected staging workflow creates a complete replacement.
          </p>
        </div>
      ) : null}

      <div className="mt-4 grid grid-cols-2 gap-3 border-t border-bg-border pt-3 text-xs">
        <div>
          <p className="text-fg-muted">APK size</p>
          <p className="mt-0.5 font-mono font-medium tabular-nums">{formatBytes(release.apk_size_bytes)}</p>
        </div>
        <div>
          <p className="text-fg-muted">{release.status === 'staged' ? 'Registered' : status.label}</p>
          <p className="mt-0.5 font-medium">{formatDate(eventDate)}</p>
        </div>
      </div>

      <div className="mt-4 flex flex-col gap-2 sm:flex-row sm:justify-end">
        {release.status !== 'active' ? (
          <button
            type="button"
            className="btn btn-primary !min-h-[44px] !px-4 !py-2 text-sm"
            disabled={busyAction !== null || !reviewableEvidence}
            onClick={() => onAction({ action: 'activate', release })}
          >
            {activating
              ? <Loader2 size={15} className="animate-spin" aria-hidden="true" />
              : <Rocket size={15} aria-hidden="true" />}
            {release.status === 'withdrawn' ? 'Review & offer again' : 'Review & offer'}
          </button>
        ) : null}
        {release.status !== 'withdrawn' ? (
          <button
            type="button"
            className="btn btn-ghost !min-h-[44px] !px-4 !py-2 text-sm text-accent-bad"
            disabled={busyAction !== null}
            onClick={() => onAction({ action: 'withdraw', release })}
          >
            {withdrawing
              ? <Loader2 size={15} className="animate-spin" aria-hidden="true" />
              : <XCircle size={15} aria-hidden="true" />}
            Withdraw
          </button>
        ) : null}
      </div>
    </article>
  );
}

export function releaseEvidenceRows(release: AndroidReleaseDTO): ReleaseEvidenceRow[] {
  return [
    {
      key: 'version_code',
      label: 'Version code',
      value: String(release.version_code),
    },
    {
      key: 'source_release_ref',
      label: 'Source release ref',
      value: release.source_release_ref,
    },
    {
      key: 'update_url',
      label: 'Immutable APK URL',
      value: release.update_url,
      wide: true,
    },
    {
      key: 'apk_sha256',
      label: 'APK SHA-256',
      value: release.apk_sha256,
      wide: true,
    },
    {
      key: 'apk_signing_cert_sha256',
      label: 'Signing certificate SHA-256',
      value: release.apk_signing_cert_sha256,
      wide: true,
    },
    {
      key: 'manifest_sha256',
      label: 'Source manifest SHA-256',
      value: release.manifest_sha256,
      wide: true,
    },
    {
      key: 'source_git_sha',
      label: 'Source Git SHA',
      value: release.source_git_sha,
      wide: true,
    },
    {
      key: 'source_workflow_run_id',
      label: 'CI workflow run ID',
      value: release.source_workflow_run_id,
    },
    {
      key: 'source_workflow_run_attempt',
      label: 'CI workflow attempt',
      value: String(release.source_workflow_run_attempt),
    },
  ];
}

export function hasReviewableReleaseEvidence(release: AndroidReleaseDTO): boolean {
  const sha256 = /^[0-9a-f]{64}$/;
  return Number.isInteger(release.version_code)
    && release.version_code > 0
    && release.version_code <= 2_147_483_647
    && release.update_url.startsWith('https://')
    && sha256.test(release.apk_sha256)
    && sha256.test(release.apk_signing_cert_sha256)
    && sha256.test(release.manifest_sha256)
    && /^[0-9a-f]{40}$/.test(release.source_git_sha)
    && release.source_release_ref === `v${release.version_name}`
    && /^[1-9][0-9]*$/.test(release.source_workflow_run_id)
    && Number.isInteger(release.source_workflow_run_attempt)
    && release.source_workflow_run_attempt > 0
    && release.source_workflow_run_attempt <= 2_147_483_647;
}

export function canConfirmReleaseAction(
  pending: PendingReleaseAction,
  evidenceReviewed: boolean,
  busyAction: string | null,
): boolean {
  if (busyAction !== null) return false;
  if (pending.action === 'withdraw') return true;
  return evidenceReviewed && hasReviewableReleaseEvidence(pending.release);
}

export function ReleaseEvidence({
  release,
  onCopy,
}: {
  release: AndroidReleaseDTO;
  onCopy: (label: string, value: string) => void;
}) {
  return (
    <dl className="mt-3 grid min-w-0 gap-2 sm:grid-cols-2">
      {releaseEvidenceRows(release).map((row) => (
        <div
          key={row.key}
          className={`min-w-0 rounded-lg border border-bg-border bg-bg-raised/45 p-3 ${row.wide ? 'sm:col-span-2' : ''}`}
        >
          <dt className="text-xs font-medium text-fg-muted">{row.label}</dt>
          <dd className="mt-1 flex min-w-0 items-start gap-2">
            <code className="min-w-0 flex-1 select-all break-all font-mono text-xs leading-5 text-fg">
              {row.value}
            </code>
            <button
              type="button"
              className="inline-flex min-h-[44px] shrink-0 items-center gap-1.5 rounded-lg border border-bg-border px-3 py-2 text-xs font-medium text-fg-muted transition hover:bg-bg-raised hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              aria-label={`Copy ${row.label}`}
              title={`Copy ${row.label}`}
              onClick={() => onCopy(row.label, row.value)}
            >
              <Copy size={14} aria-hidden="true" />
              Copy
            </button>
          </dd>
        </div>
      ))}
    </dl>
  );
}

export function ReleaseActionConfirmation({
  pending,
  previousError,
  evidenceReviewed,
  onEvidenceReviewed,
  onCopy,
}: {
  pending: PendingReleaseAction;
  previousError: string | null;
  evidenceReviewed: boolean;
  onEvidenceReviewed: (reviewed: boolean) => void;
  onCopy: (label: string, value: string) => void;
}) {
  const { action, release } = pending;
  const offering = action === 'activate';
  const reviewableEvidence = hasReviewableReleaseEvidence(release);
  return (
    <div className="space-y-4 text-sm text-fg-muted">
      <p>
        {offering
          ? `Offering version ${release.version_name} (code ${release.version_code}) publishes this staged record to eligible direct-install tablets and replaces any other active offer. Android still requires installation approval on each tablet.`
          : `Withdrawing version ${release.version_name} (code ${release.version_code}) stops future offers. Tablets that already installed it are unchanged, and saved sales or offline work are not deleted.`}
      </p>

      <div className="rounded-lg border border-accent-gold/35 bg-accent-gold/10 p-3" role="note">
        <p className="font-semibold text-fg">Recorded evidence — review against the CI release</p>
        <p className="mt-1 text-xs leading-5">
          These values are claims stored by the protected staging workflow. This browser has not
          fetched the APK or independently verified its bytes, signing certificate, source commit,
          or workflow run.
        </p>
      </div>

      <ReleaseEvidence release={release} onCopy={onCopy} />

      {offering && !reviewableEvidence ? (
        <div className="rounded-lg border border-accent-bad/40 bg-accent-bad/10 p-3" role="alert">
          Required evidence is missing or malformed. Cancel this approval and ask the release
          operator to create a complete staged record.
        </div>
      ) : null}

      {offering ? (
        <label className={`flex min-h-[52px] items-start gap-3 rounded-xl border p-3 ${reviewableEvidence ? 'cursor-pointer border-bg-border bg-bg-raised/45' : 'cursor-not-allowed border-accent-bad/30 bg-accent-bad/5 opacity-70'}`}>
          <input
            type="checkbox"
            className="mt-1 h-5 w-5 shrink-0 accent-accent"
            checked={evidenceReviewed}
            disabled={!reviewableEvidence}
            onChange={(event) => onEvidenceReviewed(event.target.checked)}
          />
          <span>
            <span className="block font-semibold text-fg">I reviewed this release evidence</span>
            <span className="mt-0.5 block text-xs leading-5">
              I compared the version code, immutable URL, all three SHA-256 values, Git commit and
              release ref, and workflow run ID/attempt with the approved CI release record.
            </span>
          </span>
        </label>
      ) : null}

      {previousError ? (
        <div className="rounded-lg border border-accent-bad/40 bg-accent-bad/10 p-3" role="alert">
          <span className="font-semibold text-accent-bad">Previous attempt failed: </span>
          {previousError}
        </div>
      ) : null}
    </div>
  );
}

function StatusPill({ label, className }: { label: string; className: string }) {
  return (
    <span className={`inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-medium ${className}`}>
      {label}
    </span>
  );
}

function EmptyPanel({
  icon,
  title,
  detail,
}: {
  icon: React.ReactNode;
  title: string;
  detail: string;
}) {
  return (
    <div className="mt-4 rounded-xl border border-dashed border-bg-border px-5 py-8 text-center">
      <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-xl bg-bg-raised text-fg-muted">
        {icon}
      </div>
      <h4 className="mt-3 font-semibold">{title}</h4>
      <p className="mx-auto mt-1 max-w-xl text-sm text-fg-muted">{detail}</p>
    </div>
  );
}

function ErrorPanel({ title, message }: { title: string; message: string }) {
  return (
    <div className="flex items-start gap-3 rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-4" role="alert">
      <AlertCircle className="mt-0.5 shrink-0 text-accent-bad" size={18} aria-hidden="true" />
      <div>
        <p className="font-semibold text-accent-bad">{title}</p>
        <p className="mt-1 text-sm text-fg-muted">{message}</p>
      </div>
    </div>
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message.trim()
    ? error.message
    : 'The server did not complete this request. Check the connection and try again.';
}

function formatDate(value: string | null): string {
  if (!value) return 'Not recorded';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Invalid server time' : DATE_TIME.format(date);
}

function relativeTime(value: string, serverTime: string): string {
  const then = new Date(value).getTime();
  const now = new Date(serverTime).getTime();
  if (!Number.isFinite(then) || !Number.isFinite(now)) return 'Time unavailable';
  const elapsedSeconds = Math.max(0, Math.floor((now - then) / 1_000));
  if (elapsedSeconds < 60) return 'Just now';
  const minutes = Math.floor(elapsedSeconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function shortId(value: string): string {
  return value.length > 8 ? value.slice(0, 8) : value;
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return 'Unknown';
  if (bytes < 1_024 * 1_024) return `${Math.max(1, Math.round(bytes / 1_024))} KB`;
  return `${(bytes / (1_024 * 1_024)).toFixed(1)} MB`;
}
