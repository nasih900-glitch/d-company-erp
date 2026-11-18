import type { TerminalDTO } from './erp-api';
import type { PaidBillScope } from './paid-bill-contract';
import { samePaidScope } from './paid-bill-contract';
import { readPaidBillEnvelope } from './paid-bill-journal';

export function paidLoginMatches(endpoint: string, user: string, company: string): boolean {
  const pending = readPaidBillEnvelope().pending;
  return !pending || pending.scope.endpoint === endpoint && pending.scope.userId === user && pending.scope.companyId === company;
}
/** Only fresh authorized terminal rows may restore a saved original context. */
export function originalPaidTerminal(endpoint: string, user: string, company: string, branch: string | null, rows: TerminalDTO[]): TerminalDTO | null {
  const pending = readPaidBillEnvelope().pending;
  if (!pending) return null;
  if (!paidLoginMatches(endpoint, user, company)) throw new Error('Sign in as the original Owner on the original server to recover the saved bill change.');
  const found = rows.filter(t => t.id === pending.scope.terminalId && t.branch_id === pending.scope.branchId && t.is_active === true && (!branch || t.branch_id === branch));
  if (found.length !== 1) throw new Error('The original paid-bill terminal could not be verified. Keep the saved request and restore its original workspace.');
  return found[0];
}
export function isPaidContextRestore(scope: PaidBillScope): boolean {
  const pending = readPaidBillEnvelope().pending;
  return !!pending && samePaidScope(pending.scope, scope);
}
