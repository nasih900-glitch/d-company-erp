import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { finance } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('expense receipt API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uploads the selected file and its capture source as authenticated multipart data', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'receipt-1' } });
    const file = new File(['image'], 'bill.jpg', { type: 'image/jpeg' });

    await finance.uploadExpenseReceipt('expense-1', file, 'camera', 'receipt-key-1');

    const [url, body, config] = vi.mocked(api.post).mock.calls[0];
    expect(url).toBe('/finance/expenses/expense-1/receipts');
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('file')).toBe(file);
    expect((body as FormData).get('source')).toBe('camera');
    expect(config).toEqual({ headers: { 'Idempotency-Key': 'receipt-key-1' } });
  });

  it('lists, downloads and reviews receipts through protected finance routes', async () => {
    const blob = new Blob(['receipt'], { type: 'application/pdf' });
    vi.mocked(api.get)
      .mockResolvedValueOnce({ data: [] })
      .mockResolvedValueOnce({ data: blob });
    vi.mocked(api.post).mockResolvedValueOnce({ data: { id: 'expense-1' } });

    await finance.listExpenseReceipts('expense-1');
    await expect(finance.getExpenseReceiptContent('receipt-1')).resolves.toBe(blob);
    await finance.reviewExpenseReceipts('expense-1', {
      status: 'verified',
      review_note: 'Matched to statement',
    }, 'review-key-1');

    expect(api.get).toHaveBeenNthCalledWith(1, '/finance/expenses/expense-1/receipts');
    expect(api.get).toHaveBeenNthCalledWith(2, '/finance/expense-receipts/receipt-1/content', {
      responseType: 'blob',
    });
    expect(api.post).toHaveBeenCalledWith(
      '/finance/expenses/expense-1/receipt-review',
      { status: 'verified', review_note: 'Matched to statement' },
      { headers: { 'Idempotency-Key': 'review-key-1' } },
    );
  });

  it('links cash paid-outs to an explicit shift and voids them with an audit reason', async () => {
    vi.mocked(api.post)
      .mockResolvedValueOnce({ data: { id: 'expense-1' } })
      .mockResolvedValueOnce({ data: { id: 'expense-1', is_voided: true } });
    const body = {
      branch_id: 'branch-1',
      category_id: 'category-1',
      amount_minor: 7500,
      paid_via: 'cash' as const,
      paid_at: '2026-09-19T16:00:00.000Z',
      shift_id: 'shift-1',
    };

    await finance.createExpense(body, 'expense:323e4567-e89b-42d3-a456-426614174000');
    await finance.voidExpense('expense-1', 'Duplicate entry');

    expect(api.post).toHaveBeenNthCalledWith(1, '/finance/expenses', body, {
      headers: { 'Idempotency-Key': 'expense:323e4567-e89b-42d3-a456-426614174000' },
    });
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      '/finance/expenses/expense-1/void',
      { reason: 'Duplicate entry' },
    );
  });
});
