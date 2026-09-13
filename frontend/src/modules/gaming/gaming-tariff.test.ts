import { describe, expect, it } from 'vitest';

import {
  extraControllerExtensionSurchargeMinor,
  extraControllerSurchargeMinor,
  eligibleNewStartPackages,
  gamingModeLabel,
  gamingPackageSelectionLabel,
  requiresFixedGamingTariff,
} from './GamingScreen';

describe('gaming tariff controller charges', () => {
  it('charges per started hour with one-hour minimum', () => {
    expect(extraControllerSurchargeMinor(1, 30)).toBe(3_000);
    expect(extraControllerSurchargeMinor(1, 60)).toBe(3_000);
    expect(extraControllerSurchargeMinor(1, 61)).toBe(6_000);
    expect(extraControllerSurchargeMinor(1, 90)).toBe(6_000);
    expect(extraControllerSurchargeMinor(1, 120)).toBe(6_000);
    expect(extraControllerSurchargeMinor(1, 121)).toBe(9_000);
    expect(extraControllerSurchargeMinor(2, 90)).toBe(12_000);
  });

  it('charges only the cumulative increase for an extension', () => {
    expect(extraControllerExtensionSurchargeMinor(1, 30, 30)).toBe(0);
    expect(extraControllerExtensionSurchargeMinor(1, 60, 30)).toBe(3_000);
    expect(extraControllerExtensionSurchargeMinor(1, 90, 30)).toBe(0);
    expect(extraControllerExtensionSurchargeMinor(1, 120, 30)).toBe(3_000);
  });

  it('uses the printed customer-facing names for the fixed modes', () => {
    expect(gamingModeLabel('simdrive')).toBe('Racing Sim');
    expect(gamingModeLabel('vr_racing')).toBe('VR Racing Sim');
    expect(gamingModeLabel('vr_games')).toBe('VR Games');
  });

  it('fails closed against stale Premium and uncoded fixed-tariff rows', () => {
    const packageRow = {
      id: 'standard', code: 'vr-games-session-15m', station_type: 'vr',
      pricing_tier: 'standard', variant: 'vr_games', included_players: 1,
      max_players: 1, kind: 'base' as const, name: 'VR Games',
      duration_minutes: 15, price_minor: 8_000,
    };
    expect(eligibleNewStartPackages([
      packageRow,
      { ...packageRow, id: 'premium', pricing_tier: 'premium' },
      { ...packageRow, id: 'uncoded', code: '' },
      { ...packageRow, id: 'extension', kind: 'extension' },
    ], 'vr').map((item) => item.id)).toEqual(['standard']);
    expect(requiresFixedGamingTariff('ps5')).toBe(true);
    expect(requiresFixedGamingTariff('simulator')).toBe(true);
    expect(requiresFixedGamingTariff('vr')).toBe(true);
    expect(requiresFixedGamingTariff('streaming')).toBe(false);
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
    })).toBe('Single');
    expect(gamingPackageSelectionLabel({
      ...base,
      package_pricing_tier_snapshot: 'premium',
      package_variant_snapshot: 'dual',
      extra_controllers: 2,
    })).toBe('Premium · 4 players');
    expect(gamingPackageSelectionLabel({
      ...base,
      package_pricing_tier_snapshot: 'standard',
      package_variant_snapshot: 'vr_racing',
    })).toBe('VR Racing Sim');
  });
});
