import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { GamingStopConfirmation } from './GamingStopConfirmation';

describe('GamingStopConfirmation', () => {
  it('explains the fixed charge and payment-due impact before ending a session', () => {
    const markup = renderToStaticMarkup(
      <GamingStopConfirmation
        stationName="PS5 Station 1"
        elapsedMinutes={42}
        estimatedAmountMinor={12_000}
        fixedPrice
        busy={false}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    expect(markup).toContain('End PS5 Station 1?');
    expect(markup).toContain('fixed session charge is ₹120.00');
    expect(markup).toContain('approximately 42 min');
    expect(markup).toContain('moves this session to Payment Due');
    expect(markup).toContain('End session');
  });

  it('disables both decisions while the stop request is in progress', () => {
    const markup = renderToStaticMarkup(
      <GamingStopConfirmation
        stationName="VR Pod 1"
        elapsedMinutes={1}
        estimatedAmountMinor={null}
        fixedPrice={false}
        busy
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    expect(markup).toContain('amount is not available');
    expect(markup.match(/disabled=""/g)).toHaveLength(2);
  });
});
