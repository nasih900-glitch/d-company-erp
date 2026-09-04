import { describe, expect, it } from 'vitest';

import {
  extraControllerExtensionSurchargeMinor,
  extraControllerSurchargeMinor,
  gamingPackageSelectionLabel,
  resolvePricingTier,
} from './GamingScreen';

describe('gaming tariff controller charges', () => {
  it('charges per started hour with one-hour minimum', () => {
    expect(extraControllerSurchargeMinor(1, 30)).toBe(3_000);
    expect(extraControllerSurchargeMinor(1, 60)).toBe(3_000);
    expect(extraControllerSurchargeMinor(2, 90)).toBe(12_000);
  });

  it('charges only the cumulative increase for an extension', () => {
    expect(extraControllerExtensionSurchargeMinor(1, 30, 30)).toBe(0);
    expect(extraControllerExtensionSurchargeMinor(1, 60, 30)).toBe(3_000);
    expect(extraControllerExtensionSurchargeMinor(1, 90, 30)).toBe(0);
    expect(extraControllerExtensionSurchargeMinor(1, 120, 30)).toBe(3_000);
  });

  it('defaults every fresh or stale selection to Standard when it is offered', () => {
    expect(resolvePricingTier(['standard', 'premium'], undefined)).toBe('standard');
    expect(resolvePricingTier(['standard', 'premium'], 'retired-tier')).toBe('standard');
    expect(resolvePricingTier(['standard', 'premium'], 'premium')).toBe('premium');
  });

  it('labels the immutable tier, mode and multiplayer count after Start', () => {
    const base = {
      station_id: 'station-1',
      start_at: 0,
      status: 'active' as const,
      pausedMs: 0,
      billing_mode: 'package' as const,
    };
    expect(gamingPackageSelectionLabel({
      ...base,
      package_pricing_tier_snapshot: 'standard',
      package_variant_snapshot: 'single',
    })).toBe('Standard · Single');
    expect(gamingPackageSelectionLabel({
      ...base,
      package_pricing_tier_snapshot: 'premium',
      package_variant_snapshot: 'dual',
      extra_controllers: 2,
    })).toBe('Premium · 4 players');
  });
});
