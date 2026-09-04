export interface ActionFeedback {
  title: string;
  message: string;
}

export const FINANCE_ACTION_FEEDBACK = {
  manualCollectionRecorded: {
    title: 'Collection recorded',
    message: 'The manual collection is now included in the Finance register and reports.',
  },
  manualCollectionVoided: {
    title: 'Collection voided',
    message: 'The collection is excluded from active totals; its original record and reason remain in the audit history.',
  },
  tipPayoutRecorded: {
    title: 'Tip payout recorded',
    message: 'The payout is now recorded against Tips Payable in Finance.',
  },
  tipPayoutVoided: {
    title: 'Tip payout voided',
    message: 'The payout is excluded from active totals and the amount is owed to staff again; the audit record remains.',
  },
  assetRecorded: {
    title: 'Asset recorded',
    message: 'The asset is now included in the fixed-asset register and depreciation reporting.',
  },
  partnerRecorded: {
    title: 'Partner recorded',
    message: 'The partner is now available for capital movements and ownership reporting.',
  },
  capitalRecorded: {
    title: 'Capital movement recorded',
    message: 'The movement is now included in the partner capital ledger.',
  },
  capitalVoided: {
    title: 'Capital movement voided',
    message: 'The movement is excluded from the active capital balance; its original record and reason remain visible.',
  },
} as const satisfies Record<string, ActionFeedback>;

export function queuedOrderVoidedFeedback(sourceLabel?: string | null): ActionFeedback {
  return {
    title: 'Queued bill voided',
    message: `${sourceLabel?.trim() || 'The queued bill'} was voided with its reason preserved. No payment was recorded.`,
  };
}

export const POS_CART_CLEARED_FEEDBACK: ActionFeedback = {
  title: 'Cart cleared',
  message: 'The unsent cart and its saved recovery copy were cleared. No order or payment was created.',
};

export const POS_PREPARED_BILL_CANCELLED_FEEDBACK: ActionFeedback = {
  title: 'Prepared bill cancelled',
  message: 'The prepared bill was cancelled with an audit reason. No payment was recorded.',
};
