import {
  MonitorSmartphone,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react';

import type { RemoteAssistanceGrantKind } from '@/lib/erp-api';
import type {
  RemoteAssistanceModule,
  SafeRemoteAssistanceCommand,
} from '@/lib/remote-assistance-policy';
import { DeviceDetail } from './DeviceDetailPanel';
import { DeviceList } from './DeviceListPanel';
import {
  EmptyDevices,
  InlineNotice,
} from './DeviceCentrePrimitives';
import type {
  BusyAction,
  DeviceCentreRow,
  RemoteFrameViewModel,
} from './remote-assistance-state';

export interface DeviceCentreViewProps {
  rows: DeviceCentreRow[];
  selectedId: string | null;
  refreshing: boolean;
  refreshError: string | null;
  actionError: string | null;
  frame: RemoteFrameViewModel;
  grantKind: RemoteAssistanceGrantKind;
  selectedModule: RemoteAssistanceModule;
  busyAction: BusyAction;
  onSelect: (installationId: string) => void;
  onRefresh: () => void;
  onGrantKindChange: (kind: RemoteAssistanceGrantKind) => void;
  onRequestGrant: () => void;
  onConnect: () => void;
  onEnd: () => void;
  onRevoke: () => void;
  onReviewPairing: (keyId: string, replacement: boolean) => void;
  onRevokeKey: (keyId: string) => void;
  onModuleChange: (module: RemoteAssistanceModule) => void;
  onCommand: (command: SafeRemoteAssistanceCommand) => void;
}

export function DeviceCentreView({
  rows,
  selectedId,
  refreshing,
  refreshError,
  actionError,
  frame,
  grantKind,
  selectedModule,
  busyAction,
  onSelect,
  onRefresh,
  onGrantKindChange,
  onRequestGrant,
  onConnect,
  onEnd,
  onRevoke,
  onReviewPairing,
  onRevokeKey,
  onModuleChange,
  onCommand,
}: DeviceCentreViewProps) {
  const selected = rows.find((row) => row.device.installation_id === selectedId) ?? null;

  return (
    <div className="mx-auto max-w-[1600px] space-y-4" aria-label="Owner Device Centre">
      <header className="flex flex-col gap-4 border-b border-bg-border/80 pb-5 md:flex-row md:items-center md:justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight md:text-3xl">Device Centre</h2>
          <p className="mt-1 text-sm text-fg-muted">
            Private, audited assistance for D Company ERP tablets.
          </p>
        </div>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <div className="flex items-start gap-2.5 border-l-2 border-accent/60 pl-3">
            <ShieldCheck className="mt-0.5 shrink-0 text-accent" size={18} aria-hidden="true" />
            <div>
              <p className="text-sm font-semibold">Protected owner access</p>
              <p className="max-w-sm text-xs leading-5 text-fg-muted">
                Employee consent, short-lived sessions and a complete actor trail are required.
              </p>
            </div>
          </div>
          <button
            type="button"
            className="btn btn-ghost !min-h-[44px] shrink-0 !px-4 !py-2 text-sm"
            onClick={onRefresh}
            disabled={refreshing}
          >
            <RefreshCw
              size={16}
              className={refreshing ? 'animate-spin' : ''}
              aria-hidden="true"
            />
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {refreshError ? (
        <InlineNotice
          tone="warning"
          title="Latest device check could not finish"
          message={`${refreshError} Previously received device state remains visible and is marked by its last-seen time.`}
        />
      ) : null}

      {actionError ? (
        <InlineNotice
          tone="bad"
          title="The action was not confirmed"
          message={`${actionError} Refresh device state before deciding whether to retry.`}
        />
      ) : null}

      {rows.length === 0 ? (
        <EmptyDevices onRefresh={onRefresh} />
      ) : (
        <div className="grid min-w-0 gap-4 xl:grid-cols-[minmax(320px,0.88fr)_minmax(0,1.72fr)]">
          <DeviceList rows={rows} selectedId={selectedId} onSelect={onSelect} />
          {selected ? (
            <DeviceDetail
              row={selected}
              frame={frame}
              grantKind={grantKind}
              selectedModule={selectedModule}
              busyAction={busyAction}
              onGrantKindChange={onGrantKindChange}
              onRequestGrant={onRequestGrant}
              onConnect={onConnect}
              onEnd={onEnd}
              onRevoke={onRevoke}
              onReviewPairing={onReviewPairing}
              onRevokeKey={onRevokeKey}
              onModuleChange={onModuleChange}
              onCommand={onCommand}
            />
          ) : (
            <section className="card grid min-h-[420px] place-items-center text-center">
              <div>
                <MonitorSmartphone className="mx-auto text-fg-subtle" size={30} aria-hidden="true" />
                <h3 className="mt-3 font-semibold">Choose a tablet</h3>
                <p className="mt-1 text-sm text-fg-muted">
                  Select a registered ERP tablet to review consent and session state.
                </p>
              </div>
            </section>
          )}
        </div>
      )}
    </div>
  );
}
