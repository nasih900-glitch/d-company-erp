export function gamingStationStatus(
  isActive: boolean,
  sessionStatus?: 'active' | 'paused' | 'ended',
  billingMissing = false,
): { label: string; dotClassName: string } {
  if (sessionStatus === 'ended') return billingMissing
    ? { label: 'Needs review', dotClassName: 'bg-accent-bad' }
    : { label: 'Payment due', dotClassName: 'bg-fg' };
  if (sessionStatus === 'paused') return { label: 'Paused', dotClassName: 'bg-fg-muted' };
  if (sessionStatus === 'active') return { label: 'Active', dotClassName: 'bg-accent-good' };
  return isActive
    ? { label: 'Available', dotClassName: 'bg-accent-good' }
    : { label: 'Inactive', dotClassName: 'bg-fg-muted' };
}
