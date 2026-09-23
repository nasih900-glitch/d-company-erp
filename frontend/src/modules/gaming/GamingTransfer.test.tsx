import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { StationDTO } from "@/lib/erp-api";
import {
  GamingTransferModal,
  applyConfirmedGamingTransfer,
  eligibleGamingTransferTargets,
  gamingTransferFailureGuidance,
} from "./GamingTransfer";

const source: StationDTO = {
  id: "station-source",
  branch_id: "branch-1",
  code: "PS5-1",
  name: "PS5 Station 1",
  type: "ps5",
  rate_per_hour_minor: 12_000,
  is_active: true,
};

function station(id: string, changes: Partial<StationDTO> = {}): StationDTO {
  return {
    ...source,
    id,
    code: id,
    name: id,
    ...changes,
  };
}

describe("Gaming station transfer policy", () => {
  it("offers only active, available stations in the same branch and type", () => {
    const available = station("available");
    const activeOccupied = station("active-occupied");
    const pausedOccupied = station("paused-occupied");
    const paymentDue = station("payment-due");
    const stations = [
      source,
      available,
      activeOccupied,
      pausedOccupied,
      paymentDue,
      station("inactive", { is_active: false }),
      station("wrong-type", { type: "vr" }),
      station("wrong-branch", { branch_id: "branch-2" }),
    ];
    const sessions = {
      [source.id]: {
        station_id: source.id,
        backend_session_id: "session-source",
        status: "active" as const,
      },
      [activeOccupied.id]: {
        station_id: activeOccupied.id,
        backend_session_id: "session-active",
        status: "active" as const,
      },
      [pausedOccupied.id]: {
        station_id: pausedOccupied.id,
        backend_session_id: "session-paused",
        status: "paused" as const,
      },
      [paymentDue.id]: {
        station_id: paymentDue.id,
        backend_session_id: "session-ended",
        status: "ended" as const,
      },
    };

    expect(eligibleGamingTransferTargets(source, stations, sessions)).toEqual([
      available,
    ]);
  });

  it("atomically relocates only a confirmed matching session and preserves commercial state", () => {
    const sourceSession = {
      station_id: source.id,
      backend_session_id: "session-1",
      status: "paused" as const,
      shift_id: "shift-1",
      start_at: 1_788_172_400_000,
      timer_minutes: 60,
      timer_ends_at: 1_788_176_000_000,
      billing_mode: "package" as const,
      package_id: "package-1",
      package_variant_snapshot: "dual",
      locked_amount_minor: 15_000,
      amount_minor: 15_000,
      rate_per_hour_minor: 15_000,
      extra_controllers: 1,
    };
    const before = { [source.id]: sourceSession };

    const after = applyConfirmedGamingTransfer(
      before,
      "session-1",
      source.id,
      "station-target",
    );

    expect(after).not.toBe(before);
    expect(after[source.id]).toBeUndefined();
    expect(after["station-target"]).toEqual({
      ...sourceSession,
      station_id: "station-target",
    });
    expect(before[source.id]).toBe(sourceSession);
  });

  it("does not overwrite an occupied target or move a stale source snapshot", () => {
    const sourceSession = {
      station_id: source.id,
      backend_session_id: "session-1",
      status: "active" as const,
    };
    const occupied = {
      station_id: "station-target",
      backend_session_id: "session-2",
      status: "active" as const,
    };
    const before = { [source.id]: sourceSession, "station-target": occupied };

    expect(
      applyConfirmedGamingTransfer(
        before,
        "session-1",
        source.id,
        "station-target",
      ),
    ).toBe(before);
    expect(
      applyConfirmedGamingTransfer(
        { [source.id]: sourceSession },
        "different-session",
        source.id,
        "station-target",
      ),
    ).toEqual({ [source.id]: sourceSession });
  });

  it("requires refresh after stale conflicts and retains only ambiguous exact attempts", () => {
    expect(
      gamingTransferFailureGuidance({
        message:
          "Session was transferred on another device. Refresh Gaming and try again.",
        status: 409,
        ambiguous: false,
      }),
    ).toMatchObject({
      title: "Station state changed",
      refreshRequired: true,
      retainExactAttempt: false,
    });
    expect(
      gamingTransferFailureGuidance({
        message: "Network Error",
        ambiguous: true,
      }),
    ).toMatchObject({
      title: "Transfer not yet confirmed",
      refreshRequired: true,
      retainExactAttempt: true,
    });
  });
});

describe("GamingTransferModal", () => {
  it("names only eligible targets and explains the unchanged session evidence", () => {
    const markup = renderToStaticMarkup(
      <GamingTransferModal
        source={source}
        targets={[
          station("station-target", { name: "PS5 Station 2", code: "PS5-2" }),
        ]}
        busy={false}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    expect(markup).toContain("Transfer PS5 Station 1");
    expect(markup).toContain("PS5 Station 2");
    expect(markup).toContain("PS5-2");
    expect(markup).toContain(
      "locked price, package, timer, elapsed time, shift, customer and staged items stay unchanged",
    );
    expect(markup).toContain("Transfer session");
  });

  it("shows an actionable empty state and keeps confirmation disabled", () => {
    const markup = renderToStaticMarkup(
      <GamingTransferModal
        source={source}
        targets={[]}
        busy={false}
        error="Gaming refreshed after a station conflict."
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    expect(markup).toContain(
      "No active, available station of the same type is shown",
    );
    expect(markup).toContain("Gaming refreshed after a station conflict.");
    expect(markup).toMatch(
      /<button[^>]*type="submit"[^>]*disabled=""|<button[^>]*disabled=""[^>]*type="submit"/,
    );
  });
});
