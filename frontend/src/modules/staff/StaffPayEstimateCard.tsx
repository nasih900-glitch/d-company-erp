import { useState } from 'react';
import type { StaffTimeSummary } from '@/lib/staff-time-api';
import { payAmount } from './staff-pay-estimate';
import { duration } from './staff-time-policy';

export default function StaffPayEstimateCard({ summary }: {summary: StaffTimeSummary}) {
  const [overtime, setOvertime] = useState<'off' | '125' | '150'>('off');
  const p = summary.pay_estimate;
  const extra = overtime === '125' ? p?.overtime_125_minor : overtime === '150' ? p?.overtime_150_minor : 0;
  return <section className="rounded-2xl border border-bg-border/70 bg-bg-surface/60 p-5 shadow-lg backdrop-blur-xl" aria-label="Pay estimate from recorded hours">
    <h2 className="font-semibold">{summary.name} · Hours & pay estimate</h2>
    <p className="mt-2 text-sm text-fg-muted">Estimate for completed attendance only. No payment, payroll deduction or expense is posted.</p>
    {!p || p.status !== 'available' ? <p className="mt-3 text-sm">{p?.reason ?? 'Pay estimates require the updated server. No amount is inferred.'}</p> : <>
      <p className="mt-3 text-sm">Monthly reference: {payAmount(p.monthly_salary_minor!)} ÷ {duration(p.scheduled_paid_seconds)} scheduled paid hours = {payAmount(p.hourly_rate_minor!)} / hour (display rounded).</p>
      <p className="mt-2 text-xs text-fg-muted">Paid presence includes breaks. Workdays are dated by arrival in {summary.timezone}; each overnight visit stays together in its arrival month. Base time is capped at {duration(p.daily_paid_seconds)} per scheduled arrival workday; additional time is separate. {p.attendance_scope === 'selected_branch' ? 'Selected branch attendance only.' : 'All company branches.'} Amounts use exact timestamps, rounded once per monthly component.</p>
      <dl className="mt-4 grid gap-3 sm:grid-cols-2"><div><dt>Recorded paid presence</dt><dd className="font-semibold">{duration(p.recorded_paid_seconds)}</dd></div><div><dt>Base hours</dt><dd className="font-semibold">{duration(p.ordinary_seconds)}</dd></div><div><dt>Base-pay estimate</dt><dd className="font-semibold">{payAmount(p.base_pay_minor!)}</dd></div><div><dt>Extra hours · optional overtime</dt><dd className="font-semibold">{duration(p.extra_seconds)}</dd></div></dl>
      <fieldset className="mt-4"><legend className="text-sm font-semibold">Optional overtime preview</legend><div className="mt-2 flex flex-wrap gap-2">{(['off', '125', '150'] as const).map(value => <button type="button" className={`btn ${overtime === value ? 'btn-primary' : 'btn-ghost'}`} aria-pressed={overtime === value} key={value} onClick={() => setOvertime(value)}>{value === 'off' ? 'No overtime' : value === '125' ? '1.25× overtime' : '1.5× overtime'}</button>)}</div></fieldset>
      <p className="mt-3 text-sm">Optional overtime amount: <strong>{payAmount(extra ?? 0)}</strong></p>
      <p className="mt-2 text-lg font-semibold">Total estimate: {payAmount(p.base_pay_minor! + (extra ?? 0))}</p>
      <p className="mt-2 text-xs text-fg-muted">Overtime choices are comparison previews, not an approved overtime rate. Open entries remain provisional and excluded.</p>
      <details className="mt-4"><summary className="min-h-12 cursor-pointer text-sm font-semibold">Daily hours breakdown</summary><div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr><th>Arrival workday</th><th>Paid presence</th><th>Base hours</th><th>Extra hours</th></tr></thead><tbody>{p.days.map(day => <tr key={day.date}><td className="py-2">{day.date}</td><td>{duration(day.paid_seconds)}</td><td>{duration(day.ordinary_seconds)}</td><td>{duration(day.extra_seconds)}</td></tr>)}</tbody></table></div></details>
    </>}
  </section>;
}
