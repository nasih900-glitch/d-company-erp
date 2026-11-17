import type {
  ReceiptCorrectionCommitDTO,
  ReceiptCorrectionResultDTO,
  ReceiptStockCorrectionCommitDTO,
  ReceiptStockCorrectionResultDTO,
} from "@/lib/erp-api";
import { ownerScope, type OwnerIdentity } from "@/lib/owner-workspace";
import type { MutationIntentStorage } from "@/lib/stable-mutation-intent";

const record = (v: unknown): v is Record<string, unknown> =>
  !!v && typeof v === "object" && !Array.isArray(v);
const keys = (v: Record<string, unknown>, expected: string[]) =>
  Object.keys(v).length === expected.length &&
  expected.every((k) => Object.hasOwn(v, k));
const uuid = (v: unknown): v is string =>
  typeof v === "string" &&
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(v);
const positiveMinor = (v: unknown): v is number =>
  typeof v === "number" && Number.isSafeInteger(v) && v > 0;
export interface ReceiptCorrectionAction {
  version: 1;
  scope: string;
  key: string;
  body: ReceiptCorrectionCommitDTO | ReceiptStockCorrectionCommitDTO;
  expected: {
    invoice_no: string;
    payment_id: string;
    original_shift_id: string;
    amount_minor: number;
    stock?: { count: number; inventory_increase_minor: number };
  };
}
interface Envelope {
  version: 1;
  scope: string;
  generation: number;
  pending: ReceiptCorrectionAction | null;
}
export function validCorrectionAction(
  value: unknown,
  scope: string,
): value is ReceiptCorrectionAction {
  if (
    !record(value) ||
    !keys(value, ["version", "scope", "key", "body", "expected"]) ||
    value.version !== 1 ||
    value.scope !== scope ||
    typeof value.key !== "string" ||
    !value.key.startsWith("pos-no-value:web:") ||
    !uuid(value.key.slice("pos-no-value:web:".length)) ||
    !record(value.body) ||
    !record(value.expected)
  )
    return false;
  const b = value.body,
    e = value.expected;
  return (
    keys(b, [
      "order_id",
      "source_fingerprint",
      "reason",
      "no_funds_received",
      "all_items_not_supplied",
      "acknowledges_no_payout",
      ...(b.restore_stock === true
        ? ["restore_stock", "stock_never_consumed", "stock_fingerprint"]
        : []),
    ]) &&
    (b.restore_stock !== true ||
      (b.stock_never_consumed === true &&
        typeof b.stock_fingerprint === "string" &&
        /^[a-f0-9]{64}$/.test(b.stock_fingerprint))) &&
    uuid(b.order_id) &&
    typeof b.source_fingerprint === "string" &&
    /^[a-f0-9]{64}$/.test(b.source_fingerprint) &&
    b.no_funds_received === true &&
    b.all_items_not_supplied === true &&
    b.acknowledges_no_payout === true &&
    typeof b.reason === "string" &&
    b.reason === b.reason.trim() &&
    b.reason.length >= 3 &&
    b.reason.length <= 500 &&
    keys(e, [
      "invoice_no",
      "payment_id",
      "original_shift_id",
      "amount_minor",
      ...(b.restore_stock === true ? ["stock"] : []),
    ]) &&
    (b.restore_stock !== true ||
      (record(e.stock) &&
        keys(e.stock, ["count", "inventory_increase_minor"]) &&
        positiveMinor(e.stock.count) &&
        typeof e.stock.inventory_increase_minor === "number" &&
        Number.isSafeInteger(e.stock.inventory_increase_minor) &&
        e.stock.inventory_increase_minor >= 0)) &&
    typeof e.invoice_no === "string" &&
    e.invoice_no.length > 0 &&
    e.invoice_no.length <= 32 &&
    uuid(e.payment_id) &&
    uuid(e.original_shift_id) &&
    positiveMinor(e.amount_minor)
  );
}
/** One envelope makes generation acknowledgement + pending-command removal atomic.
 * Only the bounded command/receipt identity is retained, never credentials or source snapshots. */
