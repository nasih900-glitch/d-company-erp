import { useEffect, useRef, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import {
  diagnosticEvents,
  type ClientDiagnosticEventDTO,
  type ClientDiagnosticEventType,
} from '@/lib/erp-api';
import { businessLabel, shopTime } from '@/modules/audit/audit-presentation';

export default function IncidentReports() {
  const [type, setType] = useState<ClientDiagnosticEventType | ''>('');
  const [rows, setRows] = useState<ClientDiagnosticEventDTO[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  async function load() {
    controller.current?.abort();
    const active = new AbortController();
    controller.current = active;
    setBusy(true);
    setError(null);
    try {
      const result = await diagnosticEvents.list(
        { limit: 20, ...(type ? { event_type: type } : {}) },
        active.signal,
      );
      if (active.signal.aborted) return;
      setRows(result.items);
      setTotal(result.total);
    } catch (cause) {
      if (!active.signal.aborted)
        setError(
          `${(cause as Error).message} Previously loaded reports remain visible.`,
        );
    } finally {
      if (!active.signal.aborted) setBusy(false);
    }
  }
  return (
    <details className="card">
      <summary className="cursor-pointer font-semibold">
        Individual app reports
      </summary>
      <p className="mt-2 text-sm text-fg-muted">
        Reported incidents are evidence, not a complete crash census. An app
        that cannot upload may have unreported incidents.
      </p>
      <div className="my-4 flex flex-wrap items-end gap-3">
        <label className="text-sm">
          Report type
          <select
            className="input mt-1 block"
            value={type}
            onChange={(event) =>
              setType(event.target.value as ClientDiagnosticEventType | '')
            }
          >
            <option value="">All report types</option>
            {(['crash', 'anr', 'api_failure', 'sync_stall'] as const).map(
              (value) => (
                <option key={value} value={value}>
                  {businessLabel(
                    value === 'anr' ? 'app_not_responding' : value,
                  )}
                </option>
              ),
            )}
          </select>
        </label>
        <button
          type="button"
          className="btn btn-ghost"
          disabled={busy}
          onClick={() => void load()}
        >
          <RefreshCw size={14} />
          {busy ? 'Loading…' : 'Load latest reports'}
        </button>
      </div>
      {error && (
        <p role="alert" className="mb-3 text-sm text-accent-bad">
          {error}
        </p>
      )}
      {total !== null && (
        <p className="mb-3 text-xs text-fg-muted">
          {rows.length} latest reports loaded of {total} matching retained
          reports. Times below are explicitly UTC.
        </p>
      )}
      {total === 0 && (
        <p className="text-sm text-fg-muted">
          No matching reports received. This does not prove the app has never
          crashed.
        </p>
      )}
      <div className="grid gap-3 lg:grid-cols-2">
        {rows.map((row) => (
          <IncidentReport key={row.id} row={row} />
        ))}
      </div>
    </details>
  );
}
export function IncidentReport({ row }: { row: ClientDiagnosticEventDTO }) {
  return (
    <article className="rounded-xl border border-bg-border p-4 text-sm">
      <h4 className="font-semibold">
        {businessLabel(
          row.event_type === 'anr' ? 'app_not_responding' : row.event_type,
        )}{' '}
        · {businessLabel(row.component)}
      </h4>
      <p className="mt-1 text-fg-muted">
        Build {row.version_code} · v{row.version_name} ·{' '}
        {businessLabel(row.severity)}
      </p>
      <dl className="mt-3 space-y-2">
        <div>
          <dt className="text-xs text-fg-muted">
            Client says occurred · UTC (unverified device clock)
          </dt>
          <dd className="tabular-nums">{shopTime(row.occurred_at, 'UTC')}</dd>
        </div>
        <div>
          <dt className="text-xs text-fg-muted">Server received · UTC</dt>
          <dd className="tabular-nums">{shopTime(row.received_at, 'UTC')}</dd>
        </div>
        <div>
          <dt className="text-xs text-fg-muted">
            Reported reason / connection
          </dt>
          <dd>
            {businessLabel(row.reason_code)} · {businessLabel(row.connectivity)}
          </dd>
        </div>
      </dl>
      <details className="mt-3">
        <summary className="cursor-pointer text-xs text-fg-muted">
          Technical reference
        </summary>
        <p className="mt-2 break-all font-mono text-xs">
          {row.id} · installation {row.installation_id}
        </p>
      </details>
    </article>
  );
}
