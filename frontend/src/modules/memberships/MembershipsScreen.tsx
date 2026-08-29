import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  CreditCard,
  History,
  Loader2,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  UserRound,
  WifiOff,
} from 'lucide-react';

import Modal from '@/components/ui/Modal';
import { SkeletonCard } from '@/components/ui/Skeleton';
import { useNotifications } from '@/components/ui/Notifications';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import {
  customers,
  memberships,
  shifts,
  type CustomerDTO,
  type MembershipPaymentTaskDTO,
  type MembershipRefundDTO,
  type MembershipRefundWithdrawalDTO,
  type MembershipTierDTO,
  type ShiftDTO,
  type SubscriptionDTO,
} from '@/lib/erp-api';
import { inr } from '@/lib/inr';
import {
  clearStoredShift,
  readStoredShiftId,
  resolveOpenShift,
  shiftResolutionMessage,
  storeShiftId,
  type ShiftResolution,
} from '@/lib/operational-context';
import { useAuth } from '@/modules/auth/AuthContext';
import { MembershipTaskControls } from './MembershipTaskControls';
import {
  canManageMemberships,
  canViewMemberships,
  isDefiniteMembershipRejection,
  makeMembershipActionId,
  paymentTaskActions,
  PAYMENT_STATUS,
  refundTaskActions,
  REFUND_STATUS,
  type MembershipMoneyAction,
} from './membership-policy';

const FINISHED = new Set(['settled', 'withdrawn']);

type MembershipData = {
  tiers: MembershipTierDTO[];
  paymentTasks: MembershipPaymentTaskDTO[];
  refundTasks: MembershipRefundDTO[];
  openShifts: ShiftDTO[];
};

type DialogState =
  | { kind: 'prepare'; tier: MembershipTierDTO }
  | { kind: 'cancel'; subscription: SubscriptionDTO }
  | { kind: 'refund'; subscription: SubscriptionDTO }
  | { kind: 'payment_complete'; task: MembershipPaymentTaskDTO }
  | { kind: 'payment_withdraw'; task: MembershipPaymentTaskDTO }
  | { kind: 'refund_complete'; task: MembershipRefundDTO }
  | { kind: 'refund_withdraw'; task: MembershipRefundDTO };

