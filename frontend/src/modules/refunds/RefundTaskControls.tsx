import type { RefundTaskAction } from './refund-policy';

const ACTION_LABELS: Record<RefundTaskAction, string> = {
  begin_cash: 'Start cash handover',
  settle_cash: 'Record cash handed over',
  finalize_cash: 'Finish cash accounting',
  withdraw_cash: 'Withdraw — no cash given',
  resolve_cash: 'Resolve — drawer unchanged',
  begin_provider: 'Start provider payout',
  settle_provider: 'Record provider completion',
  finalize_provider: 'Finish provider accounting',
  withdraw_provider: 'Withdraw — payout not started',
  resolve_provider: 'Resolve failed provider payout',
};

const DANGEROUS_ACTIONS = new Set<RefundTaskAction>(['settle_cash', 'settle_provider']);

export function RefundTaskControls({
  actions,
  busy,
  disabledReason,
  onAction,
}: {
  actions: readonly RefundTaskAction[];
  busy: boolean;
  disabledReason?: string | null;
  onAction: (action: RefundTaskAction) => void;
}) {
  if (!actions.length) {
    return disabledReason ? (
      <p className="rounded-xl border border-bg-border bg-bg/40 px-3 py-2 text-xs text-fg-muted">
        {disabledReason}
      </p>
    ) : null;
  }

  return (
    <div className="flex flex-wrap gap-2">
      {actions.map((action, index) => (
        <button
          key={action}
          type="button"
          className={DANGEROUS_ACTIONS.has(action)
            ? 'btn border border-accent-bad/60 bg-accent-bad/15 text-accent-bad hover:bg-accent-bad/25'
            : index === 0
              ? 'btn btn-primary'
              : 'btn btn-ghost'}
          disabled={busy}
          onClick={() => onAction(action)}
        >
          {busy ? 'Working…' : ACTION_LABELS[action]}
        </button>
      ))}
    </div>
  );
}
