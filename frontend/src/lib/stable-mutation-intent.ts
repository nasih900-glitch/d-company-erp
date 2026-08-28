export interface MutationIntentStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface StableMutationIntentValue<TPayload> {
  version: 1;
  idempotencyKey: string;
  fingerprint: string;
  payload: TPayload;
}

interface StableMutationIntentOptions<TPayload> {
  prefix: string;
  storage?: MutationIntentStorage | null;
  storageKey?: string;
  keyFactory?: (prefix: string) => string;
  isPayload?: (value: unknown) => value is TPayload;
}

function defaultKeyFactory(prefix: string): string {
  const random = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}:${random}`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isStoredIntent<TPayload>(
  value: unknown,
  prefix: string,
  isPayload: ((payload: unknown) => payload is TPayload) | undefined,
): value is StableMutationIntentValue<TPayload> {
  if (!isRecord(value)) return false;
  return value.version === 1
    && typeof value.idempotencyKey === 'string'
    && value.idempotencyKey.length > 0
    && value.idempotencyKey.length <= 160
    && value.idempotencyKey.startsWith(`${prefix}:`)
    && typeof value.fingerprint === 'string'
    && value.fingerprint.length > 0
    && (isPayload ? isPayload(value.payload) : value.payload !== undefined);
}

/**
 * Owns the identity of one mutating user intent.
 *
 * A request may have committed even when the client sees a timeout. For that
 * reason `resolve` reuses both the idempotency key and the frozen payload until
 * the caller explicitly invalidates the intent or confirms success. Optional
 * storage makes the same operation recoverable after a page reload.
 */
export class StableMutationIntent<TPayload> {
  private current: StableMutationIntentValue<TPayload> | null = null;
  private readonly prefix: string;
  private readonly storage: MutationIntentStorage | null;
  private readonly storageKey: string | null;
  private readonly keyFactory: (prefix: string) => string;
  private readonly isPayload?: (value: unknown) => value is TPayload;

  constructor(options: StableMutationIntentOptions<TPayload>) {
    this.prefix = options.prefix;
    this.storage = options.storage ?? null;
    this.storageKey = options.storageKey ?? null;
    this.keyFactory = options.keyFactory ?? defaultKeyFactory;
    this.isPayload = options.isPayload;
    this.current = this.restore();
  }

  resolve(fingerprint: string, createPayload: () => TPayload): StableMutationIntentValue<TPayload> {
    if (!fingerprint) throw new Error('A stable mutation intent requires a fingerprint.');
    if (this.current?.fingerprint === fingerprint) return this.current;

    const next: StableMutationIntentValue<TPayload> = {
      version: 1,
      idempotencyKey: this.keyFactory(this.prefix),
      fingerprint,
      payload: createPayload(),
    };
    this.current = next;
    this.persist(next);
    return next;
  }

  peek(): StableMutationIntentValue<TPayload> | null {
    return this.current;
  }

  invalidate(): void {
    this.current = null;
    if (!this.storage || !this.storageKey) return;
    try {
      this.storage.removeItem(this.storageKey);
    } catch {
      // Storage is a durability enhancement. The in-memory operation is still
      // invalidated if a browser privacy setting makes localStorage unavailable.
    }
  }

  confirmSuccess(expected?: StableMutationIntentValue<TPayload>): void {
    if (expected && this.current?.idempotencyKey !== expected.idempotencyKey) return;
    this.invalidate();
  }

  private restore(): StableMutationIntentValue<TPayload> | null {
    if (!this.storage || !this.storageKey) return null;
    try {
      const raw = this.storage.getItem(this.storageKey);
      if (!raw) return null;
      const parsed: unknown = JSON.parse(raw);
      if (isStoredIntent(parsed, this.prefix, this.isPayload)) return parsed;
      this.storage.removeItem(this.storageKey);
    } catch {
      try { this.storage.removeItem(this.storageKey); } catch { /* unavailable storage */ }
    }
    return null;
  }

  private persist(value: StableMutationIntentValue<TPayload>): void {
    if (!this.storage || !this.storageKey) return;
    try {
      this.storage.setItem(this.storageKey, JSON.stringify(value));
    } catch {
      // The same mounted controller still provides safe retries when storage
      // is full or disabled; only cross-reload recovery is unavailable.
    }
  }
}

export function availableLocalStorage(): MutationIntentStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
