import { describe, expect, it } from 'vitest';

import {
  GamingSessionActionPersistenceError,
  clearGamingSessionAction,
  inspectGamingSessionAction,
  listGamingSessionActions,
  parseGamingSessionActionAttempt,
  persistGamingSessionAction,
  type GamingSessionActionAttempt,
  type GamingSessionActionScope,
} from './gaming-session-action';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length(): number { return this.values.size; }
  key(index: number): string | null { return Array.from(this.values.keys())[index] ?? null; }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void { this.values.delete(key); }
  clear(): void { this.values.clear(); }
}

const scope: GamingSessionActionScope = {
  actorUserId: 'staff-1',
  companyId: 'company-1',
  branchId: 'branch-1',
  terminalId: 'terminal-1',
  sessionId: 'session-1',
  shiftId: 'shift-1',
};

const amendment: GamingSessionActionAttempt = {
  ...scope,
  version: 1,
  idempotencyKey: 'gaming-session-action:amend-1',
  kind: 'amend',
  payload: {
    target_package_id: 'ps5-single-30',
    expected_timer_minutes: 60,
    expected_amount_minor: 12_000,
    expected_pause_version: 0,
    expected_participant_revision: 0,
    expected_billing_revision: 0,
    expected_target_price_minor: 8_000,
    expected_target_duration_minutes: 30,
    expected_target_variant: 'single',
  },
};

const join: GamingSessionActionAttempt = {
  ...scope,
  version: 1,
  idempotencyKey: 'gaming-session-action:join-1',
  kind: 'join',
  payload: {
    customer_id: 'customer-1',
    expected_participant_revision: 1,
    customer_directory_revision: 7,
    customer_directory_company_id: 'company-1',
  },
};

const leave: GamingSessionActionAttempt = {
  ...scope,
  version: 1,
  idempotencyKey: 'gaming-session-action:leave-1',
  kind: 'leave',
  participantId: 'participant-1',
  payload: { expected_participant_revision: 2 },
};

const stop: GamingSessionActionAttempt = {
  ...scope,
  version: 1,
  idempotencyKey: 'gaming-session-action:stop-1',
  kind: 'stop',
  payload: { expected_participant_revision: 3, expected_billing_revision: 1 },
};

describe('Gaming session action recovery receipts', () => {
  it('accepts exact amendment, Join, Leave and Stop snapshots and rejects incomplete revisions', () => {
    for (const attempt of [amendment, join, leave, stop]) {
      expect(parseGamingSessionActionAttempt(attempt)).toEqual(attempt);
    }
    expect(parseGamingSessionActionAttempt({
      ...amendment,
      payload: { ...amendment.payload, expected_billing_revision: -1 },
    })).toBeNull();
    expect(parseGamingSessionActionAttempt({
      ...join,
      payload: { ...join.payload, customer_directory_company_id: undefined },
    })).toBeNull();
    expect(parseGamingSessionActionAttempt({
      ...leave,
      payload: { expected_participant_revision: 2, unexpected: true },
    })).toBeNull();
    expect(parseGamingSessionActionAttempt({
      ...stop,
      payload: { expected_participant_revision: 3 },
    })).toBeNull();
    expect(parseGamingSessionActionAttempt({ ...stop, unexpected: true })).toBeNull();
  });

  it('persists the exact action and prevents a second action for the same session', () => {
    const storage = new MemoryStorage();
    persistGamingSessionAction(storage, amendment);

    expect(inspectGamingSessionAction(storage, scope)).toEqual(amendment);
    expect(listGamingSessionActions(storage, scope)).toEqual([amendment]);
    expect(() => persistGamingSessionAction(storage, join))
      .toThrowError(GamingSessionActionPersistenceError);
    expect(() => persistGamingSessionAction(storage, amendment))
      .toThrowError(GamingSessionActionPersistenceError);
    expect(inspectGamingSessionAction(storage, scope)).toEqual(amendment);
  });

  it('replays the saved key and snapshots after reload even when live state changes', () => {
    const storage = new MemoryStorage();
    persistGamingSessionAction(storage, join);

    const saved = inspectGamingSessionAction(storage, {
      ...scope,
      shiftId: 'shift-2',
    });
    expect(saved).toEqual(join);
    expect(saved?.idempotencyKey).toBe('gaming-session-action:join-1');
    expect(saved?.payload).toEqual(join.payload);
  });

  it('isolates receipts by staff and terminal and rejects corrupted scoped evidence', () => {
    const storage = new MemoryStorage();
    persistGamingSessionAction(storage, amendment);
    persistGamingSessionAction(storage, { ...join, actorUserId: 'staff-2' });
    persistGamingSessionAction(storage, { ...leave, terminalId: 'terminal-2' });

    expect(listGamingSessionActions(storage, scope)).toEqual([amendment]);
    const key = storage.key(0);
    if (!key) throw new Error('expected saved action key');
    storage.setItem(key, '{broken');
    expect(() => inspectGamingSessionAction(storage, scope))
      .toThrowError(GamingSessionActionPersistenceError);
    expect(() => listGamingSessionActions(storage, scope))
      .toThrowError(GamingSessionActionPersistenceError);
    expect(storage.getItem(key)).toBe('{broken');
  });

  it('fails closed if storage changes the amendment before readback', () => {
    const storage = new MemoryStorage();
    const setItem = storage.setItem.bind(storage);
    storage.setItem = (key, value) => {
      const saved = JSON.parse(value) as GamingSessionActionAttempt;
      if (saved.kind !== 'amend') throw new Error('expected amendment');
      setItem(key, JSON.stringify({
        ...saved,
        payload: { ...saved.payload, expected_target_price_minor: 9_000 },
      }));
    };

    expect(() => persistGamingSessionAction(storage, amendment))
      .toThrowError(GamingSessionActionPersistenceError);
    expect(inspectGamingSessionAction(storage, scope)).toMatchObject({
      kind: 'amend', payload: { expected_target_price_minor: 9_000 },
    });
  });

  it('cannot prepare a new action when storage rejects the durable write', () => {
    const storage = new MemoryStorage();
    storage.setItem = () => { throw new Error('quota exceeded'); };

    expect(() => persistGamingSessionAction(storage, join))
      .toThrowError(GamingSessionActionPersistenceError);
    expect(inspectGamingSessionAction(storage, scope)).toBeNull();
  });

  it('clears only the exact confirmed receipt and preserves a changed Stop revision', () => {
    const storage = new MemoryStorage();
    persistGamingSessionAction(storage, stop);
    const key = storage.key(0);
    if (!key) throw new Error('expected saved action key');
    storage.setItem(key, JSON.stringify({
      ...stop,
      payload: { expected_participant_revision: 4, expected_billing_revision: 1 },
    }));

    expect(() => clearGamingSessionAction(storage, stop))
      .toThrowError(GamingSessionActionPersistenceError);
    expect(storage.getItem(key)).not.toBeNull();

    clearGamingSessionAction(storage, {
      ...stop,
      payload: { expected_participant_revision: 4, expected_billing_revision: 1 },
    });
    expect(inspectGamingSessionAction(storage, scope)).toBeNull();
  });

  it('keeps a confirmed action visible when storage fails to remove it', () => {
    const storage = new MemoryStorage();
    persistGamingSessionAction(storage, stop);
    storage.removeItem = () => { throw new Error('storage unavailable'); };

    expect(() => clearGamingSessionAction(storage, stop))
      .toThrowError(GamingSessionActionPersistenceError);
    expect(inspectGamingSessionAction(storage, scope)).toEqual(stop);
  });
});
