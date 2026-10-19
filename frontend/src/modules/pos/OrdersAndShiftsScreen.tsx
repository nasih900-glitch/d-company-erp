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
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Receipt, Loader2, AlertCircle, RefreshCw, Eye, Lock, ShieldCheck,
  ClipboardList, X, Gamepad2, CreditCard, UserRound, ChevronDown,
  Clock3, Monitor, CheckCircle2, Search, Copy, RotateCcw,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { inr } from '@/lib/inr';
import { shopTime } from '@/modules/audit/audit-presentation';
import { parseRupeesToMinor } from '@/lib/money-input';
import { resolveOpenShift, shiftResolutionMessage } from '@/lib/operational-context';
import { profileMembershipMoneyLabel } from '@/lib/product-profile';
import {
  orders, receipts, shifts, gaming, staff,
  type ReceiptInvestigationDTO, type StationDTO, type UserDTO,
  type OrderListItemDTO, type ReceiptGamingSessionHistoryDTO, type ReceiptHistoryDTO,
  type ShiftDTO,
  type ShiftRecoveryCandidateDTO, type ShiftRecoveryCloseDTO,
} from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';
import TenderCorrections from '@/modules/finance/TenderCorrections';
import { Link } from 'react-router-dom';
import { ownerScope, ownerWorkspaceGeneration, readOwnerWorkspace, rememberOwnerWorkspace } from '@/lib/owner-workspace';
import { emptyReceiptFilters, receiptSearchParams, receiptSearchScope, taxPercentage, type ReceiptFilterInputs } from './receipt-search';
import { useNotifications } from '@/components/ui/Notifications';
import { useAuth } from '@/modules/auth/AuthContext';
import { canAccessRefunds } from '@/modules/refunds/refund-policy';
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
  mergeReceiptWindow,
  refreshKnownReceipts,
  collectReceiptRefreshWindow,
  receiptScopeMatches,
  type ReceiptQueryScope,
  type ReceiptRequestScope,
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
import {
  formatShiftBusinessDateTime,
  formatShiftBusinessTime,
  groupShiftsByBusinessDay,
} from './shift-business-days';

type Tab = 'orders' | 'shifts';

export function operationsInitialTab(searchParams: Pick<URLSearchParams, 'get'>): Tab {
  return searchParams.get('tab') === 'shifts' ? 'shifts' : 'orders';
}

export type ShiftCollectionRails = {
  cash: number;
  card: number;
  upi: number;
  other: number;
};

/** Do not turn an older server's missing payment-rail fields into false zeros. */
export function shiftCollectionRails(shift: ShiftDTO): ShiftCollectionRails | null {
  const values = [
    shift.cash_collections_minor,
    shift.card_collections_minor,
    shift.upi_collections_minor,
    shift.other_collections_minor,
  ];
  if (!values.every((value): value is number => typeof value === 'number' && Number.isFinite(value))) {
    return null;
  }
  return { cash: values[0], card: values[1], upi: values[2], other: values[3] };
}

export function receiptGamingChargeRows(
  session: ReceiptGamingSessionHistoryDTO,
): Array<{ label: string; amountMinor: number }> {
  const rows: Array<{ label: string; amountMinor: number }> = [];
  const add = (label: string, amount: number | null | undefined, includeZero = false) => {
    if (typeof amount === 'number' && Number.isFinite(amount) && (includeZero || amount > 0)) {
      rows.push({ label, amountMinor: amount });
    }
  };
  if (session.billing_mode === 'package') {
    add('Booked controller fee', session.booked_controller_surcharge_minor, true);
    add('Paid package extensions', session.extension_package_minor);
    add('Extension controller fee', session.extension_controller_surcharge_minor);
  }
  add('Late player controller fee', session.late_friend_charge_minor, true);
  return rows;
}

function ShiftRailBreakdown({ shift }: { shift: ShiftDTO }) {
  const rails = shiftCollectionRails(shift);
  if (!rails) {
    return (
      <p className="mt-3 border-t border-bg-border/60 pt-3 text-xs text-fg-muted">
        Payment rail breakdown is unavailable from this server. Gross and net totals above remain authoritative.
      </p>
    );
  }
  return (
    <div className="mt-3 grid grid-cols-2 gap-3 border-t border-bg-border/60 pt-3 md:grid-cols-4">
      <Stat label="Cash collected" value={inr(rails.cash)}/>
      <Stat label="UPI collected" value={inr(rails.upi)}/>
      <Stat label="Card collected" value={inr(rails.card)}/>
      <Stat label="Other collected" value={inr(rails.other)}/>
    </div>
  );
}
// Fallback only — real-time push (see subscribeRealtime below) is what
// actually keeps this current; this just covers a missed/dropped push.
const OPERATIONS_POLL_MS = 120_000;

export default function OrdersAndShiftsScreen() {
  const [searchParams, setSearchParams] = useSearchParams();
  const {me}=useAuth();
  const [tab, setTab] = useState<Tab>(() => operationsInitialTab(searchParams));
  const requestedTab = operationsInitialTab(searchParams);

  useEffect(() => setTab(requestedTab), [requestedTab]);

  const selectTab = (next: Tab) => {
    setTab(next);
    setSearchParams(next === 'shifts' ? { tab: 'shifts' } : {}, { replace: true });
  };

  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Operations</h2>
        <p className="text-fg-muted text-sm">
          Live orders, receipt history &amp; shift reconciliation
        </p>
      </header>

      <div className="scroll-strip flex gap-1 mb-6 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'orders'} onClick={() => selectTab('orders')}>
          <Receipt size={14}/> Orders
        </TabBtn>
        <TabBtn active={tab === 'shifts'} onClick={() => selectTab('shifts')}>
          <ClipboardList size={14}/> Shifts
        </TabBtn>
      </div>

      {tab === 'orders' ? <OrdersTab key={me?ownerScope(me):'anonymous'}/> : <ShiftsTab/>}
    </div>
  );
}

