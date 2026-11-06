import type { AxiosRequestConfig } from 'axios';
import { api, readSessionGeneration } from './api';
import { readStoredTerminalId } from './operational-context';

export interface OperatorRecoveryActor {
  roles: string[];
  effective_permissions?: string[];
}

export function canRecoverOperator(actor: OperatorRecoveryActor | null): boolean {
  return actor?.roles.includes('owner') === true &&
    actor.effective_permissions?.includes('pos.shift.handover.recover') === true;
}

export const validOperatorId = (id: string) => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(id);

export interface OperatorReceipt {
  id: string;
  shift_id: string;
  branch_id: string;
  terminal_id: string;
  initiated_by_user_id: string;
  previous_operator_user_id: string | null;
  incoming_user_id: string;
  from_revision: number;
  to_revision: number;
  cash_count_status: 'NOT_COUNTED';
  counted_cash_minor: null;
  handover_kind: 'handover' | 'owner_recovery';
  reason: string | null;
  resolves_handover_id: string | null;
  created_at: string;
}

export interface OperatorRecoveryState {
  shift_id: string;
  shift_status: string;
  operator_user_id: string | null;
  operator_name: string | null;
  revision: number;
  tracking: string;
  latest_handover: OperatorReceipt | null;
  recovery_target: { id: string; name: string } | null;
}

export interface OperatorRecoveryScope {
  actorId: string;
  companyId: string;
  branchId: string;
  terminalId: string;
  shiftId: string;
}

export interface OperatorRecoveryBody {
  incoming_user_id: string;
  expected_revision: number;
  approved: true;
  reason: string;
  resolves_handover_id: string;
}

export function recoveryTarget(state: OperatorRecoveryState, scope: OperatorRecoveryScope) {
  const latest = state.latest_handover;
  const target = state.recovery_target;
  if (!latest || !target || !validOperatorId(target.id) || !target.name.trim() ||
      state.shift_status !== 'open' || state.tracking !== 'recorded' || state.shift_id !== scope.shiftId ||
      latest.shift_id !== scope.shiftId || latest.branch_id !== scope.branchId ||
      latest.terminal_id !== scope.terminalId || latest.handover_kind !== 'handover' ||
      !validOperatorId(latest.id) || state.revision < 1 || !Number.isSafeInteger(state.revision) ||
      latest.to_revision !== state.revision || latest.incoming_user_id !== state.operator_user_id ||
      latest.initiated_by_user_id !== target.id || target.id === state.operator_user_id) return null;
  return { id: target.id, name: target.name, revision: state.revision, resolvesId: latest.id };
}

export function isOperatorRecoveryBody(value: unknown): value is OperatorRecoveryBody {
  if (typeof value !== 'object' || value === null) return false;
  const body = value as Partial<OperatorRecoveryBody>;
  return typeof body.incoming_user_id === 'string' && validOperatorId(body.incoming_user_id) &&
    typeof body.resolves_handover_id === 'string' && validOperatorId(body.resolves_handover_id) &&
    typeof body.expected_revision === 'number' && Number.isSafeInteger(body.expected_revision) &&
    body.expected_revision >= 1 && body.expected_revision < 2_147_483_647 &&
    body.approved === true && typeof body.reason === 'string' && body.reason.trim() === body.reason &&
    body.reason.length >= 12 && body.reason.length <= 500;
}

export function verifyOperatorRecoveryReceipt(receipt: OperatorReceipt, scope: OperatorRecoveryScope,
  body: OperatorRecoveryBody): void {
  if (!validOperatorId(receipt.id) || receipt.shift_id !== scope.shiftId || receipt.branch_id !== scope.branchId ||
      receipt.terminal_id !== scope.terminalId || receipt.initiated_by_user_id !== scope.actorId ||
      receipt.handover_kind !== 'owner_recovery' || receipt.incoming_user_id !== body.incoming_user_id ||
      receipt.from_revision !== body.expected_revision || receipt.to_revision !== body.expected_revision + 1 ||
      receipt.resolves_handover_id !== body.resolves_handover_id || receipt.reason !== body.reason ||
      receipt.cash_count_status !== 'NOT_COUNTED' || receipt.counted_cash_minor !== null ||
      !Number.isFinite(Date.parse(receipt.created_at))) {
    throw new Error('This receipt could not verify the saved operator recovery.');
  }
}

export function operatorRecoveryRequest(scope: OperatorRecoveryScope, generation: number, signal: AbortSignal,
  actionKey?: string): AxiosRequestConfig & { _sessionGeneration: number; _retried?: boolean } {
  const defaults = api.defaults.transformRequest;
  return {
    signal,
    _sessionGeneration: generation,
    _retried: actionKey ? true : undefined,
    headers: actionKey ? { 'Idempotency-Key': actionKey } : undefined,
    transformRequest: [(data: unknown) => {
      if (generation !== readSessionGeneration() || scope.terminalId !== readStoredTerminalId()) {
        throw new Error('The signed-in till changed. Reopen the exact recovery link.');
      }
      return data;
    }, ...(Array.isArray(defaults) ? defaults : defaults ? [defaults] : [])],
  };
}

export function operatorRecoveryPath(scope: OperatorRecoveryScope): string {
  return `/pos/shifts/${scope.shiftId}`;
}
