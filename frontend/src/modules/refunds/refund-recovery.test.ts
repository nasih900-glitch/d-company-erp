import { describe, expect, it } from 'vitest';

import type { PosRefundRequestDTO } from '@/lib/erp-api';
import {
  assessRefundRecoveryCheckpoints,
  readRefundRecoveryCheckpoints,
  refundRecoveryStorageKey,
  removeRefundRecoveryCheckpoint,
  saveRefundRecoveryCheckpoint,
  type RefundRecoveryCheckpoint,
  type RefundRecoveryStorage,
} from './refund-recovery';

class MemoryStorage implements RefundRecoveryStorage {
  values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
}

const scope = { companyId: 'company-1', branchId: 'branch-1', terminalId: 'terminal-1' };
const checkpoint: RefundRecoveryCheckpoint = {
  version: 1,
  taskId: 'refund-1',
  orderId: 'order-1',
  shiftId: 'shift-1',
  terminalId: 'terminal-1',
  amountMinor: 2_500,
  action: 'settle_provider',
  actionId: 'web-refund:settle_provider:stable-action',
  occurredAt: '2026-08-28T12:34:56.000Z',
  externalReference: 'UPI-REF-991',
  createdAt: '2026-08-28T12:34:56.000Z',
};

const task: PosRefundRequestDTO = {
  id: 'refund-1',
  order_id: 'order-1',
  shift_id: 'shift-1',
  branch_id: 'branch-1',
  terminal_id: 'terminal-1',
  amount_minor: 2_500,
  reason_code: 'customer_unhappy',
  mode: 'original',
  settlement_method: 'upi',
  status: 'provider_payout_in_progress',
  accepted_at: '2026-08-28T12:30:00.000Z',
  accepted_by: 'manager-1',
  accepted_by_name: 'Manager',
  handoff_started_at: null,
  handoff_started_by: null,
  handoff_started_by_name: null,
  cash_handed_over_at: null,
  cash_handed_over_recorded_at: null,
  cash_handed_over_by: null,
  cash_handed_over_by_name: null,
  provider_payout_started_at: '2026-08-28T12:31:00.000Z',
  provider_payout_started_by: 'manager-1',
  provider_payout_started_by_name: 'Manager',
  provider_completed_at: null,
  provider_completion_recorded_at: null,
  provider_completed_by: null,
  provider_completed_by_name: null,
  settled_at: null,
  settled_by: null,
  settled_by_name: null,
  client_occurred_at: null,
  captured_time_reconciled: null,
  provider_evidence_reconciled: null,
  withdrawn_at: null,
  withdrawn_by: null,
  withdrawn_by_name: null,
  provider_verification_status: null,
  provider_verification_reference: null,
  provider_verified_at: null,
  external_reference: null,
  receipt_no: null,
  refund_id: null,
  client_action_id: 'web-refund:request:stable-request',
  customer_spend_reconciled: null,
  loyalty_reconciliation_state: null,
  note: null,
};

describe('web refund payout recovery checkpoint', () => {
  it('persists exact idempotency and provider evidence per terminal until explicitly removed', () => {
    const storage = new MemoryStorage();
    expect(saveRefundRecoveryCheckpoint(scope, checkpoint, storage)).toBe(true);
    expect(readRefundRecoveryCheckpoints(scope, storage)).toEqual([checkpoint]);
    expect(storage.values.has(refundRecoveryStorageKey(scope))).toBe(true);

    expect(removeRefundRecoveryCheckpoint(scope, checkpoint.taskId, storage)).toBe(true);
    expect(readRefundRecoveryCheckpoints(scope, storage)).toEqual([]);
  });

  it('rejects malformed local evidence instead of trusting browser storage', () => {
    const storage = new MemoryStorage();
    storage.setItem(refundRecoveryStorageKey(scope), JSON.stringify([
      { ...checkpoint, amountMinor: -1 },
      { ...checkpoint, terminalId: 'another-terminal' },
      { ...checkpoint, action: 'unknown_action' },
    ]));
    expect(readRefundRecoveryCheckpoints(scope, storage)).toEqual([]);
  });

  it('offers same-request retry only while the matching server payout is still in progress', () => {
    expect(assessRefundRecoveryCheckpoints([checkpoint], [task])[0]).toMatchObject({
      state: 'retryable', task,
    });
    expect(assessRefundRecoveryCheckpoints([checkpoint], [{
      ...task, status: 'provider_completed_pending_accounting',
    }])[0].state).toBe('recorded');
    expect(assessRefundRecoveryCheckpoints([checkpoint], [{
      ...task, status: 'withdrawn',
    }])[0].state).toBe('conflict');
    expect(assessRefundRecoveryCheckpoints([checkpoint], [
      { ...task, amount_minor: 2_400 },
    ])[0].state).toBe('conflict');
    expect(assessRefundRecoveryCheckpoints([checkpoint], [])[0].state).toBe('missing');
  });
});
