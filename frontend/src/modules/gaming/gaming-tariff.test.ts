import { describe, expect, it } from 'vitest';

import {
  extraControllerExtensionSurchargeMinor,
  extraControllerSurchargeMinor,
  eligibleNewStartPackages,
  eligiblePaidExtensions,
  eligiblePs5ShortenTarget,
  estimatedFriendControllerMinor,
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

  it('offers only the exact published 60-to-30-minute PS5 tariff before 30 actual minutes', () => {
    const session = {
      station_id: 'station-1', start_at: 0, status: 'active' as const, pausedMs: 0,
      billing_mode: 'package' as const, package_station_type_snapshot: 'ps5',
      package_pricing_tier_snapshot: 'standard' as const,
      package_variant_snapshot: 'single', package_price_minor_snapshot: 12_000,
      package_duration_minutes_snapshot: 60, timer_minutes: 60,
      locked_amount_minor: 12_000, pause_version: 0, participant_revision: 0,
      billing_revision: 0,
    };
    const target = {
      id: 'target', code: 'standard-single-session-30m', station_type: 'ps5',
      pricing_tier: 'standard', variant: 'single', included_players: 1,
      max_players: 4, kind: 'base' as const, name: 'Single 30 min',
      duration_minutes: 30, price_minor: 8_000,
    };
    const roster = { session_id: 'session-1', participant_revision: 0,
      active_friend_count: 0, current_player_count: 1, max_player_count: 4, participants: [] };
    expect(eligiblePs5ShortenTarget(session, [target], 1_799_999, roster)?.id).toBe('target');
    expect(eligiblePs5ShortenTarget(session, [target], 1_800_000, roster)).toBeNull();
    expect(eligiblePs5ShortenTarget({ ...session, billing_revision: 1 }, [target], 1_000, roster)).toBeNull();
    expect(eligiblePs5ShortenTarget({ ...session, participant_revision: 1 }, [target], 1_000, roster)).toBeNull();
    expect(eligiblePs5ShortenTarget(session, [{ ...target, price_minor: 7_999 }], 1_000, roster)).toBeNull();
    expect(eligiblePs5ShortenTarget(session, [target], 1_000, undefined)).toBeNull();
  });

  it('estimates joined friend charges by cumulative pause-excluded playtime per customer', () => {
    const rows = [
      { id: 'visit-1', customer_id: 'friend-1', joined_at: '', joined_play_elapsed_ms: 0,
        left_at: 'later', left_play_elapsed_ms: 1_800_000, join_revision: 1, leave_revision: 2 },
      { id: 'visit-2', customer_id: 'friend-1', joined_at: '', joined_play_elapsed_ms: 2_000_000,
        left_at: null, left_play_elapsed_ms: null, join_revision: 3, leave_revision: null },
      { id: 'visit-3', customer_id: 'friend-2', joined_at: '', joined_play_elapsed_ms: 3_000_000,
        left_at: null, left_play_elapsed_ms: null, join_revision: 4, leave_revision: null },
    ];
    const state = { session_id: 'session-1', participant_revision: 4,
      active_friend_count: 2, current_player_count: 3, max_player_count: 4, participants: rows };
    // friend-1: 30 + 45 minutes -> two started hours, friend-2: 28 minutes -> one.
    expect(estimatedFriendControllerMinor(state, 4_700_000)).toBe(9_000);
    expect(estimatedFriendControllerMinor(undefined, 4_700_000)).toBeNull();
  });

  it('offers paid Racing, VR Games, and VR Racing extensions only for the booked mode', () => {
    const template = {
      id: 'extension', code: 'extension-code', station_type: 'simulator',
      pricing_tier: 'standard', variant: 'simdrive', included_players: 1,
      max_players: 1, kind: 'extension' as const, name: 'Paid extension',
      duration_minutes: 15, price_minor: 7_000,
    };
    const catalog = [
      template,
      { ...template, id: 'vr-games', station_type: 'vr', variant: 'vr_games', price_minor: 8_000 },
      { ...template, id: 'vr-racing', variant: 'vr_racing', price_minor: 10_000 },
      { ...template, id: 'premium', pricing_tier: 'premium' },
      { ...template, id: 'uncoded', code: '' },
    ];
    const session = { station_id: 'station-1', start_at: 0, status: 'active' as const,
      pausedMs: 0, billing_mode: 'package' as const,
      package_pricing_tier_snapshot: 'standard' as const };
    expect(eligiblePaidExtensions(catalog, { ...session, package_variant_snapshot: 'simdrive' },
      'simulator').map((item) => item.id)).toEqual(['extension']);
    expect(eligiblePaidExtensions(catalog, { ...session, package_variant_snapshot: 'vr_games' },
      'vr').map((item) => item.id)).toEqual(['vr-games']);
    expect(eligiblePaidExtensions(catalog, { ...session, package_variant_snapshot: 'vr_racing' },
      'simulator').map((item) => item.id)).toEqual(['vr-racing']);
  });
});
