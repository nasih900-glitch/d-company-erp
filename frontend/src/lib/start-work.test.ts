// @vitest-environment happy-dom
import { webcrypto } from 'node:crypto';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import type { Me } from '@/modules/auth/AuthContext';
const auth = vi.hoisted(() => ({ token: 'original-bearer', generation: 1, terminal: '44444444-4444-4444-8444-444444444444', endpoint: '/api/v1' }));
vi.mock('./api', () => ({ api: { defaults: { get baseURL() { return auth.endpoint; } } }, readAccessToken: () => auth.token, readSessionGeneration: () => auth.generation }));
vi.mock('./operational-context', () => ({ readStoredTerminalId: () => auth.terminal }));
import { readPendingWork, recoverStartWork, runStartWork, type WorkOutcome, type WorkReceipt } from './start-work';
const actor = '11111111-1111-4111-8111-111111111111', company = '22222222-2222-4222-8222-222222222222', branch = '33333333-3333-4333-8333-333333333333', terminal = '44444444-4444-4444-8444-444444444444';
function bearer(nonce:string) { return `${btoa('{}')}.${btoa(JSON.stringify({type:'access',sub:actor,company_id:company,branch_id:branch,nonce}))}.test`; }
const identity = { user_id: actor, company_id: company, branch_id: branch, name: 'Cashier' } as Me;
const receipt: WorkReceipt = {
receipt_id: actor, company_id: company, actor_user_id: actor, branch_id: branch, terminal_id: terminal, installation_id: null, shift_id: terminal, shift_status_at_start: 'open', opening_float_minor: 0,
  attendance_id: branch, clock_in_at: '2026-11-12T00:00:00Z', attendance_created: true, original_attendance_preserved: false, started_at: '2026-11-12T00:00:00Z'
};
const shift = { id: terminal, company_id: company, branch_id: branch, terminal_id: terminal, status: 'open', opening_client_platform: 'web', opening_client_installation_id: null };
const response = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => {
localStorage.clear(); auth.token = bearer('original'); auth.generation = 1; auth.terminal = terminal; auth.endpoint = '/api/v1';
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: webcrypto });
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: async (_key: string, _options: unknown, fn: (lock: object) => Promise<unknown>) => fn({}) } });
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); });
function server(lost = false) {
const fetch = vi.fn(async (url: string, config?: RequestInit) => {
    if (url.includes('/settings/terminals')) return response([{ id: terminal, branch_id: branch, purpose: 'hybrid', online_start_work_supported: true }]);
    if (config?.method === 'POST') { expect(readPendingWork()?.phase).toBe('UNKNOWN'); if (lost) throw new Error('lost reply'); return response(receipt); }
    expect(readPendingWork()?.phase).toBe('CONFIRMED_AWAITING_HYDRATION'); return response([shift]);
  }); vi.stubGlobal('fetch', fetch); return fetch;
}
it('durably saves before the one POST and before exact-shift hydration, then archives positive result', async () => {
  const fetch = server(); await runStartWork(identity, terminal, 0); expect(readPendingWork()).toBeNull();
  expect(fetch.mock.calls.filter(([, c]) => c?.method === 'POST')).toHaveLength(1);
  expect(fetch.mock.calls[1][1]?.headers).not.toHaveProperty('X-Client-Platform');
  expect(Object.keys(localStorage).some(k => k.startsWith('dcompany_start_work_resolved_v1:'))).toBe(true);
});
it('lost reply preserves the original identity and another start cannot allocate a replacement', async () => {
  const fetch = server(true); await expect(runStartWork(identity, terminal, 0)).rejects.toThrow('lost reply'); const saved = readPendingWork(); expect(saved).not.toBeNull();
  await expect(runStartWork(identity, terminal, 0)).rejects.toThrow(); expect(readPendingWork()).toEqual(saved);
  expect(fetch.mock.calls.filter(([, c]) => c?.method === 'POST')).toHaveLength(1);
});
it('a new login only reads status and unconfirmed or empty exact reads cannot retire evidence', async () => {
  server(true); await expect(runStartWork(identity, terminal, 0)).rejects.toThrow(); const saved = readPendingWork(); auth.token = bearer('new-login');
  const fetch = vi.fn(async () => response({ state: 'unconfirmed', receipt: null, cancellation: null, current_shift: null })); vi.stubGlobal('fetch', fetch);
  await expect(recoverStartWork(identity, terminal, readPendingWork()!)).rejects.toThrow('not confirmed'); expect(readPendingWork()).toEqual(saved);
  expect(fetch.mock.calls).toHaveLength(1);
  expect((fetch.mock.calls[0] as unknown as [string])[0]).toContain(`/pos/shifts/start-work/${saved!.reference.action_id}`);
});
it('Owner cancellation creates positive exact proof and unknown cancel reply never re-sends the old start', async () => {
  server(true); await expect(runStartWork(identity, terminal, 0)).rejects.toThrow(); const saved = readPendingWork()!;
  const fetch = vi.fn(async () => { throw new Error('cancel reply lost'); }); vi.stubGlobal('fetch', fetch);
  await expect(recoverStartWork(identity, terminal, readPendingWork()!, { email: 'owner@test.local', password: 'memory-only', cancel: true, reason: 'Never dispatched, retire this request' })).rejects.toThrow('cancel reply lost');
  expect(JSON.stringify(localStorage)).not.toContain('memory-only'); expect(readPendingWork()?.cancelReason).toContain('Never dispatched');
  const cancellation = { ...saved.reference, id: actor, action_key: `shift-open:${saved.reference.action_id}`, cancelled_by: actor, cancelled_at: '2026-11-12T01:00:00Z', reason: 'Never dispatched, retire this request' };
  const outcome: WorkOutcome = { state: 'cancelled', receipt: null, current_shift: null, cancellation };
  const lookup = vi.fn(async () => response(outcome)); vi.stubGlobal('fetch', lookup); await recoverStartWork(identity, terminal, readPendingWork()!); expect(readPendingWork()).toBeNull();
  expect((lookup.mock.calls[0] as unknown as [string])[0]).toContain('/pos/shifts/start-work/');
});
it('late responses after a workspace generation change retain the saved action without publishing success', async () => {
  const fetch = server(); fetch.mockImplementationOnce(async () => response([{ id: terminal, branch_id: branch, purpose: 'hybrid', online_start_work_supported: true }]))
    .mockImplementationOnce(async () => { auth.generation++; return response(receipt); });
  await expect(runStartWork(identity, terminal, 0)).rejects.toThrow(); expect(readPendingWork()?.phase).toBe('UNKNOWN');
  expect(Object.keys(localStorage).some(k => k.startsWith('dcompany_start_work_resolved_v1:'))).toBe(false);
});

