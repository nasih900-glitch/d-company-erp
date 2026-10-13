import { describe, expect, it } from 'vitest';

import { gamingPosNavigationIntent, gamingPosNavigationRoute } from './gaming-pos-navigation';

describe('Gaming to POS navigation', () => {
  it('carries the canonical order, session and accountable shift without guessing', () => {
    const intent = {
      orderId: 'order / 7', sessionId: 'session-2', shiftId: 'shift-4', terminalId: 'register-1',
    };
    expect(gamingPosNavigationIntent(gamingPosNavigationRoute(intent).split('?')[1])).toEqual(intent);
  });

  it('rejects partial navigation state', () => {
    expect(gamingPosNavigationIntent('?incoming=order-1&shift=shift-1&terminal=register-1')).toBeNull();
  });
});
