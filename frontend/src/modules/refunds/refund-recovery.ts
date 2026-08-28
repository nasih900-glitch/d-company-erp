import type { PosRefundRequestDTO } from '@/lib/erp-api';

export type RefundSettlementAction = 'settle_cash' | 'settle_provider';

export interface RefundRecoveryScope {
  companyId: string;
  branchId: string;
  terminalId: string;
}

/**
 * Durable safety evidence for a payout the operator says already happened.
 *
 * This is deliberately not an offline queue: the browser never submits it in
 * the background. It only preserves the exact idempotency key and evidence so
 * a foreground, online recovery can repeat the same accounting request after
 * a timeout/reload without suggesting that money should move again.
 */
export interface RefundRecoveryCheckpoint {
  version: 1;
  taskId: string;
  orderId: string;
  shiftId: string;
  terminalId: string;
  amountMinor: number;
  action: RefundSettlementAction;
  actionId: string;
  occurredAt: string;
  externalReference: string | null;
  createdAt: string;
}

export interface RefundRecoveryAssessment {
  checkpoint: RefundRecoveryCheckpoint;
  state: 'retryable' | 'recorded' | 'conflict' | 'missing';
  task: PosRefundRequestDTO | null;
}

export interface RefundRecoveryStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

const STORAGE_PREFIX = 'dcompany_refund_recovery:v1';
const MAX_CHECKPOINTS = 20;

function browserStorage(): RefundRecoveryStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

export function refundRecoveryStorageKey(scope: RefundRecoveryScope): string {
  return `${STORAGE_PREFIX}:${scope.companyId}:${scope.branchId}:${scope.terminalId}`;
}

function validText(value: unknown, maxLength = 200): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLength;
}

function validIsoDate(value: unknown): value is string {
  return validText(value, 64) && Number.isFinite(Date.parse(value));
}

function parseCheckpoint(value: unknown): RefundRecoveryCheckpoint | null {
  if (!value || typeof value !== 'object') return null;
  const row = value as Partial<RefundRecoveryCheckpoint>;
  if (
    row.version !== 1
    || !validText(row.taskId)
    || !validText(row.orderId)
    || !validText(row.shiftId)
    || !validText(row.terminalId)
    || !Number.isSafeInteger(row.amountMinor)
    || Number(row.amountMinor) <= 0
    || (row.action !== 'settle_cash' && row.action !== 'settle_provider')
    || !validText(row.actionId, 160)
    || !validIsoDate(row.occurredAt)
    || !validIsoDate(row.createdAt)
    || (row.externalReference !== null && row.externalReference !== undefined
      && !validText(row.externalReference, 200))
  ) return null;
  return {
    version: 1,
    taskId: row.taskId,
    orderId: row.orderId,
    shiftId: row.shiftId,
    terminalId: row.terminalId,
    amountMinor: Number(row.amountMinor),
    action: row.action,
    actionId: row.actionId,
    occurredAt: row.occurredAt,
    externalReference: row.externalReference ?? null,
    createdAt: row.createdAt,
  };
}

export function readRefundRecoveryCheckpoints(
  scope: RefundRecoveryScope,
  storage: RefundRecoveryStorage | null = browserStorage(),
): RefundRecoveryCheckpoint[] {
  if (!storage) return [];
  try {
    const raw = storage.getItem(refundRecoveryStorageKey(scope));
    if (!raw) return [];
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    const byTask = new Map<string, RefundRecoveryCheckpoint>();
    for (const value of parsed) {
      const checkpoint = parseCheckpoint(value);
      if (!checkpoint || checkpoint.terminalId !== scope.terminalId) continue;
      byTask.set(checkpoint.taskId, checkpoint);
    }
    return [...byTask.values()].slice(-MAX_CHECKPOINTS);
  } catch {
    return [];
  }
}

function writeRefundRecoveryCheckpoints(
  scope: RefundRecoveryScope,
  checkpoints: readonly RefundRecoveryCheckpoint[],
  storage: RefundRecoveryStorage | null,
): boolean {
  if (!storage) return false;
  try {
    const key = refundRecoveryStorageKey(scope);
    if (!checkpoints.length) storage.removeItem(key);
    else storage.setItem(key, JSON.stringify(checkpoints.slice(-MAX_CHECKPOINTS)));
    return true;
  } catch {
    return false;
  }
}

export function saveRefundRecoveryCheckpoint(
  scope: RefundRecoveryScope,
  checkpoint: RefundRecoveryCheckpoint,
  storage: RefundRecoveryStorage | null = browserStorage(),
): boolean {
  const current = readRefundRecoveryCheckpoints(scope, storage)
    .filter((row) => row.taskId !== checkpoint.taskId);
  return writeRefundRecoveryCheckpoints(scope, [...current, checkpoint], storage);
}

export function removeRefundRecoveryCheckpoint(
  scope: RefundRecoveryScope,
  taskId: string,
  storage: RefundRecoveryStorage | null = browserStorage(),
): boolean {
  const current = readRefundRecoveryCheckpoints(scope, storage);
  return writeRefundRecoveryCheckpoints(
    scope,
    current.filter((row) => row.taskId !== taskId),
    storage,
  );
}

export function assessRefundRecoveryCheckpoints(
  checkpoints: readonly RefundRecoveryCheckpoint[],
  tasks: readonly PosRefundRequestDTO[],
): RefundRecoveryAssessment[] {
  const byId = new Map(tasks.map((task) => [task.id, task]));
  return checkpoints.map((checkpoint) => {
    const task = byId.get(checkpoint.taskId) ?? null;
    if (!task) return { checkpoint, task, state: 'missing' };
    if (
      task.order_id !== checkpoint.orderId
      || task.shift_id !== checkpoint.shiftId
      || task.terminal_id !== checkpoint.terminalId
      || task.amount_minor !== checkpoint.amountMinor
      || (checkpoint.action === 'settle_cash' && task.settlement_method !== 'cash')
      || (checkpoint.action === 'settle_provider' && task.settlement_method === 'cash')
    ) return { checkpoint, task, state: 'conflict' };

    if (task.status === 'settled') return { checkpoint, task, state: 'recorded' };
    if (
      checkpoint.action === 'settle_cash'
      && task.status === 'cash_handed_over_pending_accounting'
    ) return { checkpoint, task, state: 'recorded' };
    if (
      checkpoint.action === 'settle_provider'
      && task.status === 'provider_completed_pending_accounting'
    ) return { checkpoint, task, state: 'recorded' };
    if (
      (checkpoint.action === 'settle_cash' && task.status === 'cash_handoff_in_progress')
      || (checkpoint.action === 'settle_provider' && task.status === 'provider_payout_in_progress')
    ) return { checkpoint, task, state: 'retryable' };
    return { checkpoint, task, state: 'conflict' };
  });
}
