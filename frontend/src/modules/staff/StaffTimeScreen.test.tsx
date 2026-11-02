// @vitest-environment happy-dom
import { act, StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Me } from '@/modules/auth/AuthContext';
import type { StaffTimeSummary, StaffAttendance } from '@/lib/staff-time-api';
import { clearOwnerWorkspaces } from '@/lib/owner-workspace';
const mocks = vi.hoisted(() => ({
  me: null as Me | null,
  generation: 1,
  people: vi.fn(),
  summary: vi.fn(),
  policies: vi.fn(),
  submit: vi.fn()
}));
vi.mock('@/modules/auth/AuthContext', () => ({
  useAuth: () => ({
    me: mocks.me,
    terminalId: 'terminal-a',
    terminalOptions: [{
      id: 'terminal-a',
      branch_id: 'branch-a'
    }]
  })
}));
vi.mock('@/lib/api', () => ({
  readSessionGeneration: () => mocks.generation
}));
vi.mock('@/lib/operational-context', () => ({
  readStoredTerminalId: () => 'terminal-a'
}));
vi.mock('@/lib/staff-time-api', () => ({
  staffTime: {
    summary: mocks.summary,
    people: mocks.people,
    policies: mocks.policies,
    submit: mocks.submit
  }
}));
import StaffTimeScreen from './StaffTimeScreen';
const employee = {
  user_id: 'employee-a',
  company_id: 'company-a',
  branch_id: 'branch-a',
  name: 'Synthetic Alpha',
  roles: ['gaming_cashier'],
  effective_permissions: ['staff.attendance.write'],
  accessible_modules: ['gaming'],
  protected_access: false,
  audit_access: false
} as Me;
const entry = {
  id: 'entry-a',
  user_id: 'employee-a',
  branch_id: 'branch-a',
  clock_in_at: '2026-10-02T04:00:00.123456+00:00',
  clock_out_at: '2026-10-02T16:00:00.654321+00:00',
  break_minutes: 60,
  break_confirmed: true,
  source: 'manual',
  updated_at: '2026-10-02T16:00:00.654321+00:00'
} satisfies StaffAttendance;
function summary(month: string, id = mocks.me!.user_id): StaffTimeSummary {
  return {
    user_id: id,
    name: id === employee.user_id ? employee.name : 'Synthetic Beta',
    month,
    timezone: 'UTC',
    as_of: new Date().toISOString(),
    entries: [],
    closed_gross_seconds: 43200,
    closed_work_seconds: 39600,
    provisional_open_seconds: 0,
    review_attendance_ids: [],
    pay_status: 'draft',
    payable_minor: null,
    overtime_pay_minor: null,
    schedule: {
      effective_from: '2026-11-01',
      daily_presence_minutes: 720,
      daily_break_minutes: 60,
      working_weekday_mask: 127
    },
    policy: null,
    policy_covers_full_month: false,
    scheduled_days: null,
    scheduled_work_minutes: null,
    reference_hourly_numerator_minor: null,
    reference_hourly_denominator_minutes: null
  };
}
let host: HTMLDivElement, root: Root;
const environment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT?: boolean;
};
beforeEach(() => {
  environment.IS_REACT_ACT_ENVIRONMENT = true;
  clearOwnerWorkspaces();
  vi.clearAllMocks();
  mocks.generation = 1;
  mocks.me = employee;
  mocks.summary.mockImplementation(async (month: string, id?: string) => summary(month, id));
  mocks.policies.mockResolvedValue([]);
  mocks.people.mockResolvedValue({
    items: [{
      id: 'employee-b',
      name: 'Synthetic Beta',
      status: 'active',
      departed: false
    }],
    next_cursor: null
  });
  mocks.submit.mockResolvedValue(entry);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  clearOwnerWorkspaces();
  vi.useRealTimers();
  delete environment.IS_REACT_ACT_ENVIRONMENT;
});
async function render() {
  await act(async () => root.render(<StrictMode><StaffTimeScreen /></StrictMode>));
}
function findButton(text: string) {
  const b = [...document.querySelectorAll('button')].find(b => b.textContent?.includes(text));
  if (!b) throw new Error('Missing button ' + text);
  return b;
}
async function click(text: string) {
  await act(async () => findButton(text).click());
}
async function fill(selector: string, value: string) {
  const n = document.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(selector)!;
  expect(n).toBeTruthy();
  await act(async () => {
    const proto = n instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : n instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(n, value);
    n.dispatchEvent(new Event('input', {
      bubbles: true
    }));
    n.dispatchEvent(new Event('change', {
      bubbles: true
    }));
  });
}
async function saveForm() {
  await act(async () => document.querySelector('form')!.dispatchEvent(new Event('submit', {
    bubbles: true,
    cancelable: true
  })));
}
it('allows ordinary staff own facts without fetching directory, admin roles or salary history', async () => {
  await render();
  expect(host.textContent).toContain('11h 0m');
  expect(host.textContent).toContain('720 min presence');
  expect(mocks.people).not.toHaveBeenCalled();
  expect(mocks.policies).not.toHaveBeenCalled();
  expect(host.textContent).not.toContain('Salary proposals');
  expect(mocks.summary.mock.calls.every(c => c[1] === undefined)).toBe(true);
});
it('does not grant manager payroll controls from a permission without Owner identity', async () => {
  mocks.me = {
    ...employee,
    roles: ['manager'],
    effective_permissions: ['staff.payroll.write', 'staff.read']
  };
  await render();
  expect(mocks.people).not.toHaveBeenCalled();
  expect(mocks.policies).not.toHaveBeenCalled();
  expect(host.textContent).not.toContain('New draft revision');
});
it('captures an explicit actual break for self clock-out, never substituting the planned hour', async () => {
  mocks.summary.mockImplementation(async (m: string) => ({
    ...summary(m),
    entries: [{
      ...entry,
      clock_in_at: new Date(Date.now() - 12 * 3600000).toISOString(),
      clock_out_at: null
    }]
  }));
  await render();
  await click('Record actual break');
  const input = document.querySelector<HTMLInputElement>('form input[type="number"]')!;
  expect(input.value).toBe('0');
  await fill('form input[type="number"]', '35');
  await saveForm();
  expect(mocks.submit.mock.calls[0][0]).toEqual({
    kind: 'self-out',
    body: {
      attendance_id: 'entry-a',
      break_minutes: 35
    }
  });
});
it('holds an ambiguous request across remount and retries the same key and frozen body after refresh', async () => {
  mocks.submit.mockRejectedValueOnce(new Error('network lost')).mockResolvedValueOnce(entry);
  await render();
  await click('Clock in');
  expect(host.textContent).toContain('Keep this request together');
  expect(mocks.submit).toHaveBeenCalledTimes(1);
  const original = mocks.submit.mock.calls[0];
  await act(async () => root.render(null));
  await render();
  await click('Refresh records');
  await click('Retry same request');
  expect(mocks.submit).toHaveBeenCalledTimes(2);
  expect(mocks.submit.mock.calls[1]).toEqual(original);
  expect(host.textContent).not.toContain('Keep this request together');
});
it('ignores an old month response after a newer selection', async () => {
  await render();
  let old!: (s: StaffTimeSummary) => void;
  const firstMonth = '2025-10';
  mocks.summary.mockImplementation((m: string) => m === firstMonth ? new Promise<StaffTimeSummary>(resolve => {
    old = resolve;
  }) : Promise.resolve(summary(m)));
  await fill('input[type="month"]', firstMonth);
  await fill('input[type="month"]', '2025-11');
  await act(async () => old({
    ...summary(firstMonth),
    closed_work_seconds: 999999
  }));
  expect(document.querySelector<HTMLInputElement>('input[type="month"]')!.value).toBe('2025-11');
  expect(host.textContent).not.toContain('277h');
});
it('requires an Owner reason and submits only a draft proposal with the actual target and revision', async () => {
  mocks.me = {
    ...employee,
    roles: ['owner'],
    effective_permissions: ['staff.payroll.write', 'staff.read']
  };
  await render();
  await fill('select', 'employee-b');
  await click('New draft revision');
  expect(document.querySelector<HTMLInputElement>('input[inputmode="decimal"]')!.value).toBe('');
  await fill('input[inputmode="decimal"]', '12000');
  await fill('textarea', ' ');
  await saveForm();
  expect(mocks.submit).not.toHaveBeenCalled();
  await fill('textarea', 'Agreed proposal, payment not approved');
  await saveForm();
  expect(mocks.submit.mock.calls[0][0]).toMatchObject({
    kind: 'policy',
    userId: 'employee-b',
    body: {
      expected_revision: 0,
      monthly_salary_minor: 1200000,
      daily_presence_minutes: 720,
      daily_break_minutes: 60,
      working_weekday_mask: 127,
      overtime_multiplier_basis_points: null,
      reason: 'Agreed proposal, payment not approved'
    }
  });
});
it('retains exact row timestamps and version when correcting only an actual break', async () => {
  mocks.me = {
    ...employee,
    roles: ['owner'],
    effective_permissions: ['staff.payroll.write']
  };
  mocks.summary.mockImplementation(async (m: string) => ({
    ...summary(m),
    entries: [entry]
  }));
  await render();
  await click('Correct entry');
  await fill('form input[type="number"]', '45');
  await fill('textarea', 'Verified actual break correction');
  await saveForm();
  expect(mocks.submit.mock.calls[0][0]).toEqual({
    kind: 'correction',
    userId: employee.user_id,
    attendanceId: entry.id,
    body: {
      expected_updated_at: entry.updated_at,
      clock_in_at: entry.clock_in_at,
      clock_out_at: entry.clock_out_at,
      break_minutes: 45,
      reason: 'Verified actual break correction'
    }
  });
});
it('clears previous account facts immediately when the authenticated identity changes', async () => {
  await render();
  mocks.generation = 2;
  clearOwnerWorkspaces();
  mocks.me = {
    ...employee,
    user_id: 'different-user',
    name: 'Other account'
  };
  mocks.summary.mockImplementation(() => new Promise(() => {}));
  await render();
  expect(host.textContent).not.toContain('11h 0m');
  expect(host.textContent).not.toContain('Synthetic Alpha');
});
it('never mounts attendance APIs when modern permission was removed', async () => {
  mocks.me = {
    ...employee,
    effective_permissions: []
  };
  await render();
  expect(mocks.summary).not.toHaveBeenCalled();
  expect(mocks.people).not.toHaveBeenCalled();
  expect(host.textContent).toContain('unavailable');
});
it('pages the minimal Owner people list and keeps departed history reachable without new clock-ins', async () => {
  mocks.me = {
    ...employee,
    roles: ['owner'],
    effective_permissions: ['staff.payroll.write']
  };
  mocks.people.mockResolvedValueOnce({
    items: [{
      id: 'employee-b',
      name: 'Synthetic Beta',
      status: 'active',
      departed: false
    }],
    next_cursor: 'employee-b'
  }).mockResolvedValueOnce({
    items: [{
      id: 'departed-person',
      name: 'Departed worker',
      status: 'suspended',
      departed: true
    }],
    next_cursor: null
  });
  await act(async () => root.render(<StaffTimeScreen />));
  await click('Load more people');
  expect(mocks.people.mock.calls[1][0]).toBe('employee-b');
  await fill('select', 'departed-person');
  expect(host.textContent).toContain('Departed or suspended staff');
  expect(findButton('Clock in').disabled).toBe(true);
  expect(findButton('New draft revision').disabled).toBe(true);
  expect(mocks.policies).toHaveBeenCalledWith('departed-person', undefined, expect.any(AbortSignal));
});
it('keeps actual attendance available when draft policy history fails', async () => {
  mocks.me = {
    ...employee,
    roles: ['owner'],
    effective_permissions: ['staff.payroll.write', 'staff.attendance.write']
  };
  mocks.policies.mockRejectedValue(new Error('history unavailable'));
  await render();
  expect(host.textContent).toContain('11h 0m');
  expect(findButton('Clock in').disabled).toBe(false);
  expect(findButton('New draft revision').disabled).toBe(true);
  expect(host.textContent).toContain('Draft revision history is unavailable');
});
it('chooses the current company month at a UTC boundary before publishing the initial view', async () => {
  vi.useFakeTimers({
    toFake: ['Date']
  });
  vi.setSystemTime(new Date('2026-10-31T20:00:00Z'));
  mocks.summary.mockImplementation(async (m: string, id?: string) => ({
    ...summary(m, id),
    timezone: 'Asia/Kolkata'
  }));
  await render();
  expect(document.querySelector<HTMLInputElement>('input[type="month"]')!.value).toBe('2026-11');
  expect(mocks.summary.mock.calls.some(c => c[0] === '2026-11')).toBe(true);
});
it('preserves an explicitly chosen remembered month instead of following the current company month', async () => {
  vi.useFakeTimers({
    toFake: ['Date']
  });
  vi.setSystemTime(new Date('2026-10-31T20:00:00Z'));
  mocks.summary.mockImplementation(async (m: string, id?: string) => ({
    ...summary(m, id),
    timezone: 'Asia/Kolkata'
  }));
  await render();
  await fill('input[type="month"]', '2025-02');
  await act(async () => root.render(null));
  vi.setSystemTime(new Date('2027-01-01T03:00:00Z'));
  await render();
  expect(document.querySelector<HTMLInputElement>('input[type="month"]')!.value).toBe('2025-02');
});
it('lets a payroll-authorized Owner record their own time under the backend self-service contract', async () => {
  mocks.me = {
    ...employee,
    roles: ['owner'],
    effective_permissions: ['staff.payroll.write']
  };
  await render();
  expect(findButton('Clock in').disabled).toBe(false);
  await click('Clock in');
  expect(mocks.submit.mock.calls[0][0].kind).toBe('self-in');
});
it('distinguishes a confirmed save from a failed subsequent refresh', async () => {
  await render();
  mocks.summary.mockRejectedValue(new Error('refresh failed'));
  await click('Clock in');
  expect(host.textContent).toContain('Saved. Refresh records to review the confirmed write.');
  expect(host.textContent).not.toContain('Records refreshed.');
  expect(host.textContent).not.toContain('Keep this request together');
});
it('refuses a nonzero actual break on an open correction before sending a request', async () => {
  mocks.me = {
    ...employee,
    roles: ['owner'],
    effective_permissions: ['staff.payroll.write']
  };
  mocks.summary.mockImplementation(async (m: string) => ({
    ...summary(m),
    entries: [entry]
  }));
  await render();
  await click('Correct entry');
  await act(async () => document.querySelector<HTMLInputElement>('form input[type="checkbox"]')!.click());
  await fill('textarea', 'Review reopening the record');
  await saveForm();
  expect(mocks.submit).not.toHaveBeenCalled();
});

