import type { ExpenseCreatePaymentRail } from '@/lib/erp-api';

export interface ExpensePaymentOption {
  value: ExpenseCreatePaymentRail;
  label: string;
}

export const DEFAULT_EXPENSE_PAYMENT_RAIL: ExpenseCreatePaymentRail = 'upi';

export const EXPENSE_PAYMENT_OPTIONS: readonly ExpensePaymentOption[] = [
  { value: 'cash', label: 'Cash from open shift drawer' },
  { value: 'upi', label: 'UPI (business account)' },
  { value: 'card', label: 'Business debit card' },
  { value: 'bank', label: 'Bank transfer' },
];

export const EXPENSE_CASH_DRAWER_GUIDANCE =
  'Cash paid-outs must be linked to the open shift drawer that supplied the money. ' +
  'The amount is deducted from that drawer immediately and restored only if an authorised user ' +
  'voids the expense while the same shift is still open.';
