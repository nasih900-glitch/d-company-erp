import { describe, expect, it, vi } from 'vitest';

import {
  GamingAddonCreatePersistenceError,
  clearGamingAddonCreateAttempt,
  gamingAddonCreatePersistenceGuidance,
  gamingAddonCreateStorageKey,
  inspectGamingAddonCreateAttempt,
  prepareGamingAddonCreateAttempt,
  sendDurablyPersistedGamingAddon,
  withGamingAddonCreateLock,
  type DurableGamingAddonCreateAttempt,
  type GamingAddonCreateAttemptContext,
  type GamingAddonCreateLockManager,
  type GamingAddonCreateStorage,
} from './gaming-addon-create-attempt';

class MemoryStorage implements GamingAddonCreateStorage {
  readonly values = new Map<string, string>();
  readonly events: string[] = [];

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.events.push('persist');
    this.values.set(key, value);
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }
}

class SerialLockManager implements GamingAddonCreateLockManager {
  private readonly tails = new Map<string, Promise<void>>();

  async request<Response>(
    name: string,
    _options: { mode: 'exclusive' },
    callback: (lock: unknown) => Promise<Response> | Response,
  ): Promise<Response> {
    const previous = this.tails.get(name) ?? Promise.resolve();
    let release!: () => void;
    const current = new Promise<void>((resolve) => { release = resolve; });
    this.tails.set(name, previous.then(() => current));
    await previous;
    try {
      return await callback({ name });
    } finally {
      release();
    }
  }
}

const context: GamingAddonCreateAttemptContext = {
  actorUserId: 'user-1',
  companyId: 'company-1',
  branchId: 'branch-1',
  terminalId: 'terminal-1',
  sessionId: 'session-1',
  shiftId: 'shift-1',
  draft: {
    menu_item_id: 'item-1',
    variant_id: null,
    modifiers: [{ modifier_id: 'modifier-1', qty: 1 }],
    qty: 2,
    expected_unit_price_minor: 150,
    note: 'Cold',
  },
};

const factories = {
  clientLineId: () => '11111111-1111-4111-8111-111111111111',
  idempotencyKey: () => 'gaming-addon-add:attempt-1',
};

function expectPersistenceCode(
  error: unknown,
  code: GamingAddonCreatePersistenceError['code'],
) {
  expect(error).toBeInstanceOf(GamingAddonCreatePersistenceError);
  expect((error as GamingAddonCreatePersistenceError).code).toBe(code);
}

function expectSyncPersistenceCode(
  action: () => unknown,
  code: GamingAddonCreatePersistenceError['code'],
) {
  let thrown: unknown;
  try {
    action();
  } catch (error) {
    thrown = error;
  }
  expectPersistenceCode(thrown, code);
}

