import { describe, expect, it } from 'vitest';

import { sessionTimerMinutesForLocalState } from './session-start-snapshot';

describe('gaming start session snapshots', () => {
  it('retains the authoritative server timer despite response latency or client clock skew', () => {
    const serverTimerMinutes = 60;
    const timerEndsAt = Date.parse('2026-08-27T12:00:00.000Z');
    const delayedClientNow = Date.parse('2026-08-27T11:00:31.000Z');
    const reconstructedFromClock = Math.round((timerEndsAt - delayedClientNow) / 60_000);

    expect(reconstructedFromClock).toBe(59);
    expect(sessionTimerMinutesForLocalState({
      liveMode: true,
      requestedTimerMinutes: null,
      serverTimerMinutes,
    })).toBe(60);
  });
});
