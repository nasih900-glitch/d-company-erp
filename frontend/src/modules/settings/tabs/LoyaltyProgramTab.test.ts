import { describe, expect, it, vi } from 'vitest';

import type { LoyaltyProgramRulesDTO } from '@/lib/erp-api';

vi.mock('@/lib/erp-api', () => ({ settings: {} }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({ me: null }) }));
vi.mock('@/lib/retry-drafts', () => ({ createOperationKey: () => 'test-key' }));

import {
  summarizeLoyaltyProgramChanges,
  validateLoyaltyProgramDraft,
} from './LoyaltyProgramTab';

function legacyRules(): LoyaltyProgramRulesDTO {
  return {
    gaming_earn_points: 2,
    gaming_earn_rupees: 10,
    minor_per_point: 10,
    ranks: [
      { key: 'rookie', label: 'Rookie', threshold: 0, bonus_points: 0 },
      { key: 'player', label: 'Player', threshold: 200, bonus_points: 50 },
      { key: 'pro', label: 'Pro', threshold: 500, bonus_points: 150 },
      { key: 'legend', label: 'Legend', threshold: 1_000, bonus_points: 300 },
    ],
    rewards: [
      {
        key: 'extra_controller',
        label: 'Controller',
        description: 'Controller credit',
        points_cost: 50,
        value_minor: 3_000,
        min_rank_key: 'rookie',
        enabled: true,
      },
    ],
  };
}

describe('points program owner review', () => {
  it('lists frozen earning rank reward eligibility and exact rupee changes', () => {
    const before = legacyRules();
    const after: LoyaltyProgramRulesDTO = {
      ...before,
      gaming_earn_points: 3,
      minor_per_point: 11,
      ranks: before.ranks.map((rank) => rank.key === 'player'
        ? { ...rank, threshold: 250, bonus_points: 75 }
        : rank),
      rewards: before.rewards.map((reward) => ({
        ...reward,
        points_cost: 55,
        value_minor: 3_050,
        min_rank_key: 'player',
        enabled: false,
      })),
    };

    const summary = summarizeLoyaltyProgramChanges(before, after).join('\n');

    expect(summary).toContain('Gaming earn: 2 per ₹10 → 3 per ₹10');
    expect(summary).toContain('100-point value: ₹10.00 → ₹11.00');
    expect(summary).toContain('threshold 200 → 250');
    expect(summary).toContain('bonus 50 → 75');
    expect(summary).toContain('credit ₹30.00 → ₹30.50');
    expect(summary).toContain('minimum rank rookie → player');
    expect(summary).toContain('disabled');
  });

  it('blocks zero values and non-increasing rank thresholds before review', () => {
    const before = legacyRules();
    const invalid = {
      ...before,
      minor_per_point: 0,
      ranks: before.ranks.map((rank) => rank.key === 'player'
        ? { ...rank, threshold: 0 }
        : rank),
    };

    expect(validateLoyaltyProgramDraft(invalid)).toEqual(expect.arrayContaining([
      expect.stringContaining('Value of 100 points'),
      expect.stringContaining('player threshold must be above rookie'),
    ]));
  });
});
