import { describe, expect, it, vi } from 'vitest';
import {
  TenderJournal,
  withTenderRecoveryLock,
  matchesTenderReceipt,
  type TenderAction,
} from './tender-journal';
import {
  clearOwnerWorkspaces,
  ownerScope,
  ownerWorkspaceGeneration,
  readOwnerWorkspace,
  rememberOwnerWorkspace,
} from '@/lib/owner-workspace';
import type { TenderReceiptDTO } from '@/lib/erp-api';
const identity = {
  company_id: 'company-1',
  user_id: 'owner-1',
  branch_id: 'branch-1',
};
const action: TenderAction = {
  version: 1,
  scope: ownerScope(identity),
  key: 'tender:web:stable-command',
  operation: 'payment',
  body: {
    payment_id: 'payment-1',
    source_fingerprint: 'ab'.repeat(32),
    reason: 'Staff confirmed Cash was UPI',
    evidence_reference: null,
  },
};
function memory() {
  const data = new Map<string, string>();
  return {
    data,
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, value);
    },
    removeItem: (key: string) => {
      data.delete(key);
    },
  };
}
describe('Protected correction recovery', () => {
  it('retains one exact nonsecret action across reload and refuses a changed command', () => {
    const storage = memory();
    new TenderJournal(identity, storage).save(action);
    expect(new TenderJournal(identity, storage).read()).toEqual(action);
    expect(() =>
      new TenderJournal(identity, storage).save({
        ...action,
        key: 'tender:web:other',
      }),
    ).toThrow('Recover');
    expect([...storage.data.values()][0]).not.toMatch(/password|token/);
  });
  it('isolates company, account and branch without autoexecuting or clearing the previous request', () => {
    const storage = memory();
    const first = new TenderJournal(identity, storage);
    first.save(action);
    for (const other of [
      { ...identity, user_id: 'owner-2' },
      { ...identity, branch_id: 'branch-2' },
      { ...identity, company_id: 'company-2' },
    ])
      expect(new TenderJournal(other, storage).read()).toBeNull();
    expect(first.read()).toEqual(action);
  });
  it('keeps corrupt evidence and refuses submission when storage is unavailable or full', () => {
    const storage = memory();
    storage.setItem('dcompany:tender:v1:' + ownerScope(identity), 'broken');
    expect(() => new TenderJournal(identity, storage).read()).toThrow(
      'evidence was kept',
    );
    expect(storage.data.size).toBe(1);
    expect(() => new TenderJournal(identity, null).save(action)).toThrow();
    expect(() =>
      new TenderJournal(identity, {
        ...memory(),
        setItem: () => {
          throw new Error('quota');
        },
      }).save(action),
    ).toThrow('quota');
  });
  it('requires receipt identity, original source and exact actor before forgetting an uncertain action', () => {
    const receipt = {
      idempotency_key: action.key,
      company_id: identity.company_id,
      branch_id: identity.branch_id,
      recorded_by: identity.user_id,
      source_fingerprint: action.body.source_fingerprint,
      reason: action.body.reason,
      operation: 'payment_method_corrected',
      payment_id: 'payment-1',
    } as TenderReceiptDTO;
    expect(matchesTenderReceipt(action, receipt, identity)).toBe(true);
    for (const patch of [
      { recorded_by: 'other' },
      { payment_id: 'other' },
      { source_fingerprint: 'ff'.repeat(32) },
      { idempotency_key: 'other' },
    ])
      expect(
        matchesTenderReceipt(action, { ...receipt, ...patch }, identity),
      ).toBe(false);
  });
  it('clears only the matching pending key and clears protected memory on logout', () => {
    const storage = memory();
    const journal = new TenderJournal(identity, storage);
    journal.save(action);
    journal.clear({ ...action, key: 'tender:web:other' });
    expect(journal.read()).toEqual(action);
    rememberOwnerWorkspace(action.scope, 'audit', { token: 'memory-only' });
    const generation = ownerWorkspaceGeneration();
    clearOwnerWorkspaces();
    expect(readOwnerWorkspace(action.scope, 'audit')).toBeUndefined();
    expect(ownerWorkspaceGeneration()).toBeGreaterThan(generation);
    expect(journal.read()).toEqual(action);
    journal.clear(action);
    expect(journal.read()).toBeNull();
  });
});

describe('Cross-tab correction reservation', () => {
  it('requires a supported exclusive lock and serializes two same-scope journal users', async () => {
    const storage = memory();
    let tail = Promise.resolve();
    const lockNames: string[] = [];
    vi.stubGlobal('navigator', {
      locks: {
        request: (name: string, run: () => Promise<void>) => {
          lockNames.push(name);
          const next = tail.then(run);
          tail = next;
          return next;
        },
      },
    });
    try {
      const first = new TenderJournal(identity, storage),
        second = new TenderJournal(identity, storage);
      const order: string[] = [];
      await Promise.all([
        withTenderRecoveryLock(first.scope, async () => {
          first.save(action);
          await Promise.resolve();
          order.push('first');
        }),
        withTenderRecoveryLock(second.scope, async () => {
          expect(second.read()).toEqual(action);
          expect(() =>
            second.save({ ...action, key: 'tender:web:second' }),
          ).toThrow('Recover');
          order.push('second');
        }),
      ]);
      expect(order).toEqual(['first', 'second']);
      expect(new Set(lockNames).size).toBe(1);
      expect(first.read()).toEqual(action);
      vi.stubGlobal('navigator', {});
      await expect(
        withTenderRecoveryLock(first.scope, async () => undefined),
      ).rejects.toThrow('saved request remains intact');
      expect(first.read()).toEqual(action);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
