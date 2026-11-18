import { configuredApiBase } from './configured-api-base';
import { effectivePaidEndpoint, validPaidAction, exactKeys, isRecord, validPaidOutcome, type PaidBillAction, type PaidBillOutcome } from './paid-bill-contract';

const PREFIX = '/pos/paid-bill-amendments/recovery';
export const HISTORICAL_PAID_ROUTE = '/owner/paid-bill-recovery';
export const historicalEndpoint = () => effectivePaidEndpoint(configuredApiBase(), window.location.href);
const unavailable = () => new Error('Recovery could not be confirmed. Keep browser data and the original saved request.');
export function validHistoricalUnlock(v: unknown): v is { protocol: 1; purpose: 'paid_bill_action_recovery'; token: string; expires_in_seconds: 300 } {
  return isRecord(v) && exactKeys(v, ['protocol', 'purpose', 'token', 'expires_in_seconds']) && v.protocol === 1 && v.purpose === 'paid_bill_action_recovery' && v.expires_in_seconds === 300 && typeof v.token === 'string' && v.token.length >= 16 && v.token.length <= 8192;
}
export function validHistoricalLock(v: unknown): v is { protocol: 1; locked: true } {
  return isRecord(v) && exactKeys(v, ['protocol', 'locked']) && v.protocol === 1 && v.locked === true;
}
async function request(endpoint: string, path: '/unlock' | '/actions/outcome' | '/actions/cancel' | '/lock', body: unknown, key?: string, token?: string, signal?: AbortSignal) {
  if (path !== '/lock' && historicalEndpoint() !== endpoint) throw unavailable();
  const controller = new AbortController();
  const abort = () => controller.abort(); signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) controller.abort();
  const timer = setTimeout(abort, 20000);
  try {
    const response = await fetch(endpoint + PREFIX + path, {
      method: path === '/actions/outcome' ? 'GET' : 'POST', credentials: 'omit', redirect: 'error', cache: 'no-store', signal: controller.signal,
      headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(key ? { 'Idempotency-Key': key } : {}), ...(token ? { 'X-Paid-Bill-Recovery': token } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) throw unavailable();
    // A bounded stream avoids buffering an arbitrary server/error response.
    const reader = response.body?.getReader(); if (!reader) throw unavailable();
    let size = 0; const chunks: Uint8Array[] = [];
    try {
      for (;;) { const { value, done } = await reader.read(); if (done) break; size += value.byteLength; if (size > 1048576) throw unavailable(); chunks.push(value); }
    } finally { await reader.cancel().catch(() => {}); }
    const bytes = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) as unknown;
  } catch { throw unavailable(); }
  finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
}
export const historicalPaidApi = {
  unlock: async (action: PaidBillAction, password: string, signal?: AbortSignal) => {
    if (!validPaidAction(action)) throw unavailable();
    const value = await request(action.scope.endpoint, '/unlock', { company_id: action.scope.companyId, actor_user_id: action.scope.userId, branch_id: action.scope.branchId, terminal_id: action.scope.terminalId, order_id: action.body.order_id, request_path: '/api/v1/pos/paid-bill-amendments/commit', original_command: action.body, password }, action.key, undefined, signal);
    if (!validHistoricalUnlock(value)) throw unavailable(); return value;
  },
  outcome: async (action: PaidBillAction, token: string, signal?: AbortSignal): Promise<PaidBillOutcome> => {
    if (!validPaidAction(action)) throw unavailable();
    const value = await request(action.scope.endpoint, '/actions/outcome', undefined, action.key, token, signal);
    if (!validPaidOutcome(value)) throw unavailable(); return value;
  },
  cancel: async (action: PaidBillAction, reason: string, token: string, signal?: AbortSignal): Promise<PaidBillOutcome> => {
    if (!validPaidAction(action)) throw unavailable();
    const value = await request(action.scope.endpoint, '/actions/cancel', { original_command: action.body, cancellation_reason: reason }, action.key, token, signal);
    if (!validPaidOutcome(value)) throw unavailable(); return value;
  },
  lock: async (action: PaidBillAction, token: string, signal?: AbortSignal) => {
    if (!validPaidAction(action)) throw unavailable();
    const value = await request(action.scope.endpoint, '/lock', undefined, undefined, token, signal);
    if (!validHistoricalLock(value)) throw unavailable(); return value;
  },
};
