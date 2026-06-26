export const APP_STORE_REVIEW = import.meta.env.VITE_APP_STORE_REVIEW === 'true';

const APP_STORE_BLOCKED_TYPES = new Set(['hookah', 'tobacco']);

export function isAppStoreAllowedType(type: string): boolean {
  return !APP_STORE_REVIEW || !APP_STORE_BLOCKED_TYPES.has(type.toLowerCase());
}
