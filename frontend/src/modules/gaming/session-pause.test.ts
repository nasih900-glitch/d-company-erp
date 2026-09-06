import { describe, expect, it } from 'vitest';
import { mayApplyRunningSessionReceipt, playedSessionMilliseconds, serverPauseClock } from './session-pause';
import { runningBillMinor } from './running-bill';

describe('authoritative shared gaming pause clock', () => {
  const start = Date.parse('2026-09-05T10:00:00Z');
  const paused = Date.parse('2026-09-05T10:03:30.500Z');

  it('preserves sub-minute precision and does not double subtract legacy minutes', () => {
    const clock = serverPauseClock({ status: 'active', paused_minutes: 2, paused_duration_ms: 125_500, pause_version: 4 });
    expect(clock).toEqual({ pausedMs: 125_500, pause_started_at: undefined, pause_version: 4 });
    expect(playedSessionMilliseconds({ start_at: start, status: 'active', ...clock }, start + 300_000)).toBe(174_500);
  });

  it('freezes a paused clock after reload and on a second device', () => {
    const snapshot = { status: 'paused' as const, paused_minutes: 0, paused_duration_ms: 30_250, paused_at: new Date(paused).toISOString(), pause_version: 3 };
    const firstDevice = { start_at: start, status: snapshot.status, ...serverPauseClock(snapshot) };
    const secondDeviceAfterReload = { start_at: start, status: snapshot.status, ...serverPauseClock(JSON.parse(JSON.stringify(snapshot))) };
    expect(playedSessionMilliseconds(firstDevice, paused + 1_000)).toBe(180_250);
    expect(playedSessionMilliseconds(secondDeviceAfterReload, paused + 3_600_000)).toBe(180_250);
  });

  it('converts legacy whole minutes only when modern milliseconds are absent', () => {
    expect(serverPauseClock({ status: 'active', paused_minutes: 3 }).pausedMs).toBe(180_000);
    expect(serverPauseClock({ status: 'active', paused_minutes: 3, paused_duration_ms: 0 }).pausedMs).toBe(0);
  });

  it('refuses to manufacture elapsed billing from missing or malformed pause time', () => {
    for (const paused_at of [undefined, 'not-a-date']) {
      const clock = serverPauseClock({ status: 'paused', paused_minutes: 0, paused_at });
      const elapsedMs = playedSessionMilliseconds({ start_at: start, status: 'paused', ...clock }, paused);
      expect(elapsedMs).toBeNaN();
      expect(runningBillMinor({ billingMode: 'hourly', ratePerHourMinor: 12_000, elapsedMs })).toBeNull();
      expect(runningBillMinor({ billingMode: 'package', lockedAmountMinor: 8_000, elapsedMs })).toBe(8_000);
    }
  });

  it('does not turn malformed modern fields into zero pause or permit unknown versions', () => {
    expect(serverPauseClock({ status: 'active', paused_minutes: 0, paused_duration_ms: -1 }).pausedMs).toBeNaN();
    expect(serverPauseClock({ status: 'active', paused_minutes: 0, pause_version: -1 }).pause_version).toBeUndefined();
    expect(serverPauseClock({ status: 'active', paused_minutes: 0, pause_version: 0 }).pause_version).toBe(0);
  });

  it('continues from the frozen point on resume with accumulated milliseconds', () => {
    const pauseDuration = 75_125;
    const resumedAt = paused + pauseDuration;
    const clock = serverPauseClock({ status: 'active', paused_minutes: 0, paused_duration_ms: pauseDuration, pause_version: 2 });
    const session = { start_at: start, status: 'active', ...clock };
    expect(playedSessionMilliseconds(session, resumedAt)).toBe(paused - start);
    expect(playedSessionMilliseconds(session, resumedAt + 1_000)).toBe(paused - start + 1_000);
  });

  it('rejects delayed timer receipts after a later pause cycle, stop, or station replacement', () => {
    const receipt = { id: 'session-a', status: 'active' as const, pause_version: 2 };
    expect(mayApplyRunningSessionReceipt({ backend_session_id: 'session-a', status: 'paused', pause_version: 3 }, receipt)).toBe(false);
    expect(mayApplyRunningSessionReceipt({ backend_session_id: 'session-a', status: 'ended', pause_version: 2 }, receipt)).toBe(false);
    expect(mayApplyRunningSessionReceipt({ backend_session_id: 'session-b', status: 'active', pause_version: 0 }, receipt)).toBe(false);
    expect(mayApplyRunningSessionReceipt({ backend_session_id: 'session-a', status: 'active', pause_version: 2 }, { ...receipt, status: 'ended' })).toBe(false);
    expect(mayApplyRunningSessionReceipt({ backend_session_id: 'session-a', status: 'paused', pause_version: 1 }, receipt)).toBe(true);
  });
});
