import type { GamingSessionAddonCreateDTO } from '@/lib/erp-api';

import {
  resolveAddonCreateAttempt,
  type GamingAddonCreateAttempt,
  type GamingAddonDraft,
} from './gaming-addons';

const STORAGE_PREFIX = 'dcompany:gaming-addon-create:v1:';
const IDEMPOTENCY_PREFIX = 'gaming-addon-add:';

export type GamingAddonCreatePersistenceErrorCode =
  | 'current_scope_unverified'
  | 'storage_unavailable'
  | 'corrupt_attempt'
  | 'scope_mismatch'
  | 'attempt_conflict'
  | 'cross_tab_lock_unavailable'
  | 'write_verification_failed'
  | 'clear_verification_failed';

export class GamingAddonCreatePersistenceError extends Error {
  readonly code: GamingAddonCreatePersistenceErrorCode;

  constructor(code: GamingAddonCreatePersistenceErrorCode, message: string) {
    super(message);
    this.name = 'GamingAddonCreatePersistenceError';
    this.code = code;
  }
}

export interface GamingAddonCreateTerminalScope {
  readonly actorUserId: string;
  readonly companyId: string;
  readonly branchId: string;
  readonly terminalId: string;
}

export interface GamingAddonCreateAttemptContext extends GamingAddonCreateTerminalScope {
  readonly sessionId: string;
  readonly shiftId: string;
  readonly draft: GamingAddonDraft;
}

export interface DurableGamingAddonCreateAttempt extends GamingAddonCreateAttempt,
  GamingAddonCreateTerminalScope {
  readonly version: 1;
  readonly shiftId: string;
}

export interface GamingAddonCreateStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface GamingAddonCreateLockManager {
  request<Response>(
    name: string,
    options: { mode: 'exclusive' },
    callback: (lock: unknown) => Promise<Response> | Response,
  ): Promise<Response>;
}

type StorageProvider = () => GamingAddonCreateStorage;
type LockProvider = () => GamingAddonCreateLockManager | null | undefined;

const ATTEMPT_FIELDS = [
  'version',
  'actorUserId',
  'companyId',
  'branchId',
  'terminalId',
  'sessionId',
  'shiftId',
  'idempotencyKey',
  'body',
] as const;

const BODY_FIELDS = [
  'client_line_id',
  'menu_item_id',
  'variant_id',
  'modifiers',
  'qty',
  'expected_unit_price_minor',
  'note',
] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactFields(
  value: Record<string, unknown>,
  fields: readonly string[],
): boolean {
  const keys = Object.keys(value);
  return keys.length === fields.length
    && fields.every((field) => Object.prototype.hasOwnProperty.call(value, field));
}

function isNonEmptyString(value: unknown, maxLength = 255): value is string {
  return typeof value === 'string'
    && value.length > 0
    && value.length <= maxLength
    && value === value.trim();
}

function isNullableString(value: unknown, maxLength: number): value is string | null {
  return value === null || (
    typeof value === 'string'
    && value.length <= maxLength
    && value === value.trim()
  );
}

function isWholeMinor(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isInteger(value)
    && value >= 0
    && value <= 9_999_999_999;
}

function parseBody(value: unknown): GamingSessionAddonCreateDTO | null {
  if (!isRecord(value) || !hasExactFields(value, BODY_FIELDS)) return null;
  if (
    !isNonEmptyString(value.client_line_id)
    || !isNonEmptyString(value.menu_item_id)
    || !isNullableString(value.variant_id, 255)
    || !Array.isArray(value.modifiers)
    || value.modifiers.length > 100
    || !Number.isInteger(value.qty)
    || (value.qty as number) < 1
    || (value.qty as number) > 100
    || !isWholeMinor(value.expected_unit_price_minor)
    || !isNullableString(value.note, 500)
  ) return null;

  const modifierIds = new Set<string>();
  for (const modifier of value.modifiers) {
    if (
      !isRecord(modifier)
      || !hasExactFields(modifier, ['modifier_id', 'qty'])
      || !isNonEmptyString(modifier.modifier_id)
      || !Number.isInteger(modifier.qty)
      || (modifier.qty as number) < 1
      || (modifier.qty as number) > 100
      || modifierIds.has(modifier.modifier_id)
    ) return null;
    modifierIds.add(modifier.modifier_id);
  }
  return value as unknown as GamingSessionAddonCreateDTO;
}

