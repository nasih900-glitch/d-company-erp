import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  AlertCircle,
  AlertTriangle,
  Banknote,
  CheckCircle2,
  History,
  Loader2,
  ReceiptText,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldAlert,
  WifiOff,
} from 'lucide-react';

import Modal from '@/components/ui/Modal';
import { SkeletonCard } from '@/components/ui/Skeleton';
import { useNotifications } from '@/components/ui/Notifications';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { hasAdminSystemAccess } from '@/lib/admin-access';
import { LIVE_MODE } from '@/lib/demo';
import {
  orders,
  refunds,
  shifts,
  type OrderDTO,
  type OrderListItemDTO,
  type PosRefundRequestDTO,
  type ShiftDTO,
} from '@/lib/erp-api';
import { inr } from '@/lib/inr';
import { parseRupeesToMinor } from '@/lib/money-input';
import {
  clearStoredShift,
  readStoredShiftId,
  resolveOpenShift,
  shiftResolutionMessage,
  storeShiftId,
  type ShiftResolution,
} from '@/lib/operational-context';
import { subscribeRealtime } from '@/lib/realtime';
import { useAuth } from '@/modules/auth/AuthContext';
import { RefundTaskControls } from './RefundTaskControls';
import {
  allowedRefundActions,
  canAccessRefunds,
  makeRefundActionId,
  paymentMethodLabel,
  refundModeAllowed,
  refundRailPolicy,
  refundStatusPresentation,
  type RefundTaskAction,
} from './refund-policy';
import {
  assessRefundRecoveryCheckpoints,
  readRefundRecoveryCheckpoints,
  removeRefundRecoveryCheckpoint,
  saveRefundRecoveryCheckpoint,
  type RefundRecoveryAssessment,
  type RefundRecoveryCheckpoint,
  type RefundRecoveryScope,
} from './refund-recovery';

const REFUND_REASONS = [
  ['customer_unhappy', 'Customer unhappy'],
  ['wrong_item', 'Wrong item'],
  ['order_cancelled', 'Order cancelled'],
  ['billing_error', 'Billing error'],
  ['other', 'Other'],
] as const;

const COMPLETED_STATUSES = new Set(['settled', 'withdrawn']);

type ActionDialogState = {
  action: RefundTaskAction;
  task: PosRefundRequestDTO;
  recoveryCheckpoint: RefundRecoveryCheckpoint | null;
};

type ActionEvidence = {
  acknowledged: boolean;
  reason: string;
  externalReference: string;
  providerStatus: 'no_matching_transaction' | 'provider_declined' | 'provider_reversed';
  verificationReference: string;
};

type RefundData = {
  orderRows: OrderListItemDTO[];
  tasks: PosRefundRequestDTO[];
  recent: PosRefundRequestDTO[];
  openShifts: ShiftDTO[];
};

function actor(name: string | null, id: string | null): string | null {
  return name?.trim() || id || null;
}

