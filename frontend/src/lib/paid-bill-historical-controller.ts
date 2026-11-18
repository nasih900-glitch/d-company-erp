import { historicalPaidApi } from './paid-bill-historical-api';
import { HistoricalPaidLease } from './paid-bill-historical-lease';
import { matchesPaidOutcome, validCancellationReason, type PaidBillAction, type PaidBillOutcome } from './paid-bill-contract';
import { readPaidBillEnvelope, subscribePaidBillWork, withPaidBillAdmission } from './paid-bill-journal';
import { rootWorkspaceSnapshot, subscribeRootWorkspace } from './root-workspace-epoch';

type Positive = Exclude<PaidBillOutcome, { outcome: 'unconfirmed' }>;
export interface HistoricalRecoveryView {
  busy: boolean; error: string | null; notice: string;
  private: { action: PaidBillAction; reason: string | null; outcome: Positive | null; pending: boolean } | null;
}
interface Grant { lease: HistoricalPaidLease; token: string; started: number; deadline: number }
const genericFailure = 'Recovery was not confirmed. Keep browser data and the exact saved request.';

/** RAM only; no ordinary session installation or commit/replay transport exists here. */
export class HistoricalPaidRecoveryController {
  view: HistoricalRecoveryView = { busy: false, error: null, notice: 'Saved Owner work can be checked after fresh authentication.', private: null };
  private listeners = new Set<() => void>();
  private grant: Grant | null = null;
  private revocation: Grant | null = null;
  private attempt: { lease: HistoricalPaidLease; abort: AbortController } | null = null;
  private serial = 0;
  private disposed = false;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private revocationTimer: ReturnType<typeof setTimeout> | undefined;
  private unsubscribe: () => void;
  constructor() {
    const check = () => {
      if (this.attempt && !this.attempt.lease.current() || this.grant && !this.valid(this.grant)) this.invalidate('Recovery context changed. Authenticate again; saved evidence was kept.');
    };
    const context = subscribeRootWorkspace(check), journal = subscribePaidBillWork(check);
    this.unsubscribe = () => { context(); journal(); };
  }
  subscribe(listener: () => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; }
  private publish(value: HistoricalRecoveryView) { if (!this.disposed) { this.view = value; for (const l of this.listeners) l(); } }
  private valid(g: Grant) { const now = performance.now(); return Number.isFinite(now) && now >= g.started && now < g.deadline && g.lease.current(); }
  private require(g: Grant) { if (!this.valid(g)) throw new Error(genericFailure); }
  private arm(g: Grant) {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      if (this.grant !== g) return;
      if (!this.valid(g)) this.invalidate('Recovery approval expired. Authenticate again; saved evidence was kept.');
      else this.arm(g);
    }, Math.max(0, g.deadline - performance.now()));
  }
  private invalidate(notice: string) {
    const previous = this.grant;
    ++this.serial; this.attempt?.abort.abort(); this.attempt?.lease.close(); this.attempt = null;
    this.grant = null; previous?.lease.close(); clearTimeout(this.timer);
    this.publish({ busy: false, error: null, notice, private: null });
    if (previous) { this.keepRevocation(previous); void this.revoke(previous, false); }
  }
  async unlock(password: string) {
    if (this.view.busy || this.disposed) return;
    this.invalidate('Checking recovery access…');
    const serial = this.serial;
    let lease: HistoricalPaidLease;
    try { lease = new HistoricalPaidLease(readPaidBillEnvelope()); }
    catch { this.publish({ busy: false, error: genericFailure, notice: 'Use the original configured server and keep browser data.', private: null }); return; }
    const started = performance.now();
    if (!Number.isFinite(started)) { this.invalidate(genericFailure); return; }
    const deadline = started + 299000;
    const abort = new AbortController(); this.attempt = { lease, abort };
    this.timer = setTimeout(() => { password = ''; if (serial === this.serial) this.invalidate('Recovery authentication expired. Saved evidence was kept.'); }, 299000);
    this.publish({ busy: true, error: null, notice: 'Checking recovery access…', private: null });
    try {
      await withPaidBillAdmission(async () => {
        lease.check();
        const now = performance.now();
        if (!Number.isFinite(now) || now < started || now >= deadline) throw new Error(genericFailure);
        const pending = historicalPaidApi.unlock(lease.action, password, abort.signal); password = '';
        const result = await pending; lease.check();
        // JWT NumericDate uses whole seconds: reserve one second as well as network time.
        const grant = { lease, token: result.token, started, deadline };
        this.require(grant); if (serial !== this.serial) throw new Error(genericFailure);
        this.grant = grant; this.arm(grant);
        this.publish({ busy: true, error: null, notice: 'Checking the exact saved outcome…', private: { action: lease.action, reason: lease.journal.read().cancellation_reason, outcome: null, pending: true } });
        await this.lookup(grant, abort.signal);
      }, true);
    } catch {
      if (serial === this.serial) {
        if (this.grant && this.valid(this.grant)) this.publish({ ...this.view, busy: false, error: genericFailure });
        else this.invalidate(genericFailure);
      }
    } finally { if (this.attempt?.lease === lease) this.attempt = null; }
  }
  private async lookup(g: Grant, signal: AbortSignal, cancel = false, reason = '') {
    this.require(g);
    if (cancel) {
      const stored = g.lease.journal.read().cancellation_reason;
      const exact = stored ?? reason.trim(); if (!validCancellationReason(exact)) throw new Error(genericFailure);
      g.lease.reason(exact); this.require(g);
      if (this.view.private) this.publish({ ...this.view, private: { ...this.view.private, reason: exact } });
      await this.finish(g, await historicalPaidApi.cancel(g.lease.action, exact, g.token, signal));
    } else await this.finish(g, await historicalPaidApi.outcome(g.lease.action, g.token, signal));
  }
  private async finish(g: Grant, outcome: PaidBillOutcome) {
    this.require(g);
    const reason = g.lease.journal.read().cancellation_reason;
    const data = { action: g.lease.action, reason, outcome: null as Positive | null, pending: true };
    if (outcome.outcome === 'unconfirmed') {
      if (outcome.action_key !== data.action.key || outcome.company_id !== data.action.scope.companyId || outcome.actor_user_id !== data.action.scope.userId || outcome.branch_id !== data.action.scope.branchId || outcome.terminal_id !== data.action.scope.terminalId) throw new Error(genericFailure);
      this.publish({ busy: false, error: null, notice: 'No durable outcome is confirmed. The exact saved request remains.', private: data }); return;
    }
    if (!matchesPaidOutcome(data.action, outcome, reason)) throw new Error(genericFailure);
    data.outcome = outcome;
    try { g.lease.acknowledge(outcome); g.lease.clear(outcome); }
    catch {
      this.require(g);
      this.publish({ busy: false, error: null, notice: 'Server outcome confirmed; local acknowledgement is pending. Keep browser data and check this same saved request.', private: data }); return;
    }
    this.require(g); data.pending = false;
    this.publish({ busy: false, error: null, notice: outcome.outcome === 'accepted' ? 'The request was already accepted. Continue only its existing task through normal authorized operations.' : 'The saved request was retired. No invoice value, payout or stock was changed.', private: data });
  }
  async check(cancel = false, reason = '') {
    const g = this.grant;
    if (!g || this.view.busy || this.disposed) return;
    const serial = this.serial, abort = new AbortController(); this.attempt = { lease: g.lease, abort };
    this.publish({ ...this.view, busy: true, error: null });
    try { await withPaidBillAdmission(async () => { this.require(g); await this.lookup(g, abort.signal, cancel, reason); }, true); }
    catch { if (serial === this.serial) { if (this.valid(g)) this.publish({ ...this.view, busy: false, error: genericFailure }); else this.invalidate('Recovery approval expired or context changed. Saved evidence was kept.'); } }
    finally { if (this.attempt?.abort === abort) this.attempt = null; }
  }
  private keepRevocation(g: Grant) {
    this.revocation = g; clearTimeout(this.revocationTimer);
    this.revocationTimer = setTimeout(() => { if (this.revocation === g) this.revocation = null; }, Math.max(0, g.deadline - performance.now()));
  }
  private async revoke(g: Grant, explicit: boolean) {
    const serial = this.serial; let context = '';
    try { context = rootWorkspaceSnapshot(); } catch { /* Only local lock can be claimed. */ }
    try {
      if (performance.now() >= g.deadline) throw new Error(genericFailure);
      await withPaidBillAdmission(() => { if (performance.now() >= g.deadline) throw new Error(genericFailure); return historicalPaidApi.lock(g.lease.action, g.token); }, true);
      if (this.revocation === g) { this.revocation = null; clearTimeout(this.revocationTimer); }
      if (explicit && serial === this.serial && rootWorkspaceSnapshot() === context) this.publish({ busy: false, error: null, notice: 'Server recovery access revoked. The saved command was kept.', private: null });
    } catch {
      if (explicit && serial === this.serial) this.publish({ busy: false, error: null, notice: 'Locked on this page; server confirmation is pending. The saved command was kept.', private: null });
    }
  }
  async lock() {
    const g = this.grant ?? this.revocation;
    // Do not launch the background revoke as well as the explicit one.
    this.grant = null; this.invalidate('Locked on this page. The saved command was kept.');
    if (g) { g.lease.close(); this.keepRevocation(g); await this.revoke(g, true); }
  }
  dispose() { if (this.disposed) return; this.invalidate('Recovery closed.'); this.disposed = true; this.revocation = null; clearTimeout(this.timer); clearTimeout(this.revocationTimer); this.unsubscribe(); this.listeners.clear(); }
}
