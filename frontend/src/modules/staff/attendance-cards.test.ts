import { expect, it } from 'vitest';
import type { AttendanceCardsPage } from '@/lib/staff-time-api';
import { attendanceDate, attendanceEnd, attendanceStart, mergeAttendanceCards, validateAttendanceCards } from './attendance-cards';
const actor = '11111111-1111-4111-8111-111111111111';
const branch = '33333333-3333-4333-8333-333333333333';
function page(): AttendanceCardsPage {
  return { period: 'week', period_start: '2026-11-02', period_end_exclusive: '2026-11-09', timezone: 'Asia/Dubai',
    as_of: '2026-11-05T00:00:00Z', scope_branch_id: branch, current_open_attendance: null,
    next_cursor: 'firstCursor', items: [{ user_id: actor, name: 'Synthetic', clock_in_day_count: 4,
      entries: [{ id: '44444444-4444-4444-8444-444444444444', user_id: actor, branch_id: branch,
        local_date: '2026-11-02', clock_in_at: '2026-11-02T00:00:00Z', clock_out_at: null, is_open: true }],
      continued_from_previous: false, entries_continued: true }] };
}
it('uses Monday and calendar month based on company-local as_of including UTC boundary', () => {
  expect(attendanceStart('week', attendanceDate('2026-11-01T21:00:00Z', 'Asia/Dubai'))).toBe('2026-11-02');
  expect(attendanceStart('month', attendanceDate('2026-11-30T21:00:00Z', 'Asia/Dubai'))).toBe('2026-12-01');
  expect(attendanceEnd('month', '2026-12-01')).toBe('2027-01-01');
});
it('retains server full-period count and exactly merges split person without duplicate entries', () => {
  const first = page(); validateAttendanceCards(first, 'week', '2026-11-02', actor, branch);
  const next = { ...first, next_cursor: null, items: [{ ...first.items[0], continued_from_previous: true, entries_continued: false,
    entries: [{ ...first.items[0].entries[0], id: '55555555-5555-4555-8555-555555555555', local_date: '2026-11-03', clock_in_at: '2026-11-03T00:00:00Z' }] }] };
  validateAttendanceCards(next, 'week', '2026-11-02', actor, branch, true);
  const joined = mergeAttendanceCards(first, next);
  expect(joined.items[0].clock_in_day_count).toBe(4); expect(joined.items[0].entries).toHaveLength(2);
  expect(() => mergeAttendanceCards(first, { ...next, items: first.items })).toThrow();
});
it('rejects wrong branch period fact identity and mismatched open flags', () => {
  const p = page();
  for (const bad of [{ ...p, scope_branch_id: actor }, { ...p, period_start: '2026-11-03' },
    { ...p, current_open_attendance: { ...p.items[0].entries[0], user_id: branch } },
    { ...p, items: [{ ...p.items[0], entries: [{ ...p.items[0].entries[0], is_open: false }] }] }]) {
    expect(() => validateAttendanceCards(bad, 'week', '2026-11-02', actor, branch)).toThrow();
  }
});

it('preserves submillisecond arrival ordering and rejects reversed records', () => {
  const p = page(); const row = p.items[0].entries[0];
  const first = { ...row, clock_in_at: '2026-11-02T00:00:00.000001Z' };
  const second = { ...row, id: '55555555-5555-4555-8555-555555555555', clock_in_at: '2026-11-02T00:00:00.000002Z' };
  const valid = { ...p, items: [{ ...p.items[0], entries: [first, second] }] };
  validateAttendanceCards(valid, 'week', '2026-11-02', actor, branch);
  expect(() => validateAttendanceCards({ ...p, items: [{ ...p.items[0], entries: [second, first] }] }, 'week', '2026-11-02', actor, branch)).toThrow();
});

function validatePair(first: AttendanceCardsPage, next: AttendanceCardsPage) {
  validateAttendanceCards(first, 'week', '2026-11-02', actor, branch);
  validateAttendanceCards(next, 'week', '2026-11-02', actor, branch, true);
}
it('rejects promised continuation switching person while allowing a completed person boundary', () => {
  const first = page(); const other = '66666666-6666-4666-8666-666666666666';
  const next = { ...first, next_cursor: null, items: [{ ...first.items[0], user_id: other, clock_in_day_count: 1,
    entries: [{ ...first.items[0].entries[0], id: '77777777-7777-4777-8777-777777777777', user_id: other }],
    continued_from_previous: false, entries_continued: false }] };
  validatePair(first, next); expect(() => mergeAttendanceCards(first, next)).toThrow();
  const ended = { ...first, items: [{ ...first.items[0], entries_continued: false }] };
  validatePair(ended, next); expect(mergeAttendanceCards(ended, next).items).toHaveLength(2);
});
it('rejects empty terminal page while a person continuation is promised', () => {
  const first = page(); const empty = { ...first, items: [], next_cursor: null };
  validatePair(first, empty); expect(() => mergeAttendanceCards(first, empty)).toThrow();
  const ended = { ...first, items: [{ ...first.items[0], entries_continued: false }] };
  expect(mergeAttendanceCards(ended, empty).items[0].entries_continued).toBe(false);
});
it('rejects merged distinct local days exceeding unchanged full count but accepts same-day visits', () => {
  const first = { ...page(), items: [{ ...page().items[0], clock_in_day_count: 1 }] };
  const next = { ...first, next_cursor: null, items: [{ ...first.items[0], continued_from_previous: true, entries_continued: false,
    entries: [{ ...first.items[0].entries[0], id: '55555555-5555-4555-8555-555555555555', local_date: '2026-11-03', clock_in_at: '2026-11-03T00:00:00Z' }] }] };
  validatePair(first, next); expect(() => mergeAttendanceCards(first, next)).toThrow();
  const sameDay = { ...next, items: [{ ...next.items[0], entries: [{ ...next.items[0].entries[0], local_date: '2026-11-02', clock_in_at: first.items[0].entries[0].clock_in_at }] }] };
  validatePair(first, sameDay); expect(mergeAttendanceCards(first, sameDay).items[0].entries).toHaveLength(2);
});
it('keeps equal-time UUID order strict and rejects nonfinal or cursorless continuation shape', () => {
  const first = page();
  const next = { ...first, next_cursor: null, items: [{ ...first.items[0], entries: [{ ...first.items[0].entries[0], id: '33333333-3333-4333-8333-333333333333' }],
    continued_from_previous: true, entries_continued: false }] };
  validatePair(first, next); expect(() => mergeAttendanceCards(first, next)).toThrow();
  const invalid = { ...first, items: [...first.items, { ...first.items[0], user_id: '77777777-7777-4777-8777-777777777777' }] };
  expect(() => mergeAttendanceCards(invalid, { ...first, items: [], next_cursor: null })).toThrow();
});
