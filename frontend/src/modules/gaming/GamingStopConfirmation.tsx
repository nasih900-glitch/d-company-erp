import { ConfirmModal } from '@/components/ui/ConfirmDialog';
import { inr } from '@/lib/inr';

export function GamingStopConfirmation({
  stationName,
  elapsedMinutes,
  estimatedAmountMinor,
  fixedPrice,
  busy,
  onConfirm,
  onCancel,
}: {
  stationName: string;
  elapsedMinutes: number;
  estimatedAmountMinor: number | null;
  fixedPrice: boolean;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const amountDescription = estimatedAmountMinor == null
    ? 'The session amount is not available on this screen and will require review.'
    : fixedPrice
      ? `The fixed session charge is ${inr(estimatedAmountMinor)}.`
      : `The current estimated session charge is ${inr(estimatedAmountMinor)}.`;

  return (
    <ConfirmModal
      title={`End ${stationName}?`}
      message={(
        <div className="space-y-2 text-sm text-fg-muted">
          <p>{amountDescription}</p>
          <p>
            Elapsed time is approximately {Math.max(1, elapsedMinutes)} min. Ending now moves this
            session to Payment Due; the server confirms the final time and amount before saving.
          </p>
        </div>
      )}
      confirmLabel="End session"
      danger
      busy={busy}
      onConfirm={onConfirm}
      onCancel={onCancel}
    />
  );
}
