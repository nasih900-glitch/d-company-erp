const CHECKOUT_CLIENT_INSTANCE_KEY = 'dcompany.checkout-client-instance.v1';
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

interface CheckoutClientInstanceDependencies {
  storage?: Pick<Storage, 'getItem' | 'setItem'>;
  randomUUID?: () => string;
}

/**
 * Stable identity for this browser installation's checkout lease.
 *
 * A user and terminal are not a client identity: the same staff login can be
 * open on a tablet and a browser at once. The server stores only a SHA-256
 * digest of this UUID and uses it to prevent a second physical client from
 * renewing or rotating the first client's active bill lock.
 */
export function checkoutClientInstance(
  dependencies: CheckoutClientInstanceDependencies = {},
): string {
  try {
    const storage = dependencies.storage ?? globalThis.localStorage;
    const randomUUID = dependencies.randomUUID ?? globalThis.crypto?.randomUUID?.bind(globalThis.crypto);
    const existing = storage
      .getItem(CHECKOUT_CLIENT_INSTANCE_KEY)
      ?.trim()
      .toLowerCase();
    if (existing && CANONICAL_UUID.test(existing)) return existing;

    if (!randomUUID) {
      throw new Error('Secure browser identity generation is unavailable.');
    }
    const generated = randomUUID().toLowerCase();
    storage.setItem(CHECKOUT_CLIENT_INSTANCE_KEY, generated);
    if (storage.getItem(CHECKOUT_CLIENT_INSTANCE_KEY) !== generated) {
      throw new Error('Browser identity storage could not be verified.');
    }
    return generated;
  } catch {
    throw new Error(
      'Secure checkout storage is unavailable. Enable browser storage and reload before collecting payment.',
    );
  }
}
