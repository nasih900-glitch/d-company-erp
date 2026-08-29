export type RunningBillSnapshot = {
  billingMode?: 'hourly' | 'package' | 'legacy_ambiguous';
  lockedAmountMinor?: number | null;
  ratePerHourMinor?: number | null;
  elapsedMs: number;
};

/**
 * Mirrors the backend's live-session billing preview without inventing a rate.
 *
 * The final Stop response remains authoritative. Hourly billing rounds played
 * time up to a whole minute first, then rounds the paise amount up; applying a
 * continuous elapsed-hours formula here can materially underquote short play.
 */
export function runningBillMinor(snapshot: RunningBillSnapshot): number | null {
  if (snapshot.billingMode === 'legacy_ambiguous') return null;

  if (snapshot.billingMode === 'package') {
    const locked = snapshot.lockedAmountMinor;
    return locked != null && Number.isSafeInteger(locked) && locked >= 0 ? locked : null;
  }

  const rate = snapshot.ratePerHourMinor;
  if (rate == null || !Number.isSafeInteger(rate) || rate < 0) return null;

  const elapsedMs = Number.isFinite(snapshot.elapsedMs)
    ? Math.max(0, snapshot.elapsedMs)
    : 0;
  const billableMinutes = elapsedMs > 0 ? Math.ceil(elapsedMs / 60_000) : 0;
  return Math.ceil((billableMinutes * rate) / 60);
}
