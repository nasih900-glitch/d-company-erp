/**
 * Orders & Shifts — the operations history screen.
 *
 *  Tabs:
 *    Orders   — open/held operations plus canonical receipt history
 *    Shifts   — open shift cash reconciliation · close shift workflow
 *
 * Why this matters: at end of day the cashier needs to:
 *   1. See every receipt issued (audit + spot mistakes)
 *   2. Count the cash drawer
 *   3. Close the shift — variance is recorded
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Receipt, Loader2, AlertCircle, RefreshCw, Eye, Lock, ShieldCheck,
  ClipboardList, X, Gamepad2, CreditCard, UserRound, ChevronDown,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { inr } from '@/lib/inr';
import { parseRupeesToMinor } from '@/lib/money-input';
import { resolveOpenShift, shiftResolutionMessage } from '@/lib/operational-context';
import { profileMembershipMoneyLabel } from '@/lib/product-profile';
import {
  orders, receipts, shifts,
  type OrderListItemDTO, type ReceiptHistoryDTO, type ShiftDTO,
} from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';
import { useAuth } from '@/modules/auth/AuthContext';
import { subscribeRealtime } from '@/lib/realtime';
import { SkeletonCard } from '@/components/ui/Skeleton';
import { useRealtimeRefresh } from '@/hooks/useRealtimeRefresh';
import { StableMutationIntent } from '@/lib/stable-mutation-intent';
import {
  exactQuantityLabel,
  orderTypeLabel,
  paymentMethodLabel,
  receiptLineCustomizationLabels,
  receiptPaymentActorLabel,
  receiptSourceLabel,
  sessionDurationLabel,
} from './receipt-history';
import {
  applyDirectOrderRecovery,
  directOrderRecoveryFailure,
  directOrderRecoveryFingerprint,
  directOrderRecoveryReasonError,
  isDirectOrderRecoveryEligible,
  normalizeDirectOrderRecoveryReason,
  type DirectOrderRecoveryFailure,
  type DirectOrderRecoveryPayload,
} from './order-recovery-policy';

type Tab = 'orders' | 'shifts';
// Fallback only — real-time push (see subscribeRealtime below) is what
// actually keeps this current; this just covers a missed/dropped push.
const OPERATIONS_POLL_MS = 120_000;

export default function OrdersAndShiftsScreen() {
  const [tab, setTab] = useState<Tab>('orders');

  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Operations</h2>
        <p className="text-fg-muted text-sm">
          Live orders, receipt history &amp; shift reconciliation
        </p>
      </header>

      <div className="scroll-strip flex gap-1 mb-6 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'orders'} onClick={() => setTab('orders')}>
          <Receipt size={14}/> Orders
        </TabBtn>
        <TabBtn active={tab === 'shifts'} onClick={() => setTab('shifts')}>
          <ClipboardList size={14}/> Shifts
        </TabBtn>
      </div>

      {tab === 'orders' ? <OrdersTab/> : <ShiftsTab/>}
    </div>
  );
}

// ============================================================================
// Live orders and immutable receipt history
// ============================================================================
function OrdersTab() {
  const { me } = useAuth();
  const [operationalRows, setOperationalRows] = useState<OrderListItemDTO[]>([]);
  const [receiptRows, setReceiptRows] = useState<ReceiptHistoryDTO[]>([]);
  const [operationalLoading, setOperationalLoading] = useState(true);
  const [receiptsLoading, setReceiptsLoading] = useState(true);
  const [operationalError, setOperationalError] = useState<string | null>(null);
  const [receiptsError, setReceiptsError] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [operationalView, setOperationalView] = useState<OrderListItemDTO | null>(null);
  const [receiptView, setReceiptView] = useState<{ orderId: string; invoiceNo: string } | null>(null);
  const [recoveryTarget, setRecoveryTarget] = useState<OrderListItemDTO | null>(null);
  const [recoveryIntent] = useState(() => new StableMutationIntent<DirectOrderRecoveryPayload>({
    prefix: 'pos-direct-recovery:web',
  }));
  const operationalSequence = useRef(0);
  const receiptSequence = useRef(0);

  const loadOperational = useCallback(async (showLoading: boolean) => {
    if (!LIVE_MODE) { setOperationalLoading(false); return; }
    const sequence = ++operationalSequence.current;
    if (showLoading) setOperationalLoading(true);
    setOperationalError(null);
    try {
      const result = await orders.list({ status: ['open', 'held'] });
      if (sequence === operationalSequence.current) setOperationalRows(result);
    } catch (error) {
      if (sequence === operationalSequence.current) setOperationalError((error as Error).message);
    } finally {
      if (sequence === operationalSequence.current) setOperationalLoading(false);
    }
  }, []);

  const loadReceipts = useCallback(async (showLoading: boolean) => {
    if (!LIVE_MODE) { setReceiptsLoading(false); return; }
    const sequence = ++receiptSequence.current;
    // A first-page refresh supersedes any in-flight pagination request.
    setLoadingMore(false);
    if (showLoading) setReceiptsLoading(true);
    setReceiptsError(null);
    try {
      const page = await receipts.list({ limit: 50 });
      if (sequence !== receiptSequence.current) return;
      setReceiptRows(page.items);
      setNextCursor(page.next_cursor);
      setHasMore(page.has_more);
    } catch (error) {
      if (sequence === receiptSequence.current) setReceiptsError((error as Error).message);
    } finally {
      if (sequence === receiptSequence.current) setReceiptsLoading(false);
    }
  }, []);

  const refreshAll = useCallback(async () => {
    await Promise.all([loadOperational(false), loadReceipts(false)]);
  }, [loadOperational, loadReceipts]);

  useEffect(() => {
    void Promise.all([loadOperational(true), loadReceipts(true)]);
  }, [loadOperational, loadReceipts]);

  useRealtimeRefresh({
    resources: ['orders', 'receipts'],
    refresh: refreshAll,
    enabled: LIVE_MODE,
  });

  useEffect(() => {
    if (!LIVE_MODE) return undefined;
    const id = setInterval(() => { void refreshAll(); }, OPERATIONS_POLL_MS);
    return () => clearInterval(id);
  }, [refreshAll]);

  const loadMore = useCallback(async () => {
    if (!nextCursor || loadingMore || receiptsLoading) return;
    const sequence = ++receiptSequence.current;
    setLoadingMore(true);
    setReceiptsError(null);
    try {
      const page = await receipts.list({ cursor: nextCursor, limit: 50 });
      if (sequence !== receiptSequence.current) return;
      setReceiptRows((current) => {
        const known = new Set(current.map((receipt) => receipt.order_id));
        return [...current, ...page.items.filter((receipt) => !known.has(receipt.order_id))];
      });
      setNextCursor(page.next_cursor);
      setHasMore(page.has_more);
    } catch (error) {
      if (sequence === receiptSequence.current) setReceiptsError((error as Error).message);
    } finally {
      if (sequence === receiptSequence.current) setLoadingMore(false);
    }
  }, [loadingMore, nextCursor, receiptsLoading]);

  const beginRecovery = useCallback((order: OrderListItemDTO) => {
    if (!me?.protected_access || !isDirectOrderRecoveryEligible(order)) return;
    setRecoveryTarget(order);
  }, [me?.protected_access]);

  const closeRecovery = useCallback(() => {
    setRecoveryTarget(null);
  }, []);

  const refreshAfterRecoveryFailure = useCallback(() => {
    recoveryIntent.invalidate();
    closeRecovery();
    void loadOperational(true);
  }, [closeRecovery, loadOperational, recoveryIntent]);

  const recoverDirectOrder = useCallback(async (
    order: OrderListItemDTO,
    reason: string,
  ) => {
    const normalizedReason = normalizeDirectOrderRecoveryReason(reason);
    const validationError = directOrderRecoveryReasonError(normalizedReason);
    if (validationError) throw new Error(validationError);
    const payload: DirectOrderRecoveryPayload = {
      expected_checkout_version: order.checkout_version,
      reason: normalizedReason,
    };
    const attempt = recoveryIntent.resolve(
      directOrderRecoveryFingerprint(order.id, payload),
      () => payload,
    );

    const recovered = await orders.holdForCheckout(
      order.id,
      attempt.payload,
      attempt.idempotencyKey,
    );
    recoveryIntent.confirmSuccess(attempt);
    setOperationalRows((current) => applyDirectOrderRecovery(current, recovered));
    await loadOperational(false);
    setRecoveryTarget(null);
  }, [loadOperational, recoveryIntent]);

  if (!LIVE_MODE) return <div className="card text-fg-muted text-sm">Order history is live-mode only.</div>;

  const refreshing = operationalLoading || receiptsLoading;

  return (
    <div className="space-y-5">
      <div className="flex justify-between items-center flex-wrap gap-3">
        <div>
          <p className="text-sm font-semibold">Live operations and immutable receipts</p>
          <p className="text-xs text-fg-muted">
            Open work stays separate from finalized, server-authoritative billing history.
          </p>
        </div>
        <button
          type="button"
          className="btn btn-ghost"
          onClick={() => { void Promise.all([loadOperational(true), loadReceipts(true)]); }}
          disabled={refreshing}
          aria-label="Refresh orders and receipt history"
        >
          <RefreshCw size={14} className={refreshing ? 'animate-spin' : ''}/>
          Refresh
        </button>
      </div>

      <section className="card !p-0 overflow-hidden">
        <div className="flex items-center justify-between gap-3 border-b border-bg-border px-4 py-3">
          <div>
            <h3 className="font-semibold">Open &amp; held orders</h3>
            <p className="text-xs text-fg-muted">Operational bills that have not been finalized.</p>
          </div>
          <span className="chip border-accent-gold/30 text-accent-gold">{operationalRows.length} active</span>
        </div>
        {operationalError && <InlineLoadError message={operationalError} onRetry={() => { void loadOperational(true); }}/>}
        {operationalLoading ? <div className="p-4"><SkeletonCard/></div> : !operationalRows.length && !operationalError ? (
          <div className="px-4 py-8 text-center">
            <Receipt className="mx-auto mb-2 text-fg-muted" size={24}/>
            <p className="font-medium">No open bills</p>
            <p className="mt-1 text-xs text-fg-muted">New POS and Gaming bills will appear here until payment.</p>
          </div>
        ) : operationalRows.length ? <OperationalOrderList
          rows={operationalRows}
          protectedAccess={Boolean(me?.protected_access)}
          onView={setOperationalView}
          onRecover={beginRecovery}
        /> : null}
      </section>

      <section className="card !p-0 overflow-hidden">
        <div className="flex items-center justify-between gap-3 border-b border-bg-border px-4 py-3">
          <div>
            <h3 className="font-semibold">Receipt history</h3>
            <p className="text-xs text-fg-muted">Finalized receipts from the shared server.</p>
          </div>
          <div className="text-right">
            <div className="text-xs text-fg-muted">Server history</div>
            <div className="font-mono text-sm font-semibold">{receiptRows.length} loaded</div>
          </div>
        </div>
        {receiptsError && <InlineLoadError message={receiptsError} onRetry={() => { void loadReceipts(true); }}/>}
        {receiptsLoading ? <div className="p-4"><SkeletonCard/></div> : !receiptRows.length && !receiptsError ? (
          <div className="px-4 py-10 text-center">
            <Receipt className="mx-auto mb-2 text-fg-muted" size={26}/>
            <p className="font-medium">No finalized receipts yet</p>
            <p className="mt-1 text-xs text-fg-muted">A receipt appears here after a successful Cash, UPI or other payment.</p>
          </div>
        ) : receiptRows.length ? <ReceiptHistoryList rows={receiptRows} onView={setReceiptView}/> : null}
        {hasMore && (
          <div className="border-t border-bg-border p-3 text-center">
            <button type="button" className="btn btn-ghost" onClick={() => { void loadMore(); }} disabled={loadingMore || receiptsLoading}>
              {loadingMore ? <Loader2 className="animate-spin" size={14}/> : <ChevronDown size={14}/>} Load older receipts
            </button>
          </div>
        )}
      </section>

      {operationalView && <OperationalOrderModal order={operationalView} onClose={() => setOperationalView(null)}/>}
      {recoveryTarget && <RecoverDirectOrderModal
        key={`${recoveryTarget.id}:${recoveryTarget.checkout_version}`}
        order={recoveryTarget}
        onClose={closeRecovery}
        onRefresh={refreshAfterRecoveryFailure}
        onSubmit={(reason) => recoverDirectOrder(recoveryTarget, reason)}
      />}
      {receiptView && <ReceiptViewModal
        orderId={receiptView.orderId}
        invoiceNo={receiptView.invoiceNo}
        onClose={() => setReceiptView(null)}
      />}
    </div>
  );
}

export function OperationalOrderList({ rows, protectedAccess, onView, onRecover }: {
  rows: OrderListItemDTO[];
  protectedAccess: boolean;
  onView: (order: OrderListItemDTO) => void;
  onRecover: (order: OrderListItemDTO) => void;
}) {
  return (
    <div className="divide-y divide-bg-border/60">
      {rows.map((order) => {
        const canRecover = protectedAccess && isDirectOrderRecoveryEligible(order);
        return (
          <div key={order.id} className="flex min-h-14 items-center gap-2 pr-3">
            <button
              type="button"
              onClick={() => onView(order)}
              className="flex min-w-0 flex-1 items-center justify-between gap-4 px-4 py-3 text-left transition hover:bg-bg-raised/40 active:bg-bg-raised/70"
            >
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="font-semibold">{order.source_label || orderTypeLabel(order.type)}</span>
                  <span className={`chip text-[10px] ${order.status === 'held' ? 'border-accent-gold/40 text-accent-gold' : 'border-fg-muted/40 text-fg-muted'}`}>
                    {order.status}
                  </span>
                </div>
                <div className="mt-1 text-xs text-fg-muted">
                  {formatDateTime(order.held_at || order.created_at)} · {order.items_count} line{order.items_count === 1 ? '' : 's'}
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-3">
                <span className="font-mono font-semibold">{inr(order.total_minor)}</span>
                <Eye className="text-fg-muted" size={15}/>
              </div>
            </button>
            {canRecover ? (
              <button
                type="button"
                className="btn btn-ghost shrink-0 whitespace-nowrap text-xs"
                onClick={() => onRecover(order)}
                aria-label={`Recover order ${order.id.slice(0, 8)} to POS`}
              >
                <ShieldCheck size={14}/> Recover to POS
              </button>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

export function RecoverDirectOrderModal({
  order,
  onClose,
  onRefresh,
  onSubmit,
}: {
  order: OrderListItemDTO;
  onClose: () => void;
  onRefresh: () => void;
  onSubmit: (reason: string) => Promise<void>;
}) {
  const [reason, setReason] = useState('');
  const [abandonedConfirmed, setAbandonedConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<DirectOrderRecoveryFailure | null>(null);
  const submissionInFlight = useRef(false);
  const reasonError = reason.length > 0 ? directOrderRecoveryReasonError(reason) : null;
  const canSubmit = abandonedConfirmed
    && directOrderRecoveryReasonError(reason) === null
    && !busy;

  const close = () => {
    if (!busy) onClose();
  };

  const submit = async () => {
    if (submissionInFlight.current) return;
    const validationError = directOrderRecoveryReasonError(reason);
    if (validationError) {
      setFailure({ message: validationError, resolution: 'retry' });
      return;
    }
    if (!abandonedConfirmed) {
      setFailure({
        message: 'Confirm that the original checkout or device is abandoned before recovery.',
        resolution: 'retry',
      });
      return;
    }

    submissionInFlight.current = true;
    setBusy(true);
    setFailure(null);
    try {
      await onSubmit(normalizeDirectOrderRecoveryReason(reason));
    } catch (error) {
      setFailure(directOrderRecoveryFailure(error));
    } finally {
      submissionInFlight.current = false;
      setBusy(false);
    }
  };

  return (
    <Modal
      open
      onClose={close}
      title={`Recover order ${order.id.slice(0, 8)} to POS?`}
      size="md"
    >
      <form
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <div className="rounded-xl border border-accent-bad/35 bg-accent-bad/10 p-3 text-sm">
          <p className="font-semibold text-accent-bad">Verify the original checkout is abandoned</p>
          <p className="mt-1 text-fg-muted">
            Use recovery only after checking that the original checkout screen or device is no
            longer processing this order. Recovery does not collect payment; it moves the bill
            into the shared POS queue so one cashier can claim it.
          </p>
        </div>

        <div className="grid gap-2 rounded-xl border border-bg-border bg-bg-raised/30 p-3 text-sm sm:grid-cols-2">
          <Row label="Order" value={order.id}/>
          <Row label="Current total" value={inr(order.total_minor)} bold/>
          <Row label="Status" value={order.status}/>
          <Row label="Bill version" value={String(order.checkout_version)}/>
        </div>

        <label className="block">
          <span className="text-sm font-medium">Recovery reason</span>
          <textarea
            className="input mt-1 min-h-24 resize-y"
            value={reason}
            onChange={(event) => {
              setReason(event.target.value);
              setFailure(null);
            }}
            minLength={3}
            maxLength={500}
            required
            disabled={busy}
            autoFocus
            aria-describedby="direct-order-recovery-reason-help"
            placeholder="Example: Front counter tablet closed before checkout completed"
          />
          <span
            id="direct-order-recovery-reason-help"
            className={`mt-1 flex justify-between gap-3 text-xs ${reasonError ? 'text-accent-bad' : 'text-fg-muted'}`}
          >
            <span>{reasonError || 'Required for the recovery audit trail.'}</span>
            <span>{reason.length}/500</span>
          </span>
        </label>

        <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-bg-border p-3 text-sm">
          <input
            type="checkbox"
            className="mt-0.5 h-4 w-4 shrink-0 accent-[var(--accent)]"
            checked={abandonedConfirmed}
            onChange={(event) => {
              setAbandonedConfirmed(event.target.checked);
              setFailure(null);
            }}
            disabled={busy}
          />
          <span>
            I confirmed the original checkout/device is abandoned and no one is collecting
            payment for this order.
          </span>
        </label>

        {failure ? (
          <div
            role="alert"
            className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad"
          >
            {failure.message}
          </div>
        ) : null}

        <p className="text-xs text-fg-muted">
          If the request times out, leave these details unchanged and retry here. The app will
          reuse the same operation key instead of creating a second recovery.
        </p>

        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn btn-ghost" onClick={close} disabled={busy}>
            Cancel
          </button>
          {failure?.resolution === 'refresh' ? (
            <button type="button" className="btn btn-primary" onClick={onRefresh} disabled={busy}>
              <RefreshCw size={14}/> Refresh orders
            </button>
          ) : (
            <button type="submit" className="btn btn-danger" disabled={!canSubmit}>
              {busy ? <Loader2 className="animate-spin" size={14}/> : <ShieldCheck size={14}/>}
              {failure ? 'Retry same recovery' : 'Recover to POS'}
            </button>
          )}
        </div>
      </form>
    </Modal>
  );
}

function ReceiptHistoryList({ rows, onView }: {
  rows: ReceiptHistoryDTO[];
  onView: (receipt: { orderId: string; invoiceNo: string }) => void;
}) {
  return (
    <>
      <div className="hidden overflow-x-auto md:block">
        <table className="w-full text-sm">
          <thead className="bg-bg-raised/60 text-xs text-fg-muted">
            <tr>
              <th className="p-3 text-left font-medium">Issued</th>
              <th className="p-3 text-left font-medium">Invoice</th>
              <th className="p-3 text-left font-medium">Source</th>
              <th className="p-3 text-left font-medium">Payment</th>
              <th className="p-3 text-left font-medium">Employee</th>
              <th className="p-3 text-right font-medium">Total</th>
              <th className="p-3 text-left font-medium">Status</th>
              <th className="p-3"><span className="sr-only">Open receipt</span></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((receipt) => (
              <tr key={receipt.order_id} className="border-t border-bg-border/60 transition hover:bg-bg-raised/30">
                <td className="p-3 text-xs text-fg-muted">{formatDateTime(receipt.invoice_issued_at)}</td>
                <td className="p-3 font-mono text-xs font-semibold">{receipt.invoice_no}</td>
                <td className="p-3">{receiptSourceLabel(receipt.gaming_sessions, receipt.order_type)}</td>
                <td className="p-3 text-xs text-fg-muted">{receiptPaymentLabels(receipt)}</td>
                <td className="p-3 text-xs text-fg-muted">{receiptPaymentActorLabel(receipt.payments)}</td>
                <td className="p-3 text-right">
                  <div className="font-mono font-semibold">{inr(receipt.total_minor)}</div>
                  {receipt.refunded_minor > 0 && (
                    <div className="mt-0.5 text-[10px] text-fg-muted">Net {inr(receipt.net_collected_minor)}</div>
                  )}
                </td>
                <td className="p-3"><ReceiptStatus status={receipt.status}/></td>
                <td className="p-3 text-right">
                  <button
                    type="button"
                    className="rounded-lg p-2 text-fg-muted transition hover:bg-bg-raised hover:text-accent"
                    onClick={() => onView({ orderId: receipt.order_id, invoiceNo: receipt.invoice_no })}
                    aria-label={`Open receipt ${receipt.invoice_no}`}
                  ><Eye size={15}/></button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="divide-y divide-bg-border/60 md:hidden">
        {rows.map((receipt) => (
          <button
            key={receipt.order_id}
            type="button"
            onClick={() => onView({ orderId: receipt.order_id, invoiceNo: receipt.invoice_no })}
            className="w-full p-4 text-left transition active:bg-bg-raised/60"
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="font-mono text-sm font-semibold">{receipt.invoice_no}</div>
                <div className="mt-1 truncate text-xs text-fg-muted">
                  {receiptSourceLabel(receipt.gaming_sessions, receipt.order_type)} · {formatDateTime(receipt.invoice_issued_at)}
                </div>
              </div>
              <ReceiptStatus status={receipt.status}/>
            </div>
            <div className="mt-3 flex items-end justify-between gap-3">
              <div className="text-xs text-fg-muted">
                {receiptPaymentLabels(receipt)} · {receiptPaymentActorLabel(receipt.payments)}
              </div>
              <div className="text-right">
                <div className="font-mono font-semibold">{inr(receipt.total_minor)}</div>
                {receipt.refunded_minor > 0 && (
                  <div className="mt-0.5 text-[10px] text-fg-muted">Net {inr(receipt.net_collected_minor)}</div>
                )}
              </div>
            </div>
          </button>
        ))}
      </div>
    </>
  );
}

function OperationalOrderModal({ order, onClose }: { order: OrderListItemDTO; onClose: () => void }) {
  return (
    <Modal open onClose={onClose} title={`Order ${order.invoice_no || order.id.slice(0, 8)}`}>
      <div className="space-y-2 text-sm">
        <Row label="Source" value={order.source_label || orderTypeLabel(order.type)}/>
        <Row label="Status" value={order.status}/>
        <Row label="Customer" value={order.customer_name || '—'}/>
        <Row label="Opened" value={formatDateTime(order.created_at)}/>
        {order.held_at && <Row label="Sent to POS" value={formatDateTime(order.held_at)}/>}
        <Row label="Items" value={order.items_count.toString()}/>
        <Row label="Total" value={inr(order.total_minor)} bold/>
        <p className="text-xs text-fg-muted pt-3 border-t border-bg-border">
          This bill is still operational. Its immutable receipt will appear in Receipt history after payment succeeds.
        </p>
      </div>
    </Modal>
  );
}

function ReceiptViewModal({ orderId, invoiceNo, onClose }: {
  orderId: string;
  invoiceNo: string;
  onClose: () => void;
}) {
  const [receipt, setReceipt] = useState<ReceiptHistoryDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestSequence = useRef(0);

  const load = useCallback(async () => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(null);
    try {
      const result = await receipts.get(orderId);
      if (sequence === requestSequence.current) setReceipt(result);
    } catch (cause) {
      if (sequence === requestSequence.current) setError((cause as Error).message);
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, [orderId]);

  useEffect(() => { void load(); }, [load]);
  useRealtimeRefresh({ resources: ['receipts'], refresh: load, enabled: LIVE_MODE });

  return (
    <Modal open onClose={onClose} title={`Receipt ${invoiceNo}`} size="lg">
      {loading && !receipt ? <SkeletonCard/> : error && !receipt ? (
        <InlineLoadError message={error} onRetry={() => { void load(); }}/>
      ) : receipt ? (
        <>
          {error && <InlineLoadError message={error} onRetry={() => { void load(); }}/>}
          <ReceiptDetail receipt={receipt} refreshing={loading}/>
        </>
      ) : null}
    </Modal>
  );
}

function ReceiptDetail({ receipt, refreshing }: { receipt: ReceiptHistoryDTO; refreshing: boolean }) {
  return (
    <div className="space-y-5">
      <section className="rounded-xl border border-bg-border bg-bg-raised/35 p-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <ReceiptStatus status={receipt.status}/>
              {refreshing && <Loader2 className="animate-spin text-fg-muted" size={13}/>}
            </div>
            <h4 className="mt-2 font-mono text-xl font-bold tracking-tight">{receipt.invoice_no}</h4>
            <p className="mt-1 text-xs text-fg-muted">Issued {formatDateTime(receipt.invoice_issued_at)} · FY {receipt.fiscal_year}</p>
          </div>
          <div className="text-right">
            <div className="text-xs uppercase tracking-wider text-fg-muted">Receipt total</div>
            <div className="font-mono text-2xl font-bold">{inr(receipt.total_minor)}</div>
            <div className="mt-1 text-xs text-fg-muted">
              {receipt.refunded_minor > 0
                ? `Net collected ${inr(receipt.net_collected_minor)}`
                : `Payments recorded ${inr(receipt.paid_minor)}`}
            </div>
          </div>
        </div>
        <div className="mt-4 grid gap-3 border-t border-bg-border pt-3 text-sm sm:grid-cols-3">
          <InfoTile label="Source" value={receiptSourceLabel(receipt.gaming_sessions, receipt.order_type)}/>
          <InfoTile label="Customer" value={receipt.customer_name || 'Walk-in'}/>
          <InfoTile label="Payments recorded by" value={receiptPaymentActorLabel(receipt.payments)}/>
        </div>
      </section>

      <ReceiptLines receipt={receipt}/>

      <div className="grid gap-4 lg:grid-cols-[1.15fr_0.85fr]">
        <ReceiptPayments receipt={receipt}/>
        <ReceiptTotals receipt={receipt}/>
      </div>

      {receipt.gaming_sessions.length > 0 && <GamingProvenance receipt={receipt}/>}

      <section className="rounded-xl border border-bg-border p-4">
        <SectionTitle icon={<UserRound size={16}/>} title="People & shift identity"/>
        <div className="mt-3 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <Row label="Order opened by" value={actorLabel(receipt.opened_by_name, receipt.opened_by)}/>
          <Row label="Shift opened by" value={actorLabel(receipt.shift_opened_by_name, receipt.shift_opened_by)}/>
          <Row label="Shift opened" value={formatOptionalDateTime(receipt.shift_opened_at)}/>
          <Row label="Shift closed" value={formatOptionalDateTime(receipt.shift_closed_at)}/>
          <div className="sm:col-span-2"><Row label="Shift ID" value={receipt.shift_id}/></div>
          <div className="sm:col-span-2"><Row label="Terminal ID" value={receipt.terminal_id}/></div>
        </div>
      </section>

      {(receipt.customer_phone || receipt.customer_gstin || receipt.customer_address || receipt.notes) && (
        <section className="rounded-xl border border-bg-border p-4 text-sm">
          <h5 className="font-semibold">Customer & receipt notes</h5>
          <div className="mt-3 space-y-1">
            {receipt.customer_phone && <Row label="Phone" value={receipt.customer_phone}/>}
            {receipt.customer_gstin && <Row label="GSTIN" value={receipt.customer_gstin}/>}
            {receipt.customer_address && <Row label="Address" value={receipt.customer_address}/>}
            {receipt.notes && <Row label="Notes" value={receipt.notes}/>}
          </div>
        </section>
      )}
    </div>
  );
}

function ReceiptLines({ receipt }: { receipt: ReceiptHistoryDTO }) {
  return (
    <section className="overflow-hidden rounded-xl border border-bg-border">
      <div className="border-b border-bg-border px-4 py-3">
        <h5 className="font-semibold">Sold lines</h5>
        <p className="text-xs text-fg-muted">Immutable names, prices, tax and customizations captured at sale.</p>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[620px] text-sm">
          <thead className="bg-bg-raised/50 text-xs text-fg-muted">
            <tr>
              <th className="p-3 text-left font-medium">Item</th>
              <th className="p-3 text-right font-medium">Qty</th>
              <th className="p-3 text-right font-medium">Unit</th>
              <th className="p-3 text-right font-medium">Discount</th>
              <th className="p-3 text-right font-medium">Line total</th>
            </tr>
          </thead>
          <tbody>
            {receipt.lines.map((line) => {
              const customizations = receiptLineCustomizationLabels(line);
              return (
                <tr key={line.id} className="border-t border-bg-border/60 align-top">
                  <td className="p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className={line.voided_at ? 'line-through text-fg-muted' : 'font-medium'}>{line.menu_item_name}</span>
                      {line.voided_at && <span className="chip border-accent-bad/40 text-[10px] text-accent-bad">Voided</span>}
                    </div>
                    {customizations.length > 0 && <div className="mt-1 text-xs text-fg-muted">{customizations.join(' · ')}</div>}
                    <div className="mt-1 text-[11px] text-fg-muted">
                      {line.hsn_or_sac ? `HSN/SAC ${line.hsn_or_sac} · ` : ''}Tax {exactQuantityLabel(line.tax_rate)}%
                    </div>
                    {line.note && <div className="mt-1 text-xs text-fg-muted">Note: {line.note}</div>}
                    {line.void_reason && (
                      <div className="mt-1 text-xs text-accent-bad">
                        Voided{line.voided_by_name ? ` by ${line.voided_by_name}` : ''}: {line.void_reason}
                      </div>
                    )}
                  </td>
                  <td className="p-3 text-right font-mono">{exactQuantityLabel(line.qty)}</td>
                  <td className="p-3 text-right font-mono">{inr(line.unit_price_minor)}</td>
                  <td className="p-3 text-right font-mono">{line.discount_minor ? `−${inr(line.discount_minor)}` : '—'}</td>
                  <td className="p-3 text-right font-mono font-semibold">{inr(line.line_total_minor)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function ReceiptPayments({ receipt }: { receipt: ReceiptHistoryDTO }) {
  return (
    <section className="rounded-xl border border-bg-border p-4">
      <SectionTitle icon={<CreditCard size={16}/>} title="Payments & refunds"/>
      <div className="mt-3 space-y-3">
        {!receipt.payments.length && (
          <p className="rounded-lg bg-bg-raised/40 p-3 text-sm text-fg-muted">
            No payment rail was required or recorded for this receipt.
          </p>
        )}
        {receipt.payments.map((payment) => (
          <div key={payment.id} className="rounded-lg bg-bg-raised/40 p-3 text-sm">
            <div className="flex items-center justify-between gap-3">
              <span className="font-semibold">{paymentMethodLabel(payment.method)}</span>
              <span className="font-mono font-bold">{inr(payment.amount_minor)}</span>
            </div>
            <div className="mt-2 space-y-1">
              <Row label="Paid" value={formatDateTime(payment.paid_at)}/>
              <Row label="Recorded by" value={actorLabel(payment.recorded_by_name, payment.recorded_by)}/>
              {payment.tendered_minor !== null && <Row label="Cash tendered" value={inr(payment.tendered_minor)}/>}
              {payment.change_minor !== null && <Row label="Change given" value={inr(payment.change_minor)}/>}
              {payment.reference && <Row label="Reference" value={payment.reference}/>}
              <Row label="Payment shift" value={payment.shift_id}/>
            </div>
          </div>
        ))}
        {receipt.refunds.map((refund) => (
          <div key={refund.id} className="rounded-lg border border-accent-bad/25 bg-accent-bad/5 p-3 text-sm">
            <div className="flex items-center justify-between gap-3">
              <div>
                <span className="font-semibold text-accent-bad">Refund</span>
                {refund.receipt_no && <span className="ml-2 font-mono text-xs text-fg-muted">{refund.receipt_no}</span>}
              </div>
              <span className="font-mono font-bold text-accent-bad">−{inr(refund.amount_minor)}</span>
            </div>
            <div className="mt-2 space-y-1">
              <Row label="Method" value={refund.settlement_method ? paymentMethodLabel(refund.settlement_method) : 'Not recorded'}/>
              <Row label="Reason" value={orderTypeLabel(refund.reason_code)}/>
              <Row label="Settled" value={formatOptionalDateTime(refund.settled_at)}/>
              <Row label="Approved by" value={actorLabel(refund.approved_by_name, refund.approved_by)}/>
              <Row label="Settled by" value={actorLabel(refund.settled_by_name, refund.settled_by)}/>
              {refund.manager_override_user_id && (
                <Row label="Manager override" value={actorLabel(refund.manager_override_user_name, refund.manager_override_user_id)}/>
              )}
              {refund.external_reference && <Row label="Reference" value={refund.external_reference}/>}
              {refund.settlement_shift_id && <Row label="Settlement shift" value={refund.settlement_shift_id}/>}
              {refund.note && <Row label="Note" value={refund.note}/>}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function ReceiptTotals({ receipt }: { receipt: ReceiptHistoryDTO }) {
  return (
    <section className="rounded-xl border border-bg-border p-4 text-sm">
      <h5 className="font-semibold">Receipt totals</h5>
      <div className="mt-3 space-y-1">
        <Row label="Taxable value" value={inr(receipt.subtotal_minor)}/>
        {receipt.cgst_minor > 0 && <Row label="CGST" value={inr(receipt.cgst_minor)}/>}
        {receipt.sgst_minor > 0 && <Row label="SGST" value={inr(receipt.sgst_minor)}/>}
        {receipt.igst_minor > 0 && <Row label="IGST" value={inr(receipt.igst_minor)}/>}
        {receipt.cess_minor > 0 && <Row label="Cess" value={inr(receipt.cess_minor)}/>}
        <Row label="Tax total" value={inr(receipt.tax_minor)}/>
        {receipt.discount_minor > 0 && <Row label="Discounts (already applied)" value={inr(receipt.discount_minor)}/>}
        {receipt.manual_discount_minor > 0 && <Row label="Includes manual discount" value={inr(receipt.manual_discount_minor)}/>}
        {receipt.points_redeemed_minor > 0 && <Row label="Includes points redeemed" value={inr(receipt.points_redeemed_minor)}/>}
        {receipt.round_off_minor !== 0 && <Row label="Round off" value={signedMoney(receipt.round_off_minor)}/>}
        {receipt.tip_minor > 0 && <Row label="Tip" value={inr(receipt.tip_minor)}/>}
        <Row label="Total" value={inr(receipt.total_minor)} bold/>
        <Row label="Original payments" value={inr(receipt.paid_minor)} bold/>
        {receipt.refunded_minor > 0 && <Row label="Refunded" value={`−${inr(receipt.refunded_minor)}`}/>}
        {receipt.refunded_minor > 0 && <Row label="Net collected" value={inr(receipt.net_collected_minor)} bold/>}
      </div>
    </section>
  );
}

function GamingProvenance({ receipt }: { receipt: ReceiptHistoryDTO }) {
  return (
    <section className="rounded-xl border border-bg-border p-4">
      <SectionTitle icon={<Gamepad2 size={16}/>} title="Gaming session provenance"/>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        {receipt.gaming_sessions.map((session) => (
          <article key={session.id} className="rounded-lg bg-bg-raised/40 p-3 text-sm">
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className="font-semibold">{session.station_name}</div>
                <div className="text-xs text-fg-muted">{session.station_code} · {orderTypeLabel(session.station_type)}</div>
              </div>
              {session.amount_minor !== null && <div className="font-mono font-bold">{inr(session.amount_minor)}</div>}
            </div>
            <div className="mt-3 space-y-1">
              <Row label="Billing" value={orderTypeLabel(session.billing_mode)}/>
              <Row label="Rate" value={`${inr(session.rate_per_hour_minor)}/hr`}/>
              <Row label="Billable time" value={sessionDurationLabel(session.billable_minutes)}/>
              {session.paused_minutes > 0 && <Row label="Paused" value={sessionDurationLabel(session.paused_minutes)}/>}
              <Row label="Started" value={formatDateTime(session.started_at)}/>
              <Row label="Stopped" value={formatOptionalDateTime(session.stopped_at)}/>
              <Row label="Started by" value={actorLabel(session.started_by_name, session.started_by)}/>
              <Row label="Stopped by" value={actorLabel(session.stopped_by_name, session.stopped_by)}/>
              <Row label="Sent to POS by" value={actorLabel(session.sent_to_pos_by_name, session.sent_to_pos_by)}/>
              <Row label="Source shift" value={session.source_shift_id}/>
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}

function ReceiptStatus({ status }: { status: string }) {
  return (
    <span className={`chip text-[10px] ${status === 'paid' ? 'border-accent-good/40 text-accent-good' : 'border-accent-gold/40 text-accent-gold'}`}>
      {status}
    </span>
  );
}

function InlineLoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="m-3 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad">
      <div className="flex min-w-0 items-start gap-2"><AlertCircle className="mt-0.5 shrink-0" size={15}/><span>{message}</span></div>
      <button type="button" className="btn btn-ghost" onClick={onRetry}><RefreshCw size={14}/> Retry</button>
    </div>
  );
}

function SectionTitle({ icon, title }: { icon: React.ReactNode; title: string }) {
  return <h5 className="flex items-center gap-2 font-semibold text-fg">{icon}{title}</h5>;
}

function InfoTile({ label, value }: { label: string; value: string }) {
  return <div><div className="text-[10px] uppercase tracking-wider text-fg-muted">{label}</div><div className="mt-0.5 font-medium">{value}</div></div>;
}

function receiptPaymentLabels(receipt: ReceiptHistoryDTO): string {
  const labels = [...new Set(receipt.payments.map((payment) => paymentMethodLabel(payment.method)))];
  return labels.length ? labels.join(' + ') : 'Payment not recorded';
}

function actorLabel(name: string | null, id: string | null): string {
  if (name?.trim()) return name.trim();
  return id ? `Employee ${id.slice(0, 8)}` : 'Legacy record — not recorded';
}

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

function formatOptionalDateTime(value: string | null): string {
  return value ? formatDateTime(value) : 'Not recorded';
}

function signedMoney(value: number): string {
  if (value === 0) return inr(0);
  return `${value > 0 ? '+' : '−'}${inr(Math.abs(value))}`;
}

// ============================================================================
// Shifts
// ============================================================================
function ShiftsTab() {
  const { me, terminalId, terminalReady } = useAuth();
  const [rows, setRows] = useState<ShiftDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [closing, setClosing] = useState<ShiftDTO | null>(null);
  const [opening, setOpening] = useState(false);

  const load = useCallback(async () => {
    if (!LIVE_MODE) { setLoading(false); return; }
    if (!terminalReady || !terminalId) {
      setRows([]);
      setErr('The shared register could not be verified. Refresh; if the problem remains, ask a protected owner to check the Combined register setup.');
      setLoading(false);
      return;
    }
    setLoading(true); setErr(null);
    try { setRows(await shifts.list()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }, [terminalId, terminalReady]);
  useEffect(() => { void load(); }, [load]);

  // Whether a shift is open, and who has it open, is shared across every
  // device on this terminal. Real-time push means a shift opened elsewhere
  // shows up here within about a second — this is what stops someone from
  // trying to open a second shift on top of one that's already running,
  // just because their screen hadn't caught up yet. The interval is just a
  // safety net for a missed push.
  useEffect(() => {
    if (!LIVE_MODE || !terminalReady || !terminalId) return;
    const refresh = () => { shifts.list().then(setRows).catch(() => {}); };
    const unsubscribe = subscribeRealtime('shifts', refresh);
    const id = setInterval(refresh, OPERATIONS_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, [terminalReady, terminalId]);

  if (!LIVE_MODE) return <div className="card text-fg-muted text-sm">Shift management is live-mode only.</div>;
  if (loading) return <SkeletonCard />;

  const openResolution = resolveOpenShift({
    storedShiftId: null,
    branchId: me?.branch_id ?? null,
    terminalId,
    openShifts: rows,
  });
  const openShift = openResolution.kind === 'ready' ? openResolution.shift : undefined;
  const scopeError = openResolution.kind === 'ambiguous_open_shifts'
    ? shiftResolutionMessage(openResolution)
    : null;

  return (
    <div>
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          {rows.length} shift{rows.length === 1 ? '' : 's'} ·{' '}
          {openShift
            ? <span className="text-accent-good">Open shift active</span>
            : <span>No shift currently open</span>}
        </p>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          {!openShift && !scopeError && (
            <button className="btn btn-primary" onClick={() => setOpening(true)}>
              <ShieldCheck size={14}/> Open shift
            </button>
          )}
        </div>
      </div>

      {err && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {err}
      </div>}
      {scopeError && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {scopeError}
      </div>}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No shifts recorded. Open the first shift before taking orders.
        </div>
      ) : (
        <div className="space-y-3">
          {rows.map((s) => {
            const legacyRevenueLabel = profileMembershipMoneyLabel(
              'revenue',
              s.membership_sales_minor ?? 0,
            );
            const legacyRefundLabel = profileMembershipMoneyLabel(
              'refund',
              s.settled_membership_refunds_minor ?? 0,
            );
            return (
            <div key={s.id} className="card">
              <div className="flex justify-between items-start gap-3 flex-wrap">
                <div>
                  <div className="font-bold flex items-center gap-2">
                    {s.status === 'open'
                      ? <span className="chip border-accent-good/40 text-accent-good">Open</span>
                      : <span className="chip border-fg-muted/40 text-fg-muted">Closed</span>}
                    {new Date(s.opened_at).toLocaleString('en-IN')}
                  </div>
                  <div className="text-xs text-fg-muted">
                    Opened by <span className="text-fg font-medium">
                      {s.opened_by_name ?? 'Unknown'}
                      {s.opened_by_email ? ` · ${s.opened_by_email}` : ''}
                    </span>
                  </div>
                  {s.closed_at && (
                    <div className="text-xs text-fg-muted">
                      Closed {new Date(s.closed_at).toLocaleString('en-IN')}
                    </div>
                  )}
                </div>
                {s.status === 'open' && (s.opened_by === me?.user_id || me?.protected_access) && (
                  <button className="btn btn-primary" onClick={() => setClosing(s)}>
                    <Lock size={14}/> Close shift
                  </button>
                )}
                {s.status === 'open' && s.opened_by !== me?.user_id && !me?.protected_access && (
                  <div className="max-w-xs text-right text-xs text-fg-muted">
                    Only the opener shown here, or a protected owner, can count and close this shift.
                  </div>
                )}
              </div>

              <div className="grid grid-cols-3 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                <Stat label="POS collections" value={inr(s.pos_sales_minor ?? 0)}/>
                {legacyRevenueLabel && (
                  <Stat label={legacyRevenueLabel} value={inr(s.membership_sales_minor ?? 0)}/>
                )}
                <Stat label="Gross collections" value={inr(s.gross_collections_minor ?? 0)}/>
              </div>
              <p className="mt-1 text-[10px] text-fg-muted">
                Payment receipts before refunds; opening float is excluded.
              </p>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                <Stat label="POS refunds" value={inr(s.settled_pos_refunds_minor ?? 0)}/>
                {legacyRefundLabel && (
                  <Stat label={legacyRefundLabel} value={inr(s.settled_membership_refunds_minor ?? 0)}/>
                )}
                <Stat label="Total refunds" value={inr(s.total_refunds_minor ?? 0)}/>
                <Stat label="Net collections" value={inr(s.net_collections_minor ?? 0)} tone="good"/>
              </div>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                <Stat label="Opening float" value={inr(s.opening_float_minor)}/>
                <Stat label="Expected cash" value={s.expected_minor != null ? inr(s.expected_minor) : '—'}/>
                <Stat label="Counted cash"  value={s.counted_minor != null ? inr(s.counted_minor) : '—'}/>
                <Stat label="Variance"
                  value={s.variance_minor != null ? inr(s.variance_minor) : '—'}
                  tone={s.variance_minor == null ? 'default' :
                        s.variance_minor === 0 ? 'good' :
                        Math.abs(s.variance_minor) < 5000 ? 'gold' : 'bad'}/>
              </div>
            </div>
            );
          })}
        </div>
      )}

      {opening && <OpenShiftForm
        onClose={() => setOpening(false)}
        onSuccess={() => { setOpening(false); load(); }}
        onError={load}/>}
      {closing && <CloseShiftForm shift={closing}
        onClose={() => setClosing(null)}
        onSuccess={() => { setClosing(null); load(); }}/>}
    </div>
  );
}

function OpenShiftForm({ onClose, onSuccess, onError }: { onClose: () => void; onSuccess: () => void; onError: () => void }) {
  const [float, setFloat] = useState('500');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const openingFloatMinor = parseRupeesToMinor(float);
      if (openingFloatMinor === null) {
        throw new Error('Opening float must be a non-negative amount with at most two decimals.');
      }
      await shifts.open(openingFloatMinor);
      onSuccess();
    } catch (e) {
      setErr((e as Error).message);
      // Most likely cause: someone else already opened a shift here since
      // this screen last synced. Refresh the list behind this modal so the
      // real shift is already showing by the time this error is dismissed.
      onError();
    }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title="Open shift">
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-fg-muted">
          The opening float is the cash already in the drawer before you start.
          Typical Indian café float: ₹500 — ₹1,000.
        </p>
        <Field label="Opening float (₹)">
          <input type="number" required min={0} step="0.01" autoFocus
            className="input font-mono text-right text-xl" value={float}
            onChange={(e) => setFloat(e.target.value)}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <ShieldCheck size={14}/>}
            Open shift
          </button>
        </div>
      </form>
    </Modal>
  );
}

// Indian note/coin denominations, largest first — used by the optional
// denomination-breakdown entry mode below.
const CASH_DENOMINATIONS = [500, 200, 100, 50, 20, 10, 5, 2, 1];

function CloseShiftForm({
  shift, onClose, onSuccess,
}: { shift: ShiftDTO; onClose: () => void; onSuccess: () => void }) {
  const [mode, setMode] = useState<'total' | 'denomination'>('total');
  const [counted, setCounted] = useState('');
  const [denomCounts, setDenomCounts] = useState<Record<number, string>>({
    500: '', 200: '', 100: '', 50: '', 20: '', 10: '', 5: '', 2: '', 1: '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [result, setResult] = useState<{ variance_minor: number } | null>(null);

  // Sum of (denomination × count), in paise — exact integer arithmetic, no
  // float round-trip through a rupee string.
  const denomTotalMinor = CASH_DENOMINATIONS.reduce(
    (sum, d) => sum + d * 100 * (parseInt(denomCounts[d] || '0', 10) || 0),
    0,
  );

  // Keep the free-text total field mirroring the breakdown while denomination
  // mode is active, so what's on screen always matches what will be submitted.
  useEffect(() => {
    if (mode === 'denomination') setCounted((denomTotalMinor / 100).toFixed(2));
  }, [mode, denomTotalMinor]);

  function setDenomCount(denom: number, value: string) {
    setDenomCounts((prev) => ({ ...prev, [denom]: value }));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      // Denomination mode submits the exact integer sum computed above;
      // free-text mode submits exactly as before (unchanged wire format).
      const countedMinor = mode === 'denomination'
        ? denomTotalMinor
        : parseRupeesToMinor(counted);
      if (countedMinor === null) {
        throw new Error('Counted cash must be a non-negative amount with at most two decimals.');
      }
      const r = await shifts.close(shift.id, countedMinor);
      setResult(r);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  if (result) {
    const v = result.variance_minor;
    return (
      <Modal open onClose={() => { onClose(); onSuccess(); }} title="Shift closed">
        <div className="space-y-3">
          <div className={`p-4 rounded-xl ${
            v === 0 ? 'bg-accent-good/15 border border-accent-good/40 text-accent-good' :
            Math.abs(v) < 5000 ? 'bg-accent-gold/15 border border-accent-gold/40 text-accent-gold' :
                                 'bg-accent-bad/15 border border-accent-bad/40 text-accent-bad'
          }`}>
            <div className="text-xs uppercase tracking-wider mb-1">Variance</div>
            <div className="text-3xl font-bold font-mono">{inr(v)}</div>
            <div className="text-xs mt-1">
              {v === 0 ? '✓ Drawer balanced exactly' :
               v > 0 ? 'Surplus — more cash than expected' :
                       'Short — less cash than expected'}
            </div>
          </div>
          <button className="btn btn-primary w-full" onClick={() => { onClose(); onSuccess(); }}>
            Done
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal open onClose={onClose} title="Close shift">
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-fg-muted">
          Count all the cash in the drawer (notes + coins), then enter the total below.
          The system compares it with the expected amount (opening float + cash sales − cash refunds).
        </p>
        <Field label="Opening float">
          <input className="input font-mono text-right" disabled
            value={inr(shift.opening_float_minor)}/>
        </Field>

        <div className="flex gap-1 p-1 rounded-lg bg-bg-raised">
          <button type="button"
            className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
              mode === 'total' ? 'bg-bg-surface shadow text-fg' : 'text-fg-muted'}`}
            onClick={() => setMode('total')}>
            Enter total
          </button>
          <button type="button"
            className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
              mode === 'denomination' ? 'bg-bg-surface shadow text-fg' : 'text-fg-muted'}`}
            onClick={() => setMode('denomination')}>
            Count denominations
          </button>
        </div>

        <Field label="Counted cash (₹) — what's actually in the drawer">
          <input type="number" required min={0} step="0.01" autoFocus={mode === 'total'}
            disabled={mode === 'denomination'}
            className="input font-mono text-right text-xl disabled:opacity-80" value={counted}
            onChange={(e) => setCounted(e.target.value)}/>
        </Field>

        {mode === 'denomination' && (
          <div className="rounded-xl border border-bg-border divide-y divide-bg-border overflow-hidden">
            {CASH_DENOMINATIONS.map((d, i) => {
              const count = parseInt(denomCounts[d] || '0', 10) || 0;
              return (
                <div key={d} className="flex items-center gap-3 px-3 py-2">
                  <span className="w-12 shrink-0 font-mono text-sm text-fg-muted">₹{d}</span>
                  <input type="number" min={0} step="1" inputMode="numeric" autoFocus={i === 0}
                    className="input font-mono text-right flex-1" value={denomCounts[d]}
                    placeholder="0"
                    onChange={(e) => setDenomCount(d, e.target.value)}/>
                  <span className="w-24 shrink-0 text-right font-mono text-sm text-fg-muted">
                    {inr(d * 100 * count)}
                  </span>
                </div>
              );
            })}
            <div className="flex items-center justify-between px-3 py-2 font-bold bg-bg-raised/50">
              <span>Total counted</span>
              <span className="font-mono">{inr(denomTotalMinor)}</span>
            </div>
          </div>
        )}

        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Lock size={14}/>}
            Close shift
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ============================================================================
// bits
// ============================================================================
function TabBtn({ active, onClick, children }: {
  active: boolean; onClick: () => void; children: React.ReactNode;
}) {
  return (
    <button onClick={onClick}
      className={`shrink-0 px-4 py-2 text-sm font-medium border-b-2 -mb-px whitespace-nowrap flex items-center gap-1.5
        ${active ? 'border-accent text-accent' : 'border-transparent text-fg-muted hover:text-fg'}`}>
      {children}
    </button>
  );
}
function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs text-fg-muted">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}
function ErrorRow({ text }: { text: string }) {
  return (
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
      <X size={14}/> {text}
    </div>
  );
}
function Row({ label, value, bold }: { label: string; value: string; bold?: boolean }) {
  return (
    <div className={`flex justify-between gap-4 py-1 ${bold ? 'font-bold border-t border-bg-border pt-2 mt-2' : ''}`}>
      <span className="shrink-0 text-fg-muted">{label}</span>
      <span className="min-w-0 break-words text-right font-mono">{value}</span>
    </div>
  );
}
function Stat({ label, value, tone = 'default' }: {
  label: string; value: string; tone?: 'default' | 'good' | 'gold' | 'bad';
}) {
  const color =
    tone === 'good' ? 'text-accent-good' :
    tone === 'gold' ? 'text-accent-gold' :
    tone === 'bad' ? 'text-accent-bad' : 'text-fg';
  return (
    <div>
      <div className="text-[10px] text-fg-muted uppercase tracking-wider">{label}</div>
      <div className={`font-mono font-bold ${color}`}>{value}</div>
    </div>
  );
}