export function parseDurableGamingAddonCreateAttempt(
  value: unknown,
): DurableGamingAddonCreateAttempt | null {
  if (!isRecord(value) || !hasExactFields(value, ATTEMPT_FIELDS) || value.version !== 1) {
    return null;
  }
  if (
    !isNonEmptyString(value.actorUserId)
    || !isNonEmptyString(value.companyId)
    || !isNonEmptyString(value.branchId)
    || !isNonEmptyString(value.terminalId)
    || !isNonEmptyString(value.sessionId)
    || !isNonEmptyString(value.shiftId)
    || !isNonEmptyString(value.idempotencyKey)
    || !value.idempotencyKey.startsWith(IDEMPOTENCY_PREFIX)
    || !parseBody(value.body)
  ) return null;
  return value as unknown as DurableGamingAddonCreateAttempt;
}

function assertTerminalScope(scope: GamingAddonCreateTerminalScope): void {
  if (
    !isNonEmptyString(scope.actorUserId)
    || !isNonEmptyString(scope.companyId)
    || !isNonEmptyString(scope.branchId)
    || !isNonEmptyString(scope.terminalId)
  ) {
    throw new GamingAddonCreatePersistenceError(
      'current_scope_unverified',
      'The employee, company, branch, or terminal for this Gaming item could not be verified.',
    );
  }
}

function assertContext(context: GamingAddonCreateAttemptContext): void {
  assertTerminalScope(context);
  if (!isNonEmptyString(context.sessionId) || !isNonEmptyString(context.shiftId)) {
    throw new GamingAddonCreatePersistenceError(
      'current_scope_unverified',
      'The Gaming session or owning shift for this item could not be verified.',
    );
  }
}

function encodedIdentity(value: string): string {
  return encodeURIComponent(value);
}

export function gamingAddonCreateStorageKey(scope: GamingAddonCreateTerminalScope): string {
  assertTerminalScope(scope);
  return `${STORAGE_PREFIX}${[
    scope.companyId,
    scope.branchId,
    scope.terminalId,
    scope.actorUserId,
  ].map(encodedIdentity).join(':')}`;
}

function getStorage(storageProvider: StorageProvider): GamingAddonCreateStorage {
  try {
    const storage = storageProvider();
    if (!storage) throw new Error('storage missing');
    return storage;
  } catch {
    throw new GamingAddonCreatePersistenceError(
      'storage_unavailable',
      'The device storage used to protect this Gaming item is unavailable.',
    );
  }
}

function readStorage(storage: GamingAddonCreateStorage, key: string): string | null {
  try {
    return storage.getItem(key);
  } catch {
    throw new GamingAddonCreatePersistenceError(
      'storage_unavailable',
      'The saved Gaming item receipt could not be read.',
    );
  }
}

function parseStoredAttempt(raw: string): DurableGamingAddonCreateAttempt {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new GamingAddonCreatePersistenceError(
      'corrupt_attempt',
      'The saved Gaming item receipt is damaged.',
    );
  }
  const attempt = parseDurableGamingAddonCreateAttempt(value);
  if (!attempt) {
    throw new GamingAddonCreatePersistenceError(
      'corrupt_attempt',
      'The saved Gaming item receipt is incomplete or has an unsupported format.',
    );
  }
  return attempt;
}

function hasExactTerminalScope(
  attempt: DurableGamingAddonCreateAttempt,
  scope: GamingAddonCreateTerminalScope,
): boolean {
  return attempt.actorUserId === scope.actorUserId
    && attempt.companyId === scope.companyId
    && attempt.branchId === scope.branchId
    && attempt.terminalId === scope.terminalId;
}

