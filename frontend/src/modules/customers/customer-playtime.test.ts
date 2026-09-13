import { describe, expect, it } from 'vitest';

import { draftPlaytimeMessage, formatPlayMinutes } from './customer-playtime';

const customer = {
  rank: 1,
  customer_id: 'customer-1',
  name: 'Asha',
  masked_phone: '••••••3210',
  total_played_minutes: 625,
  qualifying_paid_minutes: 600,
  draft_estimated_reward_minutes: 60,
};

const program = {
  status: 'draft' as const,
  rewards_enabled: false as const,
  messaging_enabled: false as const,
  threshold_paid_minutes: 600,
  reward_minutes: 60,
  company_whatsapp_phone: null,
  message_template_preview: '',
};

describe('customer playtime draft copy', () => {
  it('formats recorded minutes without rounding up', () => {
    expect(formatPlayMinutes(59)).toBe('59m');
    expect(formatPlayMinutes(60)).toBe('1h');
    expect(formatPlayMinutes(625)).toBe('10h 25m');
  });

  it('states that the estimate is neither issued nor sent', () => {
    const message = draftPlaytimeMessage(customer, program);
    expect(message).toContain('Draft estimate: 1h');
    expect(message).toContain('no reward has been issued');
    expect(message).toContain('has not been sent');
  });

  it('recalculates the preview from edited draft figures without implying a balance', () => {
    const message = draftPlaytimeMessage(customer, {
      ...program,
      threshold_paid_minutes: 300,
      reward_minutes: 30,
    });
    expect(message).toContain('Draft estimate: 1h');
    expect(message).toContain('5h paid = 30m free plan');
  });
});
