import { describe, expect, it } from "vitest";
import type { GamingParticipantDTO } from "@/lib/erp-api";
import { anonymousGamingPlayerLabel } from "./GamingScreen";
describe("Readable retained player identity labels", () => {
  it("keeps an anonymous player ordinal through Leave and rejoin without exposing an ID", () => {
    const first = {
      id: "old-interval",
      participant_identity_id: "identity-a",
      customer_id: null,
      joined_at: "2026-10-19T10:00:00Z",
    } as GamingParticipantDTO;
    const second = {
      id: "second",
      participant_identity_id: "identity-b",
      customer_id: null,
      joined_at: "2026-10-19T10:01:00Z",
    } as GamingParticipantDTO;
    const rejoin = {
      ...first,
      id: "new-interval",
      joined_at: "2026-10-19T10:02:00Z",
    };
    expect(anonymousGamingPlayerLabel(rejoin, [second, rejoin, first])).toBe(
      "Added player 1",
    );
    expect(anonymousGamingPlayerLabel(second, [second, rejoin, first])).toBe(
      "Added player 2",
    );
  });
});
