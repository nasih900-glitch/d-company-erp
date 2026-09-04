import { describe, expect, it } from 'vitest';

import type { ShiftDTO, TerminalDTO } from './erp-api';
import {
  beginRealtimeShiftRefresh,
  bindPosLocalWorkShift,
  canCompletePosDraftHydrationAfterLoadFailure,
  canReconcileRealtimePosShift,
  hasOrdinaryPosDraftShiftConflict,
  invalidateRealtimeShiftRefresh,
  resolvePosAccountableShiftId,
  resolveOpenShift,
  resolveRealtimeOpenShift,
  resolveRequiredOpenShift,
  resolveTerminal,
  shiftResolutionMessage,
  terminalResolutionMessage,
} from './operational-context';

const branchA = 'branch-a';
const branchB = 'branch-b';
const terminalA = 'terminal-a';
const terminalB = 'terminal-b';

function terminal(
  id: string,
  branch_id: string,
  overrides: Partial<TerminalDTO> = {},
): TerminalDTO {
  return {
    id,
    branch_id,
    name: id,
    purpose: 'hybrid',
    is_active: true,
    device_id: null,
    last_seen_at: null,
    ...overrides,
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

  it('uses the sole active Hybrid terminal and replaces a stale stored id', () => {
    expect(resolveTerminal(
      branchA,
      'inactive-legacy',
      [
        terminal('inactive-legacy', branchA, { is_active: false, purpose: 'gaming' }),
        terminal(terminalA, branchA),
      ],
      { mode: 'single_hybrid' },
    )).toEqual({
      kind: 'ready', terminalId: terminalA, source: 'single',
    });
  });

  it('blocks a sole active split-purpose terminal in the single-Hybrid profile', () => {
    const result = resolveTerminal(
      branchA,
      terminalA,
      [terminal(terminalA, branchA, { purpose: 'gaming' })],
      { mode: 'single_hybrid' },
    );

    expect(result.kind).toBe('hybrid_required');
    expect(terminalResolutionMessage(result)).toContain('Combined mode');
  });

  it('blocks duplicate active terminals even when the stored id matches one', () => {
    const result = resolveTerminal(
      branchA,
      terminalA,
      [terminal(terminalA, branchA), terminal(terminalB, branchA)],
      { mode: 'single_hybrid' },
    );

    expect(result.kind).toBe('configuration_conflict');
    expect(terminalResolutionMessage(result)).toContain('one shared Combined register');
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
    })).rejects.toThrow('Open a shift from the Shift tab');
  });

  it('keeps the ordinary no-shift message simple but names a real device conflict', () => {
    const noShift = shiftResolutionMessage({ kind: 'no_open_shift' });
    const duplicate = shiftResolutionMessage({
      kind: 'ambiguous_open_shifts',
      shifts: [shift({ id: 'one' }), shift({ id: 'two' })],
    });

    expect(noShift).toContain('No shift is open.');
    expect(noShift.toLowerCase()).not.toContain('terminal');
    expect(duplicate).toContain('this terminal');
  });

  it('rejects when duplicate exact-scope shifts already exist', async () => {
    await expect(resolveRequiredOpenShift({
      scope: { companyId: 'company-a', branchId: branchA, terminalId: terminalA },
      listOpenShifts: async () => [shift({ id: 'one' }), shift({ id: 'two' })],
    })).rejects.toThrow('More than one open shift exists for this terminal');
  });
});