it.each([false, undefined])('withholds an unconfirmed closed break instead of presenting a legacy default as actual (%s)', async confirmed => {
  mocks.summary.mockImplementation(async (month: string, id?: string) => ({
    ...summary(month, id),
    entries: [{ ...entry, break_minutes: 0, break_confirmed: confirmed as boolean }],
    closed_work_seconds: null,
    review_attendance_ids: [entry.id]
  }));
  await render();
  expect(host.textContent).toContain('Actual break unconfirmed · Owner review');
  expect(host.textContent).toContain('Needs review');
  expect(host.textContent).not.toContain('Recorded break 0 min');
  expect(mocks.submit).not.toHaveBeenCalled();
});

it('shows an explicitly confirmed zero as recorded and leaves an open break provisional', async () => {
  mocks.summary.mockImplementation(async (month: string, id?: string) => ({
    ...summary(month, id),
    entries: [{ ...entry, break_minutes: 0, break_confirmed: true }]
  }));
  await render();
  expect(host.textContent).toContain('Recorded break 0 min');
  expect(host.textContent).not.toContain('Actual break unconfirmed');
  mocks.summary.mockImplementation(async (month: string, id?: string) => ({
    ...summary(month, id),
    entries: [{ ...entry, clock_out_at: null, break_minutes: 0, break_confirmed: false }]
  }));
  await click('Refresh records');
  expect(host.textContent).toContain('Confirm actual break at clock-out');
  expect(host.textContent).not.toContain('Recorded break 0 min');
});

