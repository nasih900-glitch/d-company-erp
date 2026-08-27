export function sessionTimerMinutesForLocalState({
  liveMode,
  requestedTimerMinutes,
  serverTimerMinutes,
}: {
  liveMode: boolean;
  requestedTimerMinutes: number | null;
  serverTimerMinutes: number | null;
}): number | null {
  // The backend's timer_minutes is the immutable compare-and-swap snapshot
  // used by paid extension. Never reconstruct it from timer_ends_at and a
  // client clock: latency or clock skew can change the rounded minute.
  return liveMode ? serverTimerMinutes : requestedTimerMinutes;
}
