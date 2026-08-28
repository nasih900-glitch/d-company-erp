import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
} from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Clock3,
  FileImage,
  HelpCircle,
  Loader2,
  MessageSquareText,
  RefreshCw,
  Send,
  X,
} from 'lucide-react';
import { useLocation } from 'react-router-dom';

import Modal from '@/components/ui/Modal';
import { useNotifications } from '@/components/ui/Notifications';
import {
  bugReports,
  type BugReportCategory,
  type BugReportCreateDTO,
  type BugReportMineDTO,
} from '@/lib/erp-api';
import { subscribeRealtime } from '@/lib/realtime';
import {
  StableMutationIntent,
  availableLocalStorage,
} from '@/lib/stable-mutation-intent';
import {
  readLastFailedSupportAction,
  type FailedSupportAction,
} from '@/lib/support-context';
import { useAuth } from '@/modules/auth/AuthContext';
import {
  bugReportCategoryLabel,
  bugReportStatusLabel,
} from '@/modules/bug-reports/bug-report-ui';

const MAX_SCREENSHOT_BYTES = 2 * 1024 * 1024;
const SCREENSHOT_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

type SupportTab = 'report' | 'mine';

interface SupportDraft {
  category: BugReportCategory;
  impact: 'low' | 'medium' | 'high' | 'critical';
  title: string;
  description: string;
}

interface AttachmentIntentPayload {
  reportId: string;
  file: File;
}

const EMPTY_DRAFT: SupportDraft = {
  category: 'other',
  impact: 'medium',
  title: '',
  description: '',
};

export function validateSupportScreenshot(file: File | null): string | null {
  if (!file) return null;
  if (!SCREENSHOT_TYPES.has(file.type)) return 'Choose a PNG, JPEG, or WebP screenshot.';
  if (file.size <= 0) return 'The selected screenshot is empty.';
  if (file.size > MAX_SCREENSHOT_BYTES) return 'Choose a screenshot smaller than 2 MB.';
  return null;
}

export function SupportScreenshotPreview({
  file,
  previewUrl,
  onRemove,
  disabled = false,
}: {
  file: File;
  previewUrl: string;
  onRemove: () => void;
  disabled?: boolean;
}) {
  return (
    <div className="mt-3 rounded-xl border border-bg-border bg-bg-surface p-2">
      <img
        src={previewUrl}
        alt="Screenshot selected for this support request"
        className="max-h-52 w-full rounded-lg object-contain"
      />
      <div className="mt-2 flex items-center justify-between gap-3 text-xs text-fg-muted">
        <span className="truncate">{file.name} · {Math.ceil(file.size / 1024)} KB</span>
        <button type="button" className="btn btn-ghost" onClick={onRemove} disabled={disabled}>
          <X size={14} aria-hidden="true" /> Remove
        </button>
      </div>
    </div>
  );
}

export function PendingScreenshotRetryNotice({
  error,
  uploading,
  invalid,
  onRetry,
  onRemove,
}: {
  error: string | null;
  uploading: boolean;
  invalid: boolean;
  onRetry: () => void;
  onRemove: () => void;
}) {
  return (
    <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3" role="alert">
      <div className="flex items-start gap-2 text-sm">
        <AlertCircle size={18} className="mt-0.5 shrink-0 text-accent-gold" aria-hidden="true" />
        <div>
          <p className="font-medium">The report is sent; its screenshot still needs confirmation.</p>
          <p className="mt-1 text-xs text-fg-muted">
            {error || 'The previous upload did not finish. Retry the same screenshot or remove it.'}
          </p>
        </div>
      </div>
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        <button type="button" className="btn btn-ghost" onClick={onRemove} disabled={uploading}>
          <X size={14} aria-hidden="true" /> Remove screenshot
        </button>
        <button
          type="button"
          className="btn btn-primary"
          onClick={onRetry}
          disabled={uploading || invalid}
        >
          {uploading
            ? <><Loader2 size={15} className="animate-spin" /> Retrying…</>
            : <><RefreshCw size={15} /> Retry screenshot</>}
        </button>
      </div>
    </div>
  );
}

