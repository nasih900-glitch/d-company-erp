import {
  exactKeys, isRecord, matchesPaidAcceptance, samePaidScope, validPaidAcceptance, validPaidAction, validPaidScope, validPaidCancellation, validCancellationReason, matchesPaidOutcome,
  type PaidBillAcceptance, type PaidBillAction, type PaidBillScope, type PaidBillCancellation, type PaidBillOutcome,
} from './paid-bill-contract';

export const PAID_BILL_STORAGE_KEY = 'dcompany:paid-bill-amendment:v1';
export const PAID_BILL_ADMISSION_LOCK = 'dcompany:paid-bill-amendment:admission:v1';
const EVENT = 'dcompany-paid-bill-work';
export interface PaidBillStorage { getItem(key: string): string | null; setItem(key: string, value: string): void }
export interface PaidBillEnvelope {
  version: 1; purpose: 'paid_bill_amendment'; generation: number;
  scope: PaidBillScope | null; pending: PaidBillAction | null; accepted: PaidBillAcceptance | null;
  cancellation_reason: string | null; cancelled: PaidBillCancellation | null;
}
export function paidBillStorage(): PaidBillStorage | null {
  try { return typeof localStorage === 'undefined' ? null : localStorage; } catch { return null; }
}
export function paidCanonicalJson(value: unknown): string {
  return JSON.stringify(value, (_key, v: unknown) => isRecord(v) ? Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]])) : v);
}
function same(a: unknown, b: unknown) { return paidCanonicalJson(a) === paidCanonicalJson(b); }
function notify() { if (typeof window !== 'undefined') window.dispatchEvent(new Event(EVENT)); }
export function readPaidBillEnvelope(storage: PaidBillStorage | null = paidBillStorage()): PaidBillEnvelope {
  if (!storage) throw new Error('Paid-bill recovery storage is unavailable. Keep browser data; nothing new can be submitted.');
  const raw = storage.getItem(PAID_BILL_STORAGE_KEY);
  if (raw === null) return { version: 1, purpose: 'paid_bill_amendment', generation: 0, scope: null, pending: null, accepted: null, cancellation_reason: null, cancelled: null };
  let v: unknown;
  try { if (raw.length > 32768) throw new Error(); v = JSON.parse(raw); if (paidCanonicalJson(v) !== raw) throw new Error(); } catch { throw new Error('Saved paid-bill evidence is unreadable. Evidence was kept; Owner recovery is required.'); }
  if (!isRecord(v) || !exactKeys(v, ['version', 'purpose', 'generation', 'scope', 'pending', 'accepted', 'cancellation_reason', 'cancelled']) || v.version !== 1 || v.purpose !== 'paid_bill_amendment' || typeof v.generation !== 'number' || !Number.isSafeInteger(v.generation) || v.generation < 0 ||
    (v.scope !== null && !validPaidScope(v.scope)) ||
    (v.pending !== null && (!validPaidAction(v.pending) || !validPaidScope(v.scope) || !samePaidScope(v.scope, v.pending.scope))) ||
    (v.accepted !== null && (!validPaidAcceptance(v.accepted) || v.scope === null || v.pending !== null && !matchesPaidAcceptance(v.pending as PaidBillAction, v.accepted))) ||
    (v.cancellation_reason !== null && !validCancellationReason(v.cancellation_reason)) ||
    (v.cancelled !== null && (!validPaidCancellation(v.cancelled) || v.cancelled.cancellation_reason !== v.cancellation_reason)) ||
    (v.accepted !== null && v.cancelled !== null) ||
    (v.pending === null && (v.accepted !== null || v.cancelled !== null || v.cancellation_reason !== null)) ||
    (v.scope === null && v.pending !== null)) throw new Error('Saved paid-bill identity needs review. Evidence was kept; no replacement can be submitted.');
  return v as unknown as PaidBillEnvelope;
}
let admissionBusy = 0;
export function paidBillWorkReadiness(storage: PaidBillStorage | null = paidBillStorage()): { verified: boolean; pending: boolean; busy: boolean } {
  try { return { verified: true, pending: readPaidBillEnvelope(storage).pending !== null, busy: admissionBusy > 0 }; }
  catch { return { verified: false, pending: false, busy: admissionBusy > 0 }; }
}
export function hasPaidBillPrivateWork() { const state = paidBillWorkReadiness(); return !state.verified || state.pending || state.busy; }
export function requirePaidBillWorkClear(storage: PaidBillStorage | null = paidBillStorage()) {
  if (readPaidBillEnvelope(storage).pending) throw new Error('Resolve the saved Owner paid-bill action before changing workspace or closing this till. Keep browser data.');
}
export function subscribePaidBillWork(listener: () => void) {
  if (typeof window === 'undefined') return () => {};
  const changed = (event: StorageEvent) => { if (event.key === null || event.key === PAID_BILL_STORAGE_KEY) listener(); };
  window.addEventListener(EVENT, listener); window.addEventListener('storage', changed);
  return () => { window.removeEventListener(EVENT, listener); window.removeEventListener('storage', changed); };
}
/** Shared with every asynchronous workspace exit. There is no automatic replay. */
export async function withPaidBillAdmission<T>(run: () => Promise<T> | T, mutation = false): Promise<T> {
  const execute = async () => { admissionBusy++; notify(); try { return await run(); } finally { admissionBusy--; notify(); } };
  if (typeof navigator === 'undefined' || !navigator.locks?.request) {
    if (mutation) throw new Error('This browser cannot coordinate paid-bill recovery across tabs. Nothing was submitted.');
    // Existing non-paid operations remain available only with verified clear evidence.
    requirePaidBillWorkClear();
    return execute();
  }
  return navigator.locks.request(PAID_BILL_ADMISSION_LOCK, { mode: 'exclusive' }, execute);
}
/** A prepared review always leaves a verified checkpoint. Ordinary unused browsers need no write. */
export function invalidatePaidBillReviews(storage: PaidBillStorage | null = paidBillStorage()) {
  requirePaidBillWorkClear(storage);
  if (!storage || storage.getItem(PAID_BILL_STORAGE_KEY) === null) return;
  const state = readPaidBillEnvelope(storage);
  if (!Number.isSafeInteger(state.generation + 1)) throw new Error('Paid-bill review generation needs Owner recovery.');
  const raw = paidCanonicalJson({ ...state, generation: state.generation + 1 });
  storage.setItem(PAID_BILL_STORAGE_KEY, raw);
  if (storage.getItem(PAID_BILL_STORAGE_KEY) !== raw) throw new Error('Paid-bill workspace change could not be safely recorded.');
  notify();
}
export class PaidBillJournal {
  constructor(readonly scope: PaidBillScope, private readonly storage: PaidBillStorage | null = paidBillStorage()) {
    if (!validPaidScope(scope)) throw new Error('Paid-bill workspace identity is invalid.');
  }
  read(): PaidBillEnvelope {
    const state = readPaidBillEnvelope(this.storage);
    if (state.pending && (!state.scope || !samePaidScope(state.scope, this.scope))) throw new Error('A paid-bill action belongs to another original server, Owner or terminal. Restore that exact context to recover it; evidence was kept.');
    return state.scope && !samePaidScope(state.scope, this.scope) ? { ...state, scope: this.scope, accepted: null } : state;
  }
  private write(value: PaidBillEnvelope) {
    if (!this.storage) throw new Error('Paid-bill recovery storage is unavailable.');
    const raw = paidCanonicalJson(value);
    if (raw.length > 32768) throw new Error('Paid-bill recovery evidence exceeds the storage limit.');
    this.storage.setItem(PAID_BILL_STORAGE_KEY, raw);
    if (this.storage.getItem(PAID_BILL_STORAGE_KEY) !== raw) throw new Error('Paid-bill recovery storage could not be verified. Keep this page and the exact saved action.');
    notify();
  }
  ensureReady() {
    const state = this.read();
    if (state.pending) throw new Error('Recover the saved paid-bill command first.');
    if (!this.storage) throw new Error('Recovery storage is unavailable.');
    if (this.storage.getItem(PAID_BILL_STORAGE_KEY) === null) this.write(state);
    return this.read();
  }
  reserve(action: PaidBillAction, expectedGeneration: number) {
    if (!validPaidAction(action) || !samePaidScope(action.scope, this.scope)) throw new Error('Paid-bill command identity is invalid. Nothing was submitted.');
    const state = this.read();
    if (state.generation !== expectedGeneration) throw new Error('Another tab acknowledged an action. Review this bill again.');
    if (state.pending && !same(state.pending, action)) throw new Error('Recover the saved paid-bill command before preparing another one.');
    this.write({ ...state, scope: this.scope, pending: action, accepted: state.pending ? state.accepted : null, cancelled: state.pending ? state.cancelled : null, cancellation_reason: state.pending ? state.cancellation_reason : null });
    const saved = this.read();
    if (!same(saved.pending, action)) throw new Error('The saved action could not be verified before transport.');
  }
  saveCancellationReason(action: PaidBillAction, reason: string) {
    const state = this.read();
    if (!same(state.pending, action) || state.accepted || state.cancelled || !validCancellationReason(reason) || state.cancellation_reason !== null && state.cancellation_reason !== reason) throw new Error('Keep the exact saved command and cancellation reason.');
    this.write({ ...state, cancellation_reason: reason });
    if (this.read().cancellation_reason !== reason) throw new Error('Cancellation reason could not be verified before transport.');
  }
  acknowledge(action: PaidBillAction, outcome: PaidBillOutcome) {
    const state = this.read();
    if (!same(state.pending, action) || !matchesPaidOutcome(action, outcome, state.cancellation_reason)) throw new Error('The saved command and server outcome do not match. Evidence was kept.');
    const accepted = outcome.outcome === 'accepted' ? outcome.accepted : null;
    const cancelled = outcome.outcome === 'cancelled' ? outcome.cancelled : null;
    if (state.accepted && !same(state.accepted, accepted) || state.cancelled && !same(state.cancelled, cancelled)) throw new Error('The immutable saved outcome changed. Evidence was kept.');
    this.write({ ...state, accepted, cancelled });
  }
  clearExact(action: PaidBillAction, outcome: PaidBillOutcome) {
    const state = this.read();
    if (!same(state.pending, action) || !matchesPaidOutcome(action, outcome, state.cancellation_reason) || !Number.isSafeInteger(state.generation + 1) ||
      (outcome.outcome === 'accepted' ? !same(state.accepted, outcome.accepted) : !same(state.cancelled, outcome.cancelled))) throw new Error('Server outcome confirmed; local recovery acknowledgement still needs review.');
    this.write({ ...state, generation: state.generation + 1, pending: null, accepted: null, cancelled: null, cancellation_reason: null });
  }
}
