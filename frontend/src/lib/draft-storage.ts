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

export function clearDraft(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // Best-effort cleanup.
  }
}
