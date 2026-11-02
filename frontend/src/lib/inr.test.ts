import { describe, expect, it } from 'vitest';
import { inr, inrShort, inrInWords, currencyFreeLabel } from './inr';

describe('symbol-free amount presentation', () => {
  it('preserves signs, fractions and Indian grouping', () => {
    expect(inr(0)).toBe('0.00');
    expect(inr(1)).toBe('0.01');
    expect(inr(-1)).toBe('-0.01');
    expect(inr(1284500)).toBe('12,845.00');
    expect(inr(-1284509)).toBe('-12,845.09');
    expect(inr(1284500, { withSymbol: true })).toBe('12,845.00');
    expect(inr(1284500, { withSymbol: false, decimals: 0 })).toBe('12,845');
  });

  it('preserves compact units and currency identity in amount-in-words', () => {
    expect(inrShort(1284500)).toBe('12.8K');
    expect(inrShort(12845000)).toBe('1.3L');
    expect(inrShort(1284500000)).toBe('1.3Cr');
    expect(inrInWords(1250)).toBe('Twelve Rupees and Fifty Paise Only.');
  });

  it('adapts legacy reward labels without mutating source text', () => {
    const stored = { label: "Legend's \u20b91,000 Credit" };
    expect(currencyFreeLabel(stored.label)).toBe("Legend's 1,000 Credit");
    expect(stored.label).toBe("Legend's \u20b91,000 Credit");
  });
});
