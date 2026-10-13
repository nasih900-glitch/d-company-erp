/**
 * Parse a human rupee amount into integer paise without binary floating-point
 * arithmetic. Returns null for blanks, negatives, exponents, more than two
 * decimals, or values outside JavaScript's exact-integer range.
 */
export function parseRupeesToMinor(value: string): number | null {
  const trimmed = value.trim();
  const match = /^(\d+)(?:\.(\d{0,2}))?$/.exec(trimmed);
  if (!match) return null;

  const whole = BigInt(match[1]);
  const fraction = BigInt((match[2] ?? '').padEnd(2, '0') || '0');
  const minor = whole * 100n + fraction;
  if (minor > BigInt(Number.MAX_SAFE_INTEGER)) return null;
  return Number(minor);
}

/** Exact editable rupee text from integer paise; never routes through a float. */
export function minorToRupeesInput(minor: number): string {
  if (!Number.isSafeInteger(minor)) return '';
  const signed = BigInt(minor);
  const negative = signed < 0n;
  const absolute = negative ? -signed : signed;
  const whole = absolute / 100n;
  const fraction = (absolute % 100n).toString().padStart(2, '0');
  return `${negative ? '-' : ''}${whole}.${fraction}`;
}
