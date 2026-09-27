import type { GamingPackageAmendBody } from '@/lib/erp-api';

const PREFIX = 'dcompany:gaming-session-action:v1:';

export type GamingSessionActionScope = {
  actorUserId: string;
  companyId: string;
  branchId: string;
  terminalId: string;
  sessionId: string;
  shiftId: string;
};

export type GamingSessionActionBody =
  | { kind: 'amend'; payload: GamingPackageAmendBody }
  | { kind: 'join'; payload: { customer_id: string; expected_participant_revision: number;
      customer_directory_revision?: number; customer_directory_company_id?: string } }
  | { kind: 'leave'; participantId: string; payload: { expected_participant_revision: number } }
  | { kind: 'stop'; payload: { expected_participant_revision: number; expected_billing_revision: number } };

export type GamingSessionActionAttempt = GamingSessionActionScope & GamingSessionActionBody & {
  version: 1;
  idempotencyKey: string;
};

export class GamingSessionActionPersistenceError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GamingSessionActionPersistenceError';
  }
}

export async function withGamingSessionActionLock<T>(sessionId: string, action: () => Promise<T>): Promise<T> {
  const locks = globalThis.navigator?.locks;
  if (!locks?.request) {
    throw new GamingSessionActionPersistenceError('Cross-tab Gaming action locking is unavailable on this device.');
  }
  return locks.request(`dcompany:gaming-session-action:${sessionId}`, { mode: 'exclusive' }, action);
}

type GamingStorage = Pick<Storage, 'length' | 'key' | 'getItem' | 'setItem' | 'removeItem'>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function validScope(value: Record<string, unknown>): boolean {
  return ['actorUserId', 'companyId', 'branchId', 'terminalId', 'sessionId', 'shiftId'].every(
    (field) => typeof value[field] === 'string' && (value[field] as string).trim().length > 0,
  );
}

