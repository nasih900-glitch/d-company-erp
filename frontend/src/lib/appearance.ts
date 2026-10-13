export type AppearancePreference = 'system' | 'day' | 'night';

const APPEARANCE_STORAGE_KEY = 'dcompany-appearance-v1';

export function readAppearancePreference(
  storage?: Pick<Storage, 'getItem'>,
): AppearancePreference {
  try {
    const source = storage ?? (typeof window === 'undefined' ? undefined : window.localStorage);
    const value = source?.getItem(APPEARANCE_STORAGE_KEY);
    return value === 'day' || value === 'night' ? value : 'system';
  } catch {
    return 'system';
  }
}

export function applyAppearancePreference(preference: AppearancePreference): void {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  try {
    if (preference === 'system') root.removeAttribute('data-theme');
    else root.dataset.theme = preference;
  } catch {
    // The in-memory preference remains authoritative for this render even if
    // a locked-down document prevents mutating the root attribute.
  }
}

export function saveAppearancePreference(
  preference: AppearancePreference,
  storage?: Pick<Storage, 'setItem' | 'removeItem'>,
): void {
  try {
    const target = storage ?? (typeof window === 'undefined' ? undefined : window.localStorage);
    if (preference === 'system') target?.removeItem(APPEARANCE_STORAGE_KEY);
    else target?.setItem(APPEARANCE_STORAGE_KEY, preference);
  } catch {
    // Storage can be disabled or throw through its property getter. Applying
    // the preference must still work for the current session.
  }
  applyAppearancePreference(preference);
}

export function nextAppearancePreference(
  preference: AppearancePreference,
): AppearancePreference {
  if (preference === 'system') return 'day';
  if (preference === 'day') return 'night';
  return 'system';
}
