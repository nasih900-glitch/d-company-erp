// @vitest-environment happy-dom
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { diagnosticEvents, type ClientDiagnosticEventDTO } from "@/lib/erp-api";
import IncidentReports from "./IncidentReports";

vi.mock("@/lib/erp-api", () => ({ diagnosticEvents: { list: vi.fn() } }));
let root: Root;
const report = {
  id: "retained-event",
  installation_id: "tablet",
  event_type: "anr",
  component: "gaming",
  severity: "error",
  reason_code: "process_anr",
  connectivity: "offline",
  occurred_at: "2026-10-17T10:00:00Z",
  received_at: "2026-10-19T10:00:00Z",
  version_code: 52,
  version_name: "3.1.44",
} as ClientDiagnosticEventDTO;
const load = () => document.querySelector<HTMLButtonElement>("button")!;
beforeEach(() => {
  (
    globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
  ).IS_REACT_ACT_ENVIRONMENT = true;
  root = createRoot(document.body.appendChild(document.createElement("div")));
  vi.mocked(diagnosticEvents.list).mockResolvedValue({
    items: [report],
    total: 1,
    limit: 20,
    offset: 0,
    retention_days: 90,
  });
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  vi.resetAllMocks();
});

describe("Retained incident evidence", () => {
  it("preserves source rows and the applied filter when a requested filter cannot load", async () => {
    await act(async () => root.render(<IncidentReports />));
    await act(async () => load().click());
    expect(document.body.textContent).toContain(
      "Observed/imported by build 51",
    );
    expect(document.body.textContent).toContain(
      "Actual incident build was not retained",
    );
    vi.mocked(diagnosticEvents.list).mockRejectedValueOnce(
      new Error("Service unavailable"),
    );
    act(() => {
      const filter = document.querySelector("select")!;
      filter.value = "crash";
      filter.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => load().click());
    expect(document.body.textContent).toContain(
      "Applied filter: All report types",
    );
    expect(document.body.textContent).toContain(
      "The requested filter has not replaced these results",
    );
    expect(document.body.textContent).toContain(
      "Previously loaded reports remain visible",
    );
    expect(document.body.textContent).toContain(
      "Observed/imported by build 51",
    );
    expect(vi.mocked(diagnosticEvents.list).mock.calls[1][0]).toEqual({
      limit: 20,
      event_type: "crash",
    });
  });
});
