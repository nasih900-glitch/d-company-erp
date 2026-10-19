// @vitest-environment happy-dom
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NotificationProvider } from "@/components/ui/Notifications";
import { menuAdmin, type MenuItemDTO } from "@/lib/erp-api";
import { ItemForm } from "./MenuScreen";
vi.mock("@/lib/erp-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/erp-api")>()),
  menuAdmin: { updateItem: vi.fn() },
}));
let root: Root;
beforeEach(() => {
  (
    globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
  ).IS_REACT_ACT_ENVIRONMENT = true;
  root = createRoot(document.body.appendChild(document.createElement("div")));
  vi.mocked(menuAdmin.updateItem).mockResolvedValue({} as MenuItemDTO);
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  vi.clearAllMocks();
});
const item = {
  id: "item",
  category_id: "drinks",
  sku: "QA-1",
  name: "Synthetic drink",
  type: "drink",
  base_price_minor: 1000,
  tax_rate: 0.005,
  hsn_code: "996331",
  price_includes_tax: true,
  is_available: true,
  description: "Original description",
} satisfies MenuItemDTO;
function field(
  label: string,
): HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement {
  return [...document.querySelectorAll("label")]
    .find((row) => row.textContent?.trim().startsWith(label))!
    .querySelector("input,textarea,select")!;
}
function renderForm() {
  act(() =>
    root.render(
      <NotificationProvider>
        <ItemForm
          item={item}
          cats={[{ id: "drinks", name: "Drinks", sort_order: 1 }]}
          onClose={() => {}}
          onSuccess={() => {}}
        />
      </NotificationProvider>,
    ),
  );
}
describe("Exact configured tax metadata while editing", () => {
  it("shows a retained nonstandard percentage and preserves its fraction on an unrelated edit", async () => {
    renderForm();
    const rate = field("Configured GST rate") as HTMLSelectElement;
    expect(rate.value).toBe("0.5");
    expect(rate.selectedOptions[0].textContent).toContain(
      "current configured rate",
    );
    const description = field("Description") as HTMLTextAreaElement;
    act(() => {
      Object.getOwnPropertyDescriptor(
        HTMLTextAreaElement.prototype,
        "value",
      )!.set!.call(description, "Updated description");
      description.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => {
      document
        .querySelector("form")!
        .dispatchEvent(
          new Event("submit", { bubbles: true, cancelable: true }),
        );
    });
    expect(menuAdmin.updateItem).toHaveBeenCalledWith(
      "item",
      expect.objectContaining({
        tax_rate: 0.005,
        base_price_minor: 1000,
        description: "Updated description",
      }),
    );
  });
  it("changes the fraction only when the configured rate is deliberately selected", async () => {
    renderForm();
    const rate = field("Configured GST rate") as HTMLSelectElement;
    act(() => {
      rate.value = "5";
      rate.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => {
      document
        .querySelector("form")!
        .dispatchEvent(
          new Event("submit", { bubbles: true, cancelable: true }),
        );
    });
    expect(menuAdmin.updateItem).toHaveBeenCalledWith(
      "item",
      expect.objectContaining({ tax_rate: 0.05, base_price_minor: 1000 }),
    );
  });
});
