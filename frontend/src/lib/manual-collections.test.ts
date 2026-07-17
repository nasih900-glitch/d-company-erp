import { describe, expect, it } from 'vitest';

import type { ManualCollectionDTO } from './erp-api';
import {
  defaultManualCollectionReference,
  dateISOInTimeZone,
  manualCollectionTotals,
  rupeesToMinor,
} from './manual-collections';

function collection(overrides: Partial<ManualCollectionDTO> = {}): ManualCollectionDTO {
  return {
    id: 'collection-1',
    company_id: 'company-1',
    branch_id: 'branch-1',
    business_date: '2026-07-16',
    method: 'cash',
    amount_minor: 21_000,
    source_kind: 'manual_daily',
    source_ref: 'Daily collection 2026-07-16 Cash',
    idempotency_key: 'manual-collection-1',
    note: null,
    created_by: 'user-1',
    created_by_name: 'Cashier',
    created_at: '2026-07-16T12:00:00Z',
    voided_at: null,
    voided_by: null,
    voided_by_name: null,
    void_reason: null,
    is_voided: false,
    ...overrides,
  };
}

describe('manual collection money parsing', () => {
  it('converts rupees to integer paise without floating-point rounding', () => {
    expect(rupeesToMinor('210')).toBe(21_000);
    expect(rupeesToMinor('1620.05')).toBe(162_005);
    expect(rupeesToMinor('0.01')).toBe(1);
  });

  it('rejects zero, negatives, exponents and more than two decimals', () => {
    expect(rupeesToMinor('0')).toBeNull();
    expect(rupeesToMinor('-1')).toBeNull();
    expect(rupeesToMinor('1e3')).toBeNull();
    expect(rupeesToMinor('1.001')).toBeNull();
  });
});

describe('manual collection dates and references', () => {
  it('uses the company timezone when IST has crossed into the next business day', () => {
    const instant = new Date('2026-07-15T20:00:00Z');

    expect(dateISOInTimeZone(instant, 'Asia/Kolkata')).toBe('2026-07-16');
    expect(dateISOInTimeZone(instant, 'Europe/London')).toBe('2026-07-15');
    expect(dateISOInTimeZone(instant, 'America/Los_Angeles')).toBe('2026-07-15');
  });

  it('creates an auditable default reference', () => {
    expect(defaultManualCollectionReference('2026-07-16', 'upi'))
      .toBe('Daily collection 2026-07-16 UPI');
  });
});

describe('manual collection totals', () => {
  it('totals active collections by method and excludes voided rows', () => {
    const totals = manualCollectionTotals([
      collection(),
      collection({ id: 'collection-2', method: 'upi', amount_minor: 162_000 }),
      collection({ id: 'collection-3', method: 'cash', amount_minor: 50_000, is_voided: true }),
    ]);

    expect(totals).toEqual({
      cash_minor: 21_000,
      upi_minor: 162_000,
      card_minor: 0,
      bank_minor: 0,
      total_minor: 183_000,
      active_count: 2,
      voided_count: 1,
    });
  });
});
