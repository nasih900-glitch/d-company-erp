/**
 * Tiny localStorage helper for in-progress, not-yet-saved UI state (a cart
 * being built, an order being resumed) — so a refresh never loses it. Payloads
 * stay small and never include card details, access tokens, or payment
 * credentials. POS recovery can include the attached customer name/phone plus
 * the method and exact amount, so callers must scope and clear their keys.
 */

export function saveDraft<T>(key: string, value: T): boolean {
  try {
    const serialized = JSON.stringify(value);
    localStorage.setItem(key, serialized);
    return localStorage.getItem(key) === serialized;
  } catch {
    return false;
  }
}

export function loadDraft<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

export interface DraftStorageSnapshot<T> {
  value: T | null;
  /** Exact serialized value used as the compare-and-swap token. */
  token: string | null;
}

export type DraftStorageMutation =
  | { ok: true; token: string | null }
  | { ok: false; reason: 'conflict' | 'unavailable'; token: string | null };

/**
 * Load both the parsed draft and its exact storage token. Callers that can be
 * open in multiple browser tabs must use the token for every later mutation;
 * otherwise an empty/stale tab can overwrite another tab's only cart copy.
 */
export function loadDraftSnapshot<T>(key: string): DraftStorageSnapshot<T> {
  try {
    const token = localStorage.getItem(key);
    if (token === null) return { value: null, token: null };
    try {
      return { value: JSON.parse(token) as T, token };
    } catch {
      // Keep the token even when corrupt so callers cannot silently overwrite
      // evidence they did not successfully hydrate.
      return { value: null, token };
    }
  } catch {
    return { value: null, token: null };
  }
}

export function saveDraftIfUnchanged<T>(
  key: string,
  value: T,
  expectedToken: string | null,
): DraftStorageMutation {
  try {
    const currentToken = localStorage.getItem(key);
    if (currentToken !== expectedToken) {
      return { ok: false, reason: 'conflict', token: currentToken };
    }
    const token = JSON.stringify(value);
    localStorage.setItem(key, token);
    return localStorage.getItem(key) === token
      ? { ok: true, token }
      : { ok: false, reason: 'unavailable', token: localStorage.getItem(key) };
  } catch {
    return { ok: false, reason: 'unavailable', token: null };
  }
}

export function clearDraftIfUnchanged(
  key: string,
  expectedToken: string | null,
): DraftStorageMutation {
  try {
    const currentToken = localStorage.getItem(key);
    if (currentToken !== expectedToken) {
      return { ok: false, reason: 'conflict', token: currentToken };
    }
    localStorage.removeItem(key);
    return localStorage.getItem(key) === null
      ? { ok: true, token: null }
      : { ok: false, reason: 'unavailable', token: localStorage.getItem(key) };
  } catch {
    return { ok: false, reason: 'unavailable', token: null };
  }
}

export function clearDraft(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // Best-effort cleanup.
  }
}
