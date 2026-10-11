import { describe, expect, it } from 'vitest';

import { resolveRequiredOpenShift } from '@/lib/operational-context';
import {
  gamingBoardIncompleteMessage,
} from './gaming-board-freshness';
import { loadGamingShiftContext } from './GamingScreen';

const scope = {
  companyId: 'company-1',
  branchId: 'branch-1',
  terminalId: 'terminal-1',
};

describe('Gaming shift-context freshness', () => {
  it('treats a verified empty shift lookup as a complete board refresh', async () => {
    const context = await loadGamingShiftContext(() => resolveRequiredOpenShift({
      scope,
      listOpenShifts: async () => [],
    }));

    expect(context.shiftId).toBeNull();
    expect(context.error).toContain('No shift is open');
    expect(context.refreshIncomplete).toBe(false);
    expect(gamingBoardIncompleteMessage([
      context.refreshIncomplete ? 'open-shift context' : null,
    ])).toBeNull();
  });

  it('keeps a failed server lookup ineligible for board verification', async () => {
    const context = await loadGamingShiftContext(() => resolveRequiredOpenShift({
      scope,
      listOpenShifts: async () => {
        throw new Error('shift lookup unavailable');
      },
    }));

    expect(context.shiftId).toBeNull();
    expect(context.error).toBe('shift lookup unavailable');
    expect(context.refreshIncomplete).toBe(true);
    expect(gamingBoardIncompleteMessage([
      context.refreshIncomplete ? 'open-shift context' : null,
    ])).toBe('The latest Gaming refresh was incomplete (open-shift context).');
  });

  it('does not treat another branch as a verified no-shift result', async () => {
    const context = await loadGamingShiftContext(() => resolveRequiredOpenShift({
      scope,
      stationBranchId: 'branch-2',
      listOpenShifts: async () => [],
    }));

    expect(context.shiftId).toBeNull();
    expect(context.refreshIncomplete).toBe(true);
    expect(context.error).toContain('different branch');
  });
});
