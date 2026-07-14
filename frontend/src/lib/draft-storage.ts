/**
 * Tiny localStorage helper for in-progress, not-yet-saved UI state (a cart
 * being built, an order being resumed) — so a refresh never loses it. Only
 * ever stores small, non-sensitive JSON (ids/quantities), never card/payment
 * data.
 */

export function saveDraft<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Storage unavailable (private browsing, quota) — draft just won't survive a refresh.
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
