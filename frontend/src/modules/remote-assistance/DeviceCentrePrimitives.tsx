import {
  AlertCircle,
  AlertTriangle,
  MonitorSmartphone,
  RefreshCw,
} from 'lucide-react';

import { Skeleton, SkeletonCard } from '@/components/ui/Skeleton';
import type { Tone } from './remote-assistance-presentation';

export function InlineNotice({
  tone,
  title,
  message,
}: {
  tone: 'warning' | 'bad' | 'neutral';
  title: string;
  message: string;
}) {
  const presentation = tone === 'bad'
    ? { Icon: AlertCircle, border: 'border-accent-bad/45', bg: 'bg-accent-bad/10', icon: 'text-accent-bad' }
    : tone === 'warning'
      ? { Icon: AlertTriangle, border: 'border-accent-gold/45', bg: 'bg-accent-gold/10', icon: 'text-accent-gold' }
      : { Icon: AlertCircle, border: 'border-bg-border', bg: 'bg-bg-raised/45', icon: 'text-fg-muted' };
  return (
    <div className={`flex items-start gap-3 rounded-xl border ${presentation.border} ${presentation.bg} p-3`} role="status">
      <presentation.Icon className={`mt-0.5 shrink-0 ${presentation.icon}`} size={17} aria-hidden="true" />
      <div>
        <p className="text-sm font-semibold">{title}</p>
        <p className="mt-0.5 text-xs leading-5 text-fg-muted">{message}</p>
      </div>
    </div>
  );
}
export function StatusPill({ label, tone }: { label: string; tone: Tone }) {
  const className = tone === 'good'
    ? 'border-accent-good/40 bg-accent-good/10 text-accent-good'
    : tone === 'warning'
      ? 'border-accent-gold/45 bg-accent-gold/10 text-accent-gold'
      : tone === 'bad'
        ? 'border-accent-bad/45 bg-accent-bad/10 text-accent-bad'
        : 'border-bg-border bg-bg-raised/70 text-fg-muted';
  return (
    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-semibold ${className}`}>
      {label}
    </span>
  );
}

export function DeviceCentreLoadError({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <section className="card mx-auto max-w-3xl" aria-labelledby="device-centre-error-title">
      <div className="flex items-start gap-3">
        <AlertCircle className="mt-0.5 shrink-0 text-accent-bad" size={20} aria-hidden="true" />
        <div>
          <h2 id="device-centre-error-title" className="font-bold">Device Centre could not be loaded</h2>
          <p className="mt-1 text-sm leading-6 text-fg-muted">{message}</p>
          <p className="mt-2 text-xs text-fg-subtle">
            No remote session or access request was started.
          </p>
        </div>
      </div>
      <button type="button" className="btn btn-primary mt-4" onClick={onRetry}>
        <RefreshCw size={16} aria-hidden="true" /> Try again
      </button>
    </section>
  );
}

export function EmptyDevices({ onRefresh }: { onRefresh: () => void }) {
  return (
    <section className="card grid min-h-[360px] place-items-center text-center">
      <div className="max-w-lg">
        <MonitorSmartphone className="mx-auto text-fg-subtle" size={34} aria-hidden="true" />
        <h3 className="mt-4 text-lg font-semibold">No ERP tablets have reported yet</h3>
        <p className="mt-2 text-sm leading-6 text-fg-muted">
          A supported, registered Android ERP installation appears here after its remote-support
          heartbeat reaches this company. This is not proof that a physical tablet is ready.
        </p>
        <button type="button" className="btn btn-ghost mt-4" onClick={onRefresh}>
          <RefreshCw size={16} aria-hidden="true" /> Check again
        </button>
      </div>
    </section>
  );
}

export function DeviceCentreSkeleton() {
  return (
    <div className="mx-auto max-w-[1600px] space-y-4" aria-label="Loading Device Centre">
      <div className="flex items-center justify-between border-b border-bg-border pb-5">
        <div>
          <Skeleton className="h-8 w-52" />
          <Skeleton className="mt-2 h-4 w-80 max-w-[70vw]" />
        </div>
        <Skeleton className="h-11 w-28" />
      </div>
      <div className="grid gap-4 xl:grid-cols-[minmax(320px,0.88fr)_minmax(0,1.72fr)]">
        <SkeletonCard lines={8} />
        <SkeletonCard lines={12} />
      </div>
    </div>
  );
}
