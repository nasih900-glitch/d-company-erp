import { describe, expect, it } from 'vitest';

import {
  approvalAttemptFor,
  canApproveGamingCleanupCandidate,
  gamingCleanupApprovalConfirmation,
  gamingCleanupStationLabel,
} from './GamingCleanupRecoveryPanel';
import type { GamingCleanupReconciliationDTO, StationDTO } from '@/lib/erp-api';

describe('Gaming cleanup recovery policy', () => {
  it('permits only a reviewed server-reported candidate with no child work', () => {
    expect(canApproveGamingCleanupCandidate(
      { status: 'reported', unresolved_child_count: 0 },
      'Reviewed exact production cleanup receipt',
    )).toBe(true);
    expect(canApproveGamingCleanupCandidate(
      { status: 'reported', unresolved_child_count: 1 },
      'Reviewed exact production cleanup receipt',
    )).toBe(false);
    expect(canApproveGamingCleanupCandidate(
      { status: 'approved', unresolved_child_count: 0 },
      'Reviewed exact production cleanup receipt',
    )).toBe(false);
    expect(canApproveGamingCleanupCandidate(
      { status: 'reported', unresolved_child_count: 0 },
      '  ',
    )).toBe(false);
  });

  it('reuses one approval key after a lost response until refresh reconciliation', () => {
    const attempts = new Map();
    let sequence = 0;
    const createKey = () => `key-${++sequence}`;
    const first = approvalAttemptFor(attempts, 'candidate-1', 'Reviewed exact receipt', createKey);
    const retry = approvalAttemptFor(attempts, 'candidate-1', 'Reviewed exact receipt', createKey);
    expect(retry?.key).toBe(first?.key);
    expect(sequence).toBe(1);
    expect(approvalAttemptFor(
      attempts, 'candidate-1', 'Changed reason', createKey,
    )).toBeNull();
    attempts.clear();
    expect(approvalAttemptFor(
      attempts, 'candidate-1', 'Changed reason', createKey,
    )?.key).toBe('key-2');
  });

  it('names the authoritative station, amount, duration and exact action before approval', () => {
    const station: StationDTO = {
      id: '66666666-6666-4666-8666-666666666666',
      branch_id: '44444444-4444-4444-8444-444444444444',
      code: 'PS5-01',
      name: 'PS5 Station 1',
      type: 'ps5',
      rate_per_hour_minor: 12_000,
      is_active: true,
    };
    const row: GamingCleanupReconciliationDTO = {
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      installation_id: '11111111-1111-4111-8111-111111111111',
      branch_id: station.branch_id,
      terminal_id: '55555555-5555-4555-8555-555555555555',
      station_id: station.id,
      local_action_id: '22222222-2222-4222-8222-222222222222',
      server_session_id: '33333333-3333-4333-8333-333333333333',
      revision: 1,
      is_current: true,
      reported_local_state: 'stop_pending',
      local_evidence_revision: 7,
      local_snapshot_sha256: 'a'.repeat(64),
      start_request_hash: 'b'.repeat(64),
      stop_request_hash: 'c'.repeat(64),
      original_action_user_id: '77777777-7777-4777-8777-777777777777',
      candidate_sha256: 'd'.repeat(64),
      unresolved_child_count: 0,
      cleanup_receipt_audit_id: 28204,
      review: {
        amount_minor: 12_000,
        billable_minutes: 54,
        started_at: '2026-09-21T09:00:00Z',
        ended_at: '2026-09-21T09:54:00Z',
      },
      status: 'reported',
      reported_at: '2026-09-21T10:00:00Z',
      approved_at: null,
      approved_by: null,
      approval_reason: null,
      applied_at: null,
      applied_by: null,
      superseded_at: null,
      device_directive: 'wait_for_owner',
    };
    expect(gamingCleanupStationLabel(station)).toBe('PS5 Station 1 (PS5-01)');
    expect(gamingCleanupApprovalConfirmation(row, station)).toBe(
      'Approve retiring only PS5 Station 1 (PS5-01) '
      + '(66666666-6666-4666-8666-666666666666) for ₹120.00, 54 billable min? '
      + 'The exact tablet action is 22222222-2222-4222-8222-222222222222.',
    );
  });
});
