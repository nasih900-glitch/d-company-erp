import type { ShiftDTO, TerminalDTO } from './erp-api';

const TERMINAL_KEY = 'terminal_id';
const LEGACY_SHIFT_KEY = 'shift_id';
const SHIFT_CONTEXT_KEY = 'operational_shift:v1';

export interface OperationalScope {
  companyId: string;
  branchId: string;
  terminalId: string;
}

interface StoredShiftContextV1 extends OperationalScope {
  shiftId: string;
}

export type TerminalResolution =
  | { kind: 'ready'; terminalId: string; source: 'stored' | 'single' }
  | { kind: 'missing_branch' }
  | { kind: 'no_terminals' }
  | { kind: 'hybrid_required'; terminal: TerminalDTO }
  | { kind: 'configuration_conflict'; terminals: TerminalDTO[] }
  | { kind: 'selection_required'; terminals: TerminalDTO[] };

export interface TerminalResolutionPolicy {
  mode: 'selectable' | 'single_hybrid';
}

const DEFAULT_TERMINAL_POLICY: Readonly<TerminalResolutionPolicy> = Object.freeze({
  mode: 'selectable',
});

export type ShiftResolution =
  | { kind: 'ready'; shift: ShiftDTO; source: 'stored' | 'single' }
  | { kind: 'missing_branch' }
  | { kind: 'missing_terminal' }
  | { kind: 'station_branch_mismatch' }
  | { kind: 'no_open_shift' }
  | { kind: 'ambiguous_open_shifts'; shifts: ShiftDTO[] };

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function readStoredTerminalId(): string | null {
  try {
    return storage()?.getItem(TERMINAL_KEY) || null;
  } catch {
    return null;
  }
}

export function storeTerminalId(terminalId: string): void {
  const target = storage();
  if (!target) return;
  try {
    if (target.getItem(TERMINAL_KEY) !== terminalId) clearStoredShift();
    target.setItem(TERMINAL_KEY, terminalId);
  } catch {
    // POS will surface a precise terminal error when browser storage is unavailable.
  }
}

/**
 * Drops this device's terminal identity and the shift context bound to it.
 * Call this ONLY when the server has proven the stored terminal is not usable
 * for the current branch — never on an auth failure or a failed request. The
 * terminal ID is device identity, not a credential: clearing it there left a
 * multi-terminal branch unable to open POS and made the saved cart look lost.
 */
export function clearStoredTerminal(): void {
  try {
    storage()?.removeItem(TERMINAL_KEY);
  } catch {
    // Best-effort cleanup for private browsing and storage-disabled browsers.
  }
  clearStoredShift();
}

export function readStoredShiftId(scope: OperationalScope): string | null {
  const target = storage();
  if (!target) return null;
  try {
    const raw = target.getItem(SHIFT_CONTEXT_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<StoredShiftContextV1>;
      if (
        parsed.companyId === scope.companyId
        && parsed.branchId === scope.branchId
        && parsed.terminalId === scope.terminalId
        && typeof parsed.shiftId === 'string'
      ) {
        return parsed.shiftId;
      }
    }
    // One-time compatibility candidate. It is never accepted until the resolver
    // proves the legacy ID is open for this exact branch and terminal.
    return target.getItem(LEGACY_SHIFT_KEY) || null;
  } catch {
    return null;
  }
}

export function storeShiftId(scope: OperationalScope, shiftId: string): void {
  const target = storage();
  if (!target) return;
  const value: StoredShiftContextV1 = { ...scope, shiftId };
  try {
    target.setItem(SHIFT_CONTEXT_KEY, JSON.stringify(value));
    target.setItem(LEGACY_SHIFT_KEY, shiftId);
  } catch {
    // The current in-memory shift remains usable for this page load.
  }
}

export function clearStoredShift(): void {
  const target = storage();
  if (!target) return;
  try {
    target.removeItem(SHIFT_CONTEXT_KEY);
    target.removeItem(LEGACY_SHIFT_KEY);
  } catch {
    // Best-effort cleanup for private browsing and storage-disabled browsers.
  }
}

