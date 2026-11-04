import type { StaffTimeSummary } from '@/lib/staff-time-api';

/** Preserve each minor unit even near the maximum safe JSON integer. */
export function payAmount(minor: number): string {
  if (!Number.isSafeInteger(minor) || minor < 0) throw new Error('Invalid estimate amount.');
  const exact = BigInt(minor);
  return `${new Intl.NumberFormat('en-IN').format(exact / 100n)}.${String(exact % 100n).padStart(2, '0')}`;
}

/** Validate the additive projection before retaining private salary data. */
export function validatePayEstimate(summary: StaffTimeSummary, owner: boolean): void {
  const p = summary.pay_estimate;
  if (p == null) return; // Older servers do not provide estimates.
  const refuse = () => { throw new Error('The pay estimate could not be verified. Refresh before reviewing pay.'); };
  if (!owner || p.basis !== 'paid_presence_clock_in_workday' || !['company', 'selected_branch'].includes(p.attendance_scope)) refuse();
  if (!Array.isArray(p.days) || p.days.length > 31) refuse();
  if (p.status === 'unavailable') {
    if (typeof p.reason !== 'string' || !p.reason.length || p.reason.length > 500 || p.days.length ||
      [p.base_pay_minor, p.overtime_125_minor, p.overtime_150_minor].some(v => v !== null)) refuse();
    return;
  }
  if (p.status !== 'available' || p.reason !== null || !summary.policy || !summary.policy_covers_full_month) refuse();
  const integers = [p.monthly_salary_minor, p.daily_paid_seconds, p.scheduled_days, p.scheduled_paid_seconds,
    p.recorded_paid_seconds, p.ordinary_seconds, p.extra_seconds, p.hourly_rate_minor, p.base_pay_minor,
    p.overtime_125_minor, p.overtime_150_minor];
  if (integers.some(v => !Number.isSafeInteger(v) || v === null || v < 0)) refuse();
  if (p.monthly_salary_minor !== summary.policy!.monthly_salary_minor || p.daily_paid_seconds !== summary.policy!.daily_presence_minutes * 60 ||
    p.scheduled_days! < 1 || p.scheduled_days! > 31 || p.scheduled_paid_seconds !== p.scheduled_days! * p.daily_paid_seconds! ||
    p.ordinary_seconds! > p.scheduled_paid_seconds! || p.base_pay_minor! > p.monthly_salary_minor! ||
    p.overtime_150_minor! < p.overtime_125_minor! || !Number.isSafeInteger(p.base_pay_minor! + p.overtime_150_minor!)) refuse();
  let previous = '';
  for (const day of p.days) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day.date) || !day.date.startsWith(`${summary.month}-`) || day.date <= previous ||
      new Date(`${day.date}T00:00:00Z`).toISOString().slice(0, 10) !== day.date ||
      ![day.paid_seconds, day.ordinary_seconds, day.extra_seconds].every(v => Number.isSafeInteger(v) && v >= 0) ||
      day.ordinary_seconds > p.daily_paid_seconds! || day.ordinary_seconds + day.extra_seconds > day.paid_seconds ||
      day.paid_seconds - day.ordinary_seconds - day.extra_seconds > 1) refuse();
    previous = day.date;
  }
  if (p.ordinary_seconds! + p.extra_seconds! > p.recorded_paid_seconds! ||
    p.recorded_paid_seconds! - p.ordinary_seconds! - p.extra_seconds! > 1) refuse();
}
