const STORAGE_PREFIX = 'dcompany:gaming-extension:v3:';
const LEGACY_V2_STORAGE_PREFIX = 'dcompany:gaming-extension:v2:';
const LEGACY_STORAGE_PREFIX = 'dcompany:gaming-extension:';
const IDEMPOTENCY_PREFIX = 'gaming-extension:';

export type PaidExtensionPersistenceErrorCode =
  | 'current_scope_unverified'
  | 'storage_unavailable'
  | 'legacy_attempt'
  | 'corrupt_attempt'
  | 'scope_mismatch'
  | 'session_attempt_conflict'
  | 'replay_receipt_missing'
  | 'replay_receipt_changed'
  | 'cross_tab_lock_unavailable'
  | 'write_verification_failed'
  | 'clear_verification_failed';

export class PaidExtensionPersistenceError extends Error {
  readonly code: PaidExtensionPersistenceErrorCode;

  constructor(code: PaidExtensionPersistenceErrorCode, message: string) {
    super(message);
    this.name = 'PaidExtensionPersistenceError';
    this.code = code;
  }
}

export interface PaidExtensionAttemptContext {
  readonly actorUserId: string;
  readonly companyId: string;
  readonly branchId: string;
  readonly terminalId: string;
  readonly sessionId: string;
  readonly shiftId: string;
  readonly packageId: string;
  readonly packagePriceMinor: number;
  readonly packageDurationMinutes: number;
  readonly packageVariant: string;
  readonly expectedTimerMinutes: number;
  readonly expectedAmountMinor: number;
}

export type PaidExtensionAttemptScope = Pick<
  PaidExtensionAttemptContext,
  'actorUserId' | 'companyId' | 'branchId' | 'terminalId' | 'sessionId' | 'shiftId'
>;

export type PaidExtensionTerminalScope = Pick<
  PaidExtensionAttemptContext,
  'actorUserId' | 'companyId' | 'branchId' | 'terminalId'
>;

export interface PaidExtensionAttempt extends PaidExtensionAttemptContext {
  readonly version: 3;
  readonly idempotencyKey: string;
}

export interface PaidExtensionStorage {
  readonly length: number;
  key(index: number): string | null;
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface PaidExtensionLockManager {
  request<Response>(
    name: string,
    options: { mode: 'exclusive' },
    callback: (lock: unknown) => Promise<Response> | Response,
  ): Promise<Response>;
}

export interface PaidExtensionAttemptInventory {
  readonly attempts: readonly PaidExtensionAttempt[];
  readonly issueCodes: readonly Extract<
    PaidExtensionPersistenceErrorCode,
    'legacy_attempt' | 'corrupt_attempt' | 'scope_mismatch'
  >[];
}

export type PaidExtensionSubmissionMode = 'new' | 'replay' | 'blocked';

export function paidExtensionRecoveryRequired(
  billingMode: 'hourly' | 'package' | 'legacy_ambiguous' | undefined,
): boolean {
  return billingMode !== 'hourly';
}

export function isPaidExtensionLifecycleBlocked({
  billingMode,
  hasSavedAttempt,
  hasRecoveryError,
}: {
  billingMode: 'hourly' | 'package' | 'legacy_ambiguous' | undefined;
  hasSavedAttempt: boolean;
  hasRecoveryError: boolean;
}): boolean {
  return paidExtensionRecoveryRequired(billingMode)
    && (hasSavedAttempt || hasRecoveryError);
}

export function paidExtensionSubmissionMode({
  savedAttempt,
  requestedPackageId,
  ownsCurrentShift,
}: {
  savedAttempt: PaidExtensionAttempt | null;
  requestedPackageId: string;
  ownsCurrentShift: boolean;
}): PaidExtensionSubmissionMode {
  if (savedAttempt) {
    return savedAttempt.packageId === requestedPackageId ? 'replay' : 'blocked';
  }
  return ownsCurrentShift ? 'new' : 'blocked';
}

type StorageProvider = () => PaidExtensionStorage;
type LockProvider = () => PaidExtensionLockManager | null | undefined;

const ATTEMPT_FIELDS = [
  'version',
  'idempotencyKey',
  'actorUserId',
  'companyId',
  'branchId',
  'terminalId',
  'sessionId',
  'shiftId',
  'packageId',
  'packagePriceMinor',
  'packageDurationMinutes',
  'packageVariant',
  'expectedTimerMinutes',
  'expectedAmountMinor',
] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown, maxLength = 255): value is string {
  return typeof value === 'string'
    && value.length > 0
    && value.length <= maxLength
    && value === value.trim();
}

