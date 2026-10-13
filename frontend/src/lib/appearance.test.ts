// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';

import {
  nextAppearancePreference,
  readAppearancePreference,
  saveAppearancePreference,
} from './appearance';

describe('appearance preference', () => {
  it('treats absent and unknown values as system-follow', () => {
    expect(readAppearancePreference({ getItem: () => null })).toBe('system');
    expect(readAppearancePreference({ getItem: () => 'sepia' })).toBe('system');
  });

  it('cycles through system, day and night without inventing a fourth mode', () => {
    expect(nextAppearancePreference('system')).toBe('day');
    expect(nextAppearancePreference('day')).toBe('night');
    expect(nextAppearancePreference('night')).toBe('system');
  });

  it('removes storage when returning to system-follow', () => {
    const calls: string[] = [];
    saveAppearancePreference('system', {
      setItem: (key, value) => calls.push(`set:${key}:${value}`),
      removeItem: (key) => calls.push(`remove:${key}`),
    });
    expect(calls).toEqual(['remove:dcompany-appearance-v1']);
  });

  it('survives blocked storage reads and writes', () => {
    expect(readAppearancePreference({ getItem: () => { throw new Error('blocked'); } }))
      .toBe('system');
    expect(() => saveAppearancePreference('night', {
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => { throw new Error('blocked'); },
    })).not.toThrow();
    expect(document.documentElement.dataset.theme).toBe('night');
  });
});
