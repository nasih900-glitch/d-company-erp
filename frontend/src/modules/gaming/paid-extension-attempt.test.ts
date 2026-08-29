import { describe, expect, it, vi } from 'vitest';

import {
  PaidExtensionPersistenceError,
  clearPaidExtensionAttempt,
  legacyPaidExtensionStorageKey,
  legacyV2PaidExtensionStorageKey,
  inspectPaidExtensionAttemptForSession,
  inspectPaidExtensionAttemptsForTerminal,
  isPaidExtensionLifecycleBlocked,
  paidExtensionPersistenceGuidance,
  paidExtensionSubmissionMode,
  paidExtensionRecoveryRequired,
  paidExtensionStorageKey,
  parsePaidExtensionAttempt,
  preparePaidExtensionAttempt,
  replayDurablyPersistedPaidExtension,
  sendDurablyPersistedPaidExtension,
  withPaidExtensionSessionLock,
  type PaidExtensionAttempt,
  type PaidExtensionAttemptContext,
  type PaidExtensionStorage,
  type PaidExtensionLockManager,
} from './paid-extension-attempt';

class MemoryStorage implements PaidExtensionStorage {
  readonly values = new Map<string, string>();

  get length(): number { return this.values.size; }

  key(index: number): string | null {
    return Array.from(this.values.keys())[index] ?? null;
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }
}

class SerialLockManager implements PaidExtensionLockManager {
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

const context: PaidExtensionAttemptContext = {
  actorUserId: 'user-1',
  companyId: 'company-1',
  branchId: 'branch-1',
  terminalId: 'terminal-1',
  sessionId: 'session-1',
  shiftId: 'shift-1',
  packageId: 'package-1',
  packagePriceMinor: 7_500,
  packageDurationMinutes: 30,
  packageVariant: 'solo',
  expectedTimerMinutes: 60,
  expectedAmountMinor: 12_000,
};

const expectedAttempt: PaidExtensionAttempt = {
  version: 3,
  idempotencyKey: 'gaming-extension:attempt-1',
  ...context,
};

function expectPersistenceCode(error: unknown, code: PaidExtensionPersistenceError['code']) {
  expect(error).toBeInstanceOf(PaidExtensionPersistenceError);
  expect((error as PaidExtensionPersistenceError).code).toBe(code);
}

describe('paid gaming extension persistence', () => {
  it('never traps a verified hourly session behind paid-extension storage', () => {
    expect(paidExtensionRecoveryRequired('hourly')).toBe(false);
    expect(paidExtensionRecoveryRequired('package')).toBe(true);
    expect(paidExtensionRecoveryRequired('legacy_ambiguous')).toBe(true);
    expect(paidExtensionRecoveryRequired(undefined)).toBe(true);
    expect(isPaidExtensionLifecycleBlocked({
      billingMode: 'hourly',
      hasSavedAttempt: false,
      hasRecoveryError: true,
    })).toBe(false);
    expect(isPaidExtensionLifecycleBlocked({
      billingMode: 'package',
      hasSavedAttempt: false,
      hasRecoveryError: true,
    })).toBe(true);
    expect(isPaidExtensionLifecycleBlocked({
      billingMode: 'legacy_ambiguous',
      hasSavedAttempt: true,
      hasRecoveryError: false,
    })).toBe(true);
  });

  it('serializes two tabs for the same session across the complete critical section', async () => {
    const locks = new SerialLockManager();
    const events: string[] = [];
    let releaseFirst!: () => void;
    const firstMayFinish = new Promise<void>((resolve) => { releaseFirst = resolve; });

    const first = withPaidExtensionSessionLock({
      sessionId: context.sessionId,
      lockProvider: () => locks,
      action: async () => {
        events.push('first:start');
        await firstMayFinish;
        events.push('first:end');
      },
    });
    const second = withPaidExtensionSessionLock({
      sessionId: context.sessionId,
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

  it('fails closed before the action when cross-tab locking is unavailable', async () => {
    const action = vi.fn(async () => 'sent');
    await expect(withPaidExtensionSessionLock({
      sessionId: context.sessionId,
      lockProvider: () => undefined,
      action,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'cross_tab_lock_unavailable');
      return true;
    });
    expect(action).not.toHaveBeenCalled();
  });

  it('persists and reads back the exact immutable action before calling the API', async () => {
    const storage = new MemoryStorage();
    const send = vi.fn(async (attempt: PaidExtensionAttempt) => attempt.idempotencyKey);

    const result = await sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context,
      createIdempotencyKey: () => 'gaming-extension:attempt-1',
      send,
    });

    expect(result).toEqual({ response: 'gaming-extension:attempt-1', attempt: expectedAttempt });
    expect(send).toHaveBeenCalledOnce();
    expect(send).toHaveBeenCalledWith(expectedAttempt);
    expect(JSON.parse(storage.getItem(paidExtensionStorageKey(context)) ?? '')).toEqual(expectedAttempt);
  });

  it('never calls the API when device storage rejects the write', async () => {
    const storage: PaidExtensionStorage = {
      length: 0,
      key: () => null,
      getItem: () => null,
      setItem: () => { throw new Error('quota'); },
      removeItem: () => undefined,
    };
    const send = vi.fn(async () => 'sent');

    await expect(sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context,
      createIdempotencyKey: () => 'gaming-extension:attempt-1',
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'storage_unavailable');
      return true;
    });
    expect(send).not.toHaveBeenCalled();
  });

  it('never calls the API when a storage write cannot be read back', async () => {
    const storage: PaidExtensionStorage = {
      length: 0,
      key: () => null,
      getItem: () => null,
      setItem: () => undefined,
      removeItem: () => undefined,
    };
    const send = vi.fn(async () => 'sent');

    await expect(sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context,
      createIdempotencyKey: () => 'gaming-extension:attempt-1',
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'write_verification_failed');
      return true;
    });
    expect(send).not.toHaveBeenCalled();
  });