function isWholeMinor(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isInteger(value)
    && value >= 0
    && value <= 9_999_999_999;
}

function isTimerMinutes(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isInteger(value)
    && value >= 1
    && value <= 1440;
}

function hasExactAttemptFields(value: Record<string, unknown>): boolean {
  const keys = Object.keys(value);
  return keys.length === ATTEMPT_FIELDS.length
    && ATTEMPT_FIELDS.every((field) => Object.prototype.hasOwnProperty.call(value, field));
}

export function parsePaidExtensionAttempt(value: unknown): PaidExtensionAttempt | null {
  if (!isRecord(value) || !hasExactAttemptFields(value) || value.version !== 3) return null;
  if (
    !isNonEmptyString(value.idempotencyKey)
    || !value.idempotencyKey.startsWith(IDEMPOTENCY_PREFIX)
    || !isNonEmptyString(value.actorUserId)
    || !isNonEmptyString(value.companyId)
    || !isNonEmptyString(value.branchId)
    || !isNonEmptyString(value.terminalId)
    || !isNonEmptyString(value.sessionId)
    || !isNonEmptyString(value.shiftId)
    || !isNonEmptyString(value.packageId)
    || !isWholeMinor(value.packagePriceMinor)
    || !isTimerMinutes(value.packageDurationMinutes)
    || !isNonEmptyString(value.packageVariant, 20)
    || !isTimerMinutes(value.expectedTimerMinutes)
    || !isWholeMinor(value.expectedAmountMinor)
  ) return null;

  return value as unknown as PaidExtensionAttempt;
}

function assertContext(context: PaidExtensionAttemptContext): void {
  if (
    !isNonEmptyString(context.actorUserId)
    || !isNonEmptyString(context.companyId)
    || !isNonEmptyString(context.branchId)
    || !isNonEmptyString(context.terminalId)
    || !isNonEmptyString(context.sessionId)
    || !isNonEmptyString(context.shiftId)
    || !isNonEmptyString(context.packageId)
    || !isWholeMinor(context.packagePriceMinor)
    || !isTimerMinutes(context.packageDurationMinutes)
    || !isNonEmptyString(context.packageVariant, 20)
    || !isTimerMinutes(context.expectedTimerMinutes)
    || !isWholeMinor(context.expectedAmountMinor)
  ) {
    throw new PaidExtensionPersistenceError(
      'current_scope_unverified',
      'The current paid-extension scope or pricing snapshot is incomplete.',
    );
  }
}

function assertScope(scope: PaidExtensionAttemptScope): void {
  if (
    !isNonEmptyString(scope.actorUserId)
    || !isNonEmptyString(scope.companyId)
    || !isNonEmptyString(scope.branchId)
    || !isNonEmptyString(scope.terminalId)
    || !isNonEmptyString(scope.sessionId)
    || !isNonEmptyString(scope.shiftId)
  ) {
    throw new PaidExtensionPersistenceError(
      'current_scope_unverified',
      'The current paid-extension operating scope is incomplete.',
    );
  }
}

function assertTerminalScope(scope: PaidExtensionTerminalScope): void {
  if (
    !isNonEmptyString(scope.actorUserId)
    || !isNonEmptyString(scope.companyId)
    || !isNonEmptyString(scope.branchId)
    || !isNonEmptyString(scope.terminalId)
  ) {
    throw new PaidExtensionPersistenceError(
      'current_scope_unverified',
      'The current paid-extension terminal scope is incomplete.',
    );
  }
}

function encodedIdentity(value: string): string {
  return encodeURIComponent(value);
}

export async function withPaidExtensionSessionLock<Response>({
  sessionId,
  lockProvider,
  action,
}: {
  sessionId: string;
  lockProvider: LockProvider;
  action: () => Promise<Response>;
}): Promise<Response> {
  if (!isNonEmptyString(sessionId)) {
    throw new PaidExtensionPersistenceError(
      'current_scope_unverified',
      'The session identity required for paid-extension locking is missing.',
    );
  }
  let lockManager: PaidExtensionLockManager | null | undefined;
  try {
    lockManager = lockProvider();
  } catch {
    lockManager = null;
  }
  if (!lockManager || typeof lockManager.request !== 'function') {
    throw new PaidExtensionPersistenceError(
      'cross_tab_lock_unavailable',
      'This browser cannot lock paid-extension recovery safely across tabs.',
    );
  }
  try {
    return await lockManager.request(
      `dcompany:gaming-extension:${encodedIdentity(sessionId)}`,
      { mode: 'exclusive' },
      async (lock) => {
        if (!lock) {
          throw new PaidExtensionPersistenceError(
            'cross_tab_lock_unavailable',
            'The paid-extension cross-tab lock was not acquired.',
          );
        }
        return action();
      },
    );
  } catch (cause) {
    if (cause instanceof PaidExtensionPersistenceError) throw cause;
    throw new PaidExtensionPersistenceError(
      'cross_tab_lock_unavailable',
      'The paid-extension cross-tab lock failed before the action completed.',
    );
  }
}

