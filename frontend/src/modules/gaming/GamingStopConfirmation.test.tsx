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
    expect(markup).toContain('fixed package charge is ₹120.00');
    expect(markup).toContain('approximately 42 min');
    expect(markup).toContain('moves this session to Payment Due');
    expect(markup).toContain('End session');
  });

  it('separates estimated friend fees from the fixed package before final server settlement', () => {
    const markup = renderToStaticMarkup(
      <GamingStopConfirmation stationName="PS5 Station 1" elapsedMinutes={29}
        estimatedAmountMinor={8_000} estimatedFriendMinor={3_000} fixedPrice
        busy={false} onConfirm={vi.fn()} onCancel={vi.fn()}/>);
    expect(markup).toContain('fixed package charge is ₹80.00');
    expect(markup).toContain('estimated ₹30.00 controller charge');
    expect(markup).toContain('server confirms their actual presence and final total');
  });

  it('warns when friend attendance cannot be read before final settlement', () => {
    const markup = renderToStaticMarkup(
      <GamingStopConfirmation stationName="PS5" elapsedMinutes={20}
        estimatedAmountMinor={8_000} estimatedFriendMinor={null} friendChargesUnverified
        fixedPrice busy={false} onConfirm={vi.fn()} onCancel={vi.fn()}/>);
    expect(markup).toContain('Friend attendance could not be loaded');
    expect(markup).toContain('may increase the final server total');
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

  it('labels missing elapsed evidence without showing NaN or inventing zero minutes', () => {
    const markup = renderToStaticMarkup(<GamingStopConfirmation stationName="PS5" elapsedMinutes={Number.NaN} estimatedAmountMinor={null} fixedPrice={false} busy={false} onConfirm={vi.fn()} onCancel={vi.fn()}/>);
    expect(markup).toContain('Elapsed time could not be verified');
    expect(markup).not.toContain('NaN');
    expect(markup).toContain('server confirms the final time and amount');
  });
});