it('requires an Owner to enter the actual break when correcting an unconfirmed closed entry', async () => {
  mocks.me = { ...employee, roles: ['owner'], effective_permissions: ['staff.payroll.write'] };
  mocks.summary.mockImplementation(async (month: string, id?: string) => ({
    ...summary(month, id),
    entries: [{ ...entry, break_minutes: 0, break_confirmed: false }],
    closed_work_seconds: null,
    review_attendance_ids: [entry.id]
  }));
  await render();
  await click('Correct entry');
  expect(document.querySelector<HTMLInputElement>('input[type="number"]')!.value).toBe('');
  await fill('textarea', 'Owner independently confirms actual zero break');
  await saveForm();
  expect(mocks.submit).not.toHaveBeenCalled();
  await fill('input[type="number"]', '0');
  await saveForm();
  expect(mocks.submit.mock.lastCall![0]).toMatchObject({
    kind: 'correction',
    body: { break_minutes: 0, reason: 'Owner independently confirms actual zero break' }
  });
});

it.each([403, 404])('retains an earlier unknown action after retry %s and clears private projections until access is revalidated', async status => {
  mocks.me = { ...employee, roles: ['owner'], effective_permissions: ['staff.payroll.write'] };
  mocks.submit.mockRejectedValueOnce(new Error('Response lost'))
    .mockRejectedValueOnce(Object.assign(new Error('Access refused on retry'), { status }));
  await render();
  await fill('select', 'employee-b');
  await click('New draft revision');
  await fill('input[inputmode="decimal"]', '12000');
  await fill('textarea', 'Original draft proposal remains unconfirmed');
  await saveForm();
  const [originalBody, originalKey] = mocks.submit.mock.calls[0];
  const exactBody = JSON.stringify(originalBody);
  expect(document.querySelector('form')).toBeNull();
  await click('Refresh records');
  await click('Retry same request');
  expect(mocks.submit.mock.calls[1][1]).toBe(originalKey);
  expect(JSON.stringify(mocks.submit.mock.calls[1][0])).toBe(exactBody);
  expect(host.textContent).toContain('earlier request remains unconfirmed');
  expect(host.textContent).toContain('Owner review');
  expect(host.textContent).not.toContain('11h 0m');
  expect(host.textContent).not.toContain('Synthetic Beta');
  expect(findButton('Retry same request').disabled).toBe(true);
  expect(document.querySelector<HTMLSelectElement>('select')!.disabled).toBe(true);
  expect(document.querySelector<HTMLInputElement>('input[type="month"]')!.disabled).toBe(true);
  await click('Refresh records');
  expect(findButton('New draft revision').disabled).toBe(true);
  mocks.submit.mockResolvedValueOnce(entry);
  await click('Retry same request');
  expect(mocks.submit.mock.calls[2][1]).toBe(originalKey);
  expect(JSON.stringify(mocks.submit.mock.calls[2][0])).toBe(exactBody);
  expect(host.textContent).not.toContain('Keep this request together');
});