// ============================================================================
// Live orders and immutable receipt history
// ============================================================================
interface ReceiptWorkspaceState {
  rows: ReceiptHistoryDTO[]; scope: ReceiptQueryScope;
  paging: ReceiptRequestScope & {nextCursor:string|null;hasMore:boolean};
  input: string; query: string; filters: ReceiptFilterInputs;
  selected: {orderId:string;invoiceNo:string}|null; scroll: number; timezone?: string|null;
}
function OrdersTab() {
  const workspaceGeneration=useRef(ownerWorkspaceGeneration()).current;
  const { me } = useAuth();
  const [params] = useSearchParams();
  const scope = me ? ownerScope(me) : 'anonymous';
  const initial = useRef(readOwnerWorkspace<ReceiptWorkspaceState>(scope,'receipt-history'));
  const cache = initial.current;
  const [receiptFilters,setReceiptFilters] = useState<ReceiptFilterInputs>(cache?.filters ?? emptyReceiptFilters);
  const [stationOptions,setStationOptions] = useState<StationDTO[]>([]);
  const [staffOptions,setStaffOptions] = useState<UserDTO[]>([]);
  useEffect(() => {
    let active=true;
    void gaming.listStations().then(rows=>{if(active)setStationOptions(rows);}).catch(()=>undefined);
    if(me?.audit_access) void staff.listUsers().then(rows=>{if(active)setStaffOptions(rows);}).catch(()=>undefined);
    return()=>{active=false;};
  },[me?.audit_access]);
  const [operationalRows, setOperationalRows] = useState<OrderListItemDTO[]>([]);
  const [receiptRows, setReceiptRows] = useState<ReceiptHistoryDTO[]>(cache?.rows ?? []);
  const [receiptTimezone,setReceiptTimezone]=useState(cache?.timezone ?? null);
  const [operationalLoading, setOperationalLoading] = useState(true);
  const [receiptsLoading, setReceiptsLoading] = useState(true);
  const [operationalError, setOperationalError] = useState<string | null>(null);
  const [receiptsError, setReceiptsError] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const receiptScopeRef = useRef<ReceiptQueryScope>(cache?.scope ?? { epoch: 0, query: '', loaded: false });
  const [receiptPaging, setReceiptPaging] = useState<ReceiptRequestScope & {
    nextCursor: string | null;
    hasMore: boolean;
  }>(cache?.paging ?? { epoch: 0, query: '', nextCursor: null, hasMore: false });
  const [pendingReceiptPage, setPendingReceiptPage] = useState<(ReceiptRequestScope & {
    newItems: ReceiptHistoryDTO[];
  }) | null>(null);
  const [receiptQueryInput, setReceiptQueryInput] = useState(params.get('receipt_q') ?? cache?.input ?? '');
  const [receiptQuery, setReceiptQuery] = useState(cache?.query ?? '');
  const [operationalView, setOperationalView] = useState<OrderListItemDTO | null>(null);
  const [receiptView, setReceiptView] = useState<{ orderId: string; invoiceNo: string } | null>(() => params.get('receipt') ? {orderId:params.get('receipt')!,invoiceNo:'Linked receipt'} : cache?.selected ?? null);
  const [recoveryTarget, setRecoveryTarget] = useState<OrderListItemDTO | null>(null);
  const [recoveryIntent] = useState(() => new StableMutationIntent<DirectOrderRecoveryPayload>({
    prefix: 'pos-direct-recovery:web',
  }));
  const operationalSequence = useRef(0);
  const receiptSequence = useRef(0);
  const receiptRowsRef = useRef<ReceiptHistoryDTO[]>(cache?.rows ?? []);
  const { nextCursor, hasMore } = receiptPaging;

  useEffect(() => { receiptRowsRef.current = receiptRows; }, [receiptRows]);

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

  const loadReceipts = useCallback(async (
    showLoading: boolean,
    requestedMode: 'replace' | 'stage' = 'replace',
  ) => {
    if (!LIVE_MODE) { setReceiptsLoading(false); return; }
    const currentScope = receiptScopeRef.current;
    const requestScope: ReceiptRequestScope = {
      epoch: currentScope.epoch,
      query: currentScope.query,
    };
    // A realtime refresh racing the first load for a new search scope must
    // replace that scope. It cannot stage against rows/cursors from the prior query.
    const mode = requestedMode === 'stage' && currentScope.loaded ? 'stage' : 'replace';
    const sequence = ++receiptSequence.current;
    // A first-page refresh supersedes any in-flight pagination request.
    setLoadingMore(false);
    if (showLoading) setReceiptsLoading(true);
    setReceiptsError(null);
    try {
      const page = await receipts.list({
        limit: 50,
        ...receiptSearchParams(requestScope.query),
      });
      const requestIsCurrent = () => sequence === receiptSequence.current
        && receiptScopeMatches(receiptScopeRef.current, requestScope);
      if (!requestIsCurrent()) return;
      setReceiptTimezone(page.shop_timezone ?? null);
      if (mode === 'stage') {
        const current = receiptRowsRef.current;
        if (!current.length) {
          setReceiptRows(page.items);
          setReceiptPaging({
            ...requestScope,
            nextCursor: page.next_cursor,
            hasMore: page.has_more,
          });
        } else {
          const refreshedWindow = await collectReceiptRefreshWindow(
            page,
            current,
            (cursor) => receipts.list({
              cursor,
              limit: 50,
              ...receiptSearchParams(requestScope.query),
            }),
            requestIsCurrent,
          );
          if (!refreshedWindow || !requestIsCurrent()) return;
          if (!refreshedWindow.complete) {
            setReceiptsError(
              `Receipt history refresh was incomplete. ${refreshedWindow.missingKnownIds.length} loaded receipt${refreshedWindow.missingKnownIds.length === 1 ? '' : 's'} could not be reverified; the existing history was kept unchanged. Retry before relying on refund status.`,
            );
            return;
          }
          setReceiptRows((rows) => refreshKnownReceipts(rows, refreshedWindow.knownUpdates));
          setPendingReceiptPage(refreshedWindow.unseenPrefix.length
            ? { ...requestScope, newItems: refreshedWindow.unseenPrefix }
            : null);
        }
      } else {
        setPendingReceiptPage(null);
        setReceiptRows(page.items);
        setReceiptPaging({
          ...requestScope,
          nextCursor: page.next_cursor,
          hasMore: page.has_more,
        });
      }
      if (receiptScopeMatches(receiptScopeRef.current, requestScope)) {
        receiptScopeRef.current.loaded = true;
      }
    } catch (error) {
      if (
        sequence === receiptSequence.current
        && receiptScopeMatches(receiptScopeRef.current, requestScope)
      ) setReceiptsError((error as Error).message);
    } finally {
      if (
        sequence === receiptSequence.current
        && receiptScopeMatches(receiptScopeRef.current, requestScope)
      ) setReceiptsLoading(false);
    }
  }, []);

  const refreshAll = useCallback(async () => {
    await Promise.all([loadOperational(false), loadReceipts(false, 'stage')]);
  }, [loadOperational, loadReceipts]);

  useEffect(() => {
    void Promise.all([loadOperational(true), loadReceipts(!cache?.scope.loaded, cache?.scope.loaded ? 'stage' : 'replace')]);
  }, [loadOperational, loadReceipts, cache?.scope.loaded]);

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
    const currentScope = receiptScopeRef.current;
    const requestScope: ReceiptRequestScope = {
      epoch: currentScope.epoch,
      query: currentScope.query,
    };
    if (
      !currentScope.loaded
      || !receiptScopeMatches(currentScope, receiptPaging)
    ) return;
    const cursor = nextCursor;
    const sequence = ++receiptSequence.current;
    setLoadingMore(true);
    setReceiptsError(null);
    try {
      const page = await receipts.list({
        cursor,
        limit: 50,
        ...receiptSearchParams(requestScope.query),
      });
      if (
        sequence !== receiptSequence.current
        || !receiptScopeMatches(receiptScopeRef.current, requestScope)
      ) return;
      setReceiptRows((current) => {
        const known = new Set(current.map((receipt) => receipt.order_id));
        return [...current, ...page.items.filter((receipt) => !known.has(receipt.order_id))];
      });
      setReceiptPaging({
        ...requestScope,
        nextCursor: page.next_cursor,
        hasMore: page.has_more,
      });
    } catch (error) {
      if (
        sequence === receiptSequence.current
        && receiptScopeMatches(receiptScopeRef.current, requestScope)
      ) setReceiptsError((error as Error).message);
    } finally {
      if (
        sequence === receiptSequence.current
        && receiptScopeMatches(receiptScopeRef.current, requestScope)
      ) setLoadingMore(false);
    }
  }, [loadingMore, nextCursor, receiptPaging, receiptsLoading]);

  const showPendingReceipts = useCallback(() => {
    if (
      !pendingReceiptPage
      || !receiptScopeMatches(receiptScopeRef.current, pendingReceiptPage)
    ) return;
    setReceiptRows((current) => mergeReceiptWindow(current, pendingReceiptPage.newItems));
    setPendingReceiptPage(null);
  }, [pendingReceiptPage]);

  const changeReceiptQuery = useCallback((query: string, filters: ReceiptFilterInputs = emptyReceiptFilters) => {
    let normalized: string;
    try { normalized = receiptSearchScope(query, filters); } catch (cause) { setReceiptsError((cause as Error).message); return; }
    const currentScope = receiptScopeRef.current;
    if (normalized === currentScope.query) {
      setPendingReceiptPage(null);
      void loadReceipts(true, 'replace');
      return;
    }

    // Invalidate every old-scope first-page and pagination response before
    // React commits the new query state. Clear all scope-owned UI together.
    receiptSequence.current += 1;
    const nextScope: ReceiptQueryScope = {
      epoch: currentScope.epoch + 1,
      query: normalized,
      loaded: false,
    };
    receiptScopeRef.current = nextScope;
    receiptRowsRef.current = [];
    setReceiptRows([]);
    setPendingReceiptPage(null);
    setReceiptPaging({
      epoch: nextScope.epoch,
      query: nextScope.query,
      nextCursor: null,
      hasMore: false,
    });
    setLoadingMore(false);
    setReceiptsError(null);
    setReceiptQuery(normalized);
    void loadReceipts(true, 'replace');
  }, [loadReceipts]);

  const receiptStateRef = useRef<ReceiptWorkspaceState>({rows:receiptRows,scope:receiptScopeRef.current,paging:receiptPaging,input:receiptQueryInput,query:receiptQuery,filters:receiptFilters,timezone:receiptTimezone,selected:receiptView,scroll:cache?.scroll ?? 0});
  receiptStateRef.current={...receiptStateRef.current,rows:receiptRows,scope:receiptScopeRef.current,paging:receiptPaging,input:receiptQueryInput,query:receiptQuery,filters:receiptFilters,timezone:receiptTimezone,selected:receiptView};
  useEffect(()=>{if(workspaceGeneration===ownerWorkspaceGeneration())rememberOwnerWorkspace(scope,'receipt-history',receiptStateRef.current);},[scope,workspaceGeneration,receiptRows,receiptPaging,receiptQueryInput,receiptQuery,receiptFilters,receiptView,receiptTimezone]);
  useEffect(()=>{
    const main=document.querySelector('main');if(main)main.scrollTop=cache?.scroll ?? 0;
    const save=()=>{receiptStateRef.current.scroll=main?.scrollTop ?? 0;if(workspaceGeneration===ownerWorkspaceGeneration())rememberOwnerWorkspace(scope,'receipt-history',receiptStateRef.current);};
    main?.addEventListener('scroll',save,{passive:true});return()=>{save();main?.removeEventListener('scroll',save);};
  },[scope,workspaceGeneration,cache?.scroll]);
  useEffect(()=>{if(params.get('receipt_q'))changeReceiptQuery(params.get('receipt_q')!);},[params,changeReceiptQuery]);

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
          onClick={() => { void Promise.all([loadOperational(true), loadReceipts(true, 'stage')]); }}
          disabled={refreshing}
          aria-label="Refresh orders and receipt history"
        >
          <RefreshCw size={14} className={refreshing ? 'animate-spin' : ''}/>
          Refresh
        </button>
      </div>

      {receiptView&&!operationalLoading&&!operationalRows.length&&!operationalError ? <p className="rounded-xl border border-bg-border px-4 py-3 text-sm text-fg-muted">No open or held bills · finalized receipt investigation below.</p> : (      <section className="card !p-0 overflow-hidden">
        <div className="flex items-center justify-between gap-3 border-b border-bg-border px-4 py-3">
          <div>
            <h3 className="font-semibold">Open &amp; held orders</h3>
            <p className="text-xs text-fg-muted">Operational bills that have not been finalized.</p>
          </div>
          <span className="chip border-accent-gold/30 text-fg">{operationalRows.length} active</span>
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
      </section>)}


      {receiptView && <ReceiptViewModal
        key={receiptView.orderId}
        orderId={receiptView.orderId}
        invoiceNo={receiptView.invoiceNo}
        onClose={() => setReceiptView(null)}
      />}

      <section className="card !p-0 overflow-hidden">
        <div className="flex items-center justify-between gap-3 border-b border-bg-border px-4 py-3">
          <div>
            <h3 className="font-semibold">Receipt history</h3>
            <p className="text-xs text-fg-muted">Finalized receipts from the shared server. Invoice issued shop dates{receiptTimezone?` · ${receiptTimezone}`:' · timezone supplied by the server'}.</p>
          </div>
          <div className="text-right">
            <div className="text-xs text-fg-muted">Server history</div>
            <div className="font-mono text-sm font-semibold">{receiptRows.length} loaded</div>
          </div>
        </div>
        <form
          className="flex flex-col gap-2 border-b border-bg-border px-4 py-3 sm:flex-row"
          onSubmit={(event) => {
            event.preventDefault();
            changeReceiptQuery(receiptQueryInput, receiptFilters);
          }}
        >
          <label className="relative min-w-0 flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-fg-muted" size={15}/>
            <span className="sr-only">Find a finalized receipt</span>
            <input
              className="input w-full pl-9"
              value={receiptQueryInput}
              maxLength={200}
              onChange={(event) => setReceiptQueryInput(event.target.value)}
              placeholder="Invoice number, numeric suffix, order ID or Gaming session ID"
            />
          </label>
          <button type="submit" className="btn btn-primary" disabled={receiptsLoading}>
            <Search size={14}/> Find receipt
          </button>
          {receiptQuery && (
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => { setReceiptQueryInput(''); setReceiptFilters(emptyReceiptFilters); changeReceiptQuery(''); }}
            >
              <RotateCcw size={14}/> Clear
            </button>
          )}
        </form>
        <details className="border-b border-bg-border px-4 py-3"><summary className="cursor-pointer text-sm font-medium">Narrow server receipt history</summary>
          <div className="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            <Field label="Invoice issued from (shop date)"><input type="date" className="input w-full" value={receiptFilters.date_from} onChange={event=>setReceiptFilters({...receiptFilters,date_from:event.target.value})}/></Field>
            <Field label="Invoice issued through (shop date)"><input type="date" className="input w-full" value={receiptFilters.date_to} onChange={event=>setReceiptFilters({...receiptFilters,date_to:event.target.value})}/></Field>
            <Field label="Exact invoice amount (₹)"><input className="input w-full" inputMode="decimal" value={receiptFilters.amount} onChange={event=>setReceiptFilters({...receiptFilters,amount:event.target.value})}/></Field>
            <Field label="Effective payment method"><select className="input w-full" value={receiptFilters.method} onChange={event=>setReceiptFilters({...receiptFilters,method:event.target.value})}><option value="">All methods</option>{['cash','upi','card','qr','wallet'].map(method=><option key={method} value={method}>{method.toUpperCase()}</option>)}</select></Field>
            <Field label="Station"><select className="input w-full" value={receiptFilters.station_id} onChange={event=>setReceiptFilters({...receiptFilters,station_id:event.target.value})}><option value="">All stations</option>{stationOptions.map(station=><option key={station.id} value={station.id}>{station.name}</option>)}</select></Field>
            <Field label="Payment recorded by"><select className="input w-full" value={receiptFilters.staff_user_id} onChange={event=>setReceiptFilters({...receiptFilters,staff_user_id:event.target.value})}><option value="">All recorded staff</option>{staffOptions.map(user=><option key={user.id} value={user.id}>{user.name} (current profile)</option>)}</select></Field>
          </div><p className="mt-2 text-xs text-fg-muted">These filters search retained server history, not only the loaded page. Unknown legacy staff remain unrecorded.</p><button className="btn btn-ghost mt-3" onClick={()=>changeReceiptQuery(receiptQueryInput,receiptFilters)} disabled={receiptsLoading}>Apply history filters</button>
        </details>
        {pendingReceiptPage && (
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-accent/30 bg-accent/10 px-4 py-3 text-sm" role="status">
            <span>New receipts are available. Your loaded history stays in place until you choose to show them.</span>
            <button type="button" className="btn btn-ghost !min-h-[40px] !py-1.5" onClick={showPendingReceipts}>
              Show new receipts
            </button>
          </div>
        )}
        {receiptsError && (
          <InlineLoadError
            message={receiptsError}
            onRetry={() => { void loadReceipts(true, 'stage'); }}
          />
        )}
        {receiptsLoading ? <div className="p-4"><SkeletonCard/></div> : !receiptRows.length && !receiptsError ? (
          <div className="px-4 py-10 text-center">
            <Receipt className="mx-auto mb-2 text-fg-muted" size={26}/>
            <p className="font-medium">{receiptQuery ? 'No matching finalized receipt' : 'No finalized receipts yet'}</p>
            <p className="mt-1 text-xs text-fg-muted">
              {receiptQuery
                ? 'Check the complete invoice number, numeric suffix, order ID or Gaming session ID.'
                : 'A receipt appears here after a successful Cash, UPI or other payment.'}
            </p>
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
                  <span className={`chip text-[10px] ${order.status === 'held' ? 'border-accent-gold/40 text-fg' : 'border-fg-muted/40 text-fg-muted'}`}>
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
  const [investigation,setInvestigation] = useState<ReceiptInvestigationDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestSequence = useRef(0);
  const navigate = useNavigate();
  const { me } = useAuth();
  const notifications = useNotifications();

  const copyReference = useCallback(async (label: string, value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      notifications.success(`${label} copied.`);
    } catch {
      notifications.error(`Could not copy ${label.toLowerCase()}. Select the value and copy it manually.`);
    }
  }, [notifications]);

  const load = useCallback(async () => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(null);
    try {
      const result = await receipts.investigate(orderId);
      if (sequence === requestSequence.current) {setReceipt(result.receipt);setInvestigation(result);}
    } catch (cause) {
      if (sequence === requestSequence.current) setError((cause as Error).message);
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, [orderId]);

  useEffect(() => { void load(); }, [load]);
  useRealtimeRefresh({ resources: ['receipts'], refresh: load, enabled: LIVE_MODE });

  return (
    <section id="receipt-investigation" className="card space-y-4" aria-label="Receipt investigation"><header className="flex items-center justify-between gap-3"><h3 className="text-lg font-semibold">Receipt {receipt?.invoice_no ?? invoiceNo}</h3><button className="btn btn-ghost" onClick={onClose}><X size={14}/> Close receipt details</button></header>
      {loading && !receipt ? <SkeletonCard/> : error && !receipt ? (
        <InlineLoadError message={error} onRetry={() => { void load(); }}/>
      ) : receipt ? (
        <>
          {error && <InlineLoadError message={error} onRetry={() => { void load(); }}/>}
          <ReceiptDetail
            receipt={receipt}
            refreshing={loading}
            onCopy={copyReference}
            onRefund={canAccessRefunds(me)
              ? () => navigate(`/refunds?order_id=${encodeURIComponent(receipt.order_id)}`)
              : undefined}
          />
          {investigation && <RetainedInvestigation details={investigation}/>}
          {me?.audit_access && <Link className="btn btn-ghost" to={`/audit?reference=${encodeURIComponent(receipt.invoice_no)}`}>Open linked Audit activity</Link>}
          {me?.audit_access && me.effective_permissions?.includes('finance.tender.correct') && receipt.payments.filter(payment=>payment.method==='cash'&&!payment.tender_correction_id).map(payment=><details key={payment.id} className="rounded-xl border border-bg-border p-4"><summary className="cursor-pointer text-sm font-semibold">Correct this Cash payment to UPI · {inr(payment.amount_minor)}</summary><div className="mt-3"><TenderCorrections paymentId={payment.id} onCommitted={()=>void load()}/></div></details>)}
        </>
      ) : null}
    </section>
  );
}

function ReceiptDetail({ receipt, refreshing, onCopy, onRefund }: {
  receipt: ReceiptHistoryDTO;
  refreshing: boolean;
  onCopy: (label: string, value: string) => void;
  onRefund?: () => void;
}) {
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
        <div className="mt-4 flex flex-wrap gap-2 border-t border-bg-border pt-3">
          <button type="button" className="btn btn-ghost" onClick={() => onCopy('Invoice number', receipt.invoice_no)}>
            <Copy size={14}/> Copy invoice
          </button>
          <button type="button" className="btn btn-ghost" onClick={() => onCopy('Order ID', receipt.order_id)}>
            <Copy size={14}/> Copy order ID
          </button>
          {receipt.gaming_sessions.map((session) => (
            <button key={session.id} type="button" className="btn btn-ghost" onClick={() => onCopy('Gaming session ID', session.id)}>
              <Copy size={14}/> Copy session ID
            </button>
          ))}
          {onRefund && receipt.refundable_minor > 0 && (
            <button type="button" className="btn btn-primary" onClick={onRefund}>
              <RotateCcw size={14}/> Refund this receipt
            </button>
          )}
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
                      {line.hsn_or_sac ? `HSN/SAC ${line.hsn_or_sac} · ` : ''}Configured rate {taxPercentage(line.tax_rate)} · Tax charged {inr(line.cgst_minor + line.sgst_minor + line.igst_minor + line.cess_minor)}
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
              {payment.tender_correction_id&&<><Row label="Original recorded method" value={paymentMethodLabel(payment.original_method??'cash')}/><Row label="Effective method after correction" value={paymentMethodLabel(payment.method)}/><Row label="Correction recorded" value={formatOptionalDateTime(payment.tender_correction_recorded_at??null)}/><p className="text-xs text-fg-muted">Owner-attested classification correction. Original payment and bill total retained.</p>{payment.original_tendered_minor!=null&&<Row label="Original cash tendered (historical)" value={inr(payment.original_tendered_minor)}/>} {payment.original_change_minor!=null&&<Row label="Original change (historical)" value={inr(payment.original_change_minor)}/>}</>}
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
        {receipt.pending_refund_minor > 0 && <Row label="Reserved by pending refund" value={`−${inr(receipt.pending_refund_minor)}`}/>}
        <Row label="Available to refund" value={inr(receipt.refundable_minor)} bold/>
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
              {session.billing_mode === 'hourly' ? (
                <Row label="Locked hourly rate" value={`${inr(session.rate_per_hour_minor)}/hr`}/>
              ) : session.billing_mode === 'package' ? (
                <Row
                  label="Package"
                  value={session.effective_package_price_minor_snapshot == null
                    ? 'Package snapshot unavailable (legacy receipt)'
                    : `${session.effective_package_variant_snapshot
                      ? orderTypeLabel(session.effective_package_variant_snapshot)
                      : 'Package'} · ${sessionDurationLabel(session.effective_package_duration_minutes_snapshot ?? null)} · ${inr(session.effective_package_price_minor_snapshot)}`}
                />
              ) : (
                <Row label="Billing basis" value="Unavailable for this legacy receipt"/>
              )}
              {receiptGamingChargeRows(session).map((row) => (
                <Row key={row.label} label={row.label} value={inr(row.amountMinor)}/>
              ))}
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
    <span className={`chip text-[10px] ${status === 'paid' ? 'border-accent-good/40 text-accent-good' : 'border-accent-gold/40 text-fg'}`}>
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

export function formatDateTime(value: string): string {
  return formatShiftBusinessDateTime(value);
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
type ShiftAction = 'load' | 'open' | 'close' | 'recovery-close';

const LEGACY_SHIFT_OPERATOR_ROLES = new Set([
  'super_owner', 'co_owner', 'owner', 'manager', 'cashier',
]);

interface ShiftPermissionIdentity {
  roles?: string[];
  protected_access?: boolean | null;
  audit_access?: boolean | null;
  effective_permissions?: string[];
}

export interface ShiftRecoveryTarget {
  id: string;
  opened_at: string;
  opened_by: string;
  opened_by_name: string | null;
  opened_by_email?: string | null;
}

interface RecoveringShift {
  shift: ShiftRecoveryTarget;
  workspaceName: string;
}

/** HTTP responses are definitive; transport failures can hide a committed write. */
export function isUnknownShiftMutationOutcome(error: unknown): boolean {
  const status = (error as { status?: unknown } | null)?.status;
  // A gateway can time out or fail after the upstream transaction committed.
  // Client/business-rule 4xx responses are definitive; 408 and 5xx are not.
  if (typeof status === 'number') return status === 408 || status >= 500;
  const message = error instanceof Error ? error.message.toLowerCase() : '';
  // Preserve compatibility with test doubles and older API wrappers that did
  // not attach an HTTP status but did retain a definitive business-rule body.
  if (/unfinished order|running gaming session|stopped session|unbilled session|kitchen cancellation|already closed|another staff member|already open|permission|forbidden/.test(message)) {
    return false;
  }
  return true;
}

export function isShiftRecoveryAuthorizationRejection(error: unknown): boolean {
  const status = (error as { status?: unknown } | null)?.status;
  return status === 401 || status === 403;
}

export function recoveryFailureRequiresExactReplay(error: unknown): boolean {
  return isUnknownShiftMutationOutcome(error);
}

/** Prefer the server's exact post-override permission; retain old-server compatibility. */
export function canUseShiftPermission(
  identity: ShiftPermissionIdentity | null | undefined,
  permission: 'pos.shift.open' | 'pos.shift.close',
): boolean {
  if (!identity) return false;
  if (identity.effective_permissions !== undefined) {
    return identity.effective_permissions.includes(permission);
  }
  if (identity.protected_access) return true;
  return identity.roles?.some((role) => LEGACY_SHIFT_OPERATOR_ROLES.has(role)) ?? false;
}

export function shiftOpenerLabel(shift: ShiftRecoveryTarget): string {
  return shift.opened_by_name?.trim()
    || shift.opened_by_email?.trim()
    || `Employee ${shift.opened_by.slice(0, 8)}`;
}

export function shiftCloserLabel(shift: ShiftDTO): string {
  return shift.closed_by_name?.trim()
    || shift.closed_by_email?.trim()
    || (shift.closed_by ? `Employee ${shift.closed_by.slice(0, 8)}` : 'Not recorded');
}

export function shiftWorkspaceLabel(
  shift: ShiftDTO,
  workspaces: ReadonlyArray<{ id: string; name: string }>,
): string {
  const known = shift.terminal_id
    ? workspaces.find((workspace) => workspace.id === shift.terminal_id)
    : null;
  if (known?.name.trim()) return known.name.trim();
  if (shift.terminal_id) return `Workspace ${shift.terminal_id.slice(0, 8)}`;
  return 'Legacy workspace';
}

/** New-protocol Android shifts retain a causal Room lifecycle for safe close. */
export function isAndroidOriginShift(shift: ShiftDTO): boolean {
  return shift.status === 'open'
    && shift.opening_protocol_revision === 1
    && shift.opening_client_platform === 'android';
}

export function canReviewAndroidShiftRecoveryCandidates(
  identity: ShiftPermissionIdentity | null | undefined,
): boolean {
  return Boolean(
    identity?.audit_access
    && canUseShiftPermission(identity, 'pos.shift.close'),
  );
}

/** This last-resort path is narrower than general protected operational access. */
export function canRecoverAndroidShift(
  identity: ShiftPermissionIdentity | null | undefined,
  shift: ShiftDTO,
): boolean {
  return canReviewAndroidShiftRecoveryCandidates(identity) && isAndroidOriginShift(shift);
}

export async function refreshShiftRecoverySurfaces(
  refreshHistory: () => Promise<ShiftDTO[] | null>,
  refreshCandidates: () => Promise<ShiftRecoveryCandidateDTO[] | null>,
  includeCandidates: boolean,
): Promise<{
  history: ShiftDTO[] | null;
  candidates: ShiftRecoveryCandidateDTO[] | null;
}> {
  if (!includeCandidates) {
    return { history: await refreshHistory(), candidates: [] };
  }
  const [history, candidates] = await Promise.all([
    refreshHistory(),
    refreshCandidates(),
  ]);
  return { history, candidates };
}

/** Turn server/business-rule failures into a next action a new employee can follow. */
export function shiftActionErrorMessage(error: unknown, action: ShiftAction): string {
  const raw = error instanceof Error ? error.message.trim() : '';
  const message = raw || 'The server did not complete the request.';
  const normalized = message.toLowerCase();
  const status = (error as { status?: number } | null)?.status;

  if (isUnknownShiftMutationOutcome(error)) {
    return action === 'load'
      ? 'Shift information could not be refreshed. Check the connection; the last confirmed information remains on screen.'
      : action === 'open'
        ? 'Shift opening could not be confirmed because the response was interrupted. Refresh Shifts first. If an open shift appears, use it; otherwise retry the same opening amount once.'
        : action === 'recovery-close'
          ? 'Protected recovery could not be confirmed because the response was interrupted. Keep the originating tablet isolated. Do not change the cash count or reason; refresh Shifts, then retry this exact recovery once only if the shift still shows open.'
          : 'Shift closure could not be confirmed because the response was interrupted. Do not enter a different cash count or open another shift. Reconnect, refresh Shifts, then retry the same count once only if this shift still shows open.';
  }
  if (status === 403) {
    if (action === 'recovery-close') {
      return 'Only the protected audit owner can use Android shift recovery. The shift remains open; close it from the originating tablet or ask the protected audit owner.';
    }
    return action === 'close'
      ? 'Your account does not currently have Close shift access. The shift is still open. Ask the owner to restore the Shift permission, then retry.'
      : 'Your account does not have permission for this shift action. Ask the owner to restore the Shift permission.';
  }
  // Current servers include opener, time, workspace and a complete next action
  // in the safe user-facing message. Preserve that authoritative wording
  // instead of appending a second, potentially conflicting instruction.
  if (normalized.includes('shift opened by') && normalized.includes(' in ')) return message;
  if (/unfinished order/.test(normalized)) {
    return `${message}. Open Operations, finish or void every open bill, then close the shift again.`;
  }
  if (/running gaming session/.test(normalized)) {
    return `${message}. Open Gaming, stop each active session, then finish its bill before closing the shift.`;
  }
  if (/stopped session/.test(normalized) || /unbilled session/.test(normalized)) {
    return `${message}. Open Gaming, send every payment-due session to POS, complete or void its bill, then retry.`;
  }
  if (/kitchen cancellation/.test(normalized)) {
    return `${message}. Open Kitchen, acknowledge the pending cancellation, then retry.`;
  }
  if (/refund/.test(normalized) && /unresolved|accepted|pending|progress|recovery/.test(normalized)) {
    return `${message}. Open the refund queue, finish or resolve the pending refund, then retry.`;
  }
  if (/already closed/.test(normalized)) {
    return 'This shift was already closed from another screen or device. Refresh Shifts to see the recorded closer and totals.';
  }
  if (/another staff member|already open/.test(normalized)) {
    return 'A shift is already open in this workspace. Use that shift instead; its opener and start time are shown on the Shifts screen.';
  }

  if (action === 'load') return `${message} Retry the refresh; no shift information was changed.`;
  if (action === 'open') return `${message} The shift was not opened. Review the message and retry once.`;
  if (action === 'recovery-close') {
    return `${message} The shift remains open. Keep the originating tablet isolated, resolve the reported blocker, refresh Shifts, then retry this same recovery.`;
  }
  return `${message} The shift remains open. Resolve the reported item, refresh Shifts, and retry once.`;
}

export function OpenShiftStatusPanel({
  shift,
  workspaceName,
  currentUserId,
  canClose,
  canRecover = false,
  closing = false,
  onClose,
  onRecover,
}: {
  shift: ShiftDTO;
  workspaceName: string;
  currentUserId: string | undefined;
  canClose: boolean;
  canRecover?: boolean;
  closing?: boolean;
  onClose: () => void;
  onRecover?: () => void;
}) {
  const opener = shiftOpenerLabel(shift);
  const openedByCurrentUser = shift.opened_by === currentUserId;
  const requiresOriginTablet = isAndroidOriginShift(shift);

  return (
    <section
      className="mb-4 rounded-2xl border border-accent-good/35 bg-accent-good/5 p-4"
      role="status"
      aria-live="polite"
    >
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-accent-good">
            <CheckCircle2 size={18} aria-hidden="true"/>
            <h3 className="font-semibold">Shift is open and ready</h3>
          </div>
          <p className="mt-2 text-sm text-fg">
            Opened by <strong>{opener}</strong>{openedByCurrentUser ? ' (you)' : ''}.
          </p>
          <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs text-fg-muted">
            <span className="inline-flex items-center gap-1.5">
              <Clock3 size={13} aria-hidden="true"/> {formatShiftBusinessDateTime(shift.opened_at)}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <Monitor size={13} aria-hidden="true"/> {workspaceName}
            </span>
          </div>
          <p className="mt-2 text-xs text-fg-muted">
            {requiresOriginTablet
              ? 'This shift was opened by the Android tablet. Close it from that originating app so any saved offline work drains before the drawer closes.'
              : 'Continue using this shared shift. When work is finished, count the drawer and close it here. Any staff member with Close shift access can close it; the closer is recorded under their own account.'}
          </p>
          {!canClose && !requiresOriginTablet && (
            <p className="mt-2 text-xs text-fg" role="status">
              Your account can view this shift but cannot close it. Ask a staff member with Close shift access.
            </p>
          )}
          {requiresOriginTablet && !canRecover && (
            <p className="mt-2 text-xs text-fg" role="status">
              If the tablet is stuck or unavailable, ask the protected audit owner to use the separate audited recovery flow.
            </p>
          )}
        </div>
        {!requiresOriginTablet && canClose && (
          <button
            type="button"
            className="btn btn-primary shrink-0"
            onClick={onClose}
            disabled={closing}
            aria-label={`Close shift opened by ${opener}`}
          >
            {closing ? <Loader2 className="animate-spin" size={14}/> : <Lock size={14}/>}
            {closing ? 'Opening cash count…' : 'Count & close shift'}
          </button>
        )}
        {requiresOriginTablet && canRecover && onRecover && (
          <button
            type="button"
            className="btn btn-ghost shrink-0 border border-accent-bad/45 text-accent-bad"
            onClick={onRecover}
            disabled={closing}
            aria-label={`Recover Android shift opened by ${opener}`}
          >
            <ShieldCheck size={14}/>
            Recover Android shift
          </button>
        )}
      </div>
    </section>
  );
}

export function AndroidShiftRecoveryCandidatesPanel({
  authorized,
  currentTerminalId,
  candidates,
  loading = false,
  error = null,
  recoveringShiftId = null,
  onRecover,
}: {
  authorized: boolean;
  currentTerminalId: string | null;
  candidates: readonly ShiftRecoveryCandidateDTO[];
  loading?: boolean;
  error?: string | null;
  recoveringShiftId?: string | null;
  onRecover: (candidate: ShiftRecoveryCandidateDTO) => void;
}) {
  if (!authorized) return null;
  const otherTerminalCandidates = candidates.filter(
    (candidate) => candidate.terminal_id !== currentTerminalId,
  );
  if (!loading && !error && otherTerminalCandidates.length === 0) return null;

  return (
    <section
      className="mb-4 rounded-2xl border border-accent-bad/45 bg-accent-bad/5 p-4"
      aria-label="Other-terminal Android shift recovery"
    >
      <div className="flex items-start gap-2 text-accent-bad">
        <ShieldCheck size={18} className="mt-0.5 shrink-0" aria-hidden="true"/>
        <div>
          <h3 className="font-semibold">Other-terminal Android shifts need review</h3>
          <p className="mt-1 text-xs text-fg-muted">
            Protected recovery only. These shifts belong to another register and do not become
            this register&apos;s active shift or part of its business-day history.
          </p>
        </div>
      </div>

      {error && (
        <div className="mt-3 rounded-xl border border-accent-bad/45 bg-accent-bad/10 p-3 text-sm text-accent-bad" role="alert">
          {error}
        </div>
      )}
      {loading && (
        <p className="mt-3 inline-flex items-center gap-2 text-sm text-fg-muted" role="status">
          <Loader2 size={14} className="animate-spin" aria-hidden="true"/>
          Checking branch recovery candidates…
        </p>
      )}

      {otherTerminalCandidates.length > 0 && (
        <div className="mt-3 space-y-3">
          {otherTerminalCandidates.map((candidate) => {
            const opener = shiftOpenerLabel(candidate);
            return (
              <article
                key={candidate.id}
                className="rounded-xl border border-accent-bad/30 bg-bg-raised/45 p-3"
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="chip border-accent-bad/45 text-accent-bad">Other terminal</span>
                      {!candidate.terminal_is_active && (
                        <span className="chip border-accent-gold/45 text-fg">Workspace inactive</span>
                      )}
                    </div>
                    <p className="mt-2 font-semibold text-fg">{candidate.terminal_name}</p>
                    <p className="mt-1 text-sm text-fg-muted">
                      Opened by <strong className="text-fg">{opener}</strong> at{' '}
                      <strong className="text-fg">{formatShiftBusinessDateTime(candidate.opened_at)}</strong>
                    </p>
                    <p className="mt-1 break-all text-xs text-fg-muted">
                      Workspace {candidate.terminal_name} · Source Android app
                      {candidate.opening_client_installation_recorded
                        ? ' · Installation identity recorded'
                        : ' · Installation identity not recorded'}
                      {candidate.terminal_device_id
                        ? ` · Registered device ${candidate.terminal_device_id}`
                        : ' · Registered device identity not recorded'}
                    </p>
                    <p className="mt-2 text-xs font-medium text-accent-bad">
                      This is another terminal&apos;s shift. Isolate its originating tablet before recovery.
                    </p>
                  </div>
                  <button
                    type="button"
                    className="btn btn-ghost shrink-0 border border-accent-bad/45 text-accent-bad"
                    onClick={() => onRecover(candidate)}
                    disabled={Boolean(error) || recoveringShiftId !== null}
                    aria-label={`Recover other-terminal Android shift on ${candidate.terminal_name} opened by ${opener}`}
                  >
                    {recoveringShiftId === candidate.id
                      ? <Loader2 className="animate-spin" size={14}/>
                      : <ShieldCheck size={14}/>}
                    Recover Android shift
                  </button>
                </div>
                <div className="mt-3 grid grid-cols-2 gap-3 border-t border-bg-border/60 pt-3">
                  <Stat label="Opening float" value={inr(candidate.opening_float_minor)}/>
                  <Stat label="Expected cash" value={inr(candidate.expected_minor)}/>
                </div>
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}

export function ExistingShiftFeedback({
  shift,
  workspaceName,
  onContinue,
}: {
  shift: ShiftDTO;
  workspaceName: string;
  onContinue: () => void;
}) {
  return (
    <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm" role="status" aria-live="polite">
      <p className="font-semibold text-fg">A shift is already open</p>
      <p className="mt-1 text-fg">
        <strong>{shiftOpenerLabel(shift)}</strong> opened it {formatShiftBusinessDateTime(shift.opened_at)} on {workspaceName}.
      </p>
      <p className="mt-1 text-fg-muted">
        Do not open another shift. Continue with this one, or close it from Shifts after all sessions and bills are finished.
      </p>
      <button type="button" className="btn btn-ghost mt-3" onClick={onContinue}>
        Use existing shift
      </button>
    </div>
  );
}

function ShiftsTab() {
  const { me, terminalId, terminalReady, terminalOptions } = useAuth();
  const notifications = useNotifications();
  const [rows, setRows] = useState<ShiftDTO[]>([]);
  const [recoveryCandidates, setRecoveryCandidates] = useState<ShiftRecoveryCandidateDTO[]>([]);
  const [recoveryCandidatesLoading, setRecoveryCandidatesLoading] = useState(false);
  const [recoveryCandidatesError, setRecoveryCandidatesError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [closing, setClosing] = useState<ShiftDTO | null>(null);
  const [recovering, setRecovering] = useState<RecoveringShift | null>(null);
  const [opening, setOpening] = useState(false);
  const requestSequence = useRef(0);
  const recoveryCandidateSequence = useRef(0);
  const initialLoadComplete = useRef(false);
  const recoveryCandidateInitialLoadComplete = useRef(false);
  const canOpenShift = canUseShiftPermission(me, 'pos.shift.open');
  const canCloseShift = canUseShiftPermission(me, 'pos.shift.close');
  const canReviewRecoveryCandidates = canReviewAndroidShiftRecoveryCandidates(me);

  const load = useCallback(async (showRefreshProgress = false): Promise<ShiftDTO[] | null> => {
    const sequence = ++requestSequence.current;
    if (!LIVE_MODE) { setLoading(false); return []; }
    if (!terminalReady || !terminalId) {
      setRows([]);
      setErr('The shared register could not be verified. Refresh; if the problem remains, ask a protected owner to check the Combined register setup.');
      setLoading(false);
      return null;
    }
    if (!initialLoadComplete.current) setLoading(true);
    if (showRefreshProgress) setRefreshing(true);
    try {
      const nextRows = await shifts.list();
      if (sequence === requestSequence.current) {
        setRows(nextRows);
        setErr(null);
      }
      return nextRows;
    } catch (error) {
      if (sequence === requestSequence.current) setErr(shiftActionErrorMessage(error, 'load'));
      return null;
    } finally {
      if (sequence === requestSequence.current) {
        initialLoadComplete.current = true;
        setLoading(false);
        if (showRefreshProgress) setRefreshing(false);
      }
    }
  }, [terminalId, terminalReady]);

  const loadRecoveryCandidates = useCallback(async (): Promise<ShiftRecoveryCandidateDTO[] | null> => {
    const sequence = ++recoveryCandidateSequence.current;
    if (!canReviewRecoveryCandidates) {
      setRecoveryCandidates([]);
      setRecoveryCandidatesError(null);
      setRecoveryCandidatesLoading(false);
      recoveryCandidateInitialLoadComplete.current = false;
      return [];
    }
    if (!LIVE_MODE || !terminalReady || !terminalId) {
      setRecoveryCandidates([]);
      setRecoveryCandidatesLoading(false);
      return [];
    }
    if (!recoveryCandidateInitialLoadComplete.current) setRecoveryCandidatesLoading(true);
    try {
      const nextCandidates = await shifts.listRecoveryCandidates();
      if (sequence === recoveryCandidateSequence.current) {
        setRecoveryCandidates(nextCandidates);
        setRecoveryCandidatesError(null);
      }
      return nextCandidates;
    } catch (error) {
      if (sequence === recoveryCandidateSequence.current) {
        const authorizationRejected = isShiftRecoveryAuthorizationRejection(error);
        if (authorizationRejected) {
          setRecoveryCandidates([]);
          setRecovering(null);
        }
        const detail = error instanceof Error && error.message.trim()
          ? ` ${error.message.trim()}`
          : '';
        setRecoveryCandidatesError(
          authorizationRejected
            ? 'Android shift recovery access is no longer available. Protected candidates and any open recovery were cleared.'
            : `Other-terminal Android shift recovery candidates could not be refreshed.${detail} Retry Refresh before attempting recovery.`,
        );
      }
      return null;
    } finally {
      if (sequence === recoveryCandidateSequence.current) {
        recoveryCandidateInitialLoadComplete.current = true;
        setRecoveryCandidatesLoading(false);
      }
    }
  }, [canReviewRecoveryCandidates, terminalId, terminalReady]);

  const refreshShiftSurfaces = useCallback(() => refreshShiftRecoverySurfaces(
    () => load(false),
    loadRecoveryCandidates,
    canReviewRecoveryCandidates,
  ), [canReviewRecoveryCandidates, load, loadRecoveryCandidates]);

  useEffect(() => {
    if (canReviewRecoveryCandidates) return;
    // A logout or role switch must discard previously fetched protected data
    // and invalidate any response that was already in flight.
    recoveryCandidateSequence.current += 1;
    recoveryCandidateInitialLoadComplete.current = false;
    setRecoveryCandidates([]);
    setRecoveryCandidatesError(null);
    setRecoveryCandidatesLoading(false);
    setRecovering(null);
  }, [canReviewRecoveryCandidates]);

  useEffect(() => { void refreshShiftSurfaces(); }, [refreshShiftSurfaces]);

  const refreshManually = useCallback(async () => {
    setRefreshing(true);
    const refreshed = await refreshShiftSurfaces();
    setRefreshing(false);
    if (
      refreshed.history
      && (!canReviewRecoveryCandidates || refreshed.candidates)
    ) {
      notifications.success('The latest opener, totals, shift status and recovery candidates are now shown.', {
        title: 'Shift information updated',
      });
    }
  }, [canReviewRecoveryCandidates, notifications, refreshShiftSurfaces]);

  // Whether a shift is open, and who has it open, is shared across every
  // device on this terminal. Real-time push means a shift opened elsewhere
  // shows up here within about a second — this is what stops someone from
  // trying to open a second shift on top of one that's already running,
  // just because their screen hadn't caught up yet. The interval is just a
  // safety net for a missed push.
  useEffect(() => {
    if (!LIVE_MODE || !terminalReady || !terminalId) return;
    const refresh = () => { void refreshShiftSurfaces(); };
    const unsubscribe = subscribeRealtime('shifts', refresh);
    const id = setInterval(refresh, OPERATIONS_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, [refreshShiftSurfaces, terminalReady, terminalId]);

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
  const currentWorkspaceName = terminalOptions.find((workspace) => workspace.id === terminalId)?.name
    || (terminalId ? `Workspace ${terminalId.slice(0, 8)}` : 'Combined register');

  const refreshAfterOpenFailure = async (): Promise<ShiftDTO | null> => {
    const refreshedRows = await load(false);
    if (!refreshedRows) return null;
    const resolution = resolveOpenShift({
      storedShiftId: null,
      branchId: me?.branch_id ?? null,
      terminalId,
      openShifts: refreshedRows,
    });
    return resolution.kind === 'ready' ? resolution.shift : null;
  };

  const refreshAfterCloseFailure = async (shiftId: string): Promise<ShiftDTO | null> => {
    const refreshedRows = await load(false);
    return refreshedRows?.find((shift) => shift.id === shiftId) ?? null;
  };
  const refreshAfterRecoveryFailure = async (): Promise<void> => {
    await refreshShiftSurfaces();
  };
  const businessDays = groupShiftsByBusinessDay(rows);

  return (
    <div>
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          {businessDays.length} business day{businessDays.length === 1 ? '' : 's'} ·{' '}
          {rows.length} shift record{rows.length === 1 ? '' : 's'} ·{' '}
          {openShift
            ? <span className="text-accent-good">Open shift active</span>
            : <span>No shift currently open</span>}
        </p>
        <div className="flex gap-2">
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => { void refreshManually(); }}
            disabled={refreshing}
            aria-label="Refresh shift information"
          >
            <RefreshCw className={refreshing ? 'animate-spin' : ''} size={14}/>
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
          {!openShift && !scopeError && canOpenShift && (
            <button type="button" className="btn btn-primary" onClick={() => setOpening(true)}>
              <ShieldCheck size={14}/> Open shift
            </button>
          )}
        </div>
      </div>

      {rows.length >= 200 && (
        <div
          className="mb-3 rounded-xl border border-accent-gold/35 bg-accent-gold/10 p-3 text-xs text-fg"
          role="status"
        >
          Showing the latest 200 shift records. Older records are still preserved, but the oldest business day shown here may be incomplete.
        </div>
      )}
      {err && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {err}
      </div>}
      {scopeError && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {scopeError}
      </div>}
      {!openShift && !scopeError && !canOpenShift && (
        <div className="card border-accent-gold/35 bg-accent-gold/5 text-sm mb-3">
          <p className="font-medium text-fg">Shift opening is view only for this account</p>
          <p className="mt-1 text-fg-muted">Ask a staff member with Open shift access. Nothing has been changed.</p>
        </div>
      )}

      <AndroidShiftRecoveryCandidatesPanel
        authorized={canReviewRecoveryCandidates}
        currentTerminalId={terminalId}
        candidates={recoveryCandidates}
        loading={recoveryCandidatesLoading}
        error={recoveryCandidatesError}
        recoveringShiftId={recovering?.shift.id ?? null}
        onRecover={(candidate) => setRecovering({
          shift: candidate,
          workspaceName: candidate.terminal_name,
        })}
      />

      {openShift && (
        <OpenShiftStatusPanel
          shift={openShift}
          workspaceName={shiftWorkspaceLabel(openShift, terminalOptions)}
          currentUserId={me?.user_id}
          canClose={canCloseShift}
          canRecover={canRecoverAndroidShift(me, openShift)}
          closing={closing?.id === openShift.id || recovering?.shift.id === openShift.id}
          onClose={() => setClosing(openShift)}
          onRecover={() => setRecovering({
            shift: openShift,
            workspaceName: shiftWorkspaceLabel(openShift, terminalOptions),
          })}
        />
      )}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No shifts recorded. Open the first shift before taking orders.
        </div>
      ) : (
        <div className="space-y-3">
          {businessDays.map((day) => {
            const legacyRevenueLabel = profileMembershipMoneyLabel(
              'revenue',
              day.membershipCollectionsMinor,
            );
            return (
            <section key={day.businessDate} className="card" aria-label={`Business day ${day.dateLabel}`}>
              <div className="flex justify-between items-start gap-3 flex-wrap">
                <div>
                  <div className="font-bold flex items-center gap-2">
                    {day.hasOpenShift
                      ? <span className="chip border-accent-good/40 text-accent-good">Open</span>
                      : day.hasIncompleteClose
                        ? <span className="chip border-accent-gold/40 text-fg">Needs review</span>
                        : <span className="chip border-fg-muted/40 text-fg-muted">Closed</span>}
                    {day.dateLabel}
                  </div>
                  <p className="mt-1 text-xs text-fg-muted">
                    First opened <strong className="text-fg">{formatShiftBusinessDateTime(day.firstOpenedAt)}</strong>
                    {' '}by <strong className="text-fg">{shiftOpenerLabel(day.firstShift)}</strong>
                  </p>
                  <p className="text-xs text-fg-muted">
                    {day.hasOpenShift
                      ? <span className="text-accent-good">Still open</span>
                      : day.hasIncompleteClose
                        ? <span className="text-fg">Needs review · close time missing</span>
                        : day.finalClosedAt
                      ? <>
                          Final close <strong className="text-fg">{formatShiftBusinessDateTime(day.finalClosedAt)}</strong>
                          {' '}by <strong className="text-fg">{shiftCloserLabel(day.finalShift)}</strong>
                        </>
                      : <span className="text-fg">Needs review · close time unavailable</span>}
                  </p>
                </div>
                <span className="chip border-accent-gold/35 text-fg">
                  {day.shifts.length} shift record{day.shifts.length === 1 ? '' : 's'}
                </span>
              </div>

              <div className="grid grid-cols-3 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                <Stat label="POS collections" value={inr(day.posCollectionsMinor)}/>
                {legacyRevenueLabel && (
                  <Stat label={legacyRevenueLabel} value={inr(day.membershipCollectionsMinor)}/>
                )}
                <Stat label="Gross collections" value={inr(day.grossCollectionsMinor)}/>
              </div>
              <p className="mt-1 text-[10px] text-fg-muted">
                Combined once for this business day. Payment receipts before refunds; opening float is excluded.
              </p>
              <div className="grid grid-cols-2 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                <Stat label="Total refunds" value={inr(day.totalRefundsMinor)}/>
                <Stat label="Net collections" value={inr(day.netCollectionsMinor)} tone="good"/>
              </div>

              <details className="mt-3 border-t border-bg-border/60 pt-3">
                <summary className="cursor-pointer text-sm font-medium text-fg">
                  Review {day.shifts.length} shift record{day.shifts.length === 1 ? '' : 's'} and drawer count{day.shifts.length === 1 ? '' : 's'}
                </summary>
                {day.shifts.length > 1 && (
                  <p className="mt-2 text-xs text-fg-muted">
                    Staff closed and reopened the drawer during this day. The records stay separate for audit, while the collection above counts the full day once.
                  </p>
                )}
                <div className="mt-3 space-y-3">
                  {day.shifts.map((s) => {
                    const shiftLegacyRevenueLabel = profileMembershipMoneyLabel(
                      'revenue',
                      s.membership_sales_minor ?? 0,
                    );
                    const shiftLegacyRefundLabel = profileMembershipMoneyLabel(
                      'refund',
                      s.settled_membership_refunds_minor ?? 0,
                    );
                    return (
                    <div key={s.id} className="rounded-xl border border-bg-border/70 bg-bg/30 p-3">
                      <div className="flex justify-between items-start gap-3 flex-wrap">
                        <div>
                          <p className="font-semibold">
                            {formatShiftBusinessTime(s.opened_at)}
                            {' → '}
                            {s.closed_at
                              ? formatShiftBusinessTime(s.closed_at)
                              : s.status === 'open'
                                ? 'Open'
                                : 'Needs review · close time missing'}
                          </p>
                          <p className="text-xs text-fg-muted">
                            Opened by <span className="text-fg font-medium">{shiftOpenerLabel(s)}</span>
                            {s.closed_at && <>
                              {' · '}Closed by <span className="text-fg font-medium">{shiftCloserLabel(s)}</span>
                            </>}
                          </p>
                          <p className="text-xs text-fg-muted">
                            Workspace <span className="text-fg font-medium">{shiftWorkspaceLabel(s, terminalOptions)}</span>
                          </p>
                          {s.status !== 'open' && !s.closed_at && (
                            <p className="mt-1 text-xs text-fg">
                              Saved status is closed, but its close time is missing. Review this historical record; it is not an open shift.
                            </p>
                          )}
                        </div>
                        {s.status === 'open' && canCloseShift && !isAndroidOriginShift(s) && (
                          <button
                            type="button"
                            className="btn btn-primary"
                            onClick={() => setClosing(s)}
                            disabled={Boolean(closing)}
                            aria-label={`Close shift opened by ${shiftOpenerLabel(s)}`}
                          >
                            <Lock size={14}/> Close shift
                          </button>
                        )}
                        {canRecoverAndroidShift(me, s) && (
                          <button
                            type="button"
                            className="btn btn-ghost border border-accent-bad/45 text-accent-bad"
                            onClick={() => setRecovering({
                              shift: s,
                              workspaceName: shiftWorkspaceLabel(s, terminalOptions),
                            })}
                            disabled={Boolean(closing || recovering)}
                            aria-label={`Recover Android shift opened by ${shiftOpenerLabel(s)}`}
                          >
                            <ShieldCheck size={14}/> Recover Android shift
                          </button>
                        )}
                      </div>
                      <div className="grid grid-cols-2 md:grid-cols-3 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                        <Stat label="POS collections" value={inr(s.pos_sales_minor ?? 0)}/>
                        {shiftLegacyRevenueLabel && (
                          <Stat label={shiftLegacyRevenueLabel} value={inr(s.membership_sales_minor ?? 0)}/>
                        )}
                        <Stat label="Gross collections" value={inr(s.gross_collections_minor ?? 0)}/>
                      </div>
                      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                        <Stat label="POS refunds" value={inr(s.settled_pos_refunds_minor ?? 0)}/>
                        {shiftLegacyRefundLabel && (
                          <Stat label={shiftLegacyRefundLabel} value={inr(s.settled_membership_refunds_minor ?? 0)}/>
                        )}
                        <Stat label="Total refunds" value={inr(s.total_refunds_minor ?? 0)}/>
                        <Stat label="Net collections" value={inr(s.net_collections_minor ?? 0)} tone="good"/>
                      </div>
                      <ShiftRailBreakdown shift={s}/>
                      <ShiftTenderEvidence shift={s}/>
                      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3 pt-3 border-t border-bg-border/60">
                        <Stat label="Opening float" value={inr(s.opening_float_minor)}/>
                        <Stat label="Expected cash" value={s.expected_minor != null ? inr(s.expected_minor) : '—'}/>
                        <Stat label="Counted cash" value={s.counted_minor != null ? inr(s.counted_minor) : '—'}/>
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
              </details>
            </section>
            );
          })}
        </div>
      )}

      {opening && (
        <OpenShiftForm
          staffName={me?.name || me?.email || 'Current employee'}
          workspaceName={currentWorkspaceName}
          onClose={() => setOpening(false)}
          onSuccess={() => { setOpening(false); void load(false); }}
          onError={refreshAfterOpenFailure}
        />
      )}
      {closing && (
        <CloseShiftForm
          shift={closing}
          currentUserId={me?.user_id}
          currentStaffName={me?.name || me?.email || 'Current employee'}
          workspaceName={shiftWorkspaceLabel(closing, terminalOptions)}
          canReadOrders={me?.effective_permissions?.includes('pos.read') === true}
          canReadGaming={me?.effective_permissions?.includes('gaming.read') === true}
          onClose={() => setClosing(null)}
          onSuccess={() => { setClosing(null); void load(false); }}
          onError={() => refreshAfterCloseFailure(closing.id)}
        />
      )}
      {recovering && (
        <RecoverAndroidShiftForm
          shift={recovering.shift}
          currentUserId={me?.user_id}
          currentStaffName={me?.name || me?.email || 'Protected audit owner'}
          workspaceName={recovering.workspaceName}
          onClose={() => setRecovering(null)}
          onSuccess={() => { setRecovering(null); void refreshShiftSurfaces(); }}
          onError={refreshAfterRecoveryFailure}
        />
      )}
    </div>
  );
}

function OpenShiftForm({
  staffName,
  workspaceName,
  onClose,
  onSuccess,
  onError,
}: {
  staffName: string;
  workspaceName: string;
  onClose: () => void;
  onSuccess: () => void;
  onError: () => Promise<ShiftDTO | null>;
}) {
  const notifications = useNotifications();
  const [float, setFloat] = useState('500');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [existingShift, setExistingShift] = useState<ShiftDTO | null>(null);
  const submissionInFlight = useRef(false);

  const close = () => {
    if (!submissionInFlight.current) onClose();
  };

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submissionInFlight.current) return;
    submissionInFlight.current = true;
    setBusy(true);
    setErr(null);
    setExistingShift(null);
    try {
      const openingFloatMinor = parseRupeesToMinor(float);
      if (openingFloatMinor === null) {
        throw new Error('Opening float must be a non-negative amount with at most two decimals.');
      }
      await shifts.open(openingFloatMinor);
      notifications.success(
        `The shared shift is ready on ${workspaceName}. Opening float ${inr(openingFloatMinor)} was saved under ${staffName}.`,
        { title: 'Shift opened' },
      );
      onSuccess();
    } catch (error) {
      // Most likely cause: someone else already opened a shift here since
      // this screen last synced. Refresh the list behind this modal so the
      // real shift is already showing by the time this error is dismissed.
      const refreshedOpenShift = await onError();
      if (refreshedOpenShift) {
        setExistingShift(refreshedOpenShift);
        notifications.info(
          `${shiftOpenerLabel(refreshedOpenShift)} already opened this shift. Continue using it instead of opening another.`,
          { title: 'Existing shift found' },
        );
      } else {
        const message = shiftActionErrorMessage(error, 'open');
        setErr(message);
        notifications.error(message, { title: 'Shift was not opened' });
      }
    }
    finally {
      submissionInFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={close} title="Open shift">
      <form onSubmit={submit} className="space-y-3">
        <div className="rounded-xl border border-bg-border bg-bg-raised/35 p-3 text-sm">
          <div className="flex items-center gap-2 font-medium"><Monitor size={15}/> {workspaceName}</div>
          <p className="mt-1 text-xs text-fg-muted">This shift will be opened by {staffName}.</p>
        </div>
        <p className="text-sm text-fg-muted">
          The opening float is the cash already in the drawer before you start.
          Typical Indian café float: ₹500 — ₹1,000.
        </p>
        <Field label="Opening float (₹)">
          <input type="number" required min={0} step="0.01" autoFocus
            className="input font-mono text-right text-xl" value={float}
            disabled={busy || Boolean(existingShift)}
            onChange={(e) => setFloat(e.target.value)}/>
        </Field>
        {existingShift && (
          <ExistingShiftFeedback
            shift={existingShift}
            workspaceName={workspaceName}
            onContinue={close}
          />
        )}
        {err && (
          <ErrorRow text={err}/>
        )}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={close} disabled={busy}>
            {existingShift ? 'Back to shifts' : 'Cancel'}
          </button>
          {!existingShift && <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <ShieldCheck size={14}/>}
            {busy ? 'Opening shift…' : 'Open shift'}
          </button>}
        </div>
      </form>
    </Modal>
  );
}

// Indian note/coin denominations, largest first — used by the optional
// denomination-breakdown entry mode below.
const CASH_DENOMINATIONS = [500, 200, 100, 50, 20, 10, 5, 2, 1];

export function CloseShiftForm({
  shift,
  currentUserId,
  currentStaffName,
  workspaceName,
  canReadOrders = false,
  canReadGaming = false,
  onClose,
  onSuccess,
  onError,
}: {
  shift: ShiftDTO;
  currentUserId: string | undefined;
  currentStaffName: string;
  workspaceName: string;
  canReadOrders?: boolean;
  canReadGaming?: boolean;
  onClose: () => void;
  onSuccess: () => void;
  onError: () => Promise<ShiftDTO | null>;
}) {
  const notifications = useNotifications();
  const [mode, setMode] = useState<'total' | 'denomination'>('total');
  const [counted, setCounted] = useState('');
  const [denominationsReviewed, setDenominationsReviewed] = useState(false);
  const [reviewedShift, setReviewedShift] = useState(shift);
  const [observations, setObservations] = useState<{ orders: number | null; games: number | null; capped: boolean; checkedAt: string } | null>(null);
  const [checkingObservations, setCheckingObservations] = useState(false);
  const [observationError, setObservationError] = useState<string | null>(null);
  const observationSequence = useRef(0);
  const checkObservations = useCallback(async () => {
    const request = ++observationSequence.current;
    setCheckingObservations(true);
    setObservationError(null);
    const checked = await Promise.allSettled([
      canReadOrders ? orders.list({ status: ['open', 'held'], limit: 500 }) : Promise.resolve(null),
      canReadGaming ? gaming.listOperationalSessions() : Promise.resolve(null),
    ]);
    if (request !== observationSequence.current) return;
    const errors = checked.flatMap(value => value.status === 'rejected' ? [value.reason instanceof Error ? value.reason.message : 'A readiness observation could not load.'] : []);
    if (errors.length) setObservationError(`${errors.join(' ')} Previous observations remain as of their displayed check.`);
    else {
      const orderRows = checked[0].status === 'fulfilled' ? checked[0].value : null;
      const gameRows = checked[1].status === 'fulfilled' ? checked[1].value : null;
      setObservations({ orders: orderRows?.length ?? null,
        games: gameRows?.filter(row => row.shift_id === shift.id).length ?? null,
        capped: orderRows?.length === 500 || gameRows?.length === 500,
        checkedAt: new Date().toISOString() });
    }
    setCheckingObservations(false);
  }, [canReadOrders, canReadGaming, shift.id]);
  useEffect(() => {
    void checkObservations();
    return () => {
      // This is a request generation, not a captured DOM ref: invalidate every
      // observation still in flight when the reviewed shift changes or closes.
      // eslint-disable-next-line react-hooks/exhaustive-deps
      ++observationSequence.current;
    };
  }, [checkObservations]);
  const [denomCounts, setDenomCounts] = useState<Record<number, string>>({
    500: '', 200: '', 100: '', 50: '', 20: '', 10: '', 5: '', 2: '', 1: '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [result, setResult] = useState<{
    variance_minor: number;
    closed_by?: string | null;
    closed_by_name?: string | null;
  } | null>(null);
  // A lost response leaves the first write's outcome unknown. Keep its exact
  // cash payload fixed until the server confirms the shift closed (or the same
  // payload is safely retried), so staff cannot create a second intent with a
  // different drawer count while the original transaction may still commit.
  const [ambiguousCountedMinor, setAmbiguousCountedMinor] = useState<number | null>(null);
  const submissionInFlight = useRef(false);
  const opener = shiftOpenerLabel(shift);
  const openedByCurrentUser = shift.opened_by === currentUserId;
  const confirmedCloser = result?.closed_by_name?.trim()
    || (result?.closed_by === currentUserId
      ? currentStaffName
      : result?.closed_by
        ? `Employee ${result.closed_by.slice(0, 8)}`
        : currentStaffName);

  const close = () => {
    if (submissionInFlight.current) return;
    if (ambiguousCountedMinor !== null) {
      const message = `The first close is still unconfirmed. Keep this window open and retry the same ${inr(ambiguousCountedMinor)} cash count after reconnecting.`;
      setErr(message);
      notifications.error(message, { title: 'Cash count is locked' });
      return;
    }
    onClose();
  };

  // Sum of (denomination × count), in paise — exact integer arithmetic, no
  // float round-trip through a rupee string.
  const denomTotalMinor = CASH_DENOMINATIONS.reduce(
    (sum, d) => sum + d * 100 * (parseInt(denomCounts[d] || '0', 10) || 0),
    0,
  );

  // Keep the independently entered total when switching modes. A denomination draft
  // is not a confirmed zero just because all note fields are initially empty.
  const denominationInputValid = Object.values(denomCounts).every(value => value === '' || /^\d+$/.test(value))
    && Number.isSafeInteger(denomTotalMinor) && denomTotalMinor >= 0;

  function setDenomCount(denom: number, value: string) {
    setDenominationsReviewed(false);
    setDenomCounts((prev) => ({ ...prev, [denom]: value }));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submissionInFlight.current) return;
    submissionInFlight.current = true;
    setBusy(true);
    setErr(null);
    // Denomination mode submits the exact integer sum computed above;
    // free-text mode submits exactly as before (unchanged wire format).
    const countedMinor = ambiguousCountedMinor ?? (
      mode === 'denomination'
        ? denominationsReviewed && denominationInputValid ? denomTotalMinor : null
        : parseRupeesToMinor(counted)
    );
    if (countedMinor === null) {
      const message = 'Counted cash must be a non-negative amount with at most two decimals.';
      setErr(message);
      notifications.error(message, { title: 'Check the cash count' });
      submissionInFlight.current = false;
      setBusy(false);
      return;
    }
    try {
      const r = await shifts.close(shift.id, countedMinor);
      setAmbiguousCountedMinor(null);
      setResult(r);
      const completedBy = r.closed_by_name?.trim()
        || (r.closed_by === currentUserId
          ? currentStaffName
          : r.closed_by
            ? `Employee ${r.closed_by.slice(0, 8)}`
            : currentStaffName);
      notifications.success(
        `Shift closure confirmed for ${completedBy}. The counted cash and variance are saved.`,
        { title: 'Shift closed' },
      );
    } catch (error) {
      // A close may commit even when its response is lost. Refresh the
      // authoritative row before telling staff to retry, so the same drawer
      // count is never accidentally submitted as a second operational intent.
      let refreshedShift: ShiftDTO | null = null;
      try { refreshedShift = await onError(); } catch { /* Unavailable evidence cannot resolve an unknown write. */ }
      if (refreshedShift?.id !== shift.id) refreshedShift = null;
      if (refreshedShift) setReviewedShift(refreshedShift);
      const outcomeIsUnknown = isUnknownShiftMutationOutcome(error);
      if (
        refreshedShift?.status === 'closed'
        && refreshedShift.counted_minor === countedMinor
      ) {
        setAmbiguousCountedMinor(null);
        setResult({
          variance_minor: refreshedShift.variance_minor ?? 0,
          closed_by: refreshedShift.closed_by,
          closed_by_name: refreshedShift.closed_by_name,
        });
        notifications.success(
          'The response was interrupted, but a refresh confirmed that this shift closed with the same cash count.',
          { title: 'Shift closure confirmed' },
        );
      } else {
        if (ambiguousCountedMinor !== null || (outcomeIsUnknown && refreshedShift?.status !== 'closed')) {
          // A later refusal cannot establish what happened to an earlier write
          // whose reply was lost. Only its matching authoritative result can.
          setAmbiguousCountedMinor(countedMinor);
        } else {
          setAmbiguousCountedMinor(null);
        }
        const message = shiftActionErrorMessage(error, 'close');
        setErr(message);
        notifications.error(message, {
          title: refreshedShift?.status === 'closed'
            ? 'Shift was already closed'
            : 'Shift closure needs review',
        });
      }
    } finally {
      submissionInFlight.current = false;
      setBusy(false);
    }
  }

  if (result) {
    const v = result.variance_minor;
    return (
      <Modal open onClose={() => { close(); onSuccess(); }} title="Shift closed">
        <div className="space-y-3">
          <div className="rounded-xl border border-bg-border bg-bg-raised/35 p-3 text-sm">
            <Row label="Workspace" value={workspaceName}/>
            <Row label="Opened by" value={opener}/>
            <Row
              label="Closed by"
              value={confirmedCloser}
            />
          </div>
          <div className={`p-4 rounded-xl ${
            v === 0 ? 'bg-accent-good/15 border border-accent-good/40 text-accent-good' :
            Math.abs(v) < 5000 ? 'bg-accent-gold/15 border border-accent-gold/40 text-fg' :
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
          <button type="button" className="btn btn-primary w-full" onClick={() => { close(); onSuccess(); }}>
            Done
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal open onClose={close} locked={busy || ambiguousCountedMinor !== null} size="xl" title={`Review & close shift · ${workspaceName}`}>
      <form onSubmit={submit} className="grid gap-5 lg:grid-cols-[1.1fr_1fr]" aria-label="Shift review and physical count">
        <section className="space-y-3" aria-label="Shift review">
        <div className="rounded-xl border border-bg-border bg-bg-raised/35 p-3 text-sm" role="status">
          <div className="flex items-center gap-2 font-semibold"><UserRound size={15}/> Shift responsibility</div>
          <div className="mt-2 space-y-1">
            <Row label="Opened by" value={`${opener}${openedByCurrentUser ? ' (you)' : ''}`}/>
            <Row label="Opened" value={formatShiftBusinessDateTime(shift.opened_at)}/>
            <Row label="Workspace" value={workspaceName}/>
            <Row label="Closing as" value={currentStaffName}/>
          </div>
          {!openedByCurrentUser && (
            <p className="mt-2 text-xs text-fg-muted">
              You can close a shift opened by another staff member. The opener stays in history,
              and this closure is recorded under your account.
            </p>
          )}
        </div>
        <div className="rounded-xl border border-bg-border bg-bg-raised/35 p-3 text-sm">
          <div className="font-semibold">Collection review</div>
          <div className="mt-2 grid grid-cols-2 gap-3">
            <Stat label="Gross collections" value={inr(reviewedShift.gross_collections_minor)}/>
            <Stat label="Refunds settled" value={inr(reviewedShift.total_refunds_minor)}/>
            <Stat label="Net collections" value={inr(reviewedShift.net_collections_minor)} tone="good"/>
            <Stat label="Expected cash" value={reviewedShift.expected_minor == null ? 'Pending server calculation' : inr(reviewedShift.expected_minor)}/>
          </div>
          <ShiftRailBreakdown shift={reviewedShift}/>
          <p className="mt-3 text-xs text-fg-muted">
            The server checks unpaid bills, running gaming sessions and other close blockers again when you submit this cash count.
          </p>
        </div>
        <section className="rounded-xl border border-bg-border p-3 text-sm" aria-label="Closing readiness observations">
          <div className="flex items-center justify-between gap-3"><h4 className="font-semibold">Before final close</h4><button type="button" className="btn btn-ghost !px-2 !py-1 text-xs" disabled={checkingObservations || busy || ambiguousCountedMinor !== null} onClick={() => {
            void checkObservations();
            void onError().then(row => { if (row?.id === shift.id) setReviewedShift(row); }).catch(cause => setObservationError(cause instanceof Error ? cause.message : 'Shift review could not refresh; retained values remain visible.'));
          }}>{checkingObservations ? 'Checking…' : 'Refresh review'}</button></div>
          {observationError && <p role="status" className="mt-2 text-xs text-fg-muted">{observationError}</p>}
          <dl className="mt-3 space-y-2">
            <div><dt className="text-xs text-fg-muted">Visible unfinished orders · authorized register scope</dt><dd>{observations?.orders == null ? 'Not verified with this account' : `${observations.orders} open or held`}</dd></div>
            <div><dt className="text-xs text-fg-muted">Visible unbilled games linked to this shift</dt><dd>{observations?.games == null ? 'Not verified with this account' : `${observations.games} active, paused or awaiting billing`}</dd></div>
          </dl>
          {observations && <p className="mt-2 text-xs text-fg-muted">Observation read completed {observations.checkedAt} (browser clock). {observations.capped ? 'A list reached its 500-row limit; more work may exist.' : ''}</p>}
          <p className="mt-3 text-xs text-fg-muted">These are partial observations, not an all-clear. Order-to-shift association is not included; private drafts and other saved work may be hidden. Tablet-local pending work is not verified here.</p>
          <p className="mt-2 text-xs text-fg-muted">Final server checks: unpaid orders, kitchen cancellations, active or unbilled games, accepted membership payments, membership refund recovery and pending membership/POS refunds. Any refusal below includes the next action.</p>
        </section>
        </section>
        <section className="space-y-3" aria-label="Physical cash count">
        <h4 className="font-semibold">Physical cash count</h4>
        <p className="text-sm text-fg-muted">
          Count all the cash in the drawer (notes + coins), then enter the total below.
          The system compares it with the recorded opening float and cash drawer movements,
          including cash sales, manual collections, refunds, expenses and corrections.
        </p>
        <Field label="Opening float">
          <input className="input font-mono text-right" disabled
            value={inr(shift.opening_float_minor)}/>
        </Field>

        <div className="flex gap-1 p-1 rounded-lg bg-bg-raised">
          <button type="button"
            className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
              mode === 'total' ? 'bg-bg-surface shadow text-fg' : 'text-fg-muted'}`}
            onClick={() => setMode('total')}
            disabled={busy || ambiguousCountedMinor !== null}>
            Enter total
          </button>
          <button type="button"
            className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
              mode === 'denomination' ? 'bg-bg-surface shadow text-fg' : 'text-fg-muted'}`}
            onClick={() => { setMode('denomination'); setDenominationsReviewed(false); }}
            disabled={busy || ambiguousCountedMinor !== null}>
            Count denominations
          </button>
        </div>

        <Field label="Counted cash (₹) — what's actually in the drawer">
          <input type="number" required min={0} step="0.01" autoFocus={mode === 'total'}
            disabled={busy || mode === 'denomination' || ambiguousCountedMinor !== null}
            className="input font-mono text-right text-xl disabled:opacity-80" value={mode === 'denomination' ? (denomTotalMinor / 100).toFixed(2) : counted}
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
                    disabled={busy || ambiguousCountedMinor !== null}
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

        {mode === 'denomination' && <label className="flex items-start gap-2 rounded-lg border border-bg-border p-3 text-sm"><input type="checkbox" className="mt-1" checked={denominationsReviewed} disabled={busy || ambiguousCountedMinor !== null || !denominationInputValid} onChange={event => setDenominationsReviewed(event.target.checked)}/><span>I counted these notes and coins: {inr(denomTotalMinor)}. An empty drawer is a deliberate ₹0 count.</span></label>}
        {err && (
          <ErrorRow text={err}/>
        )}
        {ambiguousCountedMinor !== null && (
          <div className="rounded-lg border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm" role="status" aria-live="polite">
            <div className="font-semibold text-fg">Unconfirmed close · cash count locked</div>
            <p className="mt-1 text-fg-muted">
              Reconnect, then retry this exact {inr(ambiguousCountedMinor)} count. You cannot edit the count,
              dismiss this window, or open a different close while the first result is unknown.
            </p>
          </div>
        )}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={close}
            disabled={busy || ambiguousCountedMinor !== null}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy || (ambiguousCountedMinor === null && (mode === 'denomination' ? !denominationsReviewed || !denominationInputValid : parseRupeesToMinor(counted) === null))}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Lock size={14}/>}
            {busy
              ? 'Checking shift…'
              : ambiguousCountedMinor !== null
                ? `Retry same ${inr(ambiguousCountedMinor)} count`
                : 'Close shift'}
          </button>
        </div>
        </section>
      </form>
    </Modal>
  );
}

export function androidShiftRecoveryMissingRequirements(
  counted: string,
  normalizedReason: string,
  quarantined: boolean,
): string[] {
  const missing: string[] = [];
  if (counted.trim() === '') {
    missing.push('Enter the exact physical cash count (₹0 is valid if the drawer is empty).');
  } else if (parseRupeesToMinor(counted) === null) {
    missing.push('Enter a valid cash count (₹0 or more, up to two decimal places).');
  }
  if (normalizedReason.length < 12) {
    missing.push('Add a recovery reason of at least 12 characters.');
  }
  if (!quarantined) {
    missing.push('Confirm the originating tablet app is isolated.');
  }
  return missing;
}

export function RecoverAndroidShiftForm({
  shift,
  currentUserId,
  currentStaffName,
  workspaceName,
  onClose,
  onSuccess,
  onError,
}: {
  shift: ShiftRecoveryTarget;
  currentUserId: string | undefined;
  currentStaffName: string;
  workspaceName: string;
  onClose: () => void;
  onSuccess: () => void;
  onError: () => Promise<void>;
}) {
  const notifications = useNotifications();
  const [counted, setCounted] = useState('');
  const [reason, setReason] = useState('');
  const [quarantined, setQuarantined] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [result, setResult] = useState<{
    variance_minor: number;
    closed_by?: string | null;
    closed_by_name?: string | null;
  } | null>(null);
  const [ambiguousPayload, setAmbiguousPayload] = useState<ShiftRecoveryCloseDTO | null>(null);
  const [intent] = useState(() => new StableMutationIntent<ShiftRecoveryCloseDTO>({
    prefix: 'shift-recovery-close:web',
  }));
  const submissionInFlight = useRef(false);
  const opener = shiftOpenerLabel(shift);
  const normalizedReason = reason.trim().replace(/\s+/g, ' ');
  const parsedCountedMinor = parseRupeesToMinor(counted);
  const missingRequirements = androidShiftRecoveryMissingRequirements(counted, normalizedReason, quarantined);
  const canSubmit = !busy && (
    ambiguousPayload !== null
      || missingRequirements.length === 0
  );

  const close = () => {
    if (submissionInFlight.current) return;
    if (ambiguousPayload) {
      const message = 'This recovery result is still unconfirmed. Keep the tablet isolated and retry the exact locked recovery after reconnecting.';
      setErr(message);
      notifications.error(message, { title: 'Recovery details are locked' });
      return;
    }
    onClose();
  };

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (submissionInFlight.current) return;
    if (!ambiguousPayload && parsedCountedMinor === null) {
      const message = 'Counted cash must be a non-negative amount with at most two decimals.';
      setErr(message);
      notifications.error(message, { title: 'Check the cash count' });
      return;
    }
    if (!ambiguousPayload && normalizedReason.length < 12) {
      const message = 'Explain why protected recovery is necessary in at least 12 characters.';
      setErr(message);
      notifications.error(message, { title: 'Recovery reason required' });
      return;
    }
    if (!ambiguousPayload && !quarantined) {
      const message = 'Confirm that the originating tablet app is isolated before recovery.';
      setErr(message);
      notifications.error(message, { title: 'Tablet isolation not confirmed' });
      return;
    }
    const payload: ShiftRecoveryCloseDTO = ambiguousPayload ?? {
      // Guarded above; TypeScript does not narrow a value captured by the
      // render, so retain the explicit assertion at this construction point.
      counted_minor: parsedCountedMinor as number,
      reason: normalizedReason,
      acknowledge_origin_tablet_quarantined: true,
    };
    const attempt = intent.resolve(
      `${shift.id}|${payload.counted_minor}|${payload.reason}|quarantined`,
      () => payload,
    );
    submissionInFlight.current = true;
    setBusy(true);
    setErr(null);
    try {
      const response = await shifts.recoverAndroidClose(
        shift.id,
        attempt.payload,
        attempt.idempotencyKey,
      );
      intent.confirmSuccess(attempt);
      setAmbiguousPayload(null);
      setResult(response);
      notifications.success(
        `Protected recovery closed the shift under ${currentStaffName}. The reason, cash count and server checks were added to Audit Log.`,
        { title: 'Android shift recovered' },
      );
    } catch (error) {
      // A generic ShiftRead row cannot prove which close path won a race. An
      // ordinary Android close can have the same cash count without recording
      // this protected reason and quarantine attestation. Refresh the surfaces,
      // but confirm recovery only from this endpoint's idempotent response.
      try { await onError(); } catch { /* Keep the exact recovery when refresh is unavailable. */ }
      const requiresExactReplay = ambiguousPayload !== null || recoveryFailureRequiresExactReplay(error);
      if (requiresExactReplay) setAmbiguousPayload(attempt.payload);
      else setAmbiguousPayload(null);
      const message = shiftActionErrorMessage(error, 'recovery-close');
      setErr(message);
      notifications.error(message, {
        title: requiresExactReplay ? 'Recovery needs confirmation' : 'Recovery was blocked',
      });
    } finally {
      submissionInFlight.current = false;
      setBusy(false);
    }
  }

  if (result) {
    const closer = result.closed_by_name?.trim()
      || (result.closed_by === currentUserId ? currentStaffName : 'Protected audit owner');
    return (
      <Modal open onClose={onSuccess} title="Android shift recovered">
        <div className="space-y-3">
          <div className="rounded-xl border border-accent-good/35 bg-accent-good/10 p-4 text-sm">
            <p className="font-semibold text-accent-good">Protected recovery is recorded</p>
            <p className="mt-1 text-fg-muted">
              Closed by {closer}. Audit Log retains the reason, drawer count, originating client and acknowledgement.
            </p>
          </div>
          <Row label="Opened by" value={opener}/>
          <Row label="Workspace" value={workspaceName}/>
          <Row label="Variance" value={signedMoney(result.variance_minor)} bold/>
          <button type="button" className="btn btn-primary w-full" onClick={onSuccess}>Done</button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal open onClose={close} locked={busy || ambiguousPayload !== null} title={`Recover Android shift · ${workspaceName}`}>
      <form onSubmit={submit} className="space-y-4">
        <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm">
          <p className="font-semibold text-accent-bad">Last-resort protected-owner recovery</p>
          <p className="mt-1 text-fg-muted">
            {opener} opened this shift from the Android tablet {formatShiftBusinessDateTime(shift.opened_at)}.
            Use this only when that originating app cannot complete its saved close.
          </p>
        </div>

        <div className="rounded-xl border border-bg-border bg-bg-raised/35 p-3 text-sm">
          <p className="font-medium">What the server checks before closing</p>
          <p className="mt-1 text-fg-muted">
            Open or held orders, running or unbilled gaming sessions, kitchen cancellations,
            membership tasks and refunds are checked again under a database lock. Any exact
            blocker will be shown here and the shift will remain open.
          </p>
        </div>

        <Field label="Counted cash (₹) — exact physical drawer total">
          <input
            type="number"
            required
            min={0}
            step="0.01"
            autoFocus
            className="input font-mono text-right text-xl"
            value={ambiguousPayload ? String(ambiguousPayload.counted_minor / 100) : counted}
            disabled={busy || ambiguousPayload !== null}
            onChange={(event) => setCounted(event.target.value)}
          />
        </Field>

        <label className="block">
          <span className="text-xs text-fg-muted">Recovery reason — stored in Audit Log</span>
          <textarea
            className="input mt-1 min-h-24 resize-y"
            required
            minLength={12}
            maxLength={500}
            value={ambiguousPayload?.reason ?? reason}
            disabled={busy || ambiguousPayload !== null}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Example: Tablet app was isolated after its saved close repeatedly failed to sync."
          />
          <span className="mt-1 block text-[11px] text-fg-muted">
            Explain what failed and how the tablet was isolated. Minimum 12 characters.
          </span>
        </label>

        <label className="flex items-start gap-3 rounded-xl border border-accent-bad/35 bg-bg-raised/35 p-3 text-sm">
          <input
            type="checkbox"
            className="mt-1 h-4 w-4 accent-[var(--accent)]"
            checked={ambiguousPayload !== null || quarantined}
            disabled={busy || ambiguousPayload !== null}
            onChange={(event) => setQuarantined(event.target.checked)}
          />
          <span>
            <strong>I confirm the originating tablet app is isolated.</strong>{' '}
            It will not be used or reconnected until this recovery is reviewed. I understand
            that any work which existed only on that tablet may need separate audited reconciliation
            and this close cannot be silently undone.
          </span>
        </label>

        {err && <ErrorRow text={err}/>}
        {!ambiguousPayload && missingRequirements.length > 0 && (
          <div
            id="android-shift-recovery-requirements"
            className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm"
            role="status"
            aria-live="polite"
          >
            <p className="font-semibold text-fg">To enable Recover &amp; close shift:</p>
            <ul className="mt-1 list-disc pl-5 text-fg-muted">
              {missingRequirements.map((requirement) => <li key={requirement}>{requirement}</li>)}
            </ul>
          </div>
        )}
        {ambiguousPayload && (
          <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm" role="status" aria-live="polite">
            <p className="font-semibold text-fg">Unconfirmed recovery · details locked</p>
            <p className="mt-1 text-fg-muted">
              Keep the tablet isolated. Refresh, then retry the exact {inr(ambiguousPayload.counted_minor)}
              cash count and identical audit reason. Do not create a second recovery intent.
            </p>
          </div>
        )}

        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn btn-ghost" onClick={close} disabled={busy || ambiguousPayload !== null}>
            Cancel
          </button>
          <button
            type="submit"
            className="btn btn-danger disabled:cursor-not-allowed disabled:opacity-40"
            disabled={!canSubmit}
            aria-describedby={missingRequirements.length > 0 && !ambiguousPayload
              ? 'android-shift-recovery-requirements'
              : undefined}
          >
            {busy ? <Loader2 className="animate-spin" size={14}/> : <ShieldCheck size={14}/>}
            {busy ? 'Checking every blocker…' : ambiguousPayload ? 'Retry exact recovery' : 'Recover & close shift'}
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
    tone === 'gold' ? 'text-fg' :
    tone === 'bad' ? 'text-accent-bad' : 'text-fg';
  return (
    <div>
      <div className="text-[10px] text-fg-muted uppercase tracking-wider">{label}</div>
      <div className={`font-mono font-bold ${color}`}>{value}</div>
    </div>
  );
}

export function ShiftTenderEvidence({shift}:{shift:ShiftDTO}) {
  if(!shift.tender_correction_minor)return null;
  return <section className="mt-3 rounded-xl border border-bg-border bg-bg-raised/30 p-3 text-sm" aria-label="Reconstructed bill tender"><h5 className="font-semibold">Recorded close preserved · reconstructed bill tender</h5><p className="mt-1 text-xs text-fg-muted">Original expected cash, physical count and variance stay unchanged. This includes identified bill corrections only; daily unallocated actuals are separate in Finance.</p><div className="mt-3 grid gap-3 sm:grid-cols-3"><Stat label="Reconstructed Cash collected" value={shift.reconstructed_cash_collections_minor==null?'Unavailable':inr(shift.reconstructed_cash_collections_minor)}/><Stat label="Reconstructed UPI collected" value={shift.reconstructed_upi_collections_minor==null?'Unavailable':inr(shift.reconstructed_upi_collections_minor)}/><Stat label="Reconstructed expected cash" value={shift.reconstructed_expected_minor==null?'Unavailable':inr(shift.reconstructed_expected_minor)}/></div></section>;
}
function RetainedInvestigation({details}:{details:ReceiptInvestigationDTO}) {
  const retainedTime = (value: string) => `${shopTime(value, details.shop_timezone ?? 'UTC')} · ${details.shop_timezone ?? 'UTC (shop timezone unavailable)'}`;
  return <section className="space-y-4 rounded-xl border border-bg-border p-4"><h4 className="font-semibold">Retained players, drinks & billing evidence</h4><p className="text-xs text-fg-muted">{details.historical_evidence_note}</p>{details.participant_intervals.length>0&&<div className="space-y-2"><h5 className="text-sm font-semibold">Joining / Leave / rejoin history</h5>{details.participant_intervals.map(interval=><div key={interval.id} className="rounded-lg bg-bg-raised/40 p-3 text-sm"><b>{interval.guest_name || (interval.customer_id?'Recorded customer':'Anonymous player')}</b><p className="mt-1 text-xs text-fg-muted">Joined {retainedTime(interval.joined_at)} · {interval.left_at?`Left ${retainedTime(interval.left_at)}`:'No retained Leave event'}</p><p className="mt-1 text-xs">Play position {Math.floor(interval.joined_play_elapsed_ms/60000)} min → {interval.left_play_elapsed_ms==null?'end of session':`${Math.floor(interval.left_play_elapsed_ms/60000)} min`}. {interval.settlement_line_id?'Charge is listed once per player below, including all rejoins.':'No retained settled player charge.'}</p></div>)}</div>}{details.participant_charges.length>0&&<div className="space-y-2"><h5 className="text-sm font-semibold">One settled charge per player</h5>{details.participant_charges.map(charge=><div key={charge.id} className="flex justify-between gap-3 text-sm"><span>{charge.interval_count} joining interval{charge.interval_count===1?'':'s'} · {Math.floor(charge.play_elapsed_ms/60000)} min accumulated</span><b className="tabular-nums">{inr(charge.charge_minor)}</b></div>)}</div>}{details.addons.length>0&&<div className="space-y-2"><h5 className="text-sm font-semibold">Confirmed drinks & snacks</h5>{details.addons.map(addon=><div key={addon.id} className="rounded-lg bg-bg-raised/40 p-3 text-sm"><div className="flex justify-between gap-3"><b>{addon.qty} × {addon.menu_item_name_snapshot}</b><span className="tabular-nums">{inr(addon.line_total_minor)}</span></div><p className="mt-1 text-xs text-fg-muted">Confirmed {retainedTime(addon.created_at)}{addon.voided_at?` · Voided ${retainedTime(addon.voided_at)} · ${addon.void_reason??'Reason not retained'}`:''}</p></div>)}</div>}{!details.participant_intervals.length&&!details.addons.length&&<p className="text-sm text-fg-muted">No retained player intervals or session add-ons for this receipt. Older missing proof is not reconstructed from the current catalogue.</p>}</section>;
}

/** Inline shift work preserves its caller's station selection and POS draft. */
export function InlineShiftWorkspace({onClose}:{onClose:()=>void}) {
  return <section className="mb-5 rounded-xl border border-bg-border bg-bg-surface p-4" aria-label="Shift workspace">
    <header className="mb-4 flex items-center justify-between gap-3"><div><h3 className="font-semibold">Shift workspace</h3><p className="mt-1 text-sm text-fg-muted">Your current station and bill stay in place while you open or review the shift.</p></div><button type="button" className="btn btn-ghost" onClick={onClose}>Return to work</button></header><ShiftsTab/>
  </section>;
}
