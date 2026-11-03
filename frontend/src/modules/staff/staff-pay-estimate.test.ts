import { expect, it } from 'vitest';
import { payAmount } from './staff-pay-estimate';

it.each([[0, '0.00'], [8333, '83.33'], [1200000, '12,000.00'],
  [9007199254740991, '9,00,71,99,25,47,409.91'],
  [9007199254740987, '9,00,71,99,25,47,409.87']])('formats exact minor units %s', (minor, result) => {
  expect(payAmount(minor as number)).toBe(result);
});
it.each([-1, Number.MAX_SAFE_INTEGER + 1, 1.5, NaN, Infinity])('refuses unsupported money %s', value => {
  expect(() => payAmount(value)).toThrow('Invalid estimate amount.');
});