function attemptsEqual(
  left: DurableGamingAddonCreateAttempt,
  right: DurableGamingAddonCreateAttempt,
): boolean {
  return left.version === right.version
    && left.actorUserId === right.actorUserId
    && left.companyId === right.companyId
    && left.branchId === right.branchId
    && left.terminalId === right.terminalId
    && left.sessionId === right.sessionId
    && left.shiftId === right.shiftId
    && left.idempotencyKey === right.idempotencyKey
    && JSON.stringify(left.body) === JSON.stringify(right.body);
}

export function inspectGamingAddonCreateAttempt({
  storageProvider,
  scope,
}: {
  storageProvider: StorageProvider;
  scope: GamingAddonCreateTerminalScope;
}): DurableGamingAddonCreateAttempt | null {
  const storage = getStorage(storageProvider);
  const raw = readStorage(storage, gamingAddonCreateStorageKey(scope));
  if (raw === null) return null;
  const attempt = parseStoredAttempt(raw);
  if (!hasExactTerminalScope(attempt, scope)) {
    throw new GamingAddonCreatePersistenceError(
      'scope_mismatch',
      'The saved Gaming item belongs to a different employee or operating scope.',
    );
  }
  return attempt;
}

export function prepareGamingAddonCreateAttempt({
  storageProvider,
  context,
  factories,
}: {
  storageProvider: StorageProvider;
  context: GamingAddonCreateAttemptContext;
  factories: { clientLineId: () => string; idempotencyKey: () => string };
}): DurableGamingAddonCreateAttempt {
  assertContext(context);
  const storage = getStorage(storageProvider);
  const stored = inspectGamingAddonCreateAttempt({ storageProvider: () => storage, scope: context });
  if (stored) {
    if (stored.sessionId !== context.sessionId || stored.shiftId !== context.shiftId) {
      throw new GamingAddonCreatePersistenceError(
        'attempt_conflict',
        'Another Gaming session already has an item awaiting server confirmation on this employee and terminal.',
      );
    }
    return stored;
  }

  let baseAttempt: GamingAddonCreateAttempt;
  try {
    baseAttempt = resolveAddonCreateAttempt(null, context.sessionId, context.draft, factories);
  } catch {
    throw new GamingAddonCreatePersistenceError(
      'write_verification_failed',
      'A durable identity for this Gaming item could not be created.',
    );
  }
  const attempt: DurableGamingAddonCreateAttempt = {
    version: 1,
    actorUserId: context.actorUserId,
    companyId: context.companyId,
    branchId: context.branchId,
    terminalId: context.terminalId,
    shiftId: context.shiftId,
    ...baseAttempt,
    body: {
      ...baseAttempt.body,
      variant_id: baseAttempt.body.variant_id ?? null,
      note: baseAttempt.body.note ?? null,
    },
  };
  if (!parseDurableGamingAddonCreateAttempt(attempt)) {
    throw new GamingAddonCreatePersistenceError(
      'write_verification_failed',
      'The Gaming item recovery receipt could not be validated before saving.',
    );
  }

  const key = gamingAddonCreateStorageKey(context);
  try {
    storage.setItem(key, JSON.stringify(attempt));
  } catch {
    throw new GamingAddonCreatePersistenceError(
      'storage_unavailable',
      'The Gaming item recovery receipt could not be saved.',
    );
  }
  const raw = readStorage(storage, key);
  if (raw === null) {
    throw new GamingAddonCreatePersistenceError(
      'write_verification_failed',
      'The Gaming item recovery receipt did not remain saved.',
    );
  }
  const readBack = parseStoredAttempt(raw);
  if (!attemptsEqual(readBack, attempt)) {
    throw new GamingAddonCreatePersistenceError(
      'write_verification_failed',
      'The Gaming item recovery receipt changed while it was being saved.',
    );
  }
  return readBack;
}

