import { describe, expect, it } from 'vitest';

import {
  gamingBoardIncompleteMessage,
  gamingBoardScopeKey,
  type GamingBoardScope,
} from './gaming-board-freshness';

const scope: GamingBoardScope = {
  userId: 'user-1',
  companyId: 'company-1',
  branchId: 'branch-1',
  terminalReady: true,
  terminalId: 'terminal-1',
};

describe('Gaming board freshness policy', () => {
  it.each([
    ['account', { userId: 'user-2' }],
    ['company', { companyId: 'company-2' }],
    ['branch', { branchId: 'branch-2' }],
    ['terminal readiness', { terminalReady: false }],
    ['terminal', { terminalId: 'terminal-2' }],
  ])('invalidates verified freshness when the %s context changes', (_label, change) => {
    expect(gamingBoardScopeKey({ ...scope, ...change })).not.toBe(gamingBoardScopeKey(scope));
  });

  it('marks only a complete response as eligible for verification', () => {
    expect(gamingBoardIncompleteMessage([null, false, undefined])).toBeNull();
    expect(gamingBoardIncompleteMessage(['saved session items', null, 'friend attendance']))
      .toBe('The latest Gaming refresh was incomplete (saved session items, friend attendance).');
  });
});
