// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { action, accepted, cancelled } from '@/test-fixtures/paid-bill';
import { historicalPaidApi, validHistoricalUnlock, validHistoricalLock } from './paid-bill-historical-api';
const grant = { protocol: 1, purpose: 'paid_bill_action_recovery', expires_in_seconds: 300, token: 'dedicated-ram-only-token' };
beforeEach(() => { vi.stubEnv('VITE_API_URL', ''); });
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
it('uses only the fixed recovery paths, dedicated headers and no ordinary credential pipeline', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(grant))).mockResolvedValueOnce(new Response(JSON.stringify(accepted()))).mockResolvedValueOnce(new Response(JSON.stringify(cancelled()))).mockResolvedValueOnce(new Response(JSON.stringify({ protocol: 1, locked: true })));
  vi.stubGlobal('fetch', fetch); const a = action();
  await historicalPaidApi.unlock(a, 'PASSWORD_MARKER'); await historicalPaidApi.outcome(a, grant.token); await historicalPaidApi.cancel(a, 'Stop unresolved saved request', grant.token); await historicalPaidApi.lock(a, grant.token);
  expect(fetch.mock.calls.map(([url]) => url)).toEqual(['/unlock', '/actions/outcome', '/actions/cancel', '/lock'].map(path => a.scope.endpoint + '/pos/paid-bill-amendments/recovery' + path));
  for (const [url, config] of fetch.mock.calls) {
    expect(config).toMatchObject({ credentials: 'omit', redirect: 'error', cache: 'no-store' });
    expect(url).not.toMatch(/PASSWORD_MARKER|dedicated-ram-only-token|paid-bill:web:/);
    expect(Object.keys(config.headers)).not.toContain('Authorization'); expect(Object.keys(config.headers)).not.toContain('X-Terminal-Id');
  }
  expect(fetch.mock.calls[0][1].headers).toEqual({ 'Content-Type': 'application/json', 'Idempotency-Key': a.key });
  expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ original_command: a.body, cancellation_reason: 'Stop unresolved saved request' });
  expect(fetch.mock.calls[3][1].headers).toEqual({ 'X-Paid-Bill-Recovery': grant.token });
});
it.each([{ protocol: '1' }, { purpose: ['paid_bill_action_recovery'] }, { expires_in_seconds: 301 }, { token: ['not-a-string'] }, { access_token: 'unexpected' }])('rejects a malformed unlock DTO: %j', change => {
  expect(validHistoricalUnlock({ ...grant, ...change })).toBe(false);
});
it.each([{ locked: 'true' }, { protocol: 2 }, { locked: false }, { extra: true }])('requires exact primitive revocation proof: %j', change => {
  expect(validHistoricalLock({ protocol: 1, locked: true, ...change })).toBe(false);
});
it('blocks a changed configured basepath before sending any password', async () => {
  vi.stubEnv('VITE_API_URL', '/gateway/api/v1'); const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  await expect(historicalPaidApi.unlock(action(), 'secret')).rejects.toThrow(); expect(fetch).not.toHaveBeenCalled();
});
it.each(['http', 'malformed', 'oversize', 'redirect'])('retains a generic error for %s without retrying or echoing response data', async problem => {
  const privateText = 'PRIVATE_RESPONSE_SECRET'; const fetch = vi.fn(async () => {
    if (problem === 'redirect') throw new TypeError(privateText);
    return new Response(problem === 'oversize' ? 'x'.repeat(1048577) : privateText, { status: problem === 'http' ? 409 : 200 });
  }); vi.stubGlobal('fetch', fetch);
  await expect(historicalPaidApi.outcome(action(), grant.token)).rejects.toThrow('Recovery could not be confirmed'); expect(fetch).toHaveBeenCalledTimes(1);
});
