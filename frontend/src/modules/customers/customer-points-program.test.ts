import { describe, expect, it } from 'vitest';

import { customerPointsProgramSummary } from './customer-points-program';

describe('customer points program summary', () => {
  it('shows the current count, exact configured value, and producing revision', () => {
    expect(customerPointsProgramSummary({
      loyalty_points: 500,
      loyalty_point_value_minor: 11,
      loyalty_program_revision: 4,
    })).toEqual({
      valueLabel: '500 points are currently worth ₹55.00',
      revisionLabel: 'Program revision 4 · the server verifies the value when points are applied',
    });
  });

  it('does not invent a value for an older server projection', () => {
    expect(customerPointsProgramSummary({ loyalty_points: 500 })).toEqual({
      valueLabel: 'Current point value unavailable',
      revisionLabel: 'Refresh from the current server before quoting a redemption value',
    });
  });
});
