import { describe, expect, it } from 'vitest';

import {
  beginGamingRefresh,
  invalidateGamingRefresh,
  type GamingRefreshGenerationRef,
} from './GamingScreen';

describe('Gaming refresh policy', () => {
  it('keeps realtime and poll refreshes behind the last verified board', () => {
    const generation: GamingRefreshGenerationRef = { current: 0 };
    const refresh = beginGamingRefresh(generation, 'background', true);

    expect(refresh.showSkeleton).toBe(false);
    expect(refresh.surfaceFailure).toBe(false);
    expect(refresh.isCurrent()).toBe(true);
  });

  it('shows first-load failures but does not require background refreshes to blank the board', () => {
    const generation: GamingRefreshGenerationRef = { current: 0 };

    const initial = beginGamingRefresh(generation, 'foreground', false);
    expect(initial.showSkeleton).toBe(true);
    expect(initial.surfaceFailure).toBe(true);

    const manual = beginGamingRefresh(generation, 'foreground', true);
    expect(manual.showSkeleton).toBe(false);
    expect(manual.surfaceFailure).toBe(true);
  });

  it('allows only the newest overlapping response to publish and invalidates on cleanup', () => {
    const generation: GamingRefreshGenerationRef = { current: 0 };
    const older = beginGamingRefresh(generation, 'foreground', false);
    const newer = beginGamingRefresh(generation, 'background', false);

    expect(older.isCurrent()).toBe(false);
    expect(newer.isCurrent()).toBe(true);

    invalidateGamingRefresh(generation);
    expect(older.isCurrent()).toBe(false);
    expect(newer.isCurrent()).toBe(false);
  });
});
