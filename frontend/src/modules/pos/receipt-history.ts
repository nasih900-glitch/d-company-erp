import type {
  PosPaymentMethodDTO,
  ReceiptGamingSessionHistoryDTO,
  ReceiptLineHistoryDTO,
  ReceiptPaymentHistoryDTO,
  ReceiptHistoryDTO,
  ReceiptHistoryPageDTO,
} from '@/lib/erp-api';

export interface ReceiptQueryScope {
  epoch: number;
  query: string;
  loaded: boolean;
}

export type ReceiptRequestScope = Pick<ReceiptQueryScope, 'epoch' | 'query'>;

export function receiptScopeMatches(
  current: ReceiptQueryScope,
  request: ReceiptRequestScope,
): boolean {
  return current.epoch === request.epoch && current.query === request.query;
}

const RECEIPT_REFRESH_PAGE_LIMIT = 20;

export interface ReceiptRefreshWindow {
  knownUpdates: ReceiptHistoryDTO[];
  unseenPrefix: ReceiptHistoryDTO[];
  complete: boolean;
  missingKnownIds: string[];
}

/**
 * Refresh mutable status for the retained receipt window with bounded page reads.
 * New receipts can push an already-loaded row past the first page, so stopping at
 * page one would leave older refund status stale forever.
 */
export async function collectReceiptRefreshWindow(
  firstPage: ReceiptHistoryPageDTO,
  current: readonly ReceiptHistoryDTO[],
  loadPage: (cursor: string) => Promise<ReceiptHistoryPageDTO>,
  requestIsCurrent: () => boolean = () => true,
): Promise<ReceiptRefreshWindow | null> {
  const remainingKnownIds = new Set(current.map((receipt) => receipt.order_id));
  const knownUpdates: ReceiptHistoryDTO[] = [];
  const unseenPrefix: ReceiptHistoryDTO[] = [];
  const collectedKnownIds = new Set<string>();
  const collectedPrefixIds = new Set<string>();
  let reachedRetainedWindow = false;
  const collect = (page: ReceiptHistoryPageDTO) => {
    for (const receipt of page.items) {
      if (remainingKnownIds.has(receipt.order_id)) {
        reachedRetainedWindow = true;
        remainingKnownIds.delete(receipt.order_id);
        if (!collectedKnownIds.has(receipt.order_id)) {
          collectedKnownIds.add(receipt.order_id);
          knownUpdates.push(receipt);
        }
      } else if (!reachedRetainedWindow && !collectedPrefixIds.has(receipt.order_id)) {
        // Only unknown rows before the first retained row are new receipts.
        // Unknown rows after overlap belong beyond the requested retained tail.
        collectedPrefixIds.add(receipt.order_id);
        unseenPrefix.push(receipt);
      }
    }
  };

  collect(firstPage);
  let cursor = firstPage.next_cursor;
  let hasMore = firstPage.has_more;
  const retainedPageCount = Math.max(1, Math.ceil(current.length / 50));
  const maxPages = Math.min(RECEIPT_REFRESH_PAGE_LIMIT, retainedPageCount * 2 + 2);
  let pagesLoaded = 1;

  while (remainingKnownIds.size && hasMore && cursor && pagesLoaded < maxPages) {
    if (!requestIsCurrent()) return null;
    const page = await loadPage(cursor);
    if (!requestIsCurrent()) return null;
    collect(page);
    cursor = page.next_cursor;
    hasMore = page.has_more;
    pagesLoaded += 1;
  }

  if (!requestIsCurrent()) return null;
  return {
    knownUpdates,
    unseenPrefix,
    complete: remainingKnownIds.size === 0,
    missingKnownIds: [...remainingKnownIds],
  };
}

/** Insert a refreshed newest page without dropping already-loaded older receipts. */
export function mergeReceiptWindow(
  current: readonly ReceiptHistoryDTO[],
  newest: readonly ReceiptHistoryDTO[],
): ReceiptHistoryDTO[] {
  const newestIds = new Set(newest.map((receipt) => receipt.order_id));
  return [...newest, ...current.filter((receipt) => !newestIds.has(receipt.order_id))];
}

