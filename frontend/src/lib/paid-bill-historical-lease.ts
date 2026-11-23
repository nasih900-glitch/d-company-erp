import { historicalEndpoint } from './paid-bill-historical-api';
import { effectivePaidEndpoint, matchesPaidOutcome, type PaidBillAction, type PaidBillOutcome } from './paid-bill-contract';
import { PaidBillJournal, paidCanonicalJson, readPaidBillEnvelope, type PaidBillEnvelope } from './paid-bill-journal';
import { ordinaryWorkspaceEndpoint, rootWorkspaceSnapshot } from './root-workspace-epoch';

const changed = () => new Error('Recovery context changed. Keep browser data and authenticate again.');
/** New in-memory attempt; stable original journal identity survives reload/reauthentication. */
export class HistoricalPaidLease {
  readonly action: PaidBillAction;
  readonly journal: PaidBillJournal;
  readonly context: string;
  private expected: string;
  private proposed: string | null = null;
  private closed = false;
  constructor(envelope: PaidBillEnvelope) {
    if (!envelope.pending) throw changed();
    this.action = envelope.pending;
    this.journal = new PaidBillJournal(this.action.scope);
    this.expected = paidCanonicalJson(envelope);
    this.context = rootWorkspaceSnapshot();
    const ordinary = ordinaryWorkspaceEndpoint();
    if (historicalEndpoint() !== this.action.scope.endpoint || ordinary !== null && effectivePaidEndpoint(ordinary, window.location.href) !== this.action.scope.endpoint) throw changed();
    this.check();
  }
  current() {
    try {
      const value = paidCanonicalJson(readPaidBillEnvelope());
      return !this.closed && historicalEndpoint() === this.action.scope.endpoint && rootWorkspaceSnapshot() === this.context && (value === this.expected || value === this.proposed);
    } catch { return false; }
  }
  check() { if (!this.current()) throw changed(); }
  close() { this.closed = true; }
  private transition(next: PaidBillEnvelope, write: () => void) {
    this.check(); const previous = this.expected; this.proposed = paidCanonicalJson(next);
    try { write(); }
    finally {
      try {
        const actual = paidCanonicalJson(readPaidBillEnvelope());
        if (actual !== previous && actual !== this.proposed) this.closed = true;
        else this.expected = actual;
      } catch { this.closed = true; }
      finally { this.proposed = null; }
    }
    this.check();
  }
  reason(reason: string) {
    const before = this.journal.read();
    this.transition({ ...before, cancellation_reason: reason }, () => this.journal.saveCancellationReason(this.action, reason));
  }
  acknowledge(outcome: PaidBillOutcome) {
    const before = this.journal.read();
    if (!matchesPaidOutcome(this.action, outcome, before.cancellation_reason)) throw changed();
    this.transition({ ...before, accepted: outcome.outcome === 'accepted' ? outcome.accepted : null, cancelled: outcome.outcome === 'cancelled' ? outcome.cancelled : null }, () => this.journal.acknowledge(this.action, outcome));
  }
  clear(outcome: PaidBillOutcome) {
    const before = this.journal.read();
    this.transition({ ...before, generation: before.generation + 1, pending: null, accepted: null, cancelled: null, cancellation_reason: null }, () => this.journal.clearExact(this.action, outcome));
  }
}
