import type { ReceiptFiltersDTO } from '@/lib/erp-api';
import { parseRupeesToMinor } from '@/lib/money-input';
export type ReceiptFilterInputs = {
  date_from: string;
  date_to: string;
  station_id: string;
  staff_user_id: string;
  amount: string;
  method: string;
};
export const emptyReceiptFilters: ReceiptFilterInputs = {
  date_from: '',
  date_to: '',
  station_id: '',
  staff_user_id: '',
  amount: '',
  method: '',
};
export function receiptSearchScope(
  query: string,
  inputs: ReceiptFilterInputs,
): string {
  const filters: ReceiptFiltersDTO = {};
  for (const key of [
    'date_from',
    'date_to',
    'station_id',
    'staff_user_id',
  ] as const)
    if (inputs[key]) filters[key] = inputs[key];
  if (inputs.date_from && inputs.date_to && inputs.date_from > inputs.date_to)
    throw new Error('Receipt date range is reversed.');
  if (inputs.amount.trim()) {
    const amount = parseRupeesToMinor(inputs.amount);
    if (amount === null)
      throw new Error(
        'Enter an exact nonnegative invoice amount with at most two decimals.',
      );
    filters.amount_minor = amount;
  }
  if (inputs.method) {
    if (!['cash', 'upi', 'card', 'qr', 'wallet'].includes(inputs.method))
      throw new Error('Unsupported receipt payment method.');
    filters.method = inputs.method as ReceiptFiltersDTO['method'];
  }
  return Object.keys(filters).length
    ? 'receipt-filter:v1:' +
        JSON.stringify({ q: query.trim() || undefined, ...filters })
    : query.trim();
}
export function receiptSearchParams(
  scope: string,
): ReceiptFiltersDTO & { q?: string } {
  if (!scope) return {};
  if (scope.startsWith('receipt-filter:v1:')) {
    try {
      return JSON.parse(
        scope.slice('receipt-filter:v1:'.length),
      ) as ReceiptFiltersDTO & { q?: string };
    } catch {
      return { q: scope };
    }
  }
  return { q: scope };
}
/** Decimal shift, not binary multiplication: 0.1800 becomes 18%, including 0.005 -> 0.5%. */
export function taxPercentage(rate: string): string {
  const match = /^(\d+)(?:\.(\d+))?$/.exec(rate.trim());
  if (!match) return 'Rate unavailable';
  const decimals = match[2] ?? '';
  const digits = match[1] + decimals.padEnd(2, '0');
  const scale = Math.max(0, decimals.length - 2);
  const point = digits.length - scale;
  const value = scale
    ? digits.slice(0, point) + '.' + digits.slice(point)
    : digits;
  const normalized = value
    .replace(/^0+(?=\d)/, '')
    .replace(/(\.\d*?)0+$/, '$1')
    .replace(/\.$/, '');
  return `${normalized}%`;
}
