import type { TerminalDTO } from '@/lib/erp-api';

export type GamingPosRoute = 'local' | 'handoff' | 'terminal_unverified';

/**
 * Terminal purpose, not terminal count or a name convention, decides where a
 * gaming bill belongs. This prevents Cafe POS from accidentally handing a
 * bill "backwards" to Gaming Area when both terminals are configured.
 */
export function resolveGamingPosRoute({
  currentTerminalId,
  stationBranchId,
  terminals,
}: {
  currentTerminalId: string | null;
  stationBranchId: string;
  terminals: readonly Pick<TerminalDTO, 'id' | 'branch_id' | 'purpose'>[];
}): GamingPosRoute {
  if (!currentTerminalId) return 'terminal_unverified';
  const current = terminals.find(
    (terminal) => terminal.id === currentTerminalId && terminal.branch_id === stationBranchId,
  );
  if (!current) return 'terminal_unverified';
  return current.purpose === 'gaming' ? 'handoff' : 'local';
}
