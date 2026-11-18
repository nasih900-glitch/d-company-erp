/** V6 paid decrease/discard. No credentials or payout evidence belong in these records. */
export const PAID_BILL_MAX_MINOR = 1_000_000_000_000;
export const paidStatuses = ['original', 'corrected_no_payment', 'pending_settlement', 'discarded', 'settled', 'withdrawn', 'physical_completed_accounting_pending', 'payout_in_progress_outcome_unknown'] as const;
export type PaidStatus = typeof paidStatuses[number];
export interface PaidBillPreviewBody {
  protocol: 1; order_id: string; operation: 'decrease' | 'discard'; target_total_minor: number;
  money_received: 'received' | 'not_received' | 'unknown'; reason: string;
  settlement_shift_id: string | null; refund_mode: 'cash' | 'original' | null;
}
export interface PaidBillCommitBody extends PaidBillPreviewBody { source_revision: number; source_fingerprint: string }
export interface PaidBillAcceptance {
  id: string; order_id: string; revision: number; refund_request_id: string | null;
  target_total_minor: number; status: 'pending_settlement' | 'discarded'; financial_effect: false;
}
export interface PaidBillPreview {
  protocol: number; order_id: string; invoice_no: string | null; source_revision: number; source_fingerprint: string;
  original_total_minor: number; actual_refunded_minor: number; effective_total_minor: number;
  proposed_total_minor: number; proposed_refund_minor: number; eligible: boolean; commit_available: boolean;
  blockers: string[]; settlement_shift_ids: string[]; refund_modes: ('cash' | 'original')[];
  settlement_shift_id: string | null; refund_mode: 'cash' | 'original' | null; financial_effect: false; stock_effect: false;
}
export interface PaidBillSummary {
  protocol: number; original_total_minor: number; actual_refunded_minor: number; effective_total_minor: number;
  proposed_total_minor: number | null; status: string; refund_request_id: string | null;
  physical_completed_pending_accounting_minor: number; payout_remaining_minor: number | null; stock_effect: false;
}
export interface PaidBillHistory {
  id: string; revision: number; operation: 'decrease' | 'discard'; target_minor: number;
  refund_request_id: string | null; actual_refund_id: string | null;
  physical_completed_pending_accounting_minor: number; payout_remaining_minor: number | null; status: string;
}
export interface PaidBillState extends PaidBillSummary {
  order_id: string; invoice_no: string | null; no_value_corrected_minor: number; source_revision: number; history: PaidBillHistory[];
}
export interface PaidBillActionRead { accepted: PaidBillAcceptance; current: PaidBillState }
export interface PaidBillScope { endpoint: string; companyId: string; userId: string; branchId: string; terminalId: string }
export interface PaidBillAction {
  version: 1; purpose: 'paid_bill_amendment'; scope: PaidBillScope; key: string; body: PaidBillCommitBody;
  expected: { invoice_no: string; original_total_minor: number; effective_total_minor: number; proposed_refund_minor: number; task_required: boolean };
}
export const isRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
export const exactKeys = (v: Record<string, unknown>, keys: string[]) => Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k));
export const isUuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(v);
export const isMinor = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 && v <= 9_000_000_000_000_000;
const nullableUuid = (v: unknown) => v === null || isUuid(v);
const nullableMinor = (v: unknown) => v === null || isMinor(v);
const revision = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
const invoice = (v: unknown) => v === null || typeof v === 'string' && v.length > 0 && v.length <= 64;
export const knownPaidStatus = (v: string): v is PaidStatus => (paidStatuses as readonly string[]).includes(v);
export function effectivePaidEndpoint(base: string, page: string): string {
  const u = new URL(base, page);
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password || u.search || u.hash) throw new Error('The API endpoint cannot be safely identified. Nothing was submitted.');
  return u.origin + u.pathname;
}
export function validPaidScope(v: unknown): v is PaidBillScope {
  if (!isRecord(v) || !exactKeys(v, ['endpoint', 'companyId', 'userId', 'branchId', 'terminalId']) || ![v.companyId, v.userId, v.branchId, v.terminalId].every(isUuid) || typeof v.endpoint !== 'string') return false;
  try { return effectivePaidEndpoint(v.endpoint, v.endpoint) === v.endpoint; } catch { return false; }
}
export const samePaidScope = (a: PaidBillScope, b: PaidBillScope) => a.endpoint === b.endpoint && a.companyId === b.companyId && a.userId === b.userId && a.branchId === b.branchId && a.terminalId === b.terminalId;
const bodyKeys = ['protocol', 'order_id', 'operation', 'target_total_minor', 'money_received', 'reason', 'settlement_shift_id', 'refund_mode'];
export function validPaidBody(v: unknown, commit = false): v is PaidBillCommitBody {
  if (!isRecord(v) || !exactKeys(v, [...bodyKeys, ...(commit ? ['source_revision', 'source_fingerprint'] : [])])) return false;
  return v.protocol === 1 && isUuid(v.order_id) && typeof v.operation === 'string' && ['decrease', 'discard'].includes(v.operation) && isMinor(v.target_total_minor) && v.target_total_minor <= PAID_BILL_MAX_MINOR &&
    (v.operation !== 'discard' || v.target_total_minor === 0) && typeof v.money_received === 'string' && ['received', 'not_received', 'unknown'].includes(v.money_received) &&
    validCancellationReason(v.reason) && nullableUuid(v.settlement_shift_id) &&
    (v.refund_mode === null || v.refund_mode === 'cash' || v.refund_mode === 'original') && (v.settlement_shift_id === null) === (v.refund_mode === null) &&
    (!commit || revision(v.source_revision) && typeof v.source_fingerprint === 'string' && /^[a-f0-9]{64}$/.test(v.source_fingerprint));
}
export function validPaidAcceptance(v: unknown): v is PaidBillAcceptance {
  return isRecord(v) && exactKeys(v, ['id', 'order_id', 'revision', 'refund_request_id', 'target_total_minor', 'status', 'financial_effect']) && isUuid(v.id) && isUuid(v.order_id) && revision(v.revision) && nullableUuid(v.refund_request_id) && isMinor(v.target_total_minor) && typeof v.status === 'string' && ['pending_settlement', 'discarded'].includes(v.status) && v.financial_effect === false;
}
const summaryKeys = ['protocol', 'original_total_minor', 'actual_refunded_minor', 'effective_total_minor', 'proposed_total_minor', 'status', 'refund_request_id', 'physical_completed_pending_accounting_minor', 'payout_remaining_minor', 'stock_effect'];
function summaryFacts(v: Record<string, unknown>) {
  return revision(v.protocol) && [v.original_total_minor, v.actual_refunded_minor, v.effective_total_minor, v.physical_completed_pending_accounting_minor].every(isMinor) && nullableMinor(v.proposed_total_minor) && nullableMinor(v.payout_remaining_minor) && nullableUuid(v.refund_request_id) && typeof v.status === 'string' && v.status.length <= 100 && v.stock_effect === false;
}
export function validPaidSummary(v: unknown): v is PaidBillSummary { return isRecord(v) && exactKeys(v, summaryKeys) && summaryFacts(v); }
export function validPaidHistory(v: unknown): v is PaidBillHistory {
  return isRecord(v) && exactKeys(v, ['id', 'revision', 'operation', 'target_minor', 'refund_request_id', 'actual_refund_id', 'physical_completed_pending_accounting_minor', 'payout_remaining_minor', 'status']) && isUuid(v.id) && revision(v.revision) && typeof v.operation === 'string' && ['decrease', 'discard'].includes(v.operation) && isMinor(v.target_minor) && nullableUuid(v.refund_request_id) && nullableUuid(v.actual_refund_id) && isMinor(v.physical_completed_pending_accounting_minor) && nullableMinor(v.payout_remaining_minor) && typeof v.status === 'string' && v.status.length <= 100;
}
export function validPaidState(v: unknown): v is PaidBillState {
  return isRecord(v) && exactKeys(v, [...summaryKeys, 'order_id', 'invoice_no', 'no_value_corrected_minor', 'source_revision', 'history']) && summaryFacts(v) && isUuid(v.order_id) && invoice(v.invoice_no) && isMinor(v.no_value_corrected_minor) && (v.actual_refunded_minor as number) + v.no_value_corrected_minor <= (v.original_total_minor as number) && v.effective_total_minor === (v.original_total_minor as number) - (v.actual_refunded_minor as number) - v.no_value_corrected_minor && revision(v.source_revision) && Array.isArray(v.history) && v.history.length <= 1000 && v.history.every(validPaidHistory);
}
export function validPaidPreview(v: unknown): v is PaidBillPreview {
  return isRecord(v) && exactKeys(v, ['protocol', 'order_id', 'invoice_no', 'source_revision', 'source_fingerprint', 'original_total_minor', 'actual_refunded_minor', 'effective_total_minor', 'proposed_total_minor', 'proposed_refund_minor', 'eligible', 'commit_available', 'blockers', 'settlement_shift_ids', 'refund_modes', 'settlement_shift_id', 'refund_mode', 'financial_effect', 'stock_effect']) && revision(v.protocol) && isUuid(v.order_id) && invoice(v.invoice_no) && revision(v.source_revision) && typeof v.source_fingerprint === 'string' && /^[a-f0-9]{64}$/.test(v.source_fingerprint) && [v.original_total_minor, v.actual_refunded_minor, v.effective_total_minor, v.proposed_total_minor, v.proposed_refund_minor].every(isMinor) && typeof v.eligible === 'boolean' && typeof v.commit_available === 'boolean' && Array.isArray(v.blockers) && v.blockers.length <= 100 && v.blockers.every(b => typeof b === 'string' && b.length <= 2000) && Array.isArray(v.settlement_shift_ids) && v.settlement_shift_ids.every(isUuid) && Array.isArray(v.refund_modes) && v.refund_modes.every(m => m === 'cash' || m === 'original') && nullableUuid(v.settlement_shift_id) && (v.refund_mode === null || v.refund_mode === 'cash' || v.refund_mode === 'original') && v.financial_effect === false && v.stock_effect === false;
}
export function validPaidAction(v: unknown): v is PaidBillAction {
  if (!isRecord(v) || !exactKeys(v, ['version', 'purpose', 'scope', 'key', 'body', 'expected']) || v.version !== 1 || v.purpose !== 'paid_bill_amendment' || !validPaidScope(v.scope) || typeof v.key !== 'string' || !v.key.startsWith('paid-bill:web:') || !isUuid(v.key.slice(14)) || !validPaidBody(v.body, true) || v.body.money_received !== 'received' || !isRecord(v.expected)) return false;
  const e = v.expected;
  return exactKeys(e, ['invoice_no', 'original_total_minor', 'effective_total_minor', 'proposed_refund_minor', 'task_required']) && typeof e.invoice_no === 'string' && invoice(e.invoice_no) && [e.original_total_minor, e.effective_total_minor, e.proposed_refund_minor].every(isMinor) && (e.original_total_minor as number) <= PAID_BILL_MAX_MINOR && (e.effective_total_minor as number) <= (e.original_total_minor as number) && (e.effective_total_minor as number) - v.body.target_total_minor === e.proposed_refund_minor && typeof e.task_required === 'boolean' && e.task_required === ((e.proposed_refund_minor as number) > 0) && (e.task_required || v.body.operation === 'discard' && v.body.target_total_minor === 0) && (e.task_required ? v.body.settlement_shift_id !== null : v.body.settlement_shift_id === null);
}
export function matchesPaidAcceptance(action: PaidBillAction, value: unknown): value is PaidBillAcceptance {
  return validPaidAcceptance(value) && value.order_id === action.body.order_id && value.target_total_minor === action.body.target_total_minor && value.revision === action.body.source_revision + 1 && (action.expected.task_required ? isUuid(value.refund_request_id) && value.status === 'pending_settlement' : value.refund_request_id === null && value.status === 'discarded');
}
export function matchesPaidRecovery(action: PaidBillAction, value: unknown): value is PaidBillActionRead {
  if (!isRecord(value) || !exactKeys(value, ['accepted', 'current']) || !matchesPaidAcceptance(action, value.accepted) || !validPaidState(value.current)) return false;
  const { accepted: a, current: c } = value;
  return c.protocol === 1 && knownPaidStatus(c.status) && c.order_id === action.body.order_id && c.invoice_no === action.expected.invoice_no && c.original_total_minor === action.expected.original_total_minor && c.source_revision >= a.revision && c.history.filter(h => h.id === a.id).length === 1 && c.history.some(h => h.id === a.id && h.revision === a.revision && h.operation === action.body.operation && h.target_minor === a.target_total_minor && h.refund_request_id === a.refund_request_id && knownPaidStatus(h.status));
}
export function paidStatusLabel(status: string): string {
  return ({ original: 'Original paid bill', corrected_no_payment: 'Payment not received correction', pending_settlement: 'Change requested — refund pending', discarded: 'Discarded — original invoice retained', settled: 'Amount changed — refund recorded', withdrawn: 'Refund withdrawn — original amount retained', physical_completed_accounting_pending: 'Money returned — accounting pending', payout_in_progress_outcome_unknown: 'Refund in progress — outcome unknown' } as Record<string, string>)[status] ?? 'Unrecognized bill state — Owner review required';
}

