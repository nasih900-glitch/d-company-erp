import { describe, expect, it } from 'vitest';

import { minorToRupeesInput, parseRupeesToMinor } from './money-input';

describe('exact rupee input', () => {
  it('parses whole rupees and up to two paise digits exactly', () => {
    expect(parseRupeesToMinor('210')).toBe(21_000);
    expect(parseRupeesToMinor(' 1620.5 ')).toBe(162_050);
    expect(parseRupeesToMinor('0.01')).toBe(1);
    expect(parseRupeesToMinor('25.')).toBe(2_500);
  });

  it('rejects ambiguous or inexact money strings', () => {
    for (const value of ['', '-1', '1.001', '1e3', '1,000', '.', 'NaN']) {
      expect(parseRupeesToMinor(value)).toBeNull();
    }
  });

  it('rejects values outside the exact integer range', () => {
    expect(parseRupeesToMinor('90071992547410')).toBeNull();
  });

  it('formats paise for editing without floating point', () => {
    expect(minorToRupeesInput(3_050)).toBe('30.50');
    expect(minorToRupeesInput(9_999_999_999)).toBe('99999999.99');
    expect(minorToRupeesInput(-5)).toBe('-0.05');
    expect(minorToRupeesInput(Number.MAX_SAFE_INTEGER + 1)).toBe('');
  });
});