function formatDate(value: string | null): string {
  if (!value) return '—';
  return new Date(value).toLocaleString('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}

function railLabel(value: string | null): string {
  if (!value) return 'Not recorded';
  return value === 'upi' ? 'UPI' : value.charAt(0).toUpperCase() + value.slice(1);
}

function toneClass(tone: 'warning' | 'danger' | 'info' | 'success' | 'neutral'): string {
  switch (tone) {
    case 'warning': return 'border-accent-gold/40 bg-accent-gold/10 text-accent-gold';
    case 'danger': return 'border-accent-bad/40 bg-accent-bad/10 text-accent-bad';
    case 'info': return 'border-accent/40 bg-accent/10 text-accent';
    case 'success': return 'border-accent-good/40 bg-accent-good/10 text-accent-good';
    case 'neutral': return 'border-bg-border bg-bg-raised text-fg-muted';
  }
}

function canManageShift(shift: ShiftDTO | null, userId: string | undefined, protectedAccess: boolean): boolean {
  return Boolean(shift && (protectedAccess || shift.opened_by === userId));
}

function replaceOrRemove<T extends { id: string; status: string }>(
  rows: T[],
  next: T,
): T[] {
  if (FINISHED.has(next.status)) return rows.filter((row) => row.id !== next.id);
  const found = rows.some((row) => row.id === next.id);
  return found ? rows.map((row) => (row.id === next.id ? next : row)) : [next, ...rows];
}

export default function MembershipsScreen() {
  const { me, demo, terminalId, terminalReady, terminalIssue } = useAuth();
  const online = useOnlineStatus();
  const notifications = useNotifications();
  const canView = Boolean(demo || canViewMemberships(me));
  const canManage = Boolean(!demo && canManageMemberships(me));
  const [data, setData] = useState<MembershipData>({
    tiers: [], paymentTasks: [], refundTasks: [], openShifts: [],
  });
  const [shiftResolution, setShiftResolution] = useState<ShiftResolution | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [uncertainTaskIds, setUncertainTaskIds] = useState<Set<string>>(() => new Set());
  const [uncertainPreparationId, setUncertainPreparationId] = useState<string | null>(null);
  const [uncertainCustomerMutation, setUncertainCustomerMutation] = useState<{
    kind: 'cancel' | 'refund'; customerId: string;
  } | null>(null);
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [searchResults, setSearchResults] = useState<CustomerDTO[]>([]);
  const [selectedCustomer, setSelectedCustomer] = useState<CustomerDTO | null>(null);
  const [subscription, setSubscription] = useState<SubscriptionDTO | null>(null);
  const [history, setHistory] = useState<SubscriptionDTO[]>([]);
  const [customerLoading, setCustomerLoading] = useState(false);
  const [dialog, setDialog] = useState<DialogState | null>(null);
  const requestSequence = useRef(0);
  const uncertainPreparationRef = useRef<string | null>(null);
  const operationBusyRef = useRef(false);

  const currentShift = shiftResolution?.kind === 'ready' ? shiftResolution.shift : null;
  const currentShiftManageable = canManageShift(currentShift, me?.user_id, Boolean(me?.protected_access));

  function acquireOperation(key: string): boolean {
    if (operationBusyRef.current) return false;
    operationBusyRef.current = true;
    setBusyKey(key);
    return true;
  }

  function releaseOperation() {
    operationBusyRef.current = false;
    setBusyKey(null);
  }

  const loadCustomerMembership = useCallback(async (customer: CustomerDTO) => {
    if (!online) {
      setActionError('Membership records are online-only on web. Reconnect and refresh; nothing was saved locally.');
      return false;
    }
    setCustomerLoading(true);
    setActionError(null);
    try {
      const [active, rows] = await Promise.all([
        memberships.getCustomerSubscription(customer.id),
        memberships.getCustomerSubscriptionHistory(customer.id),
      ]);
      setSubscription(active);
      setHistory(rows);
      return true;
    } catch (error) {
      setActionError((error as Error).message);
      return false;
    } finally {
      setCustomerLoading(false);
    }
  }, [online]);

  const load = useCallback(async ({
    initial = false,
    clearUncertain = true,
  }: { initial?: boolean; clearUncertain?: boolean } = {}): Promise<boolean> => {
    if (!canView) {
      setLoading(false);
      return false;
    }
    if (!online) {
      setLoadError('Memberships are online-only on web. Reconnect to load live plans, terms, and money tasks.');
      setLoading(false);
      return false;
    }
    if (canManage && (!terminalReady || !terminalId || !me?.branch_id)) {
      setShiftResolution(null);
      setLoadError(terminalIssue || 'Select this device’s shop terminal before managing memberships.');
      setLoading(false);
      return false;
    }

    const sequence = ++requestSequence.current;
    if (canManage) setShiftResolution(null);
    if (initial) setLoading(true);
    else setRefreshing(true);
    setLoadError(null);
    try {
      const [tiers, paymentTasks, refundTasks, openShifts] = await Promise.all([
        memberships.listTiers(),
        canManage ? memberships.listPaymentRequests({ unresolved: true, limit: 200 }) : Promise.resolve([]),
        canManage ? memberships.listRefundTasks({ unresolved: true, limit: 200 }) : Promise.resolve([]),
        canManage ? shifts.list(true) : Promise.resolve([]),
      ]);
      if (sequence !== requestSequence.current) return false;

      let resolution: ShiftResolution | null = null;
      if (canManage && me?.branch_id && terminalId) {
        const scope = { companyId: me.company_id, branchId: me.branch_id, terminalId };
        resolution = resolveOpenShift({
          storedShiftId: readStoredShiftId(scope),
          branchId: me.branch_id,
          terminalId,
          openShifts,
        });
        if (resolution.kind === 'ready') storeShiftId(scope, resolution.shift.id);
        else clearStoredShift();
      }

      setData({ tiers, paymentTasks, refundTasks, openShifts });
      setShiftResolution(resolution);
      if (clearUncertain) {
        setUncertainTaskIds(new Set());
        const pendingId = uncertainPreparationRef.current;
        if (pendingId) {
          const found = paymentTasks.some((task) => task.client_action_id === pendingId);
          notifications.info(
            found
              ? 'The server did accept the prepared payment. Review the recovered task; do not prepare another.'
              : 'The server has no prepared task for that action. It is safe to start a new preparation.',
            { title: 'Membership state refreshed' },
          );
          uncertainPreparationRef.current = null;
          setUncertainPreparationId(null);
        }
      }
      return true;
    } catch (error) {
      if (sequence === requestSequence.current) setLoadError((error as Error).message);
      return false;
    } finally {
      if (sequence === requestSequence.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [canManage, canView, me, notifications, online, terminalId, terminalIssue, terminalReady]);

  useEffect(() => { void load({ initial: true }); }, [load]);

  const search = useCallback(async (event?: React.FormEvent) => {
    event?.preventDefault();
    const clean = query.trim();
    if (!clean) {
      setSearchResults([]);
      return;
    }
    if (!online) {
      setActionError('Customer search is online-only. Reconnect and try again; nothing was saved locally.');
      return;
    }
    setSearching(true);
    setActionError(null);
    try {
      setSearchResults((await customers.list(clean)).slice(0, 20));
    } catch (error) {
      setActionError((error as Error).message);
    } finally {
      setSearching(false);
    }
  }, [online, query]);

  async function selectCustomer(customer: CustomerDTO) {
    setSelectedCustomer(customer);
    setSearchResults([]);
    setQuery('');
    setSubscription(null);
    setHistory([]);
    await loadCustomerMembership(customer);
  }

  async function refreshAll() {
    const ok = await load({ clearUncertain: true });
    if (ok && selectedCustomer) {
      const customerOk = await loadCustomerMembership(selectedCustomer);
      if (customerOk) setUncertainCustomerMutation(null);
    } else if (ok) {
      setUncertainCustomerMutation(null);
    }
  }

  function noMoneyActionReason(taskShiftId: string, uncertain: boolean): string | null {
    if (uncertain) {
      return 'The last action has no definite response. Do not move money again. Refresh server state first.';
    }
    if (!online) return 'Web membership money actions are online-only. Reconnect before continuing.';
    if (!currentShift) {
      return shiftResolution ? shiftResolutionMessage(shiftResolution) : 'Open this terminal’s shift first.';
    }
    if (currentShift.id !== taskShiftId) {
      return 'This task belongs to a different shift. Return to its exact terminal and open shift.';
    }
    if (!currentShiftManageable) {
      return `This shift was opened by ${currentShift.opened_by_name || 'another employee'}. They or a protected owner must continue.`;
    }
    return null;
  }

  function markAmbiguous(taskId: string, message: string) {
    setUncertainTaskIds((current) => new Set(current).add(taskId));
    setActionError(
      `${message} The browser saved no offline copy and the server outcome is unknown. `
      + 'Do not repeat the money action. Refresh Memberships to recover its authoritative state.',
    );
  }

  function markTaskStale(taskId: string, message: string) {
    setUncertainTaskIds((current) => new Set(current).add(taskId));
    setActionError(
      `${message} The server rejected this snapshot, so its controls are locked. `
      + 'Refresh the authoritative task before trying a different action; do not repeat any physical payment or payout.',
    );
  }

  async function runPaymentMutation(
    task: MembershipPaymentTaskDTO,
    label: string,
    operation: () => Promise<MembershipPaymentTaskDTO>,
  ): Promise<MembershipPaymentTaskDTO | null> {
    if (!online) {
      setActionError('This payment action requires a live connection. Nothing was saved locally.');
      return null;
    }
    if (!acquireOperation(`payment:${task.id}`)) return null;
    setActionError(null);
    try {
      const result = await operation();
      setData((current) => ({
        ...current,
        paymentTasks: replaceOrRemove(current.paymentTasks, result),
      }));
      setUncertainTaskIds((current) => {
        const next = new Set(current); next.delete(task.id); return next;
      });
      return result;
    } catch (error) {
      if (isDefiniteMembershipRejection(error)) markTaskStale(task.id, (error as Error).message);
      else markAmbiguous(task.id, `${label} did not return a definite result.`);
      return null;
    } finally {
      releaseOperation();
    }
  }

  async function runRefundMutation(
    task: MembershipRefundDTO,
    label: string,
    operation: () => Promise<MembershipRefundDTO>,
  ): Promise<MembershipRefundDTO | null> {
    if (!online) {
      setActionError('This refund action requires a live connection. Nothing was saved locally.');
      return null;
    }
    if (!acquireOperation(`refund:${task.id}`)) return null;
    setActionError(null);
    try {
      const result = await operation();
      setData((current) => ({
        ...current,
        refundTasks: replaceOrRemove(current.refundTasks, result),
      }));
      setUncertainTaskIds((current) => {
        const next = new Set(current); next.delete(task.id); return next;
      });
      return result;
    } catch (error) {
      if (isDefiniteMembershipRejection(error)) markTaskStale(task.id, (error as Error).message);
      else markAmbiguous(task.id, `${label} did not return a definite result.`);
      return null;
    } finally {
      releaseOperation();
    }
  }

  async function preparePayment(tier: MembershipTierDTO, method: 'cash' | 'card' | 'upi') {
    if (!selectedCustomer || !currentShift) return;
    if (!online) {
      setActionError('Payment preparation requires a live connection. Nothing was saved locally.');
      return;
    }
    if (uncertainPreparationId) {
      setActionError('Refresh the uncertain preparation before starting another. Do not collect money.');
      return;
    }
    if (!acquireOperation('prepare')) return;
    setActionError(null);
    const actionId = makeMembershipActionId('payment');
    try {
      // Re-read the authoritative tier immediately before capturing its price.
      const liveTier = (await memberships.listTiers()).find((candidate) => candidate.id === tier.id);
      if (!liveTier || liveTier.monthly_price_minor <= 0) {
        throw new Error('This plan or its current monthly price is unavailable. Refresh plans and try again.');
      }
      const task = await memberships.preparePayment({
        customer_id: selectedCustomer.id,
        tier_id: liveTier.id,
        shift_id: currentShift.id,
        expected_amount_minor: liveTier.monthly_price_minor,
        billing_cycle: 'monthly',
        paid_via: method,
        client_action_id: actionId,
      }, actionId);
      setData((current) => ({ ...current, paymentTasks: [task, ...current.paymentTasks] }));
      setDialog(null);
      notifications.success(
        'The payment task is prepared. No money has moved yet. Start its collection step before taking payment.',
        { title: 'Membership payment prepared' },
      );
    } catch (error) {
      if (isDefiniteMembershipRejection(error)) {
        setActionError((error as Error).message);
      } else {
        uncertainPreparationRef.current = actionId;
        setUncertainPreparationId(actionId);
        setDialog(null);
        setActionError(
          'Preparation did not return a definite result. The browser saved no offline copy; the server may still have accepted it. '
          + 'Do not collect money or prepare again. Refresh Memberships to recover the authoritative task.',
        );
      }
    } finally {
      releaseOperation();
    }
  }

  async function beginPayment(task: MembershipPaymentTaskDTO) {
    const result = await runPaymentMutation(task, 'Starting collection', () => (
      task.paid_via === 'cash'
        ? memberships.beginCashCollection(task, makeMembershipActionId('payment-begin-cash'))
        : memberships.beginProviderPayment(task, makeMembershipActionId('payment-begin-provider'))
    ));
    if (result) notifications.warning(PAYMENT_STATUS[result.status].detail, { title: PAYMENT_STATUS[result.status].label });
  }

  async function finalizePayment(task: MembershipPaymentTaskDTO) {
    const result = await runPaymentMutation(task, 'Posting the membership receipt', () => (
      memberships.finalizePayment(task, makeMembershipActionId('payment-finalize'))
    ));
    if (result?.status === 'settled') {
      notifications.success(`Receipt ${result.receipt_no || 'posted'} is complete.`, { title: 'Membership settled' });
      if (selectedCustomer?.id === result.customer_id) await loadCustomerMembership(selectedCustomer);
    }
  }

  async function beginRefund(task: MembershipRefundDTO) {
    const result = await runRefundMutation(task, 'Starting refund payout', () => (
      task.method === 'cash'
        ? memberships.beginRefundCashHandoff(task, makeMembershipActionId('refund-begin-cash'))
        : memberships.beginRefundProviderAction(task, makeMembershipActionId('refund-begin-provider'))
    ));
    if (result) notifications.warning(REFUND_STATUS[result.status].detail, { title: REFUND_STATUS[result.status].label });
  }

  async function finalizeRefund(task: MembershipRefundDTO) {
    const result = await runRefundMutation(task, 'Posting the membership refund', () => (
      memberships.finalizeRefund(task, makeMembershipActionId('refund-finalize'))
    ));
    if (result?.status === 'settled') {
      notifications.success(`Refund receipt ${result.receipt_no || 'posted'} is complete.`, { title: 'Membership refund settled' });
      if (selectedCustomer && result.customer_id === selectedCustomer.id) await loadCustomerMembership(selectedCustomer);
    }
  }

  async function handlePaymentAction(task: MembershipPaymentTaskDTO, action: MembershipMoneyAction) {
    if (action === 'begin') await beginPayment(task);
    else if (action === 'complete') setDialog({ kind: 'payment_complete', task });
    else if (action === 'finalize') await finalizePayment(task);
    else setDialog({ kind: 'payment_withdraw', task });
  }

  async function handleRefundAction(task: MembershipRefundDTO, action: MembershipMoneyAction) {
    if (action === 'begin') await beginRefund(task);
    else if (action === 'complete') setDialog({ kind: 'refund_complete', task });
    else if (action === 'finalize') await finalizeRefund(task);
    else setDialog({ kind: 'refund_withdraw', task });
  }

  const activeMemberCount = subscription?.is_active ? 1 : 0;
  const outstandingCount = data.paymentTasks.length + data.refundTasks.length;
  const selectedHasOutstandingTask = Boolean(selectedCustomer && (
    data.paymentTasks.some((task) => task.customer_id === selectedCustomer.id)
    || data.refundTasks.some((task) => task.customer_id === selectedCustomer.id)
  ));
  const prepareDisabledReason = !selectedCustomer
    ? 'Select a customer first.'
    : subscription?.is_active
      ? 'This customer already has an active membership.'
      : selectedHasOutstandingTask
        ? 'Resolve this customer’s existing membership task first.'
        : uncertainCustomerMutation?.customerId === selectedCustomer.id
          ? 'Refresh this customer’s uncertain action first.'
          : !currentShift || !currentShiftManageable
            ? 'Open and verify this terminal’s shift first.'
            : uncertainPreparationId
              ? 'Refresh the uncertain preparation first.'
              : null;

  if (!canView) {
    return (
      <div className="card border-accent-bad/40 bg-accent-bad/10">
        <h2 className="font-semibold text-accent-bad">Memberships unavailable</h2>
        <p className="mt-1 text-sm text-fg-muted">This account does not have POS read access.</p>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold">Memberships</h2>
          <p className="text-sm text-fg-muted">D Club plans, paid terms, receipts, and guarded refund recovery</p>
        </div>
        <button className="btn btn-ghost" disabled={refreshing} onClick={() => { void refreshAll(); }}>
          {refreshing ? <Loader2 className="animate-spin" size={16}/> : <RefreshCw size={16}/>} Refresh
        </button>
      </header>

      {!online && (
        <div className="card flex items-start gap-3 border-accent-bad/40 bg-accent-bad/10" role="alert">
          <WifiOff className="mt-0.5 shrink-0 text-accent-bad" size={19}/>
          <div>
            <p className="font-semibold text-accent-bad">Memberships are offline</p>
            <p className="mt-1 text-sm text-fg-muted">Web does not queue membership money actions. Nothing attempted offline is saved; reconnect and refresh.</p>
          </div>
        </div>
      )}

      {actionError && (
        <div className="card flex items-start gap-3 border-accent-bad/40 bg-accent-bad/10" role="alert">
          <AlertCircle className="mt-0.5 shrink-0 text-accent-bad" size={18}/>
          <div className="min-w-0 flex-1">
            <p className="font-semibold text-accent-bad">Action needs attention</p>
            <p className="mt-1 break-words text-sm text-fg-muted">{actionError}</p>
          </div>
          <button className="btn btn-ghost !min-h-[40px] !px-3 !py-2" onClick={() => { void refreshAll(); }}>Refresh state</button>
        </div>
      )}

      {loadError && (
        <div className="card flex items-center gap-2 border-accent-bad/40 bg-accent-bad/10 text-sm text-accent-bad" role="alert">
          <AlertCircle size={16}/>{loadError}
        </div>
      )}

      {loading ? <SkeletonCard lines={5}/> : (
        <>
          <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <SummaryCard label="Available plans" value={String(data.tiers.length)} detail="Server-configured monthly tiers" Icon={Sparkles}/>
            <SummaryCard label="Selected member" value={String(activeMemberCount)} detail={selectedCustomer ? selectedCustomer.name || selectedCustomer.phone : 'Search for a customer'} Icon={UserRound}/>
            <SummaryCard label="Payment tasks" value={String(data.paymentTasks.length)} detail="Unresolved on this terminal" Icon={CreditCard} warning={data.paymentTasks.length > 0}/>
            <SummaryCard label="Refund tasks" value={String(data.refundTasks.length)} detail={`${outstandingCount} total actions before shift close`} Icon={History} warning={data.refundTasks.length > 0}/>
          </section>

          {canManage && (
            <ShiftContextCard
              resolution={shiftResolution}
              shift={currentShift}
              terminalIssue={terminalIssue}
            />
          )}

          {canManage && outstandingCount > 0 && (
            <section className="card border-accent-gold/35">
              <div className="mb-4 flex items-start justify-between gap-3">
                <div>
                  <h3 className="font-semibold">Outstanding membership actions</h3>
                  <p className="mt-1 text-sm text-fg-muted">These tasks survive refresh and must be settled or resolved before their shift can close.</p>
                </div>
                <span className="chip border-accent-gold/40 text-accent-gold">{outstandingCount} open</span>
              </div>
              <div className="grid gap-3 xl:grid-cols-2">
                {data.paymentTasks.map((task) => (
                  <PaymentTaskCard
                    key={task.id}
                    task={task}
                    busy={busyKey === `payment:${task.id}`}
                    uncertain={uncertainTaskIds.has(task.id)}
                    currentShiftId={currentShift?.id ?? null}
                    disabledReason={noMoneyActionReason(task.shift_id, uncertainTaskIds.has(task.id))}
                    onAction={(action) => { void handlePaymentAction(task, action); }}
                  />
                ))}
                {data.refundTasks.map((task) => (
                  <RefundTaskCard
                    key={task.id}
                    task={task}
                    busy={busyKey === `refund:${task.id}`}
                    uncertain={uncertainTaskIds.has(task.id)}
                    currentShiftId={currentShift?.id ?? null}
                    disabledReason={noMoneyActionReason(task.shift_id, uncertainTaskIds.has(task.id))}
                    onAction={(action) => { void handleRefundAction(task, action); }}
                  />
                ))}
              </div>
            </section>
          )}

          <section className="grid items-start gap-4 xl:grid-cols-[minmax(18rem,0.8fr)_minmax(0,1.2fr)]">
            <div className="card">
              <div className="mb-4">
                <h3 className="font-semibold">Find a customer</h3>
                <p className="mt-1 text-sm text-fg-muted">Review the live term and receipt history before preparing payment.</p>
              </div>
              <form className="flex gap-2" onSubmit={(event) => { void search(event); }}>
                <label className="relative min-w-0 flex-1">
                  <span className="sr-only">Customer name or phone</span>
                  <Search className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-fg-muted" size={16}/>
                  <input
                    className="input pl-10"
                    value={query}
                    onChange={(event) => { setQuery(event.target.value); setSearchResults([]); }}
                    placeholder="Name or phone"
                  />
                </label>
                <button className="btn btn-ghost" disabled={searching || !query.trim()}>
                  {searching ? <Loader2 className="animate-spin" size={16}/> : 'Find'}
                </button>
              </form>
              {query.trim() && !searching && searchResults.length === 0 && (
                <p className="mt-4 text-sm text-fg-muted">Search to see matching customer profiles.</p>
              )}
              {searchResults.length > 0 && (
                <div className="mt-3 divide-y divide-bg-border overflow-hidden rounded-xl border border-bg-border">
                  {searchResults.map((customer) => (
                    <button
                      key={customer.id}
                      type="button"
                      className="flex min-h-[52px] w-full items-center justify-between gap-3 bg-bg-raised/50 px-3 py-2 text-left hover:bg-bg-raised"
                      onClick={() => { void selectCustomer(customer); }}
                    >
                      <span className="min-w-0">
                        <span className="block truncate font-medium">{customer.name || 'No name'}</span>
                        <span className="block text-xs text-fg-muted">{customer.phone}</span>
                      </span>
                      <span className="text-xs text-fg-muted">Select</span>
                    </button>
                  ))}
                </div>
              )}
            </div>

            <CustomerMembershipPanel
              customer={selectedCustomer}
              subscription={subscription}
              history={history}
              loading={customerLoading}
              canManage={canManage}
              hasOpenShift={Boolean(currentShift && currentShiftManageable)}
              hasOutstandingTask={selectedHasOutstandingTask}
              uncertainCustomerAction={uncertainCustomerMutation?.customerId === selectedCustomer?.id}
              onChange={() => {
                setSelectedCustomer(null); setSubscription(null); setHistory([]);
              }}
              onCancel={(item) => setDialog({ kind: 'cancel', subscription: item })}
              onRefund={(item) => setDialog({ kind: 'refund', subscription: item })}
            />
          </section>

          <section className="card">
            <div className="mb-4">
              <h3 className="font-semibold">Membership plans</h3>
              <p className="mt-1 text-sm text-fg-muted">Monthly terms only. Annual collection stays unavailable until deferred revenue accounting is supported.</p>
            </div>
            {data.tiers.length === 0 ? (
              <div className="rounded-xl border border-dashed border-bg-border p-8 text-center text-sm text-fg-muted">
                No membership plans are configured. A protected owner can create one in Settings → Memberships.
              </div>
            ) : (
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                {data.tiers.map((tier) => (
                  <TierCard
                    key={tier.id}
                    tier={tier}
                    canPrepare={Boolean(canManage && !prepareDisabledReason)}
                    disabledReason={canManage ? prepareDisabledReason : 'View only for this account.'}
                    onPrepare={() => setDialog({ kind: 'prepare', tier })}
                  />
                ))}
              </div>
            )}
          </section>
        </>
      )}

      {dialog?.kind === 'prepare' && selectedCustomer && currentShift && (
        <PreparePaymentModal
          customer={selectedCustomer}
          tier={dialog.tier}
          busy={busyKey === 'prepare'}
          onClose={() => { if (!busyKey) setDialog(null); }}
          onSubmit={(method) => { void preparePayment(dialog.tier, method); }}
        />
      )}
      {dialog?.kind === 'cancel' && (
        <CancelMembershipModal
          subscription={dialog.subscription}
          busy={busyKey === 'cancel'}
          onClose={() => { if (!busyKey) setDialog(null); }}
          onConfirm={async () => {
            if (!acquireOperation('cancel')) return;
            setActionError(null);
            try {
              const result = await memberships.cancel(dialog.subscription.id, makeMembershipActionId('cancel'));
              setSubscription(result.is_active ? result : null);
              setHistory((rows) => rows.map((row) => (row.id === result.id ? result : row)));
              setDialog(null);
              notifications.success('Future renewal is stopped. Paid benefits remain through the current term.', { title: 'Renewal stopped' });
            } catch (error) {
              if (isDefiniteMembershipRejection(error)) setActionError((error as Error).message);
              else setActionError('Cancellation outcome is unknown. The browser saved no offline copy. Refresh this customer before retrying.');
              if (!isDefiniteMembershipRejection(error)) {
                setUncertainCustomerMutation({ kind: 'cancel', customerId: dialog.subscription.customer_id });
              }
              setDialog(null);
            } finally { releaseOperation(); }
          }}
        />
      )}
      {dialog?.kind === 'refund' && currentShift && (
        <AcceptRefundModal
          subscription={dialog.subscription}
          busy={busyKey === 'refund-accept'}
          onClose={() => { if (!busyKey) setDialog(null); }}
          onSubmit={async (method, reason) => {
            if (!acquireOperation('refund-accept')) return;
            setActionError(null);
            const actionId = makeMembershipActionId('refund-accept');
            try {
              const task = await memberships.refund(dialog.subscription.id, {
                shift_id: currentShift.id,
                expected_amount_minor: dialog.subscription.amount_paid_minor,
                method,
                reason,
              }, actionId);
              setData((current) => ({ ...current, refundTasks: [task, ...current.refundTasks] }));
              setSubscription(null);
              setHistory((rows) => rows.map((row) => (
                row.id === dialog.subscription.id
                  ? { ...row, revoked_at: task.accepted_at, refund_id: task.id, refund_status: task.status, is_active: false }
                  : row
              )));
              setDialog(null);
              notifications.warning('Refund accepted and benefits held. No payout has moved. Start the task before paying the customer.', { title: 'Refund reserved' });
            } catch (error) {
              if (isDefiniteMembershipRejection(error)) setActionError((error as Error).message);
              else setActionError('Refund acceptance outcome is unknown. The browser saved no offline copy. Do not pay the customer or retry; refresh tasks first.');
              if (!isDefiniteMembershipRejection(error)) {
                setUncertainCustomerMutation({ kind: 'refund', customerId: dialog.subscription.customer_id });
              }
              setDialog(null);
            } finally { releaseOperation(); }
          }}
        />
      )}
      {dialog?.kind === 'payment_complete' && (
        <CompletePaymentModal
          task={dialog.task}
          currentUserId={me?.user_id ?? null}
          busy={busyKey === `payment:${dialog.task.id}`}
          onClose={() => { if (!busyKey) setDialog(null); }}
          onSubmit={async (reference, takeoverReason) => {
            const occurredAt = new Date().toISOString();
            const takeover = Boolean(dialog.task.action_started_by && dialog.task.action_started_by !== me?.user_id);
            const result = await runPaymentMutation(dialog.task, 'Recording completed payment', () => memberships.settlePayment(
              dialog.task,
              {
                collected_at: occurredAt,
                payment_received: true,
                ...(reference ? { external_reference: reference } : {}),
                ...(takeover ? { action_takeover_confirmed: true, action_takeover_reason: takeoverReason } : {}),
              },
              makeMembershipActionId('payment-complete'),
            ));
            setDialog(null);
            if (result?.status === 'payment_completed_pending_posting') {
              notifications.warning('Payment evidence is saved. Do not collect again. Post the receipt from the recovered task.', { title: 'Accounting still pending' });
            }
          }}
        />
      )}
      {dialog?.kind === 'payment_withdraw' && (
        <WithdrawPaymentModal
          task={dialog.task}
          currentUserId={me?.user_id ?? null}
          busy={busyKey === `payment:${dialog.task.id}`}
          onClose={() => { if (!busyKey) setDialog(null); }}
          onSubmit={async (body) => {
            const result = await runPaymentMutation(dialog.task, 'Resolving membership payment', () => memberships.withdrawPayment(
              dialog.task.id,
              body,
              makeMembershipActionId('payment-withdraw'),
            ));
            setDialog(null);
            if (result?.status === 'withdrawn') notifications.success('The payment task was resolved with its audit evidence.', { title: 'Payment task withdrawn' });
          }}
        />
      )}
      {dialog?.kind === 'refund_complete' && (
        <CompleteRefundModal
          task={dialog.task}
          currentUserId={me?.user_id ?? null}
          busy={busyKey === `refund:${dialog.task.id}`}
          onClose={() => { if (!busyKey) setDialog(null); }}
          onSubmit={async (reference, takeoverReason) => {
            const occurredAt = new Date().toISOString();
            const takeover = Boolean(dialog.task.action_started_by && dialog.task.action_started_by !== me?.user_id);
            const result = await runRefundMutation(dialog.task, 'Recording completed refund', () => (
              dialog.task.method === 'cash'
                ? memberships.settleCashRefund(dialog.task.id, {
                    shift_id: dialog.task.shift_id,
                    expected_amount_minor: dialog.task.amount_minor,
                    settled_at: occurredAt,
                    cash_handed_over: true,
                    ...(takeover ? { action_takeover_confirmed: true, action_takeover_reason: takeoverReason } : {}),
                  }, makeMembershipActionId('refund-complete-cash'))
                : memberships.settleProviderRefund(dialog.task, {
                    settled_at: occurredAt,
                    provider_refund_completed: true,
                    external_reference: reference,
                    ...(takeover ? { action_takeover_confirmed: true, action_takeover_reason: takeoverReason } : {}),
                  }, makeMembershipActionId('refund-complete-provider'))
            ));
            setDialog(null);
            if (result?.status === 'payout_completed_pending_posting') {
              notifications.warning('Payout evidence is saved. Do not pay again. Post the refund receipt from the recovered task.', { title: 'Accounting still pending' });
            }
          }}
        />
      )}
      {dialog?.kind === 'refund_withdraw' && (
        <WithdrawRefundModal
          task={dialog.task}
          currentUserId={me?.user_id ?? null}
          busy={busyKey === `refund:${dialog.task.id}`}
          onClose={() => { if (!busyKey) setDialog(null); }}
          onSubmit={async (body) => {
            const result = await runRefundMutation(dialog.task, 'Resolving membership refund', () => memberships.withdrawRefund(
              dialog.task.id,
              body,
              makeMembershipActionId('refund-withdraw'),
            ));
            setDialog(null);
            if (result?.status === 'withdrawn') notifications.success('The refund task was resolved with its audit evidence.', { title: 'Refund task withdrawn' });
          }}
        />
      )}
    </div>
  );
}

function SummaryCard({
  label, value, detail, Icon, warning = false,
}: {
  label: string; value: string; detail: string; Icon: typeof Sparkles; warning?: boolean;
}) {
  return (
    <div className={`card !p-4 ${warning ? 'border-accent-gold/35' : ''}`}>
      <div className="flex items-start justify-between gap-3">
        <div><p className="text-xs text-fg-muted">{label}</p><p className="mt-1 font-mono text-2xl font-bold">{value}</p></div>
        <div className={`rounded-xl p-2 ${warning ? 'bg-accent-gold/10 text-accent-gold' : 'bg-bg-raised text-fg-muted'}`}><Icon size={18}/></div>
      </div>
      <p className="mt-2 truncate text-xs text-fg-muted">{detail}</p>
    </div>
  );
}

function ShiftContextCard({
  resolution, shift, terminalIssue,
}: { resolution: ShiftResolution | null; shift: ShiftDTO | null; terminalIssue: string | null }) {
  if (shift) {
    return (
      <div className="card flex flex-wrap items-center justify-between gap-3 !p-4 border-accent-good/30 bg-accent-good/5">
        <div className="flex items-center gap-3">
          <ShieldCheck className="text-accent-good" size={20}/>
          <div><p className="font-semibold">Shift ready for membership money</p><p className="text-xs text-fg-muted">Opened by {shift.opened_by_name || shift.opened_by} · {formatDate(shift.opened_at)}</p></div>
        </div>
        <span className="chip border-accent-good/40 text-accent-good">Open</span>
      </div>
    );
  }
  return (
    <div className="card flex items-start gap-3 border-accent-gold/40 bg-accent-gold/10">
      <AlertTriangle className="mt-0.5 shrink-0 text-accent-gold" size={19}/>
      <div><p className="font-semibold text-accent-gold">Membership money is locked</p><p className="mt-1 text-sm text-fg-muted">{resolution ? shiftResolutionMessage(resolution) : terminalIssue || 'Select a terminal and open its shift.'}</p></div>
    </div>
  );
}

function PaymentTaskCard({
  task, busy, uncertain, currentShiftId, disabledReason, onAction,
}: {
  task: MembershipPaymentTaskDTO; busy: boolean; uncertain: boolean; currentShiftId: string | null;
  disabledReason: string | null; onAction: (action: MembershipMoneyAction) => void;
}) {
  const presentation = PAYMENT_STATUS[task.status];
  const actions = paymentTaskActions(task, { online: disabledReason === null, currentShiftId, uncertain });
  return (
    <article className="rounded-2xl border border-bg-border bg-bg-raised/50 p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div><h4 className="font-semibold">{task.customer_name || task.customer_phone}</h4><p className="text-xs text-fg-muted">{task.tier_name} · prepared by {task.prepared_by_name || task.prepared_by}</p></div>
        <span className={`chip ${toneClass(presentation.tone)}`}>{presentation.label}</span>
      </div>
      <div className="mt-3 flex flex-wrap items-baseline gap-x-3 gap-y-1"><span className="font-mono text-xl font-bold">{inr(task.amount_minor)}</span><span className="text-xs text-fg-muted">{railLabel(task.paid_via)} · {formatDate(task.accepted_at)}</span></div>
      <p className="mt-2 text-sm text-fg-muted">{presentation.detail}</p>
      {(task.evidence_time_untrusted || !task.provider_evidence_reconciled || (task.status === 'payment_completed_pending_posting' && !task.customer_spend_reconciled)) && (
        <p className="mt-2 text-xs text-accent-bad">Evidence or customer-spend reconciliation is incomplete. Keep this task open and do not collect again.</p>
      )}
      <div className="mt-4"><MembershipTaskControls actions={actions} rail={task.paid_via} kind="payment" busy={busy} disabledReason={disabledReason} onAction={onAction}/></div>
    </article>
  );
}

function RefundTaskCard({
  task, busy, uncertain, currentShiftId, disabledReason, onAction,
}: {
  task: MembershipRefundDTO; busy: boolean; uncertain: boolean; currentShiftId: string | null;
  disabledReason: string | null; onAction: (action: MembershipMoneyAction) => void;
}) {
  const presentation = REFUND_STATUS[task.status];
  const actions = refundTaskActions(task, { online: disabledReason === null, currentShiftId, uncertain });
  return (
    <article className="rounded-2xl border border-bg-border bg-bg-raised/50 p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div><h4 className="font-semibold">{task.customer_name || task.customer_phone || 'Customer'}</h4><p className="text-xs text-fg-muted">{task.tier_name || 'Membership'} · accepted by {task.accepted_by_name || 'protected owner'}</p></div>
        <span className={`chip ${toneClass(presentation.tone)}`}>{presentation.label}</span>
      </div>
      <div className="mt-3 flex flex-wrap items-baseline gap-x-3 gap-y-1"><span className="font-mono text-xl font-bold">{inr(task.amount_minor)}</span><span className="text-xs text-fg-muted">{railLabel(task.method)} · {formatDate(task.accepted_at)}</span></div>
      <p className="mt-2 text-sm text-fg-muted">{presentation.detail}</p>
      {(task.evidence_time_untrusted || !task.provider_evidence_reconciled || !task.customer_spend_reconciled) && (
        <p className="mt-2 text-xs text-accent-bad">Evidence or customer-spend reconciliation is incomplete. Keep this task open and do not repeat the payout.</p>
      )}
      <div className="mt-4"><MembershipTaskControls actions={actions} rail={task.method} kind="refund" busy={busy} disabledReason={disabledReason} onAction={onAction}/></div>
    </article>
  );
}

function CustomerMembershipPanel({
  customer, subscription, history, loading, canManage, hasOpenShift, hasOutstandingTask,
  uncertainCustomerAction,
  onChange, onCancel, onRefund,
}: {
  customer: CustomerDTO | null; subscription: SubscriptionDTO | null; history: SubscriptionDTO[]; loading: boolean;
  canManage: boolean; hasOpenShift: boolean; hasOutstandingTask: boolean; onChange: () => void;
  uncertainCustomerAction: boolean;
  onCancel: (subscription: SubscriptionDTO) => void; onRefund: (subscription: SubscriptionDTO) => void;
}) {
  if (!customer) {
    return (
      <div className="card flex min-h-[15rem] flex-col items-center justify-center text-center">
        <UserRound className="mb-3 text-fg-muted" size={30}/><h3 className="font-semibold">No customer selected</h3>
        <p className="mt-1 max-w-md text-sm text-fg-muted">Search by name or phone to review the current membership, payment receipt, refund state, and full term history.</p>
      </div>
    );
  }
  return (
    <div className="card">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-bg-border pb-4">
        <div><h3 className="text-lg font-semibold">{customer.name || 'No name'}</h3><p className="text-sm text-fg-muted">{customer.phone}</p></div>
        <button className="btn btn-ghost !min-h-[40px] !px-3 !py-2" onClick={onChange}>Change customer</button>
      </div>
      {loading ? <div className="pt-4"><SkeletonCard lines={3}/></div> : (
        <div className="space-y-4 pt-4">
          {subscription ? (
            <div className="rounded-xl border border-accent-good/30 bg-accent-good/5 p-4">
              <div className="flex flex-wrap items-start justify-between gap-2"><div><p className="text-xs text-fg-muted">Current plan</p><h4 className="text-lg font-semibold">{subscription.tier_name}</h4></div><span className="chip border-accent-good/40 text-accent-good">Active</span></div>
              <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
                <Info label="Paid" value={inr(subscription.amount_paid_minor)}/><Info label="Payment" value={railLabel(subscription.payment_method)}/>
                <Info label="Valid until" value={formatDate(subscription.expires_at)}/><Info label="Receipt" value={subscription.payment_receipt_no || 'Pending evidence'}/>
                <Info label="Renewal" value={subscription.auto_renew ? 'Enabled' : 'Stopped'}/><Info label="Started" value={formatDate(subscription.starts_at)}/>
              </dl>
              {(!subscription.payment_provider_evidence_reconciled || subscription.payment_evidence_time_untrusted) && <p className="mt-3 text-xs text-accent-bad">Payment evidence needs owner reconciliation. Do not issue a refund until the source payment is verified.</p>}
              {canManage && !hasOutstandingTask && (
                <div className="mt-4 flex flex-wrap gap-2">
                  {subscription.auto_renew && <button className="btn btn-ghost" disabled={uncertainCustomerAction} onClick={() => onCancel(subscription)}>Stop renewal</button>}
                  <button
                    className="btn btn-danger"
                    disabled={
                      !hasOpenShift
                      || !subscription.payment_id
                      || !subscription.payment_provider_evidence_reconciled
                      || subscription.payment_evidence_time_untrusted
                      || uncertainCustomerAction
                    }
                    onClick={() => onRefund(subscription)}
                  >
                    Start full refund
                  </button>
                </div>
              )}
              {canManage && !hasOpenShift && <p className="mt-2 text-xs text-fg-muted">Open this terminal’s shift before refunding.</p>}
              {hasOutstandingTask && <p className="mt-3 text-xs text-accent-gold">Resolve this customer’s existing payment or refund task before starting another.</p>}
              {uncertainCustomerAction && <p className="mt-3 text-xs text-accent-bad">The previous customer action has an unknown outcome. Refresh before cancelling, refunding, or preparing payment again.</p>}
            </div>
          ) : (
            <div className="rounded-xl border border-dashed border-bg-border p-5 text-center"><CheckCircle2 className="mx-auto mb-2 text-fg-muted" size={24}/><p className="font-medium">No active membership</p><p className="mt-1 text-sm text-fg-muted">Choose a plan below to prepare a payment task. Preparation itself does not move money.</p></div>
          )}
          <div>
            <div className="mb-2 flex items-center gap-2"><History size={16} className="text-fg-muted"/><h4 className="font-semibold">Term history</h4></div>
            {history.length === 0 ? <p className="text-sm text-fg-muted">No prior membership terms.</p> : (
              <div className="divide-y divide-bg-border overflow-hidden rounded-xl border border-bg-border">
                {history.map((item) => (
                  <div key={item.id} className="grid gap-2 bg-bg-raised/40 p-3 text-sm sm:grid-cols-[minmax(0,1fr)_auto]">
                    <div><p className="font-medium">{item.tier_name} · {inr(item.amount_paid_minor)}</p><p className="text-xs text-fg-muted">{formatDate(item.starts_at)} → {formatDate(item.expires_at)}</p></div>
                    <div className="text-left text-xs text-fg-muted sm:text-right"><p>{item.refund_status ? `Refund: ${REFUND_STATUS[item.refund_status].label}` : item.revoked_at ? 'Revoked' : item.cancelled_at ? 'Renewal stopped' : item.is_active ? 'Active' : 'Ended'}</p><p>{item.payment_receipt_no || 'No receipt number'}</p></div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Info({ label, value }: { label: string; value: string }) {
  return <div><dt className="text-xs text-fg-muted">{label}</dt><dd className="mt-0.5 font-medium">{value}</dd></div>;
}

function TierCard({ tier, canPrepare, disabledReason, onPrepare }: {
  tier: MembershipTierDTO; canPrepare: boolean; disabledReason: string | null; onPrepare: () => void;
}) {
  const benefits = [
    tier.food_discount_pct > 0 ? `${tier.food_discount_pct * 100}% food discount` : null,
    tier.gaming_discount_pct > 0 ? `${tier.gaming_discount_pct * 100}% gaming discount` : null,
    tier.hookah_discount_pct > 0 ? `${tier.hookah_discount_pct * 100}% shisha discount` : null,
    tier.free_gaming_minutes_per_week > 0 ? `${tier.free_gaming_minutes_per_week} free gaming min/week` : null,
    tier.free_hookah_per_month > 0 ? `${tier.free_hookah_per_month} free shisha/month` : null,
    tier.point_multiplier > 1 ? `${tier.point_multiplier}× loyalty points` : null,
  ].filter(Boolean);
  return (
    <article className="rounded-2xl border border-bg-border bg-bg-raised/50 p-4">
      <div className="flex items-start justify-between gap-3"><div><p className="text-xs font-semibold uppercase tracking-wider text-accent-gold">{tier.code}</p><h4 className="mt-1 text-lg font-semibold">{tier.name}</h4></div><p className="font-mono text-lg font-bold">{inr(tier.monthly_price_minor)}<span className="text-xs font-normal text-fg-muted">/month</span></p></div>
      <p className="mt-2 min-h-[2.5rem] text-sm text-fg-muted">{tier.description || 'Monthly D Club benefits.'}</p>
      <ul className="mt-3 min-h-[3rem] space-y-1 text-xs text-fg-muted">{benefits.length ? benefits.slice(0, 3).map((benefit) => <li key={benefit}>• {benefit}</li>) : <li>• Standard membership benefits</li>}</ul>
      <button className="btn btn-primary mt-4 w-full" disabled={!canPrepare} onClick={onPrepare}>Prepare payment</button>
      {!canPrepare && disabledReason && <p className="mt-2 text-center text-xs text-fg-muted">{disabledReason}</p>}
    </article>
  );
}

function PreparePaymentModal({ customer, tier, busy, onClose, onSubmit }: {
  customer: CustomerDTO; tier: MembershipTierDTO; busy: boolean; onClose: () => void;
  onSubmit: (method: 'cash' | 'card' | 'upi') => void;
}) {
  const [method, setMethod] = useState<'cash' | 'card' | 'upi'>('cash');
  return (
    <Modal open onClose={onClose} title="Prepare membership payment" size="sm">
      <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); onSubmit(method); }}>
        <div className="rounded-xl border border-bg-border bg-bg-raised/50 p-3 text-sm"><p className="font-semibold">{customer.name || customer.phone}</p><p className="text-fg-muted">{tier.name} · {inr(tier.monthly_price_minor)} monthly</p></div>
        <label className="block"><span className="text-xs text-fg-muted">Payment method</span><select className="input mt-1" value={method} onChange={(event) => setMethod(event.target.value as 'cash' | 'card' | 'upi')}><option value="cash">Cash</option><option value="upi">UPI</option><option value="card">Card</option></select></label>
        <div className="rounded-xl border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm text-fg-muted"><strong className="text-accent-gold">Preparation does not collect money.</strong> After server acceptance, open the recovered task and start its collection step.</div>
        <div className="flex justify-end gap-2"><button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>Cancel</button><button className="btn btn-primary" disabled={busy}>{busy && <Loader2 className="animate-spin" size={16}/>}Prepare only</button></div>
      </form>
    </Modal>
  );
}

function CancelMembershipModal({ subscription, busy, onClose, onConfirm }: { subscription: SubscriptionDTO; busy: boolean; onClose: () => void; onConfirm: () => void }) {
  return (
    <Modal open onClose={onClose} title="Stop future renewal" size="sm">
      <p className="text-sm text-fg-muted">This does not refund the payment or end benefits. {subscription.tier_name} remains valid until {formatDate(subscription.expires_at)}.</p>
      <div className="mt-4 flex justify-end gap-2"><button className="btn btn-ghost" disabled={busy} onClick={onClose}>Keep renewal</button><button className="btn btn-primary" disabled={busy} onClick={onConfirm}>{busy && <Loader2 className="animate-spin" size={16}/>}Stop renewal</button></div>
    </Modal>
  );
}

function AcceptRefundModal({ subscription, busy, onClose, onSubmit }: {
  subscription: SubscriptionDTO; busy: boolean; onClose: () => void;
  onSubmit: (method: 'cash' | 'card' | 'upi', reason: string) => void;
}) {
  const [method, setMethod] = useState<'cash' | 'card' | 'upi'>(
    subscription.payment_method === 'card' || subscription.payment_method === 'upi' ? subscription.payment_method : 'cash',
  );
  const [reason, setReason] = useState('');
  const valid = reason.trim().length >= 3;
  return (
    <Modal open onClose={onClose} title="Reserve full membership refund" size="sm">
      <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); if (valid) onSubmit(method, reason.trim()); }}>
        <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm"><p className="font-semibold text-accent-bad">Full refund · {inr(subscription.amount_paid_minor)}</p><p className="mt-1 text-fg-muted">Acceptance immediately holds benefits. It does not authorise cash/provider payout until the next server-owned step.</p></div>
        <label className="block"><span className="text-xs text-fg-muted">Refund method</span><select className="input mt-1" value={method} onChange={(event) => setMethod(event.target.value as 'cash' | 'card' | 'upi')}><option value="cash">Cash</option><option value="upi">UPI</option><option value="card">Card</option></select></label>
        <label className="block"><span className="text-xs text-fg-muted">Reason</span><textarea className="input mt-1 min-h-24" maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} placeholder="Why is this full term being refunded?"/></label>
        <div className="flex justify-end gap-2"><button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>Cancel</button><button className="btn btn-danger" disabled={busy || !valid}>{busy && <Loader2 className="animate-spin" size={16}/>}Reserve refund</button></div>
      </form>
    </Modal>
  );
}

function CompletePaymentModal({ task, currentUserId, busy, onClose, onSubmit }: {
  task: MembershipPaymentTaskDTO; currentUserId: string | null; busy: boolean; onClose: () => void;
  onSubmit: (reference: string, takeoverReason: string) => void;
}) {
  const [reference, setReference] = useState('');
  const [takeoverReason, setTakeoverReason] = useState('');
  const takeover = Boolean(task.action_started_by && task.action_started_by !== currentUserId);
  const valid = (task.paid_via === 'cash' || reference.trim().length >= 3) && (!takeover || takeoverReason.trim().length >= 3);
  return (
    <Modal open onClose={onClose} title={task.paid_via === 'cash' ? 'Confirm cash received' : 'Confirm provider payment'} size="sm">
      <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); if (valid) onSubmit(reference.trim(), takeoverReason.trim()); }}>
        <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-fg-muted"><strong className="text-accent-bad">Confirm only after exactly {inr(task.amount_minor)} was received.</strong> If this task was recovered after a timeout, verify the drawer/customer or provider first. Never collect again merely because the button reappeared.</div>
        {task.paid_via !== 'cash' && <label className="block"><span className="text-xs text-fg-muted">Completed {task.paid_via.toUpperCase()} reference</span><input className="input mt-1" value={reference} onChange={(event) => setReference(event.target.value)} maxLength={200}/></label>}
        {takeover && <label className="block"><span className="text-xs text-fg-muted">Why are you taking over from {task.action_started_by_name || 'another owner'}?</span><textarea className="input mt-1 min-h-20" value={takeoverReason} onChange={(event) => setTakeoverReason(event.target.value)} maxLength={500}/></label>}
        <div className="flex justify-end gap-2"><button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>Back</button><button className="btn btn-primary" disabled={busy || !valid}>{busy && <Loader2 className="animate-spin" size={16}/>}Payment received</button></div>
      </form>
    </Modal>
  );
}

function WithdrawPaymentModal({ task, currentUserId, busy, onClose, onSubmit }: {
  task: MembershipPaymentTaskDTO; currentUserId: string | null; busy: boolean; onClose: () => void;
  onSubmit: (body: Parameters<typeof memberships.withdrawPayment>[1]) => void;
}) {
  const noAction = task.status === 'accepted_payment_due';
  const cash = task.paid_via === 'cash';
  const options = noAction
    ? [['payment_not_collected', 'No payment was collected']]
    : task.status === 'payment_completed_pending_posting'
      ? task.paid_via === 'cash'
        ? [['cash_returned', 'Collected cash was returned']]
        : [['provider_reversed', 'Provider payment was reversed']]
    : cash
      ? [['cash_not_collected', 'Cash was not collected'], ['cash_returned', 'Collected cash was returned']]
      : [['provider_not_completed', 'Provider payment did not complete'], ['provider_reversed', 'Provider payment was reversed']];
  const [resolution, setResolution] = useState(options[0][0]);
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [takeoverReason, setTakeoverReason] = useState('');
  const takeover = Boolean(task.action_started_by && task.action_started_by !== currentUserId);
  const providerEvidence = !noAction && !cash;
  const valid = reason.trim().length >= 3 && (!providerEvidence || reference.trim().length >= 3) && (!takeover || takeoverReason.trim().length >= 3);
  return (
    <Modal open onClose={onClose} title={noAction ? 'Withdraw uncollected payment' : 'Resolve interrupted payment'} size="sm">
      <form className="space-y-4" onSubmit={(event) => {
        event.preventDefault(); if (!valid) return;
        const reversed = resolution === 'provider_reversed';
        onSubmit({
          shift_id: task.shift_id,
          expected_amount_minor: task.amount_minor,
          resolution: resolution as Parameters<typeof memberships.withdrawPayment>[1]['resolution'],
          reason: reason.trim(),
          ...(reversed ? { external_reference: reference.trim() } : {}),
          ...(!noAction ? { action_state_verified: true } : {}),
          ...(providerEvidence ? {
            provider_verification_status: reversed ? 'reversed' : 'not_completed',
            provider_verification_reference: reference.trim(),
            provider_evidence_occurred_at: new Date().toISOString(),
          } : {}),
          ...(resolution === 'cash_returned' ? { cash_return_confirmed: true } : {}),
          ...(takeover ? { action_takeover_confirmed: true, action_takeover_reason: takeoverReason.trim() } : {}),
        });
      }}>
        <label className="block"><span className="text-xs text-fg-muted">Verified outcome</span><select className="input mt-1" value={resolution} onChange={(event) => setResolution(event.target.value)}>{options.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        {providerEvidence && <label className="block"><span className="text-xs text-fg-muted">Provider verification/reversal reference</span><input className="input mt-1" value={reference} onChange={(event) => setReference(event.target.value)} maxLength={200}/></label>}
        <label className="block"><span className="text-xs text-fg-muted">Reason and checks performed</span><textarea className="input mt-1 min-h-24" value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500}/></label>
        {takeover && <label className="block"><span className="text-xs text-fg-muted">Takeover reason</span><textarea className="input mt-1 min-h-20" value={takeoverReason} onChange={(event) => setTakeoverReason(event.target.value)} maxLength={500}/></label>}
        <div className="flex justify-end gap-2"><button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>Back</button><button className="btn btn-danger" disabled={busy || !valid}>{busy && <Loader2 className="animate-spin" size={16}/>}Save verified outcome</button></div>
      </form>
    </Modal>
  );
}

function CompleteRefundModal({ task, currentUserId, busy, onClose, onSubmit }: {
  task: MembershipRefundDTO; currentUserId: string | null; busy: boolean; onClose: () => void;
  onSubmit: (reference: string, takeoverReason: string) => void;
}) {
  const [reference, setReference] = useState('');
  const [takeoverReason, setTakeoverReason] = useState('');
  const takeover = Boolean(task.action_started_by && task.action_started_by !== currentUserId);
  const valid = (task.method === 'cash' || reference.trim().length >= 3) && (!takeover || takeoverReason.trim().length >= 3);
  return (
    <Modal open onClose={onClose} title={task.method === 'cash' ? 'Confirm cash handed over' : 'Confirm provider refund'} size="sm">
      <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); if (valid) onSubmit(reference.trim(), takeoverReason.trim()); }}>
        <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-fg-muted"><strong className="text-accent-bad">Confirm only after exactly {inr(task.amount_minor)} reached the customer.</strong> If this task was recovered after a timeout, verify the customer/drawer or provider first. Never pay again merely because the button reappeared.</div>
        {task.method !== 'cash' && <label className="block"><span className="text-xs text-fg-muted">Completed {task.method.toUpperCase()} refund reference</span><input className="input mt-1" value={reference} onChange={(event) => setReference(event.target.value)} maxLength={200}/></label>}
        {takeover && <label className="block"><span className="text-xs text-fg-muted">Why are you taking over from {task.action_started_by_name || 'another owner'}?</span><textarea className="input mt-1 min-h-20" value={takeoverReason} onChange={(event) => setTakeoverReason(event.target.value)} maxLength={500}/></label>}
        <div className="flex justify-end gap-2"><button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>Back</button><button className="btn btn-danger" disabled={busy || !valid}>{busy && <Loader2 className="animate-spin" size={16}/>}Customer received refund</button></div>
      </form>
    </Modal>
  );
}

function WithdrawRefundModal({ task, currentUserId, busy, onClose, onSubmit }: {
  task: MembershipRefundDTO; currentUserId: string | null; busy: boolean; onClose: () => void;
  onSubmit: (body: MembershipRefundWithdrawalDTO) => void;
}) {
  const noAction = task.status === 'accepted_cash_due' || task.status === 'accepted_provider_due';
  const cash = task.method === 'cash';
  const options = noAction
    ? cash
      ? [['cash_not_handed_over', 'No cash was handed over']]
      : [['provider_not_completed', 'Provider refund was not started']]
    : task.status === 'payout_completed_pending_posting'
      ? cash
        ? [['cash_returned', 'Cash was recovered/returned']]
        : [['provider_reversed', 'Provider refund was reversed']]
      : cash
        ? [['cash_not_handed_over', 'No cash was handed over'], ['cash_returned', 'Cash was recovered/returned']]
        : [['provider_not_completed', 'Provider refund did not complete'], ['provider_reversed', 'Provider refund was reversed']];
  const [resolution, setResolution] = useState(options[0][0]);
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [takeoverReason, setTakeoverReason] = useState('');
  const takeover = Boolean(task.action_started_by && task.action_started_by !== currentUserId);
  const providerEvidence = !cash && !noAction;
  const valid = reason.trim().length >= 3 && (!providerEvidence || reference.trim().length >= 3) && (!takeover || takeoverReason.trim().length >= 3);
  return (
    <Modal open onClose={onClose} title={noAction ? 'Withdraw unstarted refund' : 'Resolve interrupted payout'} size="sm">
      <form className="space-y-4" onSubmit={(event) => {
        event.preventDefault(); if (!valid) return;
        const reversed = resolution === 'provider_reversed';
        onSubmit({
          shift_id: task.shift_id,
          expected_amount_minor: task.amount_minor,
          resolution: resolution as MembershipRefundWithdrawalDTO['resolution'],
          reason: reason.trim(),
          ...(reversed ? { external_reference: reference.trim() } : {}),
          ...(!noAction ? { action_state_verified: true } : {}),
          ...(providerEvidence ? {
            provider_verification_status: reversed ? 'reversed' : 'not_completed',
            provider_verification_reference: reference.trim(),
            provider_evidence_occurred_at: new Date().toISOString(),
          } : {}),
          ...(resolution === 'cash_returned' ? { cash_return_confirmed: true } : {}),
          ...(takeover ? { action_takeover_confirmed: true, action_takeover_reason: takeoverReason.trim() } : {}),
        });
      }}>
        <label className="block"><span className="text-xs text-fg-muted">Verified outcome</span><select className="input mt-1" value={resolution} onChange={(event) => setResolution(event.target.value)}>{options.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        {providerEvidence && <label className="block"><span className="text-xs text-fg-muted">Provider verification/reversal reference</span><input className="input mt-1" value={reference} onChange={(event) => setReference(event.target.value)} maxLength={200}/></label>}
        <label className="block"><span className="text-xs text-fg-muted">Reason and checks performed</span><textarea className="input mt-1 min-h-24" value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500}/></label>
        {takeover && <label className="block"><span className="text-xs text-fg-muted">Takeover reason</span><textarea className="input mt-1 min-h-20" value={takeoverReason} onChange={(event) => setTakeoverReason(event.target.value)} maxLength={500}/></label>}
        <div className="flex justify-end gap-2"><button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>Back</button><button className="btn btn-danger" disabled={busy || !valid}>{busy && <Loader2 className="animate-spin" size={16}/>}Save verified outcome</button></div>
      </form>
    </Modal>
  );
}
