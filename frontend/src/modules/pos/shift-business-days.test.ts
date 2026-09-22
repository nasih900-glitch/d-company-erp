import { describe, expect, it } from 'vitest';

import type { ShiftDTO } from '@/lib/erp-api';
import {
  formatShiftBusinessDateTime,
  formatShiftBusinessTime,
  groupShiftsByBusinessDay,
  shiftBusinessDate,
} from './shift-business-days';

function shift(overrides: Partial<ShiftDTO>): ShiftDTO {
  return {
    id: 'shift-1',
    branch_id: 'branch-1',
    terminal_id: 'terminal-1',
    status: 'closed',
    opened_at: '2026-09-21T01:37:21Z',
    closed_at: '2026-09-21T06:30:56Z',
    opening_float_minor: 0,
    expected_minor: 90_000,
    counted_minor: 90_000,
    variance_minor: 0,
    pos_sales_minor: 105_000,
    membership_sales_minor: 0,
    gross_collections_minor: 105_000,
    settled_pos_refunds_minor: 0,
    settled_membership_refunds_minor: 0,
    total_refunds_minor: 0,
    net_collections_minor: 105_000,
    total_sales_minor: 105_000,
    opened_by: 'sameer',
    opened_by_name: 'Sameer',
    opened_by_email: 'sameer@dcompany.local',
    closed_by: 'sameer',
    closed_by_name: 'Sameer',
    closed_by_email: 'sameer@dcompany.local',
    ...overrides,
  };
}

describe('shift business-day presentation', () => {
  it('combines reopened drawer lifecycles without changing their audit rows', () => {
    const morning = shift({
      id: 'morning',
      membership_sales_minor: 5_000,
      gross_collections_minor: 110_000,
      settled_pos_refunds_minor: 3_000,
      settled_membership_refunds_minor: 2_000,
      total_refunds_minor: 5_000,
      net_collections_minor: 105_000,
    });
    const evening = shift({
      id: 'evening',
      opened_at: '2026-09-21T06:36:18Z',
      closed_at: '2026-09-21T16:36:59Z',
      pos_sales_minor: 316_000,
      gross_collections_minor: 316_000,
      net_collections_minor: 316_000,
      counted_minor: 145_000,
      expected_minor: 145_000,
      closed_by: 'nasih',
      closed_by_name: 'Nasih',
    });

    const [day] = groupShiftsByBusinessDay([evening, morning]);

    expect(day.businessDate).toBe('2026-09-21');
    expect(day.shifts.map((row) => row.id)).toEqual(['morning', 'evening']);
    expect(day.firstOpenedAt).toBe(morning.opened_at);
    expect(day.finalClosedAt).toBe(evening.closed_at);
    expect(day.posCollectionsMinor).toBe(421_000);
    expect(day.membershipCollectionsMinor).toBe(5_000);
    expect(day.grossCollectionsMinor).toBe(426_000);
    expect(day.totalRefundsMinor).toBe(5_000);
    expect(day.netCollectionsMinor).toBe(421_000);
    expect(day.firstShift.opened_by_name).toBe('Sameer');
    expect(day.finalShift.closed_by_name).toBe('Nasih');
  });

  it('uses the shop business timezone instead of the browser timezone', () => {
    expect(shiftBusinessDate('2026-09-20T20:00:00Z')).toBe('2026-09-21');
    expect(formatShiftBusinessTime('2026-09-21T06:36:18Z')).toContain('12:06');
    expect(formatShiftBusinessDateTime('2026-09-21T21:06:59Z')).toContain('22 Sept 2026');

    const grouped = groupShiftsByBusinessDay([
      shift({ id: 'after-ist-midnight', opened_at: '2026-09-20T20:00:00Z' }),
      shift({ id: 'before-ist-midnight', opened_at: '2026-09-21T18:00:00Z' }),
    ]);
    expect(grouped).toHaveLength(1);
    expect(grouped[0].businessDate).toBe('2026-09-21');
  });

  it('uses the record with the latest close for the final close time and closer', () => {
    const laterOpened = shift({
      id: 'later-opened',
      opened_at: '2026-09-21T08:00:00Z',
      closed_at: '2026-09-21T09:00:00Z',
      closed_by_name: 'Sameer',
    });
    const laterClosed = shift({
      id: 'later-closed',
      opened_at: '2026-09-21T07:00:00Z',
      closed_at: '2026-09-21T10:00:00Z',
      closed_by_name: 'Nasih',
    });

    const [day] = groupShiftsByBusinessDay([laterOpened, laterClosed]);

    expect(day.finalClosedAt).toBe(laterClosed.closed_at);
    expect(day.finalShift.id).toBe(laterClosed.id);
    expect(day.finalShift.closed_by_name).toBe('Nasih');
  });

  it('keeps the day open until every source shift has a confirmed close', () => {
    const [day] = groupShiftsByBusinessDay([
      shift({ id: 'closed' }),
      shift({
        id: 'open',
        status: 'open',
        opened_at: '2026-09-21T07:00:00Z',
        closed_at: null,
        counted_minor: null,
        variance_minor: null,
      }),
    ]);

    expect(day.hasOpenShift).toBe(true);
    expect(day.hasIncompleteClose).toBe(false);
    expect(day.finalClosedAt).toBeNull();
  });

  it('flags a closed legacy row with no close time for review without making it open', () => {
    const [day] = groupShiftsByBusinessDay([
      shift({
        id: 'closed-with-missing-time',
        status: 'closed',
        closed_at: null,
        closed_by: null,
        closed_by_name: null,
        closed_by_email: null,
      }),
    ]);

    expect(day.hasOpenShift).toBe(false);
    expect(day.hasIncompleteClose).toBe(true);
    expect(day.finalClosedAt).toBeNull();
  });
});
