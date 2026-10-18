import type {
  DailyTenderCommitDTO,
  PaymentTenderCommitDTO,
  TenderReceiptDTO,
} from '@/lib/erp-api';
import type { OwnerIdentity } from '@/lib/owner-workspace';
import { ownerScope } from '@/lib/owner-workspace';
import type { MutationIntentStorage } from '@/lib/stable-mutation-intent';

export type TenderAction =
  | {
      version: 1;
      scope: string;
      key: string;
      operation: 'payment';
      body: PaymentTenderCommitDTO;
    }
  | {
      version: 1;
      scope: string;
      key: string;
      operation: 'daily';
      body: DailyTenderCommitDTO;
    };
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value);
export function validTenderAction(
  value: unknown,
  scope: string,
): value is TenderAction {
  if (
    !record(value) ||
    value.version !== 1 ||
    value.scope !== scope ||
    typeof value.key !== 'string' ||
    !value.key.startsWith('tender:web:') ||
    value.key.length > 160 ||
    value.key.length <= 'tender:web:'.length ||
    Object.keys(value).some(
      (key) => !['version', 'scope', 'key', 'operation', 'body'].includes(key),
    ) ||
    !record(value.body)
  )
    return false;
  const body = value.body;
  if (
    typeof body.source_fingerprint !== 'string' ||
    !/^[a-f0-9]{64}$/.test(body.source_fingerprint) ||
    typeof body.reason !== 'string' ||
    body.reason.trim().length < 3 ||
    body.reason.length > 500
  )
    return false;
  if (
    body.evidence_reference !== undefined &&
    body.evidence_reference !== null &&
    (typeof body.evidence_reference !== 'string' ||
      body.evidence_reference.length > 200)
  )
    return false;
  const common = ['source_fingerprint', 'reason', 'evidence_reference'];
  if (value.operation === 'payment')
    return (
      typeof body.payment_id === 'string' &&
      Object.keys(body).every((key) => [...common, 'payment_id'].includes(key))
    );
  return (
    value.operation === 'daily' &&
    typeof body.branch_id === 'string' &&
    body.branch_id === scope.split(':').at(-1) &&
    typeof body.business_date === 'string' &&
    /^\d{4}-\d{2}-\d{2}$/.test(body.business_date) &&
    Number.isSafeInteger(body.actual_cash_minor) &&
    Number.isSafeInteger(body.actual_upi_minor) &&
    Number(body.actual_cash_minor) >= 0 &&
    Number(body.actual_upi_minor) >= 0 &&
    Object.keys(body).every((key) =>
      [
        ...common,
        'branch_id',
        'business_date',
        'actual_cash_minor',
        'actual_upi_minor',
      ].includes(key),
    )
  );
}
/** No password/token is persisted. Exact nonsecret commands survive a lost reply
 * or reload, bound to the same tenant/actor/branch; fresh owner unlock is required.
 * Storage errors block submission instead of promising recovery we cannot retain. */
export class TenderJournal {
  readonly scope: string;
  private readonly storageKey: string;
  constructor(
    identity: OwnerIdentity,
    private readonly storage: MutationIntentStorage | null,
  ) {
    this.scope = ownerScope(identity);
    this.storageKey = `dcompany:tender:v1:${this.scope}`;
  }
  read(): TenderAction | null {
    if (!this.storage)
      throw new Error(
        'Recovery storage is unavailable. Enable browser storage before correcting money.',
      );
    const raw = this.storage.getItem(this.storageKey);
    if (!raw) return null;
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      throw new Error(
        'A saved correction cannot be read. Its evidence was kept; ask the owner to review it.',
      );
    }
    if (!validTenderAction(parsed, this.scope))
      throw new Error(
        'Saved correction identity needs review. Its evidence was kept; no new request was sent.',
      );
    return parsed;
  }
  save(action: TenderAction): void {
    if (!this.storage || !validTenderAction(action, this.scope))
      throw new Error('Correction recovery identity could not be saved.');
    const existing = this.read();
    if (existing && JSON.stringify(existing) !== JSON.stringify(action))
      throw new Error(
        'Recover the saved correction before preparing another action.',
      );
    const raw = JSON.stringify(action);
    this.storage.setItem(this.storageKey, raw);
    if (this.storage.getItem(this.storageKey) !== raw)
      throw new Error(
        'Correction recovery storage could not be verified. Nothing was submitted.',
      );
  }
  clear(expected: TenderAction): void {
    if (this.read()?.key === expected.key)
      this.storage?.removeItem(this.storageKey);
  }
}
export function matchesTenderReceipt(
  action: TenderAction,
  receipt: TenderReceiptDTO,
  identity: OwnerIdentity,
): boolean {
  return (
    receipt.idempotency_key === action.key &&
    receipt.company_id === identity.company_id &&
    receipt.branch_id === identity.branch_id &&
    receipt.recorded_by === identity.user_id &&
    receipt.source_fingerprint === action.body.source_fingerprint &&
    receipt.reason === action.body.reason &&
    (receipt.evidence_reference ?? null) ===
      (action.body.evidence_reference ?? null) &&
    (action.operation === 'payment'
      ? receipt.operation === 'payment_method_corrected' &&
        receipt.payment_id === action.body.payment_id
      : ['daily_collections_reconciled', 'daily_actuals_recorded'].includes(
          receipt.operation,
        ) &&
        receipt.business_date === action.body.business_date &&
        receipt.actual_cash_minor === action.body.actual_cash_minor &&
        receipt.actual_upi_minor === action.body.actual_upi_minor)
  );
}

/** One owner/branch command at a time across tabs; localStorage read/write alone
 * cannot make reservation atomic. The server still owns transaction identity. */
export async function withTenderRecoveryLock(
  scope: string,
  run: () => Promise<void>,
): Promise<void> {
  if (typeof navigator === 'undefined' || !navigator.locks?.request)
    throw new Error(
      'This browser cannot safely coordinate correction recovery across tabs. Use an up-to-date supported browser; the saved request remains intact.',
    );
  return navigator.locks.request(`dcompany:tender-recovery:${scope}`, run);
}
