// @vitest-environment happy-dom

import { act, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { GamingSecondPulse } from './GamingSecondPulse';

const reactActEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT?: boolean;
};

beforeEach(() => {
  reactActEnvironment.IS_REACT_ACT_ENVIRONMENT = true;
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  document.body.replaceChildren();
  delete reactActEnvironment.IS_REACT_ACT_ENVIRONMENT;
});

describe('GamingSecondPulse', () => {
  it('does not schedule a clock when the subtree has no live timer', () => {
    vi.useFakeTimers();
    const container = document.createElement('div');
    document.body.append(container);
    const root = createRoot(container);
    act(() => root.render(<GamingSecondPulse enabled={false}>{(now) => <span>{now}</span>}</GamingSecondPulse>));
    expect(vi.getTimerCount()).toBe(0);
    act(() => root.unmount());
  });

  it('updates its mounted clock without rendering its parent and cleans up the interval', () => {
    vi.useFakeTimers();
    const initialNow = new Date('2026-10-11T12:00:00.000Z').getTime();
    vi.setSystemTime(initialNow);
    const clearInterval = vi.spyOn(window, 'clearInterval');
    const container = document.createElement('div');
    document.body.append(container);
    const root = createRoot(container);
    let parentRenders = 0;
    let clockRenders = 0;

    function Parent() {
      parentRenders += 1;
      const [label] = useState('Gaming board');
      return (
        <GamingSecondPulse>
          {(now) => {
            clockRenders += 1;
            return <span>{label}:{now}</span>;
          }}
        </GamingSecondPulse>
      );
    }

    act(() => root.render(<Parent />));
    expect(container.textContent).toBe(`Gaming board:${initialNow}`);
    expect(vi.getTimerCount()).toBe(1);

    for (let second = 1; second <= 3; second += 1) {
      act(() => vi.advanceTimersByTime(1_000));
      expect(container.textContent).toBe(`Gaming board:${initialNow + second * 1_000}`);
    }
    expect(container.textContent).toBe(`Gaming board:${initialNow + 3_000}`);
    expect(parentRenders).toBe(1);
    expect(clockRenders).toBe(4);

    act(() => root.unmount());
    expect(clearInterval).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
    act(() => vi.advanceTimersByTime(1_000));
    expect(clockRenders).toBe(4);
  });
});
