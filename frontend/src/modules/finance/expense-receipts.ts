import type {
  ExpenseDTO,
  ExpenseReceiptDTO,
  ExpenseReceiptSource,
  ExpenseReceiptStatus,
} from '@/lib/erp-api';

export const EXPENSE_RECEIPT_ACCEPT =
  'image/jpeg,image/png,image/webp,application/pdf';
export const MAX_EXPENSE_RECEIPT_BYTES = 10 * 1024 * 1024;

const ALLOWED_RECEIPT_TYPES = new Set([
  'application/pdf',
  'image/jpeg',
  'image/png',
  'image/webp',
]);

const EXTENSION_TYPES: Record<string, string> = {
  '.jpeg': 'image/jpeg',
  '.jpg': 'image/jpeg',
  '.pdf': 'application/pdf',
  '.png': 'image/png',
  '.webp': 'image/webp',
};

export function expenseReceiptFileError(file: File): string | null {
  if (file.size === 0) return 'The selected receipt is empty.';
  if (file.size > MAX_EXPENSE_RECEIPT_BYTES) {
    return 'The selected receipt is larger than 10 MB.';
  }
  const extension = file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
  const claimedType = file.type.toLowerCase();
  const mayInferType = claimedType === '' || claimedType === 'application/octet-stream';
  if (
    (!mayInferType && !ALLOWED_RECEIPT_TYPES.has(claimedType))
    || (mayInferType && !EXTENSION_TYPES[extension])
  ) {
    return 'Choose a PDF, JPEG, PNG or WebP receipt.';
  }
  return null;
}

/** Mobile file pickers sometimes omit the MIME type for otherwise supported files. */
export function normalizeExpenseReceiptFile(file: File): File {
  if (file.type && file.type !== 'application/octet-stream') return file;
  const extension = file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
  const inferredType = EXTENSION_TYPES[extension];
  return inferredType
    ? new File([file], file.name, { type: inferredType, lastModified: file.lastModified })
    : file;
}

export function pickedExpenseReceiptSource(file: File): ExpenseReceiptSource {
  const extension = file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
  const normalizedType = file.type.toLowerCase();
  return normalizedType === 'application/pdf' || extension === '.pdf' ? 'file' : 'gallery';
}

export function expenseReceiptReviewError(
  status: ExpenseReceiptStatus,
  note: string,
): string | null {
  if ((status === 'rejected' || status === 'not_required') && note.trim().length < 3) {
    return `Add a short reason before marking this receipt ${EXPENSE_RECEIPT_STATUS_LABEL[status].toLowerCase()}.`;
  }
  return null;
}

export const EXPENSE_RECEIPT_STATUS_LABEL: Record<ExpenseReceiptStatus, string> = {
  not_required: 'Not required',
  pending: 'Needs review',
  rejected: 'Rejected',
  verified: 'Verified',
};

export function expenseReceiptReviewStatuses(
  receiptCount: number,
  currentStatus?: ExpenseReceiptStatus,
): ExpenseReceiptStatus[] {
  const options: ExpenseReceiptStatus[] = receiptCount === 0
    ? ['pending', 'not_required']
    : ['pending', 'verified', 'not_required', 'rejected'];
  return currentStatus && !options.includes(currentStatus)
    ? [currentStatus, ...options]
    : options;
}

export function expenseReceiptSummary(expense: ExpenseDTO): string {
  const count = expense.receipt_count;
  const status = expense.receipt_status;
  if (count === undefined || status === undefined) return 'Receipt status unavailable';
  if (status === 'not_required') return EXPENSE_RECEIPT_STATUS_LABEL.not_required;
  if (count === 0) return 'No receipt';
  const countLabel = `${count} receipt${count === 1 ? '' : 's'}`;
  return `${countLabel} · ${EXPENSE_RECEIPT_STATUS_LABEL[status]}`;
}

export interface PersistExpenseReceiptOptions {
  savedExpense: ExpenseDTO | null;
  selectedFile: File | null;
  source: ExpenseReceiptSource;
  createExpense: () => Promise<ExpenseDTO>;
  uploadReceipt: (
    expenseId: string,
    file: File,
    source: ExpenseReceiptSource,
    idempotencyKey: string,
  ) => Promise<ExpenseReceiptDTO>;
  receiptIdempotencyKey: string;
}

export interface PersistExpenseReceiptResult {
  expense: ExpenseDTO;
  receipt: ExpenseReceiptDTO | null;
  receiptError: string | null;
}

/**
 * Creates the accounting row before its optional document. A failed document
 * upload returns the saved expense so retries can never create another row.
 */
export async function persistExpenseWithOptionalReceipt({
  savedExpense,
  selectedFile,
  source,
  createExpense,
  uploadReceipt,
  receiptIdempotencyKey,
}: PersistExpenseReceiptOptions): Promise<PersistExpenseReceiptResult> {
  const expense = savedExpense ?? await createExpense();
  if (!selectedFile) return { expense, receipt: null, receiptError: null };

  try {
    const receipt = await uploadReceipt(
      expense.id,
      normalizeExpenseReceiptFile(selectedFile),
      source,
      receiptIdempotencyKey,
    );
    return { expense, receipt, receiptError: null };
  } catch (error) {
    return {
      expense,
      receipt: null,
      receiptError: error instanceof Error ? error.message : 'The receipt upload failed.',
    };
  }
}