export async function sendDurablyPersistedGamingAddon<Response>({
  storageProvider,
  context,
  factories,
  send,
}: {
  storageProvider: StorageProvider;
  context: GamingAddonCreateAttemptContext;
  factories: { clientLineId: () => string; idempotencyKey: () => string };
  send: (attempt: DurableGamingAddonCreateAttempt) => Promise<Response>;
}): Promise<{ response: Response; attempt: DurableGamingAddonCreateAttempt }> {
  const attempt = prepareGamingAddonCreateAttempt({ storageProvider, context, factories });
  const response = await send(attempt);
  return { response, attempt };
}

export function clearGamingAddonCreateAttempt({
  storageProvider,
  expectedAttempt,
}: {
  storageProvider: StorageProvider;
  expectedAttempt: DurableGamingAddonCreateAttempt;
}): void {
  const storage = getStorage(storageProvider);
  const key = gamingAddonCreateStorageKey(expectedAttempt);
  const raw = readStorage(storage, key);
  if (raw === null) return;
  const stored = parseStoredAttempt(raw);
  if (!attemptsEqual(stored, expectedAttempt)) {
    throw new GamingAddonCreatePersistenceError(
      'clear_verification_failed',
      'The saved Gaming item receipt changed and was not cleared.',
    );
  }
  try {
    storage.removeItem(key);
  } catch {
    throw new GamingAddonCreatePersistenceError(
      'clear_verification_failed',
      'The confirmed Gaming item receipt could not be cleared.',
    );
  }
  if (readStorage(storage, key) !== null) {
    throw new GamingAddonCreatePersistenceError(
      'clear_verification_failed',
      'The confirmed Gaming item receipt remained in device storage.',
    );
  }
}

export async function withGamingAddonCreateLock<Response>({
  scope,
  lockProvider,
  action,
}: {
  scope: GamingAddonCreateTerminalScope;
  lockProvider: LockProvider;
  action: () => Promise<Response>;
}): Promise<Response> {
  assertTerminalScope(scope);
  let locks: GamingAddonCreateLockManager | null | undefined;
  try {
    locks = lockProvider();
  } catch {
    locks = null;
  }
  if (!locks || typeof locks.request !== 'function') {
    throw new GamingAddonCreatePersistenceError(
      'cross_tab_lock_unavailable',
      'This browser cannot lock Gaming item recovery safely across tabs.',
    );
  }
  try {
    return await locks.request(
      `dcompany:gaming-addon-create:${gamingAddonCreateStorageKey(scope)}`,
      { mode: 'exclusive' },
      async (lock) => {
        if (!lock) {
          throw new GamingAddonCreatePersistenceError(
            'cross_tab_lock_unavailable',
            'The Gaming item cross-tab lock was not acquired.',
          );
        }
        return action();
      },
    );
  } catch (cause) {
    if (cause instanceof GamingAddonCreatePersistenceError) throw cause;
    throw new GamingAddonCreatePersistenceError(
      'cross_tab_lock_unavailable',
      'The Gaming item cross-tab lock failed before the action completed.',
    );
  }
}

export function gamingAddonCreatePersistenceGuidance(
  error: GamingAddonCreatePersistenceError,
): string {
  if (error.code === 'attempt_conflict') {
    return 'Review the original session item first. The app will not replace its saved line ID or receipt key.';
  }
  if (error.code === 'scope_mismatch' || error.code === 'corrupt_attempt') {
    return 'Do not clear browser data or create a replacement item. Use the original employee and terminal, or ask a protected owner to verify the session ledger.';
  }
  if (error.code === 'cross_tab_lock_unavailable') {
    return 'Close duplicate ERP tabs or use the supported app/browser, refresh Gaming, and retry.';
  }
  if (error.code === 'current_scope_unverified') {
    return 'Refresh Gaming and confirm the staff, device context, and current shift before adding another item.';
  }
  if (error.code === 'clear_verification_failed') {
    return 'The server result may be confirmed, but the local recovery receipt could not be cleared. Refresh Gaming and retry the exact saved item; do not create a replacement.';
  }
  return 'Check site storage or free device storage, reload Gaming, and retry. No item was sent without a verified recovery receipt.';
}
