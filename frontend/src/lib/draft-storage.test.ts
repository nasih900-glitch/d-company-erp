import { afterEach, describe, expect, it } from 'vitest';

import { clearDraft, loadDraft, saveDraft } from './draft-storage';

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
});