it('stale Owner review cannot act on a replacement saved request', async () => {
  server(true); await expect(runStartWork(identity,terminal,0)).rejects.toThrow();
  const original=readPendingWork()!;
  const next={...original,reference:{...original.reference,action_id:crypto.randomUUID()}};
  const {workHash}=await import('./start-work'); next.reference.request_hash=await workHash(next.reference);
  localStorage.setItem('dcompany_start_work_v1',JSON.stringify(next));
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);
  await expect(recoverStartWork(identity,terminal,original,{email:'owner@test.local',password:'memory-only',cancel:true,reason:'Retire the original action only'})).rejects.toThrow('saved start changed');
  expect(fetch).not.toHaveBeenCalled();expect(readPendingWork()).toEqual(next);
});

it('a deferred browser lock cannot rebind an old click to a new session', async () => {
  let release!:()=>void; const gate=new Promise<void>(resolve=>{release=resolve;});
  Object.defineProperty(navigator,'locks',{configurable:true,value:{request:async(_key:string,_options:unknown,callback:(lock:object)=>Promise<unknown>)=>{await gate;return callback({});}}});
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);
  const running=runStartWork(identity,terminal,0);auth.token=bearer('replacement');auth.generation++;release();
  await expect(running).rejects.toThrow('workspace changed');expect(fetch).not.toHaveBeenCalled();expect(readPendingWork()).toBeNull();
});
it('authority lost during async manifest hashing cannot send Owner credentials', async () => {
  server(true);await expect(runStartWork(identity,terminal,0)).rejects.toThrow();const saved=readPendingWork()!;
  let release!:()=>void,entered!:()=>void;
  const gate=new Promise<void>(resolve=>{release=resolve;}), started=new Promise<void>(resolve=>{entered=resolve;});
  const digest=crypto.subtle.digest.bind(crypto.subtle);
  vi.spyOn(crypto.subtle,'digest').mockImplementationOnce(async (...args)=>{entered();await gate;return digest(...args);});
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);
  const running=recoverStartWork(identity,terminal,saved,{email:'owner@test.local',password:'memory-only',cancel:true,reason:'Retire the original action only'});
  await started;auth.generation++;release();await expect(running).rejects.toThrow('workspace changed');
  expect(fetch).not.toHaveBeenCalled();expect(readPendingWork()).toEqual(saved);
});

