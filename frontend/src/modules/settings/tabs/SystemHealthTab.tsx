import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Database,
  HardDrive,
  RefreshCw,
  Server,
  ShieldAlert,
  Smartphone,
  Wifi,
  WifiOff,
} from 'lucide-react';

import { Skeleton, SkeletonCard } from '@/components/ui/Skeleton';
import type { ApiError } from '@/lib/api';
import {
  systemHealth,
  type ClientDiagnosticComponent,
  type SystemHealthDependencyStatus,
  type SystemHealthDTO,
  type SystemHealthStatus,
} from '@/lib/erp-api';

const POLL_INTERVAL_MS = 60_000;

const DATE_TIME = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

const HEALTH_STATUS: Record<SystemHealthStatus, {
  label: string;
  detail: string;
  tone: Tone;
}> = {
  healthy: {
    label: 'Healthy',
    detail: 'All connected checks are operating normally.',
    tone: 'good',
  },
  degraded: {
    label: 'Needs review',
    detail: 'Operations can continue, but one or more checks need attention.',
    tone: 'warning',
  },
  action_required: {
    label: 'Action required',
    detail: 'A critical diagnostic, stalled Sync, or service problem needs review.',
    tone: 'bad',
  },
};

const COMPONENT_LABELS: Record<ClientDiagnosticComponent, string> = {
  app: 'App',
  auth: 'Authentication',
  gaming: 'Gaming',
  pos: 'POS',
  finance: 'Finance',
  sync: 'Sync',
  network: 'Network',
  updates: 'Updates',
  storage: 'Storage',
};

type Tone = 'good' | 'warning' | 'bad' | 'neutral';

export default function SystemHealthTab() {
  const [health, setHealth] = useState<SystemHealthDTO | null>(null);
  const healthRef = useRef<SystemHealthDTO | null>(null);
  const requestRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);

  const load = useCallback(async (background = false) => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const request = ++requestRef.current;

    if (background || healthRef.current) setRefreshing(true);
    else setLoading(true);
    setLoadError(null);

    try {
      const response = await systemHealth.get(controller.signal);
      if (request !== requestRef.current || controller.signal.aborted) return;
      healthRef.current = response;
      setHealth(response);
      setRefreshError(null);
    } catch (error) {
      if (request !== requestRef.current || controller.signal.aborted) return;
      const message = systemHealthErrorMessage(error);
      if (healthRef.current) setRefreshError(message);
      else setLoadError(message);
    } finally {
      if (request === requestRef.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, []);

  useEffect(() => {
    void load();
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void load(true);
    };
    const interval = window.setInterval(refreshWhenVisible, POLL_INTERVAL_MS);
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => {
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
      requestRef.current += 1;
      controllerRef.current?.abort();
    };
  }, [load]);

  if (loading && !health) return <SystemHealthSkeleton />;

  if (!health) {
    return (
      <section className="card max-w-3xl" aria-labelledby="system-health-error-title">
        <ErrorPanel
          title="System Health could not be loaded"
          message={loadError || 'The protected health service did not return a status.'}
        />
        <button type="button" className="btn btn-primary mt-4" onClick={() => void load()}>
          <RefreshCw size={16} aria-hidden="true" /> Try again
        </button>
      </section>
    );
  }

  return (
    <SystemHealthOverview
      health={health}
      stale={refreshError !== null}
      refreshError={refreshError}
      refreshing={refreshing}
      onRefresh={() => void load(true)}
    />
  );
}

