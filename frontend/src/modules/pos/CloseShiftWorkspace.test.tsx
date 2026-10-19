// @vitest-environment happy-dom
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { NotificationProvider } from "@/components/ui/Notifications";
import { shifts, orders, gaming, type ShiftDTO } from "@/lib/erp-api";
import { CloseShiftForm } from "./OrdersAndShiftsScreen";
vi.mock("@/lib/erp-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/erp-api")>()),
  shifts: { close: vi.fn() },
  orders: { list: vi.fn() },
  gaming: { listOperationalSessions: vi.fn() },
}));
let root: Root;
const shift = {
  id: "shift",
  branch_id: "branch",
  terminal_id: "terminal",
  status: "open",
  opened_at: "2026-10-19T10:00:00Z",
  opened_by: "owner",
  opened_by_name: "QA Owner",
  opening_float_minor: 10000,
  expected_minor: 10000,
  gross_collections_minor: 0,
  total_refunds_minor: 0,
  net_collections_minor: 0,
  cash_collections_minor: 0,
  upi_collections_minor: 0,
  card_collections_minor: 0,
  other_collections_minor: 0,
} as ShiftDTO;
const dismiss = vi.fn();
function button(text: string) {
  return [...document.querySelectorAll("button")].find(
    (row) => row.textContent === text,
  )!;
}
function counted() {
  return [...document.querySelectorAll("label")]
    .find((row) => row.textContent?.includes("Counted cash (₹)"))!
    .querySelector("input")!;
}
async function render() {
  await act(async () =>
    root.render(
      <NotificationProvider>
        <CloseShiftForm
          shift={shift}
          currentUserId="owner"
          currentStaffName="QA Owner"
          workspaceName="QA Register"
          canReadOrders
          canReadGaming
          onClose={dismiss}
          onSuccess={() => {}}
          onError={async () => null}
        />
      </NotificationProvider>,
    ),
  );
}
function setCash(value: string) {
  act(() => {
    const input = counted();
    Object.getOwnPropertyDescriptor(
      HTMLInputElement.prototype,
      "value",
    )!.set!.call(input, value);
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
}
beforeEach(() => {
  (
    globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
  ).IS_REACT_ACT_ENVIRONMENT = true;
  root = createRoot(document.body.appendChild(document.createElement("div")));
  vi.mocked(orders.list).mockResolvedValue([
    { id: "order", status: "held" },
  ] as never);
  vi.mocked(gaming.listOperationalSessions).mockResolvedValue([
    { id: "game", shift_id: "shift", status: "active" },
    { id: "other", shift_id: "other-shift", status: "ended" },
  ] as never);
  vi.mocked(shifts.close).mockResolvedValue({
    id: "shift",
    status: "closed",
    variance_minor: 0,
  });
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  vi.clearAllMocks();
});
describe("Focused Shift review and count workspace", () => {
  it("keeps physical count blank beside partial observations and supports a deliberate zero", async () => {
    await render();
    expect(counted().value).toBe("");
    expect(button("Close shift").disabled).toBe(true);
    expect(
      document.querySelector('[aria-label="Shift review"]'),
    ).not.toBeNull();
    expect(
      document.querySelector('[aria-label="Physical cash count"]'),
    ).not.toBeNull();
    expect(document.body.textContent).toContain("1 open or held");
    expect(document.body.textContent).toContain(
      "1 active, paused or awaiting billing",
    );
    expect(document.body.textContent).toContain(
      "partial observations, not an all-clear",
    );
    setCash("0");
    expect(button("Close shift").disabled).toBe(false);
    await act(async () => {
      document
        .querySelector("form")!
        .dispatchEvent(
          new Event("submit", { bubbles: true, cancelable: true }),
        );
    });
    expect(shifts.close).toHaveBeenCalledExactlyOnceWith("shift", 0);
  });
  it("requires confirmation of an all-blank denomination count instead of manufacturing a counted zero", async () => {
    await render();
    act(() => button("Count denominations").click());
    expect(button("Close shift").disabled).toBe(true);
    act(() =>
      document
        .querySelector<HTMLInputElement>('input[type="checkbox"]')!
        .click(),
    );
    expect(button("Close shift").disabled).toBe(false);
    await act(async () => {
      document
        .querySelector("form")!
        .dispatchEvent(
          new Event("submit", { bubbles: true, cancelable: true }),
        );
    });
    expect(shifts.close).toHaveBeenCalledExactlyOnceWith("shift", 0);
  });
  it("keeps last checked partial observations when a later review cannot load", async () => {
    await render();
    expect(document.body.textContent).toContain("1 open or held");
    vi.mocked(orders.list).mockRejectedValueOnce(new Error("Read unavailable"));
    await act(async () => button("Refresh review").click());
    expect(document.body.textContent).toContain("Read unavailable");
    expect(document.body.textContent).toContain(
      "Previous observations remain as of their displayed check",
    );
    expect(document.body.textContent).toContain("1 open or held");
    expect(shifts.close).not.toHaveBeenCalled();
  });
  it("preserves exact unknown-result cash and target through a same-count retry", async () => {
    vi.mocked(shifts.close)
      .mockRejectedValueOnce(new Error("Reply lost"))
      .mockResolvedValueOnce({
        id: "shift",
        status: "closed",
        variance_minor: 0,
      });
    await render();
    setCash("75");
    await act(async () => {
      document
        .querySelector("form")!
        .dispatchEvent(
          new Event("submit", { bubbles: true, cancelable: true }),
        );
    });
    expect(counted().disabled).toBe(true);
    expect(counted().value).toBe("75");
    expect(button("Cancel").disabled).toBe(true);
    expect(document.querySelector('button[aria-label="Close"]')).toBeNull();
    expect(button("Refresh review").disabled).toBe(true);
    await act(async () => button("Retry same ₹75.00 count").click());
    expect(vi.mocked(shifts.close).mock.calls).toEqual([
      ["shift", 7500],
      ["shift", 7500],
    ]);
    expect(dismiss).not.toHaveBeenCalled();
  });
});