function formatDate(value: string | null): string {
  if (!value) return '—';
  return new Date(value).toLocaleString('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}

function taskActionDisabledReason({
  task,
  online,
  uncertain,
  currentShift,
  canManageCurrentShift,
  currentUserId,
  protectedAccess,
  adminSystemAccess,
}: {
  task: PosRefundRequestDTO;
  online: boolean;
  uncertain: boolean;
  currentShift: ShiftDTO | null;
  canManageCurrentShift: boolean;
  currentUserId: string | null;
  protectedAccess: boolean;
  adminSystemAccess: boolean;
}): string | null {
  if (uncertain) {
    return 'The last money action did not return a definite result. Do not pay again. Recheck the server state first.';
  }
  if (!online) return 'Refund money actions are online-only. Reconnect before continuing.';
  if (!currentShift) return 'Open the exact shift for this terminal before continuing this refund.';
  if (currentShift.id !== task.shift_id) {
    return 'This refund belongs to a different shift. Return to its exact terminal and shift; do not move money here.';
  }
  if (!canManageCurrentShift) {
    return `This shift was opened by ${currentShift.opened_by_name || 'another employee'}. They or a protected owner must continue.`;
  }
  if (
    task.status === 'cash_handoff_in_progress'
    && task.handoff_started_by !== currentUserId
    && !protectedAccess
  ) {
    return `${actor(task.handoff_started_by_name, task.handoff_started_by) || 'Another employee'} started this cash handover. They must finish it.`;
  }
  if (
    task.status === 'provider_payout_in_progress'
    && task.provider_payout_started_by !== currentUserId
    && !protectedAccess
  ) {
    return `${actor(task.provider_payout_started_by_name, task.provider_payout_started_by) || 'Another employee'} started this provider payout. They must finish it.`;
  }
  if (task.status === 'provider_payout_in_progress' && protectedAccess && !adminSystemAccess) {
    return 'Record the provider completion if it succeeded. A verified failed-payout resolution is reserved for the designated protected system owner.';
  }
  return null;
}

export default function RefundsScreen() {
  const { me, demo, terminalId, terminalReady, terminalIssue } = useAuth();
  const notifications = useNotifications();
  const online = useOnlineStatus();
  const canAccess = Boolean(demo || canAccessRefunds(me));
  const [data, setData] = useState<RefundData>({
    orderRows: [], tasks: [], recent: [], openShifts: [],
  });
  const [shiftResolution, setShiftResolution] = useState<ShiftResolution | null>(null);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [selectedOrder, setSelectedOrder] = useState<OrderListItemDTO | null>(null);
  const [dialog, setDialog] = useState<ActionDialogState | null>(null);
  const [uncertainTaskIds, setUncertainTaskIds] = useState<Set<string>>(() => new Set());
  const [recoveryAssessments, setRecoveryAssessments] = useState<RefundRecoveryAssessment[]>([]);
  const [staleOrderIds, setStaleOrderIds] = useState<Set<string>>(() => new Set());
  const requestSequence = useRef(0);
  const recoveryRef = useRef<RefundRecoveryAssessment[]>([]);

  const load = useCallback(async ({
    initial = false,
  }: { initial?: boolean } = {}): Promise<boolean> => {
    if (!LIVE_MODE) {
      setLoading(false);
      return true;
    }
    if (!canAccess) {
      setLoading(false);
      return false;
    }
    if (!terminalReady || !terminalId || !me?.branch_id) {
      setLoadError(terminalIssue || 'Select this device’s shop terminal before opening Refunds.');
      setLoading(false);
      return false;
    }
    if (!online) {
      setLoadError('Refunds are online-only. Reconnect to load authoritative order and payout state.');
      setLoading(false);
      return false;
    }

    const sequence = ++requestSequence.current;
    if (initial) setLoading(true);
    else setRefreshing(true);
    setLoadError(null);
    try {
      // Independent reads start together so the operational board does not
      // waterfall through orders -> tasks -> history -> shift.
      const [orderRows, tasks, history, openShifts] = await Promise.all([
        refunds.listOrders(),
        refunds.listRequests({ unresolved: true, limit: 200 }),
        refunds.listRequests({ unresolved: false, limit: 100 }),
        shifts.list(true),
      ]);
      if (sequence !== requestSequence.current) return false;

      const scope = {
        companyId: me.company_id,
        branchId: me.branch_id,
        terminalId,
      };
      const resolved = resolveOpenShift({
        storedShiftId: readStoredShiftId(scope),
        branchId: me.branch_id,
        terminalId,
        openShifts,
      });
      if (resolved.kind === 'ready') storeShiftId(scope, resolved.shift.id);
      else clearStoredShift();

      setData({
        orderRows,
        tasks,
        recent: history.filter((task) => COMPLETED_STATUSES.has(task.status)).slice(0, 50),
        openShifts,
      });
      setShiftResolution(resolved);
      setStaleOrderIds(new Set());
      const recoveryScope: RefundRecoveryScope = {
        companyId: me.company_id,
        branchId: me.branch_id,
        terminalId,
      };
      // Merge disk evidence with this page's in-memory copy. If browser
      // storage was unavailable while the payout was recorded, the current
      // tab must still retain its safety lock.
      const checkpointByTask = new Map(
        readRefundRecoveryCheckpoints(recoveryScope)
          .map((checkpoint) => [checkpoint.taskId, checkpoint]),
      );
      for (const assessment of recoveryRef.current) {
        if (assessment.checkpoint.terminalId === terminalId) {
          checkpointByTask.set(assessment.checkpoint.taskId, assessment.checkpoint);
        }
      }
      const assessed = assessRefundRecoveryCheckpoints(
        [...checkpointByTask.values()],
        [...tasks, ...history],
      );
      for (const assessment of assessed) {
        if (assessment.state === 'recorded') {
          removeRefundRecoveryCheckpoint(recoveryScope, assessment.checkpoint.taskId);
        }
      }
      const activeRecovery = assessed.filter((assessment) => assessment.state !== 'recorded');
      recoveryRef.current = activeRecovery;
      setRecoveryAssessments(activeRecovery);
      setUncertainTaskIds(new Set(activeRecovery.map((assessment) => assessment.checkpoint.taskId)));
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
  }, [canAccess, me, online, terminalId, terminalIssue, terminalReady]);

  useEffect(() => { void load({ initial: true }); }, [load]);

  useEffect(() => {
    if (!LIVE_MODE || !canAccess || !terminalReady || !terminalId) return undefined;
    const refresh = () => { void load(); };
    const orderUnsubscribe = subscribeRealtime('orders', refresh);
    const shiftUnsubscribe = subscribeRealtime('shifts', refresh);
    return () => {
      orderUnsubscribe();
      shiftUnsubscribe();
    };
  }, [canAccess, load, terminalId, terminalReady]);

  const currentShift = shiftResolution?.kind === 'ready' ? shiftResolution.shift : null;
  const recoveryScope = me?.company_id && me.branch_id && terminalId
    ? { companyId: me.company_id, branchId: me.branch_id, terminalId }
    : null;
  const canManageCurrentShift = Boolean(
    currentShift
    && (currentShift.opened_by === me?.user_id || me?.protected_access),
  );
  const adminSystemAccess = hasAdminSystemAccess(me);
  const recoveryByTask = useMemo(
    () => new Map(recoveryAssessments.map((assessment) => [assessment.checkpoint.taskId, assessment])),
    [recoveryAssessments],
  );
  const unresolvedRecoveryConflicts = recoveryAssessments.filter(
    (assessment) => assessment.state === 'conflict' || assessment.state === 'missing',
  );
  const unresolvedOrderIds = useMemo(
    () => new Set(data.tasks.map((task) => task.order_id)),
    [data.tasks],
  );
  const orderById = useMemo(
    () => new Map(data.orderRows.map((order) => [order.id, order])),
    [data.orderRows],
  );
  const eligibleOrders = useMemo(() => {
    const cleanQuery = query.trim().toLowerCase();
    return data.orderRows.filter((order) => {
      if (
        Number(order.refundable_minor ?? 0) <= 0
        || unresolvedOrderIds.has(order.id)
        || staleOrderIds.has(order.id)
      ) return false;
      if (!cleanQuery) return true;
      return [
        order.invoice_no,
        order.customer_name,
        order.source_label,
        order.id,
      ].some((value) => value?.toLowerCase().includes(cleanQuery));
    });
  }, [data.orderRows, query, staleOrderIds, unresolvedOrderIds]);

  const pendingAmount = data.tasks.reduce((sum, task) => sum + task.amount_minor, 0);
  const settledRecentAmount = data.recent
    .filter((task) => task.status === 'settled')
    .reduce((sum, task) => sum + task.amount_minor, 0);

  function applyServerTask(result: PosRefundRequestDTO) {
    setStaleOrderIds((current) => new Set(current).add(result.order_id));
    if (COMPLETED_STATUSES.has(result.status)) {
      setData((current) => ({
        ...current,
        tasks: current.tasks.filter((task) => task.id !== result.id),
        recent: [result, ...current.recent.filter((task) => task.id !== result.id)].slice(0, 50),
      }));
      return;
    }
    setData((current) => ({
      ...current,
      tasks: [result, ...current.tasks.filter((task) => task.id !== result.id)],
    }));
  }

  async function createRefund({
    order,
    amountMinor,
    reasonCode,
    mode,
    note,
  }: {
    order: OrderListItemDTO;
    amountMinor: number;
    reasonCode: string;
    mode: 'cash' | 'original';
    note: string;
  }) {
    if (!online || !currentShift || !canManageCurrentShift || busyKey) return;
    const actionId = makeRefundActionId('request');
    setBusyKey(`create:${order.id}`);
    setActionError(null);
    try {
      const result = await refunds.request({
        order_id: order.id,
        shift_id: currentShift.id,
        reason_code: reasonCode,
        amount_minor: amountMinor,
        expected_paid_minor: Number(order.paid_minor ?? 0),
        expected_refundable_minor: Number(order.refundable_minor ?? 0),
        mode,
        client_action_id: actionId,
        ...(note.trim() ? { note: note.trim() } : {}),
      }, actionId);
      applyServerTask(result);
      setSelectedOrder(null);
      notifications.success(
        `Refund ${inr(result.amount_minor)} reserved. No money has moved yet.`,
        { title: 'Refund request accepted' },
      );
      await load();
    } catch (error) {
      const message = (error as Error).message;
      setActionError(`Refund request was not confirmed: ${message}`);
      notifications.error(
        `${message} No cash or provider payout was authorised by this screen. Refresh before trying again.`,
        { title: 'Refund request not confirmed', critical: true },
      );
      await load();
    } finally {
      setBusyKey(null);
    }
  }

  function publishRecoveryCheckpoint(
    checkpoint: RefundRecoveryCheckpoint,
    task: PosRefundRequestDTO,
  ) {
    const next = [
      ...recoveryRef.current.filter(
        (assessment) => assessment.checkpoint.taskId !== checkpoint.taskId,
      ),
      { checkpoint, task, state: 'retryable' as const },
    ];
    recoveryRef.current = next;
    setRecoveryAssessments(next);
    setUncertainTaskIds(new Set(next.map((assessment) => assessment.checkpoint.taskId)));
  }

  function clearRecoveryCheckpoint(taskId: string) {
    if (recoveryScope) removeRefundRecoveryCheckpoint(recoveryScope, taskId);
    const next = recoveryRef.current.filter(
      (assessment) => assessment.checkpoint.taskId !== taskId,
    );
    recoveryRef.current = next;
    setRecoveryAssessments(next);
    setUncertainTaskIds(new Set(next.map((assessment) => assessment.checkpoint.taskId)));
  }

  async function executeTaskAction(
    action: RefundTaskAction,
    task: PosRefundRequestDTO,
    evidence: ActionEvidence,
    recoveryCheckpoint: RefundRecoveryCheckpoint | null,
  ) {
    if (!online || !currentShift || currentShift.id !== task.shift_id || busyKey) return;
    if (
      recoveryCheckpoint
      && (
        recoveryCheckpoint.taskId !== task.id
        || recoveryCheckpoint.shiftId !== task.shift_id
        || recoveryCheckpoint.amountMinor !== task.amount_minor
        || recoveryCheckpoint.action !== action
      )
    ) {
      setActionError('Saved refund recovery evidence does not match this task. Do not pay again; ask a protected owner to review it.');
      return;
    }
    const permitted = allowedRefundActions(task, {
      userId: me?.user_id ?? null,
      protectedAccess: Boolean(me?.protected_access),
      adminSystemAccess,
      currentShiftId: currentShift.id,
      canManageCurrentShift,
      online,
      // A recovery checkpoint replaces the ordinary controls, but the same
      // shift/actor/backend-state ceiling still applies to its exact retry.
      outcomeUncertain: false,
    });
    if (!permitted.includes(action)) {
      setActionError('This account, shift or server state no longer permits that refund action. Refresh and do not move money again.');
      return;
    }
    setBusyKey(task.id);
    setActionError(null);
    const settlementAction = action === 'settle_cash' || action === 'settle_provider';
    let checkpoint = recoveryCheckpoint;
    let serverRecordedMovement = action === 'finalize_cash' || action === 'finalize_provider';
    let result: PosRefundRequestDTO | null = null;
    try {
      const now = new Date().toISOString();
      if (settlementAction && !checkpoint) {
        checkpoint = {
          version: 1,
          taskId: task.id,
          orderId: task.order_id,
          shiftId: task.shift_id,
          terminalId: task.terminal_id,
          amountMinor: task.amount_minor,
          action,
          actionId: makeRefundActionId(action),
          occurredAt: now,
          externalReference: action === 'settle_provider'
            ? evidence.externalReference.trim()
            : null,
          createdAt: now,
        };
        const persisted = recoveryScope
          ? saveRefundRecoveryCheckpoint(recoveryScope, checkpoint)
          : false;
        publishRecoveryCheckpoint(checkpoint, task);
        if (!persisted) {
          notifications.error(
            'The browser could not persist the payout recovery reference. Keep this page open. The server submission will continue so the money you confirmed can still be recorded.',
            { title: 'Local recovery storage unavailable', critical: true },
          );
        }
      }
      const occurredAt = checkpoint?.occurredAt ?? now;
      switch (action) {
        case 'begin_cash':
          result = await refunds.beginCashHandoff(
            task.id, task.shift_id, task.amount_minor, makeRefundActionId(action),
          );
          break;
        case 'settle_cash':
          if (!checkpoint) throw new Error('Cash recovery identity was not created.');
          result = await refunds.settleCash(
            task.id,
            task.shift_id,
            task.amount_minor,
            occurredAt,
            checkpoint.actionId,
          );
          serverRecordedMovement = ['cash_handed_over_pending_accounting', 'settled']
            .includes(result.status);
          if (!serverRecordedMovement) {
            throw new Error(`The server returned ${result.status} instead of recording the confirmed cash handover.`);
          }
          clearRecoveryCheckpoint(task.id);
          applyServerTask(result);
          if (result.status === 'cash_handed_over_pending_accounting') {
            result = await refunds.finalizeCash(
              task.id, task.shift_id, task.amount_minor, makeRefundActionId('finalize_cash'),
            );
          }
          break;
        case 'finalize_cash':
          result = await refunds.finalizeCash(
            task.id, task.shift_id, task.amount_minor, makeRefundActionId(action),
          );
          break;
        case 'withdraw_cash':
          result = await refunds.withdrawCash(
            task.id,
            task.shift_id,
            task.amount_minor,
            evidence.reason.trim(),
            occurredAt,
            makeRefundActionId(action),
          );
          break;
        case 'resolve_cash':
          result = await refunds.resolveCashHandoff(
            task.id,
            task.shift_id,
            task.amount_minor,
            evidence.reason.trim(),
            occurredAt,
            makeRefundActionId(action),
          );
          break;
        case 'begin_provider':
          result = await refunds.beginProviderPayout(
            task.id, task.shift_id, task.amount_minor, makeRefundActionId(action),
          );
          break;
        case 'settle_provider':
          if (!checkpoint?.externalReference) {
            throw new Error('The exact provider completion reference is missing from recovery evidence.');
          }
          result = await refunds.settleProvider(
            task.id,
            task.shift_id,
            task.amount_minor,
            checkpoint.externalReference,
            occurredAt,
            checkpoint.actionId,
          );
          serverRecordedMovement = ['provider_completed_pending_accounting', 'settled']
            .includes(result.status);
          if (!serverRecordedMovement) {
            throw new Error(`The server returned ${result.status} instead of recording the confirmed provider payout.`);
          }
          clearRecoveryCheckpoint(task.id);
          applyServerTask(result);
          if (result.status === 'provider_completed_pending_accounting') {
            result = await refunds.finalizeProvider(
              task.id, task.shift_id, task.amount_minor, makeRefundActionId('finalize_provider'),
            );
          }
          break;
        case 'finalize_provider':
          result = await refunds.finalizeProvider(
            task.id, task.shift_id, task.amount_minor, makeRefundActionId(action),
          );
          break;
        case 'withdraw_provider':
          result = await refunds.withdrawProvider(
            task.id,
            task.shift_id,
            task.amount_minor,
            evidence.reason.trim(),
            occurredAt,
            makeRefundActionId(action),
          );
          break;
        case 'resolve_provider':
          result = await refunds.resolveProviderPayout(
            task.id,
            task.shift_id,
            task.amount_minor,
            evidence.providerStatus,
            evidence.verificationReference.trim(),
            evidence.reason.trim(),
            occurredAt,
            makeRefundActionId(action),
          );
          break;
      }
      if (!result) throw new Error('The server did not return a refund state.');
      applyServerTask(result);
      setDialog(null);
      const presentation = refundStatusPresentation(result.status);
      notifications.success(presentation.detail, { title: presentation.title });
      await load();
    } catch (error) {
      const message = (error as Error).message;
      const payoutNeedsRecovery = settlementAction && !serverRecordedMovement;
      if (payoutNeedsRecovery) {
        setUncertainTaskIds((current) => new Set(current).add(task.id));
      }
      const safeMessage = serverRecordedMovement
        ? `Money movement is already recorded, but accounting did not finish: ${message} Do not pay again; use Finish accounting.`
        : payoutNeedsRecovery
          ? `You confirmed the customer payout, but the server did not confirm its accounting record: ${message} Do not pay again. Use Retry same recording after reconnecting and verifying the task.`
          : message;
      setActionError(safeMessage);
      notifications.error(safeMessage, {
        title: serverRecordedMovement || payoutNeedsRecovery
          ? 'Refund paid — accounting needs recovery'
          : 'Refund action failed',
        critical: true,
      });
      // The exact settlement key/evidence remains durably locked. Refreshing
      // is read-only and cannot convert this into a new physical payout.
      await load();
    } finally {
      setBusyKey(null);
    }
  }

  if (!LIVE_MODE) {
    return <div className="card text-sm text-fg-muted">Refunds use live financial records and are unavailable in demo mode.</div>;
  }
  if (!canAccess) {
    return (
      <div className="card border-accent-bad/40 bg-accent-bad/10 text-sm text-accent-bad">
        This account does not have <b>pos.refund</b>. Ask an owner to use an authorised refund account.
      </div>
    );
  }
  if (loading) return <SkeletonCard />;

  const shiftMessage = shiftResolution && shiftResolution.kind !== 'ready'
    ? shiftResolutionMessage(shiftResolution)
    : currentShift && !canManageCurrentShift
      ? `Shift opened by ${currentShift.opened_by_name || 'another employee'}. They or a protected owner must manage refund money.`
      : null;

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-2xl font-bold">
            <RotateCcw className="text-accent-gold" size={24} /> Refunds
          </h2>
          <p className="mt-1 text-sm text-fg-muted">
            Reserve first, move money once, then finish the server accounting record.
          </p>
        </div>
        <button
          type="button"
          className="btn btn-ghost"
          disabled={refreshing || !online || !terminalReady}
          onClick={() => { void load(); }}
        >
          <RefreshCw size={15} className={refreshing ? 'animate-spin' : ''} />
          {uncertainTaskIds.size ? 'Recheck server state' : 'Refresh'}
        </button>
      </header>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="Refundable orders" value={String(eligibleOrders.length)} icon={<ReceiptText size={18}/>} />
        <StatCard label="Open refund tasks" value={String(data.tasks.length)} icon={<AlertTriangle size={18}/>} tone="warning" />
        <StatCard label="Reserved / in progress" value={inr(pendingAmount)} icon={<Banknote size={18}/>} tone="warning" />
        <StatCard label="Recent settled" value={inr(settledRecentAmount)} icon={<CheckCircle2 size={18}/>} tone="success" />
      </div>

      {!online && (
        <Banner
          tone="danger"
          title="Refund actions are offline"
          detail="The web app has no refund offline queue. No action will be stored or submitted later. Reconnect and refresh authoritative server state."
          icon={<WifiOff size={18}/>}
        />
      )}
      {loadError && <Banner tone="danger" title="Could not refresh Refunds" detail={loadError} icon={<AlertCircle size={18}/>} />}
      {actionError && (
        <Banner
          tone="danger"
          title="Refund action needs attention"
          detail={actionError}
          icon={<ShieldAlert size={18}/>}
        />
      )}
      {unresolvedRecoveryConflicts.length > 0 && (
        <Banner
          tone="danger"
          title="Saved payout evidence needs protected-owner review"
          detail={`${unresolvedRecoveryConflicts.length} payout record${unresolvedRecoveryConflicts.length === 1 ? '' : 's'} cannot be matched safely to an open server task. Do not pay again and do not clear browser data. Preserve the customer, drawer/provider and reference evidence for reconciliation.`}
          icon={<ShieldAlert size={18}/>}
        />
      )}
      {shiftMessage && <Banner tone="warning" title="Shift action required" detail={shiftMessage} icon={<AlertTriangle size={18}/>} />}
      {currentShift && canManageCurrentShift && (
        <Banner
          tone="info"
          title={`Refund drawer · ${currentShift.opened_by_name || 'Current employee'}`}
          detail={`Exact terminal shift opened ${formatDate(currentShift.opened_at)} · expected cash ${inr(currentShift.expected_minor ?? 0)}.`}
          icon={<Banknote size={18}/>}
        />
      )}

      <section className="card !p-0 overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-bg-border p-4">
          <div>
            <h3 className="font-semibold">Open refund tasks</h3>
            <p className="text-xs text-fg-muted">Money-sensitive work stays visible until settled or explicitly withdrawn.</p>
          </div>
          <span className="chip border-accent-gold/40 text-accent-gold">{data.tasks.length} open</span>
        </div>
        {data.tasks.length === 0 ? (
          <EmptyState
            title="No refund tasks waiting"
            detail="Choose a paid order below to create a recoverable refund request."
          />
        ) : (
          <div className="grid gap-3 p-4 xl:grid-cols-2">
            {data.tasks.map((task) => {
              const presentation = refundStatusPresentation(task.status);
              const order = orderById.get(task.order_id);
              const uncertain = uncertainTaskIds.has(task.id);
              const recovery = recoveryByTask.get(task.id) ?? null;
              const actions = allowedRefundActions(task, {
                userId: me?.user_id ?? null,
                protectedAccess: Boolean(me?.protected_access),
                adminSystemAccess,
                currentShiftId: currentShift?.id ?? null,
                canManageCurrentShift,
                online,
                outcomeUncertain: uncertain,
              });
              const disabledReason = taskActionDisabledReason({
                task,
                online,
                uncertain,
                currentShift,
                canManageCurrentShift,
                currentUserId: me?.user_id ?? null,
                protectedAccess: Boolean(me?.protected_access),
                adminSystemAccess,
              });
              const recoveryPermitted = recovery?.state === 'retryable'
                && allowedRefundActions(task, {
                  userId: me?.user_id ?? null,
                  protectedAccess: Boolean(me?.protected_access),
                  adminSystemAccess,
                  currentShiftId: currentShift?.id ?? null,
                  canManageCurrentShift,
                  online,
                  outcomeUncertain: false,
                }).includes(recovery.checkpoint.action);
              const recoveryDisabledReason = recovery?.state === 'retryable'
                ? taskActionDisabledReason({
                  task,
                  online,
                  uncertain: false,
                  currentShift,
                  canManageCurrentShift,
                  currentUserId: me?.user_id ?? null,
                  protectedAccess: Boolean(me?.protected_access),
                  adminSystemAccess,
                })
                : null;
              return (
                <RefundTaskCard
                  key={task.id}
                  task={task}
                  invoice={order?.invoice_no ?? null}
                  source={order?.source_label ?? null}
                  presentation={presentation}
                  actions={actions}
                  disabledReason={disabledReason}
                  recovery={recovery}
                  recoveryPermitted={Boolean(recoveryPermitted)}
                  recoveryDisabledReason={recoveryDisabledReason}
                  busy={busyKey === task.id}
                  onAction={(action) => setDialog({ action, task, recoveryCheckpoint: null })}
                  onRecovery={() => {
                    if (recovery?.state !== 'retryable') return;
                    setDialog({
                      action: recovery.checkpoint.action,
                      task,
                      recoveryCheckpoint: recovery.checkpoint,
                    });
                  }}
                />
              );
            })}
          </div>
        )}
      </section>

      <section className="card !p-0 overflow-hidden">
        <div className="border-b border-bg-border p-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h3 className="font-semibold">Find a paid order</h3>
              <p className="text-xs text-fg-muted">Search up to the latest 500 paid/refunded orders by invoice, customer, source or order ID.</p>
            </div>
            <div className="relative w-full sm:w-80">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-fg-muted" size={16}/>
              <input
                className="input w-full pl-9"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Invoice, customer, table, station…"
                aria-label="Search refundable orders"
              />
            </div>
          </div>
        </div>
        {!eligibleOrders.length ? (
          <EmptyState
            title={query ? 'No refundable order matches this search' : 'No orders currently have refundable balance'}
            detail="Already-reserved amounts are excluded. Finish an open task before creating another refund for the same order."
          />
        ) : (
          <div className="divide-y divide-bg-border/70">
            {eligibleOrders.map((order) => {
              const rail = refundRailPolicy(order.payment_methods ?? []);
              const canStart = online && canManageCurrentShift && rail.requestReady;
              return (
                <div key={order.id} className="grid gap-3 p-4 md:grid-cols-[minmax(0,1fr)_auto_auto] md:items-center">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-semibold">{order.invoice_no || `Order ${order.id.slice(0, 8)}`}</span>
                      <span className="chip text-[10px] text-fg-muted">{order.status}</span>
                    </div>
                    <p className="mt-1 truncate text-xs text-fg-muted">
                      {order.customer_name || 'Walk-in'} · {order.source_label || order.type} · {formatDate(order.created_at)}
                    </p>
                    <p className="mt-1 text-xs text-fg-muted">
                      Paid via {(order.payment_methods ?? []).map(paymentMethodLabel).join(' + ') || 'unverified rail'}
                    </p>
                  </div>
                  <div className="grid grid-cols-3 gap-3 text-right text-xs md:min-w-72">
                    <MiniValue label="Paid" value={inr(Number(order.paid_minor ?? 0))}/>
                    <MiniValue label="Reserved" value={inr(Number(order.pending_refund_minor ?? 0))}/>
                    <MiniValue label="Refundable" value={inr(Number(order.refundable_minor ?? 0))} good/>
                  </div>
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={!canStart || busyKey !== null}
                    onClick={() => setSelectedOrder(order)}
                    title={!rail.requestReady ? 'Payment rail evidence is unavailable; refresh before refunding.' : undefined}
                  >
                    Request refund
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </section>

      <section className="card !p-0 overflow-hidden">
        <div className="flex items-center gap-2 border-b border-bg-border p-4">
          <History size={17} className="text-fg-muted"/>
          <div>
            <h3 className="font-semibold">Recent completed refunds</h3>
            <p className="text-xs text-fg-muted">Settled and verified no-payout outcomes from this terminal.</p>
          </div>
        </div>
        {!data.recent.length ? (
          <EmptyState title="No completed refunds yet" detail="Settled or withdrawn tasks will remain visible here for review." />
        ) : (
          <div className="divide-y divide-bg-border/70">
            {data.recent.map((task) => (
              <div key={task.id} className="grid gap-2 p-4 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center">
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">{orderById.get(task.order_id)?.invoice_no || `Order ${task.order_id.slice(0, 8)}`}</span>
                    <span className={`chip text-[10px] ${task.status === 'settled' ? 'border-accent-good/40 text-accent-good' : 'text-fg-muted'}`}>
                      {refundStatusPresentation(task.status).label}
                    </span>
                    {task.receipt_no && <span className="font-mono text-xs text-fg-muted">{task.receipt_no}</span>}
                  </div>
                  <p className="mt-1 text-xs text-fg-muted">
                    {paymentMethodLabel(task.settlement_method)} · {task.reason_code.replaceAll('_', ' ')} ·{' '}
                    {formatDate(task.settled_at || task.withdrawn_at)}
                  </p>
                </div>
                <div className={`font-mono text-lg font-semibold ${task.status === 'settled' ? 'text-accent-good' : 'text-fg-muted'}`}>
                  {inr(task.amount_minor)}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      {selectedOrder && (
        <RefundRequestModal
          order={selectedOrder}
          online={online}
          canManageMoney={canManageCurrentShift}
          busy={busyKey === `create:${selectedOrder.id}`}
          onClose={() => { if (!busyKey) setSelectedOrder(null); }}
          onSubmit={createRefund}
        />
      )}
      {dialog && (
        <RefundActionModal
          key={`${dialog.task.id}:${dialog.action}`}
          action={dialog.action}
          task={dialog.task}
          busy={busyKey === dialog.task.id}
          online={online}
          recoveryCheckpoint={dialog.recoveryCheckpoint}
          onClose={() => { if (!busyKey) setDialog(null); }}
          onConfirm={(evidence) => {
            void executeTaskAction(
              dialog.action,
              dialog.task,
              evidence,
              dialog.recoveryCheckpoint,
            );
          }}
        />
      )}
    </div>
  );
}

function RefundTaskCard({
  task,
  invoice,
  source,
  presentation,
  actions,
  disabledReason,
  recovery,
  recoveryPermitted,
  recoveryDisabledReason,
  busy,
  onAction,
  onRecovery,
}: {
  task: PosRefundRequestDTO;
  invoice: string | null;
  source: string | null;
  presentation: ReturnType<typeof refundStatusPresentation>;
  actions: RefundTaskAction[];
  disabledReason: string | null;
  recovery: RefundRecoveryAssessment | null;
  recoveryPermitted: boolean;
  recoveryDisabledReason: string | null;
  busy: boolean;
  onAction: (action: RefundTaskAction) => void;
  onRecovery: () => void;
}) {
  const toneClass = {
    neutral: 'border-bg-border',
    info: 'border-accent/40',
    warning: 'border-accent-gold/45',
    danger: 'border-accent-bad/55',
    success: 'border-accent-good/45',
  }[presentation.tone];
  const startedBy = task.settlement_method === 'cash'
    ? actor(task.handoff_started_by_name, task.handoff_started_by)
    : actor(task.provider_payout_started_by_name, task.provider_payout_started_by);
  const completedBy = task.settlement_method === 'cash'
    ? actor(task.cash_handed_over_by_name, task.cash_handed_over_by)
    : actor(task.provider_completed_by_name, task.provider_completed_by);

  return (
    <article className={`rounded-2xl border bg-bg-raised/35 p-4 ${toneClass}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h4 className="font-semibold">{invoice || `Order ${task.order_id.slice(0, 8)}`}</h4>
            <span className="chip text-[10px]">{presentation.label}</span>
          </div>
          <p className="mt-1 truncate text-xs text-fg-muted">{source || `Refund ${task.id.slice(0, 8)}`}</p>
        </div>
        <div className="shrink-0 text-right">
          <div className="font-mono text-xl font-bold">{inr(task.amount_minor)}</div>
          <div className="text-[10px] uppercase tracking-wide text-fg-muted">{paymentMethodLabel(task.settlement_method)}</div>
        </div>
      </div>

      <div className="my-3 rounded-xl bg-bg/50 p-3">
        <p className="text-sm font-semibold">{presentation.title}</p>
        <p className="mt-1 text-xs leading-relaxed text-fg-muted">{presentation.detail}</p>
      </div>

      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
        <Info label="Accepted" value={formatDate(task.accepted_at)} />
        <Info label="Accepted by" value={actor(task.accepted_by_name, task.accepted_by) || '—'} />
        {startedBy && <Info label="Money step started by" value={startedBy} />}
        {completedBy && <Info label="Completion recorded by" value={completedBy} />}
        {task.external_reference && <Info label="Provider reference" value={task.external_reference} mono />}
        {task.receipt_no && <Info label="Refund receipt" value={task.receipt_no} mono />}
      </dl>
      {task.note && <p className="mt-3 rounded-lg bg-bg/40 px-3 py-2 text-xs text-fg-muted">Note: {task.note}</p>}

      {recovery && (
        <div className="mt-3 rounded-xl border border-accent-bad/55 bg-accent-bad/10 p-3">
          <p className="text-sm font-semibold text-accent-bad">Customer payout was already confirmed</p>
          <p className="mt-1 text-xs leading-relaxed text-fg-muted">
            Do not pay again. The saved reference uses the original idempotency key from{' '}
            {formatDate(recovery.checkpoint.occurredAt)}.
          </p>
          {recovery.state === 'retryable' ? (
            <>
              <button
                type="button"
                className="btn btn-ghost mt-3"
                disabled={busy || !recoveryPermitted}
                onClick={onRecovery}
              >
                Retry same server recording
              </button>
              {recoveryDisabledReason && (
                <p className="mt-2 text-xs text-fg-muted">{recoveryDisabledReason}</p>
              )}
            </>
          ) : (
            <p className="mt-2 text-xs font-semibold text-accent-bad">
              The saved evidence conflicts with server state. A protected owner must reconcile it.
            </p>
          )}
        </div>
      )}

      <div className="mt-4 border-t border-bg-border/70 pt-3">
        <RefundTaskControls
          actions={actions}
          busy={busy}
          disabledReason={disabledReason}
          onAction={onAction}
        />
      </div>
    </article>
  );
}

function RefundRequestModal({
  order,
  online,
  canManageMoney,
  busy,
  onClose,
  onSubmit,
}: {
  order: OrderListItemDTO;
  online: boolean;
  canManageMoney: boolean;
  busy: boolean;
  onClose: () => void;
  onSubmit: (request: {
    order: OrderListItemDTO;
    amountMinor: number;
    reasonCode: string;
    mode: 'cash' | 'original';
    note: string;
  }) => Promise<void>;
}) {
  const rail = useMemo(() => refundRailPolicy(order.payment_methods ?? []), [order.payment_methods]);
  const [detail, setDetail] = useState<OrderDTO | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [amount, setAmount] = useState(() => (Number(order.refundable_minor ?? 0) / 100).toFixed(2));
  const [reasonCode, setReasonCode] = useState('customer_unhappy');
  const [mode, setMode] = useState<'cash' | 'original'>(rail.defaultMode);
  const [note, setNote] = useState('');
  const [reviewing, setReviewing] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setDetail(null);
    setDetailError(null);
    orders.get(order.id)
      .then((value) => { if (!cancelled) setDetail(value); })
      .catch((error) => { if (!cancelled) setDetailError((error as Error).message); });
    return () => { cancelled = true; };
  }, [order.id]);

  const amountMinor = parseRupeesToMinor(amount);
  const validAmount = amountMinor !== null
    && amountMinor > 0
    && amountMinor <= Number(order.refundable_minor ?? 0);
  const valid = Boolean(
    online
    && canManageMoney
    && detail
    && validAmount
    && rail.requestReady
    && refundModeAllowed(rail, mode),
  );

  return (
    <Modal open onClose={onClose} title={`Refund ${order.invoice_no || order.id.slice(0, 8)}`} size="lg">
      {!reviewing ? (
        <div className="space-y-4">
          <div className="grid grid-cols-3 gap-3 rounded-xl bg-bg-raised p-3 text-center">
            <MiniValue label="Paid" value={inr(Number(order.paid_minor ?? 0))}/>
            <MiniValue label="Already reserved" value={inr(Number(order.pending_refund_minor ?? 0))}/>
            <MiniValue label="Available" value={inr(Number(order.refundable_minor ?? 0))} good/>
          </div>

          {detailError && <Banner tone="danger" title="Could not verify order items" detail={detailError} icon={<AlertCircle size={18}/>} />}
          {!detail && !detailError && (
            <div className="flex items-center gap-2 text-sm text-fg-muted"><Loader2 className="animate-spin" size={16}/> Verifying order items…</div>
          )}
          {detail && (
            <div className="rounded-xl border border-bg-border">
              <div className="border-b border-bg-border px-3 py-2 text-xs font-semibold text-fg-muted">Order items</div>
              <div className="divide-y divide-bg-border/60">
                {detail.lines.map((line, index) => (
                  <div key={`${line.menu_item_id}:${index}`} className="flex justify-between gap-3 px-3 py-2 text-sm">
                    <span>{line.qty} × {line.name}</span>
                    <span className="font-mono">{inr(line.line_total_minor)}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <label className="block text-sm">
            <span className="mb-1 block text-fg-muted">Refund amount (₹)</span>
            <input
              className="input w-full font-mono text-lg"
              inputMode="decimal"
              value={amount}
              onChange={(event) => setAmount(event.target.value.replace(/[^0-9.]/g, ''))}
              aria-invalid={!validAmount}
            />
            {!validAmount && (
              <span className="mt-1 block text-xs text-accent-bad">
                Enter an amount from ₹0.01 to {inr(Number(order.refundable_minor ?? 0))}.
              </span>
            )}
          </label>

          <div>
            <p className="mb-2 text-sm text-fg-muted">Payout method</p>
            {!rail.requestReady ? (
              <Banner
                tone="danger"
                title="Payment rail unavailable — refund locked"
                detail="Refresh after the server update. The web app will not guess whether this was Cash, Card, UPI, QR or Wallet."
                icon={<ShieldAlert size={18}/>}
              />
            ) : rail.kind === 'single_provider' ? (
              <div className="grid grid-cols-2 gap-2">
                <Choice active={mode === 'original'} onClick={() => setMode('original')}>
                  Original {paymentMethodLabel(rail.methods[0])}
                </Choice>
                <Choice active={mode === 'cash'} onClick={() => setMode('cash')}>Cash instead</Choice>
              </div>
            ) : (
              <div className="rounded-xl border border-bg-border bg-bg/40 p-3 text-sm">
                <b>Cash workflow</b>
                <p className="mt-1 text-xs text-fg-muted">
                  {rail.kind === 'mixed'
                    ? `Mixed payment (${rail.methods.map(paymentMethodLabel).join(' + ')}) must use the explicit cash workflow.`
                    : 'The original collection was cash, so this refund uses the guarded drawer workflow.'}
                </p>
              </div>
            )}
          </div>

          <div>
            <p className="mb-2 text-sm text-fg-muted">Reason</p>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {REFUND_REASONS.map(([code, label]) => (
                <Choice key={code} active={reasonCode === code} onClick={() => setReasonCode(code)}>{label}</Choice>
              ))}
            </div>
          </div>

          <label className="block text-sm">
            <span className="mb-1 block text-fg-muted">Note (optional)</span>
            <textarea className="input min-h-24 w-full" maxLength={500} value={note} onChange={(event) => setNote(event.target.value)}/>
          </label>

          <Banner
            tone="warning"
            title="Money refund only — stock and COGS stay unchanged"
            detail="Prepared food, consumed ingredients and gaming time are not automatically returned to stock. Returned unopened goods need a separate authorised inventory adjustment with evidence."
            icon={<AlertTriangle size={18}/>}
          />

          <div className="flex justify-end gap-2 border-t border-bg-border pt-3">
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>Cancel</button>
            <button type="button" className="btn btn-primary" disabled={!valid || busy} onClick={() => setReviewing(true)}>
              Review {amountMinor ? inr(amountMinor) : 'refund'}
            </button>
          </div>
        </div>
      ) : (
        <div className="space-y-4">
          <Banner
            tone="warning"
            title="This step reserves the refund only"
            detail={mode === 'cash'
              ? 'Submitting does not authorise cash to leave the drawer. Wait for the separate server-confirmed cash handover task.'
              : 'Submitting does not move provider money. Wait for the separate server-confirmed provider payout task.'}
            icon={<AlertTriangle size={18}/>}
          />
          <dl className="grid grid-cols-2 gap-3 rounded-xl bg-bg-raised p-4 text-sm">
            <Info label="Order" value={order.invoice_no || order.id}/>
            <Info label="Amount" value={inr(amountMinor ?? 0)} mono/>
            <Info label="Payout" value={mode === 'cash' ? 'Cash' : `Original ${paymentMethodLabel(rail.methods[0])}`}/>
            <Info label="Reason" value={reasonCode.replaceAll('_', ' ')}/>
          </dl>
          <div className="flex justify-end gap-2">
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => setReviewing(false)}>Go back</button>
            <button
              type="button"
              className="btn btn-primary"
              disabled={!valid || busy || amountMinor === null}
              onClick={() => {
                if (amountMinor === null) return;
                void onSubmit({ order, amountMinor, reasonCode, mode, note });
              }}
            >
              {busy ? <><Loader2 size={15} className="animate-spin"/> Reserving…</> : 'Submit refund request'}
            </button>
          </div>
        </div>
      )}
    </Modal>
  );
}

function RefundActionModal({
  action,
  task,
  busy,
  online,
  recoveryCheckpoint,
  onClose,
  onConfirm,
}: {
  action: RefundTaskAction;
  task: PosRefundRequestDTO;
  busy: boolean;
  online: boolean;
  recoveryCheckpoint: RefundRecoveryCheckpoint | null;
  onClose: () => void;
  onConfirm: (evidence: ActionEvidence) => void;
}) {
  const [acknowledged, setAcknowledged] = useState(false);
  const [reason, setReason] = useState('');
  const [externalReference, setExternalReference] = useState(
    recoveryCheckpoint?.externalReference ?? '',
  );
  const [providerStatus, setProviderStatus] = useState<ActionEvidence['providerStatus']>('no_matching_transaction');
  const [verificationReference, setVerificationReference] = useState('');
  const config = actionDialogConfig(action, task, Boolean(recoveryCheckpoint));
  const needsReason = ['withdraw_cash', 'resolve_cash', 'withdraw_provider', 'resolve_provider'].includes(action);
  const needsProviderReference = action === 'settle_provider';
  const needsVerification = action === 'resolve_provider';
  const valid = online
    && acknowledged
    && (!needsReason || reason.trim().length >= 3)
    && (!needsProviderReference || externalReference.trim().length >= 1)
    && (!needsVerification || verificationReference.trim().length >= 3);

  return (
    <Modal open onClose={onClose} title={config.title} size="md">
      <div className="space-y-4">
        <Banner tone={config.tone} title={config.bannerTitle} detail={config.detail} icon={<AlertTriangle size={18}/>} />

        <div className="grid grid-cols-2 gap-3 rounded-xl bg-bg-raised p-3 text-sm">
          <Info label="Amount" value={inr(task.amount_minor)} mono/>
          <Info label="Rail" value={paymentMethodLabel(task.settlement_method)}/>
          <Info label="Accepted" value={formatDate(task.accepted_at)}/>
          <Info label="Task" value={task.id.slice(0, 8)} mono/>
        </div>

        {needsProviderReference && (
          <label className="block text-sm">
            <span className="mb-1 block text-fg-muted">Successful provider refund reference</span>
            <input
              className="input w-full"
              value={externalReference}
              maxLength={200}
              disabled={Boolean(recoveryCheckpoint)}
              onChange={(event) => setExternalReference(event.target.value)}
              placeholder="UPI / card / wallet reference"
            />
            {recoveryCheckpoint && (
              <span className="mt-1 block text-xs text-fg-muted">
                Locked to the original provider evidence so the same idempotent request can be retried.
              </span>
            )}
          </label>
        )}

        {needsVerification && (
          <>
            <div>
              <p className="mb-2 text-sm text-fg-muted">Verified provider outcome</p>
              <div className="grid gap-2">
                <Choice active={providerStatus === 'no_matching_transaction'} onClick={() => setProviderStatus('no_matching_transaction')}>No matching transaction</Choice>
                <Choice active={providerStatus === 'provider_declined'} onClick={() => setProviderStatus('provider_declined')}>Provider declined</Choice>
                <Choice active={providerStatus === 'provider_reversed'} onClick={() => setProviderStatus('provider_reversed')}>Provider reversed</Choice>
              </div>
            </div>
            <label className="block text-sm">
              <span className="mb-1 block text-fg-muted">Provider search / case / reversal reference</span>
              <input className="input w-full" maxLength={200} value={verificationReference} onChange={(event) => setVerificationReference(event.target.value)}/>
            </label>
          </>
        )}

        {needsReason && (
          <label className="block text-sm">
            <span className="mb-1 block text-fg-muted">Reason and verification note</span>
            <textarea className="input min-h-24 w-full" maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)}/>
            {reason.length > 0 && reason.trim().length < 3 && <span className="mt-1 block text-xs text-accent-bad">Enter at least 3 characters.</span>}
          </label>
        )}

        <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-bg-border bg-bg/40 p-3 text-sm">
          <input
            type="checkbox"
            className="mt-1 h-4 w-4 accent-accent-gold"
            checked={acknowledged}
            onChange={(event) => setAcknowledged(event.target.checked)}
          />
          <span>{config.acknowledgement}</span>
        </label>

        <div className="flex justify-end gap-2 border-t border-bg-border pt-3">
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>Stop and verify</button>
          <button
            type="button"
            className={config.destructive
              ? 'btn border border-accent-bad/60 bg-accent-bad/20 text-accent-bad'
              : 'btn btn-primary'}
            disabled={!valid || busy}
            onClick={() => onConfirm({
              acknowledged,
              reason,
              externalReference,
              providerStatus,
              verificationReference,
            })}
          >
            {busy ? <><Loader2 size={15} className="animate-spin"/> Working…</> : config.confirmLabel}
          </button>
        </div>
      </div>
    </Modal>
  );
}

function actionDialogConfig(
  action: RefundTaskAction,
  task: PosRefundRequestDTO,
  recovery: boolean,
) {
  const amount = inr(task.amount_minor);
  switch (action) {
    case 'begin_cash': return {
      title: 'Start cash handover', bannerTitle: 'No cash has moved yet', tone: 'warning' as const,
      detail: `Open the handover only after confirming the customer and ${amount} amount.`,
      acknowledgement: 'I verified the customer and amount, and I understand cash must not leave until this server step succeeds.',
      confirmLabel: 'Open cash handover', destructive: false,
    };
    case 'settle_cash': return recovery ? {
      title: 'Recover cash accounting', bannerTitle: 'No new cash handover', tone: 'danger' as const,
      detail: `The customer payout was already confirmed. This retries the exact same server recording for ${amount}; do not hand over cash again.`,
      acknowledgement: 'I will not hand over cash again. I am retrying only the saved server accounting request.',
      confirmLabel: 'Retry same recording', destructive: false,
    } : {
      title: 'Record cash handed over', bannerTitle: 'Confirm physical money once', tone: 'danger' as const,
      detail: `Use this only after exactly ${amount} reached the customer. A timeout is not permission to pay twice.`,
      acknowledgement: `I personally verified that exactly ${amount} reached the customer once.`,
      confirmLabel: `Record ${amount} handed over`, destructive: true,
    };
    case 'finalize_cash': return {
      title: 'Finish cash accounting', bannerTitle: 'Cash is already recorded', tone: 'danger' as const,
      detail: 'This action creates no new cash movement. It only finishes accounting for the existing handover fact.',
      acknowledgement: 'I will not hand over cash again; I am only finishing accounting.',
      confirmLabel: 'Finish accounting', destructive: false,
    };
    case 'withdraw_cash': return {
      title: 'Withdraw unpaid cash refund', bannerTitle: 'Protected-owner decision', tone: 'danger' as const,
      detail: `Withdraw only after verifying none of ${amount} reached the customer.`,
      acknowledgement: 'I checked the customer and confirm no cash was handed over.',
      confirmLabel: 'Withdraw — no cash given', destructive: true,
    };
    case 'resolve_cash': return {
      title: 'Resolve started cash handover', bannerTitle: 'Customer and drawer must both be checked', tone: 'danger' as const,
      detail: `Continue only if none of ${amount} reached the customer and the drawer is unchanged.`,
      acknowledgement: 'I checked the customer and physical drawer: no cash moved and the drawer is unchanged.',
      confirmLabel: 'Confirm no cash moved', destructive: true,
    };
    case 'begin_provider': return {
      title: 'Start provider payout', bannerTitle: 'No provider money has moved yet', tone: 'warning' as const,
      detail: `Open this exact ${paymentMethodLabel(task.settlement_method)} payout before using the provider app.`,
      acknowledgement: 'I verified the customer and amount, and will run only this one provider refund.',
      confirmLabel: 'Open provider payout', destructive: false,
    };
    case 'settle_provider': return recovery ? {
      title: 'Recover provider accounting', bannerTitle: 'Do not run another provider refund', tone: 'danger' as const,
      detail: `The ${paymentMethodLabel(task.settlement_method)} payout was already confirmed. This retries the exact saved reference and timestamp only.`,
      acknowledgement: 'I will not run another provider refund. I am retrying only the saved server accounting request.',
      confirmLabel: 'Retry same recording', destructive: false,
    } : {
      title: 'Record provider completion', bannerTitle: 'Verify the provider before confirming', tone: 'danger' as const,
      detail: `Confirm only after ${paymentMethodLabel(task.settlement_method)} successfully refunded ${amount}. Never run it twice.`,
      acknowledgement: 'I verified the provider shows one successful refund and entered its exact reference.',
      confirmLabel: 'Record provider completion', destructive: true,
    };
    case 'finalize_provider': return {
      title: 'Finish provider accounting', bannerTitle: 'Provider completion is already recorded', tone: 'danger' as const,
      detail: 'This action creates no provider payout. It only finishes accounting for the saved completion evidence.',
      acknowledgement: 'I will not run another provider refund; I am only finishing accounting.',
      confirmLabel: 'Finish accounting', destructive: false,
    };
    case 'withdraw_provider': return {
      title: 'Withdraw provider reservation', bannerTitle: 'Protected-owner decision', tone: 'danger' as const,
      detail: 'Use only before a provider payout starts and after verifying no provider value moved.',
      acknowledgement: 'I checked the provider and confirm the payout was never started or completed.',
      confirmLabel: 'Withdraw reservation', destructive: true,
    };
    case 'resolve_provider': return {
      title: 'Resolve failed provider payout', bannerTitle: 'Protected system recovery', tone: 'danger' as const,
      detail: 'Search the provider for this exact payout first. If it succeeded, stop and record the successful reference instead.',
      acknowledgement: 'I verified the provider after payout start and confirm no successful payout exists.',
      confirmLabel: 'Confirm no provider payout', destructive: true,
    };
  }
}

function StatCard({
  label, value, icon, tone = 'default',
}: {
  label: string;
  value: string;
  icon: React.ReactNode;
  tone?: 'default' | 'warning' | 'success';
}) {
  const toneClass = tone === 'warning'
    ? 'text-accent-gold'
    : tone === 'success'
      ? 'text-accent-good'
      : 'text-fg';
  return (
    <div className="card flex min-h-24 items-center gap-3">
      <div className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-bg-raised ${toneClass}`}>{icon}</div>
      <div className="min-w-0">
        <p className="truncate text-xs text-fg-muted">{label}</p>
        <p className={`mt-1 truncate font-mono text-xl font-bold ${toneClass}`}>{value}</p>
      </div>
    </div>
  );
}

function Banner({
  tone, title, detail, icon,
}: {
  tone: 'info' | 'warning' | 'danger';
  title: string;
  detail: string;
  icon: React.ReactNode;
}) {
  const classes = tone === 'danger'
    ? 'border-accent-bad/45 bg-accent-bad/10 text-accent-bad'
    : tone === 'warning'
      ? 'border-accent-gold/45 bg-accent-gold/10 text-accent-gold'
      : 'border-accent/40 bg-accent/10 text-accent';
  return (
    <div className={`rounded-2xl border p-4 ${classes}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <div className="flex items-start gap-3">
        <span className="mt-0.5 shrink-0">{icon}</span>
        <div>
          <p className="font-semibold">{title}</p>
          <p className="mt-1 text-sm leading-relaxed text-fg-muted">{detail}</p>
        </div>
      </div>
    </div>
  );
}

function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="grid min-h-40 place-items-center p-6 text-center">
      <div>
        <ReceiptText className="mx-auto text-fg-muted" size={28}/>
        <p className="mt-3 font-semibold">{title}</p>
        <p className="mt-1 max-w-lg text-sm text-fg-muted">{detail}</p>
      </div>
    </div>
  );
}

function Choice({
  active, onClick, children,
}: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      className={`min-h-11 rounded-xl border px-3 py-2 text-sm transition ${active
        ? 'border-accent-gold/70 bg-accent-gold/15 text-accent-gold'
        : 'border-bg-border bg-bg/30 text-fg-muted hover:text-fg'}`}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

function Info({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-[10px] uppercase tracking-wide text-fg-muted">{label}</dt>
      <dd className={`mt-0.5 break-words text-sm ${mono ? 'font-mono' : ''}`}>{value}</dd>
    </div>
  );
}

function MiniValue({ label, value, good = false }: { label: string; value: string; good?: boolean }) {
  return (
    <div>
      <div className="text-[10px] uppercase tracking-wide text-fg-muted">{label}</div>
      <div className={`mt-0.5 font-mono font-semibold ${good ? 'text-accent-good' : ''}`}>{value}</div>
    </div>
  );
}