export function resolveTerminal(
  branchId: string | null,
  storedTerminalId: string | null,
  terminals: TerminalDTO[],
  policy: Readonly<TerminalResolutionPolicy> = DEFAULT_TERMINAL_POLICY,
): TerminalResolution {
  if (!branchId) return { kind: 'missing_branch' };
  // Inactive terminal rows remain useful audit history, but they must never
  // become this browser's operational scope.
  const matching = terminals.filter(
    (terminal) => terminal.branch_id === branchId && terminal.is_active,
  );

  if (policy.mode === 'single_hybrid') {
    if (matching.length === 0) return { kind: 'no_terminals' };
    if (matching.length > 1) {
      return { kind: 'configuration_conflict', terminals: matching };
    }
    if (matching[0].purpose !== 'hybrid') {
      return { kind: 'hybrid_required', terminal: matching[0] };
    }
    return {
      kind: 'ready',
      terminalId: matching[0].id,
      source: storedTerminalId === matching[0].id ? 'stored' : 'single',
    };
  }

  if (storedTerminalId && matching.some((terminal) => terminal.id === storedTerminalId)) {
    return { kind: 'ready', terminalId: storedTerminalId, source: 'stored' };
  }
  if (matching.length === 1) {
    return { kind: 'ready', terminalId: matching[0].id, source: 'single' };
  }
  if (matching.length === 0) return { kind: 'no_terminals' };
  return { kind: 'selection_required', terminals: matching };
}

export function resolveOpenShift({
  storedShiftId,
  branchId,
  terminalId,
  stationBranchId,
  openShifts,
}: {
  storedShiftId: string | null;
  branchId: string | null;
  terminalId: string | null;
  stationBranchId?: string | null;
  openShifts: ShiftDTO[];
}): ShiftResolution {
  if (!branchId) return { kind: 'missing_branch' };
  if (!terminalId) return { kind: 'missing_terminal' };
  if (stationBranchId && stationBranchId !== branchId) {
    return { kind: 'station_branch_mismatch' };
  }

  const matching = openShifts.filter((shift) => (
    shift.status === 'open'
    && shift.branch_id === branchId
    && shift.terminal_id === terminalId
    && (!stationBranchId || shift.branch_id === stationBranchId)
  ));
  if (matching.length > 1) return { kind: 'ambiguous_open_shifts', shifts: matching };
  const stored = storedShiftId
    ? matching.find((shift) => shift.id === storedShiftId)
    : undefined;
  if (stored) return { kind: 'ready', shift: stored, source: 'stored' };
  if (matching.length === 1) return { kind: 'ready', shift: matching[0], source: 'single' };
  return { kind: 'no_open_shift' };
}

export function terminalResolutionMessage(resolution: TerminalResolution): string {
  switch (resolution.kind) {
    case 'missing_branch':
      return 'This account has no branch assigned. Assign a branch before using POS or Gaming.';
    case 'no_terminals':
      return 'No active register is configured for this shop. Ask a protected owner to configure one Combined register before using Gaming, POS, or Shift.';
    case 'hybrid_required':
      return 'The active register is not in Combined mode. Ask a protected owner to change it to Combined so Gaming, POS, and Shift use the same workspace.';
    case 'configuration_conflict':
      return 'More than one active register is configured. This shop uses one shared Combined register; ask a protected owner to deactivate the extra register before continuing.';
    case 'selection_required':
      return 'Multiple POS terminals exist for this branch. Select the terminal used by this device.';
    case 'ready':
      return '';
  }
}

export function shiftResolutionMessage(resolution: ShiftResolution): string {
  switch (resolution.kind) {
    case 'missing_branch':
      return 'This account has no branch assigned. Assign a branch before opening a shift.';
    case 'missing_terminal':
      return 'This device has no POS terminal selected. Select a terminal before opening a shift.';
    case 'station_branch_mismatch':
      return 'This gaming station belongs to a different branch than the selected terminal.';
    case 'no_open_shift':
      return 'No shift is open. Open a shift from the Shift tab before taking orders or starting sessions — whoever opens it is responsible for its cash and payment closing.';
    case 'ambiguous_open_shifts':
      return 'More than one open shift exists for this terminal. Close the duplicate shift before continuing.';
    case 'ready':
      return '';
  }
}

/**
 * Resolves the exact-scope open shift for this branch+terminal, or throws a
 * user-actionable error. Never opens a shift itself: opening is a deliberate,
 * manual action (Shifts tab, with a declared cash float) taken by the staff
 * member who is then liable for that shift's cash and payment closing —
 * taking an order or starting a session must not silently create one.
 */
export async function resolveRequiredOpenShift({
  scope,
  stationBranchId,
  listOpenShifts,
}: {
  scope: OperationalScope;
  stationBranchId?: string | null;
  listOpenShifts: () => Promise<ShiftDTO[]>;
}): Promise<string> {
  const candidate = readStoredShiftId(scope);
  const resolution = resolveOpenShift({
    storedShiftId: candidate,
    branchId: scope.branchId,
    terminalId: scope.terminalId,
    stationBranchId,
    openShifts: await listOpenShifts(),
  });
  if (resolution.kind !== 'ready') {
    clearStoredShift();
    throw new Error(shiftResolutionMessage(resolution));
  }
  storeShiftId(scope, resolution.shift.id);
  return resolution.shift.id;
}
