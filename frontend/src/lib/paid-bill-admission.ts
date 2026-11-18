import { paidAccessIdentity } from './paid-bill-contract';
import { readAccessToken, readSessionGeneration } from './api';
import { currentPaidEndpoint } from './paid-bill-api';
import { ownerWorkspaceGeneration } from './owner-workspace';
import { readStoredTerminalId } from './operational-context';
import { invalidatePaidBillReviews, withPaidBillAdmission } from './paid-bill-journal';

const sessionIdentity = () => paidAccessIdentity(readAccessToken());
export function capturePaidWorkspaceFence() {
  const generation = readSessionGeneration(), cache = ownerWorkspaceGeneration(), terminal = readStoredTerminalId(), endpoint = currentPaidEndpoint(), identity = sessionIdentity();
  const current = () => generation === readSessionGeneration() && cache === ownerWorkspaceGeneration() && terminal === readStoredTerminalId() && endpoint === currentPaidEndpoint() && identity === sessionIdentity();
  return { current, check() { if (!current()) throw new Error('The signed-in workspace changed before this operation. Saved paid-bill evidence was retained.'); } };
}
/** Hold admission through the asynchronous exit, invalidating prepared quotes before any HTTP. */
export function withoutPaidBillWork<T>(run: () => Promise<T>): Promise<T> {
  const fence = capturePaidWorkspaceFence();
  return withPaidBillAdmission(async () => {
    fence.check(); invalidatePaidBillReviews();
    const result = await run(); fence.check(); return result;
  });
}
