import { describe, expect, it } from 'vitest';

import { runningBillMinor } from './running-bill';

describe('runningBillMinor', () => {
  it('matches backend whole-minute then paise rounding for an hourly session', () => {
    expect(runningBillMinor({
      billingMode: 'hourly',
      ratePerHourMinor: 10_000,
      elapsedMs: 1_000,
    })).toBe(167);
    expect(runningBillMinor({
      billingMode: 'hourly',
      ratePerHourMinor: 10_000,
      elapsedMs: 90_000,
    })).toBe(334);
  });

  it('uses the immutable session rate rather than a later station rate', () => {
    expect(runningBillMinor({
      billingMode: 'hourly',
      ratePerHourMinor: 9_000,
      elapsedMs: 60 * 60_000,
    })).toBe(9_000);
  });

  it('returns the locked package amount without elapsed-time repricing', () => {
    expect(runningBillMinor({
      billingMode: 'package',
      lockedAmountMinor: 15_000,
      ratePerHourMinor: 99_999,
      elapsedMs: 8 * 60 * 60_000,
    })).toBe(15_000);
  });

  it('fails closed when authoritative billing facts are unavailable', () => {
    expect(runningBillMinor({ billingMode: 'hourly', elapsedMs: 60_000 })).toBeNull();
    expect(runningBillMinor({ billingMode: 'package', elapsedMs: 60_000 })).toBeNull();
    expect(runningBillMinor({
      billingMode: 'legacy_ambiguous',
      ratePerHourMinor: 10_000,
      elapsedMs: 60_000,
    })).toBeNull();
  });
});
