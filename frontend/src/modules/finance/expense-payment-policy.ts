import type { ExpenseCreatePaymentRail } from '@/lib/erp-api';

export interface ExpensePaymentOption {
  value: ExpenseCreatePaymentRail;
  label: string;
}

export const DEFAULT_EXPENSE_PAYMENT_RAIL: ExpenseCreatePaymentRail = 'upi';

export const EXPENSE_PAYMENT_OPTIONS: readonly ExpensePaymentOption[] = [
  { value: 'upi', label: 'UPI (business account)' },
  { value: 'card', label: 'Business debit card' },
  { value: 'bank', label: 'Bank transfer' },
];

export const EXPENSE_CASH_DRAWER_GUIDANCE =
  'Cash is unavailable here because ordinary expenses are not linked to the open shift drawer. ' +
  'Use UPI, business debit card or bank transfer. Cash paid-outs will be available only through ' +
  'the future shift-linked drawer workflow.';