function readDraft(key: string): SupportDraft {
  try {
    const value = JSON.parse(localStorage.getItem(key) || 'null') as Partial<SupportDraft> | null;
    if (!value) return { ...EMPTY_DRAFT };
    return {
      category: value.category ?? EMPTY_DRAFT.category,
      impact: value.impact ?? EMPTY_DRAFT.impact,
      title: typeof value.title === 'string' ? value.title : '',
      description: typeof value.description === 'string' ? value.description : '',
    };
  } catch {
    return { ...EMPTY_DRAFT };
  }
}

function reportFingerprint(draft: SupportDraft): string {
  return JSON.stringify({
    category: draft.category,
    impact: draft.impact,
    title: draft.title.trim(),
    description: draft.description.trim(),
  });
}

function isBugReportCreatePayload(value: unknown): value is BugReportCreateDTO {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<BugReportCreateDTO>;
  return typeof candidate.category === 'string'
    && typeof candidate.severity === 'string'
    && typeof candidate.title === 'string'
    && typeof candidate.description === 'string'
    && typeof candidate.client_context === 'object'
    && candidate.client_context !== null
    && candidate.client_context.platform === 'web';
}

export function supportCreateIntentStorageKey({
  companyId,
  branchId,
  userId,
}: {
  companyId?: string | null;
  branchId?: string | null;
  userId?: string | null;
}): string {
  return [
    'dcompany_support_create_intent:v2',
    companyId ?? 'unknown-company',
    branchId ?? 'unassigned-branch',
    userId ?? 'anonymous-user',
  ].join(':');
}

