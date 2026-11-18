import type { PosRefundRequestDTO } from '@/lib/erp-api';
import { isMinor, isRecord, isUuid, PAID_BILL_MAX_MINOR } from '@/lib/paid-bill-contract';
export interface ExactRefundTaskTarget { id: string; orderId: string; amountMinor: number | null }
export function parseExactRefundTask(params: Pick<URLSearchParams, 'get'>): ExactRefundTaskTarget {
  const id = params.get('refund_request_id'), orderId = params.get('receipt_order_id'), amount = params.get('refund_amount_minor');
  if (!isUuid(id) || !isUuid(orderId) || amount !== null && !/^[1-9][0-9]*$/.test(amount)) throw new Error('The exact refund-task link could not be verified. No new refund was prepared.');
  const minor = amount === null ? null : Number(amount);
  if (minor !== null && (!isMinor(minor) || minor <= 0 || minor > PAID_BILL_MAX_MINOR)) throw new Error('The expected refund amount is invalid. No new refund was prepared.');
  return { id, orderId, amountMinor: minor };
}
export function matchesExactRefundTask(value: unknown, target: ExactRefundTaskTarget, branchId: string, terminalId: string): value is PosRefundRequestDTO {
  if (!isRecord(value)) return false;
  const nullableText = (v: unknown) => v === null || typeof v === 'string' && v.length <= 1000;
  const nullableId = (v: unknown) => v === null || isUuid(v);
  const date = (v: unknown) => typeof v === 'string' && v.length <= 40 && Number.isFinite(Date.parse(v));
  const nullableDate = (v: unknown) => v === null || date(v);
  if (!date(value.accepted_at) || !['accepted_by', 'handoff_started_by', 'cash_handed_over_by', 'provider_payout_started_by', 'provider_completed_by', 'settled_by', 'withdrawn_by', 'refund_id'].every(k => nullableId(value[k])) ||
    !['handoff_started_at', 'cash_handed_over_at', 'cash_handed_over_recorded_at', 'provider_payout_started_at', 'provider_completed_at', 'provider_completion_recorded_at', 'settled_at', 'withdrawn_at', 'client_occurred_at', 'provider_verified_at'].every(k => nullableDate(value[k])) ||
    !['accepted_by_name', 'handoff_started_by_name', 'cash_handed_over_by_name', 'provider_payout_started_by_name', 'provider_completed_by_name', 'settled_by_name', 'withdrawn_by_name', 'provider_verification_status', 'provider_verification_reference', 'external_reference', 'receipt_no', 'note'].every(k => nullableText(value[k])) ||
    !['captured_time_reconciled', 'provider_evidence_reconciled', 'customer_spend_reconciled'].every(k => value[k] === null || typeof value[k] === 'boolean') ||
    !(value.loyalty_reconciliation_state === null || typeof value.loyalty_reconciliation_state === 'string' && ['not_applicable', 'applied', 'legacy_redemption_restored', 'legacy_unknown'].includes(value.loyalty_reconciliation_state)) || typeof value.reason_code !== 'string' || typeof value.client_action_id !== 'string' || value.client_action_id.length < 1 || value.client_action_id.length > 160) return false;
  return value.id === target.id && value.order_id === target.orderId && value.branch_id === branchId && value.terminal_id === terminalId && isUuid(value.shift_id) && isMinor(value.amount_minor) && value.amount_minor > 0 && value.amount_minor <= PAID_BILL_MAX_MINOR && (target.amountMinor === null || value.amount_minor === target.amountMinor) && typeof value.mode === 'string' && ['cash', 'original'].includes(value.mode) && typeof value.settlement_method === 'string' && ['cash', 'card', 'upi', 'qr', 'wallet'].includes(value.settlement_method) && typeof value.status === 'string' && ['accepted_cash_due', 'accepted_provider_due', 'cash_handoff_in_progress', 'cash_handed_over_pending_accounting', 'provider_payout_in_progress', 'provider_completed_pending_accounting', 'settled', 'withdrawn'].includes(value.status);
}
