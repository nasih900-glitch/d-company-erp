// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { action, accepted, cancelled, deferred, id, scope, serialLocks } from '@/test-fixtures/paid-bill';
import { hasPaidBillPrivateWork, paidBillWorkReadiness, invalidatePaidBillReviews, PaidBillJournal, paidCanonicalJson, PAID_BILL_STORAGE_KEY, readPaidBillEnvelope, withPaidBillAdmission, type PaidBillStorage } from './paid-bill-journal';

beforeEach(() => { localStorage.clear(); Object.defineProperty(navigator, 'locks', { configurable: true, value: serialLocks() }); });
afterEach(() => localStorage.clear());
const prepare = () => { const j = new PaidBillJournal(scope); j.ensureReady(); j.reserve(action(), 0); return j; };
describe('durable paid-bill evidence', () => {
  it('writes and reads the exact command before a response and only clears a matched acknowledged result', () => {
    const j = prepare(), a = action(), proof = accepted(a);
    expect(j.read().pending).toEqual(a); expect(() => j.clearExact(a, proof)).toThrow();
    j.acknowledge(a, proof); expect(j.read().accepted).toEqual(proof.accepted);
    j.clearExact(a, proof); expect(j.read().pending).toBeNull(); expect(j.read().generation).toBe(1);
  });
  it('persists an immutable cancellation reason before transport and recovers after a reload', () => {
    const j = prepare(), a = action(), proof = cancelled(a);
    expect(() => j.acknowledge(a, proof)).toThrow(); j.saveCancellationReason(a, proof.cancelled.cancellation_reason);
    expect(() => j.saveCancellationReason(a, 'Changed reason')).toThrow();
    const restarted = new PaidBillJournal(scope); restarted.acknowledge(a, proof); restarted.clearExact(a, proof);
    expect(restarted.read().pending).toBeNull();
  });
  it('accepted winner never cancels its business task even when cancellation was requested', () => {
    const j = prepare(), a = action(), proof = accepted(a); j.saveCancellationReason(a, 'Stop unresolved request');
    j.acknowledge(a, proof); j.clearExact(a, proof); expect(j.read().pending).toBeNull(); expect(proof.accepted.refund_request_id).toBe(id(7));
  });
  it.each(['{bad', '{"version":1,"version":1}', 'null', '{"purpose":"different"}'])('retains corrupt or unfamiliar bytes %s', raw => {
    localStorage.setItem(PAID_BILL_STORAGE_KEY, raw); expect(() => readPaidBillEnvelope()).toThrow(); expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).toBe(raw);
  });
  it('never turns unavailable, throwing, quota or wrong readback storage into empty evidence', () => {
    expect(() => readPaidBillEnvelope(null)).toThrow();
    const throws: PaidBillStorage = { getItem() { throw new Error('read unavailable'); }, setItem() {} };
    expect(() => readPaidBillEnvelope(throws)).toThrow('read unavailable');
    const quota: PaidBillStorage = { getItem() { return null; }, setItem() { throw new Error('quota'); } };
    expect(() => new PaidBillJournal(scope, quota).ensureReady()).toThrow('quota');
    let raw: string | null = null;
    const bad: PaidBillStorage = { getItem() { return raw; }, setItem(_k, v) { raw = v + ' '; } };
    expect(() => new PaidBillJournal(scope, bad).ensureReady()).toThrow(); expect(raw).not.toBeNull();
  });
  it('does not retarget foreign endpoint/basepath/Owner/terminal scope', () => {
    prepare(); const raw = localStorage.getItem(PAID_BILL_STORAGE_KEY);
    for (const s of [{ ...scope, endpoint: scope.endpoint + '/other' }, { ...scope, userId: id(40) }, { ...scope, terminalId: id(41) }]) expect(() => new PaidBillJournal(s).read()).toThrow();
    expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).toBe(raw);
  });
  it('fences a competing tab and an old prepared review after acknowledgement', () => {
    const one = prepare(), two = new PaidBillJournal(scope), a = action(), b = { ...action(), key: 'paid-bill:web:' + id(22) };
    expect(() => two.reserve(b, 0)).toThrow(); one.acknowledge(a, accepted(a)); one.clearExact(a, accepted(a)); expect(() => two.reserve(b, 0)).toThrow();
  });
  it('keeps the acknowledged command when cleanup fails, without losing its durable acceptance', () => {
    let raw: string | null = null, failClear = false;
    const store: PaidBillStorage = { getItem() { return raw; }, setItem(_k, v) { if (failClear && JSON.parse(v).pending === null) throw new Error('clear failed'); raw = v; } };
    const j = new PaidBillJournal(scope, store), a = action(), proof = accepted(a); j.ensureReady(); j.reserve(a, 0); j.acknowledge(a, proof); failClear = true;
    expect(() => j.clearExact(a, proof)).toThrow(); expect(j.read().accepted).toEqual(proof.accepted); expect(j.read().pending).toEqual(a);
    failClear = false; j.clearExact(a, proof); expect(j.read().pending).toBeNull();
  });
  it('never interprets an unconfirmed/error/404-shaped response as proof', () => {
    const j = prepare(), raw = localStorage.getItem(PAID_BILL_STORAGE_KEY);
    for (const value of [{ outcome: 'unconfirmed' }, { status: 404 }, { status: 409 }, { error: 'timeout' }]) expect(() => j.acknowledge(action(), value as never)).toThrow();
    expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).toBe(raw);
  });
  it('rejects extra secrets in a canonical envelope rather than silently dropping them', () => {
    prepare(); const v = { ...readPaidBillEnvelope(), password: 'never persist' }; const raw = paidCanonicalJson(v); localStorage.setItem(PAID_BILL_STORAGE_KEY, raw);
    expect(() => readPaidBillEnvelope()).toThrow(); expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).toBe(raw);
  });
});
describe('coordinated protected operation admission', () => {
  it('blocks a stale reservation queued behind an empty-journal transition', async () => {
    const j = new PaidBillJournal(scope); j.ensureReady(); const gate = deferred(), entered = deferred();
    const transition = withPaidBillAdmission(async () => { invalidatePaidBillReviews(); entered.resolve(); await gate.promise; });
    await entered.promise;
    const reservation = withPaidBillAdmission(() => j.reserve(action(), 0), true);
    gate.resolve(); await transition; await expect(reservation).rejects.toThrow('Another tab'); expect(j.read().pending).toBeNull();
  });
  it('blocks an exit queued behind a reservation whose outcome remains unknown', async () => {
    const j = new PaidBillJournal(scope); j.ensureReady(); const gate = deferred(), entered = deferred();
    const reservation = withPaidBillAdmission(async () => { j.reserve(action(), 0); entered.resolve(); await gate.promise; }, true);
    await entered.promise; const transition = withPaidBillAdmission(() => invalidatePaidBillReviews()); gate.resolve(); await reservation;
    await expect(transition).rejects.toThrow('Resolve'); expect(j.read().pending).toEqual(action());
  });
  it('keeps ordinary clear-browser admissions available without locks but refuses new paid writes', async () => {
    Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined });
    expect(await withPaidBillAdmission(() => 'ordinary')).toBe('ordinary'); await expect(withPaidBillAdmission(() => 'paid', true)).rejects.toThrow('coordinate');
    prepare(); await expect(withPaidBillAdmission(() => 'unsafe exit')).rejects.toThrow('Resolve');
  });
});

it('treats a canonical array-operation journal as unknown retained evidence, never verified retry work', () => {
  const invalid = { version: 1, purpose: 'paid_bill_amendment', generation: 0, scope, pending: { ...action(), body: { ...action().body, operation: ['decrease'] } }, accepted: null, cancelled: null, cancellation_reason: null };
  const raw = paidCanonicalJson(invalid); localStorage.setItem(PAID_BILL_STORAGE_KEY, raw);
  expect(() => readPaidBillEnvelope()).toThrow(); expect(() => new PaidBillJournal(scope).read()).toThrow();
  expect(paidBillWorkReadiness().verified).toBe(false); expect(hasPaidBillPrivateWork()).toBe(true);
  expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).toBe(raw);
});
