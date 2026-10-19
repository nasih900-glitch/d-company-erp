import type { ClientDiagnosticEventDTO, SystemHealthDTO } from "./erp-api";

// Read-side only: never rewrite queued event identity, body or captured build fields.
const importedExits = new Set([
  "process_anr",
  "java_crash_exit",
  "native_crash_exit",
  "low_memory_exit",
  "excessive_resource_exit",
]);
const capturedJava = new Set([
  "out_of_memory",
  "stack_overflow",
  "security_exception",
  "illegal_state",
  "null_pointer",
  "concurrent_modification",
  "unhandled_java_exception",
]);

export function incidentBuildEvidence(row: ClientDiagnosticEventDTO): string {
  const build = `build ${row.version_code} · v${row.version_name}`;
  if (importedExits.has(row.reason_code)) {
    return `Observed/imported by ${build}. Actual incident build was not retained.`;
  }
  if (row.event_type === "crash" && capturedJava.has(row.reason_code)) {
    return `Captured event ${build}`;
  }
  if (row.event_type === "crash" || row.event_type === "anr") {
    return `Reported ${build}. Actual incident build provenance is unavailable.`;
  }
  return `Reporting ${build}`;
}

export function updateCoverageEvidence(devices: SystemHealthDTO["devices"]): {
  label: string;
  tone: "neutral" | "warning" | "good";
} {
  if (!devices.total) {
    return {
      label: "No reporting tablets · coverage unknown",
      tone: "neutral",
    };
  }
  if (!devices.seen_last_24h) {
    return {
      label: "No recent tablet reports · current state unknown",
      tone: "neutral",
    };
  }
  if (devices.outdated_installations) {
    return {
      label: `${devices.outdated_installations} older builds among recent reports`,
      tone: "warning",
    };
  }
  return {
    label: "No older builds flagged among recent reports",
    tone: devices.stale ? "neutral" : "good",
  };
}
