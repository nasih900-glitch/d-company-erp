import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type {
  ClientInstallationDTO,
  ClientInstallationListDTO,
} from "@/lib/erp-api";
import { ReportedQueueSummary } from "./DevicesUpdatesTab";
const row = (stale: boolean, count = 0) =>
  ({ is_stale: stale, pending_outbox_count: count }) as ClientInstallationDTO;
function markup(items: ClientInstallationDTO[], total = items.length) {
  return renderToStaticMarkup(
    <ReportedQueueSummary
      installations={
        {
          items,
          total,
          server_time: "2026-10-19T10:00:00Z",
          stale_after_hours: 24,
          limit: 200,
          offset: 0,
        } as ClientInstallationListDTO
      }
    />,
  );
}
describe("Rendered automatic queue coverage", () => {
  it.each([{ items: [] }, { items: [row(true)] }, { items: [row(true, 9)] }])(
    "keeps absent and stale-only queues unknown",
    ({ items }) => {
      const html = markup(items);
      expect(html).toContain("Unknown");
      expect(html).toContain("current queues unknown");
      expect(html).not.toContain("Reported queues are clear");
      expect(html).not.toContain("text-accent-good");
    },
  );
  it("limits a zero to loaded recent reports when coverage is truncated or stale", () => {
    const html = markup([row(false), row(true, 9)], 201);
    expect(html).toContain("1 loaded recent report only");
    expect(html).toContain("1 loaded stale report excluded");
    expect(html).toContain("199 additional tablets not loaded");
    expect(html).not.toContain("text-accent-good");
    expect(html).toContain("Protected owner recovery is separate");
  });
  it("labels a recent zero as historical reported automatic work rather than all saved work", () => {
    const html = markup([row(false)]);
    expect(html).toContain("Reported automatic queue");
    expect(html).toContain("1 loaded recent report only, at check");
    expect(html).toContain("current queues are not verified");
  });
  it("sums only loaded recent automatic actions without consuming a stale saved count", () => {
    const html = markup([row(false, 3), row(true, 99)]);
    expect(html).toContain(">3</p>");
    expect(html).toContain("1 loaded stale report excluded");
  });
});