export class ReceiptCorrectionJournal {
  readonly scope: string;
  private readonly key: string;
  constructor(
    identity: OwnerIdentity,
    private readonly storage: MutationIntentStorage | null,
  ) {
    this.scope = ownerScope(identity);
    this.key = `dcompany:pos-no-value:v1:${this.scope}`;
  }
  read(): Envelope {
    if (!this.storage)
      throw new Error(
        "Recovery storage is unavailable. Enable browser storage and reload; nothing was submitted.",
      );
    const raw = this.storage.getItem(this.key);
    if (raw === null)
      return { version: 1, scope: this.scope, generation: 0, pending: null };
    let value: unknown;
    try {
      value = JSON.parse(raw);
    } catch {
      throw new Error(
        "Saved correction storage is unreadable. Evidence was kept; no new action can be sent.",
      );
    }
    if (
      !record(value) ||
      !keys(value, ["version", "scope", "generation", "pending"]) ||
      value.version !== 1 ||
      value.scope !== this.scope ||
      typeof value.generation !== "number" ||
      !Number.isSafeInteger(value.generation) ||
      value.generation < 0 ||
      (value.pending !== null &&
        !validCorrectionAction(value.pending, this.scope))
    )
      throw new Error(
        "Saved correction identity needs review. Evidence was kept; no new action can be sent.",
      );
    return value as unknown as Envelope;
  }
  private write(value: Envelope) {
    if (!this.storage) throw new Error("Recovery storage is unavailable.");
    const raw = JSON.stringify(value);
    this.storage.setItem(this.key, raw);
    if (this.storage.getItem(this.key) !== raw)
      throw new Error(
        "Recovery storage could not be verified. Keep this page open; do not create another action.",
      );
  }
  save(action: ReceiptCorrectionAction, expectedGeneration: number) {
    if (!validCorrectionAction(action, this.scope))
      throw new Error("Correction command is invalid; nothing was submitted.");
    const state = this.read();
    if (state.generation !== expectedGeneration)
      throw new Error(
        "Another tab completed a correction. Check eligibility again before creating a new action.",
      );
    if (
      state.pending &&
      JSON.stringify(state.pending) !== JSON.stringify(action)
    )
      throw new Error(
        "Recover the saved correction before preparing another action.",
      );
    this.write({ ...state, pending: action });
  }
  complete(action: ReceiptCorrectionAction) {
    const state = this.read();
    if (JSON.stringify(state.pending) !== JSON.stringify(action))
      throw new Error(
        "Correction saved; local recovery acknowledgement needs review. Keep the original command.",
      );
    this.write({ ...state, generation: state.generation + 1, pending: null });
  }
}
export function matchesCorrectionResult(
  action: ReceiptCorrectionAction,
  value: unknown,
): value is ReceiptCorrectionResultDTO | ReceiptStockCorrectionResultDTO {
  if (
    !record(value) ||
    !keys(value, [
      "id",
      "order_id",
      "payment_id",
      "original_shift_id",
      "invoice_no",
      "kind",
      "amount_minor",
      "accounting_at",
      "payout_minor",
      "original_records_unchanged",
      ...(action.expected.stock
        ? [
            "stock_restored",
            "restored_movement_count",
            "inventory_increase_minor",
          ]
        : []),
    ])
  )
    return false;
  return (
    uuid(value.id) &&
    value.order_id === action.body.order_id &&
    value.payment_id === action.expected.payment_id &&
    value.original_shift_id === action.expected.original_shift_id &&
    value.invoice_no === action.expected.invoice_no &&
    value.kind === "payment_not_received_full_mistake" &&
    value.amount_minor === action.expected.amount_minor &&
    typeof value.accounting_at === "string" &&
    Number.isFinite(Date.parse(value.accounting_at)) &&
    value.payout_minor === 0 &&
    value.original_records_unchanged === true &&
    (!action.expected.stock ||
      (value.stock_restored === true &&
        value.restored_movement_count === action.expected.stock.count &&
        value.inventory_increase_minor ===
          action.expected.stock.inventory_increase_minor))
  );
}
export async function withReceiptCorrectionLock(
  scope: string,
  run: () => Promise<void>,
) {
  if (typeof navigator === "undefined" || !navigator.locks?.request)
    throw new Error(
      "This browser cannot coordinate safe recovery across tabs. Use a current supported browser; the saved command remains intact.",
    );
  return navigator.locks.request(`dcompany:pos-no-value:${scope}`, run);
}
