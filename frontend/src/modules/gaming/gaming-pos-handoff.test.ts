import { describe, expect, it } from 'vitest';

import { resolveGamingPosRoute } from './gaming-pos-handoff';

describe('Gaming to POS terminal selection', () => {
  const cafe = { id: 'cafe-pos', branch_id: 'main-shop', purpose: 'cafe_pos' as const };
  const gaming = { id: 'gaming-area', branch_id: 'main-shop', purpose: 'gaming' as const };

  it('requires an explicit destination from a Gaming Area terminal', () => {
    expect(resolveGamingPosRoute({
      currentTerminalId: gaming.id,
      stationBranchId: 'main-shop',
      terminals: [cafe, gaming],
      allowCrossTerminalHandoff: true,
    })).toBe('handoff');
  });

  it('keeps billing local on Cafe POS even when Gaming Area also exists', () => {
    expect(resolveGamingPosRoute({
      currentTerminalId: cafe.id,
      stationBranchId: 'main-shop',
      terminals: [cafe, gaming],
      allowCrossTerminalHandoff: true,
    })).toBe('local');
  });

  it('keeps a general-purpose legacy terminal local', () => {
    expect(resolveGamingPosRoute({
      currentTerminalId: 'general',
      stationBranchId: 'main-shop',
      terminals: [{ id: 'general', branch_id: 'main-shop', purpose: 'hybrid' }],
      allowCrossTerminalHandoff: false,
    })).toBe('local');
  });

  it('blocks split-purpose terminals in the single-Hybrid profile', () => {
    expect(resolveGamingPosRoute({
      currentTerminalId: gaming.id,
      stationBranchId: 'main-shop',
      terminals: [gaming],
      allowCrossTerminalHandoff: false,
    })).toBe('profile_conflict');
    expect(resolveGamingPosRoute({
      currentTerminalId: cafe.id,
      stationBranchId: 'main-shop',
      terminals: [cafe],
      allowCrossTerminalHandoff: false,
    })).toBe('profile_conflict');
  });

  it('blocks local fallback when the selected terminal is not verified', () => {
    expect(resolveGamingPosRoute({
      currentTerminalId: gaming.id,
      stationBranchId: 'main-shop',
      terminals: [{ id: 'other', branch_id: 'other-shop', purpose: 'cafe_pos' }],
      allowCrossTerminalHandoff: false,
    })).toBe('terminal_unverified');
  });
});
