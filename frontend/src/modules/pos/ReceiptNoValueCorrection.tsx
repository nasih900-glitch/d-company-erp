import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/modules/auth/AuthContext";
import {
  receiptCorrections,
  tenderCorrections,
  type ReceiptCorrectionPreviewDTO,
  type ReceiptStockCorrectionPreviewDTO,
  type ReceiptCorrectionResultDTO,
  type ReceiptHistoryDTO,
} from "@/lib/erp-api";
import { ownerScope, ownerWorkspaceGeneration } from "@/lib/owner-workspace";
import { availableLocalStorage } from "@/lib/stable-mutation-intent";
import { inr } from "@/lib/inr";
import {
  matchesCorrectionResult,
  ReceiptCorrectionJournal,
  withReceiptCorrectionLock,
  type ReceiptCorrectionAction,
} from "./receipt-correction-journal";

const blockers: Record<string, string> = {
  requires_recorded_stock_consumption:
    "No recorded stock consumption exists; use the ordinary no-payout correction.",
  stock_already_restored:
    "Stock restoration evidence already exists. Review the saved correction.",
  unreconciled_stock_scope_quantity_or_cost:
    "Original batch quantities, ownership or historical costs need Owner reconciliation. Nothing can be changed here.",
  requires_complete_issued_paid_invoice: "A complete paid invoice is required.",
  requires_matching_closed_original_shift:
    "The original closed shift and workspace must match.",
  requires_single_full_manual_upi_payment:
    "Only a single full manual UPI payment is supported.",
  unsupported_tax_tip_rounding_discount_or_einvoice:
    "Tax charged, tips, rounding, discounts or issued e-invoice effects need separate review.",
  unsupported_customer_or_loyalty_effects:
    "Customer or loyalty effects need separate reconciliation.",
  requires_plain_unsupplied_product_lines:
    "Product pricing or preparation evidence needs separate review.",
  recorded_refund:
    "A refund already exists; this no-payout correction is unavailable.",
  recorded_refund_request: "A refund request already exists; review it first.",
  existing_tender_correction:
    "An existing payment-method correction needs separate review.",
  already_corrected: "This receipt already has a no-value correction.",
  active_checkout_claim:
    "Another checkout is active. Finish or cancel that checkout first.",
  recorded_stock_or_cogs_movement:
    "Recorded stock or cost movements need separate reconciliation.",
  unsupported_gaming_value_or_provenance:
    "Gaming value or its historical links need separate review.",
};
function validPreview(
  value: ReceiptCorrectionPreviewDTO,
  receipt: ReceiptHistoryDTO,
) {
  return (
    value &&
    value.order_id === receipt.order_id &&
    value.invoice_no === receipt.invoice_no &&
    value.kind === "payment_not_received_full_mistake" &&
    value.financial_effect === false &&
    value.payout_minor === 0 &&
    typeof value.eligible === "boolean" &&
    Array.isArray(value.blockers) &&
    value.blockers.every((x) => typeof x === "string") &&
    /^[a-f0-9]{64}$/.test(value.source_fingerprint) &&
    Number.isSafeInteger(value.amount_minor) &&
    value.amount_minor >= 0 &&
    Array.isArray(value.lines) &&
    value.lines.every(
      (x) =>
        typeof x.name === "string" &&
        typeof x.quantity === "string" &&
        Number.isSafeInteger(x.total_minor),
    ) &&
    (!value.eligible ||
      (value.commit_available === true &&
        value.amount_minor > 0 &&
        value.payment_id !== null &&
        receipt.payments.some((p) => p.id === value.payment_id)))
  );
}
function stockPreview(
  value: ReceiptCorrectionPreviewDTO,
): value is ReceiptStockCorrectionPreviewDTO {
  return "stock_fingerprint" in value;
}
function validStockPreview(value: ReceiptStockCorrectionPreviewDTO) {
  return (
    /^[a-f0-9]{64}$/.test(value.stock_fingerprint) &&
    Number.isSafeInteger(value.inventory_increase_minor) &&
    value.inventory_increase_minor >= 0 &&
    Array.isArray(value.stock_movements) &&
    value.stock_movements.every(
      (m) =>
        [m.movement_id, m.batch_id, m.ingredient_id].every(
          (id) => typeof id === "string" && /^[a-f0-9-]{36}$/i.test(id),
        ) &&
        typeof m.ingredient_name === "string" &&
        ["ml", "g", "unit"].includes(m.base_unit) &&
        typeof m.quantity === "string" &&
        /^(?:[0-9]+)(?:\.[0-9]{1,4})?$/.test(m.quantity) &&
        Number(m.quantity) > 0 &&
        Number.isSafeInteger(m.cost_per_unit_minor) &&
        m.cost_per_unit_minor > 0,
    ) &&
    (!value.eligible || value.stock_movements.length > 0)
  );
}
export default function ReceiptNoValueCorrection({
  receipt,
  onCommitted,
}: {
  receipt: ReceiptHistoryDTO;
  onCommitted: () => Promise<void>;
}) {
  const { me } = useAuth();
  if (
    !me?.audit_access ||
    !me.effective_permissions?.includes("finance.receipt.correct")
  )
    return null;
  return (
    <CorrectionWorkspace
      key={ownerScope(me) + ":" + receipt.order_id}
      receipt={receipt}
      onCommitted={onCommitted}
    />
  );
}
function CorrectionWorkspace({
  receipt,
  onCommitted,
}: {
  receipt: ReceiptHistoryDTO;
  onCommitted: () => Promise<void>;
}) {
  const { me } = useAuth();
  const identity = useRef({ ...me! }).current;
  const currentIdentity = useRef(me);
  currentIdentity.current = me;
  const [journal] = useState(
    () => new ReceiptCorrectionJournal(identity, availableLocalStorage()),
  );
  const [initial] = useState(() => {
    try {
      return { state: journal.read(), error: null };
    } catch (cause) {
      return { state: null, error: (cause as Error).message };
    }
  });
  const [pending, setPending] = useState<ReceiptCorrectionAction | null>(
    initial.state?.pending ?? null,
  );
  const [error, setError] = useState<string | null>(initial.error);
  const [storageBlocked, setStorageBlocked] = useState(!!initial.error);
  const [review, setReview] = useState<{
    preview: ReceiptCorrectionPreviewDTO | ReceiptStockCorrectionPreviewDTO;
    generation: number;
  } | null>(null);
  const [reason, setReason] = useState("");
  const [checks, setChecks] = useState([false, false, false]);
  const [busy, setBusy] = useState(false);
  const [ownerPassword, setOwnerPassword] = useState("");
  const [confirmationToken, setConfirmationToken] = useState("");
  const [saved, setSaved] = useState<ReceiptCorrectionResultDTO | null>(null);
  const [refreshPending, setRefreshPending] = useState(false);
  const inFlight = useRef(false),
    mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const current = (generation: number) =>
    mounted.current &&
    generation === ownerWorkspaceGeneration() &&
    !!currentIdentity.current &&
    ownerScope(currentIdentity.current) === journal.scope;
  async function prepare() {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    const generation = ownerWorkspaceGeneration();
    try {
      const state = journal.read();
      if (state.pending) {
        setPending(state.pending);
        throw new Error(
          "Recover the saved correction before preparing another action.",
        );
      }
      let value: ReceiptCorrectionPreviewDTO = await receiptCorrections.preview(
        receipt.order_id,
      );
      if (value.blockers?.includes("recorded_stock_or_cogs_movement"))
        value = await receiptCorrections.stockPreview(receipt.order_id);
      if (!current(generation)) return;
      if (
        !validPreview(value, receipt) ||
        (stockPreview(value) && !validStockPreview(value))
      )
        throw new Error(
          "The review does not match this invoice. Nothing was submitted; reload the receipt.",
        );
      if (journal.read().generation !== state.generation)
        throw new Error(
          "Another tab completed a correction. Check eligibility again.",
        );
      setReview({ preview: value, generation: state.generation });
      setChecks(
        stockPreview(value)
          ? [false, false, false, false]
          : [false, false, false],
      );
      setConfirmationToken("");
      setReason("");
    } catch (cause) {
      if (current(generation)) setError((cause as Error).message);
    } finally {
      inFlight.current = false;
      if (current(generation)) setBusy(false);
    }
  }
  async function refreshSaved() {
    try {
      await onCommitted();
      if (mounted.current) setRefreshPending(false);
    } catch {
      if (mounted.current) {
        setRefreshPending(true);
        setError(
          "Saved. Receipt/history refresh is pending; retry refresh without applying another correction.",
        );
      }
    }
  }
  async function submit() {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    const generation = ownerWorkspaceGeneration(),
      recovery = pending;
    try {
      await withReceiptCorrectionLock(journal.scope, async () => {
        if (!current(generation)) return;
        const state = journal.read();
        let action = state.pending;
        if (action && (!recovery || action.key !== recovery.key)) {
          setPending(action);
          setReview(null);
          throw new Error(
            "Another saved correction is awaiting its receipt. Review the saved invoice and choose Recover saved correction.",
          );
        }
        if (recovery && !action) {
          if (saved && matchesCorrectionResult(recovery, saved)) {
            setPending(null);
            return;
          }
          throw new Error(
            "Saved command is no longer available in this workspace. Reload and review the receipt; no new action was sent.",
          );
        }
        if (!action) {
          if (
            !review?.preview.eligible ||
            !checks.every(Boolean) ||
            reason.trim().length < 3 ||
            reason.trim().length > 500
          )
            throw new Error(
              "Review all three confirmations and enter a clear reason.",
            );
          if (state.generation !== review.generation)
            throw new Error(
              "Another tab completed a correction. Check eligibility again; no new action was sent.",
            );
          const value = review.preview;
          if (stockPreview(value) && !confirmationToken)
            throw new Error(
              "Confirm the Owner password for stock restoration before continuing.",
            );
          action = {
            version: 1,
            scope: journal.scope,
            key: "pos-no-value:web:" + crypto.randomUUID(),
            body: {
              order_id: value.order_id,
              source_fingerprint: value.source_fingerprint,
              no_funds_received: true,
              all_items_not_supplied: true,
              acknowledges_no_payout: true,
              reason: reason.trim(),
              ...(stockPreview(value)
                ? {
                    restore_stock: true as const,
                    stock_never_consumed: true as const,
                    stock_fingerprint: value.stock_fingerprint,
                  }
                : {}),
            },
            expected: {
              invoice_no: value.invoice_no!,
              payment_id: value.payment_id!,
              original_shift_id: receipt.shift_id,
              amount_minor: value.amount_minor,
              ...(stockPreview(value)
                ? {
                    stock: {
                      count: value.stock_movements.length,
                      inventory_increase_minor: value.inventory_increase_minor,
                    },
                  }
                : {}),
            },
          };
          journal.save(action, review.generation);
          setPending(action);
        }
        if (!current(generation)) return;
        if (
          "restore_stock" in action.body &&
          action.body.order_id !== receipt.order_id
        )
          throw new Error(
            `Open saved invoice ${action.expected.invoice_no} to unlock and recover its original stock correction.`,
          );
        const result =
          "restore_stock" in action.body
            ? await receiptCorrections.stockCommit(
                action.body,
                action.key,
                confirmationToken,
              )
            : await receiptCorrections.commit(action.body, action.key);
        if (!current(generation)) return;
        if (!matchesCorrectionResult(action, result))
          throw new Error(
            "The returned receipt does not match the saved command. Evidence was kept; recover the same correction.",
          );
        setSaved(result);
        setReview(null);
        setChecks([false, false, false]);
        try {
          journal.complete(action);
          setPending(null);
        } catch (cause) {
          setError(
            "Saved; local recovery acknowledgement is pending. " +
              (cause as Error).message,
          );
        }
        await refreshSaved();
      });
    } catch (cause) {
      if (!current(generation)) return;
      setError((cause as Error).message);
      if (needsStockConfirmation) setConfirmationToken("");
      try {
        setPending(journal.read().pending);
      } catch {
        setStorageBlocked(true);
      }
    } finally {
      inFlight.current = false;
      if (current(generation)) setBusy(false);
    }
  }
  async function unlockStock() {
    if (
      pending &&
      "restore_stock" in pending.body &&
      pending.body.order_id !== receipt.order_id
    ) {
      setError(
        `Open saved invoice ${pending.expected.invoice_no} to unlock and recover its original stock correction.`,
      );
      return;
    }
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    const generation = ownerWorkspaceGeneration();
    try {
      const result = await tenderCorrections.unlock(
        receipt.branch_id,
        "receipt_stock",
        ownerPassword,
      );
      if (current(generation)) {
        setConfirmationToken(result.token);
        setOwnerPassword("");
      }
    } catch (cause) {
      if (current(generation)) setError((cause as Error).message);
    } finally {
      inFlight.current = false;
      if (current(generation)) setBusy(false);
    }
  }
  const value = review?.preview;
  const foreignStockAction = !!(
    pending &&
    "restore_stock" in pending.body &&
    pending.body.order_id !== receipt.order_id
  );
  const needsStockConfirmation =
    !foreignStockAction &&
    (!!(value?.eligible && stockPreview(value)) ||
      !!(pending && "restore_stock" in pending.body));
  if (
    (receipt.receipt_correction || (receipt.corrected_minor ?? 0) > 0) &&
    !pending &&
    !saved &&
    !storageBlocked
  )
    return null;
  return (
    <details
      open={Boolean(pending || saved)}
      className="rounded-xl border border-bg-border p-4"
    >
      <summary className="cursor-pointer text-sm font-semibold">
        Correct mistaken paid bill — no money received
      </summary>
      <section
        className="space-y-3 rounded-xl border border-accent-gold/40 p-4"
        aria-label="Correct mistaken paid bill"
      >
        <h4 className="font-semibold">
          Correct mistaken paid bill — no money received
        </h4>
        <p className="text-sm text-fg-muted">
          The original invoice and closed shift stay unchanged. This records a
          separate sale/UPI offset today. It makes no refund, bank transfer or
          customer payout.
        </p>
        {error && (
          <p role="alert" className="text-sm text-accent-bad">
            {error}
          </p>
        )}
        {saved && (
          <div role="status" className="text-sm text-accent-good">
            <strong>Corrected — payment not received; no payout.</strong>
            <p>
              {saved.invoice_no} · {inr(saved.amount_minor)} offset. Original
              records retained.
            </p>
            {refreshPending && (
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => void refreshSaved()}
              >
                Retry saved receipt refresh
              </button>
            )}
          </div>
        )}
        {needsStockConfirmation && !confirmationToken && (
          <div className="space-y-2">
            <label className="block text-sm">
              Owner password for stock restoration
              <input
                type="password"
                autoComplete="current-password"
                className="input mt-1 w-full"
                value={ownerPassword}
                onChange={(e) => setOwnerPassword(e.target.value)}
                disabled={busy}
              />
            </label>
            <button
              type="button"
              className="btn btn-ghost"
              disabled={busy || !ownerPassword}
              onClick={() => void unlockStock()}
            >
              Confirm Owner for stock restoration
            </button>
          </div>
        )}
        {pending ? (
          <div className="space-y-2 text-sm">
            <p>
              Saved correction: <strong>{pending.expected.invoice_no}</strong> ·{" "}
              {inr(pending.expected.amount_minor)}. The result is unresolved
              locally; keep the same command.
            </p>
            {foreignStockAction && (
              <p role="alert">
                Open saved invoice {pending.expected.invoice_no} to unlock and
                recover its original stock correction. The saved action is
                preserved.
              </p>
            )}
            <button
              type="button"
              className="btn btn-primary"
              disabled={
                busy ||
                storageBlocked ||
                foreignStockAction ||
                (needsStockConfirmation && !confirmationToken)
              }
              onClick={() => void submit()}
            >
              {busy ? "Recovering…" : "Recover saved correction"}
            </button>
          </div>
        ) : (
          !saved && (
            <>
              <button
                type="button"
                className="btn btn-ghost"
                disabled={busy || storageBlocked}
                onClick={() => void prepare()}
              >
                {busy ? "Checking…" : "Check eligibility"}
              </button>
              {value && (
                <div className="space-y-3">
                  <p className="text-sm font-semibold">
                    {value.invoice_no} · {inr(value.amount_minor)} · payout{" "}
                    {inr(0)}
                  </p>
                  <ul className="text-sm">
                    {value.lines.map((line) => (
                      <li key={line.line_id}>
                        {line.name} × {line.quantity} · {inr(line.total_minor)}
                      </li>
                    ))}
                  </ul>
                  {stockPreview(value) && (
                    <section
                      aria-label="Exact stock restoration"
                      className="rounded border border-bg-border p-3 text-sm"
                    >
                      <strong>
                        Stock never supplied — restore original batches
                      </strong>
                      <ul>
                        {value.stock_movements.map((m) => (
                          <li key={m.movement_id}>
                            {m.ingredient_name} × {m.quantity} {m.base_unit} ·
                            historical unit cost {inr(m.cost_per_unit_minor)} ·
                            batch {m.batch_id.slice(-8)}
                          </li>
                        ))}
                      </ul>
                      <p>
                        Inventory value increases by{" "}
                        {inr(value.inventory_increase_minor)}. Cost of goods is
                        reduced by the same amount today. No customer payout.
                      </p>
                    </section>
                  )}
                  {!value.eligible ? (
                    <div role="status" className="text-sm">
                      <p>Correction unavailable for this source:</p>
                      <ul>
                        {value.blockers.map((code) => (
                          <li key={code}>
                            {blockers[code] ??
                              "This receipt requires separate Owner reconciliation."}
                          </li>
                        ))}
                      </ul>
                    </div>
                  ) : (
                    <>
                      <label className="block text-sm">
                        Reason
                        <input
                          className="input mt-1 w-full"
                          maxLength={500}
                          value={reason}
                          onChange={(e) => {
                            setReason(e.target.value);
                            setChecks(
                              stockPreview(value)
                                ? [false, false, false, false]
                                : [false, false, false],
                            );
                          }}
                          disabled={busy}
                        />
                      </label>
                      {[
                        "No payment was received for this invoice.",
                        "All listed goods were added by mistake and were not supplied.",
                        "I understand this operation makes no customer payout.",
                        ...(stockPreview(value)
                          ? [
                              "Every listed ingredient and quantity was never physically consumed, removed, prepared or wasted and remains on hand in its original batch. Restore exactly these recorded quantities.",
                            ]
                          : []),
                      ].map((label, index) => (
                        <label className="flex gap-2 text-sm" key={label}>
                          <input
                            type="checkbox"
                            checked={checks[index]}
                            disabled={busy}
                            onChange={(e) =>
                              setChecks((rows) =>
                                rows.map((v, i) =>
                                  i === index ? e.target.checked : v,
                                ),
                              )
                            }
                          />
                          {label}
                        </label>
                      ))}
                      <button
                        type="button"
                        className="btn btn-primary"
                        disabled={
                          busy ||
                          !checks.every(Boolean) ||
                          (needsStockConfirmation && !confirmationToken) ||
                          reason.trim().length < 3
                        }
                        onClick={() => void submit()}
                      >
                        {busy ? "Saving…" : "Confirm no-payout correction"}
                      </button>
                    </>
                  )}
                </div>
              )}
            </>
          )
        )}
      </section>
    </details>
  );
}
