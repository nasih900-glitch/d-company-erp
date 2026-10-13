import { inr } from '@/lib/inr';

export interface CustomerPointsProgramProjection {
  loyalty_points: number;
  loyalty_program_revision?: number;
  loyalty_point_value_minor?: number;
}

export function customerPointsProgramSummary(customer: CustomerPointsProgramProjection): {
  valueLabel: string;
  revisionLabel: string;
} {
  const revision = customer.loyalty_program_revision;
  const pointValue = customer.loyalty_point_value_minor;
  if (
    revision == null || !Number.isSafeInteger(revision) || revision < 0 ||
    pointValue == null || !Number.isSafeInteger(pointValue) || pointValue <= 0
  ) {
    return {
      valueLabel: 'Current point value unavailable',
      revisionLabel: 'Refresh from the current server before quoting a redemption value',
    };
  }
  return {
    valueLabel: `${customer.loyalty_points} points are currently worth ${inr(customer.loyalty_points * pointValue)}`,
    revisionLabel: `Program revision ${revision} · the server verifies the value when points are applied`,
  };
}
