import { describe, expect, it } from 'vitest';
import {
  canRecoverOperator, isOperatorRecoveryBody, recoveryTarget, verifyOperatorRecoveryReceipt,
  type OperatorReceipt, type OperatorRecoveryBody, type OperatorRecoveryScope, type OperatorRecoveryState,
} from './operator-recovery-api';

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const scope: OperatorRecoveryScope = { actorId: id(1), companyId: id(2), branchId: id(3), terminalId: id(4), shiftId: id(5) };
const latest: OperatorReceipt = {
  id: id(6), shift_id: scope.shiftId, branch_id: scope.branchId, terminal_id: scope.terminalId,
  initiated_by_user_id: id(7), previous_operator_user_id: null, incoming_user_id: id(8),
  from_revision: 0, to_revision: 1, cash_count_status: 'NOT_COUNTED', counted_cash_minor: null,
  handover_kind: 'handover', reason: null, resolves_handover_id: null, created_at: '2026-11-06T00:00:00Z',
};
const state: OperatorRecoveryState = {
  shift_id: scope.shiftId, shift_status: 'open', operator_user_id: id(8), operator_name: 'Incoming',
  revision: 1, tracking: 'recorded', latest_handover: latest, recovery_target: { id: id(7), name: 'Previous' },
};
const body: OperatorRecoveryBody = {
  incoming_user_id: id(7), expected_revision: 1, approved: true,
  reason: 'The incoming operator is unavailable', resolves_handover_id: latest.id,
};
const corrected: OperatorReceipt = {
  ...latest, id: id(9), handover_kind: 'owner_recovery', initiated_by_user_id: scope.actorId,
  incoming_user_id: body.incoming_user_id, from_revision: 1, to_revision: 2, reason: body.reason,
  previous_operator_user_id: latest.incoming_user_id, resolves_handover_id: latest.id,
};

describe('operator recovery scope', () => {
  it('allows the exact Owner permission even with no routine POS permission', () => {
    const effective_permissions = ['pos.shift.handover.recover'];
    expect(canRecoverOperator({ roles: ['owner'], effective_permissions })).toBe(true);
    expect(canRecoverOperator({ roles: ['gaming_cashier'], effective_permissions })).toBe(false);
    expect(canRecoverOperator({ roles: ['owner'], effective_permissions: ['pos.write'] })).toBe(false);
    expect(canRecoverOperator({ roles: ['owner'] })).toBe(false);
  });

  it('selects only the verified prior operator from the latest matching handover', () => {
    expect(recoveryTarget(state, scope)).toEqual({ id: id(7), name: 'Previous', revision: 1, resolvesId: id(6) });
    for (const changed of [
      { ...state, shift_status: 'closed' },
      { ...state, revision: 2 },
      { ...state, operator_user_id: id(9) },
      { ...state, recovery_target: { id: id(9), name: 'Unrelated' } },
      { ...state, latest_handover: { ...latest, terminal_id: id(9) } },
      { ...state, latest_handover: corrected },
    ]) expect(recoveryTarget(changed, scope)).toBeNull();
  });

  it('verifies exact Owner resolution and no cash count', () => {
    expect(isOperatorRecoveryBody(body)).toBe(true);
    expect(isOperatorRecoveryBody({ ...body, reason: 'short' })).toBe(false);
    expect(() => verifyOperatorRecoveryReceipt(corrected, scope, body)).not.toThrow();
    for (const changed of [
      { ...corrected, initiated_by_user_id: id(10) },
      { ...corrected, resolves_handover_id: id(11) },
      { ...corrected, to_revision: 3 },
      { ...corrected, counted_cash_minor: 0 },
    ]) expect(() => verifyOperatorRecoveryReceipt(changed as OperatorReceipt, scope, body)).toThrow();
  });
});