export const PAID_BILL_COMMIT_PATH = '/api/v1/pos/paid-bill-amendments/commit' as const;
export interface PaidBillReference {
  action_key: string; company_id: string; actor_user_id: string; branch_id: string; terminal_id: string;
  order_id: string; request_path: typeof PAID_BILL_COMMIT_PATH; request_hash: string; original_command: PaidBillCommitBody;
}
export interface PaidBillCancellation { id: string; cancelled_at: string; cancellation_reason: string; financial_effect: false; stock_effect: false }
export type PaidBillOutcome =
  | { protocol: 1; outcome: 'accepted'; reference: PaidBillReference; accepted: PaidBillAcceptance; current: PaidBillState }
  | { protocol: 1; outcome: 'cancelled'; reference: PaidBillReference; cancelled: PaidBillCancellation }
  | { protocol: 1; outcome: 'unconfirmed'; action_key: string; company_id: string; actor_user_id: string; branch_id: string; terminal_id: string };
export function validCancellationReason(v: unknown): v is string {
  if (typeof v !== 'string' || v !== v.trim() || v.length > 500) return false;
  const points = Array.from(v);
  return points.length >= 3 && points.every(c => { const n = c.codePointAt(0)!; return n >= 32 && (n < 127 || n > 159); });
}
export function validPaidCancellation(v: unknown): v is PaidBillCancellation {
  return isRecord(v) && exactKeys(v, ['id', 'cancelled_at', 'cancellation_reason', 'financial_effect', 'stock_effect']) && isUuid(v.id) && typeof v.cancelled_at === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(v.cancelled_at) && Number.isFinite(Date.parse(v.cancelled_at)) && validCancellationReason(v.cancellation_reason) && v.financial_effect === false && v.stock_effect === false;
}
function validReference(v: unknown): v is PaidBillReference {
  return isRecord(v) && exactKeys(v, ['action_key', 'company_id', 'actor_user_id', 'branch_id', 'terminal_id', 'order_id', 'request_path', 'request_hash', 'original_command']) && typeof v.action_key === 'string' && v.action_key.length > 0 && v.action_key.length <= 160 && [v.company_id, v.actor_user_id, v.branch_id, v.terminal_id, v.order_id].every(isUuid) && v.request_path === PAID_BILL_COMMIT_PATH && typeof v.request_hash === 'string' && /^[a-f0-9]{64}$/.test(v.request_hash) && validPaidBody(v.original_command, true);
}
export function validPaidOutcome(v: unknown): v is PaidBillOutcome {
  if (!isRecord(v) || v.protocol !== 1) return false;
  if (v.outcome === 'unconfirmed') return exactKeys(v, ['protocol', 'outcome', 'action_key', 'company_id', 'actor_user_id', 'branch_id', 'terminal_id']) && typeof v.action_key === 'string' && v.action_key.length > 0 && v.action_key.length <= 160 && [v.company_id, v.actor_user_id, v.branch_id, v.terminal_id].every(isUuid);
  if (!validReference(v.reference)) return false;
  if (v.outcome === 'accepted') return exactKeys(v, ['protocol', 'outcome', 'reference', 'accepted', 'current']) && validPaidAcceptance(v.accepted) && validPaidState(v.current);
  return v.outcome === 'cancelled' && exactKeys(v, ['protocol', 'outcome', 'reference', 'cancelled']) && validPaidCancellation(v.cancelled);
}
export function matchesPaidOutcome(action: PaidBillAction, value: unknown, cancellationReason: string | null): value is Exclude<PaidBillOutcome, { outcome: 'unconfirmed' }> {
  if (!validPaidOutcome(value) || value.outcome === 'unconfirmed') return false;
  const r = value.reference, s = action.scope;
  if (r.action_key !== action.key || r.company_id !== s.companyId || r.actor_user_id !== s.userId || r.branch_id !== s.branchId || r.terminal_id !== s.terminalId || r.order_id !== action.body.order_id || !Object.entries(action.body).every(([k, v]) => r.original_command[k as keyof PaidBillCommitBody] === v)) return false;
  return value.outcome === 'accepted' ? matchesPaidRecovery(action, { accepted: value.accepted, current: value.current }) : cancellationReason !== null && value.cancelled.cancellation_reason === cancellationReason;
}
export function parsePaidAmount(text: string): number {
  const match = /^(0|[1-9][0-9]*)(?:\.([0-9]{1,2}))?$/.exec(text.trim());
  if (!match || match[1].length > 11) throw new Error('Enter a non-negative amount with at most two decimals.');
  const minor = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
  if (!Number.isSafeInteger(minor) || minor > PAID_BILL_MAX_MINOR) throw new Error('The amount exceeds this bill-change profile.');
  return minor;
}

export function exactPaidRefundUrl(task: string, order: string, amount?: number): string {
  if (!isUuid(task) || !isUuid(order) || amount !== undefined && (!isMinor(amount) || amount <= 0 || amount > PAID_BILL_MAX_MINOR)) throw new Error('The exact refund task identity is invalid.');
  return '/refunds?' + new URLSearchParams({ refund_request_id: task, receipt_order_id: order, ...(amount === undefined ? {} : { refund_amount_minor: String(amount) }) }).toString();
}

/** Session renewal may replace bearer bytes; an actor/family replacement is a new authority. */
export function paidAccessIdentity(token: string | null): string | null {
  if (!token) return null;
  try {
    const v: unknown = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    if (!isRecord(v) || v.type !== 'access' || typeof v.sub !== 'string' || typeof v.company_id !== 'string') return 'unverified';
    return JSON.stringify([v.sub, v.company_id, v.branch_id ?? null, v.session_family_id ?? null, v.auth_version ?? null]);
  } catch { return 'unverified'; }
}
