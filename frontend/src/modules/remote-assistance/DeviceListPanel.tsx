import {
  ChevronRight,
  Smartphone,
} from 'lucide-react';

import {
  deviceName,
  deviceKeyStatusPresentation,
  grantStatusPresentation,
  relativeTime,
  sessionStatusPresentation,
} from './remote-assistance-presentation';
import { StatusPill } from './DeviceCentrePrimitives';
import type { DeviceCentreRow } from './remote-assistance-state';

export function DeviceList({
  rows,
  selectedId,
  onSelect,
}: {
  rows: DeviceCentreRow[];
  selectedId: string | null;
  onSelect: (installationId: string) => void;
}) {
  return (
    <section className="overflow-hidden rounded-2xl border border-bg-border bg-bg-surface/90 shadow-glow">
      <div className="flex items-center justify-between border-b border-bg-border px-4 py-4">
        <div>
          <h3 className="font-bold">ERP tablets</h3>
          <p className="mt-0.5 text-xs text-fg-muted">
            {rows.length} registered {rows.length === 1 ? 'device' : 'devices'}
          </p>
        </div>
        <Smartphone className="text-accent" size={20} aria-hidden="true" />
      </div>
      <div className="divide-y divide-bg-border xl:max-h-[calc(100dvh-15rem)] xl:overflow-y-auto">
        {rows.map((row) => {
          const selected = row.device.installation_id === selectedId;
          const key = deviceKeyStatusPresentation(
            row.device.device_key_status,
            Boolean(row.device.pending_device_key_id && row.device.device_key_status === 'active'),
          );
          const grant = grantStatusPresentation(row.device.grant_status);
          const session = sessionStatusPresentation(row.device.session_status);
          return (
            <button
              type="button"
              key={row.device.installation_id}
              onClick={() => onSelect(row.device.installation_id)}
              aria-pressed={selected}
              className={`group grid w-full min-w-0 gap-3 px-4 py-4 text-left transition sm:grid-cols-[minmax(0,1fr)_auto] xl:grid-cols-1 2xl:grid-cols-[minmax(0,1fr)_auto] ${
                selected
                  ? 'bg-accent/10 shadow-[inset_3px_0_0_#d2b36d]'
                  : 'hover:bg-bg-raised/55 active:bg-bg-raised'
              }`}
            >
              <div className="min-w-0">
                <div className="flex min-w-0 items-center gap-2">
                  <span className={`h-2 w-2 shrink-0 rounded-full ${
                    row.device.is_remote_online ? 'bg-accent-good' : 'bg-fg-subtle'
                  }`} aria-hidden="true" />
                  <span className="truncate font-semibold">{deviceName(row.device)}</span>
                </div>
                <p className="mt-1 truncate text-xs text-fg-muted">
                  {row.device.is_remote_online ? 'Connected' : 'Offline'} · last seen{' '}
                  {relativeTime(row.device.remote_support_last_seen_at ?? row.device.last_seen_at)}
                </p>
                <p className="mt-1 text-xs text-fg-subtle">
                  App {row.device.version_name} · code {row.device.version_code}
                </p>
              </div>
              <div className="flex items-center justify-between gap-2 sm:justify-end xl:justify-between 2xl:justify-end">
                <div className="flex flex-wrap gap-1.5">
                  <StatusPill label={key.shortLabel} tone={key.tone} />
                  <StatusPill label={grant.shortLabel} tone={grant.tone} />
                  <StatusPill label={session.shortLabel} tone={session.tone} />
                </div>
                <ChevronRight
                  className={`shrink-0 transition ${selected ? 'text-accent' : 'text-fg-subtle group-hover:text-fg'}`}
                  size={17}
                  aria-hidden="true"
                />
              </div>
            </button>
          );
        })}
      </div>
      <div className="border-t border-bg-border px-4 py-3 text-xs text-fg-subtle">
        Status refreshes every 10 seconds while this page is visible.
      </div>
    </section>
  );
}
