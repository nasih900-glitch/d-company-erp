import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertCircle,
  ArrowUpRight,
  ChevronDown,
  Lock,
  RefreshCw,
  Search,
  ShieldCheck,
  X,
} from 'lucide-react';
import {
  audit,
  staff,
  type AuditCatalogueDTO,
  type AuditEventFilters,
  type AuditEventsPageDTO,
  type BusinessAuditEntryDTO,
  type UserDTO,
} from '@/lib/erp-api';
import type { ApiError } from '@/lib/api';
import { useAuth } from '@/modules/auth/AuthContext';
import { useLatestRequest } from '@/hooks/useLatestRequest';
import { useRealtimeRefresh } from '@/hooks/useRealtimeRefresh';
import {
  ownerScope,
  ownerWorkspaceGeneration,
  readOwnerWorkspace,
  rememberOwnerWorkspace,
} from '@/lib/owner-workspace';
import {
  auditActor,
  auditBusinessSummary,
  auditBusinessChanges,
  auditRetainedSnapshots,
  auditOrderId,
  businessLabel,
  businessValue,
  safeAuditValue,
  shopTime,
} from './audit-presentation';

type AuditState = {
  token: string | null;
  expiresAt: number;
  rows: BusinessAuditEntryDTO[];
  page: AuditEventsPageDTO | null;
  filters: AuditEventFilters;
  selected: BusinessAuditEntryDTO | null;
  scroll: number;
  catalogue: AuditCatalogueDTO | null;
  users: UserDTO[];
};
const defaults: AuditEventFilters = { view: 'business' };
export default function AuditScreen() {
  const { me } = useAuth();
  return me?.audit_access ? (
    <AuditWorkspace key={ownerScope(me)} scope={ownerScope(me)} />
  ) : (
    <div className="card">Audit requires protected-owner access.</div>
  );
}
function AuditWorkspace({ scope }: { scope: string }) {
  const [searchParams] = useSearchParams();
  const workspaceGeneration = useRef(ownerWorkspaceGeneration()).current;
  const initial = useRef(readOwnerWorkspace<AuditState>(scope, 'audit'));
  const cached = initial.current;
  const [token, setToken] = useState(
    cached && cached.expiresAt > Date.now() ? cached.token : null,
  );
  const [expiresAt, setExpiresAt] = useState(cached?.expiresAt ?? 0);
  const [password, setPassword] = useState('');
  const linkedReference = searchParams.get('reference');
  const sameReference =
    !linkedReference || linkedReference === cached?.filters.reference;
  const [rows, setRows] = useState(sameReference ? (cached?.rows ?? []) : []);
  const [page, setPage] = useState(
    sameReference ? (cached?.page ?? null) : null,
  );
  const [filters, setFilters] = useState<AuditEventFilters>(
    linkedReference
      ? { ...defaults, reference: linkedReference }
      : (cached?.filters ?? defaults),
  );
  const [draft, setDraft] = useState(filters);
  const [catalogue, setCatalogue] = useState<AuditCatalogueDTO | null>(
    cached?.catalogue ?? null,
  );
  const [users, setUsers] = useState<UserDTO[]>(cached?.users ?? []);
  const [selected, setSelected] = useState<BusinessAuditEntryDTO | null>(
    sameReference ? (cached?.selected ?? null) : null,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [newEntries, setNewEntries] = useState(false);
  const requests = useLatestRequest();
  const busyRef = useRef(false);
  const list = useRef<HTMLDivElement>(null);
  const detail = useRef<HTMLDivElement>(null);
  const detailFocusRequested = useRef(false);
  const detailReturn = useRef<{main: HTMLElement | null; mainScroll: number; listScroll: number; button: HTMLButtonElement} | null>(null);
  function openDetails(entry: BusinessAuditEntryDTO, button: HTMLButtonElement) {
    const main = list.current?.closest('main') ?? null;
    detailReturn.current = { main, mainScroll: main?.scrollTop ?? 0, listScroll: list.current?.scrollTop ?? 0, button };
    detailFocusRequested.current = true;
    setSelected(entry);
  }
  function closeDetails() {
    const position = detailReturn.current;
    detailFocusRequested.current = false;
    setSelected(null);
    if (position) {
      if (position.main) position.main.scrollTop = position.mainScroll;
      if (list.current) list.current.scrollTop = position.listScroll;
      if (position.button.isConnected) position.button.focus({preventScroll: true});
    }
    detailReturn.current = null;
  }
  useEffect(() => {
    if (selected && detailFocusRequested.current && detail.current) {
      detailFocusRequested.current = false;
      detail.current.focus({preventScroll: true});
      detail.current.scrollIntoView?.({block: 'nearest'});
    }
  }, [selected]);

  const rowsRef = useRef(rows);
  rowsRef.current = rows;
  const stateRef = useRef<AuditState>({
    token,
    expiresAt,
    rows,
    page,
    filters,
    selected,
    scroll: cached?.scroll ?? 0,
    catalogue,
    users,
  });
  stateRef.current = {
    token,
    expiresAt,
    rows,
    page,
    filters,
    selected,
    scroll: stateRef.current.scroll,
    catalogue,
    users,
  };
  const persist = useCallback(() => {
    if (workspaceGeneration === ownerWorkspaceGeneration())
      rememberOwnerWorkspace(scope, 'audit', stateRef.current);
  }, [scope, workspaceGeneration]);
  useEffect(() => {
    persist();
  }, [
    token,
    expiresAt,
    rows,
    page,
    filters,
    selected,
    catalogue,
    users,
    persist,
  ]);
  useEffect(() => {
    if (list.current) list.current.scrollTop = cached?.scroll ?? 0;
  }, [cached]);
  useEffect(() => {
    if (!token) return;
    const id = window.setTimeout(
      () => {
        requests.invalidate();
        busyRef.current = false;
        setBusy(false);
        setToken(null);
        setPassword('');
        setError(
          'Owner unlock expired. Unlock again; your retained history and filters remain available.',
        );
      },
      Math.max(0, expiresAt - Date.now()),
    );
    return () => window.clearTimeout(id);
  }, [token, expiresAt, requests]);
  const load = useCallback(
    async (
      mode: 'restart' | 'older' | 'check',
      applied = filters,
      activeToken = token,
    ) => {
      if (!activeToken || (mode === 'check' && busyRef.current)) return;
      if (
        mode === 'check' &&
        (!page || !page.activity_cursor)
      )
        return;
      const current = requests.begin(),
        authGeneration = ownerWorkspaceGeneration();
      if (mode !== 'check') {
        busyRef.current = true;
        setBusy(true);
      }
      setError(null);
      try {
        const next = await audit.events(
          {
            ...applied,
            limit: 50,
            ...(mode === 'older' && page?.next_cursor
              ? { cursor: page.next_cursor }
              : mode === 'check' && page?.activity_cursor
                ? { cursor: page.activity_cursor }
                : {}),
          },
          activeToken,
        );
        if (!current() || authGeneration !== ownerWorkspaceGeneration()) return;
        if (mode === 'check') {
          if (
            next.new_entries_available ||
            (!rowsRef.current.length && next.entries.length > 0)
          )
            setNewEntries(true);
        } else {
          setRows((previous) =>
            mode === 'older'
              ? [
                  ...previous,
                  ...next.entries.filter(
                    (row) => !previous.some((known) => known.id === row.id),
                  ),
                ]
              : next.entries,
          );
          setPage(next);
          setNewEntries(next.new_entries_available);
          if (mode === 'restart') {
            setFilters(applied);
            setSelected(null);
            if (list.current) list.current.scrollTop = 0;
          }
        }
      } catch (cause) {
        if (!current()) return;
        setError(
          (cause as Error).message + ' Your loaded events have been kept.',
        );
        if (
          (cause as ApiError).status === 401 ||
          (cause as ApiError).status === 403
        ) {
          setToken(null);
          setExpiresAt(0);
        }
      } finally {
        if (current()) {
          busyRef.current = false;
          setBusy(false);
        }
      }
    },
    [filters, page, requests, token],
  );
  useRealtimeRefresh({
    resources: ['audit'],
    enabled: !!token,
    refresh: () => load('check'),
  });
  async function unlock(event: React.FormEvent) {
    event.preventDefault();
    if(busyRef.current)return;
    busyRef.current=true;
    setBusy(true);
    setError(null);
    const current = requests.begin(),
      authGeneration = ownerWorkspaceGeneration();
    const secret = password;
    setPassword('');
    try {
      const result = await audit.unlock(secret);
      if (!current() || authGeneration !== ownerWorkspaceGeneration()) return;
      setToken(result.audit_token);
      setExpiresAt(Date.now() + result.expires_in * 1000);
      const [definitions, employees] = await Promise.allSettled([
        audit.catalogue(result.audit_token),
        staff.listUsers(),
      ]);
      if (!current() || authGeneration !== ownerWorkspaceGeneration()) return;
      if (definitions.status === 'fulfilled') setCatalogue(definitions.value);
      else
        setError(
          'Action suggestions could not load; retained events and server search remain available.',
        );
      if (employees.status === 'fulfilled') setUsers(employees.value);
      if (!page || !sameReference)
        await load('restart', filters, result.audit_token);
    } catch (cause) {
      if (current()) setError((cause as Error).message);
    } finally {
      // A nested list load owns its newer request generation and busy state.
      // A locked/expired older unlock must not clear a subsequent operation.
      if(current()){busyRef.current=false;setBusy(false);}
    }
  }
  useEffect(() => {
    if (token && linkedReference && !sameReference && !page&&!busyRef.current)
      void load('restart', filters);
  }, [token, linkedReference, sameReference, page, load, filters]);
  const zone = page?.shop_timezone ?? 'Asia/Kolkata';
  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-2xl font-bold">
            <ShieldCheck size={22} /> Audit activity
          </h2>
          <p className="mt-1 text-sm text-fg-muted">
            Business actions, retained evidence and clear outcomes.
          </p>
        </div>
        {token && (
          <div className="flex gap-2">
            <button
              className="btn btn-ghost"
              onClick={() => void load('check')}
              disabled={busy}
            >
              <RefreshCw size={14} /> Check new activity
            </button>
            <button
              className="btn btn-ghost"
              onClick={() => {
                requests.invalidate();
                busyRef.current = false;
                setBusy(false);
                setToken(null);
                setExpiresAt(0);
                setPassword('');
              }}
            >
              <Lock size={14} /> Lock
            </button>
          </div>
        )}
      </header>
      {!token ? (
        <form onSubmit={unlock} className="card max-w-md space-y-3">
          <h3 className="font-semibold">Confirm owner access</h3>
          <p className="text-sm text-fg-muted">
            Re-enter your password to view protected activity. The unlock stays
            in memory for this account only.
          </p>
          <label className="block text-sm">
            Owner password
            <input
              type="password"
              autoComplete="current-password"
              className="input mt-1 w-full"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <button className="btn btn-primary" disabled={busy || !password}>
            {busy ? 'Confirming…' : 'Unlock activity'}
          </button>
          {error && (
            <p role="alert" className="text-sm text-accent-bad">
              {error}
            </p>
          )}
        </form>
      ) : (
        <>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void load('restart', draft);
            }}
            className="card space-y-3"
          >
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-[minmax(230px,1fr)_165px_165px_auto]">
              <label className="text-sm">
                Action or reference
                <div className="relative mt-1">
                  <Search
                    className="absolute left-3 top-3 text-fg-muted"
                    size={15}
                  />
                  <input
                    className="input w-full pl-9"
                    list="audit-actions"
                    placeholder="Code, action name or recorded text"
                    value={draft.q ?? ''}
                    onChange={(event) =>
                      setDraft({ ...draft, q: event.target.value || undefined })
                    }
                  />
                  <datalist id="audit-actions">
                    {catalogue?.actions.map((action) => (
                      <option key={action.code} value={action.code}>
                        {action.label}
                      </option>
                    ))}
                  </datalist>
                </div>
              </label>
              <label className="text-sm">
                Recorded from
                <input
                  type="date"
                  className="input mt-1 w-full"
                  value={draft.date_from ?? ''}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      date_from: event.target.value || undefined,
                    })
                  }
                />
              </label>
              <label className="text-sm">
                Recorded through
                <input
                  type="date"
                  className="input mt-1 w-full"
                  value={draft.date_to ?? ''}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      date_to: event.target.value || undefined,
                    })
                  }
                />
              </label>
              <button className="btn btn-primary self-end" disabled={busy}>
                Find activity
              </button>
            </div>
            <details>
              <summary className="cursor-pointer text-sm font-medium">
                Advanced filters
              </summary>
              <div className="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <label className="text-sm">
                  Activity view
                  <select
                    className="input mt-1 w-full"
                    value={draft.view ?? 'business'}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        view: event.target.value === 'all' ? 'all' : 'business',
                      })
                    }
                  >
                    <option value="business">Business activity</option>
                    <option value="all">All, including System</option>
                  </select>
                </label>
                <label className="text-sm">
                  Category
                  <select
                    className="input mt-1 w-full"
                    value={draft.category ?? ''}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        category: event.target.value || undefined,
                      })
                    }
                  >
                    <option value="">All categories</option>
                    {catalogue?.categories.map((category) => (
                      <option key={category} value={category}>
                        {businessLabel(category)}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-sm">
                  Outcome
                  <select
                    className="input mt-1 w-full"
                    value={draft.outcome ?? ''}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        outcome: event.target.value || undefined,
                      })
                    }
                  >
                    <option value="">All outcomes</option>
                    {catalogue?.outcomes.map((value) => (
                      <option key={value} value={value}>
                        {businessLabel(value)}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-sm">
                  Recorded actor
                  <select
                    className="input mt-1 w-full"
                    value={draft.actor_user_id ?? ''}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        actor_user_id: event.target.value || undefined,
                      })
                    }
                  >
                    <option value="">All actors</option>
                    {users.map((user) => (
                      <option key={user.id} value={user.id}>
                        {user.name} (current profile)
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-sm">
                  Exact bill / source reference
                  <input
                    className="input mt-1 w-full"
                    value={draft.reference ?? ''}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        reference: event.target.value || undefined,
                      })
                    }
                  />
                </label>
                <label className="text-sm">
                  Exact action
                  <select
                    className="input mt-1 w-full"
                    value={draft.code ?? ''}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        code: event.target.value || undefined,
                      })
                    }
                  >
                    <option value="">All actions</option>
                    {catalogue?.actions.map((action) => (
                      <option key={action.code} value={action.code}>
                        {action.code} · {action.label}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
            </details>
          </form>
          <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
            <span>
              <b>{rows.length}</b> loaded · <b>{page?.matching_count ?? '—'}</b>{' '}
              event{page?.matching_count === 1 ? '' : 's'} in this snapshot
            </span>
            <span className="text-fg-muted">
              Recorded shop dates · {zone} · event count, not revenue
            </span>
          </div>
          {error && (
            <div
              role="alert"
              className="card border-accent-bad/40 text-sm text-accent-bad"
            >
              <AlertCircle size={15} className="mr-2 inline" />
              {error}{' '}
              <button
                className="underline"
                onClick={() => void load('restart')}
              >
                Start a fresh snapshot
              </button>
            </div>
          )}
          {newEntries && (
            <div
              className="card flex flex-wrap items-center justify-between gap-2 text-sm"
              role="status"
            >
              <span>
                New matching activity is available. Loaded history stays in
                place.
              </span>
              <button
                className="btn btn-ghost"
                disabled={busy}
                onClick={() => void load('restart')}
              >
                Show new activity
              </button>
            </div>
          )}
          <div
            className={`grid items-start gap-4 ${selected ? '2xl:grid-cols-[minmax(0,1.6fr)_minmax(320px,1fr)]' : ''}`}
          >
            <section className="card min-w-0 !p-0">
              <div
                ref={list}
                className="max-h-[70dvh] overflow-auto"
                onScroll={(event) => {
                  stateRef.current.scroll = event.currentTarget.scrollTop;
                  persist();
                }}
              >
                <table className="w-full min-w-[680px] text-sm">
                  <thead className="sticky top-0 bg-bg-raised text-left text-xs text-fg-muted">
                    <tr>
                      <th className="p-3">Recorded shop time</th>
                      <th className="p-3">Action & reference</th>
                      <th className="p-3">Actor</th>
                      <th className="p-3">Outcome</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((entry) => (
                      <tr
                        key={entry.id}
                        className={`border-t border-bg-border ${selected?.id === entry.id ? 'bg-bg-raised' : ''}`}
                      >
                        <td className="p-3 tabular-nums text-xs">
                          {shopTime(entry.created_at, zone)}
                        </td>
                        <td className="p-3">
                          <button
                            onClick={(event) => openDetails(entry, event.currentTarget)}
                            aria-expanded={selected?.id === entry.id}
                            aria-controls={selected?.id === entry.id ? `audit-detail-${entry.id}` : undefined}
                            className="w-full text-left"
                          >
                            <span className="mr-2 font-mono text-xs text-fg-muted">
                              {entry.action_code ?? 'LEGACY'}
                            </span>
                            <span className="font-semibold">
                              {entry.action_label}
                            </span>
                            <span className="mt-1 block text-xs text-fg-muted">
                              {entry.source_reference ??
                                'No retained business reference'}
                            </span>
                            <span className="mt-1 block text-xs">
                              {auditBusinessSummary(entry)}
                            </span>
                          </button>
                        </td>
                        <td className="p-3 text-xs">
                          {auditActor(entry)}
                          {entry.target_user_id && (
                            <span className="mt-1 block text-fg-muted">
                              Target account is separate
                            </span>
                          )}
                        </td>
                        <td className="p-3 text-xs">
                          <span
                            className={
                              /failed|rejected/.test(entry.outcome)
                                ? 'text-accent-bad'
                                : 'text-fg'
                            }
                          >
                            {businessLabel(entry.outcome)}
                          </span>
                          <span className="mt-1 block text-fg-muted">
                            {businessLabel(entry.stage)}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!rows.length && (
                  <p className="p-6 text-sm text-fg-muted">
                    {busy
                      ? 'Loading activity…'
                      : error
                        ? 'Activity could not be loaded. Retry without changing filters.'
                        : 'No matching events in this search.'}
                  </p>
                )}
              </div>
              {page?.has_more && (
                <div className="border-t border-bg-border p-3">
                  <button
                    className="btn btn-ghost w-full"
                    onClick={() => void load('older')}
                    disabled={busy}
                  >
                    <ChevronDown size={14} />
                    {busy ? 'Loading…' : 'Load older activity'}
                  </button>
                </div>
              )}
            </section>
            {selected && (
              <div ref={detail} id={`audit-detail-${selected.id}`} tabIndex={-1} className="min-w-0 focus:outline-none focus:ring-2 focus:ring-fg-muted focus:ring-offset-2 focus:ring-offset-bg" aria-label="Selected activity evidence">
                <AuditDetail entry={selected} zone={zone} onClose={closeDetails} />
                <button className="btn btn-ghost mt-3" onClick={closeDetails}>Return to loaded activity</button>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
export function AuditDetail({
  entry,
  zone,
  onClose,
}: {
  entry: BusinessAuditEntryDTO;
  zone: string;
  onClose: () => void;
}) {
  const orderId = auditOrderId(entry);
  const isBusinessField = (key: string) =>
    !/(^id$|_id$|_ids$|fingerprint|hash|password|secret|token|otp|authorization|cookie|private_key)/i.test(
      key,
    );
  const details = Object.entries(entry.business_details ?? {}).filter(([key]) =>
    isBusinessField(key),
  );
  const changes = auditBusinessChanges(entry);
  const snapshots = auditRetainedSnapshots(entry);
  return (
    <aside className="card min-w-0 space-y-4" aria-label="Activity details">
      <header className="flex items-start justify-between gap-3">
        <div>
          <p className="font-mono text-xs text-fg-muted">
            {entry.action_code ?? 'Legacy / unclassified'}
          </p>
          <h3 className="mt-1 text-lg font-semibold">{entry.action_label}</h3>
        </div>
        <button
          className="btn btn-ghost !p-2"
          aria-label="Close activity details"
          onClick={onClose}
        >
          <X size={16} />
        </button>
      </header>
      <p className="text-sm">{auditBusinessSummary(entry)}</p>
      <dl className="grid gap-3 text-sm sm:grid-cols-2 2xl:grid-cols-1">
        <Detail label="Recorded actor" value={auditActor(entry)} />
        <Detail
          label="Actor provenance"
          value={businessLabel(entry.actor_kind)}
        />
        <Detail
          label="Actor name basis"
          value={businessLabel(entry.actor_label_source)}
        />
        {entry.target_user_id && (
          <Detail
            label="Target account (not the actor)"
            value={entry.target_user_id}
          />
        )}
        <Detail
          label="Result / stage"
          value={`${businessLabel(entry.outcome)} · ${businessLabel(entry.stage)}`}
        />
        <Detail
          label={`Server recorded · ${zone}`}
          value={shopTime(entry.created_at, zone)}
        />
        <Detail
          label="Source reference"
          value={entry.source_reference ?? 'Not retained'}
        />
        <Detail
          label="Classification evidence"
          value={businessLabel(entry.classification_source)}
        />
        {entry.reason && <Detail label="Reason" value={entry.reason} />}{' '}
        {details.map(([key, value]) => (
          <Detail
            key={key}
            label={businessLabel(key)}
            value={businessValue(key, value, zone)}
          />
        ))}
      </dl>
      {entry.match_explanation && (
        <p className="text-xs text-fg-muted">
          Match: {entry.match_explanation}
        </p>
      )}
      {(orderId || entry.source_reference) && (
        <Link
          className="btn btn-ghost"
          to={
            orderId
              ? `/operations?receipt=${encodeURIComponent(orderId)}`
              : `/operations?receipt_q=${encodeURIComponent(entry.source_reference!)}`
          }
        >
          <ArrowUpRight size={14} /> Open linked receipt
        </Link>
      )}
      {changes.length > 0 && (
        <details>
          <summary className="cursor-pointer text-sm font-semibold">
            Recorded Before → After
          </summary>
          <dl className="mt-3 space-y-2 text-sm">
            {changes.map(({key, before, after}) => (
              <Detail key={key} label={businessLabel(key)}
                value={`${businessValue(key, safeAuditValue(before), zone)} → ${businessValue(key, safeAuditValue(after), zone)}`} />
            ))}
          </dl>
        </details>
      )}
      {snapshots.length > 0 && <section aria-label="Retained action snapshots" className="space-y-3">
        <h4 className="text-sm font-semibold">Retained action snapshots</h4>
        {!entry.before && entry.after && <p className="text-xs text-fg-muted">Before state was not retained; no change is inferred from the after snapshot.</p>}
        {entry.before && !entry.after && <p className="text-xs text-fg-muted">After state was not retained; the before snapshot is retained evidence only.</p>}
        <dl className="grid gap-3 text-sm sm:grid-cols-2 2xl:grid-cols-1">{snapshots.map(({side,key,value}) =>
          <Detail key={`${side}:${key}`} label={`${side} · ${businessLabel(key)}`} value={businessValue(key,safeAuditValue(value),zone)} />)}</dl>
      </section>}
      <details>
        <summary className="cursor-pointer text-sm text-fg-muted">
          Technical details
        </summary>
        <dl className="mt-3 space-y-2 text-xs">
          <Detail
            label="Event / operation"
            value={`${entry.id} / ${entry.operation_id ?? 'Not retained'}`}
          />
          <Detail
            label="Client reported (unverified)"
            value={shopTime(entry.client_reported_at, zone)}
          />
          <Detail
            label="Server sync received"
            value={shopTime(entry.synced_at, zone)}
          />
          <Detail
            label="Reported platform / build"
            value={`${entry.client_platform ?? 'Unknown'} / ${entry.client_version_code ?? 'Unknown'}`}
          />
        </dl>
        <pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap break-all text-xs">
          {JSON.stringify(
            safeAuditValue({
              entity_type: entry.entity_type,
              entity_id: entry.entity_id,
              business_details: entry.business_details,
              before: entry.before,
              after: entry.after,
              request_id: entry.request_id,
            }),
            null,
            2,
          )}
        </pre>
      </details>
    </aside>
  );
}
function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid gap-1 border-b border-bg-border/60 pb-2 sm:grid-cols-[minmax(100px,0.8fr)_minmax(0,1.2fr)]">
      <dt className="text-fg-muted">{label}</dt>
      <dd className="break-words tabular-nums">{value}</dd>
    </div>
  );
}
