import type { AttendanceCardsPage, AttendanceClockFact } from '@/lib/staff-time-api';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function requireFact(ok: unknown): asserts ok {
  if (!ok) throw new Error('Clock-in records could not be verified. Refresh this period.');
}
export function attendanceDate(value: string, timezone: string): string {
  const date = new Date(value);
  requireFact(Number.isFinite(date.getTime()));
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date);
  return ['year', 'month', 'day'].map(name => parts.find(p => p.type === name)!.value).join('-');
}
function dateOnly(value: string): Date {
  requireFact(/^\d{4}-\d{2}-\d{2}$/.test(value));
  const date = new Date(`${value}T00:00:00Z`);
  requireFact(Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value);
  return date;
}
export function attendanceStart(period: 'week' | 'month', day: string): string {
  const date = dateOnly(day);
  if (period === 'month') date.setUTCDate(1);
  else date.setUTCDate(date.getUTCDate() - (date.getUTCDay() + 6) % 7);
  const result = date.toISOString().slice(0, 10);
  requireFact(date.getUTCFullYear() >= 2000 && date.getUTCFullYear() <= 2100);
  return result;
}
export function attendanceEnd(period: 'week' | 'month', start: string): string {
  const date = dateOnly(start);
  if (period === 'month') date.setUTCMonth(date.getUTCMonth() + 1);
  else date.setUTCDate(date.getUTCDate() + 7);
  return date.toISOString().slice(0, 10);
}
export function adjacentAttendance(period: 'week' | 'month', start: string, direction: number): string {
  const date = dateOnly(start);
  if (period === 'month') date.setUTCMonth(date.getUTCMonth() + direction);
  else date.setUTCDate(date.getUTCDate() + 7 * direction);
  return attendanceStart(period, date.toISOString().slice(0, 10));
}
// Preserve the server's microsecond ordering; JavaScript Date alone truncates it.
function compareClock(left: string, right: string): number {
  const key = (value: string): [number, string] => {
    const parts = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/.exec(value);
    requireFact(parts);
    const second = Date.parse(parts[1] + parts[3]); requireFact(Number.isFinite(second));
    return [second, (parts[2] ?? '').padEnd(9, '0')];
  };
  const a = key(left), b = key(right);
  return a[0] !== b[0] ? Math.sign(a[0] - b[0]) : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0;
}
export function validateAttendanceCards(page: AttendanceCardsPage, period: 'week' | 'month', start: string,
  actor: string, branch: string | null, more = false): void {
  requireFact(page.period === period && page.period_start === start && attendanceStart(period, start) === start);
  const end = attendanceEnd(period, start);
  requireFact(page.period_end_exclusive === end && page.scope_branch_id === branch);
  attendanceDate(page.as_of, page.timezone);
  const fact = (row: AttendanceClockFact) => {
    requireFact(uuid.test(row.id) && uuid.test(row.user_id) && uuid.test(row.branch_id));
    requireFact(branch === null || row.branch_id === branch);
    requireFact(row.local_date === attendanceDate(row.clock_in_at, page.timezone));
    requireFact(typeof row.is_open === 'boolean' && row.is_open === (row.clock_out_at === null));
    requireFact(row.clock_out_at === null || Number.isFinite(Date.parse(row.clock_out_at)) && Date.parse(row.clock_out_at) >= Date.parse(row.clock_in_at));
  };
  if (page.current_open_attendance !== null) {
    fact(page.current_open_attendance);
    requireFact(page.current_open_attendance.user_id === actor && page.current_open_attendance.is_open);
  }
  requireFact(Array.isArray(page.items) && page.items.length <= 100 && page.items.reduce((n, card) => n + card.entries.length, 0) <= 100);
  const ids = new Set<string>();
  const days = (dateOnly(end).getTime() - dateOnly(start).getTime()) / 86400000;
  page.items.forEach((card, index) => {
    requireFact(uuid.test(card.user_id) && typeof card.name === 'string' && card.name.length > 0 && card.name.length <= 200);
    requireFact(Number.isInteger(card.clock_in_day_count) && card.clock_in_day_count > 0 && card.clock_in_day_count <= days);
    requireFact(Array.isArray(card.entries) && card.entries.length > 0);
    requireFact(typeof card.continued_from_previous === 'boolean' && (!card.continued_from_previous || more && index === 0));
    requireFact(typeof card.entries_continued === 'boolean' && (!card.entries_continued || index === page.items.length - 1 && page.next_cursor !== null));
    requireFact(index === 0 || page.items[index - 1].user_id < card.user_id);
    card.entries.forEach((row, rowIndex) => {
      fact(row);
      requireFact(row.user_id === card.user_id && !ids.has(row.id) && row.local_date >= start && row.local_date < end);
      ids.add(row.id);
      const prior = card.entries[rowIndex - 1];
      requireFact(!prior || compareClock(prior.clock_in_at, row.clock_in_at) < 0 ||
        compareClock(prior.clock_in_at, row.clock_in_at) === 0 && prior.id < row.id);
    });
    requireFact(new Set(card.entries.map(row => row.local_date)).size <= card.clock_in_day_count);
  });
  requireFact(page.next_cursor === null || typeof page.next_cursor === 'string' && /^[A-Za-z0-9_-]{1,2048}$/.test(page.next_cursor) && page.items.length > 0);
}
export function mergeAttendanceCards(previous: AttendanceCardsPage, next: AttendanceCardsPage): AttendanceCardsPage {
  requireFact(previous.period === next.period && previous.period_start === next.period_start && previous.period_end_exclusive === next.period_end_exclusive &&
    previous.timezone === next.timezone && previous.scope_branch_id === next.scope_branch_id && previous.next_cursor !== null && previous.next_cursor !== next.next_cursor);
  const ids = new Set(previous.items.flatMap(card => card.entries.map(row => row.id)));
  requireFact(next.items.every(card => card.entries.every(row => !ids.has(row.id))));
  const items = [...previous.items];
  for (const card of next.items) {
    const last = items[items.length - 1];
    if (last?.user_id === card.user_id) {
      requireFact(last.entries_continued && card.continued_from_previous && last.name === card.name && last.clock_in_day_count === card.clock_in_day_count);
      const prior = last.entries[last.entries.length - 1], first = card.entries[0];
      const order = compareClock(prior.clock_in_at, first.clock_in_at);
      requireFact(order < 0 || order === 0 && prior.id < first.id);
      const entries = [...last.entries, ...card.entries];
      requireFact(new Set(entries.map(row => row.local_date)).size <= last.clock_in_day_count);
      items[items.length - 1] = { ...last, entries, entries_continued: card.entries_continued };
    } else {
      requireFact(!card.continued_from_previous && !last?.entries_continued && (!last || last.user_id < card.user_id));
      items.push(card);
    }
  }
  items.forEach((card, index) => requireFact(!card.entries_continued || index === items.length - 1 && next.next_cursor !== null));
  return { ...next, items };
}
