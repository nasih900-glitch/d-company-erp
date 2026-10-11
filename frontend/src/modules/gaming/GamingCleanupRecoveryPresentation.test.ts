import { describe, expect, it } from 'vitest';

import { gamingCleanupPanelMode } from './GamingCleanupRecoveryPanel';

describe('Gaming cleanup recovery presentation', () => {
  it('keeps empty and completed-only cleanup panels compact', () => {
    expect(gamingCleanupPanelMode([], false, null)).toBe('empty');
    expect(gamingCleanupPanelMode(
      [{ status: 'applied' }, { status: 'superseded' }],
      false,
      null,
    )).toBe('history');
  });

  it('keeps review candidates and failures expanded', () => {
    expect(gamingCleanupPanelMode([{ status: 'reported' }], false, null)).toBe('attention');
    expect(gamingCleanupPanelMode([{ status: 'approved' }], false, null)).toBe('attention');
    expect(gamingCleanupPanelMode([{ status: 'applied' }], false, 'Refresh failed'))
      .toBe('error');
  });
});