it('keeps policy inputs and the server error inside the mounted form after a definitive first 422', async () => {
  mocks.me = { ...employee, roles: ['owner'], effective_permissions: ['staff.payroll.write'] };
  mocks.submit.mockRejectedValueOnce(Object.assign(new Error('Review the draft terms'), { status: 422 }));
  await render();
  await click('New draft revision');
  await fill('input[inputmode="decimal"]', '12000');
  await fill('textarea', 'Keep this entered draft reason');
  await saveForm();
  expect(document.querySelector<HTMLInputElement>('input[inputmode="decimal"]')!.value).toBe('12000');
  expect(document.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('Keep this entered draft reason');
  expect(document.querySelector('form [role="alert"]')!.textContent).toContain('Review the draft terms');
  expect(findButton('Save draft revision').disabled).toBe(false);
  expect(host.textContent).not.toContain('Keep this request together');
  const firstKey = mocks.submit.mock.calls[0][1];
  await fill('input[inputmode="decimal"]', '13000');
  await saveForm();
  expect(mocks.submit.mock.calls[1][1]).not.toBe(firstKey);
  expect(mocks.submit.mock.calls[1][0]).toMatchObject({
    kind: 'policy', body: { monthly_salary_minor: 1300000, reason: 'Keep this entered draft reason' }
  });
  expect(document.querySelector('form')).toBeNull();
});

it('does not republish a delayed people response after an unknown retry loses authority', async () => {
  mocks.me = { ...employee, roles: ['owner'], effective_permissions: ['staff.payroll.write'] };
  let releasePeople!: (value: unknown) => void;
  mocks.people.mockImplementation(() => new Promise(resolve => { releasePeople = resolve; }));
  mocks.submit.mockRejectedValueOnce(new Error('Response lost'))
    .mockRejectedValueOnce(Object.assign(new Error('Authority removed'), { status: 403 }));
  await render();
  await click('New draft revision');
  await fill('input[inputmode="decimal"]', '12000');
  await fill('textarea', 'Unconfirmed own draft');
  await saveForm();
  await click('Refresh records');
  await click('Retry same request');
  await act(async () => releasePeople({
    items: [{ id: 'employee-b', name: 'Synthetic Beta private directory name', status: 'active', departed: false }],
    next_cursor: null
  }));
  expect(host.textContent).not.toContain('Synthetic Beta');
  expect(host.textContent).toContain('earlier request remains unconfirmed');
  expect(findButton('Retry same request').disabled).toBe(true);
});

it('clears private people and fences a late page after a refresh loses scoped read access', async () => {
  mocks.me = { ...employee, roles: ['owner'], effective_permissions: ['staff.payroll.write'] };
  mocks.people.mockResolvedValue({ items: [{ id: 'employee-b', name: 'Synthetic Beta', status: 'active', departed: false }], next_cursor: 'employee-b' });
  await render();
  expect(host.textContent).toContain('Synthetic Beta');
  let releasePage!: (value: unknown) => void;
  mocks.people.mockImplementation(() => new Promise(resolve => { releasePage = resolve; }));
  await click('Load more people');
  mocks.summary.mockRejectedValueOnce(Object.assign(new Error('Read access removed'), { status: 403 }));
  await click('Refresh records');
  expect(host.textContent).not.toContain('Synthetic Beta');
  expect(host.textContent).not.toContain('11h 0m');
  expect(host.textContent).toContain('Owner review');
  await act(async () => releasePage({ items: [{ id: 'employee-c', name: 'Late private Gamma', status: 'active', departed: false }], next_cursor: null }));
  expect(host.textContent).not.toContain('Late private Gamma');
  expect(host.textContent).not.toContain('Load more people');
});

it('does not swallow a policy-history access refusal as an ordinary network outage', async () => {
  mocks.me = { ...employee, roles: ['owner'], effective_permissions: ['staff.payroll.write'] };
  await render();
  expect(host.textContent).toContain('Synthetic Beta');
  mocks.policies.mockRejectedValueOnce(Object.assign(new Error('Salary-history access removed'), { status: 403 }));
  await click('Refresh records');
  expect(host.textContent).not.toContain('Synthetic Beta');
  expect(host.textContent).not.toContain('11h 0m');
  expect(host.textContent).toContain('Owner review');
});

it('clears all private projections when paging older salary revisions loses scope', async () => {
  mocks.me = { ...employee, roles: ['owner'], effective_permissions: ['staff.payroll.write'] };
  mocks.policies.mockResolvedValue(Array.from({ length: 50 }, (_, i) => ({
    id: 'policy-' + i, user_id: employee.user_id, revision: 50 - i,
    monthly_salary_minor: 1200000, daily_presence_minutes: 720, daily_break_minutes: 60,
    working_weekday_mask: 127, effective_from: '2026-11-01', overtime_multiplier_basis_points: null,
    pay_status: 'draft', reason: 'Private Owner policy ' + i, created_at: '2026-11-01T00:00:00Z'
  })));
  await render();
  expect(host.textContent).toContain('Private Owner policy 1');
  mocks.policies.mockRejectedValueOnce(Object.assign(new Error('History target removed'), { status: 404 }));
  await click('Load older revisions');
  expect(host.textContent).not.toContain('Private Owner policy');
  expect(host.textContent).not.toContain('Synthetic Beta');
  expect(host.textContent).not.toContain('11h 0m');
  expect(host.textContent).toContain('Owner review');
});

it('does not treat a refresh network timeout as revocation of the already-authorized people list', async () => {
  mocks.me = { ...employee, roles: ['owner'], effective_permissions: ['staff.payroll.write'] };
  await render();
  expect(host.textContent).toContain('Synthetic Beta');
  mocks.summary.mockRejectedValueOnce(new Error('Network timeout'));
  await click('Refresh records');
  expect(host.textContent).toContain('Synthetic Beta');
  expect(host.textContent).toContain('Network timeout');
  expect(host.textContent).not.toContain('Access changed. Owner review');
});
