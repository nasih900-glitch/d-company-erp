import { DEFAULT_BUSINESS_TIMEZONE } from '@/lib/manual-collections';
import type { ShiftDTO } from '@/lib/erp-api';

export interface ShiftBusinessDaySummary {
  businessDate: string;
  dateLabel: string;
  shifts: ShiftDTO[];
  firstOpenedAt: string;
  finalClosedAt: string | null;
  firstShift: ShiftDTO;
  finalShift: ShiftDTO;
  hasOpenShift: boolean;
  hasIncompleteClose: boolean;
  posCollectionsMinor: number;
  membershipCollectionsMinor: number;
  grossCollectionsMinor: number;
  totalRefundsMinor: number;
  netCollectionsMinor: number;
}

function dateParts(value: string, timeZone: string): Record<string, string> {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw new Error(`Invalid shift timestamp: ${value}`);
  return Object.fromEntries(
    new Intl.DateTimeFormat('en-GB', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(date).map((part) => [part.type, part.value]),
  );
}

export function shiftBusinessDate(
  openedAt: string,
  timeZone: string = DEFAULT_BUSINESS_TIMEZONE,
): string {
  const parts = dateParts(openedAt, timeZone);
  return `${parts.year}-${parts.month}-${parts.day}`;
}

export function formatShiftBusinessDate(
  openedAt: string,
  timeZone: string = DEFAULT_BUSINESS_TIMEZONE,
): string {
  return new Intl.DateTimeFormat('en-IN', {
    timeZone,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(new Date(openedAt));
}

export function formatShiftBusinessTime(
  value: string,
  timeZone: string = DEFAULT_BUSINESS_TIMEZONE,
): string {
  const rendered = new Intl.DateTimeFormat('en-IN', {
    timeZone,
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  }).format(new Date(value));
  return `${rendered} IST`;
}

export function formatShiftBusinessDateTime(
  value: string,
  timeZone: string = DEFAULT_BUSINESS_TIMEZONE,
): string {
  const rendered = new Intl.DateTimeFormat('en-IN', {
    timeZone,
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  }).format(new Date(value));
  return `${rendered} IST`;
}

/**
 * Present immutable drawer lifecycles as one commercial business day.
 *
 * A closed drawer must never be rewritten or merged in the database. If staff
 * close and reopen during one day, this read model combines collections once
 * while retaining every source shift for audit and drawer reconciliation.
 */
export function groupShiftsByBusinessDay(
  shifts: readonly ShiftDTO[],
  timeZone: string = DEFAULT_BUSINESS_TIMEZONE,
): ShiftBusinessDaySummary[] {
  const groups = new Map<string, ShiftDTO[]>();
  for (const shift of shifts) {
    const key = shiftBusinessDate(shift.opened_at, timeZone);
    const group = groups.get(key);
    if (group) group.push(shift);
    else groups.set(key, [shift]);
  }

  return [...groups.entries()]
    .map(([businessDate, source]) => {
      const rows = [...source].sort(
        (left, right) => new Date(left.opened_at).getTime() - new Date(right.opened_at).getTime(),
      );
      const firstShift = rows[0];
      const closedRows = rows.filter((shift) => shift.closed_at !== null);
      const hasOpenShift = rows.some((shift) => shift.status === 'open');
      // A non-open legacy/damaged row can still be missing its close time
      // because ShiftRead intentionally exposes closed_at as nullable. Keep it
      // distinct from a real open shift: it needs review, but must not expose
      // ordinary close actions or imply that the drawer is currently open.
      const hasIncompleteClose = rows.some(
        (shift) => shift.status !== 'open' && shift.closed_at === null,
      );
      const finalClosedShift = hasOpenShift || closedRows.length !== rows.length
        ? null
        : closedRows.reduce((latest, shift) => (
          new Date(shift.closed_at as string).getTime()
            > new Date(latest.closed_at as string).getTime()
            ? shift
            : latest
        ));
      const finalClosedAt = finalClosedShift?.closed_at ?? null;

      return {
        businessDate,
        dateLabel: formatShiftBusinessDate(firstShift.opened_at, timeZone),
        shifts: rows,
        firstOpenedAt: firstShift.opened_at,
        finalClosedAt,
        firstShift,
        finalShift: finalClosedShift ?? rows[rows.length - 1],
        hasOpenShift,
        hasIncompleteClose,
        posCollectionsMinor: rows.reduce((sum, shift) => sum + (shift.pos_sales_minor ?? 0), 0),
        membershipCollectionsMinor: rows.reduce(
          (sum, shift) => sum + (shift.membership_sales_minor ?? 0),
          0,
        ),
        grossCollectionsMinor: rows.reduce(
          (sum, shift) => sum + (shift.gross_collections_minor ?? 0),
          0,
        ),
        totalRefundsMinor: rows.reduce(
          (sum, shift) => sum + (shift.total_refunds_minor ?? 0),
          0,
        ),
        netCollectionsMinor: rows.reduce(
          (sum, shift) => sum + (shift.net_collections_minor ?? 0),
          0,
        ),
      };
    })
    .sort((left, right) => right.businessDate.localeCompare(left.businessDate));
}
