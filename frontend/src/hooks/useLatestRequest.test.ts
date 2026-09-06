import { describe, expect, it } from 'vitest';
import { LatestRequestGate } from './useLatestRequest';

describe('latest verified snapshot', () => {
  it('ignores a delayed initial response after a newer realtime total arrives', async () => {
    const gate = new LatestRequestGate();
    let resolveOld!: (value: number) => void;
    let displayedTotal = 0;
    const oldResponse = new Promise<number>((resolve) => { resolveOld = resolve; });
    const load = async (response: Promise<number>) => {
      const isCurrent = gate.begin();
      const amount = await response;
      if (isCurrent()) displayedTotal = amount;
    };
    const older = load(oldResponse);
    await load(Promise.resolve(22222));
    resolveOld(11111);
    await older;
    expect(displayedTotal).toBe(22222);
  });

  it('prevents late failures and loading callbacks after a screen is left', () => {
    const gate = new LatestRequestGate();
    const isCurrent = gate.begin();
    gate.invalidate();
    expect(isCurrent()).toBe(false);
    expect(gate.begin()()).toBe(true);
  });

  it('invalidates a prior online response when a replacement load exits offline or loses scope', async () => {
    const gate = new LatestRequestGate();
    const onlineResponse = gate.begin();
    // Offline/permission preflight must begin a new generation before returning.
    gate.begin();
    expect(onlineResponse()).toBe(false);
  });
});
