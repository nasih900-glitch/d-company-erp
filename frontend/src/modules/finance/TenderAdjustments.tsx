import type { TenderReclassificationDTO } from '@/lib/erp-api';
import { inr } from '@/lib/inr';
export default function TenderAdjustments({
  rows,
}: {
  rows: readonly TenderReclassificationDTO[];
}) {
  if (!rows.length) return null;
  return (
    <section
      className="rounded-xl border border-bg-border p-4 space-y-3"
      aria-label="Separate day-level tender evidence"
    >
      <h4 className="font-semibold">
        Daily tender evidence · separate from gross payments
      </h4>
      <p className="text-sm text-fg-muted">
        Owner-attested classification changes do not create sales or refund
        payments. Unresolved actuals have no financial effect.
      </p>
      {rows.map((row) => (
        <div key={row.id} className="border-t border-bg-border pt-3 text-sm">
          <div className="flex flex-wrap justify-between gap-2">
            <b>
              {row.business_date} · {row.business_timezone}
            </b>
            <span>
              {row.kind === 'applied'
                ? 'Applied · revenue unchanged'
                : 'Unresolved · no posting'}
            </span>
          </div>
          <p className="mt-1 tabular-nums">
            Cash {inr(row.cash_delta_minor)} · UPI {inr(row.upi_delta_minor)}
            {row.kind === 'unresolved'
              ? ` · Unexplained total difference ${inr(row.total_difference_minor)}`
              : ''}
          </p>
          <p className="mt-1 text-xs text-fg-muted">
            Unallocated to any bill, staff member or physical drawer count.
          </p>
        </div>
      ))}
    </section>
  );
}