export function paidExtensionStorageKey(
  context: Pick<PaidExtensionAttemptContext, 'sessionId'>,
): string {
  return `${STORAGE_PREFIX}${encodedIdentity(context.sessionId)}`;
}

export function legacyV2PaidExtensionStorageKey(
  context: Pick<PaidExtensionAttemptContext, 'sessionId' | 'packageId'>,
): string {
  return `${LEGACY_V2_STORAGE_PREFIX}${encodedIdentity(context.sessionId)}:${encodedIdentity(context.packageId)}`;
}

export function legacyPaidExtensionStorageKey(
  context: Pick<PaidExtensionAttemptContext, 'sessionId' | 'packageId'>,
): string {
  return `${LEGACY_STORAGE_PREFIX}${context.sessionId}:${context.packageId}`;
}

function getStorage(storageProvider: StorageProvider): PaidExtensionStorage {
  try {
    const storage = storageProvider();
    if (!storage) throw new Error('storage missing');
    return storage;
  } catch {
    throw new PaidExtensionPersistenceError(
      'storage_unavailable',
      'The device storage used for paid-extension recovery is unavailable.',
    );
  }
}

function readStorage(storage: PaidExtensionStorage, key: string): string | null {
  try {
    return storage.getItem(key);
  } catch {
    throw new PaidExtensionPersistenceError(
      'storage_unavailable',
      'The saved paid-extension recovery receipt could not be read.',
    );
  }
}

function storageKeys(storage: PaidExtensionStorage): string[] {
  try {
    if (!Number.isInteger(storage.length) || storage.length < 0 || typeof storage.key !== 'function') {
      throw new Error('storage enumeration unavailable');
    }
    const keys: string[] = [];
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (key !== null) keys.push(key);
    }
    return keys;
  } catch {
    throw new PaidExtensionPersistenceError(
      'storage_unavailable',
      'Saved paid-extension receipts could not be enumerated safely.',
    );
  }
}

function parseStoredAttempt(raw: string): PaidExtensionAttempt {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new PaidExtensionPersistenceError(
      'corrupt_attempt',
      'The saved paid-extension recovery receipt is damaged.',
    );
  }
  const attempt = parsePaidExtensionAttempt(parsed);
  if (!attempt) {
    throw new PaidExtensionPersistenceError(
      'corrupt_attempt',
      'The saved paid-extension recovery receipt is incomplete or has an unsupported format.',
    );
  }
  return attempt;
}

function hasExactOperatingScope(
  attempt: PaidExtensionAttempt,
  context: PaidExtensionAttemptScope,
): boolean {
  return attempt.actorUserId === context.actorUserId
    && attempt.companyId === context.companyId
    && attempt.branchId === context.branchId
    && attempt.terminalId === context.terminalId
    && attempt.sessionId === context.sessionId
    && attempt.shiftId === context.shiftId;
}

function hasExactTerminalScope(
  attempt: PaidExtensionAttempt,
  scope: PaidExtensionTerminalScope,
): boolean {
  return attempt.actorUserId === scope.actorUserId
    && attempt.companyId === scope.companyId
    && attempt.branchId === scope.branchId
    && attempt.terminalId === scope.terminalId;
}

export function inspectPaidExtensionAttemptsForTerminal({
  storageProvider,
  scope,
}: {
  storageProvider: StorageProvider;
  scope: PaidExtensionTerminalScope;
}): PaidExtensionAttemptInventory {
  assertTerminalScope(scope);
  const storage = getStorage(storageProvider);
  const attempts: PaidExtensionAttempt[] = [];
  const issueCodes: Array<'legacy_attempt' | 'corrupt_attempt' | 'scope_mismatch'> = [];

  for (const key of storageKeys(storage)) {
    if (key.startsWith(STORAGE_PREFIX)) {
      const raw = readStorage(storage, key);
      if (raw === null) continue;
      try {
        const attempt = parseStoredAttempt(raw);
        if (paidExtensionStorageKey(attempt) !== key) {
          issueCodes.push('corrupt_attempt');
        } else if (!hasExactTerminalScope(attempt, scope)) {
          issueCodes.push('scope_mismatch');
        } else {
          attempts.push(attempt);
        }
      } catch (cause) {
        if (cause instanceof PaidExtensionPersistenceError) {
          issueCodes.push('corrupt_attempt');
        } else {
          throw cause;
        }
      }
      continue;
    }
    if (key.startsWith(LEGACY_V2_STORAGE_PREFIX)) {
      issueCodes.push('legacy_attempt');
      continue;
    }
    if (key.startsWith(LEGACY_STORAGE_PREFIX)) {
      issueCodes.push('legacy_attempt');
    }
  }

  return { attempts, issueCodes };
}

