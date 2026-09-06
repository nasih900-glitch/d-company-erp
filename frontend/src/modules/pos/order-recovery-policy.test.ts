import { describe, expect, it } from 'vitest';

import { StableMutationIntent } from '@/lib/stable-mutation-intent';
import {
  applyDirectOrderRecovery,
  directOrderRecoveryFailure,
  directOrderRecoveryFingerprint,
  directOrderRecoveryReasonError,
  isDirectOrderRecoveryEligible,
  normalizeDirectOrderRecoveryReason,
} from './order-recovery-policy';

const eligible = {
  status: 'open',
  table_id: null,
  type: 'takeaway',
  checkout_version: 7,
  items_count: 2,
  invoice_no: null,
  paid_minor: 0,
  total_minor: 4_200,
};

describe('direct-order recovery policy', () => {
  it('allows only versioned, tableless, non-session open direct orders', () => {
    expect(isDirectOrderRecoveryEligible(eligible)).toBe(true);
    expect(isDirectOrderRecoveryEligible({ ...eligible, status: 'held' })).toBe(false);
    expect(isDirectOrderRecoveryEligible({ ...eligible, table_id: 'table-1' })).toBe(false);
    expect(isDirectOrderRecoveryEligible({ ...eligible, type: 'session' })).toBe(false);
    expect(isDirectOrderRecoveryEligible({ ...eligible, items_count: 0 })).toBe(false);
    expect(isDirectOrderRecoveryEligible({ ...eligible, invoice_no: 'MN-1' })).toBe(false);
    expect(isDirectOrderRecoveryEligible({ ...eligible, paid_minor: 100 })).toBe(false);
    expect(isDirectOrderRecoveryEligible({ ...eligible, total_minor: -1 })).toBe(false);
    expect(isDirectOrderRecoveryEligible({ ...eligible, checkout_version: 0 })).toBe(false);
  });

  it('trims the reason and enforces the backend 3..500 character contract', () => {
    expect(normalizeDirectOrderRecoveryReason('  Tablet closed  ')).toBe('Tablet closed');
    expect(directOrderRecoveryReasonError('  yes  ')).toBeNull();
    expect(directOrderRecoveryReasonError('  x  ')).toContain('at least 3');
    expect(directOrderRecoveryReasonError('x'.repeat(501))).toContain('500');
  });

  it('fingerprints the exact order, version and normalized request body', () => {
    const first = directOrderRecoveryFingerprint('order-1', {
      expected_checkout_version: 7,
      reason: 'Original checkout abandoned',
    });
    const same = directOrderRecoveryFingerprint('order-1', {
      expected_checkout_version: 7,
      reason: 'Original checkout abandoned',
    });
    const changedVersion = directOrderRecoveryFingerprint('order-1', {
      expected_checkout_version: 8,
      reason: 'Original checkout abandoned',
    });

    expect(same).toBe(first);
    expect(changedVersion).not.toBe(first);
  });

  it('keeps one operation key for an unchanged retry and rotates after an edit', () => {
    let sequence = 0;
    const intents = new StableMutationIntent<{ reason: string }>({
      prefix: 'pos-direct-recovery:web',
      keyFactory: (prefix) => `${prefix}:attempt-${++sequence}`,
    });
    const firstPayload = { expected_checkout_version: 7, reason: 'Tablet abandoned' };
    const first = intents.resolve(
      directOrderRecoveryFingerprint('order-1', firstPayload),
      () => ({ reason: firstPayload.reason }),
    );
    const retry = intents.resolve(
      directOrderRecoveryFingerprint('order-1', firstPayload),
      () => ({ reason: 'must not replace frozen retry' }),
    );
    const editedPayload = { ...firstPayload, reason: 'Tablet abandoned after restart' };
    const edited = intents.resolve(
      directOrderRecoveryFingerprint('order-1', editedPayload),
      () => ({ reason: editedPayload.reason }),
    );

    expect(retry).toEqual(first);
    expect(edited.idempotencyKey).not.toBe(first.idempotencyKey);
    expect(edited.payload.reason).toBe('Tablet abandoned after restart');
  });

  it('applies the confirmed held snapshot before any best-effort list refresh', () => {
    const original = {
      ...eligible,
      id: 'order-1',
      invoice_no: null,
      source_label: 'Direct POS',
      total_minor: 4_200,
      customer_name: null,
      created_at: '2026-09-03T19:00:00Z',
      held_at: null,
      paid_minor: 0,
      refundable_minor: 0,
      pending_refund_minor: 0,
      payment_methods: [],
    };
    const rows = applyDirectOrderRecovery([original], {
      id: 'order-1',
      status: 'held',
      held_at: '2026-09-03T19:05:00Z',
      checkout_version: 8,
    });

    expect(rows[0]).toMatchObject({
      status: 'held',
      held_at: '2026-09-03T19:05:00Z',
      checkout_version: 8,
    });
    expect(isDirectOrderRecoveryEligible(rows[0])).toBe(false);
  });

  it('requires a refresh for server conflicts but preserves same-key retry copy for ambiguity', () => {
    expect(directOrderRecoveryFailure({
      status: 409,
      code: 'conflict',
      message: 'This bill changed before recovery.',
    })).toEqual({
      message: 'Recovery stopped: This bill changed before recovery. Refresh orders and review the latest server state before trying again.',
      resolution: 'refresh',
    });

    const network = directOrderRecoveryFailure(new Error('Network timeout'));
    expect(network.resolution).toBe('retry');
    expect(network.message).toContain('Keep these details unchanged');

    const inProgress = directOrderRecoveryFailure({
      status: 409,
      code: 'idempotency_in_progress',
      message: 'The first request is still running.',
    });
    expect(inProgress.resolution).toBe('retry');
    expect(inProgress.message).toContain('same recovery');
  });
});
