import { describe, expect, it, vi } from 'vitest';

import { checkoutClientInstance } from './checkout-client-instance';

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
  };
}

describe('checkout browser identity', () => {
  it('creates one stable UUID for this browser installation', () => {
    const storage = memoryStorage();
    const generated = '123e4567-e89b-42d3-a456-426614174000';
    const randomUUID = vi.fn(() => generated);

    expect(checkoutClientInstance({ storage, randomUUID })).toBe(generated);
    expect(checkoutClientInstance({ storage, randomUUID })).toBe(generated);
    expect(randomUUID).toHaveBeenCalledTimes(1);
  });

  it('replaces malformed storage instead of sending it as lease authority', () => {
    const storage = memoryStorage();
    storage.setItem('dcompany.checkout-client-instance.v1', 'not-a-client-id');
    const generated = '223e4567-e89b-42d3-a456-426614174000';

    expect(checkoutClientInstance({ storage, randomUUID: () => generated })).toBe(generated);
  });

  it('fails closed when durable browser storage cannot be verified', () => {
    const storage = memoryStorage();
    storage.setItem = () => { throw new Error('blocked'); };

    expect(() => checkoutClientInstance({
      storage,
      randomUUID: () => '323e4567-e89b-42d3-a456-426614174000',
    })).toThrow('Secure checkout storage is unavailable');
  });
});