export function inspectPaidExtensionAttemptForSession({
  storageProvider,
  scope,
}: {
  storageProvider: StorageProvider;
  scope: PaidExtensionAttemptScope;
}): PaidExtensionAttempt | null {
  assertScope(scope);
  const storage = getStorage(storageProvider);
  const existingKeys = storageKeys(storage);
  const encodedSession = encodedIdentity(scope.sessionId);
  const hasLegacySessionReceipt = existingKeys.some((key) => (
    key.startsWith(`${LEGACY_V2_STORAGE_PREFIX}${encodedSession}:`)
    || key.startsWith(`${LEGACY_STORAGE_PREFIX}${scope.sessionId}:`)
  ));
  if (hasLegacySessionReceipt) {
    throw new PaidExtensionPersistenceError(
      'legacy_attempt',
      'An older paid-extension attempt exists without a complete session-scoped receipt.',
    );
  }

  const raw = readStorage(storage, paidExtensionStorageKey(scope));
  if (raw === null) return null;
  const attempt = parseStoredAttempt(raw);
  if (!hasExactOperatingScope(attempt, scope)) {
    throw new PaidExtensionPersistenceError(
      'scope_mismatch',
      'The saved paid-extension receipt belongs to a different employee or operating scope.',
    );
  }
  return attempt;
}

function attemptsEqual(left: PaidExtensionAttempt, right: PaidExtensionAttempt): boolean {
  return ATTEMPT_FIELDS.every((field) => left[field] === right[field]);
}

export function preparePaidExtensionAttempt({
  storageProvider,
  context,
  createIdempotencyKey,
}: {
  storageProvider: StorageProvider;
  context: PaidExtensionAttemptContext;
  createIdempotencyKey: () => string;
}): PaidExtensionAttempt {
  assertContext(context);
  const storage = getStorage(storageProvider);
  const storageKey = paidExtensionStorageKey(context);
  const stored = inspectPaidExtensionAttemptForSession({
    storageProvider: () => storage,
    scope: context,
  });
  if (stored !== null) {
    if (stored.packageId !== context.packageId) {
      throw new PaidExtensionPersistenceError(
        'session_attempt_conflict',
        'This session already has an unresolved paid extension for a different package.',
      );
    }
    // A replay deliberately keeps every original expected snapshot. The live
    // package/session may already include the charge after a lost response.
    return stored;
  }

  let idempotencyKey: string;
  try {
    idempotencyKey = createIdempotencyKey();
  } catch {
    throw new PaidExtensionPersistenceError(
      'write_verification_failed',
      'A durable paid-extension identity could not be created.',
    );
  }
  if (!isNonEmptyString(idempotencyKey) || !idempotencyKey.startsWith(IDEMPOTENCY_PREFIX)) {
    throw new PaidExtensionPersistenceError(
      'write_verification_failed',
      'A durable paid-extension identity could not be verified.',
    );
  }

  const attempt: PaidExtensionAttempt = {
    version: 3,
    idempotencyKey,
    ...context,
  };
  try {
    storage.setItem(storageKey, JSON.stringify(attempt));
  } catch {
    throw new PaidExtensionPersistenceError(
      'storage_unavailable',
      'The paid-extension recovery receipt could not be saved.',
    );
  }

  const readBackRaw = readStorage(storage, storageKey);
  if (readBackRaw === null) {
    throw new PaidExtensionPersistenceError(
      'write_verification_failed',
      'The paid-extension recovery receipt did not remain saved.',
    );
  }
  const readBack = parseStoredAttempt(readBackRaw);
  if (!attemptsEqual(readBack, attempt)) {
    throw new PaidExtensionPersistenceError(
      'write_verification_failed',
      'The paid-extension recovery receipt changed while it was being saved.',
    );
  }
  return readBack;
}

