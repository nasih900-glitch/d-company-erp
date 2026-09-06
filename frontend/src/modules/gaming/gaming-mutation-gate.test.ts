import { describe, expect, it } from 'vitest';

import { enterGamingMutation, leaveGamingMutation } from './gaming-mutation-gate';

describe('Gaming mutation gate', () => {
  it('admits one action synchronously and allows another only after release', () => {
    const gate = { current: false };

    expect(enterGamingMutation(gate)).toBe(true);
    expect(enterGamingMutation(gate)).toBe(false);

    leaveGamingMutation(gate);
    expect(enterGamingMutation(gate)).toBe(true);
  });
});
