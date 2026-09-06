import type { GameSessionDTO } from '@/lib/erp-api';

type PauseSnapshot = Pick<GameSessionDTO, 'status' | 'paused_minutes' | 'paused_at' | 'paused_duration_ms' | 'pause_version'>;

/** Modern milliseconds already include legacy minutes; never subtract both. */
export function serverPauseClock(session: PauseSnapshot) {
  const modern = session.paused_duration_ms;
  const pausedMs = modern === undefined
    ? Math.max(0, session.paused_minutes) * 60_000
    : Number.isSafeInteger(modern) && modern >= 0 ? modern : Number.NaN;
  const started = session.status === 'paused' && session.paused_at
    ? new Date(session.paused_at).getTime() : undefined;
  return {
    pausedMs,
    pause_started_at: started,
    pause_version: Number.isSafeInteger(session.pause_version) && (session.pause_version ?? -1) >= 0
      ? session.pause_version : undefined,
  };
}

export function playedSessionMilliseconds(session: {
  start_at: number; status: string; pausedMs: number; pause_started_at?: number;
}, now: number): number {
  const until = session.status === 'paused' ? session.pause_started_at : now;
  if (until === undefined || !Number.isFinite(until) || !Number.isFinite(session.start_at)
    || !Number.isFinite(session.pausedMs)) return Number.NaN;
  return Math.max(0, until - session.start_at - session.pausedMs);
}

/** A delayed timer/pause receipt cannot revive an ended or newer pause cycle. */
export function mayApplyRunningSessionReceipt(
  current: { backend_session_id?: string; status: string; pause_version?: number } | undefined,
  response: Pick<GameSessionDTO, 'id' | 'status' | 'pause_version'>,
): boolean {
  return current?.backend_session_id === response.id
    && current.status !== 'ended'
    && ['active', 'paused'].includes(response.status)
    && (current.pause_version ?? -1) <= (response.pause_version ?? -1);
}
