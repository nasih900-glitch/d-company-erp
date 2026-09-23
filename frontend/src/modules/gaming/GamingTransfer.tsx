import { useMemo, useState } from "react";
import { ArrowRightLeft, Loader2 } from "lucide-react";

import type { StationDTO } from "@/lib/erp-api";
import Modal from "@/components/ui/Modal";

export interface GamingTransferSessionSnapshot {
  station_id: string;
  backend_session_id?: string;
  status: "active" | "paused" | "ended";
}

/**
 * Present only targets the transfer endpoint can accept from the current
 * authoritative board. The server repeats every check under row locks.
 * A visible ended session is payment due, so it blocks the station too.
 */
export function eligibleGamingTransferTargets<
  T extends GamingTransferSessionSnapshot,
>(
  source: StationDTO,
  stations: readonly StationDTO[],
  sessions: Readonly<Record<string, T | undefined>>,
): StationDTO[] {
  return stations.filter(
    (target) =>
      target.id !== source.id &&
      target.branch_id === source.branch_id &&
      target.type === source.type &&
      target.is_active &&
      sessions[target.id] === undefined,
  );
}

/**
 * Publish a confirmed transfer as one state replacement. Only station_id may
 * change locally: price, package, timer, shift and all other session evidence
 * are copied byte-for-byte from the source snapshot until the board refreshes.
 */
export function applyConfirmedGamingTransfer<
  T extends GamingTransferSessionSnapshot,
>(
  sessions: Record<string, T>,
  sessionId: string,
  expectedSourceStationId: string,
  confirmedTargetStationId: string,
): Record<string, T> {
  const source = sessions[expectedSourceStationId];
  if (
    !source ||
    source.backend_session_id !== sessionId ||
    source.station_id !== expectedSourceStationId ||
    (source.status !== "active" && source.status !== "paused")
  )
    return sessions;

  const destination = sessions[confirmedTargetStationId];
  if (destination && destination.backend_session_id !== sessionId) {
    return sessions;
  }

  const next = { ...sessions };
  delete next[expectedSourceStationId];
  next[confirmedTargetStationId] = {
    ...source,
    station_id: confirmedTargetStationId,
  };
  return next;
}

export function gamingTransferFailureGuidance({
  message,
  status,
  ambiguous,
}: {
  message: string;
  status?: number;
  ambiguous: boolean;
}): {
  title: string;
  message: string;
  refreshRequired: boolean;
  retainExactAttempt: boolean;
} {
  if (ambiguous) {
    return {
      title: "Transfer not yet confirmed",
      message:
        "The server response was interrupted. Gaming is refreshing the authoritative stations. If the session remains on the source station, retry only this exact transfer so its saved receipt key is reused.",
      refreshRequired: true,
      retainExactAttempt: true,
    };
  }
  if (status === 409) {
    return {
      title: "Station state changed",
      message: `${message} Gaming is refreshing the station board before another transfer.`,
      refreshRequired: true,
      retainExactAttempt: false,
    };
  }
  return {
    title: "Could not transfer session",
    message: `${message} The server did not confirm a station change.`,
    refreshRequired: false,
    retainExactAttempt: false,
  };
}

export function GamingTransferModal({
  source,
  targets,
  busy,
  error,
  retryTargetId,
  onConfirm,
  onCancel,
}: {
  source: StationDTO;
  targets: readonly StationDTO[];
  busy: boolean;
  error?: string | null;
  retryTargetId?: string | null;
  onConfirm: (target: StationDTO) => void;
  onCancel: () => void;
}) {
  const [selectedTargetId, setSelectedTargetId] = useState(retryTargetId ?? "");
  const selectedTarget = useMemo(
    () => targets.find((target) => target.id === selectedTargetId) ?? null,
    [selectedTargetId, targets],
  );
  const handleCancel = () => {
    if (!busy) onCancel();
  };

  return (
    <Modal
      open
      onClose={handleCancel}
      title={`Transfer ${source.name}`}
      size="sm"
    >
      <form
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (selectedTarget) onConfirm(selectedTarget);
        }}
      >
        <div className="rounded-lg border border-bg-border bg-bg-raised p-3 text-sm text-fg-muted">
          <div className="mb-1 flex items-center gap-2 font-semibold text-fg">
            <ArrowRightLeft size={15} /> Move the live session
          </div>
          The locked price, package, timer, elapsed time, shift, customer and
          staged items stay unchanged.
        </div>

        {targets.length > 0 ? (
          <fieldset disabled={busy} className="space-y-2">
            <legend className="mb-2 text-xs font-semibold uppercase tracking-wide text-fg-muted">
              Available matching station
            </legend>
            {targets.map((target) => {
              const selected = target.id === selectedTargetId;
              return (
                <button
                  key={target.id}
                  type="button"
                  className={`w-full rounded-lg border p-3 text-left transition ${
                    selected
                      ? "border-accent bg-accent/10 text-fg"
                      : "border-bg-border bg-bg-surface text-fg-muted hover:border-accent/60 hover:text-fg"
                  }`}
                  aria-pressed={selected}
                  onClick={() => setSelectedTargetId(target.id)}
                >
                  <span className="block font-semibold">{target.name}</span>
                  <span className="mt-0.5 block text-xs">{target.code}</span>
                </button>
              );
            })}
          </fieldset>
        ) : (
          <div className="rounded-lg border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm text-accent-gold">
            No active, available station of the same type is shown. Refresh
            Gaming after another session is moved, billed or cancelled.
          </div>
        )}

        {error && (
          <div
            role="alert"
            className="rounded-lg border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad"
          >
            {error}
          </div>
        )}

        <div className="flex justify-end gap-2">
          <button
            type="button"
            className="btn btn-ghost"
            onClick={handleCancel}
            disabled={busy}
          >
            Cancel
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={busy || !selectedTarget}
          >
            {busy ? (
              <Loader2 className="animate-spin" size={14} />
            ) : (
              <ArrowRightLeft size={14} />
            )}{" "}
            {retryTargetId ? "Retry exact transfer" : "Transfer session"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
