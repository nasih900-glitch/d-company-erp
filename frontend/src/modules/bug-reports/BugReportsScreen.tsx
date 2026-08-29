import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  CircleDot,
  Clock3,
  Eye,
  Inbox,
  Loader2,
  MapPin,
  MessageSquareWarning,
  MonitorSmartphone,
  Paperclip,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  MessageSquareText,
  UserRound,
  Wifi,
  WifiOff,
} from 'lucide-react';

import Modal from '@/components/ui/Modal';
import { useNotifications } from '@/components/ui/Notifications';
import { SkeletonCard } from '@/components/ui/Skeleton';
import {
  bugReports,
  type BugReportDTO,
  type BugReportListResponseDTO,
  type BugReportSeverity,
  type BugReportStatus,
  type BugReportUpdateDTO,
  type BugReportAttachmentDTO,
} from '@/lib/erp-api';
import { subscribeRealtime } from '@/lib/realtime';
import { StableMutationIntent } from '@/lib/stable-mutation-intent';
import {
  BUG_REPORT_CATEGORY_OPTIONS,
  BUG_REPORT_SEVERITY_OPTIONS,
  BUG_REPORT_STATUS_OPTIONS,
  BugReportDetailRequestGate,
  EMPTY_BUG_REPORT_FILTERS,
  buildBugReportListParams,
  bugReportCategoryLabel,
  bugReportSeverityLabel,
  bugReportStatusOptionsFor,
  bugReportStatusLabel,
  resolutionNoteError,
  type BugReportFilters,
} from './bug-report-ui';

const PAGE_SIZE = 25;
const MAX_RESOLUTION_NOTE_LENGTH = 4_000;
const INBOX_POLL_INTERVAL_MS = 60_000;

const STATUS_TONE: Record<BugReportStatus, string> = {
  open: 'border-accent-bad/45 bg-accent-bad/10 text-accent-bad',
  acknowledged: 'border-accent-purple/45 bg-accent-purple/10 text-accent-purple',
  in_progress: 'border-accent-gold/45 bg-accent-gold/10 text-accent-gold',
  resolved: 'border-accent-good/45 bg-accent-good/10 text-accent-good',
  closed: 'border-bg-border bg-bg-raised text-fg-muted',
  rejected: 'border-bg-border bg-bg-raised text-fg-muted',
};

const SEVERITY_TONE: Record<BugReportSeverity, string> = {
  critical: 'border-accent-bad/55 bg-accent-bad/12 text-accent-bad',
  high: 'border-accent-gold/55 bg-accent-gold/10 text-accent-gold',
  medium: 'border-accent-purple/45 bg-accent-purple/10 text-accent-purple',
  low: 'border-bg-border bg-bg-raised text-fg-muted',
};

