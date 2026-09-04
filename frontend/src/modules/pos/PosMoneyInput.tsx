import type { InputHTMLAttributes } from 'react';

export const POS_MONEY_INPUT_LABELS = {
  discount: 'Custom discount amount in rupees',
  tip: 'Tip amount in rupees',
  cashTendered: 'Cash received from customer in rupees',
} as const;

export function PosMoneyInput({
  purpose,
  ...props
}: Omit<InputHTMLAttributes<HTMLInputElement>, 'aria-label' | 'type'> & {
  purpose: keyof typeof POS_MONEY_INPUT_LABELS;
}) {
  return (
    <input
      {...props}
      type="number"
      aria-label={POS_MONEY_INPUT_LABELS[purpose]}
    />
  );
}
