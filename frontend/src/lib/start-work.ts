import { withoutPaidBillWork } from './paid-bill-admission';
import { api, readAccessToken, readSessionGeneration } from './api';
import { readStoredTerminalId } from './operational-context';
import type { Me } from '@/modules/auth/AuthContext';

const KEY = 'dcompany_start_work_v1';
const ARCHIVE = 'dcompany_start_work_resolved_v1';
const PATH = '/api/v1/pos/shifts/start-work';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const anyUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export interface WorkReference {
  action_id: string; company_id: string; actor_user_id: string; branch_id: string;
  terminal_id: string; installation_id: null; client_platform: 'web'; opening_float_minor: number; request_hash: string;
}
export interface WorkReceipt {
  receipt_id: string; company_id: string; actor_user_id: string; branch_id: string; terminal_id: string;
  installation_id: string | null; shift_id: string; shift_status_at_start: string; opening_float_minor: number;
  attendance_id: string; clock_in_at: string; attendance_created: boolean; original_attendance_preserved: boolean; started_at: string;
}
interface CurrentShift {
  id: string; company_id: string; branch_id: string; terminal_id: string; status: string;
  opening_client_platform: string; opening_client_installation_id: string | null
}
export interface WorkOutcome {
  state: 'committed' | 'cancelled' | 'unconfirmed'; receipt: WorkReceipt | null;
  cancellation: (Omit<WorkReference, 'action_id'> & { id: string; action_key: string; cancelled_by: string; cancelled_at: string; reason: string }) | null;
  current_shift: CurrentShift | null
}
export interface PendingWork {
  version: 1; reference: WorkReference; actorName: string; authBinding: string;
  phase: 'UNKNOWN' | 'CONFIRMED_AWAITING_HYDRATION'; receipt: WorkReceipt | null; cancelReason: string | null
}
let busy = false;
let confirmation: string | null = null;
export function workConfirmation() { return confirmation; }
export function workBusy() { return busy; }
function check(value: unknown, message = 'Saved Start work evidence could not be verified. Keep browser data and ask an Owner to review.'): asserts value {
  if (!value) throw new Error(message);
}
export async function sha(value: string) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))), b => b.toString(16).padStart(2, '0')).join('');
}
export async function workHash(r: WorkReference) {
  return sha(`POST\n${PATH}\n\nshift-open:${r.action_id}\n${JSON.stringify({ opening_float_minor: r.opening_float_minor })}`);
}
function validateReference(r: WorkReference) {
  check(r && uuid.test(r.action_id) && [r.company_id, r.actor_user_id, r.branch_id, r.terminal_id].every(x => anyUuid.test(x)) &&
    r.installation_id === null && r.client_platform === 'web' && Number.isSafeInteger(r.opening_float_minor) && r.opening_float_minor >= 0 && /^[0-9a-f]{64}$/.test(r.request_hash));
  check(Object.keys(r).sort().join() === ['action_id', 'company_id', 'actor_user_id', 'branch_id', 'terminal_id', 'installation_id', 'client_platform', 'opening_float_minor', 'request_hash'].sort().join());
}
export function readPendingWork(): PendingWork | null {
  const raw = localStorage.getItem(KEY);
  if (raw === null) return null;
  check(raw.length > 0 && raw.length <= 32768);
  const p = JSON.parse(raw) as PendingWork;
  check(p.version === 1 && ['UNKNOWN', 'CONFIRMED_AWAITING_HYDRATION'].includes(p.phase) &&
    typeof p.actorName === 'string' && p.actorName.length > 0 && p.actorName.length <= 200 && /^[0-9a-f]{64}$/.test(p.authBinding));
  check(Object.keys(p).sort().join() === ['version', 'reference', 'actorName', 'authBinding', 'phase', 'receipt', 'cancelReason'].sort().join());
  validateReference(p.reference);
  if (p.receipt !== null) validateReceipt(p.reference, p.receipt);
  check(p.phase !== 'CONFIRMED_AWAITING_HYDRATION' || p.receipt !== null);
  check(p.cancelReason === null || typeof p.cancelReason === 'string' && p.cancelReason.trim().length >= 12 && p.cancelReason.length <= 500);
  return p;
}
export function hasPendingWork() { try { return readPendingWork() !== null || localStorage.getItem('dcompany_shared_attendance_v1') !== null || busy; } catch { return true; } }
function save(expected: PendingWork | null, next: PendingWork) {
  check(JSON.stringify(readPendingWork()) === JSON.stringify(expected));
  const raw = JSON.stringify(next); check(raw.length <= 32768); localStorage.setItem(KEY, raw);
  check(localStorage.getItem(KEY) === raw);
}
export function validateReceipt(r: WorkReference, v: WorkReceipt) {
  check(v && Object.keys(v).sort().join() === ['receipt_id', 'company_id', 'actor_user_id', 'branch_id', 'terminal_id', 'installation_id', 'shift_id', 'shift_status_at_start', 'opening_float_minor', 'attendance_id', 'clock_in_at', 'attendance_created', 'original_attendance_preserved', 'started_at'].sort().join());
  check(v && [v.receipt_id, v.shift_id, v.attendance_id].every(x => anyUuid.test(x)) &&
    v.company_id === r.company_id && v.actor_user_id === r.actor_user_id && v.branch_id === r.branch_id &&
    v.terminal_id === r.terminal_id && v.installation_id === null && v.opening_float_minor === r.opening_float_minor && v.shift_status_at_start === 'open');
  check(typeof v.attendance_created === 'boolean' && typeof v.original_attendance_preserved === 'boolean' && v.attendance_created !== v.original_attendance_preserved);
  const start = Date.parse(v.started_at), clock = Date.parse(v.clock_in_at);
  check(Number.isFinite(start) && Number.isFinite(clock) && clock <= start && (!v.attendance_created || clock === start));
}
export function validateOutcome(r: WorkReference, o: WorkOutcome) {
  check(o && Object.keys(o).sort().join() === ['state', 'receipt', 'cancellation', 'current_shift'].sort().join());
  if (o.state === 'unconfirmed') { check(!o.receipt && !o.cancellation && !o.current_shift); return; }
  if (o.state === 'committed') {
    validateReceipt(r, o.receipt!); const s = o.current_shift;
    check(!o.cancellation && s && s.id === o.receipt!.shift_id && s.company_id === r.company_id && s.branch_id === r.branch_id && s.terminal_id === r.terminal_id &&
      ['open', 'closed'].includes(s.status) && s.opening_client_platform === 'web' && s.opening_client_installation_id === null);
  } else {
    const c = o.cancellation;
    check(o.state === 'cancelled' && !o.receipt && !o.current_shift && c && c.action_key === `shift-open:${r.action_id}` && anyUuid.test(c.id) && anyUuid.test(c.cancelled_by) && Number.isFinite(Date.parse(c.cancelled_at)));
    for (const k of ['company_id', 'actor_user_id', 'branch_id', 'terminal_id', 'installation_id', 'client_platform', 'opening_float_minor', 'request_hash'] as const) check(c[k] === r[k]);
    check(c.reason.trim().length >= 12 && c.reason.length <= 500);
  }
}
function accessClaims(bearer: string): Record<string, unknown> {
  const payload: unknown = JSON.parse(atob(bearer.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  check(payload && typeof payload === 'object' && !Array.isArray(payload));
  return payload as Record<string, unknown>;
}
function renewalIdentity(p: Record<string, unknown>): string | null {
  if (p.type !== 'access' || ![p.sub, p.company_id, p.session_family_id].every(v => typeof v === 'string' && anyUuid.test(v)) ||
    !(p.branch_id === null || typeof p.branch_id === 'string' && anyUuid.test(p.branch_id)) ||
    !Number.isSafeInteger(p.auth_version) || (p.auth_version as number) < 0 ||
    !Array.isArray(p.roles) || !p.roles.every(v => typeof v === 'string' && v.length > 0) || new Set(p.roles).size !== p.roles.length ||
    typeof p.protected_access !== 'boolean' || typeof p.audit_access !== 'boolean') return null;
  // Callback correlation only: the server still validates the original wire bearer.
  return JSON.stringify([p.sub, p.company_id, p.branch_id, p.session_family_id, p.auth_version,
    [...p.roles].sort(), p.protected_access, p.audit_access]);
}
function effectiveEndpoint(): string {
  const url = new URL(String(api.defaults.baseURL), window.location.href);
  check(!url.search && !url.hash && !url.username && !url.password);
  return `${url.origin}${url.pathname.replace(/\/$/, '')}`;
}
function authority(me: Me, terminal: string) {
  const generation = readSessionGeneration(), bearer = readAccessToken(); check(bearer && terminal === readStoredTerminalId());
  let identity: string | null;
  const endpoint = effectiveEndpoint();
  try {
    const payload = accessClaims(bearer);
    check(payload.type === 'access' && payload.sub === me.user_id && payload.company_id === me.company_id && payload.branch_id === me.branch_id);
    identity = renewalIdentity(payload);
  } catch { throw new Error('The signed-in account changed. Refresh before continuing.'); }
  return { bearer, current: () => {
    try {
      if (readSessionGeneration() !== generation || readStoredTerminalId() !== terminal || effectiveEndpoint() !== endpoint || me.user_id === '') return false;
      const current = readAccessToken();
      // Legacy tokens remain exact-bearer-only; no durable replay binding changes.
      return current === bearer || !!current && identity !== null && renewalIdentity(accessClaims(current)) === identity;
    } catch { return false; }
  } };
}
async function wire(path: string, method: string, body: unknown, bearer: string | null, terminal: string | null, key?: string) {
  const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(`${String(api.defaults.baseURL).replace(/\/$/, '')}${path}`, {
      method, redirect: 'error', credentials: 'omit', signal: controller.signal,
      headers: {
        'Content-Type': 'application/json', ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
        ...(terminal ? { 'X-Terminal-Id': terminal } : {}), ...(key ? { 'Idempotency-Key': key } : {})
      }, body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) throw new Error(`Server returned HTTP ${response.status}. The exact saved request is retained.`);
    return await response.json() as unknown;
  } finally { clearTimeout(timeout); }
}
async function settle(p: PendingWork, outcome: WorkOutcome, current: () => boolean) {
  check(current()); check(await workHash(p.reference) === p.reference.request_hash); check(current()); validateOutcome(p.reference, outcome);
  check(outcome.state !== 'unconfirmed', 'The server has not confirmed an outcome. An Owner can review and retire this exact request if it never committed.');
  if (outcome.receipt) { const next: PendingWork = { ...p, phase: 'CONFIRMED_AWAITING_HYDRATION', receipt: outcome.receipt }; save(p, next); p = next; }
  check(current());
  const archive = JSON.stringify({ pending: p, outcome }); check(archive.length <= 32768);
  const key = `${ARCHIVE}:${p.reference.action_id}`;
  const old = localStorage.getItem(key);
  if (old === null) localStorage.setItem(key, archive);
  else {
    check(old.length <= 32768); const previous = JSON.parse(old) as { pending: PendingWork; outcome: WorkOutcome };
    check(JSON.stringify(previous.pending.reference) === JSON.stringify(p.reference)); validateOutcome(p.reference, previous.outcome);
  }
  check(localStorage.getItem(key) !== null && current());
  check(JSON.stringify(readPendingWork()) === JSON.stringify(p)); localStorage.removeItem(KEY); check(localStorage.getItem(KEY) === null);
  confirmation = outcome.receipt ? `Shift start and clock-in recorded for ${p.actorName} at ${new Intl.DateTimeFormat('en-IN', {timeZone:'Asia/Kolkata',dateStyle:'medium',timeStyle:'short'}).format(new Date(outcome.receipt.clock_in_at))}.`
    : 'An Owner retired the saved request before it opened a shift or recorded a clock-in.';
  window.dispatchEvent(new Event('dcompany-daily-work-changed'));
}
async function exactState(p: PendingWork, a: ReturnType<typeof authority>): Promise<WorkOutcome> {
  check(a.current());
  const value = await wire(`/pos/shifts?shift_id=${p.receipt!.shift_id}&limit=1`, 'GET', undefined, a.bearer, p.reference.terminal_id) as CurrentShift[];
  check(a.current() && Array.isArray(value) && value.length === 1);
  const s = value[0];
  return { state: 'committed', receipt: p.receipt, cancellation: null, current_shift: { id: s.id, branch_id: s.branch_id, terminal_id: s.terminal_id, status: s.status, opening_client_platform: s.opening_client_platform, opening_client_installation_id: s.opening_client_installation_id, company_id: p.reference.company_id } };
}
export async function runStartWork(me: Me, terminal: string, cash: number) {
  return withoutPaidBillWork(() => runStartWorkAdmitted(me, terminal, cash));
}
async function runStartWorkAdmitted(me: Me, terminal: string, cash: number) {
  const a = authority(me, terminal);
  return navigator.locks.request(KEY, { ifAvailable: true }, async lock => {
    check(a.current(), 'The signed-in workspace changed. Review the saved request again.');
    check(lock && !busy, 'Another Start work check is active. Wait for its result.'); busy = true;
    try {
      check(readPendingWork() === null && localStorage.getItem('dcompany_shared_attendance_v1') === null && Number.isSafeInteger(cash) && cash >= 0);
      const terminals = await wire(`/settings/terminals?branch_id=${me.branch_id}`, 'GET', undefined, a.bearer, terminal) as { id: string; branch_id: string; purpose: string; online_start_work_supported?: boolean }[];
      check(a.current() && terminals.some(t => t.id === terminal && t.branch_id === me.branch_id && t.purpose === 'hybrid' && t.online_start_work_supported === true));
      const ref: WorkReference = {
action_id: crypto.randomUUID(), company_id: me.company_id, actor_user_id: me.user_id, branch_id: me.branch_id!, terminal_id: terminal,
        installation_id: null, client_platform: 'web', opening_float_minor: cash, request_hash: ''
}; ref.request_hash = await workHash(ref); validateReference(ref);
      const p: PendingWork = { version: 1, reference: ref, actorName: me.name, authBinding: await sha(a.bearer), phase: 'UNKNOWN', receipt: null, cancelReason: null };
      check(a.current()); save(null, p);
      await dispatch(p, a);
    } finally { busy = false; }
  });
}
async function dispatch(p: PendingWork, a: ReturnType<typeof authority>) {
  check(a.current()); const receipt = await wire('/pos/shifts/start-work', 'POST', { opening_float_minor: p.reference.opening_float_minor }, a.bearer, p.reference.terminal_id, `shift-open:${p.reference.action_id}`) as WorkReceipt;
  check(a.current()); validateReceipt(p.reference, receipt);
  const next: PendingWork = { ...p, phase: 'CONFIRMED_AWAITING_HYDRATION', receipt }; save(p, next);
  await settle(next, await exactState(next, a), a.current);
}
export async function recoverStartWork(me: Me, terminal: string, expected: PendingWork, owner?: { email: string; password: string; cancel: boolean; reason: string }) {
  return withoutPaidBillWork(() => recoverStartWorkAdmitted(me, terminal, expected, owner));
}
async function recoverStartWorkAdmitted(me: Me, terminal: string, expected: PendingWork, owner?: { email: string; password: string; cancel: boolean; reason: string }) {
  const a = authority(me, terminal);
  return navigator.locks.request(KEY, { ifAvailable: true }, async lock => {
    check(a.current(), 'The signed-in workspace changed. Review the saved request again.');
    check(lock && !busy, 'Another Start work check is active. Wait for its result.'); busy = true;
    try {
      let p = readPendingWork(); check(p);
    check(JSON.stringify(p) === JSON.stringify(expected), 'The saved start changed. Review it again before confirming.');
    check(await workHash(p.reference) === p.reference.request_hash);
      check(a.current(), 'The signed-in workspace changed. Review the saved request again.');
      if (owner) {
        if (owner.cancel) { check(owner.reason.trim().length >= 12); const next = { ...p, cancelReason: owner.reason.trim() }; save(p, next); p = next; }
        check(a.current(), 'The signed-in workspace changed.');
        const outcome = await wire('/pos/shifts/start-work/recovery', 'POST', { reference: p.reference, mode: owner.cancel ? 'cancel' : 'status', owner_email: owner.email, owner_password: owner.password, reason: owner.cancel ? p.cancelReason : null }, null, null) as WorkOutcome;
        check(a.current()); validateOutcome(p.reference, outcome);
        if (outcome.receipt && outcome.current_shift?.status === 'open') {
          check(me.company_id === p.reference.company_id && me.branch_id === p.reference.branch_id && terminal === p.reference.terminal_id);
          const next: PendingWork = { ...p, phase: 'CONFIRMED_AWAITING_HYDRATION', receipt: outcome.receipt }; save(p, next); p = next;
          await settle(p, await exactState(p, a), a.current);
        } else await settle(p, outcome, a.current);
      } else if (p.phase === 'CONFIRMED_AWAITING_HYDRATION') await settle(p, await exactState(p, a), a.current);
      else if (!p.cancelReason && p.reference.actor_user_id === me.user_id && p.reference.company_id === me.company_id && p.reference.branch_id === me.branch_id && p.reference.terminal_id === terminal && await sha(a.bearer) === p.authBinding) await dispatch(p, a);
      else {
        check(a.current(), 'The signed-in workspace changed.');
        const outcome = await wire(`/pos/shifts/start-work/${p.reference.action_id}`, 'GET', undefined, a.bearer, p.reference.terminal_id) as WorkOutcome;
        validateOutcome(p.reference, outcome);
        if (outcome.current_shift?.status === 'open' && outcome.receipt) {
          const next: PendingWork = { ...p, phase: 'CONFIRMED_AWAITING_HYDRATION', receipt: outcome.receipt }; save(p, next); p = next;
          await settle(p, await exactState(p, a), a.current);
        } else await settle(p, outcome, a.current);
      }
    } finally { busy = false; }
  });
}
