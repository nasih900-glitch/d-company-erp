import type {
  BugReportCategory,
  BugReportListParams,
  BugReportSeverity,
  BugReportStatus,
} from '@/lib/erp-api';

export interface BugReportFilters {
  q: string;
  status: '' | BugReportStatus;
  severity: '' | BugReportSeverity;
  category: '' | BugReportCategory;
  platform: string;
}

export const EMPTY_BUG_REPORT_FILTERS: BugReportFilters = {
  q: '',
  status: '',
  severity: '',
  category: '',
  platform: '',
};

export interface BugReportSaveRequestToken {
  session: number;
  request: number;
}

/**
 * Distinguishes one modal lifetime from another, even when both show the same
 * report ID. A report ID alone cannot reject a late response after close and
 * reopen, and React state is not a synchronous lock against double submit.
 */
export class BugReportDetailRequestGate {
  private session = 0;
  private nextSaveRequest = 0;
  private activeSaveRequest: number | null = null;

  openSession(): number {
    this.session += 1;
    this.activeSaveRequest = null;
    return this.session;
  }

  closeSession(): void {
    this.session += 1;
    this.activeSaveRequest = null;
  }

  isCurrentSession(session: number): boolean {
    return this.session === session;
  }

  startSave(session: number): BugReportSaveRequestToken | null {
    if (!this.isCurrentSession(session) || this.activeSaveRequest !== null) return null;
    const request = ++this.nextSaveRequest;
    this.activeSaveRequest = request;
    return { session, request };
  }

  isCurrentSave(token: BugReportSaveRequestToken): boolean {
    return this.isCurrentSession(token.session) && this.activeSaveRequest === token.request;
  }

  finishSave(token: BugReportSaveRequestToken): boolean {
    if (!this.isCurrentSave(token)) return false;
    this.activeSaveRequest = null;
    return true;
  }
}

export const BUG_REPORT_STATUS_OPTIONS: ReadonlyArray<{
  value: BugReportStatus;
  label: string;
}> = [
  { value: 'open', label: 'Open' },
  { value: 'acknowledged', label: 'Acknowledged' },
  { value: 'in_progress', label: 'In progress' },
  { value: 'resolved', label: 'Resolved' },
  { value: 'closed', label: 'Closed' },
  { value: 'rejected', label: 'Rejected' },
];

const BUG_REPORT_STATUS_TRANSITIONS: Record<BugReportStatus, readonly BugReportStatus[]> = {
  open: ['acknowledged', 'in_progress', 'resolved', 'rejected'],
  acknowledged: ['open', 'in_progress', 'resolved', 'rejected'],
  in_progress: ['open', 'acknowledged', 'resolved', 'rejected'],
  resolved: ['in_progress', 'closed'],
  closed: ['in_progress'],
  rejected: ['open', 'in_progress'],
};

const TERMINAL_BUG_REPORT_STATUSES = new Set<BugReportStatus>([
  'resolved',
  'closed',
  'rejected',
]);

export const BUG_REPORT_SEVERITY_OPTIONS: ReadonlyArray<{
  value: BugReportSeverity;
  label: string;
}> = [
  { value: 'critical', label: 'Critical' },
  { value: 'high', label: 'High' },
  { value: 'medium', label: 'Medium' },
  { value: 'low', label: 'Low' },
];

export const BUG_REPORT_CATEGORY_OPTIONS: ReadonlyArray<{
  value: BugReportCategory;
  label: string;
}> = [
  { value: 'crash', label: 'Crash' },
  { value: 'incorrect_data', label: 'Incorrect data' },
  { value: 'payment', label: 'Payment' },
  { value: 'sync', label: 'Sync' },
  { value: 'permission', label: 'Permission' },
  { value: 'performance', label: 'Performance' },
  { value: 'usability', label: 'Usability' },
  { value: 'other', label: 'Other' },
];

export function buildBugReportListParams(
  filters: BugReportFilters,
  offset: number,
  limit: number,
): BugReportListParams {
  return {
    q: filters.q.trim() || undefined,
    status: filters.status || undefined,
    severity: filters.severity || undefined,
    category: filters.category || undefined,
    platform: filters.platform || undefined,
    offset,
    limit,
  };
}

export function bugReportStatusLabel(status: BugReportStatus): string {
  return BUG_REPORT_STATUS_OPTIONS.find((option) => option.value === status)?.label ?? status;
}

export function bugReportSeverityLabel(severity: BugReportSeverity): string {
  return BUG_REPORT_SEVERITY_OPTIONS.find((option) => option.value === severity)?.label ?? severity;
}

export function bugReportCategoryLabel(category: BugReportCategory): string {
  return BUG_REPORT_CATEGORY_OPTIONS.find((option) => option.value === category)?.label ?? category;
}

export function bugReportStatusOptionsFor(
  currentStatus: BugReportStatus,
): ReadonlyArray<{ value: BugReportStatus; label: string }> {
  const allowed = new Set<BugReportStatus>([
    currentStatus,
    ...BUG_REPORT_STATUS_TRANSITIONS[currentStatus],
  ]);
  return BUG_REPORT_STATUS_OPTIONS.filter((option) => allowed.has(option.value));
}

export function resolutionNoteError(status: BugReportStatus, note: string): string | null {
  if (TERMINAL_BUG_REPORT_STATUSES.has(status) && note.trim().length < 3) {
    return 'Add an internal note of at least 3 characters before resolving, closing, or rejecting.';
  }
  return null;
}