  it('replays every original snapshot and never invokes the key factory again', async () => {
    const storage = new MemoryStorage();
    storage.setItem(paidExtensionStorageKey(context), JSON.stringify(expectedAttempt));
    const createIdempotencyKey = vi.fn(() => 'gaming-extension:replacement');
    const send = vi.fn(async (attempt: PaidExtensionAttempt) => attempt);

    const changedLiveContext: PaidExtensionAttemptContext = {
      ...context,
      packagePriceMinor: 9_000,
      packageDurationMinutes: 45,
      packageVariant: 'duo',
      expectedTimerMinutes: 90,
      expectedAmountMinor: 19_500,
    };
    const result = await sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context: changedLiveContext,
      createIdempotencyKey,
      send,
    });

    expect(result.attempt).toEqual(expectedAttempt);
    expect(send).toHaveBeenCalledWith(expectedAttempt);
    expect(createIdempotencyKey).not.toHaveBeenCalled();
  });

  it('replay mode refuses a missing receipt without writing or sending', async () => {
    const storage = new MemoryStorage();
    const setItem = vi.spyOn(storage, 'setItem');
    const send = vi.fn(async () => 'sent');

    await expect(replayDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      expectedAttempt,
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'replay_receipt_missing');
      return true;
    });
    expect(setItem).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();
  });

  it('replay mode refuses a changed receipt without replacing or sending it', async () => {
    const storage = new MemoryStorage();
    const changed = { ...expectedAttempt, expectedAmountMinor: expectedAttempt.expectedAmountMinor + 1 };
    storage.setItem(paidExtensionStorageKey(context), JSON.stringify(changed));
    const setItem = vi.spyOn(storage, 'setItem');
    const send = vi.fn(async () => 'sent');

    await expect(replayDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      expectedAttempt,
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'replay_receipt_changed');
      return true;
    });
    expect(setItem).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();
    expect(JSON.parse(storage.getItem(paidExtensionStorageKey(context)) ?? '')).toEqual(changed);
  });

  it.each([
    ['actorUserId', 'user-2'],
    ['terminalId', 'terminal-2'],
  ] as const)('fails closed when saved %s belongs to another scope', async (field, value) => {
    const storage = new MemoryStorage();
    storage.setItem(paidExtensionStorageKey(context), JSON.stringify(expectedAttempt));
    const send = vi.fn(async () => 'sent');

    await expect(sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context: { ...context, [field]: value },
      createIdempotencyKey: () => 'gaming-extension:replacement',
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'scope_mismatch');
      return true;
    });
    expect(send).not.toHaveBeenCalled();
  });

  it('quarantines a legacy partial receipt instead of minting a replacement key', async () => {
    const storage = new MemoryStorage();
    storage.setItem(legacyPaidExtensionStorageKey(context), JSON.stringify({
      key: 'gaming-extension:old-attempt',
      packageId: context.packageId,
      expectedTimerMinutes: context.expectedTimerMinutes,
      expectedAmountMinor: context.expectedAmountMinor,
    }));
    const createIdempotencyKey = vi.fn(() => 'gaming-extension:replacement');
    const send = vi.fn(async () => 'sent');

    await expect(sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context,
      createIdempotencyKey,
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'legacy_attempt');
      return true;
    });
    expect(createIdempotencyKey).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();
  });

  it('quarantines a package-scoped v2 receipt for the session even when another package is selected', async () => {
    const storage = new MemoryStorage();
    storage.setItem(legacyV2PaidExtensionStorageKey(context), JSON.stringify({
      ...expectedAttempt,
      version: 2,
    }));
    const send = vi.fn(async () => 'sent');

    await expect(sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context: { ...context, packageId: 'package-2' },
      createIdempotencyKey: () => 'gaming-extension:replacement',
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'legacy_attempt');
      return true;
    });
    expect(send).not.toHaveBeenCalled();
  });

  it('blocks a different package while one session-scoped attempt remains unresolved after reload', async () => {
    const storage = new MemoryStorage();
    storage.setItem(paidExtensionStorageKey(context), JSON.stringify(expectedAttempt));
    const send = vi.fn(async () => 'sent');

    await expect(sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context: { ...context, packageId: 'package-2' },
      createIdempotencyKey: () => 'gaming-extension:replacement',
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'session_attempt_conflict');
      return true;
    });
    expect(send).not.toHaveBeenCalled();
    expect(JSON.parse(storage.getItem(paidExtensionStorageKey(context)) ?? '')).toEqual(expectedAttempt);
  });

  it('exposes a valid unresolved receipt on startup so the session lifecycle can be gated', () => {
    const storage = new MemoryStorage();
    storage.setItem(paidExtensionStorageKey(context), JSON.stringify(expectedAttempt));

    expect(inspectPaidExtensionAttemptForSession({
      storageProvider: () => storage,
      scope: context,
    })).toEqual(expectedAttempt);
  });

  it('allows an exact saved replay after the source shift is no longer current', () => {
    expect(paidExtensionSubmissionMode({
      savedAttempt: expectedAttempt,
      requestedPackageId: expectedAttempt.packageId,
      ownsCurrentShift: false,
    })).toBe('replay');
    expect(paidExtensionSubmissionMode({
      savedAttempt: expectedAttempt,
      requestedPackageId: 'package-2',
      ownsCurrentShift: false,
    })).toBe('blocked');
    expect(paidExtensionSubmissionMode({
      savedAttempt: null,
      requestedPackageId: expectedAttempt.packageId,
      ownsCurrentShift: false,
    })).toBe('blocked');
  });

  it('enumerates exact terminal receipts even when their session is no longer on the board', () => {
    const storage = new MemoryStorage();
    storage.setItem(paidExtensionStorageKey(context), JSON.stringify(expectedAttempt));
    storage.setItem(paidExtensionStorageKey({ sessionId: 'session-2' }), JSON.stringify({
      ...expectedAttempt,
      sessionId: 'session-2',
      shiftId: 'shift-2',
      idempotencyKey: 'gaming-extension:attempt-2',
    }));

    expect(inspectPaidExtensionAttemptsForTerminal({
      storageProvider: () => storage,
      scope: context,
    })).toEqual({
      attempts: [
        expectedAttempt,
        {
          ...expectedAttempt,
          sessionId: 'session-2',
          shiftId: 'shift-2',
          idempotencyKey: 'gaming-extension:attempt-2',
        },
      ],
      issueCodes: [],
    });
  });

  it('surfaces legacy, corrupt, and other-scope terminal receipts without deleting them', () => {
    const storage = new MemoryStorage();
    storage.setItem(legacyV2PaidExtensionStorageKey(context), '{}');
    storage.setItem(paidExtensionStorageKey(context), '{broken');
    storage.setItem(paidExtensionStorageKey({ sessionId: 'other-session' }), JSON.stringify({
      ...expectedAttempt,
      actorUserId: 'user-2',
      sessionId: 'other-session',
      idempotencyKey: 'gaming-extension:other',
    }));

    expect(inspectPaidExtensionAttemptsForTerminal({
      storageProvider: () => storage,
      scope: context,
    })).toEqual({
      attempts: [],
      issueCodes: ['legacy_attempt', 'corrupt_attempt', 'scope_mismatch'],
    });
    expect(storage.length).toBe(3);
  });

  it('fails startup inspection closed for a corrupt session-scoped receipt', () => {
    const storage = new MemoryStorage();
    storage.setItem(paidExtensionStorageKey(context), '{broken');

    expect(() => inspectPaidExtensionAttemptForSession({
      storageProvider: () => storage,
      scope: context,
    })).toThrowError(PaidExtensionPersistenceError);
  });

  it('quarantines a corrupt current receipt instead of replacing it', async () => {
    const storage = new MemoryStorage();
    storage.setItem(paidExtensionStorageKey(context), '{broken');
    const createIdempotencyKey = vi.fn(() => 'gaming-extension:replacement');
    const send = vi.fn(async () => 'sent');

    await expect(sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context,
      createIdempotencyKey,
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'corrupt_attempt');
      return true;
    });
    expect(createIdempotencyKey).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();
  });

  it('rejects a readback that differs from the action that was written', async () => {
    const storage = new MemoryStorage();
    const originalSet = storage.setItem.bind(storage);
    storage.setItem = (key, value) => {
      const saved = JSON.parse(value) as PaidExtensionAttempt;
      originalSet(key, JSON.stringify({ ...saved, expectedAmountMinor: saved.expectedAmountMinor + 1 }));
    };
    const send = vi.fn(async () => 'sent');

    await expect(sendDurablyPersistedPaidExtension({
      storageProvider: () => storage,
      context,
      createIdempotencyKey: () => 'gaming-extension:attempt-1',
      send,
    })).rejects.toSatisfy((error: unknown) => {
      expectPersistenceCode(error, 'write_verification_failed');
      return true;
    });
    expect(send).not.toHaveBeenCalled();
  });

  it('requires every versioned field and rejects extra fields', () => {
    for (const field of Object.keys(expectedAttempt)) {
      const incomplete: Record<string, unknown> = { ...expectedAttempt };
      delete incomplete[field];
      expect(parsePaidExtensionAttempt(incomplete), field).toBeNull();
    }
    expect(parsePaidExtensionAttempt({ ...expectedAttempt, unexpected: true })).toBeNull();
  });

  it('clears only the exact confirmed receipt and verifies its removal', () => {
    const storage = new MemoryStorage();
    storage.setItem(paidExtensionStorageKey(context), JSON.stringify(expectedAttempt));

    clearPaidExtensionAttempt({
      storageProvider: () => storage,
      expectedAttempt,
    });

    expect(storage.getItem(paidExtensionStorageKey(context))).toBeNull();
  });

  it('does not clear a receipt whose immutable snapshots changed', () => {
    const storage = new MemoryStorage();
    const changed = { ...expectedAttempt, expectedAmountMinor: 12_001 };
    storage.setItem(paidExtensionStorageKey(context), JSON.stringify(changed));

    expect(() => clearPaidExtensionAttempt({
      storageProvider: () => storage,
      expectedAttempt,
    })).toThrowError(PaidExtensionPersistenceError);
    expect(storage.getItem(paidExtensionStorageKey(context))).toBe(JSON.stringify(changed));
  });

  it('rejects an unverified current shift before reading or writing storage', () => {
    const storageProvider = vi.fn(() => new MemoryStorage());
    expect(() => preparePaidExtensionAttempt({
      storageProvider,
      context: { ...context, shiftId: '' },
      createIdempotencyKey: () => 'gaming-extension:attempt-1',
    })).toThrowError(PaidExtensionPersistenceError);
    expect(storageProvider).not.toHaveBeenCalled();
  });

  it('uses simple device-and-shift guidance for an ordinary context error but keeps real scope conflicts precise', () => {
    const currentContext = paidExtensionPersistenceGuidance(
      new PaidExtensionPersistenceError('current_scope_unverified', 'unverified'),
    );
    const conflictingContext = paidExtensionPersistenceGuidance(
      new PaidExtensionPersistenceError('scope_mismatch', 'mismatch'),
    );

    expect(currentContext).toContain('confirm the current shift is open');
    expect(currentContext).not.toContain('terminal');
    expect(conflictingContext).toContain('original terminal');
  });
});