describe('realtime open shift reconciliation', () => {
  it('keeps ordinary cart accountability on its first shift through close and replacement', () => {
    const boundToA = bindPosLocalWorkShift(null, 'shift-a');
    expect(boundToA).toBe('shift-a');
    expect(bindPosLocalWorkShift(boundToA, 'shift-b')).toBe('shift-a');
    expect(resolvePosAccountableShiftId({
      checkoutRecoveryShiftId: null,
      localWorkShiftId: boundToA,
      currentShiftId: null,
    })).toBe('shift-a');
    expect(resolvePosAccountableShiftId({
      checkoutRecoveryShiftId: null,
      localWorkShiftId: boundToA,
      currentShiftId: 'shift-b',
    })).toBe('shift-a');

    expect(resolveRealtimeOpenShift({
      currentShiftId: null,
      accountableLocalShiftId: boundToA,
      hasLocalShiftWork: true,
      branchId: branchA,
      terminalId: terminalA,
      openShifts: [shift({ id: 'shift-b' })],
    }).kind).toBe('local_work_conflict');

    // The same identity is written back to the durable draft while no shift is
    // open. On a later reload under B, it is preserved and locked rather than
    // silently restored/rebilled under the new shift.
    expect(hasOrdinaryPosDraftShiftConflict({
      hasCheckoutRecovery: false,
      storedShiftId: boundToA,
      resolvedShiftId: 'shift-b',
    })).toBe(true);
    expect(hasOrdinaryPosDraftShiftConflict({
      hasCheckoutRecovery: false,
      storedShiftId: boundToA,
      resolvedShiftId: null,
    })).toBe(true);
    expect(hasOrdinaryPosDraftShiftConflict({
      hasCheckoutRecovery: true,
      storedShiftId: boundToA,
      resolvedShiftId: 'shift-b',
    })).toBe(false);
  });

  it('allows only the newest overlapping shift refresh to mutate state', () => {
    const generation = { current: 0 };
    const olderCloseResponse = beginRealtimeShiftRefresh(generation);
    const newerOpenResponse = beginRealtimeShiftRefresh(generation);

    expect(olderCloseResponse.isCurrent()).toBe(false);
    expect(newerOpenResponse.isCurrent()).toBe(true);

    invalidateRealtimeShiftRefresh(generation);
    expect(newerOpenResponse.isCurrent()).toBe(false);
  });

  it('allows server recovery after an empty mount fails, without releasing an ordinary saved cart', () => {
    expect(canCompletePosDraftHydrationAfterLoadFailure({
      hasStoredDraft: false,
      hasCheckoutRecovery: false,
    })).toBe(true);
    expect(canCompletePosDraftHydrationAfterLoadFailure({
      hasStoredDraft: true,
      hasCheckoutRecovery: true,
    })).toBe(true);
    expect(canCompletePosDraftHydrationAfterLoadFailure({
      hasStoredDraft: true,
      hasCheckoutRecovery: false,
    })).toBe(false);

    const openedAfterFailure = shift({ id: 'opened-after-reconnect' });
    expect(resolveRealtimeOpenShift({
      currentShiftId: null,
      hasLocalShiftWork: false,
      branchId: branchA,
      terminalId: terminalA,
      openShifts: [openedAfterFailure],
    })).toEqual({ kind: 'ready', shift: openedAfterFailure, source: 'single' });
  });

  it('waits for durable POS recovery hydration before reconciling a server shift', () => {
    const savedShift = shift({ id: 'saved-open-shift' });

    expect(canReconcileRealtimePosShift({
      draftHydrated: false,
      companyId: 'company-a',
      branchId: branchA,
      terminalReady: true,
      terminalId: terminalA,
    })).toBe(false);
    // This is the exact unsafe mount state: the payment journal has appeared,
    // but its accountable shift id has not yet been restored into React state.
    expect(resolveRealtimeOpenShift({
      currentShiftId: null,
      hasLocalShiftWork: true,
      branchId: branchA,
      terminalId: terminalA,
      openShifts: [savedShift],
    }).kind).toBe('local_work_conflict');
    expect(canReconcileRealtimePosShift({
      draftHydrated: true,
      companyId: 'company-a',
      branchId: branchA,
      terminalReady: true,
      terminalId: terminalA,
    })).toBe(true);
    expect(resolveRealtimeOpenShift({
      currentShiftId: savedShift.id,
      hasLocalShiftWork: true,
      branchId: branchA,
      terminalId: terminalA,
      openShifts: [savedShift],
    }).kind).toBe('ready');
  });

  it('uses a restored checkout journal shift while the screen shift is still null', () => {
    const savedShift = shift({ id: 'saved-open-shift' });
    expect(resolveRealtimeOpenShift({
      currentShiftId: null,
      accountableLocalShiftId: savedShift.id,
      hasLocalShiftWork: true,
      branchId: branchA,
      terminalId: terminalA,
      openShifts: [savedShift],
    }).kind).toBe('ready');

    expect(resolveRealtimeOpenShift({
      currentShiftId: null,
      accountableLocalShiftId: 'closed-original-shift',
      hasLocalShiftWork: true,
      branchId: branchA,
      terminalId: terminalA,
      openShifts: [savedShift],
    }).kind).toBe('local_work_conflict');
  });

  it('reports a closed selected shift immediately instead of retaining stale POS state', () => {
    expect(resolveRealtimeOpenShift({
      currentShiftId: 'closed-shift',
      hasLocalShiftWork: false,
      branchId: branchA,
      terminalId: terminalA,
      openShifts: [],
    })).toEqual({ kind: 'no_open_shift' });
  });

  it('adopts a newly opened exact-scope shift when POS has no unfinished work', () => {
    const next = shift({ id: 'new-shift' });
    const result = resolveRealtimeOpenShift({
      currentShiftId: null,
      hasLocalShiftWork: false,
      branchId: branchA,
      terminalId: terminalA,
      openShifts: [next],
    });

    expect(result.kind).toBe('ready');
    if (result.kind === 'ready') expect(result.shift.id).toBe('new-shift');
  });

  it('never moves unfinished local POS work onto a different shift', () => {
    const result = resolveRealtimeOpenShift({
      currentShiftId: 'closed-shift',
      hasLocalShiftWork: true,
      branchId: branchA,
      terminalId: terminalA,
      openShifts: [shift({ id: 'replacement-shift' })],
    });

    expect(result.kind).toBe('local_work_conflict');
  });
});
