import type { PosRefundRequestDTO } from '@/lib/erp-api';
import type { PaidBillAction, PaidBillOutcome, PaidBillScope, PaidBillState } from '@/lib/paid-bill-contract';
export const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
export const scope: PaidBillScope = { endpoint: 'http://localhost:3000/api/v1', companyId: id(1), userId: id(2), branchId: id(3), terminalId: id(4) };
export function action(): PaidBillAction { return { version: 1, purpose: 'paid_bill_amendment', scope: { ...scope }, key: 'paid-bill:web:' + id(10), body: { protocol: 1, order_id: id(5), operation: 'decrease', target_total_minor: 6000, money_received: 'received', reason: 'Original bill amount was mistaken', settlement_shift_id: id(6), refund_mode: 'cash', source_revision: 0, source_fingerprint: 'a'.repeat(64) }, expected: { invoice_no: 'D/QA/26-27/00001', original_total_minor: 10500, effective_total_minor: 10500, proposed_refund_minor: 4500, task_required: true } }; }
export function originalState(): PaidBillState { return { protocol: 1, order_id: id(5), invoice_no: 'D/QA/26-27/00001', original_total_minor: 10500, actual_refunded_minor: 0, no_value_corrected_minor: 0, effective_total_minor: 10500, source_revision: 0, proposed_total_minor: null, status: 'original', refund_request_id: null, history: [], physical_completed_pending_accounting_minor: 0, payout_remaining_minor: 0, stock_effect: false }; }
export function accepted(a = action()): Extract<PaidBillOutcome, { outcome: 'accepted' }> {
  const acceptance = { id: id(8), order_id: a.body.order_id, revision: a.body.source_revision + 1, refund_request_id: a.expected.task_required ? id(7) : null, target_total_minor: a.body.target_total_minor, status: a.expected.task_required ? 'pending_settlement' as const : 'discarded' as const, financial_effect: false as const };
  return { protocol: 1, outcome: 'accepted', reference: { action_key: a.key, company_id: a.scope.companyId, actor_user_id: a.scope.userId, branch_id: a.scope.branchId, terminal_id: a.scope.terminalId, order_id: a.body.order_id, request_path: '/api/v1/pos/paid-bill-amendments/commit', request_hash: 'b'.repeat(64), original_command: { ...a.body } }, accepted: acceptance, current: { ...originalState(), source_revision: acceptance.revision, proposed_total_minor: a.body.target_total_minor, status: acceptance.status, refund_request_id: acceptance.refund_request_id, payout_remaining_minor: a.expected.proposed_refund_minor, history: [{ id: acceptance.id, revision: acceptance.revision, operation: a.body.operation, target_minor: a.body.target_total_minor, refund_request_id: acceptance.refund_request_id, actual_refund_id: null, physical_completed_pending_accounting_minor: 0, payout_remaining_minor: a.expected.proposed_refund_minor, status: acceptance.status }] } };
}
export function cancelled(a = action(), reason = 'Stop unresolved saved request'): Extract<PaidBillOutcome, { outcome: 'cancelled' }> { return { protocol: 1, outcome: 'cancelled', reference: accepted(a).reference, cancelled: { id: id(11), cancelled_at: '2026-11-13T00:00:00.000000Z', cancellation_reason: reason, financial_effect: false, stock_effect: false } }; }
export function serialLocks() {
  let tail = Promise.resolve();
  return { request: <T>(_name: string, _options: unknown, run: () => Promise<T>) => { const next = tail.then(run); tail = next.then(() => undefined, () => undefined); return next; } };
}
export const deferred = <T = void>() => { let resolve!: (v: T | PromiseLike<T>) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; };

export function refundTask(): PosRefundRequestDTO { return {
  id: id(7), order_id: id(5), shift_id: id(6), branch_id: id(3), terminal_id: id(4), amount_minor: 4500,
  reason_code: 'paid_bill_amendment', mode: 'cash', settlement_method: 'cash', status: 'accepted_cash_due',
  accepted_at: '2026-11-13T00:00:00Z', accepted_by: id(2), accepted_by_name: 'Owner',
  handoff_started_at: null, handoff_started_by: null, handoff_started_by_name: null,
  cash_handed_over_at: null, cash_handed_over_recorded_at: null, cash_handed_over_by: null, cash_handed_over_by_name: null,
  provider_payout_started_at: null, provider_payout_started_by: null, provider_payout_started_by_name: null,
  provider_completed_at: null, provider_completion_recorded_at: null, provider_completed_by: null, provider_completed_by_name: null,
  settled_at: null, settled_by: null, settled_by_name: null, client_occurred_at: null,
  captured_time_reconciled: null, provider_evidence_reconciled: null, withdrawn_at: null, withdrawn_by: null, withdrawn_by_name: null,
  provider_verification_status: null, provider_verification_reference: null, provider_verified_at: null,
  external_reference: null, receipt_no: null, refund_id: null, client_action_id: 'paid-task-original',
  customer_spend_reconciled: null, loyalty_reconciliation_state: null, note: null,
}; }
