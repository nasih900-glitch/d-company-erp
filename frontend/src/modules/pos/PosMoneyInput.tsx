import type { InputHTMLAttributes } from 'react';

export const POS_MONEY_INPUT_LABELS = {
  discount: 'Custom discount amount in rupees',
  tip: 'Tip amount in rupees',
  cashTendered: 'Cash received from customer in rupees',
  splitPayment: 'Split payment amount in rupees',
  splitCashTendered: 'Cash received for split payment in rupees',
} as const;

export function PosMoneyInput({
  purpose,
  accessibleName,
  ...props
}: Omit<InputHTMLAttributes<HTMLInputElement>, 'aria-label' | 'type'> & {
  purpose: keyof typeof POS_MONEY_INPUT_LABELS;
  accessibleName?: string;
}) {
  return (
    <input
      {...props}
      type="number"
      aria-label={accessibleName ?? POS_MONEY_INPUT_LABELS[purpose]}
    />
  );
}