export default function SupportLauncher({ inboxUnread = 0 }: { inboxUnread?: number }) {
  const { me, terminalId, terminalOptions } = useAuth();
  const location = useLocation();
  const notifications = useNotifications();
  const draftKey = `dcompany_support_draft:${me?.user_id ?? 'anonymous'}`;
  const createIntentKey = supportCreateIntentStorageKey({
    companyId: me?.company_id,
    branchId: me?.branch_id,
    userId: me?.user_id,
  });
  const createIntent = useMemo(() => new StableMutationIntent<BugReportCreateDTO>({
    prefix: 'bug-report:web',
    storage: availableLocalStorage(),
    storageKey: createIntentKey,
    isPayload: isBugReportCreatePayload,
  }), [createIntentKey]);
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<SupportTab>('report');
  const [draft, setDraft] = useState<SupportDraft>(() => readDraft(draftKey));
  const [screenshot, setScreenshot] = useState<File | null>(null);
  const [screenshotPreviewUrl, setScreenshotPreviewUrl] = useState<string | null>(null);
  const [failureContext, setFailureContext] = useState<FailedSupportAction | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submittedId, setSubmittedId] = useState<string | null>(null);
  const [pendingAttachmentReportId, setPendingAttachmentReportId] = useState<string | null>(null);
  const [attachmentUploading, setAttachmentUploading] = useState(false);
  const [attachmentError, setAttachmentError] = useState<string | null>(null);
  const [mine, setMine] = useState<BugReportMineDTO[]>([]);
  const [mineLoading, setMineLoading] = useState(false);
  const [mineError, setMineError] = useState<string | null>(null);
  const screenshotInputRef = useRef<HTMLInputElement>(null);
  const submitLockRef = useRef(false);
  const attachmentUploadLockRef = useRef(false);
  const attachmentIntentRef = useRef<StableMutationIntent<AttachmentIntentPayload> | null>(null);
  if (attachmentIntentRef.current === null) {
    attachmentIntentRef.current = new StableMutationIntent<AttachmentIntentPayload>({
      prefix: 'bug-report-attachment:web',
    });
  }
  const selectedTerminal = terminalOptions.find((item) => item.id === terminalId) ?? null;

  const clearScreenshot = useCallback((event?: ReactMouseEvent<HTMLButtonElement>) => {
    event?.preventDefault();
    event?.stopPropagation();
    if (submitLockRef.current || attachmentUploadLockRef.current) return;
    attachmentIntentRef.current!.invalidate();
    setScreenshot(null);
    setPendingAttachmentReportId(null);
    setAttachmentError(null);
    if (screenshotInputRef.current) screenshotInputRef.current.value = '';
  }, []);

  const updateDraft = useCallback((update: Partial<SupportDraft>) => {
    if (submitLockRef.current) return;
    // Once a send has been attempted its exact body belongs to that operation.
    // Editing is an explicit new intent, so the next send receives a new key.
    createIntent.invalidate();
    setSubmitError(null);
    setSubmittedId(null);
    setDraft((value) => ({ ...value, ...update }));
  }, [createIntent]);

  const selectScreenshot = useCallback((file: File | null) => {
    if (submitLockRef.current || attachmentUploadLockRef.current) return;
    // A replacement is a different upload intent even when the filename and
    // byte size happen to match the previous selection.
    attachmentIntentRef.current!.invalidate();
    setAttachmentError(null);
    setScreenshot(file);
  }, []);

  useEffect(() => {
    setDraft(readDraft(draftKey));
  }, [draftKey]);

  useEffect(() => {
    // Never carry a private screenshot or its operation identity into another
    // signed-in account on a shared browser/tablet.
    attachmentIntentRef.current!.invalidate();
    setScreenshot(null);
    setPendingAttachmentReportId(null);
    setAttachmentError(null);
    if (screenshotInputRef.current) screenshotInputRef.current.value = '';
  }, [createIntentKey]);

  useEffect(() => {
    try { localStorage.setItem(draftKey, JSON.stringify(draft)); } catch { /* optional draft */ }
  }, [draft, draftKey]);

  useEffect(() => {
    if (!screenshot) {
      setScreenshotPreviewUrl(null);
      return undefined;
    }
    const url = URL.createObjectURL(screenshot);
    setScreenshotPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [screenshot]);

  const loadMine = useCallback(async () => {
    if (!me) return;
    setMineLoading(true);
    setMineError(null);
    try {
      const result = await bugReports.mine({ limit: 20, offset: 0 });
      setMine(result.items);
    } catch (error) {
      setMineError(errorMessage(error));
    } finally {
      setMineLoading(false);
    }
  }, [me]);

  useEffect(() => subscribeRealtime('bug_reports', () => {
    if (open && tab === 'mine') void loadMine();
  }), [loadMine, open, tab]);

  useEffect(() => {
    if (open && tab === 'mine') void loadMine();
  }, [loadMine, open, tab]);

  const screenshotError = validateSupportScreenshot(screenshot);
  const titleError = draft.title.trim().length < 5
    ? 'Use at least 5 characters so the issue is recognizable.'
    : null;
  const descriptionError = draft.description.trim().length < 10
    ? 'Describe what happened in at least 10 characters.'
    : null;
  const canSubmit = !titleError
    && !descriptionError
    && !screenshotError
    && !submitting
    && !attachmentUploading
    && !pendingAttachmentReportId;

  const uploadScreenshot = useCallback(async (reportId: string, file: File) => {
    const fingerprint = [
      reportId,
      file.name,
      file.type,
      String(file.size),
      String(file.lastModified),
    ].join('\u0000');
    const intent = attachmentIntentRef.current!.resolve(fingerprint, () => ({ reportId, file }));
    await bugReports.attachMine(
      intent.payload.reportId,
      intent.payload.file,
      intent.idempotencyKey,
    );
    attachmentIntentRef.current!.confirmSuccess(intent);
  }, []);

  const retryScreenshot = useCallback(async () => {
    if (
      !pendingAttachmentReportId
      || !screenshot
      || validateSupportScreenshot(screenshot)
      || attachmentUploadLockRef.current
    ) return;
    attachmentUploadLockRef.current = true;
    setAttachmentUploading(true);
    setAttachmentError(null);
    try {
      await uploadScreenshot(pendingAttachmentReportId, screenshot);
      setPendingAttachmentReportId(null);
      setScreenshot(null);
      if (screenshotInputRef.current) screenshotInputRef.current.value = '';
      notifications.success('The screenshot is now attached to the existing support request.', {
        title: 'Screenshot sent',
      });
      await loadMine();
    } catch (error) {
      setAttachmentError(errorMessage(error));
    } finally {
      attachmentUploadLockRef.current = false;
      setAttachmentUploading(false);
    }
  }, [loadMine, notifications, pendingAttachmentReportId, screenshot, uploadScreenshot]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSubmit || !me || submitLockRef.current) return;
    submitLockRef.current = true;
    setSubmitting(true);
    setSubmitError(null);
    setSubmittedId(null);
    try {
      const createOperation = createIntent.resolve(reportFingerprint(draft), () => ({
        category: draft.category,
        severity: draft.impact,
        title: draft.title.trim(),
        description: draft.description.trim(),
        client_context: {
          platform: 'web',
          app_version: import.meta.env.VITE_APP_VERSION || null,
          current_screen: location.pathname,
          last_action: failureContext?.lastAction ?? `Opened Help from ${location.pathname}`,
          error_code: failureContext?.errorCode ?? null,
          branch_id: me.branch_id,
          terminal_id: terminalId,
          terminal_name: selectedTerminal?.name ?? null,
          connectivity: navigator.onLine ? 'online' : 'offline',
          occurred_at: new Date().toISOString(),
        },
      }));
      const report = await bugReports.submit(
        createOperation.payload,
        createOperation.idempotencyKey,
      );
      createIntent.confirmSuccess();

      let screenshotWarning: string | null = null;
      if (screenshot) {
        // This is the first upload for a newly confirmed report. Any older
        // attachment identity is deliberately discarded before binding the
        // selected file to this report.
        attachmentIntentRef.current!.invalidate();
        setPendingAttachmentReportId(report.id);
        try {
          await uploadScreenshot(report.id, screenshot);
          setPendingAttachmentReportId(null);
          setScreenshot(null);
          if (screenshotInputRef.current) screenshotInputRef.current.value = '';
        } catch (error) {
          screenshotWarning = errorMessage(error);
          setAttachmentError(screenshotWarning);
        }
      }

      setSubmittedId(report.id);
      setDraft({ ...EMPTY_DRAFT });
      try { localStorage.removeItem(draftKey); } catch { /* optional draft */ }
      await loadMine();
      if (screenshotWarning) {
        notifications.warning(
          `The report was sent. The screenshot upload was not confirmed; keep this window open and retry it. ${screenshotWarning}`,
          { title: 'Screenshot needs retry' },
        );
      } else {
        notifications.success('Your report is now in the protected owner inbox.', {
          title: 'Support request sent',
        });
      }
    } catch (error) {
      setSubmitError(
        navigator.onLine
          ? errorMessage(error)
          : 'You are offline. Your draft is saved on this device; reconnect and tap Send again.',
      );
    } finally {
      submitLockRef.current = false;
      setSubmitting(false);
    }
  };

  const myReplyCount = useMemo(
    () => mine.reduce((total, report) => total + report.public_replies.length, 0),
    [mine],
  );

  return (
    <>
      <button
        type="button"
        className="fixed bottom-4 right-4 z-30 inline-flex min-h-12 items-center gap-2 rounded-2xl border border-bg-border bg-bg-surface/95 px-4 py-3 text-sm font-semibold text-fg shadow-2xl backdrop-blur transition hover:border-accent/40 hover:bg-bg-raised active:scale-[0.98]"
        style={{ marginBottom: 'env(safe-area-inset-bottom)' }}
        onClick={() => {
          setFailureContext(readLastFailedSupportAction());
          setOpen(true);
        }}
        aria-label="Open Help and support"
      >
        <HelpCircle size={19} className="text-accent" aria-hidden="true" />
        Help
        {inboxUnread > 0 && (
          <span className="grid min-w-5 place-items-center rounded-full bg-accent-bad px-1.5 py-0.5 text-[10px] text-white">
            {Math.min(inboxUnread, 99)}
          </span>
        )}
      </button>

      <Modal
        open={open}
        onClose={() => !submitting && !attachmentUploading && setOpen(false)}
        title="Help and support"
        size="lg"
      >
        <div className="mb-4 grid grid-cols-2 gap-2 rounded-2xl border border-bg-border bg-bg-raised/40 p-1">
          <button
            type="button"
            className={`btn ${tab === 'report' ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setTab('report')}
          >
            <MessageSquareText size={16} aria-hidden="true" /> Report a problem
          </button>
          <button
            type="button"
            className={`btn ${tab === 'mine' ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setTab('mine')}
          >
            <Clock3 size={16} aria-hidden="true" /> My requests
          </button>
        </div>

        {tab === 'report' ? (
          <form onSubmit={submit} className="space-y-4">
            <div className="rounded-xl border border-bg-border bg-bg-raised/35 px-3 py-2 text-xs text-fg-muted">
              Current screen: <span className="font-medium text-fg">{location.pathname}</span>
              {failureContext && (
                <span className="mt-1 block">
                  Last failed action: <span className="font-medium text-fg">{failureContext.lastAction}</span>
                  {' · '}{failureContext.errorCode}
                </span>
              )}
            </div>
            {submittedId && (
              <div className="flex items-start gap-2 rounded-xl border border-accent-good/35 bg-accent-good/10 p-3 text-sm" role="status">
                <CheckCircle2 size={18} className="mt-0.5 shrink-0 text-accent-good" aria-hidden="true" />
                <span>Sent successfully. Reference #{submittedId.slice(0, 8)}. You can follow its status in My requests.</span>
              </div>
            )}
            {submitError && (
              <div className="flex items-start gap-2 rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad" role="alert">
                <AlertCircle size={18} className="mt-0.5 shrink-0" aria-hidden="true" />
                <span>{submitError}</span>
              </div>
            )}
            {pendingAttachmentReportId && screenshot && (
              <PendingScreenshotRetryNotice
                error={attachmentError}
                uploading={attachmentUploading}
                invalid={Boolean(screenshotError)}
                onRetry={() => void retryScreenshot()}
                onRemove={clearScreenshot}
              />
            )}
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="block text-xs font-medium text-fg-muted">
                What do you need help with?
                <select
                  className="input mt-1"
                  value={draft.category}
                  disabled={submitting}
                  onChange={(event) => updateDraft({
                    category: event.target.value as BugReportCategory,
                  })}
                >
                  <option value="other">Something failed</option>
                  <option value="usability">I’m stuck or unsure what to do</option>
                  <option value="incorrect_data">A total or information looks wrong</option>
                  <option value="payment">Payment or billing problem</option>
                  <option value="sync">Offline or sync problem</option>
                  <option value="performance">The app is slow or frozen</option>
                  <option value="permission">An action says I don’t have access</option>
                  <option value="crash">The app crashed</option>
                </select>
              </label>
              <label className="block text-xs font-medium text-fg-muted">
                Can you continue working?
                <select
                  className="input mt-1"
                  value={draft.impact}
                  disabled={submitting}
                  onChange={(event) => updateDraft({
                    impact: event.target.value as SupportDraft['impact'],
                  })}
                >
                  <option value="low">Yes — minor issue</option>
                  <option value="medium">Yes, but it is difficult</option>
                  <option value="high">No — work is blocked</option>
                  <option value="critical">Urgent — payment or data may be at risk</option>
                </select>
              </label>
            </div>
            <label className="block text-xs font-medium text-fg-muted">
              Short title
              <input
                className="input mt-1"
                value={draft.title}
                maxLength={160}
                disabled={submitting}
                onChange={(event) => updateDraft({ title: event.target.value })}
                placeholder="Example: Cash payment button is stuck"
                autoComplete="off"
              />
              {draft.title.length > 0 && titleError && <span className="mt-1 block text-accent-bad">{titleError}</span>}
            </label>
            <label className="block text-xs font-medium text-fg-muted">
              What happened?
              <textarea
                className="input mt-1 min-h-[120px] resize-y"
                value={draft.description}
                maxLength={4000}
                disabled={submitting}
                onChange={(event) => updateDraft({ description: event.target.value })}
                placeholder="Say what you tapped, what appeared, and what you expected. Do not include passwords or payment details."
              />
              {draft.description.length > 0 && descriptionError && <span className="mt-1 block text-accent-bad">{descriptionError}</span>}
            </label>
            <label className="block rounded-2xl border border-dashed border-bg-border bg-bg-raised/25 p-4 text-sm">
              <span className="flex items-center gap-2 font-medium"><FileImage size={17} aria-hidden="true" /> Screenshot (optional)</span>
              <span className="mt-1 block text-xs text-fg-muted">Only attach a screen you reviewed. Hide passwords, customer details and payment information first. PNG, JPEG or WebP; maximum 2 MB.</span>
              <input
                ref={screenshotInputRef}
                className="mt-3 block w-full text-xs text-fg-muted file:mr-3 file:rounded-lg file:border-0 file:bg-bg-raised file:px-3 file:py-2 file:text-fg"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                disabled={submitting || attachmentUploading}
                onChange={(event) => selectScreenshot(event.target.files?.[0] ?? null)}
              />
              {screenshotError && (
                <span className="mt-2 flex items-center justify-between gap-3 text-xs text-accent-bad">
                  {screenshotError}
                  <button
                    type="button"
                    className="btn btn-ghost"
                    onClick={clearScreenshot}
                    disabled={submitting || attachmentUploading}
                  >
                    <X size={14} aria-hidden="true" /> Remove
                  </button>
                </span>
              )}
              {screenshot && screenshotPreviewUrl && !screenshotError && (
                <SupportScreenshotPreview
                  file={screenshot}
                  previewUrl={screenshotPreviewUrl}
                  onRemove={clearScreenshot}
                  disabled={submitting || attachmentUploading}
                />
              )}
            </label>
            <div className="flex flex-wrap items-center justify-between gap-3 border-t border-bg-border pt-4">
              <p className="max-w-md text-xs text-fg-muted">
                {pendingAttachmentReportId
                  ? 'Finish or remove the pending screenshot before starting another report.'
                  : 'Your draft stays on this device until the server confirms receipt.'}
              </p>
              <button type="submit" className="btn btn-primary min-w-36" disabled={!canSubmit}>
                {submitting ? <><Loader2 size={16} className="animate-spin" /> Sending…</> : <><Send size={16} /> Send to owner</>}
              </button>
            </div>
          </form>
        ) : (
          <section aria-label="My support requests">
            <div className="mb-3 flex items-center justify-between gap-3">
              <p className="text-xs text-fg-muted">{mine.length} recent request{mine.length === 1 ? '' : 's'} · {myReplyCount} owner repl{myReplyCount === 1 ? 'y' : 'ies'}</p>
              <button type="button" className="btn btn-ghost" onClick={() => void loadMine()} disabled={mineLoading}>
                <RefreshCw size={15} className={mineLoading ? 'animate-spin' : ''} /> Refresh
              </button>
            </div>
            {mineError && <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad" role="alert">{mineError}</div>}
            {mineLoading && mine.length === 0 ? (
              <div className="flex min-h-40 items-center justify-center gap-2 text-sm text-fg-muted"><Loader2 size={17} className="animate-spin" /> Loading requests…</div>
            ) : mine.length === 0 ? (
              <div className="flex min-h-44 flex-col items-center justify-center rounded-2xl border border-bg-border bg-bg-raised/25 p-6 text-center">
                <HelpCircle size={26} className="text-fg-muted" />
                <p className="mt-3 font-medium">No support requests yet</p>
                <p className="mt-1 text-sm text-fg-muted">Reports you send will appear here with owner replies.</p>
              </div>
            ) : (
              <div className="max-h-[55dvh] space-y-3 overflow-y-auto pr-1">
                {mine.map((report) => <MyRequestCard key={report.id} report={report} />)}
              </div>
            )}
          </section>
        )}
      </Modal>
    </>
  );
}

function MyRequestCard({ report }: { report: BugReportMineDTO }) {
  return (
    <article className="rounded-2xl border border-bg-border bg-bg-raised/30 p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-semibold">{report.title}</p>
          <p className="mt-1 text-xs text-fg-muted">{bugReportCategoryLabel(report.category)} · #{report.id.slice(0, 8)}</p>
        </div>
        <span className="chip text-[10px]">{bugReportStatusLabel(report.status)}</span>
      </div>
      <p className="mt-3 whitespace-pre-wrap text-sm text-fg-muted">{report.description}</p>
      {report.public_replies.length > 0 && (
        <div className="mt-3 space-y-2 border-t border-bg-border pt-3">
          <p className="flex items-center gap-1.5 text-xs font-semibold"><MessageSquareText size={14} className="text-accent" /> Owner response</p>
          {report.public_replies.map((reply) => (
            <div key={reply.id} className="rounded-xl bg-bg-surface p-3 text-sm">
              <p className="whitespace-pre-wrap">{reply.message}</p>
              <p className="mt-1 text-[11px] text-fg-muted">{reply.author_name} · {formatDateTime(reply.created_at)}</p>
            </div>
          ))}
        </div>
      )}
    </article>
  );
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Date unavailable';
  return date.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) return error.message;
  return 'Please check the connection and try again.';
}
