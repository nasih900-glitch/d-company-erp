import type { OrderDTO, OrderListItemDTO } from '@/lib/erp-api';

export const DIRECT_ORDER_RECOVERY_REASON_MIN_LENGTH = 3;
export const DIRECT_ORDER_RECOVERY_REASON_MAX_LENGTH = 500;

export type DirectOrderRecoveryPayload = {
  expected_checkout_version: number;
  reason: string;
};

export type DirectOrderRecoveryFailure = {
  message: string;
  resolution: 'refresh' | 'retry';
};

type RecoveryCandidate = Pick<
  OrderListItemDTO,
  | 'status'
  | 'table_id'
  | 'type'
  | 'checkout_version'
  | 'items_count'
  | 'invoice_no'
  | 'paid_minor'
  | 'total_minor'
>;

export function isDirectOrderRecoveryEligible(order: RecoveryCandidate): boolean {
  return order.status === 'open'
    && order.table_id === null
    && order.type !== 'session'
    && Number.isInteger(order.items_count)
    && order.items_count > 0
    && order.invoice_no === null
    && order.paid_minor === 0
    && Number.isInteger(order.total_minor)
    && order.total_minor >= 0
    && Number.isInteger(order.checkout_version)
    && order.checkout_version >= 1;
}

export function normalizeDirectOrderRecoveryReason(reason: string): string {
  return reason.trim();
}

export function directOrderRecoveryReasonError(reason: string): string | null {
  const normalized = normalizeDirectOrderRecoveryReason(reason);
  if (normalized.length < DIRECT_ORDER_RECOVERY_REASON_MIN_LENGTH) {
    return 'Enter a reason with at least 3 characters.';
  }
  if (normalized.length > DIRECT_ORDER_RECOVERY_REASON_MAX_LENGTH) {
    return 'Keep the recovery reason to 500 characters or fewer.';
  }
  return null;
}

export function directOrderRecoveryFingerprint(
  orderId: string,
  payload: DirectOrderRecoveryPayload,
): string {
  return JSON.stringify([
    orderId,
    payload.expected_checkout_version,
    payload.reason,
  ]);
}

export function applyDirectOrderRecovery(
  rows: OrderListItemDTO[],
  recovered: Pick<OrderDTO, 'id' | 'status' | 'held_at' | 'checkout_version'>,
): OrderListItemDTO[] {
  return rows.map((row) => (
    row.id === recovered.id
      ? {
          ...row,
          status: recovered.status,
          held_at: recovered.held_at,
          checkout_version: recovered.checkout_version,
        }
      : row
  ));
}

export function directOrderRecoveryFailure(error: unknown): DirectOrderRecoveryFailure {
  const candidate = error as { code?: unknown; status?: unknown; message?: unknown };
  const message = typeof candidate?.message === 'string' && candidate.message.trim()
    ? candidate.message.trim()
    : 'The server did not confirm the recovery.';
  const status = typeof candidate?.status === 'number' ? candidate.status : null;
  const code = typeof candidate?.code === 'string' ? candidate.code : '';
  if (code === 'idempotency_in_progress') {
    return {
      message: `Recovery is still being checked by the server: ${message} Keep these details unchanged and retry the same recovery.`,
      resolution: 'retry',
    };
  }
  const serverRejected = status !== null && status >= 400 && status < 500;
  const stateConflict = code.includes('conflict')
    || /\b(changed|conflict|stale|version)\b/i.test(message);

  if (serverRejected || stateConflict) {
    return {
      message: `Recovery stopped: ${message} Refresh orders and review the latest server state before trying again.`,
      resolution: 'refresh',
    };
  }

  return {
    message: `Recovery was not confirmed: ${message} Keep these details unchanged and retry the same recovery.`,
    resolution: 'retry',
  };
}