/** Refresh mutable receipt status in place so an investigation keeps its scroll position. */
export function refreshKnownReceipts(
  current: readonly ReceiptHistoryDTO[],
  newest: readonly ReceiptHistoryDTO[],
): ReceiptHistoryDTO[] {
  const newestById = new Map(newest.map((receipt) => [receipt.order_id, receipt]));
  return current.map((receipt) => newestById.get(receipt.order_id) ?? receipt);
}

const PAYMENT_LABELS: Record<PosPaymentMethodDTO, string> = {
  cash: 'Cash',
  card: 'Card',
  upi: 'UPI',
  qr: 'QR',
  wallet: 'Wallet',
};

export function paymentMethodLabel(method: PosPaymentMethodDTO): string {
  return PAYMENT_LABELS[method];
}

export function orderTypeLabel(type: string): string {
  return type
    .split('_')
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

export function exactQuantityLabel(quantity: string): string {
  const trimmed = quantity.trim();
  if (!/^-?\d+(?:\.\d+)?$/.test(trimmed)) return trimmed;
  if (!trimmed.includes('.')) return trimmed.replace(/^(-?)0+(?=\d)/, '$1');
  const withoutTrailingZeroes = trimmed.replace(/0+$/, '').replace(/\.$/, '');
  return withoutTrailingZeroes.replace(/^(-?)0+(?=\d)/, '$1') || '0';
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function positiveQuantity(value: unknown): string | null {
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return exactQuantityLabel(String(value));
  }
  if (typeof value === 'string' && /^\d+(?:\.\d+)?$/.test(value.trim())) {
    const normalized = exactQuantityLabel(value);
    return Number(normalized) > 0 ? normalized : null;
  }
  return null;
}

/** Human-readable immutable customization snapshots without trusting their shape. */
export function receiptLineCustomizationLabels(line: ReceiptLineHistoryDTO): string[] {
  const labels: string[] = [];
  const variant = nonEmptyString(line.variant_snapshot?.name);
  if (variant) labels.push(variant);

  for (const modifier of line.modifiers) {
    const name = nonEmptyString(modifier.name);
    if (!name) continue;
    const quantity = positiveQuantity(modifier.qty);
    labels.push(quantity && quantity !== '1' ? `${quantity} × ${name}` : name);
  }
  return labels;
}

export function receiptSourceLabel(
  sessions: ReadonlyArray<Pick<ReceiptGamingSessionHistoryDTO, 'station_name'>>,
  orderType: string,
): string {
  const stationNames = [...new Set(
    sessions.map((session) => session.station_name.trim()).filter(Boolean),
  )];
  return stationNames.length ? stationNames.join(', ') : orderTypeLabel(orderType);
}

/** Distinct settlement actors; split payments must not be attributed to only the first cashier. */
export function receiptPaymentActorLabel(
  payments: ReadonlyArray<Pick<ReceiptPaymentHistoryDTO, 'recorded_by' | 'recorded_by_name'>>,
): string {
  const names = [...new Set(
    payments
      .map((payment) => payment.recorded_by_name?.trim())
      .filter((name): name is string => Boolean(name)),
  )];
  const hasUnattributedPayment = payments.some((payment) => !payment.recorded_by_name?.trim());
  if (!names.length) return 'Legacy record — not recorded';
  return `${names.join(' + ')}${hasUnattributedPayment ? ' + unrecorded staff' : ''}`;
}

export function sessionDurationLabel(minutes: number | null): string {
  if (minutes === null || minutes < 0 || !Number.isFinite(minutes)) return 'Not recorded';
  const rounded = Math.floor(minutes);
  const hours = Math.floor(rounded / 60);
  const remainder = rounded % 60;
  if (!hours) return `${remainder} min`;
  if (!remainder) return `${hours} hr`;
  return `${hours} hr ${remainder} min`;
}