describe('durable Gaming add-on creation', () => {
  it('persists and reads back the exact request before the API is called', async () => {
    const storage = new MemoryStorage();
    const send = vi.fn(async (attempt: DurableGamingAddonCreateAttempt) => {
      storage.events.push('send');
      return attempt.body;
    });

    const result = await sendDurablyPersistedGamingAddon({
      storageProvider: () => storage,
      context,
      factories,
      send,
    });

    expect(storage.events).toEqual(['persist', 'send']);
    expect(send).toHaveBeenCalledOnce();
    expect(result.attempt.sessionId).toBe(context.sessionId);
    expect(result.attempt.shiftId).toBe(context.shiftId);
    expect(result.attempt.idempotencyKey).toBe('gaming-addon-add:attempt-1');
    expect(result.attempt.body).toEqual({
      ...context.draft,
      client_line_id: '11111111-1111-4111-8111-111111111111',
    });
    expect(inspectGamingAddonCreateAttempt({
      storageProvider: () => storage,
      scope: context,
    })).toEqual(result.attempt);
  });

  it('reuses every original field after reload and never calls either identity factory again', () => {
    const storage = new MemoryStorage();
    const first = prepareGamingAddonCreateAttempt({ storageProvider: () => storage, context, factories });
    const clientLineId = vi.fn(() => '22222222-2222-4222-8222-222222222222');
    const idempotencyKey = vi.fn(() => 'gaming-addon-add:replacement');

    const restored = prepareGamingAddonCreateAttempt({
      storageProvider: () => storage,
      context: {
        ...context,
        draft: {
          ...context.draft,
          menu_item_id: 'different-item',
          qty: 99,
          expected_unit_price_minor: 999_999,
          note: 'Changed after reload',
        },
      },
      factories: { clientLineId, idempotencyKey },
    });

    expect(restored).toEqual(first);
    expect(clientLineId).not.toHaveBeenCalled();
    expect(idempotencyKey).not.toHaveBeenCalled();
  });

  it('isolates receipts by employee, company, branch and terminal', () => {
    const originalKey = gamingAddonCreateStorageKey(context);
    for (const [field, value] of [
      ['actorUserId', 'user-2'],
      ['companyId', 'company-2'],
      ['branchId', 'branch-2'],
      ['terminalId', 'terminal-2'],
    ] as const) {
      expect(gamingAddonCreateStorageKey({ ...context, [field]: value })).not.toBe(originalKey);
    }
  });

  it('blocks a second session or shift instead of replacing the unresolved receipt', () => {
    const storage = new MemoryStorage();
    const first = prepareGamingAddonCreateAttempt({ storageProvider: () => storage, context, factories });

    for (const changedContext of [
      { ...context, sessionId: 'session-2' },
      { ...context, shiftId: 'shift-2' },
    ]) {
      expectSyncPersistenceCode(() => prepareGamingAddonCreateAttempt({
        storageProvider: () => storage,
        context: changedContext,
        factories,
      }), 'attempt_conflict');
    }
    expect(inspectGamingAddonCreateAttempt({ storageProvider: () => storage, scope: context }))
      .toEqual(first);
  });

  it('never calls the API if the recovery receipt cannot be durably written', async () => {
    const storage: GamingAddonCreateStorage = {
      getItem: () => null,
      setItem: () => { throw new Error('quota'); },
      removeItem: () => undefined,
    };
    const send = vi.fn(async () => 'sent');

    await expect(sendDurablyPersistedGamingAddon({
      storageProvider: () => storage,
      context,
      factories,
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'storage_unavailable');
      return true;
    });
    expect(send).not.toHaveBeenCalled();
  });

  it('quarantines a damaged receipt instead of minting a replacement request', () => {
    const storage = new MemoryStorage();
    storage.setItem(gamingAddonCreateStorageKey(context), '{not-json');
    const clientLineId = vi.fn(factories.clientLineId);
    const idempotencyKey = vi.fn(factories.idempotencyKey);

    expectSyncPersistenceCode(() => prepareGamingAddonCreateAttempt({
      storageProvider: () => storage,
      context,
      factories: { clientLineId, idempotencyKey },
    }), 'corrupt_attempt');
    expect(clientLineId).not.toHaveBeenCalled();
    expect(idempotencyKey).not.toHaveBeenCalled();
  });

  it('only clears the exact confirmed receipt and verifies its removal', () => {
    const storage = new MemoryStorage();
    const attempt = prepareGamingAddonCreateAttempt({ storageProvider: () => storage, context, factories });
    expectSyncPersistenceCode(() => clearGamingAddonCreateAttempt({
      storageProvider: () => storage,
      expectedAttempt: { ...attempt, idempotencyKey: 'gaming-addon-add:different' },
    }), 'clear_verification_failed');

    clearGamingAddonCreateAttempt({ storageProvider: () => storage, expectedAttempt: attempt });
    expect(inspectGamingAddonCreateAttempt({ storageProvider: () => storage, scope: context }))
      .toBeNull();
  });

  it('serializes the complete create critical section across tabs', async () => {
    const locks = new SerialLockManager();
    const events: string[] = [];
    let releaseFirst!: () => void;
    const firstMayFinish = new Promise<void>((resolve) => { releaseFirst = resolve; });

    const first = withGamingAddonCreateLock({
      scope: context,
      lockProvider: () => locks,
      action: async () => {
        events.push('first:start');
        await firstMayFinish;
        events.push('first:end');
      },
    });
    const second = withGamingAddonCreateLock({
      scope: context,
      lockProvider: () => locks,
      action: async () => {
        events.push('second:start');
        events.push('second:end');
      },
    });

    await Promise.resolve();
    expect(events).toEqual(['first:start']);
    releaseFirst();
    await Promise.all([first, second]);
    expect(events).toEqual(['first:start', 'first:end', 'second:start', 'second:end']);
  });

  it('uses simple current-device guidance while keeping a real saved-scope conflict precise', () => {
    const currentContext = gamingAddonCreatePersistenceGuidance(
      new GamingAddonCreatePersistenceError('current_scope_unverified', 'unverified'),
    );
    const conflictingContext = gamingAddonCreatePersistenceGuidance(
      new GamingAddonCreatePersistenceError('scope_mismatch', 'mismatch'),
    );

    expect(currentContext).toContain('current shift');
    expect(currentContext).not.toContain('terminal');
    expect(conflictingContext).toContain('original employee and terminal');
  });
});
