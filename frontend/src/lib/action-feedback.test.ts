import { describe, expect, it } from 'vitest';

import {
  FINANCE_ACTION_FEEDBACK,
  POS_CART_CLEARED_FEEDBACK,
  POS_PREPARED_BILL_CANCELLED_FEEDBACK,
  queuedOrderVoidedFeedback,
} from './action-feedback';

describe('critical action feedback', () => {
  it('names a queued bill and confirms that no payment was recorded', () => {
    const feedback = queuedOrderVoidedFeedback('PS5 Station 2');

    expect(feedback.title).toBe('Queued bill voided');
    expect(feedback.message).toContain('PS5 Station 2');
    expect(feedback.message).toContain('No payment was recorded');
  });

  it('explains the durable result of cart and prepared-bill cancellation', () => {
    expect(POS_CART_CLEARED_FEEDBACK.message).toContain('saved recovery copy');
    expect(POS_PREPARED_BILL_CANCELLED_FEEDBACK.message).toContain('audit reason');
  });

  it('explains where each Finance write is reflected', () => {
    expect(FINANCE_ACTION_FEEDBACK.manualCollectionRecorded.message).toContain('reports');
    expect(FINANCE_ACTION_FEEDBACK.tipPayoutRecorded.message).toContain('Tips Payable');
    expect(FINANCE_ACTION_FEEDBACK.assetRecorded.message).toContain('depreciation');
    expect(FINANCE_ACTION_FEEDBACK.capitalVoided.message).toContain('active capital balance');
  });
});