export async function sendDurablyPersistedPaidExtension<Response>({
  storageProvider,
  context,
  createIdempotencyKey,
  send,
}: {
  storageProvider: StorageProvider;
  context: PaidExtensionAttemptContext;
  createIdempotencyKey: () => string;
  send: (attempt: PaidExtensionAttempt) => Promise<Response>;
}): Promise<{ response: Response; attempt: PaidExtensionAttempt }> {
  const attempt = preparePaidExtensionAttempt({
    storageProvider,
    context,
    createIdempotencyKey,
  });
  const response = await send(attempt);
  return { response, attempt };
}

export async function replayDurablyPersistedPaidExtension<Response>({
  storageProvider,
  expectedAttempt,
  send,
}: {
  storageProvider: StorageProvider;
  expectedAttempt: PaidExtensionAttempt;
  send: (attempt: PaidExtensionAttempt) => Promise<Response>;
}): Promise<{ response: Response; attempt: PaidExtensionAttempt }> {
  const parsedExpected = parsePaidExtensionAttempt(expectedAttempt);
  if (!parsedExpected) {
    throw new PaidExtensionPersistenceError(
      'replay_receipt_changed',
      'The expected paid-extension replay receipt is incomplete or invalid.',
    );
  }
  const stored = inspectPaidExtensionAttemptForSession({
    storageProvider,
    scope: parsedExpected,
  });
  if (stored === null) {
    throw new PaidExtensionPersistenceError(
      'replay_receipt_missing',
      'The paid-extension receipt selected for replay no longer exists.',
    );
  }
  if (!attemptsEqual(stored, parsedExpected)) {
    throw new PaidExtensionPersistenceError(
      'replay_receipt_changed',
      'The paid-extension receipt changed after it was selected for replay.',
    );
  }
  const response = await send(stored);
  return { response, attempt: stored };
}

export function clearPaidExtensionAttempt({
  storageProvider,
  expectedAttempt,
}: {
  storageProvider: StorageProvider;
  expectedAttempt: PaidExtensionAttempt;
}): void {
  const storage = getStorage(storageProvider);
  const storageKey = paidExtensionStorageKey(expectedAttempt);
  const raw = readStorage(storage, storageKey);
  if (raw === null) return;
  const stored = parseStoredAttempt(raw);
  if (!attemptsEqual(stored, expectedAttempt)) {
    throw new PaidExtensionPersistenceError(
      'clear_verification_failed',
      'The saved paid-extension receipt changed and was not cleared.',
    );
  }
  try {
    storage.removeItem(storageKey);
  } catch {
    throw new PaidExtensionPersistenceError(
      'clear_verification_failed',
      'The confirmed paid-extension receipt could not be cleared.',
    );
  }
  if (readStorage(storage, storageKey) !== null) {
    throw new PaidExtensionPersistenceError(
      'clear_verification_failed',
      'The confirmed paid-extension receipt remained in device storage.',
    );
  }
}

export function paidExtensionPersistenceGuidance(error: PaidExtensionPersistenceError): string {
  if (error.code === 'cross_tab_lock_unavailable') {
    return 'Paid extension was not sent because this browser could not prevent another tab from changing the same session at the same time. Close duplicate ERP tabs or use the supported app/browser, refresh Gaming, and retry.';
  }
  if (error.code === 'replay_receipt_missing' || error.code === 'replay_receipt_changed') {
    return 'The saved extension was not replayed because its recovery receipt disappeared or changed. Refresh Gaming before taking any action. Do not create a replacement charge.';
  }
  if (error.code === 'session_attempt_conflict') {
    return 'Paid extension was not sent because this session already has a different saved extension awaiting confirmation. Reopen the original extension so the app can replay its exact receipt; do not choose another package.';
  }
  if (error.code === 'scope_mismatch') {
    return 'Paid extension was not sent because the saved attempt belongs to another employee, company, branch, terminal, or shift. Do not create a replacement. Sign in as the original employee on the original terminal, or ask a protected owner to verify the session.';
  }
  if (error.code === 'legacy_attempt' || error.code === 'corrupt_attempt') {
    return 'Paid extension was not sent because this session already has an older or damaged saved attempt that cannot prove whether it was charged. Do not retry with a new extension. A protected owner must verify the session and extension receipt first.';
  }
  if (error.code === 'current_scope_unverified') {
    return 'Paid extension was not sent because the employee, company, branch, terminal, session, shift, or pricing snapshot could not be verified. Refresh Gaming and confirm this terminal has the correct open shift.';
  }
  return 'Paid extension was not sent because this device could not save and verify its recovery receipt. Check site storage or free device storage, reload Gaming, and try again. Do not take payment or add the extension manually.';
}
