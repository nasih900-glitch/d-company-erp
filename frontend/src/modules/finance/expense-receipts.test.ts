import { describe, expect, it, vi } from 'vitest';

import type { ExpenseDTO, ExpenseReceiptDTO } from '@/lib/erp-api';
import {
  expenseReceiptFileError,
  expenseReceiptReviewError,
  expenseReceiptReviewStatuses,
  expenseReceiptSummary,
  normalizeExpenseReceiptFile,
  pickedExpenseReceiptSource,
  persistExpenseWithOptionalReceipt,
} from './expense-receipts';

const expense: ExpenseDTO = {
  id: 'expense-1',
  branch_id: 'branch-1',
  category_id: 'category-1',
  supplier_id: null,
  amount_minor: 12500,
  paid_via: 'upi',
  paid_at: '2026-09-19T12:00:00Z',
  vendor_name: 'Test Vendor',
  invoice_no: 'INV-1',
  note: null,
  receipt_count: 0,
  receipt_status: 'pending',
};

const receipt: ExpenseReceiptDTO = {
  id: 'receipt-1',
  expense_id: expense.id,
  original_filename: 'bill.jpg',
  content_type: 'image/jpeg',
  size_bytes: 4,
  source: 'camera',
  status: 'pending',
  review_note: null,
  created_at: '2026-09-19T12:01:00Z',
};

describe('expense receipt workflow', () => {
  it('retains the created expense when upload fails and never creates it again on retry', async () => {
    const createExpense = vi.fn().mockResolvedValue(expense);
    const uploadReceipt = vi.fn()
      .mockRejectedValueOnce(new Error('Connection dropped'))
      .mockResolvedValueOnce(receipt);
    const file = new File(['bill'], 'bill.jpg', { type: 'image/jpeg' });

    const first = await persistExpenseWithOptionalReceipt({
      savedExpense: null,
      selectedFile: file,
      source: 'camera',
      createExpense,
      uploadReceipt,
      receiptIdempotencyKey: 'receipt-key-1',
    });
    expect(first).toEqual({ expense, receipt: null, receiptError: 'Connection dropped' });

    const retried = await persistExpenseWithOptionalReceipt({
      savedExpense: first.expense,
      selectedFile: file,
      source: 'camera',
      createExpense,
      uploadReceipt,
      receiptIdempotencyKey: 'receipt-key-1',
    });
    expect(retried).toEqual({ expense, receipt, receiptError: null });
    expect(createExpense).toHaveBeenCalledTimes(1);
    expect(uploadReceipt).toHaveBeenCalledTimes(2);
    expect(uploadReceipt).toHaveBeenLastCalledWith(
      expense.id, file, 'camera', 'receipt-key-1',
    );
  });

  it('allows a complete manual expense without a receipt', async () => {
    const createExpense = vi.fn().mockResolvedValue(expense);
    const uploadReceipt = vi.fn();

    await expect(persistExpenseWithOptionalReceipt({
      savedExpense: null,
      selectedFile: null,
      source: 'file',
      createExpense,
      uploadReceipt,
      receiptIdempotencyKey: 'receipt-key-2',
    })).resolves.toEqual({ expense, receipt: null, receiptError: null });
    expect(uploadReceipt).not.toHaveBeenCalled();
  });

  it('validates supported files and restores missing safe image content types', () => {
    expect(expenseReceiptFileError(new File(['x'], 'bill.txt', { type: 'text/plain' })))
      .toContain('PDF, JPEG, PNG or WebP');
    expect(expenseReceiptFileError(new File(['x'], 'bill.pdf', { type: 'application/pdf' })))
      .toBeNull();
    const jpeg = new File(['x'], 'camera.JPG');
    expect(normalizeExpenseReceiptFile(jpeg).type).toBe('image/jpeg');
    expect(pickedExpenseReceiptSource(jpeg)).toBe('gallery');
    expect(expenseReceiptFileError(new File(['x'], 'camera.heic', { type: 'image/heic' })))
      .toContain('PDF, JPEG, PNG or WebP');
    expect(pickedExpenseReceiptSource(new File(['x'], 'bill.pdf', { type: 'application/pdf' })))
      .toBe('file');
    expect(expenseReceiptFileError(new File(['x'], 'bill.pdf', { type: 'text/plain' })))
      .toContain('PDF, JPEG, PNG or WebP');
  });

  it('requires an audit reason when evidence is rejected or not required', () => {
    expect(expenseReceiptReviewError('verified', '')).toBeNull();
    expect(expenseReceiptReviewError('rejected', '')).toContain('short reason');
    expect(expenseReceiptReviewError('not_required', 'No supplier receipt')).toBeNull();
  });

  it('keeps not required available after a receipt has been attached', () => {
    expect(expenseReceiptReviewStatuses(0)).toEqual(['pending', 'not_required']);
    expect(expenseReceiptReviewStatuses(1)).toEqual([
      'pending', 'verified', 'not_required', 'rejected',
    ]);
    expect(expenseReceiptReviewStatuses(0, 'verified')).toEqual([
      'verified', 'pending', 'not_required',
    ]);
  });

  it('summarizes server receipt evidence without inventing old-server status', () => {
    expect(expenseReceiptSummary(expense)).toBe('No receipt');
    expect(expenseReceiptSummary({ ...expense, receipt_count: 2, receipt_status: 'verified' }))
      .toBe('2 receipts · Verified');
    expect(expenseReceiptSummary({ ...expense, receipt_count: undefined, receipt_status: undefined }))
      .toBe('Receipt status unavailable');
  });
});
