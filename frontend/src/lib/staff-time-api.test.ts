import { beforeEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({
  post: vi.fn(),
  patch: vi.fn(),
  get: vi.fn(),
  generation: 3,
  terminal: 'terminal-a' as string | null
}));
vi.mock('./api', () => ({
  api: {
    get: mocks.get,
    post: mocks.post,
    patch: mocks.patch,
    defaults: {}
  },
  readSessionGeneration: () => mocks.generation
}));
vi.mock('./operational-context', () => ({
  readStoredTerminalId: () => mocks.terminal
}));
import { staffTime, staffTimeWriteConfig, type StaffTimeAction } from './staff-time-api';
beforeEach(() => {
  vi.clearAllMocks();
  mocks.generation = 3;
  mocks.terminal = 'terminal-a';
  mocks.post.mockResolvedValue({
    data: {}
  });
  mocks.patch.mockResolvedValue({
    data: {}
  });
  mocks.get.mockResolvedValue({
    data: []
  });
});
it('sends exact endpoints, original bodies and one captured key on every mutation', async () => {
  const actions: [StaffTimeAction, string][] = [[{
    kind: 'self-in',
    body: {
      branch_id: 'branch-a'
    }
  }, '/staff/my-attendance/clock-in'], [{
    kind: 'self-out',
    body: {
      attendance_id: 'entry-a',
      break_minutes: 60
    }
  }, '/staff/my-attendance/clock-out'], [{
    kind: 'owner-in',
    userId: 'staff-a',
    body: {
      branch_id: 'branch-a',
      reason: 'arrival confirmed'
    }
  }, '/staff/attendance/staff-a/clock-in'], [{
    kind: 'owner-out',
    userId: 'staff-a',
    body: {
      attendance_id: 'entry-a',
      break_minutes: 60,
      reason: 'departure confirmed'
    }
  }, '/staff/attendance/staff-a/clock-out'], [{
    kind: 'policy',
    userId: 'staff-a',
    body: {
      effective_from: '2026-11-01',
      expected_revision: 1,
      monthly_salary_minor: 1200000,
      daily_presence_minutes: 720,
      daily_break_minutes: 60,
      working_weekday_mask: 127,
      overtime_multiplier_basis_points: null,
      reason: 'Draft proposal only'
    }
  }, '/staff/work-policies/staff-a'], [{
    kind: 'correction',
    userId: 'staff-a',
    attendanceId: 'entry-a',
    body: {
      expected_updated_at: 'old-exact-version',
      clock_in_at: '2026-11-02T04:00:00Z',
      clock_out_at: null,
      break_minutes: 0,
      reason: 'Owner corrects time'
    }
  }, '/staff/attendance/staff-a/entry-a']];
  for (const [action, url] of actions) {
    const original = JSON.stringify(action);
    await staffTime.submit(action, 'fixed-key', {
      generation: 3,
      terminalId: 'terminal-a'
    });
    const call = (action.kind === 'correction' ? mocks.patch : mocks.post).mock.lastCall!;
    expect(call[0]).toBe(url);
    expect(call[1]).toEqual(action.body);
    expect(call[2].headers).toEqual({
      'Idempotency-Key': 'fixed-key'
    });
    expect(call[2]._sessionGeneration).toBe(3);
    expect(JSON.stringify(action)).toBe(original);
  }
});
describe('queued request scope fencing', () => {
  it('refuses a changed account or terminal after Axios interceptors and before serialization', () => {
    const config = staffTimeWriteConfig('key', {
      generation: 3,
      terminalId: 'terminal-a'
    });
    const transforms = config.transformRequest as ((data: unknown) => unknown)[];
    const original = {
      break_minutes: 60
    };
    expect(transforms[0](original)).toBe(original);
    mocks.terminal = 'terminal-b';
    expect(() => transforms[0](original)).toThrow('workspace changed');
    mocks.terminal = 'terminal-a';
    mocks.generation = 4;
    expect(() => transforms[0](original)).toThrow('workspace changed');
  });
});
it('pages only the minimal people endpoint with a bounded limit and exact cursor', async () => {
  await staffTime.people('cursor-person');
  expect(mocks.get).toHaveBeenLastCalledWith('/staff/time-people', {
    params: {
      limit: 50,
      after_id: 'cursor-person'
    },
    signal: undefined
  });
});

it('uses the separate minimal cards endpoint with exact period and opaque continuation', async () => {
  mocks.get.mockResolvedValueOnce({ data: { items: [], next_cursor: null } });
  const controller = new AbortController();
  await staffTime.cards('week', '2026-11-02', 'opaqueCursor', controller.signal);
  expect(mocks.get).toHaveBeenCalledWith('/staff/attendance-cards', {
    params: { period: 'week', start: '2026-11-02', limit: 100, after: 'opaqueCursor' }, signal: controller.signal
  });
  expect(mocks.post).not.toHaveBeenCalled(); expect(mocks.patch).not.toHaveBeenCalled();
});
