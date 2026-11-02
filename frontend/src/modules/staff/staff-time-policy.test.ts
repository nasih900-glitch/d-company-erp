import { describe, expect, it } from 'vitest';
import { canManageStaffTime, canUseStaffTime, confirmedWriteRefusal, readUtcInput, utcInput, wholeMinutes, duration } from './staff-time-policy';
import { visibleProfileNavigationGroups } from '@/lib/product-profile';
describe('staff-time authority and facts', () => {
  it('uses current permissions and never grants payroll by a title alone', () => {
    expect(canUseStaffTime({
      effective_permissions: ['staff.attendance.write']
    })).toBe(true);
    expect(canUseStaffTime({
      effective_permissions: []
    })).toBe(false);
    expect(canManageStaffTime({
      roles: ['owner'],
      effective_permissions: []
    })).toBe(false);
    expect(canManageStaffTime({
      roles: ['manager'],
      effective_permissions: ['staff.payroll.write']
    })).toBe(false);
    expect(canManageStaffTime({
      roles: ['owner'],
      effective_permissions: ['staff.payroll.write']
    })).toBe(true);
  });
  it('makes attendance reachable without exposing the existing owner administration route', () => {
    const groups = visibleProfileNavigationGroups({
      isOwner: false,
      hasAuditAccess: false,
      hasSystemAccess: false,
      hasMembershipAccess: false,
      hasRefundAccess: false,
      hasProductManagementAccess: false,
      accessibleModules: ['gaming'],
      hasStaffTimeAccess: true
    });
    expect(groups.flatMap(g => g.items).map(i => i.to)).toContain('/staff/time');
    expect(groups.flatMap(g => g.items).map(i => i.to)).not.toContain('/staff');
  });
  it('withholds unknown totals and validates whole actual break minutes', () => {
    expect(duration(null)).toBe('Needs review');
    expect(duration(39600)).toBe('11h 0m');
    expect(wholeMinutes('60')).toBe(60);
    for (const value of ['-1', '1.5', '1e2', '1441', '']) expect(wholeMinutes(value)).toBeNull();
  });
  it('uses explicit UTC while preserving an unchanged precise stored timestamp', () => {
    const raw = '2026-11-02T04:00:00.123456+00:00';
    expect(readUtcInput(utcInput(raw), raw)).toBe(raw);
    expect(readUtcInput('2026-11-02T04:05')).toBe('2026-11-02T04:05:00.000Z');
    expect(() => readUtcInput('2026-02-30T04:05')).toThrow();
    expect(() => readUtcInput('not a date')).toThrow();
  });
  it('keeps ambiguous failures and in-progress receipts distinct from a server refusal', () => {
    expect(confirmedWriteRefusal(Object.assign(new Error(), {
      status: 422
    }))).toBe(true);
    for (const error of [new Error('offline'), {
      status: 503
    }, {
      status: 409,
      code: 'idempotency_in_progress'
    }, {
      status: 408
    }, {
      status: 429
    }]) expect(confirmedWriteRefusal(error)).toBe(false);
  });
});
