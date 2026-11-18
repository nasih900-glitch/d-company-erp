// @vitest-environment happy-dom
import { act, StrictMode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Me } from "@/modules/auth/AuthContext";
import type {
  ReceiptHistoryDTO,
  ReceiptCorrectionPreviewDTO,
} from "@/lib/erp-api";
import { clearOwnerWorkspaces, ownerScope } from "@/lib/owner-workspace";
import ReceiptNoValueCorrection from "./ReceiptNoValueCorrection";
import {
  ReceiptCorrectionJournal,
  type ReceiptCorrectionAction,
} from "./receipt-correction-journal";
const mocks = vi.hoisted(() => ({
  me: null as Me | null,
  preview: vi.fn(),
  commit: vi.fn(),
  stockPreview: vi.fn(),
  stockCommit: vi.fn(),
  unlock: vi.fn(),
}));
vi.mock("@/modules/auth/AuthContext", () => ({
  useAuth: () => ({ me: mocks.me }),
}));
vi.mock("@/lib/erp-api", () => ({
  receiptCorrections: mocks,
  tenderCorrections: { unlock: mocks.unlock },
}));
const id = (n: number) =>
  `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const identity = {
  company_id: id(1),
  user_id: id(2),
  branch_id: null,
  audit_access: true,
  effective_permissions: ["finance.receipt.correct"],
} as Me;
const receipt = {
  order_id: id(4),
  branch_id: id(9),
  invoice_no: "QA-0004",
  shift_id: id(6),
  payments: [{ id: id(5) }],
} as ReceiptHistoryDTO;
const preview = {
  kind: "payment_not_received_full_mistake",
  order_id: id(4),
  invoice_no: "QA-0004",
  payment_id: id(5),
  amount_minor: 4600,
  eligible: true,
  blockers: [],
  source_fingerprint: "ab".repeat(32),
  lines: [
    { line_id: id(8), name: "Test drink", quantity: "2", total_minor: 4600 },
  ],
  financial_effect: false,
  payout_minor: 0,
  commit_available: true,
} as ReceiptCorrectionPreviewDTO;
const result = () => ({
  id: id(7),
  order_id: id(4),
  payment_id: id(5),
  original_shift_id: id(6),
  invoice_no: "QA-0004",
  kind: "payment_not_received_full_mistake",
  amount_minor: 4600,
  accounting_at: "2026-11-08T00:00:00Z",
  payout_minor: 0,
  original_records_unchanged: true,
});
let root: Root,
  host: HTMLDivElement,
  disk: Map<string, string>,
  onCommitted: ReturnType<typeof vi.fn<() => Promise<void>>>;
const env = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT?: boolean;
};
beforeEach(() => {
  env.IS_REACT_ACT_ENVIRONMENT = true;
  clearOwnerWorkspaces();
  vi.clearAllMocks();
  mocks.me = identity;
  disk = new Map();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => disk.get(k) ?? null,
    setItem: (k: string, v: string) => {
      disk.set(k, v);
    },
    removeItem: (k: string) => {
      disk.delete(k);
    },
  });
  vi.stubGlobal("navigator", {
    locks: {
      request: async (_name: string, run: () => Promise<void>) => run(),
    },
  });
  mocks.preview.mockResolvedValue(preview);
  mocks.commit.mockResolvedValue(result());
  mocks.stockPreview.mockResolvedValue({
    ...preview,
    eligible: false,
    blockers: ["unreconciled_stock_scope_quantity_or_cost"],
    stock_fingerprint: "cd".repeat(32),
    stock_movements: [],
    inventory_increase_minor: 0,
  });
  mocks.stockCommit.mockResolvedValue({
    ...result(),
    stock_restored: true,
    restored_movement_count: 1,
    inventory_increase_minor: 250,
  });
  mocks.unlock.mockResolvedValue({ token: "memory-only-test-confirmation" });
  onCommitted = vi.fn().mockResolvedValue(undefined);
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  vi.unstubAllGlobals();
  delete env.IS_REACT_ACT_ENVIRONMENT;
});
async function render(nextReceipt = receipt) {
  await act(async () =>
    root.render(
      <StrictMode>
        <ReceiptNoValueCorrection
          receipt={nextReceipt}
          onCommitted={onCommitted}
        />
      </StrictMode>,
    ),
  );
}
function button(label: string) {
  const node = [...host.querySelectorAll("button")].find((x) =>
    x.textContent?.includes(label),
  );
  if (!node) throw new Error("Missing button " + label);
  return node;
}
async function click(label: string) {
  await act(async () => button(label).click());
}
async function reason() {
  const input = host.querySelector<HTMLInputElement>(
    'input:not([type="checkbox"]):not([type="password"])',
  )!;
  await act(async () => {
    Object.getOwnPropertyDescriptor(
      HTMLInputElement.prototype,
      "value",
    )!.set!.call(input, "Mistaken entry; nothing supplied or received");
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  });
}
async function confirm() {
  await click("Check eligibility");
  await reason();
  await act(async () =>
    host
      .querySelectorAll<HTMLInputElement>('input[type="checkbox"]')
      .forEach((x) => x.click()),
  );
}
const journal = () => new ReceiptCorrectionJournal(identity, localStorage);
async function remount() {
  await act(async () => root.unmount());
  root = createRoot(host);
  await render();
}
describe("protected no-payout receipt workflow", () => {
  it("hides all controls without actual protected permission", async () => {
    mocks.me = { ...identity, audit_access: false };
    await render();
    expect(host.textContent).toBe("");
  });
  it("checks eligibility without financial mutation and requires all three confirmations", async () => {
    await render();
    await click("Check eligibility");
    expect(mocks.commit).not.toHaveBeenCalled();
    expect(host.textContent).toContain("Test drink");
    expect(button("Confirm no-payout").disabled).toBe(true);
    await reason();
    const checks = host.querySelectorAll<HTMLInputElement>(
      'input[type="checkbox"]',
    );
    await act(async () => checks[0].click());
    await act(async () => checks[1].click());
    expect(button("Confirm no-payout").disabled).toBe(true);
    await act(async () => checks[2].click());
    expect(button("Confirm no-payout").disabled).toBe(false);
  });
  it("submits once under StrictMode/double click and verifies exact server receipt", async () => {
    await render();
    await confirm();
    const commit = button("Confirm no-payout");
    await act(async () => {
      commit.click();
      commit.click();
    });
    expect(mocks.commit).toHaveBeenCalledTimes(1);
    expect(journal().read()).toMatchObject({ generation: 1, pending: null });
    expect(host.textContent).toContain(
      "Corrected — payment not received; no payout",
    );
    expect(onCommitted).toHaveBeenCalledOnce();
  });
  it("shows actionable unsupported source and no confirmation inputs", async () => {
    mocks.preview.mockResolvedValue({
      ...preview,
      eligible: false,
      blockers: ["recorded_stock_or_cogs_movement"],
    });
    await render();
    await click("Check eligibility");
    expect(host.textContent).toContain(
      "Original batch quantities, ownership or historical costs need Owner reconciliation",
    );
    expect(host.querySelector("input")).toBeNull();
    expect(mocks.commit).not.toHaveBeenCalled();
  });
  it("blocks storage write/readback failure before sending any financial command", async () => {
    await render();
    await confirm();
    vi.spyOn(localStorage, "setItem").mockImplementation(() => {});
    await click("Confirm no-payout");
    expect(mocks.commit).not.toHaveBeenCalled();
    expect(host.textContent).toContain("could not be verified");
  });
  it.each([401, 403, 409, 500])(
    "retains exact command after unknown/auth failure %s",
    async (status) => {
      mocks.commit.mockRejectedValue(
        Object.assign(new Error("Connection or authorization interrupted"), {
          status,
        }),
      );
      await render();
      await confirm();
      await click("Confirm no-payout");
      expect(journal().read().pending).not.toBeNull();
      expect(host.textContent).toContain("Recover saved correction");
    },
  );
  it("recovers the same durable key/body after lost reply and reload", async () => {
    mocks.commit.mockRejectedValueOnce(new Error("Reply lost"));
    await render();
    await confirm();
    await click("Confirm no-payout");
    const [body, key] = mocks.commit.mock.calls[0];
    await remount();
    expect(host.textContent).toContain("QA-0004");
    await click("Recover saved correction");
    expect(mocks.commit.mock.calls[1]).toEqual([body, key]);
    expect(mocks.preview).toHaveBeenCalledTimes(1);
    expect(journal().read().pending).toBeNull();
  });
  it("retains mismatched/unknown returned receipts rather than declaring success", async () => {
    mocks.commit.mockResolvedValue({ ...result(), amount_minor: 1 });
    await render();
    await confirm();
    await click("Confirm no-payout");
    expect(host.textContent).toContain("does not match");
    expect(journal().read().pending).not.toBeNull();
    expect(onCommitted).not.toHaveBeenCalled();
  });
  it("keeps saved result if refresh fails and refresh retry never reapplies money", async () => {
    onCommitted.mockRejectedValueOnce(new Error("Refresh unavailable"));
    await render();
    await confirm();
    await click("Confirm no-payout");
    expect(host.textContent).toContain(
      "Saved. Receipt/history refresh is pending",
    );
    expect(journal().read().pending).toBeNull();
    await click("Retry saved receipt refresh");
    expect(mocks.commit).toHaveBeenCalledTimes(1);
    expect(onCommitted).toHaveBeenCalledTimes(2);
  });
  it("rejects a stale second-tab preview after another tab acknowledges a correction", async () => {
    await render();
    await confirm();
    const a: ReceiptCorrectionAction = {
      version: 1,
      scope: ownerScope(identity),
      key: "pos-no-value:web:" + id(90),
      body: {
        order_id: id(4),
        source_fingerprint: preview.source_fingerprint,
        no_funds_received: true,
        all_items_not_supplied: true,
        acknowledges_no_payout: true,
        reason: "Completed in another tab",
      },
      expected: {
        invoice_no: "QA-0004",
        payment_id: id(5),
        original_shift_id: id(6),
        amount_minor: 4600,
      },
    };
    journal().save(a, 0);
    journal().complete(a);
    await click("Confirm no-payout");
    expect(mocks.commit).not.toHaveBeenCalled();
    expect(host.textContent).toContain("Another tab completed");
  });
  it("does not send a command after owner-generation changes while waiting for WebLock", async () => {
    let release: (() => void) | undefined;
    vi.stubGlobal("navigator", {
      locks: {
        request: async (_name: string, run: () => Promise<void>) => {
          await new Promise<void>((resolve) => {
            release = resolve;
          });
          await run();
        },
      },
    });
    await render();
    await confirm();
    await act(async () => button("Confirm no-payout").click());
    clearOwnerWorkspaces();
    await act(async () => release?.());
    expect(mocks.commit).not.toHaveBeenCalled();
    expect(journal().read().pending).toBeNull();
  });
  it("isolates saved recovery after actor/scope changes", async () => {
    mocks.commit.mockRejectedValueOnce(new Error("Reply lost"));
    await render();
    await confirm();
    await click("Confirm no-payout");
    const original = journal().read().pending;
    expect(original).not.toBeNull();
    mocks.me = { ...identity, user_id: id(99) };
    clearOwnerWorkspaces();
    await render();
    expect(host.textContent).not.toContain("Saved correction:");
    expect(journal().read().pending).toEqual(original);
    expect(mocks.commit).toHaveBeenCalledTimes(1);
  });
  it("fails closed for malformed stored envelope without deleting evidence", async () => {
    disk.set("dcompany:pos-no-value:v1:" + ownerScope(identity), "{}");
    await render();
    expect(host.textContent).toContain(
      "Saved correction identity needs review",
    );
    expect(button("Check eligibility").disabled).toBe(true);
    expect(mocks.preview).not.toHaveBeenCalled();
    expect([...disk.values()]).toEqual(["{}"]);
  });
  it("requires Web Locks and never submits an unsupported browser fallback", async () => {
    vi.stubGlobal("navigator", {});
    await render();
    await confirm();
    await click("Confirm no-payout");
    expect(mocks.commit).not.toHaveBeenCalled();
    expect(host.textContent).toContain("cannot coordinate safe recovery");
  });
  it("ignores preview from old authenticated generation", async () => {
    let release: (value: ReceiptCorrectionPreviewDTO) => void = () => {};
    mocks.preview.mockImplementation(
      () =>
        new Promise<ReceiptCorrectionPreviewDTO>((resolve) => {
          release = resolve;
        }),
    );
    await render();
    await act(async () => button("Check eligibility").click());
    clearOwnerWorkspaces();
    await act(async () => release(preview));
    expect(host.querySelector("input")).toBeNull();
    expect(mocks.commit).not.toHaveBeenCalled();
  });
  it("keeps unresolved same-key recovery visible after a realtime corrected receipt arrives", async () => {
    mocks.commit.mockRejectedValueOnce(new Error("Committed but reply lost"));
    await render();
    await confirm();
    await click("Confirm no-payout");
    const [body, key] = mocks.commit.mock.calls[0];
    await render({
      ...receipt,
      corrected_minor: 4600,
      receipt_correction: {
        id: id(7),
        kind: "payment_not_received_full_mistake",
        amount_minor: 4600,
        accounting_at: "2026-11-08T00:00:00Z",
        payout_minor: 0,
        label: "No payout",
      },
    });
    expect(host.textContent).toContain("Recover saved correction");
    await click("Recover saved correction");
    expect(mocks.commit.mock.calls[1]).toEqual([body, key]);
    expect(journal().read().pending).toBeNull();
  });
});

describe("exact stock restoration", () => {
  async function prepareStock() {
    mocks.preview.mockResolvedValue({
      ...preview,
      eligible: false,
      blockers: ["recorded_stock_or_cogs_movement"],
    });
    mocks.stockPreview.mockResolvedValue({
      ...preview,
      stock_fingerprint: "cd".repeat(32),
      inventory_increase_minor: 250,
      stock_movements: [
        {
          movement_id: id(20),
          batch_id: id(21),
          ingredient_id: id(22),
          ingredient_name: "Original bottled drink",
          base_unit: "unit",
          quantity: "2",
          cost_per_unit_minor: 125,
        },
      ],
    });
    await render();
    await confirm();
  }
  async function unlock() {
    const input = host.querySelector<HTMLInputElement>(
      'input[type="password"]',
    )!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(
        HTMLInputElement.prototype,
        "value",
      )!.set!.call(input, "synthetic-owner-password");
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await click("Confirm Owner for stock restoration");
  }
  it("requires exact stock review, physical attestation and purpose-specific fresh Owner confirmation", async () => {
    await prepareStock();
    expect(host.textContent).toContain("Original bottled drink × 2 unit");
    expect(host.textContent).toContain(
      "never physically consumed, removed, prepared or wasted",
    );
    expect(host.querySelectorAll('input[type="checkbox"]')).toHaveLength(4);
    expect(button("Confirm no-payout").disabled).toBe(true);
    await unlock();
    expect(mocks.unlock).toHaveBeenCalledWith(
      id(9),
      "receipt_stock",
      "synthetic-owner-password",
    );
    await click("Confirm no-payout");
    expect(mocks.commit).not.toHaveBeenCalled();
    expect(mocks.stockCommit).toHaveBeenCalledWith(
      expect.objectContaining({
        restore_stock: true,
        stock_never_consumed: true,
        stock_fingerprint: "cd".repeat(32),
      }),
      expect.any(String),
      "memory-only-test-confirmation",
    );
    expect(JSON.stringify([...disk.values()])).not.toContain(
      "synthetic-owner-password",
    );
    expect(JSON.stringify([...disk.values()])).not.toContain(
      "memory-only-test-confirmation",
    );
    expect(journal().read().pending).toBeNull();
  });
  it("preserves another invoice's saved stock action and directs a branch-unassigned Owner to its original invoice", async () => {
    await prepareStock();
    await unlock();
    mocks.stockCommit.mockRejectedValueOnce(new Error("Reply lost"));
    await click("Confirm no-payout");
    const saved = journal().read().pending!;
    await act(async () => root.unmount());
    root = createRoot(host);
    await render({
      ...receipt,
      order_id: id(30),
      branch_id: id(31),
      invoice_no: "OTHER-INVOICE",
    });
    expect(host.textContent).toContain("Open saved invoice QA-0004");
    expect(host.querySelector('input[type="password"]')).toBeNull();
    expect(button("Recover saved correction").disabled).toBe(true);
    expect(journal().read().pending).toEqual(saved);
    expect(mocks.unlock).toHaveBeenCalledTimes(1);
  });
  it("recovers a lost stock response with the original saved body/key after remount and fresh unlock", async () => {
    await prepareStock();
    await unlock();
    mocks.stockCommit.mockRejectedValueOnce(
      new Error("Reply lost after server saved"),
    );
    await click("Confirm no-payout");
    const saved = journal().read().pending!;
    expect(saved.body).toMatchObject({
      restore_stock: true,
      stock_never_consumed: true,
    });
    await remount();
    expect(button("Recover saved correction").disabled).toBe(true);
    await unlock();
    await click("Recover saved correction");
    expect(mocks.stockCommit.mock.calls[1].slice(0, 2)).toEqual([
      saved.body,
      saved.key,
    ]);
    expect(journal().read().pending).toBeNull();
  });
});
