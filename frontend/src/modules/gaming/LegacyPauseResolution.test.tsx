import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import {
  canResolveLegacyPausedSession,
  hasLegacyPausedTimingGap,
  LegacyPauseResolutionModal,
  validateLegacyPauseResolutionForm,
} from './GamingScreen';

const exactLegacyPause = {
  station_id: 'station-1',
  shift_id: 'shift-1',
  start_at: new Date('2026-08-25T10:00:00Z').getTime(),
  status: 'paused' as const,
  pausedMs: 180_000,
  pause_version: 0,
  paused_at: null,
  paused_duration_ms: 180_000,
  last_pause_transition_at: null,
  end_at: null,
  billable_minutes: null,
  amount_minor: null,
  order_id: null,
  backend_session_id: 'session-1',
  timer_minutes: 60,
  timer_ends_at: null,
  billing_mode: 'hourly' as const,
  rate_per_hour_minor: 10_000,
};

const station = {
  id: 'station-1',
  branch_id: 'branch-1',
  code: 'PS5-1',
  name: 'PS5 Station 1',
  type: 'ps5' as const,
  rate_per_hour_minor: 10_000,
  is_active: true,
};

describe('protected legacy paused-session recovery', () => {
  it('offers recovery only to the protected audit owner for the exact unresolved state', () => {
    expect(hasLegacyPausedTimingGap(exactLegacyPause)).toBe(true);
    expect(canResolveLegacyPausedSession(exactLegacyPause, true)).toBe(true);
    expect(canResolveLegacyPausedSession(exactLegacyPause, false)).toBe(false);

    for (const changed of [
      { paused_at: '2026-08-25T10:10:00Z' },
      { pause_version: 1 },
      { last_pause_transition_at: '2026-08-25T10:10:00Z' },
      { end_at: '2026-08-25T11:00:00Z' },
      { billable_minutes: 50 },
      { order_id: 'order-1' },
      { paused_duration_ms: undefined },
      { amount_minor: undefined },
    ]) {
      expect(hasLegacyPausedTimingGap({ ...exactLegacyPause, ...changed })).toBe(false);
    }
  });

  it('requires reviewed time, integer minutes, amount, reason, and acknowledgement', () => {
    const valid = validateLegacyPauseResolutionForm({
      endedAtLocal: '2026-08-25T11:00',
      sessionStartedAt: exactLegacyPause.start_at,
      billableMinutes: '57',
      amountRupees: '95.50',
      reason: ' Reviewed the written station log ',
      timingEvidenceReviewed: true,
    });
    expect(valid).toMatchObject({
      ok: true,
      value: {
        billable_minutes: 57,
        amount_minor: 9_550,
        timing_evidence_reviewed: true,
        reason: 'Reviewed the written station log',
      },
    });

    expect(validateLegacyPauseResolutionForm({
      endedAtLocal: '',
      sessionStartedAt: exactLegacyPause.start_at,
      billableMinutes: '57',
      amountRupees: '95.50',
      reason: 'Reviewed written log',
      timingEvidenceReviewed: true,
    })).toEqual({ ok: false, error: 'Enter the reviewed final session time.' });
    expect(validateLegacyPauseResolutionForm({
      endedAtLocal: '2026-08-25T11:00',
      sessionStartedAt: exactLegacyPause.start_at,
      billableMinutes: '57.5',
      amountRupees: '95.50',
      reason: 'Reviewed written log',
      timingEvidenceReviewed: true,
    })).toEqual({ ok: false, error: 'Enter billable minutes as a whole non-negative number.' });
    expect(validateLegacyPauseResolutionForm({
      endedAtLocal: '2026-08-25T11:00',
      sessionStartedAt: exactLegacyPause.start_at,
      billableMinutes: '57',
      amountRupees: '95.50',
      reason: 'Reviewed written log',
      timingEvidenceReviewed: false,
    })).toEqual({ ok: false, error: 'Confirm that you reviewed the timing evidence.' });
  });

  it('renders the protected evidence fields and keeps a server cross-check error visible', () => {
    const markup = renderToStaticMarkup(
      <LegacyPauseResolutionModal
        station={station}
        session={exactLegacyPause}
        busy={false}
        requestError="Final amount does not match the locked package ledger."
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    expect(markup).toContain('Protected owner recovery');
    expect(markup).toContain('Reviewed final time');
    expect(markup).toContain('Billable played minutes');
    expect(markup).toContain('Verified final amount');
    expect(markup).toContain('Audit reason and evidence source');
    expect(markup).toContain('I reviewed the timing evidence');
    expect(markup).toContain('Final amount does not match the locked package ledger.');
    expect(markup).toMatch(/End with audited evidence/);
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*type="submit"|<button[^>]*type="submit"[^>]*disabled=""/);
  });
});
