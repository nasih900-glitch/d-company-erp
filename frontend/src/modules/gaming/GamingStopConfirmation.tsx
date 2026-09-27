import { ConfirmModal } from '@/components/ui/ConfirmDialog';
import { inr } from '@/lib/inr';

export function GamingStopConfirmation({
  stationName,
  elapsedMinutes,
  estimatedAmountMinor,
  estimatedFriendMinor,
  friendChargesUnverified,
  fixedPrice,
  busy,
  onConfirm,
  onCancel,
}: {
  stationName: string;
  elapsedMinutes: number;
  estimatedAmountMinor: number | null;
  estimatedFriendMinor?: number | null;
  friendChargesUnverified?: boolean;
  fixedPrice: boolean;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const amountDescription = estimatedAmountMinor == null
    ? 'The session amount is not available on this screen and will require review.'
    : fixedPrice
      ? `The fixed package charge is ${inr(estimatedAmountMinor)}.`
      : `The current estimated session charge is ${inr(estimatedAmountMinor)}.`;

  return (
    <ConfirmModal
      title={`End ${stationName}?`}
      message={(
        <div className="space-y-2 text-sm text-fg-muted">
          <p>{amountDescription}</p>
          {estimatedFriendMinor != null && estimatedFriendMinor > 0 && (
            <p>Joined friends add an estimated {inr(estimatedFriendMinor)} controller charge. The server confirms their actual presence and final total when the session ends.</p>
          )}
          {friendChargesUnverified && (
            <p>Friend attendance could not be loaded here. Their controller charge may increase the final server total.</p>
          )}
          <p>
            {Number.isFinite(elapsedMinutes)
              ? `Elapsed time is approximately ${Math.max(1, elapsedMinutes)} min. `
              : 'Elapsed time could not be verified on this screen. '}
            Ending now moves this
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
