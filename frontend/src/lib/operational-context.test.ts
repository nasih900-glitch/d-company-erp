import { describe, expect, it } from 'vitest';

import type { ShiftDTO, TerminalDTO } from './erp-api';
import { resolveOpenShift, resolveRequiredOpenShift, resolveTerminal } from './operational-context';

const branchA = 'branch-a';
const branchB = 'branch-b';
const terminalA = 'terminal-a';
const terminalB = 'terminal-b';

function terminal(id: string, branch_id: string): TerminalDTO {
  return {
    id,
    branch_id,
    name: id,
    purpose: 'hybrid',
    is_active: true,
    device_id: null,
    last_seen_at: null,
  };
}

function shift(overrides: Partial<ShiftDTO> = {}): ShiftDTO {
  return {
    id: 'shift-a',
    branch_id: branchA,
    terminal_id: terminalA,
    status: 'open',
    opened_at: '2026-07-14T10:00:00Z',
    closed_at: null,
    opening_float_minor: 0,
    expected_minor: 0,
    counted_minor: null,
    variance_minor: null,
    pos_sales_minor: 0,
    membership_sales_minor: 0,
    gross_collections_minor: 0,
    settled_pos_refunds_minor: 0,
    settled_membership_refunds_minor: 0,
    total_refunds_minor: 0,
    net_collections_minor: 0,
    total_sales_minor: 0,
    opened_by: 'cashier-a',
    opened_by_name: 'Cashier',
    opened_by_email: null,
    ...overrides,
  };
}

describe('terminal resolution', () => {
  it('keeps a cached terminal only when it belongs to the current branch', () => {
    expect(resolveTerminal(branchA, terminalA, [terminal(terminalA, branchA)])).toEqual({
      kind: 'ready', terminalId: terminalA, source: 'stored',
    });
    expect(resolveTerminal(branchA, terminalB, [terminal(terminalB, branchB)])).toEqual({
      kind: 'no_terminals',
    });
  });

  it('auto-selects only a sole branch terminal', () => {
    expect(resolveTerminal(branchA, null, [terminal(terminalA, branchA)])).toEqual({
      kind: 'ready', terminalId: terminalA, source: 'single',
    });
  });

  it('requires an explicit choice when multiple branch terminals exist', () => {
    const result = resolveTerminal(branchA, null, [
      terminal(terminalA, branchA), terminal(terminalB, branchA),
    ]);
    expect(result.kind).toBe('selection_required');
  });
});

describe('open shift resolution', () => {
  it('keeps a cached shift only for the exact branch and terminal', () => {
    const result = resolveOpenShift({
      storedShiftId: 'shift-a', branchId: branchA, terminalId: terminalA,
      openShifts: [shift()],
    });
    expect(result.kind).toBe('ready');
    if (result.kind === 'ready') expect(result.source).toBe('stored');
  });

  it('rejects cached open shifts from another terminal', () => {
    expect(resolveOpenShift({
      storedShiftId: 'shift-b', branchId: branchA, terminalId: terminalA,
      openShifts: [shift({ id: 'shift-b', terminal_id: terminalB })],
    })).toEqual({ kind: 'no_open_shift' });
  });

  it('rejects open shifts from another branch', () => {
    expect(resolveOpenShift({
      storedShiftId: 'shift-b', branchId: branchA, terminalId: terminalA,
      openShifts: [shift({ id: 'shift-b', branch_id: branchB })],
    })).toEqual({ kind: 'no_open_shift' });
  });

  it('selects the exact compatible shift even when the first row is wrong', () => {
    const correct = shift({ id: 'correct' });
    const result = resolveOpenShift({
      storedShiftId: null, branchId: branchA, terminalId: terminalA,
      openShifts: [shift({ id: 'wrong', terminal_id: terminalB }), correct],
    });
    expect(result.kind).toBe('ready');
    if (result.kind === 'ready') expect(result.shift.id).toBe('correct');
  });

  it('rejects a station from another branch before selecting a shift', () => {
    expect(resolveOpenShift({
      storedShiftId: null, branchId: branchA, terminalId: terminalA,
      stationBranchId: branchB, openShifts: [shift()],
    })).toEqual({ kind: 'station_branch_mismatch' });
  });

  it('rejects duplicate exact-scope open shifts instead of picking one', () => {
    const result = resolveOpenShift({
      storedShiftId: null, branchId: branchA, terminalId: terminalA,
      openShifts: [shift({ id: 'one' }), shift({ id: 'two' })],
    });
    expect(result.kind).toBe('ambiguous_open_shifts');
  });

  it('rejects duplicate exact-scope shifts even when the cache names one of them', () => {
    const result = resolveOpenShift({
      storedShiftId: 'one', branchId: branchA, terminalId: terminalA,
      openShifts: [shift({ id: 'one' }), shift({ id: 'two' })],
    });
    expect(result.kind).toBe('ambiguous_open_shifts');
  });
});

describe('required open shift resolution', () => {
  it('resolves an already-open exact-scope shift without opening one', async () => {
    const result = await resolveRequiredOpenShift({
      scope: { companyId: 'company-a', branchId: branchA, terminalId: terminalA },
      listOpenShifts: async () => [shift({ id: 'existing' })],
    });
    expect(result).toBe('existing');
  });

  it('never opens a shift itself — rejects with an actionable message when none exists', async () => {
    await expect(resolveRequiredOpenShift({
      scope: { companyId: 'company-a', branchId: branchA, terminalId: terminalA },
      listOpenShifts: async () => [],
    })).rejects.toThrow('Open a shift from the Shifts tab');
  });

  it('rejects when duplicate exact-scope shifts already exist', async () => {
    await expect(resolveRequiredOpenShift({
      scope: { companyId: 'company-a', branchId: branchA, terminalId: terminalA },
      listOpenShifts: async () => [shift({ id: 'one' }), shift({ id: 'two' })],
    })).rejects.toThrow('More than one open shift exists for this terminal');
  });
});
