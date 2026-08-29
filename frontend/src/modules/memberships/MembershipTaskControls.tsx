import type { MembershipMoneyAction } from './membership-policy';
import { Loader2 } from 'lucide-react';

const LABELS: Record<MembershipMoneyAction, string> = {
  begin: 'Start',
  complete: 'Confirm completed',
  finalize: 'Post receipt',
  withdraw: 'Resolve without posting',
};

export function MembershipTaskControls({
  actions,
  rail,
  kind,
  busy,
  disabledReason,
  onAction,
}: {
  actions: readonly MembershipMoneyAction[];
  rail: string;
  kind: 'payment' | 'refund';
  busy: boolean;
  disabledReason?: string | null;
  onAction: (action: MembershipMoneyAction) => void;
}) {
  const label = (action: MembershipMoneyAction): string => {
    if (action === 'begin') {
      if (kind === 'payment') return rail === 'cash' ? 'Start cash collection' : `Start ${rail.toUpperCase()}`;
      return rail === 'cash' ? 'Start cash handover' : `Start ${rail.toUpperCase()} refund`;
    }
    if (action === 'complete') {
      if (kind === 'payment') return rail === 'cash' ? 'Cash received' : 'Provider completed';
      return rail === 'cash' ? 'Cash handed over' : 'Provider refund completed';
    }
    return LABELS[action];
  };

  if (!actions.length) {
    return disabledReason ? <p className="text-xs text-fg-muted">{disabledReason}</p> : null;
  }

  if (busy) {
    return (
      <p className="flex min-h-[44px] items-center gap-2 text-sm text-fg-muted" role="status">
        <Loader2 className="animate-spin" size={16} aria-hidden="true" />
        Updating the authoritative task…
      </p>
    );
  }

  return (
    <div className="flex flex-wrap gap-2">
      {actions.map((action) => (
        <button
          key={action}
          type="button"
          onClick={() => onAction(action)}
          className={action === 'withdraw' ? 'btn btn-ghost text-accent-bad' : 'btn btn-primary'}
        >
          {label(action)}
        </button>
      ))}
    </div>
  );
}