const family = '55555555-5555-4555-8555-555555555555';
function sessionBearer(nonce: string, overrides: Record<string, unknown> = {}) {
  return `${btoa('{}')}.${btoa(JSON.stringify({ type: 'access', sub: actor, company_id: company, branch_id: branch,
    session_family_id: family, auth_version: 3, roles: ['cashier', 'manager'], protected_access: false, audit_access: false,
    iat: nonce === 'original' ? 100 : 200, exp: nonce === 'original' ? 1000 : 1100, nonce, ...overrides }))}.signature-${nonce}`;
}
function expectArchived() {
  expect(readPendingWork()).toBeNull();
  expect(Object.keys(localStorage).filter(k => k.startsWith('dcompany_start_work_resolved_v1:'))).toHaveLength(1);
}
it.each(['acceptance', 'hydration', 'settle hash'] as const)('same-family renewal during %s completes with only the original wire bearer', async stage => {
  const original = sessionBearer('original'); auth.token = original;
  const fetch = server(), normal = fetch.getMockImplementation()!;
  fetch.mockImplementation(async (url, config) => {
    const result = await normal(url, config);
    if (stage === 'acceptance' && config?.method === 'POST' || stage === 'hydration' && url.includes('/pos/shifts?')) {
      auth.token = sessionBearer('renewed', { roles: ['manager', 'cashier'] });
    }
    return result;
  });
  if (stage === 'settle hash') {
    const digest = crypto.subtle.digest.bind(crypto.subtle);
    vi.spyOn(crypto.subtle, 'digest').mockImplementation(async (...args) => {
      const result = await digest(...args);
      if (readPendingWork()?.phase === 'CONFIRMED_AWAITING_HYDRATION') auth.token = sessionBearer('renewed');
      return result;
    });
  }
  await runStartWork(identity, terminal, 0); expectArchived();
  expect(fetch.mock.calls.filter(([, c]) => c?.method === 'POST')).toHaveLength(1);
  for (const [, config] of fetch.mock.calls) expect(config?.headers).toHaveProperty('Authorization', `Bearer ${original}`);
  const archive = JSON.parse(localStorage.getItem(Object.keys(localStorage).find(k => k.startsWith('dcompany_start_work_resolved_v1:'))!)!);
  const { sha } = await import('./start-work'); expect(archive.pending.authBinding).toBe(await sha(original));
  expect(JSON.stringify(localStorage)).not.toContain(original);
});
it.each([
  ['family', { session_family_id: actor }], ['actor', { sub: terminal }], ['company', { company_id: terminal }],
  ['branch', { branch_id: terminal }], ['version', { auth_version: 4 }], ['roles', { roles: ['owner'] }],
  ['protected', { protected_access: true }], ['audit', { audit_access: true }], ['type', { type: 'refresh' }],
  ['missing family', { session_family_id: undefined }], ['null family', { session_family_id: null }],
  ['array family', { session_family_id: [family] }], ['string version', { auth_version: '3' }],
  ['negative version', { auth_version: -1 }], ['array role', { roles: [['cashier'], 'manager'] }],
  ['string flag', { protected_access: 'false' }], ['missing flag', { audit_access: undefined }],
] as const)('changed %s rejects a late accepted response and retains exact unknown work', async (_label, changes) => {
  auth.token = sessionBearer('original'); const fetch = server(), normal = fetch.getMockImplementation()!;
  let saved: ReturnType<typeof readPendingWork>;
  fetch.mockImplementation(async (url, config) => {
    const result = await normal(url, config);
    if (config?.method === 'POST') { saved = readPendingWork(); auth.token = sessionBearer('renewed', changes); }
    return result;
  });
  await expect(runStartWork(identity, terminal, 0)).rejects.toThrow(); expect(readPendingWork()).toEqual(saved!);
  expect(fetch.mock.calls).toHaveLength(2);
  expect(Object.keys(localStorage).some(k => k.startsWith('dcompany_start_work_resolved_v1:'))).toBe(false);
});
it.each(['generation', 'terminal', 'API origin', 'API basepath', 'malformed bearer', 'legacy renewal'] as const)('%s change cannot publish a late acceptance', async change => {
  auth.token = change === 'legacy renewal' ? bearer('original') : sessionBearer('original');
  const fetch = server(), normal = fetch.getMockImplementation()!;
  fetch.mockImplementation(async (url, config) => {
    const result = await normal(url, config);
    if (config?.method === 'POST') {
      if (change === 'generation') auth.generation++;
      else if (change === 'terminal') auth.terminal = actor;
      else if (change === 'API origin') auth.endpoint = 'https://different.example/api/v1';
      else if (change === 'API basepath') auth.endpoint = '/gateway/api/v1';
      else auth.token = change === 'legacy renewal' ? bearer('renewed') : 'invalid';
    }
    return result;
  });
  await expect(runStartWork(identity, terminal, 0)).rejects.toThrow(); expect(readPendingWork()?.phase).toBe('UNKNOWN');
  expect(fetch.mock.calls).toHaveLength(2);
});
it('a confirmed receipt whose original GET expires is retained until explicit fresh-token hydration', async () => {
  const original = sessionBearer('original'); auth.token = original;
  const fetch = server(), normal = fetch.getMockImplementation()!;
  fetch.mockImplementation(async (url, config) => {
    if (url.includes('/pos/shifts?')) { auth.token = sessionBearer('renewed'); return new Response('', { status: 401 }); }
    return normal(url, config);
  });
  await expect(runStartWork(identity, terminal, 0)).rejects.toThrow('HTTP 401');
  const saved = readPendingWork()!; expect(saved.phase).toBe('CONFIRMED_AWAITING_HYDRATION');
  expect(fetch.mock.calls[2][1]?.headers).toHaveProperty('Authorization', `Bearer ${original}`);
  const recover = vi.fn<(url: string, config?: RequestInit) => Promise<Response>>(async () => response([shift])); vi.stubGlobal('fetch', recover);
  await recoverStartWork(identity, terminal, saved); expectArchived(); expect(recover.mock.calls).toHaveLength(1);
  expect(recover.mock.calls[0][1]?.method).toBe('GET');
  expect(recover.mock.calls[0][1]?.headers).toHaveProperty('Authorization', `Bearer ${auth.token}`);
});
it('original POST expiry never refreshes or retries; same-family renewal only reads status for UNKNOWN', async () => {
  auth.token = sessionBearer('original');
  const fetch = server(), normal = fetch.getMockImplementation()!;
  fetch.mockImplementation(async (url, config) => config?.method === 'POST' ? new Response('', { status: 401 }) : normal(url, config));
  await expect(runStartWork(identity, terminal, 0)).rejects.toThrow('HTTP 401'); const saved = readPendingWork()!;
  auth.token = sessionBearer('renewed');
  const lookup = vi.fn<(url: string, config?: RequestInit) => Promise<Response>>(async () => response({ state: 'unconfirmed', receipt: null, cancellation: null, current_shift: null })); vi.stubGlobal('fetch', lookup);
  await expect(recoverStartWork(identity, terminal, saved)).rejects.toThrow('not confirmed');
  expect(readPendingWork()).toEqual(saved); expect(lookup.mock.calls).toHaveLength(1); expect(lookup.mock.calls[0][1]?.method).toBe('GET');
  expect(fetch.mock.calls.filter(([, c]) => c?.method === 'POST')).toHaveLength(1);
});
it.each([undefined, null, 'not-a-family'])('an original token with family %s stays exact-bearer-only', async originalFamily => {
  auth.token = sessionBearer('original', { session_family_id: originalFamily });
  const fetch = server(), normal = fetch.getMockImplementation()!;
  fetch.mockImplementation(async (url, config) => {
    const result = await normal(url, config);
    if (config?.method === 'POST') auth.token = sessionBearer('renewed');
    return result;
  });
  await expect(runStartWork(identity, terminal, 0)).rejects.toThrow();
  expect(readPendingWork()?.phase).toBe('UNKNOWN'); expect(fetch.mock.calls).toHaveLength(2);
});
