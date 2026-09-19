import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const source = readFileSync(
  resolve(process.cwd(), "ios/App/App/DCompanyNativeApp.swift"),
  "utf8",
);

function section(start: string, end: string): string {
  const startIndex = source.indexOf(start);
  const endIndex = source.indexOf(end, startIndex + start.length);
  expect(startIndex).toBeGreaterThanOrEqual(0);
  expect(endIndex).toBeGreaterThan(startIndex);
  return source.slice(startIndex, endIndex);
}

describe("native iOS finance expense contract", () => {
  it("decodes the current expense evidence and cash-shift fields", () => {
    const dto = section(
      "private struct ExpenseDTO",
      "private struct ExpenseCreateRequest",
    );
    const request = section(
      "private struct ExpenseCreateRequest",
      "private struct ExpenseVoidRequest",
    );

    expect(dto).toContain("let shift_id: String?");
    expect(dto).toContain("let receipt_count: Int");
    expect(dto).toContain("let receipt_status: String");
    expect(dto).toContain("let is_voided: Bool");
    expect(request).toContain("let shift_id: String?");
  });

  it("requires an explicit same-branch open shift for a cash paid-out", () => {
    const expenseList = section(
      "private struct FinanceExpensesTab",
      "private struct VoidExpenseSheet",
    );
    const addExpense = section(
      "private struct AddExpenseSheet",
      "private struct ExpenseReceiptsSheet",
    );

    expect(expenseList).toContain('"pos/shifts"');
    expect(expenseList).toContain(
      'URLQueryItem(name: "only_open", value: "true")',
    );
    expect(expenseList).toContain('APIClient.shared.get("finance/branches"');
    expect(addExpense).toContain(
      '$0.status == "open" && $0.branch_id == branchId',
    );
    expect(addExpense).toContain(
      'paidVia != "cash" || selectedCashShift != nil',
    );
    expect(addExpense).toContain(
      '@State private var idempotencyKey = "expense:\\(UUID().uuidString.lowercased())"',
    );
    expect(addExpense).not.toContain("isShiftOpener");
  });

  it("voids with a required reason instead of calling legacy delete", () => {
    const expenseList = section(
      "private struct FinanceExpensesTab",
      "private struct AddExpenseSheet",
    );

    expect(expenseList).toContain("private struct VoidExpenseSheet");
    expect(expenseList).toContain("(3...500).contains(normalizedReason.count)");
    expect(expenseList).toContain('"finance/expenses/\\(expense.id)/void"');
    expect(source).not.toContain(
      'APIClient.shared.delete("finance/expenses/\\(expense.id)"',
    );
  });

  it("saves once and retries receipt evidence against the saved expense", () => {
    const addExpense = section(
      "private struct AddExpenseSheet",
      "private struct ExpenseReceiptsSheet",
    );
    const receiptList = section(
      "private struct ExpenseReceiptsSheet",
      "private struct ExpenseReceiptQuickLook",
    );

    expect(addExpense).toContain(
      "@State private var createdExpense: ExpenseDTO?",
    );
    expect(addExpense).toContain("if let createdExpense");
    expect(addExpense).toContain("createdExpense = savedExpense");
    expect(addExpense).toContain('"finance/expenses/\\(expense.id)/receipts"');
    expect(addExpense).toContain(
      'headers: ["Idempotency-Key": receiptIdempotencyKey]',
    );
    expect(addExpense).toContain('source: "camera"');
    expect(addExpense).toContain('source: "gallery"');
    expect(source).toContain('source: "file"');
    expect(addExpense).toContain(".fileImporter(");
    expect(source).not.toContain('mimeType = "image/heic"');
    expect(source).not.toContain('mimeType = "image/heif"');
    expect(receiptList).toContain('"finance/expenses/\\(expense.id)/receipts"');
    expect(receiptList).toContain("ExpenseReceiptQuickLook");
  });

  it("does not restore a direct Google Sheets delivery path", () => {
    expect(source).not.toContain("GSheetsPusher");
    expect(source).not.toContain("script.google.com");
    expect(source).not.toContain("Push to Sheets");
  });
});