export function SystemHealthOverview({
  health,
  stale,
  refreshError,
  refreshing,
  onRefresh,
}: {
  health: SystemHealthDTO;
  stale: boolean;
  refreshError: string | null;
  refreshing: boolean;
  onRefresh: () => void;
}) {
  const status = HEALTH_STATUS[health.status];
  const backup = backupStatusPresentation(health.backups.status);
  const componentRows = (Object.entries(health.diagnostics.counts_by_component) as Array<[
    ClientDiagnosticComponent,
    number,
  ]>).filter(([, count]) => count > 0);
  const noDiagnostics = health.diagnostics.total === 0;

  return (
    <div className="max-w-6xl space-y-4" aria-label="Owner System Health">
      <section className="card flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex min-w-0 items-start gap-3">
          <ToneIcon tone={status.tone}>
            {health.status === 'healthy'
              ? <CheckCircle2 size={20} aria-hidden="true" />
              : health.status === 'degraded'
                ? <AlertTriangle size={20} aria-hidden="true" />
                : <ShieldAlert size={20} aria-hidden="true" />}
          </ToneIcon>
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="font-bold">Owner System Health</h3>
              <StatusPill label={status.label} tone={status.tone} />
              {stale ? <StatusPill label="Saved status" tone="warning" /> : null}
            </div>
            <p className="mt-1 max-w-3xl text-sm text-fg-muted">{status.detail}</p>
            <p className="mt-1 flex items-center gap-1.5 text-xs text-fg-subtle">
              <Clock3 size={13} aria-hidden="true" />
              Server status at {formatDate(health.server_time)} · diagnostics retained for{' '}
              {health.retention_days} days
            </p>
          </div>
        </div>
        <button
          type="button"
          className="btn btn-ghost shrink-0 !min-h-[44px] !px-4 !py-2 text-sm"
          onClick={onRefresh}
          disabled={refreshing}
        >
          <RefreshCw
            size={16}
            className={refreshing ? 'animate-spin' : ''}
            aria-hidden="true"
          />
          {refreshing ? 'Checking…' : 'Check now'}
        </button>
      </section>

      {refreshError ? (
        <div
          className="flex items-start gap-3 rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-4"
          role="status"
        >
          <AlertTriangle className="mt-0.5 shrink-0 text-accent-gold" size={18} aria-hidden="true" />
          <div>
            <p className="font-semibold text-accent-gold">Latest check could not finish</p>
            <p className="mt-1 text-sm text-fg-muted">
              {refreshError} The last successful server status remains visible and is marked as saved.
            </p>
          </div>
        </div>
      ) : null}

      <section className="grid grid-cols-2 gap-3 lg:grid-cols-4" aria-label="System health summary">
        <MetricCard
          icon={<Smartphone size={18} aria-hidden="true" />}
          label="Tablets seen"
          value={`${health.devices.seen_last_24h}/${health.devices.total}`}
          detail="Authenticated in the last 24 hours"
          tone={health.devices.total > 0 && health.devices.seen_last_24h === health.devices.total
            ? 'good'
            : health.devices.total === 0 ? 'neutral' : 'warning'}
        />
        <MetricCard
          icon={<Activity size={18} aria-hidden="true" />}
          label="Diagnostics"
          value={String(health.diagnostics.total)}
          detail={`Last ${health.diagnostics.window_hours} hours`}
          tone={health.diagnostics.total ? 'warning' : 'good'}
        />
        <MetricCard
          icon={<ShieldAlert size={18} aria-hidden="true" />}
          label="Critical"
          value={String(health.diagnostics.critical_count)}
          detail={`${health.diagnostics.affected_installations} tablet${health.diagnostics.affected_installations === 1 ? '' : 's'} affected`}
          tone={health.diagnostics.critical_count ? 'bad' : 'good'}
        />
        <MetricCard
          icon={<WifiOff size={18} aria-hidden="true" />}
          label="Sync stalled"
          value={String(health.devices.sync_stalled)}
          detail={`${health.devices.with_pending_sync} tablet${health.devices.with_pending_sync === 1 ? '' : 's'} with saved actions`}
          tone={health.devices.sync_stalled ? 'bad' : health.devices.with_pending_sync ? 'warning' : 'good'}
        />
      </section>

      <section className="card" aria-labelledby="service-health-title">
        <SectionHeading
          icon={<Server size={18} aria-hidden="true" />}
          title="Connected services"
          id="service-health-title"
          detail="Owner-safe availability checks only. Hostnames, credentials and infrastructure logs are never shown here."
        />
        <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <DependencyCard
            icon={<Server size={18} aria-hidden="true" />}
            label="ERP API"
            status={health.dependencies.api}
          />
          <DependencyCard
            icon={<Database size={18} aria-hidden="true" />}
            label="Database"
            status={health.dependencies.database}
          />
          <DependencyCard
            icon={<Wifi size={18} aria-hidden="true" />}
            label="Server protection"
            status={health.dependencies.redis}
          />
          <article className={`rounded-xl border p-4 ${backup.surface}`}>
            <div className="flex items-start justify-between gap-3">
              <ToneIcon tone={backup.tone} compact>
                <HardDrive size={18} aria-hidden="true" />
              </ToneIcon>
              <StatusPill label={backup.label} tone={backup.tone} />
            </div>
            <h5 className="mt-3 font-semibold">Backup proof</h5>
            <p className="mt-1 text-xs text-fg-muted">
              {backup.detail}
            </p>
            <dl className="mt-3 grid gap-2 text-xs">
              <InfoRow label="Last verified backup" value={formatDate(health.backups.last_success_at)} />
              <InfoRow label="Restore test" value={formatDate(health.backups.restore_tested_at)} />
            </dl>
          </article>
        </div>
      </section>

      <section className="grid gap-4 xl:grid-cols-[1.15fr_0.85fr]">
        <div className="card" aria-labelledby="tablet-health-title">
          <SectionHeading
            icon={<Smartphone size={18} aria-hidden="true" />}
            title="Tablet, Sync and update coverage"
            id="tablet-health-title"
            detail="Aggregated installation health. Employee names, device identifiers and diagnostic payloads are excluded."
          />

          {health.devices.total === 0 ? (
            <EmptyPanel
              icon={<Smartphone size={23} aria-hidden="true" />}
              title="No tablet health received yet"
              detail="A signed Code 15 tablet reports here after an authenticated sign-in. No device state is being inferred."
            />
          ) : (
            <dl className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3">
              <DataPoint label="Known tablets" value={health.devices.total} />
              <DataPoint label="Seen in 24h" value={health.devices.seen_last_24h} tone={health.devices.stale ? 'warning' : 'good'} />
              <DataPoint label="Stale" value={health.devices.stale} tone={health.devices.stale ? 'warning' : 'good'} />
              <DataPoint label="Pending Sync" value={health.devices.with_pending_sync} tone={health.devices.with_pending_sync ? 'warning' : 'good'} />
              <DataPoint label="Largest queue" value={health.devices.max_pending_outbox_count} tone={health.devices.max_pending_outbox_count ? 'warning' : 'good'} />
              <DataPoint label="Sync stalled" value={health.devices.sync_stalled} tone={health.devices.sync_stalled ? 'bad' : 'good'} />
            </dl>
          )}

          <div className="mt-4 rounded-xl border border-bg-border bg-bg-raised/35 p-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="text-xs font-medium uppercase tracking-wide text-fg-muted">Update coverage</p>
                <p className="mt-1 font-mono text-xl font-semibold tabular-nums">
                  Code {health.devices.latest_supported_version_code}
                </p>
                <p className="mt-1 text-xs text-fg-muted">Latest supported Android version code</p>
              </div>
              <StatusPill
                label={health.devices.outdated_installations
                  ? `${health.devices.outdated_installations} outdated`
                  : 'All current'}
                tone={health.devices.outdated_installations ? 'warning' : 'good'}
              />
            </div>
            <p className="mt-3 text-xs text-fg-subtle">
              This is read-only health evidence. Offering or withdrawing an APK remains in Devices &amp; Updates and requires its separate release-control grant.
            </p>
          </div>
        </div>

        <div className="card" aria-labelledby="diagnostic-health-title">
          <SectionHeading
            icon={<Activity size={18} aria-hidden="true" />}
            title="Automatic diagnostics"
            id="diagnostic-health-title"
            detail={`Sanitized categories received during the last ${health.diagnostics.window_hours} hours.`}
          />

          <div className="mt-4 grid grid-cols-2 gap-3">
            <IncidentCount label="Crashes" value={health.diagnostics.counts_by_type.crash} />
            <IncidentCount label="App not responding" value={health.diagnostics.counts_by_type.anr} />
            <IncidentCount label="API failures" value={health.diagnostics.counts_by_type.api_failure} />
            <IncidentCount label="Sync stalls" value={health.diagnostics.counts_by_type.sync_stall} />
          </div>

          {noDiagnostics ? (
            <EmptyPanel
              icon={<CheckCircle2 size={23} aria-hidden="true" />}
              title="No incidents received in this window"
              detail="This means the server has no retained diagnostic events for the selected period; it does not replace physical tablet testing."
            />
          ) : (
            <div className="mt-4 overflow-hidden rounded-xl border border-bg-border">
              <div className="grid grid-cols-[1fr_auto] gap-3 bg-bg-raised/60 px-3 py-2 text-xs font-medium uppercase tracking-wide text-fg-muted">
                <span>Area</span><span>Incidents</span>
              </div>
              <div className="divide-y divide-bg-border">
                {componentRows.map(([component, count]) => (
                  <div key={component} className="grid grid-cols-[1fr_auto] gap-3 px-3 py-2.5 text-sm">
                    <span>{COMPONENT_LABELS[component]}</span>
                    <span className="font-mono font-semibold tabular-nums">{count}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-bg-border pt-4 text-xs">
            <InfoRow label="Offline incidents" value={String(health.diagnostics.offline_event_count)} />
            <InfoRow label="Latest incident" value={formatDate(health.diagnostics.latest_event_at)} />
          </dl>
        </div>
      </section>

      <section className="card" aria-labelledby="recommendations-title">
        <SectionHeading
          icon={<AlertCircle size={18} aria-hidden="true" />}
          title="Recommended actions"
          id="recommendations-title"
          detail="Server-generated next steps based only on the aggregate checks above."
        />
        {health.recommendations.length ? (
          <ul className="mt-4 grid gap-2" aria-label="System health recommendations">
            {health.recommendations.map((recommendation, index) => (
              <li
                key={`${index}:${recommendation}`}
                className="flex items-start gap-3 rounded-xl border border-bg-border bg-bg-raised/30 p-3 text-sm"
              >
                <AlertTriangle className="mt-0.5 shrink-0 text-accent-gold" size={16} aria-hidden="true" />
                <span>{recommendation}</span>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyPanel
            icon={<CheckCircle2 size={23} aria-hidden="true" />}
            title="No corrective action recommended"
            detail="All currently connected health checks are within their expected state."
          />
        )}
      </section>
    </div>
  );
}

function SystemHealthSkeleton() {
  return (
    <div className="max-w-6xl space-y-4" aria-label="Loading System Health">
      <SkeletonCard lines={4} />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} className="card space-y-3">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-8 w-1/3" />
            <Skeleton className="h-3 w-3/4" />
          </div>
        ))}
      </div>
      <SkeletonCard lines={6} />
    </div>
  );
}

function SectionHeading({
  icon,
  title,
  detail,
  id,
}: {
  icon: ReactNode;
  title: string;
  detail: string;
  id?: string;
}) {
  return (
    <div className="flex items-start gap-3">
      <div className="rounded-xl border border-bg-border bg-bg-raised p-2 text-fg-muted">{icon}</div>
      <div>
        <h4 id={id} className="font-semibold">{title}</h4>
        <p className="mt-0.5 text-xs text-fg-muted">{detail}</p>
      </div>
    </div>
  );
}

function MetricCard({
  icon,
  label,
  value,
  detail,
  tone,
}: {
  icon: ReactNode;
  label: string;
  value: string;
  detail: string;
  tone: Tone;
}) {
  return (
    <article className="card min-w-0">
      <div className="flex items-start gap-3">
        <ToneIcon tone={tone} compact>{icon}</ToneIcon>
        <div className="min-w-0">
          <p className="text-xs font-medium text-fg-muted">{label}</p>
          <p className="mt-1 font-mono text-2xl font-semibold tabular-nums">{value}</p>
          <p className="mt-0.5 text-xs text-fg-subtle">{detail}</p>
        </div>
      </div>
    </article>
  );
}

function DependencyCard({
  icon,
  label,
  status,
}: {
  icon: ReactNode;
  label: string;
  status: SystemHealthDependencyStatus;
}) {
  const operational = status === 'operational';
  return (
    <article className={`rounded-xl border p-4 ${operational
      ? 'border-accent-good/25 bg-accent-good/5'
      : 'border-accent-bad/40 bg-accent-bad/8'}`}>
      <div className="flex items-start justify-between gap-3">
        <ToneIcon tone={operational ? 'good' : 'bad'} compact>{icon}</ToneIcon>
        <StatusPill
          label={operational ? 'Operational' : 'Unavailable'}
          tone={operational ? 'good' : 'bad'}
        />
      </div>
      <h5 className="mt-3 font-semibold">{label}</h5>
      <p className="mt-1 text-xs text-fg-muted">
        {operational ? 'The protected server check completed.' : 'The protected server check failed.'}
      </p>
    </article>
  );
}

function IncidentCount({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-xl border border-bg-border bg-bg-raised/35 p-3">
      <p className="text-xs text-fg-muted">{label}</p>
      <p className={`mt-1 font-mono text-xl font-semibold tabular-nums ${value ? 'text-accent-gold' : 'text-accent-good'}`}>
        {value}
      </p>
    </div>
  );
}

function DataPoint({
  label,
  value,
  tone = 'neutral',
}: {
  label: string;
  value: number;
  tone?: Tone;
}) {
  return (
    <div className="rounded-xl border border-bg-border bg-bg-raised/30 p-3">
      <dt className="text-xs text-fg-muted">{label}</dt>
      <dd className={`mt-1 font-mono text-xl font-semibold tabular-nums ${toneText(tone)}`}>{value}</dd>
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-fg-muted">{label}</dt>
      <dd className="mt-0.5 break-words font-medium text-fg">{value}</dd>
    </div>
  );
}

function EmptyPanel({
  icon,
  title,
  detail,
}: {
  icon: ReactNode;
  title: string;
  detail: string;
}) {
  return (
    <div className="mt-4 rounded-xl border border-dashed border-bg-border px-5 py-7 text-center">
      <div className="mx-auto flex h-11 w-11 items-center justify-center rounded-xl bg-bg-raised text-fg-muted">
        {icon}
      </div>
      <h5 className="mt-3 font-semibold">{title}</h5>
      <p className="mx-auto mt-1 max-w-xl text-sm text-fg-muted">{detail}</p>
    </div>
  );
}

function ErrorPanel({ title, message }: { title: string; message: string }) {
  return (
    <div className="flex items-start gap-3 rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-4" role="alert">
      <AlertCircle className="mt-0.5 shrink-0 text-accent-bad" size={18} aria-hidden="true" />
      <div>
        <p id="system-health-error-title" className="font-semibold text-accent-bad">{title}</p>
        <p className="mt-1 text-sm text-fg-muted">{message}</p>
      </div>
    </div>
  );
}

function ToneIcon({
  tone,
  compact = false,
  children,
}: {
  tone: Tone;
  compact?: boolean;
  children: ReactNode;
}) {
  return (
    <div className={`${compact ? 'p-2' : 'p-2.5'} shrink-0 rounded-xl border ${toneSurface(tone)}`}>
      {children}
    </div>
  );
}

function StatusPill({ label, tone }: { label: string; tone: Tone }) {
  return (
    <span className={`inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-semibold ${toneSurface(tone)}`}>
      {label}
    </span>
  );
}

function toneSurface(tone: Tone): string {
  if (tone === 'good') return 'border-accent-good/35 bg-accent-good/10 text-accent-good';
  if (tone === 'warning') return 'border-accent-gold/40 bg-accent-gold/10 text-accent-gold';
  if (tone === 'bad') return 'border-accent-bad/45 bg-accent-bad/10 text-accent-bad';
  return 'border-bg-border bg-bg-raised text-fg-muted';
}

function toneText(tone: Tone): string {
  if (tone === 'good') return 'text-accent-good';
  if (tone === 'warning') return 'text-accent-gold';
  if (tone === 'bad') return 'text-accent-bad';
  return 'text-fg';
}

function backupStatusPresentation(status: SystemHealthDTO['backups']['status']): {
  label: string;
  detail: string;
  tone: Tone;
  surface: string;
} {
  if (status === 'operational') {
    return {
      label: 'Verified',
      detail: 'The connected host monitor supplied verified backup evidence.',
      tone: 'good',
      surface: 'border-accent-good/25 bg-accent-good/5',
    };
  }
  if (status === 'unavailable') {
    return {
      label: 'Unavailable',
      detail: 'The connected host monitor reported that backup evidence is unavailable.',
      tone: 'bad',
      surface: 'border-accent-bad/40 bg-accent-bad/8',
    };
  }
  return {
    label: 'Not connected',
    detail: 'The host backup monitor is not connected to this protected view, so no success or restore claim is made.',
    tone: 'warning',
    surface: 'border-accent-gold/35 bg-accent-gold/5',
  };
}

function formatDate(value: string | null): string {
  if (!value) return 'Not reported';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Not reported' : DATE_TIME.format(date);
}

export function systemHealthErrorMessage(error: unknown): string {
  const apiError = error as ApiError | null;
  if (apiError?.status === 401) {
    return 'Your sign-in could not be verified. Sign in again, then retry.';
  }
  if (apiError?.status === 403) {
    return 'This account does not have protected System Health access.';
  }
  if (apiError?.code === 'network_error' || apiError?.status === undefined) {
    return 'The ERP server could not be reached. Check the connection and retry.';
  }
  return 'The server did not complete the health check. Retry in a moment.';
}
