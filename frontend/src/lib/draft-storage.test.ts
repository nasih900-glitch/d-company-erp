import { afterEach, describe, expect, it } from 'vitest';

import {
  clearDraft,
  clearDraftIfUnchanged,
  loadDraft,
  loadDraftSnapshot,
  saveDraft,
  saveDraftIfUnchanged,
} from './draft-storage';

const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');

function installStorage(storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>) {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: storage,
  });
}

afterEach(() => {
  if (originalStorage) {
    Object.defineProperty(globalThis, 'localStorage', originalStorage);
  } else {
    Reflect.deleteProperty(globalThis, 'localStorage');
  }
});

describe('draft storage', () => {
  it('reports success only after reading back the exact serialized journal', () => {
    const values = new Map<string, string>();
    installStorage({
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => { values.set(key, value); },
      removeItem: (key) => { values.delete(key); },
    });

    expect(saveDraft('checkout', { phase: 'recording_payment', amount: 42000 })).toBe(true);
    expect(loadDraft('checkout')).toEqual({ phase: 'recording_payment', amount: 42000 });
    clearDraft('checkout');
    expect(loadDraft('checkout')).toBeNull();
  });

  it('reports failure when storage rejects or does not retain a critical journal write', () => {
    installStorage({
      getItem: () => null,
      setItem: () => undefined,
      removeItem: () => undefined,
    });
    expect(saveDraft('checkout', { phase: 'preparing_order' })).toBe(false);

    installStorage({
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => { throw new Error('blocked'); },
    });
    expect(saveDraft('checkout', { phase: 'recording_payment' })).toBe(false);
    expect(loadDraft('checkout')).toBeNull();
    expect(() => clearDraft('checkout')).not.toThrow();
  });

  it('prevents a stale or empty browser tab from overwriting another tab draft', () => {
    const values = new Map<string, string>();
    installStorage({
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => { values.set(key, value); },
      removeItem: (key) => { values.delete(key); },
    });

    const tabOne = loadDraftSnapshot<{ cart: string[] }>('pos');
    const tabTwo = loadDraftSnapshot<{ cart: string[] }>('pos');
    expect(tabOne.token).toBeNull();
    expect(tabTwo.token).toBeNull();

    const firstWrite = saveDraftIfUnchanged('pos', { cart: ['drink'] }, tabOne.token);
    expect(firstWrite.ok).toBe(true);

    expect(saveDraftIfUnchanged('pos', { cart: ['crisps'] }, tabTwo.token)).toMatchObject({
      ok: false,
      reason: 'conflict',
    });
    expect(clearDraftIfUnchanged('pos', tabTwo.token)).toMatchObject({
      ok: false,
      reason: 'conflict',
    });
    expect(loadDraft('pos')).toEqual({ cart: ['drink'] });
  });

  it('advances the token after each owned write and clear', () => {
    const values = new Map<string, string>();
    installStorage({
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => { values.set(key, value); },
      removeItem: (key) => { values.delete(key); },
    });

    const saved = saveDraftIfUnchanged('pos', { phase: 'cart' }, null);
    expect(saved.ok).toBe(true);
    if (!saved.ok) return;
    expect(clearDraftIfUnchanged('pos', saved.token)).toEqual({ ok: true, token: null });
    expect(loadDraftSnapshot('pos')).toEqual({ value: null, token: null });
  });
});
