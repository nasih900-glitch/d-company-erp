import type { TerminalDTO } from '@/lib/erp-api';

export type GamingPosRoute = 'local' | 'handoff' | 'terminal_unverified' | 'profile_conflict';

/**
 * The focused product bills locally on its verified Hybrid register. The old
 * cross-register route remains explicit for a future profile, but a split
 * terminal is a configuration conflict unless that profile enables handoff.
 */
export function resolveGamingPosRoute({
  currentTerminalId,
  stationBranchId,
  terminals,
  allowCrossTerminalHandoff,
}: {
  currentTerminalId: string | null;
  stationBranchId: string;
  terminals: readonly Pick<TerminalDTO, 'id' | 'branch_id' | 'purpose'>[];
  allowCrossTerminalHandoff: boolean;
}): GamingPosRoute {
  if (!currentTerminalId) return 'terminal_unverified';
  const current = terminals.find(
    (terminal) => terminal.id === currentTerminalId && terminal.branch_id === stationBranchId,
  );
  if (!current) return 'terminal_unverified';
  if (!allowCrossTerminalHandoff && current.purpose !== 'hybrid') {
    return 'profile_conflict';
  }
  return current.purpose === 'gaming' ? 'handoff' : 'local';
}
