// @vitest-environment happy-dom
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NotificationProvider } from "@/components/ui/Notifications";
import { shifts, type ShiftDTO } from "@/lib/erp-api";
import {
  CloseShiftForm,
  RecoverAndroidShiftForm,
} from "./OrdersAndShiftsScreen";
vi.mock("@/lib/erp-api", async (original) => ({
  ...(await original<typeof import("@/lib/erp-api")>()),
  shifts: { close: vi.fn(), recoverAndroidClose: vi.fn() },
}));
const shift = {
  id: "exact-shift",
  branch_id: "branch",
  terminal_id: "terminal",
  opened_by: "owner",
  opened_by_name: "Owner",
  opened_at: "2026-10-19T10:00:00Z",
  opening_float_minor: 0,
  status: "open",
} as ShiftDTO;
const forbidden = () => Object.assign(new Error("Forbidden"), { status: 403 });
const dismissed = vi.fn();
const refresh = vi.fn(async (): Promise<ShiftDTO | null> => null);
let root: Root;
function button(text: string) {
  return [...document.querySelectorAll("button")].find(
    (e) => e.textContent === text,
  )!;
}
function cash() {
  return (
    document.querySelector<HTMLInputElement>(
      "input[type=number]:not([disabled])",
    ) ?? document.querySelectorAll<HTMLInputElement>("input[type=number]")[0]
  );
}
function enter(element: HTMLInputElement | HTMLTextAreaElement, value: string) {
  act(() => {
    const proto =
      element instanceof HTMLTextAreaElement
        ? HTMLTextAreaElement.prototype
        : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, "value")!.set!.call(element, value);
    element.dispatchEvent(new Event("input", { bubbles: true }));
  });
}
async function submit() {
  await act(async () => {
    document
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  });
}
async function render(recovery = false) {
  const props = {
    shift,
    currentUserId: "owner",
    currentStaffName: "Owner",
    workspaceName: "Register",
    onClose: dismissed,
    onSuccess: vi.fn(),
  };
  await act(async () =>
    root.render(
      <NotificationProvider>
        {recovery ? (
          <RecoverAndroidShiftForm
            {...props}
            onError={async () => {
              await refresh();
            }}
          />
        ) : (
          <CloseShiftForm
            {...props}
            canReadOrders={false}
            canReadGaming={false}
            onError={refresh}
          />
        )}
      </NotificationProvider>,
    ),
  );
}
beforeEach(() => {
  (
    globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
  ).IS_REACT_ACT_ENVIRONMENT = true;
  root = createRoot(document.body.appendChild(document.createElement("div")));
  refresh.mockResolvedValue(null);
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  vi.resetAllMocks();
});
describe("Unknown Shift outcomes survive later refusals", () => {
  it("keeps the original normal count and target after unknown then forbidden with no authoritative row", async () => {
    vi.mocked(shifts.close)
      .mockRejectedValueOnce(new Error("Reply lost"))
      .mockRejectedValueOnce(forbidden())
      .mockResolvedValueOnce({
        id: shift.id,
        status: "closed",
        variance_minor: 0,
      });
    await render();
    enter(cash(), "75");
    await submit();
    await submit();
    expect(cash().disabled).toBe(true);
    expect(cash().value).toBe("75");
    expect(button("Cancel").disabled).toBe(true);
    expect(document.querySelector("button[aria-label=Close]")).toBeNull();
    await submit();
    expect(vi.mocked(shifts.close).mock.calls).toEqual([
      [shift.id, 7500],
      [shift.id, 7500],
      [shift.id, 7500],
    ]);
  });
  it("keeps the original recovery key, reason, quarantine and count after unknown then forbidden", async () => {
    vi.mocked(shifts.recoverAndroidClose)
      .mockRejectedValueOnce(new Error("Reply lost"))
      .mockRejectedValueOnce(forbidden());
    await render(true);
    enter(cash(), "75");
    enter(
      document.querySelector("textarea")!,
      "Tablet isolated after a failed saved close.",
    );
    act(() =>
      document.querySelector<HTMLInputElement>("input[type=checkbox]")!.click(),
    );
    await submit();
    await submit();
    expect(cash().disabled).toBe(true);
    expect(cash().value).toBe("75");
    expect(document.querySelector("textarea")!.disabled).toBe(true);
    expect(button("Cancel").disabled).toBe(true);
    expect(document.querySelector("button[aria-label=Close]")).toBeNull();
    const calls = vi.mocked(shifts.recoverAndroidClose).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[1]).toEqual(calls[0]);
    expect(calls[0][0]).toBe(shift.id);
    expect(calls[0][1]).toEqual({
      counted_minor: 7500,
      reason: "Tablet isolated after a failed saved close.",
      acknowledge_origin_tablet_quarantined: true,
    });
    expect(calls[0][2]).toMatch(/^shift-recovery-close:web:/);
  });
  it.each([false, true])(
    "leaves a first definitive refusal editable, recovery=%s",
    async (recovery) => {
      vi.mocked(shifts.close).mockRejectedValueOnce(forbidden());
      vi.mocked(shifts.recoverAndroidClose).mockRejectedValueOnce(forbidden());
      await render(recovery);
      enter(cash(), "75");
      if (recovery) {
        enter(
          document.querySelector("textarea")!,
          "Originating tablet isolated.",
        );
        act(() =>
          document
            .querySelector<HTMLInputElement>("input[type=checkbox]")!
            .click(),
        );
      }
      await submit();
      expect(cash().disabled).toBe(false);
      expect(button("Cancel").disabled).toBe(false);
      enter(cash(), "80");
      expect(cash().value).toBe("80");
    },
  );
  it("does not accept a matching amount from a different authoritative shift", async () => {
    vi.mocked(shifts.close).mockRejectedValueOnce(new Error("Reply lost"));
    refresh.mockResolvedValue({
      ...shift,
      id: "other-shift",
      status: "closed",
      counted_minor: 7500,
    });
    await render();
    enter(cash(), "75");
    await submit();
    expect(cash().disabled).toBe(true);
    expect(document.body.textContent).not.toContain("Shift closure confirmed");
  });
  it("retains the unknown count when refreshing authoritative evidence itself fails", async () => {
    vi.mocked(shifts.close).mockRejectedValueOnce(new Error("Reply lost"));
    refresh.mockRejectedValueOnce(new Error("Read unavailable"));
    await render();
    enter(cash(), "75");
    await submit();
    expect(cash().disabled).toBe(true);
    expect(cash().value).toBe("75");
  });
});
