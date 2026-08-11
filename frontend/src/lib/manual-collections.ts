import type { ManualCollectionDTO, ManualCollectionMethod } from './erp-api';

export const MANUAL_COLLECTION_METHODS: ReadonlyArray<{
  value: ManualCollectionMethod;
  label: string;
}> = [
  { value: 'cash', label: 'Cash' },
  { value: 'upi', label: 'UPI' },
  { value: 'card', label: 'Card' },
  { value: 'bank', label: 'Bank transfer' },
];

export const DEFAULT_BUSINESS_TIMEZONE = 'Asia/Kolkata';

/**
 * Intl.DateTimeFormat throws a RangeError on a timezone it cannot resolve,
 * which unmounts the React tree and leaves the till on a white screen.
 * Constructing a formatter is the check that matches what actually happens at
 * render time: Intl.supportedValuesOf is missing on the older Android WebViews
 * these tablets ship with, and it lists canonical names only — it would reject
 * legacy aliases such as "IST" that ICU resolves and prints perfectly well.
 */
export function isValidTimeZone(timeZone: string | null | undefined): boolean {
  const candidate = timeZone?.trim();
  if (!candidate) return false;
  try {
    new Intl.DateTimeFormat('en-CA', { timeZone: candidate });
    return true;
  } catch {
    return false;
  }
}

export function dateISOInTimeZone(date: Date, timeZone: string): string {
  // A bad value already saved in Settings must degrade to the house timezone
  // rather than blank the screen.
  const zone = isValidTimeZone(timeZone) ? timeZone.trim() : DEFAULT_BUSINESS_TIMEZONE;
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date);
  const values = new Map(parts.map((part) => [part.type, part.value]));
  const year = values.get('year');
  const month = values.get('month');
  const day = values.get('day');
  if (!year || !month || !day) {
    throw new Error(`Unable to resolve business date in timezone ${zone}`);
  }
  return `${year}-${month}-${day}`;
}

export function rupeesToMinor(value: string): number | null {
  const trimmed = value.trim();
  if (!/^\d+(?:\.\d{1,2})?$/.test(trimmed)) return null;

  const [whole, fractional = ''] = trimmed.split('.');
  const minor = Number(whole) * 100 + Number(fractional.padEnd(2, '0'));
  return Number.isSafeInteger(minor) && minor > 0 ? minor : null;
}

export function manualCollectionMethodLabel(method: ManualCollectionMethod): string {
  return MANUAL_COLLECTION_METHODS.find((candidate) => candidate.value === method)?.label ?? method;
}

export function defaultManualCollectionReference(
  businessDate: string,
  method: ManualCollectionMethod,
): string {
  return `Daily collection ${businessDate} ${manualCollectionMethodLabel(method)}`;
}

export interface ManualCollectionTotals {
  cash_minor: number;
  upi_minor: number;
  card_minor: number;
  bank_minor: number;
  total_minor: number;
  active_count: number;
  voided_count: number;
}

export function manualCollectionTotals(rows: ManualCollectionDTO[]): ManualCollectionTotals {
  const totals: ManualCollectionTotals = {
    cash_minor: 0,
    upi_minor: 0,
    card_minor: 0,
    bank_minor: 0,
    total_minor: 0,
    active_count: 0,
    voided_count: 0,
  };

  for (const row of rows) {
    if (row.is_voided) {
      totals.voided_count += 1;
      continue;
    }
    totals[`${row.method}_minor`] += row.amount_minor;
    totals.total_minor += row.amount_minor;
    totals.active_count += 1;
  }
  return totals;
}