function revision(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function validAction(value: Record<string, unknown>): boolean {
  if (!isRecord(value.payload)) return false;
  const payload = value.payload;
  if (value.kind === 'stop') {
    return revision(payload.expected_participant_revision) && revision(payload.expected_billing_revision)
      && Object.keys(payload).length === 2;
  }
  if (value.kind === 'leave') {
    return typeof value.participantId === 'string' && value.participantId.length > 0
      && revision(payload.expected_participant_revision) && Object.keys(payload).length === 1;
  }
  if (value.kind === 'join') {
    const evidence = payload.customer_directory_revision;
    return typeof payload.customer_id === 'string' && payload.customer_id.length > 0
      && revision(payload.expected_participant_revision)
      && (evidence === undefined || revision(evidence))
      && (evidence === undefined
        ? payload.customer_directory_company_id === undefined
        : typeof payload.customer_directory_company_id === 'string'
          && payload.customer_directory_company_id.length > 0)
      && Object.keys(payload).every((field) => [
        'customer_id', 'expected_participant_revision',
        'customer_directory_revision', 'customer_directory_company_id',
      ].includes(field));
  }
  if (value.kind === 'amend') {
    return typeof payload.target_package_id === 'string' && payload.target_package_id.length > 0
      && revision(payload.expected_timer_minutes) && payload.expected_timer_minutes > 0
      && revision(payload.expected_amount_minor)
      && revision(payload.expected_pause_version)
      && revision(payload.expected_participant_revision)
      && revision(payload.expected_billing_revision)
      && revision(payload.expected_target_price_minor)
      && revision(payload.expected_target_duration_minutes)
      && payload.expected_target_duration_minutes > 0
      && typeof payload.expected_target_variant === 'string'
      && payload.expected_target_variant.length > 0
      && Object.keys(payload).length === 9;
  }
  return false;
}

export function parseGamingSessionActionAttempt(value: unknown): GamingSessionActionAttempt | null {
  if (!isRecord(value) || value.version !== 1 || !validScope(value) || !validAction(value)
    || typeof value.idempotencyKey !== 'string'
    || !value.idempotencyKey.startsWith('gaming-session-action:')) return null;
  const fields = Object.keys(value);
  const expected = value.kind === 'leave' ? 11 : 10;
  if (fields.length !== expected || !fields.every((field) => [
    'version', 'idempotencyKey', 'actorUserId', 'companyId', 'branchId',
    'terminalId', 'sessionId', 'shiftId', 'kind', 'payload', 'participantId',
  ].includes(field))) return null;
  return value as GamingSessionActionAttempt;
}

function scopePrefix(scope: Omit<GamingSessionActionScope, 'sessionId' | 'shiftId'>): string {
  const fields = [scope.companyId, scope.branchId, scope.terminalId, scope.actorUserId];
  if (fields.some((field) => !field?.trim())) {
    throw new GamingSessionActionPersistenceError('The staff and terminal scope is not verified.');
  }
  return PREFIX + fields.map(encodeURIComponent).join(':') + ':';
}

function keyFor(scope: GamingSessionActionScope): string {
  if (!scope.sessionId?.trim() || !scope.shiftId?.trim()) {
    throw new GamingSessionActionPersistenceError('The session and shift scope is not verified.');
  }
  return scopePrefix(scope) + encodeURIComponent(scope.sessionId);
}

function read(storage: GamingStorage, key: string): GamingSessionActionAttempt | null {
  let raw: string | null;
  try { raw = storage.getItem(key); } catch {
    throw new GamingSessionActionPersistenceError('Saved Gaming action could not be read.');
  }
  if (raw === null) return null;
  let parsed: unknown;
  try { parsed = JSON.parse(raw); } catch {
    throw new GamingSessionActionPersistenceError('Saved Gaming action is damaged.');
  }
  const attempt = parseGamingSessionActionAttempt(parsed);
  if (!attempt || keyFor(attempt) !== key) {
    throw new GamingSessionActionPersistenceError('Saved Gaming action is damaged or belongs to a different scope.');
  }
  return attempt;
}

export function inspectGamingSessionAction(
  storage: GamingStorage,
  scope: GamingSessionActionScope,
): GamingSessionActionAttempt | null {
  return read(storage, keyFor(scope));
}

export function listGamingSessionActions(
  storage: GamingStorage,
  scope: Omit<GamingSessionActionScope, 'sessionId' | 'shiftId'>,
): GamingSessionActionAttempt[] {
  const prefix = scopePrefix(scope);
  const attempts: GamingSessionActionAttempt[] = [];
  try {
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i);
      if (!key?.startsWith(prefix)) continue;
      const attempt = read(storage, key);
      if (attempt) attempts.push(attempt);
    }
  } catch (error) {
    if (error instanceof GamingSessionActionPersistenceError) throw error;
    throw new GamingSessionActionPersistenceError('Saved Gaming actions could not be inspected.');
  }
  return attempts;
}

export function persistGamingSessionAction(storage: GamingStorage, attempt: GamingSessionActionAttempt): void {
  if (!parseGamingSessionActionAttempt(attempt)) {
    throw new GamingSessionActionPersistenceError('Gaming action evidence is incomplete.');
  }
  const key = keyFor(attempt);
  if (read(storage, key)) {
    throw new GamingSessionActionPersistenceError('This session already has a saved action. Retry it before another change.');
  }
  const serialized = JSON.stringify(attempt);
  try { storage.setItem(key, serialized); } catch {
    throw new GamingSessionActionPersistenceError('Gaming action could not be saved before sending.');
  }
  if (JSON.stringify(read(storage, key)) !== serialized) {
    throw new GamingSessionActionPersistenceError('Gaming action storage changed before sending.');
  }
}

export function clearGamingSessionAction(storage: GamingStorage, expected: GamingSessionActionAttempt): void {
  const key = keyFor(expected);
  const stored = read(storage, key);
  if (!stored) return;
  if (JSON.stringify(stored) !== JSON.stringify(expected)) {
    throw new GamingSessionActionPersistenceError('The saved Gaming action changed and cannot be cleared.');
  }
  try { storage.removeItem(key); } catch {
    throw new GamingSessionActionPersistenceError('Confirmed Gaming action could not be cleared.');
  }
  if (read(storage, key)) {
    throw new GamingSessionActionPersistenceError('Confirmed Gaming action is still saved.');
  }
}
