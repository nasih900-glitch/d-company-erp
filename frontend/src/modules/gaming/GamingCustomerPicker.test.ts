import { describe, expect, it } from 'vitest';

import type { CustomerDTO } from '@/lib/erp-api';
import {
  buildGamingCustomerStartIdentity,
  clearStartedStationCustomer,
  customerMatchesSearch,
  isLatestCustomerSearch,
} from './GamingCustomerPicker';

const saved: CustomerDTO = {
  id: 'customer-1', name: 'Amina Rahman', phone: '+91 98765 43210',
  email: null, birthday: null, visit_count: 1, total_spent_minor: 0,
  loyalty_points: 0, lifetime_gaming_points_earned: 0, gaming_rank: 'Rookie',
  gaming_rank_floor: 0, next_gaming_rank: null, next_gaming_rank_floor: null,
  points_to_next_gaming_rank: null, last_visit_at: null, notes: null,
};

describe('gaming customer picker contract', () => {
  it('matches saved customers by name and phone', () => {
    expect(customerMatchesSearch(saved, 'amina')).toBe(true);
    expect(customerMatchesSearch(saved, '765 432')).toBe(true);
    expect(customerMatchesSearch(saved, 'different')).toBe(false);
  });

  it('uses only the stable ID for a selected customer', () => {
    expect(buildGamingCustomerStartIdentity(saved, 'forged', '0000')).toEqual({
      customer_id: 'customer-1',
    });
  });

  it('keeps add-new and cleared anonymous starts on snapshot resolution', () => {
    expect(buildGamingCustomerStartIdentity(undefined, ' New guest ', ' 9000000000 ')).toEqual({
      customer_name: 'New guest', customer_phone: '9000000000',
    });
    expect(buildGamingCustomerStartIdentity(undefined, '', '')).toEqual({
      customer_name: undefined, customer_phone: undefined,
    });
  });

  it('rejects a late result after a newer search generation starts', () => {
    expect(isLatestCustomerSearch(4, 5)).toBe(false);
    expect(isLatestCustomerSearch(5, 5)).toBe(true);
  });

  it('clears the completed station selection before its next session', () => {
    const next = clearStartedStationCustomer({ station1: saved, station2: saved }, 'station1');
    expect(next.station1).toBeUndefined();
    expect(next.station2).toBe(saved);
  });
});
