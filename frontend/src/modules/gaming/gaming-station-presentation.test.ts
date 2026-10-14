import { describe, expect, it } from 'vitest';
import { gamingStationStatus } from './gaming-station-presentation';

describe('Gaming station header status', () => {
  it('shows availability only when there is no session', () => {
    expect(gamingStationStatus(true)).toEqual({ label: 'Available', dotClassName: 'bg-accent-good' });
    expect(gamingStationStatus(false)).toEqual({ label: 'Inactive', dotClassName: 'bg-fg-muted' });
  });

  it('keeps live and unpaid work visible even when the station is disabled', () => {
    for (const active of [true, false]) {
      expect(gamingStationStatus(active, 'active')).toEqual({ label: 'Active', dotClassName: 'bg-accent-good' });
      expect(gamingStationStatus(active, 'paused')).toEqual({ label: 'Paused', dotClassName: 'bg-fg-muted' });
      expect(gamingStationStatus(active, 'ended')).toEqual({ label: 'Payment due', dotClassName: 'bg-fg' });
      expect(gamingStationStatus(active, 'ended', true)).toEqual({ label: 'Needs review', dotClassName: 'bg-accent-bad' });
    }
  });
});
