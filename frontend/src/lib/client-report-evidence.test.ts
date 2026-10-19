import { describe, expect, it } from "vitest";
import type { ClientDiagnosticEventDTO, SystemHealthDTO } from "./erp-api";
import {
  incidentBuildEvidence,
  updateCoverageEvidence,
} from "./client-report-evidence";
describe("Reported device and incident evidence", () => {
  it("never attributes an old OS exit to its importing build", () => {
    for (const reason_code of [
      "process_anr",
      "java_crash_exit",
      "native_crash_exit",
      "low_memory_exit",
      "excessive_resource_exit",
    ]) {
      const row = {
        reason_code,
        event_type: "crash",
        version_code: 52,
        version_name: "3.1.44",
        occurred_at: "2026-10-17T01:00:00Z",
      } as ClientDiagnosticEventDTO;
      const original = structuredClone(row);
      expect(incidentBuildEvidence(row)).toContain(
        "Observed/imported by build 51",
      );
      expect(incidentBuildEvidence(row)).toContain(
        "Actual incident build was not retained",
      );
      expect(row).toEqual(original);
    }
  });
  it("retains captured Java build evidence and explicit unknown provenance", () => {
    expect(
      incidentBuildEvidence({
        event_type: "crash",
        reason_code: "null_pointer",
        version_code: 50,
        version_name: "3.1.42",
      } as ClientDiagnosticEventDTO),
    ).toBe("Captured event build 50 · v3.1.42");
    expect(
      incidentBuildEvidence({
        event_type: "anr",
        reason_code: "legacy_unknown",
        version_code: 50,
        version_name: "3.1.42",
      } as ClientDiagnosticEventDTO),
    ).toContain("provenance is unavailable");
  });
  it("does not claim that stale-only or absent reporters form a current fleet", () => {
    const devices = {
      total: 0,
      seen_last_24h: 0,
      stale: 0,
      outdated_installations: 0,
    } as SystemHealthDTO["devices"];
    expect(updateCoverageEvidence(devices)).toEqual({
      label: "No reporting tablets · coverage unknown",
      tone: "neutral",
    });
    expect(
      updateCoverageEvidence({ ...devices, total: 3, stale: 3 }).label,
    ).toContain("current state unknown");
    expect(
      updateCoverageEvidence({
        ...devices,
        total: 3,
        seen_last_24h: 1,
        stale: 2,
      }).tone,
    ).toBe("neutral");
    expect(
      updateCoverageEvidence({
        ...devices,
        total: 3,
        seen_last_24h: 1,
        stale: 2,
        outdated_installations: 1,
      }).label,
    ).toContain("among recent reports");
  });
});
