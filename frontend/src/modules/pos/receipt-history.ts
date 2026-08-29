import type {
  PosPaymentMethodDTO,
  ReceiptGamingSessionHistoryDTO,
  ReceiptLineHistoryDTO,
  ReceiptPaymentHistoryDTO,
} from '@/lib/erp-api';

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
