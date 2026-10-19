// @vitest-environment happy-dom
import { act, useState } from "react";
import { createRoot } from "react-dom/client";
import { describe, expect, it } from "vitest";
import { WorkspaceAppearanceChooser } from "./AppShell";
import {
  readAppearancePreference,
  saveAppearancePreference,
} from "@/lib/appearance";
describe("Desktop appearance shortcut", () => {
  it("offers all three persisted modes and returns keyboard focus to the same control", () => {
    (
      globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
    ).IS_REACT_ACT_ENVIRONMENT = true;
    const host = document.body.appendChild(document.createElement("div"));
    const root = createRoot(host);
    const rows = new Map<string, string>();
    const storage = {
      getItem: (key: string) => rows.get(key) ?? null,
      setItem: (key: string, value: string) => {
        rows.set(key, value);
      },
      removeItem: (key: string) => {
        rows.delete(key);
      },
    };
    function Harness() {
      const [appearance, setAppearance] = useState(
        readAppearancePreference(storage),
      );
      return (
        <WorkspaceAppearanceChooser
          appearance={appearance}
          resolvedNight={appearance !== "day"}
          onChange={(value) => {
            saveAppearancePreference(value, storage);
            setAppearance(value);
          }}
        />
      );
    }
    act(() => root.render(<Harness />));
    const details = host.querySelector("details")!;
    const summary = host.querySelector("summary")!;
    for (const label of ["Day", "Night", "System"]) {
      act(() => {
        details.open = true;
        [...host.querySelectorAll("button")]
          .find((button) => button.textContent === label)!
          .click();
      });
      expect(readAppearancePreference(storage)).toBe(label.toLowerCase());
      expect(document.activeElement).toBe(summary);
      expect(details.open).toBe(false);
    }
    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
    act(() => root.unmount());
    host.remove();
  });
});
