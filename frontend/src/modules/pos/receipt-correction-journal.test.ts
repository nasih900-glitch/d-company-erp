import { describe, expect, it } from "vitest";
import { ownerScope } from "@/lib/owner-workspace";
import {
  matchesCorrectionResult,
  ReceiptCorrectionJournal,
  validCorrectionAction,
  type ReceiptCorrectionAction,
} from "./receipt-correction-journal";
const id = (n: number) =>
  `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const identity = { company_id: id(1), user_id: id(2), branch_id: null };
function storage() {
  const data = new Map<string, string>();
  return {
    data,
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => {
      data.set(k, v);
    },
    removeItem: (k: string) => {
      data.delete(k);
    },
  };
}
export function action(): ReceiptCorrectionAction {
  return {
    version: 1,
    scope: ownerScope(identity),
    key: "pos-no-value:web:" + id(3),
    body: {
      order_id: id(4),
      source_fingerprint: "ab".repeat(32),
      reason: "Mistaken entry; nothing supplied or received",
      no_funds_received: true,
      all_items_not_supplied: true,
      acknowledges_no_payout: true,
    },
    expected: {
      invoice_no: "QA-0004",
      payment_id: id(5),
      original_shift_id: id(6),
      amount_minor: 4600,
    },
  };
}
function result() {
  const a = action();
  return {
    id: id(7),
    order_id: a.body.order_id,
    payment_id: a.expected.payment_id,
    original_shift_id: a.expected.original_shift_id,
    invoice_no: a.expected.invoice_no,
    kind: "payment_not_received_full_mistake",
    amount_minor: 4600,
    accounting_at: "2026-11-08T00:00:00Z",
    payout_minor: 0,
    original_records_unchanged: true,
  };
}
describe("strict no-value correction journal", () => {
  it("persists one exact command and atomically acknowledges with a generation", () => {
    const disk = storage(),
      a = action(),
      journal = new ReceiptCorrectionJournal(identity, disk);
    journal.save(a, 0);
    expect(journal.read().pending).toEqual(a);
    expect(new ReceiptCorrectionJournal(identity, disk).read().pending).toEqual(
      a,
    );
    journal.complete(a);
    expect(journal.read()).toMatchObject({ generation: 1, pending: null });
    expect(disk.data.size).toBe(1);
    expect(() =>
      journal.save({ ...a, key: "pos-no-value:web:" + id(8) }, 0),
    ).toThrow("Another tab");
  });
  it("rejects replacing saved evidence even with the same key", () => {
    const journal = new ReceiptCorrectionJournal(identity, storage()),
      a = action();
    journal.save(a, 0);
    expect(() =>
      journal.save(
        { ...a, body: { ...a.body, reason: "Different reason" } },
        0,
      ),
    ).toThrow("Recover");
    expect(() =>
      journal.complete({
        ...a,
        body: { ...a.body, reason: "Different reason" },
      }),
    ).toThrow("acknowledgement");
    expect(journal.read().pending).toEqual(a);
  });
  it("isolates actors and branches including global Owner scope", () => {
    const disk = storage(),
      j = new ReceiptCorrectionJournal(identity, disk);
    j.save(action(), 0);
    expect(
      new ReceiptCorrectionJournal({ ...identity, user_id: id(9) }, disk).read()
        .pending,
    ).toBeNull();
    expect(
      new ReceiptCorrectionJournal(
        { ...identity, branch_id: id(9) },
        disk,
      ).read().pending,
    ).toBeNull();
  });
  it.each([
    "",
    "{",
    "{}",
    "null",
    '{"version":1,"scope":"wrong","generation":0,"pending":null}',
  ])("keeps corrupt storage and fails closed: %s", (raw) => {
    const disk = storage();
    disk.data.set("dcompany:pos-no-value:v1:" + ownerScope(identity), raw);
    expect(() => new ReceiptCorrectionJournal(identity, disk).read()).toThrow();
    expect([...disk.data.values()]).toEqual([raw]);
  });
  it("blocks unreadable/unwritable/unverified storage before send", () => {
    expect(() => new ReceiptCorrectionJournal(identity, null).read()).toThrow(
      "unavailable",
    );
    const badRead = {
      ...storage(),
      getItem: () => {
        throw new Error("disk read failed");
      },
    };
    expect(() =>
      new ReceiptCorrectionJournal(identity, badRead).read(),
    ).toThrow("disk read");
    const drop = { ...storage(), setItem: () => {} };
    expect(() =>
      new ReceiptCorrectionJournal(identity, drop).save(action(), 0),
    ).toThrow("verified");
    const disk = storage();
    let dropWrites = false;
    const j = new ReceiptCorrectionJournal(identity, {
      ...disk,
      setItem: (k, v) => {
        if (!dropWrites) disk.setItem(k, v);
      },
    });
    j.save(action(), 0);
    dropWrites = true;
    expect(() => j.complete(action())).toThrow("verified");
    expect(j.read().pending).toEqual(action());
  });
  it.each(["amount_minor", "actor_id", "accounting_at", "payment_id"])(
    "rejects invented client command field %s",
    (field) => {
      const a = action();
      expect(
        validCorrectionAction(
          { ...a, body: { ...a.body, [field]: 123 } },
          a.scope,
        ),
      ).toBe(false);
    },
  );
  it("requires exact source-free receipt result before acknowledgement", () => {
    expect(matchesCorrectionResult(action(), result())).toBe(true);
    for (const delta of [
      { amount_minor: 1 },
      { order_id: id(99) },
      { payment_id: id(99) },
      { original_shift_id: id(99) },
      { invoice_no: "OTHER" },
      { payout_minor: 4600 },
      { original_records_unchanged: false },
      { kind: "refund" },
      { accounting_at: "invalid" },
      { extra: "unexpected" },
    ])
      expect(matchesCorrectionResult(action(), { ...result(), ...delta })).toBe(
        false,
      );
    expect(matchesCorrectionResult(action(), null)).toBe(false);
  });
  it("validates strict envelope, positive integer money and true attestations", () => {
    const a = action();
    expect(validCorrectionAction(a, a.scope)).toBe(true);
    for (const delta of [
      { amount_minor: 0 },
      { amount_minor: 1.5 },
      { payment_id: "invalid" },
    ])
      expect(
        validCorrectionAction(
          { ...a, expected: { ...a.expected, ...delta } },
          a.scope,
        ),
      ).toBe(false);
    expect(
      validCorrectionAction(
        { ...a, body: { ...a.body, no_funds_received: false } },
        a.scope,
      ),
    ).toBe(false);
    const disk = storage();
    const j = new ReceiptCorrectionJournal(identity, disk);
    j.save(a, 0);
    const [key, raw] = [...disk.data.entries()][0];
    disk.setItem(key, JSON.stringify({ ...JSON.parse(raw), unexpected: true }));
    expect(() => j.read()).toThrow("identity");
  });
});

describe("stock correction saved-action identity", () => {
  function stockAction(): ReceiptCorrectionAction {
    const value = action();
    return {
      ...value,
      body: {
        ...value.body,
        restore_stock: true,
        stock_never_consumed: true,
        stock_fingerprint: "cd".repeat(32),
      },
      expected: {
        ...value.expected,
        stock: { count: 2, inventory_increase_minor: 250 },
      },
    };
  }
  it("retains only the exact stock command and requires matching stock receipt effects", () => {
    const value = stockAction();
    const journal = new ReceiptCorrectionJournal(identity, storage());
    journal.save(value, 0);
    expect(journal.read().pending).toEqual(value);
    expect(matchesCorrectionResult(value, result())).toBe(false);
    const receipt = {
      ...result(),
      stock_restored: true,
      restored_movement_count: 2,
      inventory_increase_minor: 250,
    };
    expect(matchesCorrectionResult(value, receipt)).toBe(true);
    expect(
      matchesCorrectionResult(value, {
        ...receipt,
        inventory_increase_minor: 251,
      }),
    ).toBe(false);
  });
  it("rejects absent/false physical-stock consent and any token sneaked into saved evidence", () => {
    const value = stockAction();
    expect(
      validCorrectionAction(
        { ...value, body: { ...value.body, stock_never_consumed: false } },
        value.scope,
      ),
    ).toBe(false);
    expect(
      validCorrectionAction(
        { ...value, body: { ...value.body, token: "secret" } },
        value.scope,
      ),
    ).toBe(false);
    expect(
      validCorrectionAction(
        {
          ...value,
          expected: {
            ...value.expected,
            stock: { count: 2, inventory_increase_minor: -1 },
          },
        },
        value.scope,
      ),
    ).toBe(false);
  });
});