export default function BugReportsScreen() {
  const notifications = useNotifications();
  const [draftFilters, setDraftFilters] = useState<BugReportFilters>({
    ...EMPTY_BUG_REPORT_FILTERS,
  });
  const [filters, setFilters] = useState<BugReportFilters>({ ...EMPTY_BUG_REPORT_FILTERS });
  const [offset, setOffset] = useState(0);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [result, setResult] = useState<BugReportListResponseDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [detail, setDetail] = useState<BugReportDTO | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailReady, setDetailReady] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [statusDraft, setStatusDraft] = useState<BugReportStatus>('open');
  const [noteDraft, setNoteDraft] = useState('');
  const [saving, setSaving] = useState(false);
  const [replyDraft, setReplyDraft] = useState('');
  const [replying, setReplying] = useState(false);
  const [attachmentPreview, setAttachmentPreview] = useState<{
    url: string;
    name: string;
  } | null>(null);
  const detailRequestId = useRef<string | null>(null);
  const detailRequestController = useRef<AbortController | null>(null);
  const detailSession = useRef<number | null>(null);
  const replyRequestGeneration = useRef(0);
  const replyLockRef = useRef(false);
  const replyIntentRef = useRef<StableMutationIntent<{ reportId: string; message: string }> | null>(null);
  if (replyIntentRef.current === null) {
    replyIntentRef.current = new StableMutationIntent({
      prefix: 'bug-report-reply:web',
    });
  }
  const detailRequestGate = useRef<BugReportDetailRequestGate | null>(null);
  if (detailRequestGate.current === null) {
    detailRequestGate.current = new BugReportDetailRequestGate();
  }

  useEffect(() => {
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') {
        setRefreshVersion((version) => version + 1);
      }
    };
    const interval = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        setRefreshVersion((version) => version + 1);
      }
    }, INBOX_POLL_INTERVAL_MS);
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => {
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
      detailRequestGate.current?.closeSession();
      detailSession.current = null;
      detailRequestId.current = null;
      detailRequestController.current?.abort();
    };
  }, []);

  useEffect(() => subscribeRealtime('bug_reports', () => {
    setRefreshVersion((version) => version + 1);
  }), []);

  useEffect(() => () => {
    if (attachmentPreview) URL.revokeObjectURL(attachmentPreview.url);
  }, [attachmentPreview]);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    setLoading(true);
    setLoadError(null);

    void bugReports
      .list(buildBugReportListParams(filters, offset, PAGE_SIZE), controller.signal)
      .then((response) => {
        if (!cancelled) setResult(response);
      })
      .catch((error: unknown) => {
        if (!cancelled && !controller.signal.aborted) setLoadError(errorMessage(error));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [filters, offset, refreshVersion]);

  const applyFilters = useCallback((event: React.FormEvent) => {
    event.preventDefault();
    setOffset(0);
    setFilters({ ...draftFilters, q: draftFilters.q.trim() });
    setRefreshVersion((version) => version + 1);
  }, [draftFilters]);

  const clearFilters = useCallback(() => {
    const empty = { ...EMPTY_BUG_REPORT_FILTERS };
    setDraftFilters(empty);
    setFilters(empty);
    setOffset(0);
    setRefreshVersion((version) => version + 1);
  }, []);

  const refresh = useCallback(() => {
    setRefreshVersion((version) => version + 1);
  }, []);

  const openDetail = useCallback(async (report: BugReportDTO) => {
    replyRequestGeneration.current += 1;
    replyLockRef.current = false;
    replyIntentRef.current!.invalidate();
    const session = detailRequestGate.current!.openSession();
    detailSession.current = session;
    detailRequestController.current?.abort();
    const controller = new AbortController();
    detailRequestController.current = controller;
    detailRequestId.current = report.id;
    setDetail(report);
    setStatusDraft(report.status);
    setNoteDraft(report.internal_resolution_note ?? '');
    setDetailError(null);
    setDetailLoading(true);
    setDetailReady(false);
    setSaving(false);
    setReplyDraft('');
    setReplying(false);

    try {
      const latest = await bugReports.get(report.id, controller.signal);
      if (
        !detailRequestGate.current!.isCurrentSession(session)
        || detailSession.current !== session
        || detailRequestId.current !== report.id
        || controller.signal.aborted
      ) return;
      setDetail(latest);
      setStatusDraft(latest.status);
      setNoteDraft(latest.internal_resolution_note ?? '');
      setDetailReady(true);
      void bugReports.markRead(report.id).catch(() => {
        // The detail remains usable; a failed cursor write simply keeps the
        // unread badge present so the owner can retry by reopening it.
      });
    } catch (error) {
      if (
        !detailRequestGate.current!.isCurrentSession(session)
        || detailSession.current !== session
        || detailRequestId.current !== report.id
        || detailRequestController.current !== controller
        || controller.signal.aborted
      ) return;
      setDetailError(
        `The current report details could not be refreshed. The saved list copy is still shown. ${errorMessage(error)}`,
      );
    } finally {
      if (
        detailRequestGate.current!.isCurrentSession(session)
        && detailSession.current === session
        && detailRequestId.current === report.id
        && detailRequestController.current === controller
      ) {
        detailRequestController.current = null;
        setDetailLoading(false);
      }
    }
  }, []);

  const closeDetail = useCallback(() => {
    replyRequestGeneration.current += 1;
    replyLockRef.current = false;
    replyIntentRef.current!.invalidate();
    detailRequestGate.current!.closeSession();
    detailSession.current = null;
    detailRequestController.current?.abort();
    detailRequestController.current = null;
    detailRequestId.current = null;
    setDetail(null);
    setDetailError(null);
    setDetailLoading(false);
    setDetailReady(false);
    setSaving(false);
    setReplyDraft('');
    setReplying(false);
  }, []);

  const changeReplyDraft = useCallback((value: string) => {
    if (replyDraft !== value) replyIntentRef.current!.invalidate();
    setReplyDraft(value);
  }, [replyDraft]);

  const sendPublicReply = useCallback(async (event: React.FormEvent) => {
    event.preventDefault();
    const message = replyDraft.trim();
    if (
      !detail
      || !detailReady
      || replying
      || replyLockRef.current
      || message.length < 2
    ) return;
    const reportId = detail.id;
    const requestGeneration = replyRequestGeneration.current + 1;
    replyRequestGeneration.current = requestGeneration;
    replyLockRef.current = true;
    const intent = replyIntentRef.current!.resolve(
      `${reportId}\u0000${message}`,
      () => ({ reportId, message }),
    );
    setReplying(true);
    setDetailError(null);
    try {
      const reply = await bugReports.reply(
        intent.payload.reportId,
        intent.payload.message,
        intent.idempotencyKey,
      );
      replyIntentRef.current!.confirmSuccess(intent);
      if (
        replyRequestGeneration.current !== requestGeneration
        || detailRequestId.current !== reportId
      ) return;
      setDetail((current) => {
        if (!current || current.id !== reportId) return current;
        if (current.public_replies.some((existing) => existing.id === reply.id)) return current;
        return { ...current, public_replies: [...current.public_replies, reply] };
      });
      setReplyDraft('');
      notifications.success('The staff member can now see this response in My requests.', {
        title: 'Reply sent',
      });
      setRefreshVersion((version) => version + 1);
    } catch (error) {
      if (
        replyRequestGeneration.current !== requestGeneration
        || detailRequestId.current !== reportId
      ) return;
      const message = `The reply was not sent. ${errorMessage(error)}`;
      setDetailError(message);
      notifications.error(message, { title: 'Could not send reply' });
    } finally {
      if (replyRequestGeneration.current === requestGeneration) {
        replyLockRef.current = false;
        setReplying(false);
      }
    }
  }, [detail, detailReady, notifications, replying, replyDraft]);

  const openAttachment = useCallback(async (
    reportId: string,
    attachment: BugReportAttachmentDTO,
  ) => {
    if (!attachment.available) return;
    try {
      const blob = await bugReports.inboxAttachment(reportId, attachment.id);
      const url = URL.createObjectURL(blob);
      setAttachmentPreview((current) => {
        if (current) URL.revokeObjectURL(current.url);
        return { url, name: attachment.filename };
      });
    } catch (error) {
      notifications.error(errorMessage(error), { title: 'Could not open screenshot' });
    }
  }, [notifications]);

  const noteValue = noteDraft.trim() || null;
  const detailNoteValue = detail?.internal_resolution_note?.trim() || null;
  const hasChanges = Boolean(
    detail && (statusDraft !== detail.status || noteValue !== detailNoteValue),
  );
  const noteTooLong = noteDraft.length > MAX_RESOLUTION_NOTE_LENGTH;
  const noteValidationError = resolutionNoteError(statusDraft, noteDraft);

  const saveResolution = useCallback(async (event: React.FormEvent) => {
    event.preventDefault();
    const session = detailSession.current;
    if (
      !detail
      || session === null
      || !detailReady
      || detailLoading
      || !hasChanges
      || noteTooLong
      || noteValidationError
    ) return;
    const saveToken = detailRequestGate.current!.startSave(session);
    if (saveToken === null) return;

    const body: BugReportUpdateDTO = {};
    if (statusDraft !== detail.status) body.status = statusDraft;
    if (noteValue !== detailNoteValue) body.internal_resolution_note = noteValue;

    setSaving(true);
    setDetailError(null);
    try {
      const updated = await bugReports.update(detail.id, body);
      if (
        !detailRequestGate.current!.isCurrentSave(saveToken)
        || detailSession.current !== session
        || detailRequestId.current !== detail.id
      ) return;
      setDetail(updated);
      setStatusDraft(updated.status);
      setNoteDraft(updated.internal_resolution_note ?? '');
      setResult((current) => current ? {
        ...current,
        items: current.items.map((item) => item.id === updated.id ? updated : item),
      } : current);
      notifications.success('The report status and internal note are saved.', {
        title: 'Bug report updated',
      });
      setRefreshVersion((version) => version + 1);
    } catch (error) {
      if (
        !detailRequestGate.current!.isCurrentSave(saveToken)
        || detailSession.current !== session
        || detailRequestId.current !== detail.id
      ) return;
      const message = `The report was not changed. ${errorMessage(error)}`;
      setDetailError(message);
      notifications.error(message, { title: 'Could not update bug report' });
    } finally {
      if (detailRequestGate.current!.finishSave(saveToken)) setSaving(false);
    }
  }, [
    detail,
    detailLoading,
    detailNoteValue,
    detailReady,
    hasChanges,
    noteTooLong,
    noteValidationError,
    noteValue,
    notifications,
    statusDraft,
  ]);

  const summary = result?.summary;
  const openCount = summary ? summary.counts_by_status.open : null;
  const activeCount = summary
    ? summary.counts_by_status.acknowledged + summary.counts_by_status.in_progress
    : null;
  const criticalCount = summary ? summary.counts_by_severity.critical : null;
  const completedCount = summary
    ? summary.counts_by_status.resolved + summary.counts_by_status.closed
    : null;
  const total = result?.total ?? 0;
  const shownStart = total > 0 ? offset + 1 : 0;
  const shownEnd = Math.min(offset + (result?.items.length ?? 0), total);
  const hasPrevious = offset > 0;
  const hasNext = offset + PAGE_SIZE < total;
  const filtersActive = useMemo(
    () => Object.values(filters).some(Boolean),
    [filters],
  );

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-2xl font-bold">
            <MessageSquareWarning size={23} className="text-accent" aria-hidden="true" />
            Bug report inbox
          </h2>
          <p className="mt-1 text-sm text-fg-muted">
            Review issues sent from staff devices and web sessions.
          </p>
        </div>
        <button
          type="button"
          className="btn btn-ghost"
          onClick={refresh}
          disabled={loading}
          aria-label="Refresh bug reports"
        >
          <RefreshCw size={16} className={loading ? 'animate-spin' : ''} aria-hidden="true" />
          {loading ? 'Refreshing…' : 'Refresh'}
        </button>
      </header>

      <section className="grid grid-cols-2 gap-3 lg:grid-cols-4" aria-label="Bug report summary">
        <SummaryCard
          label="Open"
          value={openCount}
          hint="Awaiting review"
          Icon={CircleDot}
          tone="text-accent-bad"
        />
        <SummaryCard
          label="Being handled"
          value={activeCount}
          hint="Acknowledged or in progress"
          Icon={Activity}
          tone="text-accent-gold"
        />
        <SummaryCard
          label="Critical"
          value={criticalCount}
          hint="Across these filters"
          Icon={AlertTriangle}
          tone="text-accent-bad"
        />
        <SummaryCard
          label="Completed"
          value={completedCount}
          hint="Resolved or closed"
          Icon={CheckCircle2}
          tone="text-accent-good"
        />
      </section>

      <form
        onSubmit={applyFilters}
        className="card grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-[minmax(220px,1fr)_170px_150px_170px_150px_auto] xl:items-end"
        aria-label="Filter bug reports"
      >
        <div className="sm:col-span-2 xl:col-span-1">
          <label htmlFor="bug-report-search" className="text-xs font-medium text-fg-muted">
            Search reports
          </label>
          <div className="relative mt-1">
            <Search
              size={16}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-fg-muted"
              aria-hidden="true"
            />
            <input
              id="bug-report-search"
              className="input pl-10"
              value={draftFilters.q}
              onChange={(event) => setDraftFilters((current) => ({
                ...current,
                q: event.target.value,
              }))}
              placeholder="Title, detail, reporter…"
              autoComplete="off"
              maxLength={100}
            />
          </div>
        </div>
        <FilterSelect
          id="bug-report-status"
          label="Status"
          value={draftFilters.status}
          onChange={(value) => setDraftFilters((current) => ({
            ...current,
            status: value as BugReportFilters['status'],
          }))}
          options={BUG_REPORT_STATUS_OPTIONS}
        />
        <FilterSelect
          id="bug-report-severity"
          label="Severity"
          value={draftFilters.severity}
          onChange={(value) => setDraftFilters((current) => ({
            ...current,
            severity: value as BugReportFilters['severity'],
          }))}
          options={BUG_REPORT_SEVERITY_OPTIONS}
        />
        <FilterSelect
          id="bug-report-category"
          label="Category"
          value={draftFilters.category}
          onChange={(value) => setDraftFilters((current) => ({
            ...current,
            category: value as BugReportFilters['category'],
          }))}
          options={BUG_REPORT_CATEGORY_OPTIONS}
        />
        <FilterSelect
          id="bug-report-platform"
          label="Platform"
          value={draftFilters.platform}
          onChange={(value) => setDraftFilters((current) => ({ ...current, platform: value }))}
          options={[
            { value: 'android', label: 'Android' },
            { value: 'web', label: 'Web' },
          ]}
        />
        <button type="submit" className="btn btn-primary w-full">
          Apply
        </button>
        {filtersActive && (
          <button
            type="button"
            className="btn btn-ghost w-full sm:col-span-2 xl:col-span-6"
            onClick={clearFilters}
          >
            Clear filters
          </button>
        )}
      </form>

      {loadError && (
        <div
          className="card flex flex-wrap items-center justify-between gap-3 border-accent-bad/45 bg-accent-bad/10"
          role="alert"
        >
          <div className="flex min-w-0 items-start gap-2 text-sm text-accent-bad">
            <AlertCircle size={18} className="mt-0.5 shrink-0" aria-hidden="true" />
            <div>
              <div className="font-semibold">Couldn’t load bug reports</div>
              <p className="mt-0.5 break-words text-fg-muted">{loadError}</p>
            </div>
          </div>
          <button type="button" className="btn btn-ghost" onClick={refresh} disabled={loading}>
            Try again
          </button>
        </div>
      )}

      {loading && !result ? (
        <SkeletonCard />
      ) : (
        <section className="card !p-0 overflow-hidden" aria-labelledby="bug-report-results-title">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-bg-border px-4 py-4 md:px-5">
            <div>
              <h3 id="bug-report-results-title" className="font-semibold">Reports</h3>
              <p className="mt-0.5 text-xs text-fg-muted" aria-live="polite">
                {total === 0
                  ? 'No matching reports'
                  : `Showing ${shownStart}–${shownEnd} of ${total}`}
                {loading ? ' · Refreshing' : ''}
              </p>
            </div>
            {filtersActive && (
              <span className="chip border-accent/35 text-accent">Filters applied</span>
            )}
          </div>

          {!result?.items.length ? (
            <EmptyInbox filtered={filtersActive} onClear={clearFilters} />
          ) : (
            <>
              <div className="mobile-card-list xl:hidden">
                {result.items.map((report) => (
                  <ReportMobileCard key={report.id} report={report} onOpen={openDetail} />
                ))}
              </div>
              <div className="hidden overflow-x-auto xl:block">
                <table className="w-full min-w-[900px] text-sm">
                  <thead className="bg-bg-raised/80 text-xs text-fg-muted">
                    <tr>
                      <th scope="col" className="p-3 text-left">Report</th>
                      <th scope="col" className="p-3 text-left">Severity</th>
                      <th scope="col" className="p-3 text-left">Status</th>
                      <th scope="col" className="p-3 text-left">Reporter</th>
                      <th scope="col" className="p-3 text-left">Platform</th>
                      <th scope="col" className="p-3 text-left">Received</th>
                      <th scope="col" className="p-3 text-right">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.items.map((report) => (
                      <ReportTableRow key={report.id} report={report} onOpen={openDetail} />
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {total > 0 && (
            <div className="flex flex-wrap items-center justify-between gap-3 border-t border-bg-border px-4 py-3 md:px-5">
              <p className="text-xs text-fg-muted">
                Page {Math.floor(offset / PAGE_SIZE) + 1} of {Math.max(1, Math.ceil(total / PAGE_SIZE))}
              </p>
              <div className="flex gap-2">
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={() => setOffset((current) => Math.max(0, current - PAGE_SIZE))}
                  disabled={!hasPrevious || loading}
                  aria-label="Previous bug reports page"
                >
                  <ChevronLeft size={16} aria-hidden="true" /> Previous
                </button>
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={() => setOffset((current) => current + PAGE_SIZE)}
                  disabled={!hasNext || loading}
                  aria-label="Next bug reports page"
                >
                  Next <ChevronRight size={16} aria-hidden="true" />
                </button>
              </div>
            </div>
          )}
        </section>
      )}

      <Modal
        open={Boolean(detail)}
        onClose={closeDetail}
        title={detail?.title ?? 'Bug report details'}
        size="lg"
      >
        {detail && (
          <ReportDetail
            report={detail}
            detailLoading={detailLoading}
            detailReady={detailReady}
            error={detailError}
            statusDraft={statusDraft}
            noteDraft={noteDraft}
            noteTooLong={noteTooLong}
            noteValidationError={noteValidationError}
            saving={saving}
            hasChanges={hasChanges}
            onStatusChange={setStatusDraft}
            onNoteChange={setNoteDraft}
            onSave={saveResolution}
            replyDraft={replyDraft}
            replying={replying}
            onReplyChange={changeReplyDraft}
            onReply={sendPublicReply}
            onOpenAttachment={openAttachment}
          />
        )}
      </Modal>
      <Modal
        open={Boolean(attachmentPreview)}
        onClose={() => setAttachmentPreview((current) => {
          if (current) URL.revokeObjectURL(current.url);
          return null;
        })}
        title={attachmentPreview?.name ?? 'Support screenshot'}
        size="lg"
      >
        {attachmentPreview && (
          <img
            src={attachmentPreview.url}
            alt="Screenshot attached to this support request"
            className="max-h-[70dvh] w-full rounded-xl bg-bg-raised object-contain"
          />
        )}
      </Modal>
    </div>
  );
}

function SummaryCard({
  label,
  value,
  hint,
  Icon,
  tone,
}: {
  label: string;
  value: number | null;
  hint: string;
  Icon: typeof CircleDot;
  tone: string;
}) {
  return (
    <div className="card !p-4 md:!p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs font-medium uppercase tracking-wide text-fg-muted">{label}</p>
          <p className="mt-2 font-mono text-2xl font-bold tabular-nums md:text-3xl">
            {value ?? '—'}
          </p>
          <p className="mt-1 hidden text-xs text-fg-muted sm:block">{hint}</p>
        </div>
        <span className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-bg-raised ${tone}`}>
          <Icon size={19} aria-hidden="true" />
        </span>
      </div>
    </div>
  );
}

function FilterSelect({
  id,
  label,
  value,
  onChange,
  options,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: ReadonlyArray<{ value: string; label: string }>;
}) {
  return (
    <div className="min-w-0">
      <label htmlFor={id} className="text-xs font-medium text-fg-muted">{label}</label>
      <select
        id={id}
        className="input mt-1"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="">All</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </select>
    </div>
  );
}

function EmptyInbox({ filtered, onClear }: { filtered: boolean; onClear: () => void }) {
  return (
    <div className="flex min-h-[280px] flex-col items-center justify-center px-5 py-10 text-center">
      <span className="grid h-14 w-14 place-items-center rounded-2xl border border-bg-border bg-bg-raised text-fg-muted">
        <Inbox size={25} aria-hidden="true" />
      </span>
      <h3 className="mt-4 font-semibold">
        {filtered ? 'No reports match these filters' : 'No bug reports yet'}
      </h3>
      <p className="mt-1 max-w-md text-sm text-fg-muted">
        {filtered
          ? 'Clear or adjust the filters to review other reports.'
          : 'New reports submitted by authorised staff devices will appear here.'}
      </p>
      {filtered && (
        <button type="button" className="btn btn-ghost mt-5" onClick={onClear}>
          Clear filters
        </button>
      )}
    </div>
  );
}

function ReportMobileCard({
  report,
  onOpen,
}: {
  report: BugReportDTO;
  onOpen: (report: BugReportDTO) => void;
}) {
  return (
    <button
      type="button"
      className="mobile-record-card w-full text-left transition hover:bg-bg-raised/40 active:scale-[0.99]"
      onClick={() => onOpen(report)}
      aria-label={`Review ${report.title}`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-semibold leading-snug">{report.title}</p>
          <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-fg-muted">
            {report.description}
          </p>
        </div>
        <SeverityBadge severity={report.severity} />
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <StatusBadge status={report.status} />
        <span className="chip text-[10px]">{bugReportCategoryLabel(report.category)}</span>
        <span className="chip text-[10px]">{platformLabel(report.client_context.platform)}</span>
      </div>
      <div className="mt-3 flex items-center justify-between gap-3 text-xs text-fg-muted">
        <span className="truncate">{report.reporter.name || report.reporter.email}</span>
        <span className="shrink-0">{formatDate(report.created_at)}</span>
      </div>
    </button>
  );
}

function ReportTableRow({
  report,
  onOpen,
}: {
  report: BugReportDTO;
  onOpen: (report: BugReportDTO) => void;
}) {
  return (
    <tr className="border-t border-bg-border/70 transition hover:bg-bg-raised/30">
      <td className="max-w-[360px] p-3">
        <p className="truncate font-medium">{report.title}</p>
        <p className="mt-0.5 truncate text-xs text-fg-muted">
          {bugReportCategoryLabel(report.category)} · {report.description}
        </p>
      </td>
      <td className="p-3"><SeverityBadge severity={report.severity} /></td>
      <td className="p-3"><StatusBadge status={report.status} /></td>
      <td className="max-w-[190px] p-3">
        <p className="truncate">{report.reporter.name}</p>
        {report.reporter.email && (
          <p className="truncate text-[11px] text-fg-muted">{report.reporter.email}</p>
        )}
      </td>
      <td className="p-3 text-xs text-fg-muted">
        {platformLabel(report.client_context.platform)}
        {report.client_context.app_version && (
          <div className="mt-0.5">v{report.client_context.app_version}</div>
        )}
      </td>
      <td className="whitespace-nowrap p-3 text-xs text-fg-muted">
        {formatDate(report.created_at)}
      </td>
      <td className="p-3 text-right">
        <button
          type="button"
          className="tap-target inline-flex items-center justify-center rounded-xl text-fg-muted transition hover:bg-bg-raised hover:text-accent"
          onClick={() => onOpen(report)}
          aria-label={`Review ${report.title}`}
        >
          <Eye size={17} aria-hidden="true" />
        </button>
      </td>
    </tr>
  );
}

export function ReportDetail({
  report,
  detailLoading,
  detailReady,
  error,
  statusDraft,
  noteDraft,
  noteTooLong,
  noteValidationError,
  saving,
  hasChanges,
  onStatusChange,
  onNoteChange,
  onSave,
  replyDraft,
  replying,
  onReplyChange,
  onReply,
  onOpenAttachment,
}: {
  report: BugReportDTO;
  detailLoading: boolean;
  detailReady: boolean;
  error: string | null;
  statusDraft: BugReportStatus;
  noteDraft: string;
  noteTooLong: boolean;
  noteValidationError: string | null;
  saving: boolean;
  hasChanges: boolean;
  onStatusChange: (status: BugReportStatus) => void;
  onNoteChange: (note: string) => void;
  onSave: (event: React.FormEvent) => void;
  replyDraft: string;
  replying: boolean;
  onReplyChange: (reply: string) => void;
  onReply: (event: React.FormEvent) => void;
  onOpenAttachment: (reportId: string, attachment: BugReportAttachmentDTO) => void;
}) {
  const context = report.client_context;
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <SeverityBadge severity={report.severity} />
        <StatusBadge status={report.status} />
        <span className="chip">{bugReportCategoryLabel(report.category)}</span>
        {detailLoading && (
          <span className="inline-flex items-center gap-1.5 text-xs text-fg-muted" role="status">
            <Loader2 size={13} className="animate-spin" aria-hidden="true" />
            Refreshing details…
          </span>
        )}
      </div>

      {error && (
        <div
          className="flex items-start gap-2 rounded-xl border border-accent-bad/45 bg-accent-bad/10 p-3 text-sm text-accent-bad"
          role="alert"
        >
          <AlertCircle size={17} className="mt-0.5 shrink-0" aria-hidden="true" />
          <span className="break-words">{error}</span>
        </div>
      )}

      <section className="rounded-2xl border border-bg-border bg-bg-raised/35 p-4">
        <h4 className="text-sm font-semibold">What happened</h4>
        <DetailText label="Description" value={report.description} />
        <div className="mt-4 grid gap-4 md:grid-cols-3">
          <DetailText label="Steps to reproduce" value={report.reproduction_steps} compact />
          <DetailText label="Expected" value={report.expected_behavior} compact />
          <DetailText label="Actual" value={report.actual_behavior} compact />
        </div>
      </section>

      <div className="grid gap-4 md:grid-cols-2">
        <section className="rounded-2xl border border-bg-border bg-bg-raised/35 p-4">
          <h4 className="flex items-center gap-2 text-sm font-semibold">
            <UserRound size={16} className="text-accent" aria-hidden="true" /> Reporter
          </h4>
          <div className="mt-3 space-y-3">
            <InfoRow label="Staff member" value={report.reporter.name} />
            <InfoRow label="Email" value={report.reporter.email} />
            <InfoRow label="Submitted" value={formatDateTime(report.created_at)} />
            <InfoRow label="Last updated" value={formatDateTime(report.updated_at)} />
          </div>
        </section>

        <section className="rounded-2xl border border-bg-border bg-bg-raised/35 p-4">
          <h4 className="flex items-center gap-2 text-sm font-semibold">
            <MonitorSmartphone size={16} className="text-accent" aria-hidden="true" /> Client context
          </h4>
          <div className="mt-3 space-y-3">
            <InfoRow label="Platform" value={platformLabel(context.platform)} />
            <InfoRow label="App" value={formatAppVersion(context.app_version, context.version_code)} />
            <InfoRow
              label="Device / OS"
              value={[context.device_model, context.os_version].filter(Boolean).join(' · ') || 'Not provided'}
            />
            <InfoRow label="Screen" value={context.current_screen || 'Not provided'} />
            <InfoRow label="Last action" value={context.last_action || 'Not provided'} />
            <InfoRow label="Error code" value={context.error_code || 'Not provided'} />
          </div>
        </section>
      </div>

      <section className="rounded-2xl border border-bg-border bg-bg-raised/35 p-4">
        <h4 className="flex items-center gap-2 text-sm font-semibold">
          <MapPin size={16} className="text-accent" aria-hidden="true" /> Operational context
        </h4>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <InfoRow label="Branch" value={context.branch_name || 'Not provided'} />
          <InfoRow label="Terminal" value={context.terminal_name || 'Not provided'} />
          <InfoRow
            label="Connectivity"
            value={connectivityLabel(context.connectivity)}
            Icon={context.connectivity === 'offline' ? WifiOff : Wifi}
          />
          <InfoRow
            label="Occurred"
            value={context.occurred_at ? formatDateTime(context.occurred_at) : 'Not provided'}
            Icon={Clock3}
          />
        </div>
      </section>

      {report.attachments.length > 0 && (
        <section className="rounded-2xl border border-bg-border bg-bg-raised/35 p-4">
          <h4 className="flex items-center gap-2 text-sm font-semibold">
            <Paperclip size={16} className="text-accent" aria-hidden="true" /> Screenshots
          </h4>
          <div className="mt-3 flex flex-wrap gap-2">
            {report.attachments.map((attachment) => (
              <button
                key={attachment.id}
                type="button"
                className="btn btn-ghost"
                disabled={!attachment.available}
                onClick={() => onOpenAttachment(report.id, attachment)}
              >
                {attachment.available ? `View ${attachment.filename}` : `${attachment.filename} expired`}
              </button>
            ))}
          </div>
        </section>
      )}

      <section className="rounded-2xl border border-bg-border bg-bg-raised/35 p-4">
        <h4 className="flex items-center gap-2 text-sm font-semibold">
          <MessageSquareText size={16} className="text-accent" aria-hidden="true" /> Replies visible to staff
        </h4>
        <p className="mt-1 text-xs text-fg-muted">
          These messages appear in the reporter’s My requests view. Internal notes below remain private.
        </p>
        {report.public_replies.length > 0 && (
          <div className="mt-3 space-y-2">
            {report.public_replies.map((reply) => (
              <div key={reply.id} className="rounded-xl border border-bg-border bg-bg-surface p-3 text-sm">
                <p className="whitespace-pre-wrap break-words">{reply.message}</p>
                <p className="mt-1 text-[11px] text-fg-muted">
                  {reply.author_name} · {formatDateTime(reply.created_at)}
                </p>
              </div>
            ))}
          </div>
        )}
        <form onSubmit={onReply} className="mt-3">
          <label htmlFor="bug-report-public-reply" className="text-xs font-medium text-fg-muted">
            Reply to staff member
          </label>
          <textarea
            id="bug-report-public-reply"
            className="input mt-1 min-h-[88px] resize-y"
            value={replyDraft}
            onChange={(event) => onReplyChange(event.target.value)}
            maxLength={4000}
            placeholder="Explain what was checked, what they should do next, or that the issue is fixed."
            disabled={replying || !detailReady}
          />
          <div className="mt-2 flex items-center justify-between gap-3">
            <p className="text-xs text-fg-muted">Do not include passwords, tokens or private management notes.</p>
            <button
              type="submit"
              className="btn btn-ghost"
              disabled={replying || !detailReady || replyDraft.trim().length < 2}
            >
              {replying ? <><Loader2 size={15} className="animate-spin" /> Sending…</> : <><Send size={15} /> Send reply</>}
            </button>
          </div>
        </form>
      </section>

      {(report.resolved_at || report.resolved_by) && (
        <section className="rounded-2xl border border-accent-good/30 bg-accent-good/5 p-4">
          <h4 className="flex items-center gap-2 text-sm font-semibold text-accent-good">
            <ShieldCheck size={16} aria-hidden="true" /> Resolution history
          </h4>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <InfoRow label="Resolved by" value="System administrator" />
            <InfoRow
              label="Resolved at"
              value={report.resolved_at ? formatDateTime(report.resolved_at) : 'Not provided'}
            />
          </div>
        </section>
      )}

      <form onSubmit={onSave} className="rounded-2xl border border-bg-border bg-bg-surface p-4">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <h4 className="text-sm font-semibold">Internal triage</h4>
            <p className="mt-0.5 text-xs text-fg-muted">
              Internal notes are visible only in the protected admin inbox.
            </p>
          </div>
          <span className="font-mono text-[11px] text-fg-muted">#{report.id.slice(0, 8)}</span>
        </div>
        <div className="mt-4 grid gap-4 md:grid-cols-[190px_1fr]">
          <div>
            <label htmlFor="bug-report-detail-status" className="text-xs font-medium text-fg-muted">
              Status
            </label>
            <select
              id="bug-report-detail-status"
              className="input mt-1"
              value={statusDraft}
              onChange={(event) => onStatusChange(event.target.value as BugReportStatus)}
              disabled={saving || !detailReady}
            >
              {bugReportStatusOptionsFor(report.status).map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </div>
          <div>
            <div className="flex items-center justify-between gap-2">
              <label htmlFor="bug-report-resolution-note" className="text-xs font-medium text-fg-muted">
                Internal resolution note
              </label>
              <span
                className={`font-mono text-[11px] ${noteTooLong ? 'text-accent-bad' : 'text-fg-muted'}`}
              >
                {noteDraft.length}/{MAX_RESOLUTION_NOTE_LENGTH}
              </span>
            </div>
            <textarea
              id="bug-report-resolution-note"
              className="input mt-1 min-h-[120px] resize-y"
              value={noteDraft}
              onChange={(event) => onNoteChange(event.target.value)}
              maxLength={MAX_RESOLUTION_NOTE_LENGTH + 1}
              placeholder="Record what was checked, the fix or reason for closure, and any follow-up."
              aria-invalid={noteTooLong || Boolean(noteValidationError)}
              aria-describedby="bug-report-note-help"
              disabled={saving || !detailReady}
            />
            <p
              id="bug-report-note-help"
              className={`mt-1 text-xs ${noteTooLong || noteValidationError ? 'text-accent-bad' : 'text-fg-muted'}`}
            >
              {noteTooLong
                ? `Shorten the note to ${MAX_RESOLUTION_NOTE_LENGTH.toLocaleString('en-IN')} characters.`
                : noteValidationError
                  || 'Do not paste passwords, tokens, payment credentials, or raw request headers.'}
            </p>
          </div>
        </div>
        <div className="mt-4 flex justify-end">
          <button
            type="submit"
            className="btn btn-primary w-full sm:w-auto"
            disabled={
              !detailReady
              || !hasChanges
              || noteTooLong
              || Boolean(noteValidationError)
              || saving
              || detailLoading
            }
          >
            {saving ? (
              <><Loader2 size={16} className="animate-spin" aria-hidden="true" /> Saving…</>
            ) : (
              'Save update'
            )}
          </button>
        </div>
      </form>
    </div>
  );
}

function DetailText({
  label,
  value,
  compact = false,
}: {
  label: string;
  value: string | null;
  compact?: boolean;
}) {
  return (
    <div className={compact ? '' : 'mt-3'}>
      <p className="text-xs font-medium text-fg-muted">{label}</p>
      <p className="mt-1 whitespace-pre-wrap break-words text-sm leading-relaxed">
        {value || 'Not provided'}
      </p>
    </div>
  );
}

function InfoRow({
  label,
  value,
  Icon,
}: {
  label: string;
  value: string;
  Icon?: typeof Clock3;
}) {
  return (
    <div className="flex items-start justify-between gap-3 text-sm">
      <span className="text-fg-muted">{label}</span>
      <span className="flex min-w-0 items-center gap-1.5 break-words text-right font-medium">
        {Icon && <Icon size={14} className="shrink-0 text-fg-muted" aria-hidden="true" />}
        {value}
      </span>
    </div>
  );
}

function SeverityBadge({ severity }: { severity: BugReportSeverity }) {
  return (
    <span className={`chip shrink-0 text-[10px] ${SEVERITY_TONE[severity]}`}>
      {bugReportSeverityLabel(severity)}
    </span>
  );
}

function StatusBadge({ status }: { status: BugReportStatus }) {
  return (
    <span className={`chip shrink-0 text-[10px] ${STATUS_TONE[status]}`}>
      {bugReportStatusLabel(status)}
    </span>
  );
}

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Date unavailable';
  return date.toLocaleDateString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: date.getFullYear() === new Date().getFullYear() ? undefined : 'numeric',
  });
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Date unavailable';
  return date.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function platformLabel(platform: string): string {
  const normalized = platform.trim().toLowerCase();
  if (normalized === 'android') return 'Android';
  if (normalized === 'web') return 'Web';
  return platform || 'Unknown';
}

function connectivityLabel(connectivity: BugReportDTO['client_context']['connectivity']): string {
  if (connectivity === 'online') return 'Online';
  if (connectivity === 'offline') return 'Offline when reported';
  return 'Unknown';
}

function formatAppVersion(version: string | null, code: number | null): string {
  if (!version && !code) return 'Not provided';
  if (version && code) return `v${version} · build ${code}`;
  if (version) return `v${version}`;
  return `Build ${code}`;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) return error.message;
  return 'Please check the connection and try again.';
}
