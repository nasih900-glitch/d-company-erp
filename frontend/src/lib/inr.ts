/**
 * Indian rupee formatting helpers.
 *
 * Money is stored everywhere as integer paise (1 INR = 100 paise).
 * These helpers convert to display strings using Indian numbering
 * conventions (1,00,000 instead of 100,000) and the ₹ symbol.
 */

/** Format minor units as Indian rupee string: 1284500 → "₹12,845.00" */
export function inr(minor: number, opts: { withSymbol?: boolean; decimals?: number } = {}): string {
  const { withSymbol = true, decimals = 2 } = opts;
  const value = minor / 100;
  // Indian numbering: 12,84,500.00 (commas every 2 digits after the thousand)
  const formatted = new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(value);
  return withSymbol ? `₹${formatted}` : formatted;
}

/** Short form: 12_84_500 → "₹12.8K" or "₹1.2L" for headers */
export function inrShort(minor: number): string {
  const v = minor / 100;
  if (v >= 10_000_000) return `₹${(v / 10_000_000).toFixed(1)}Cr`;
  if (v >= 100_000)   return `₹${(v / 100_000).toFixed(1)}L`;
  if (v >= 1_000)     return `₹${(v / 1_000).toFixed(1)}K`;
  return `₹${v.toFixed(0)}`;
}

/** Convert a tax-inclusive amount and rate into taxable value (rounded to nearest paise). */
export function splitTaxFromInclusive(
  inclusive_minor: number,
  rate: number,
): { taxable: number; cgst: number; sgst: number; total: number } {
  // taxable = inclusive / (1 + rate); CGST = SGST = taxable * rate / 2
  const taxable = Math.round(inclusive_minor / (1 + rate));
  const tax = inclusive_minor - taxable;
  const cgst = Math.round(tax / 2);
  const sgst = tax - cgst; // ensures cgst + sgst === tax exactly
  return { taxable, cgst, sgst, total: inclusive_minor };
}

/** Round invoice total to nearest rupee, return the round-off delta. */
export function roundToRupee(minor: number): { rounded: number; round_off: number } {
  const rounded = Math.round(minor / 100) * 100;
  return { rounded, round_off: rounded - minor };
}

/** Convert minor units to spoken English (Indian style). Used for "amount in words" line. */
export function inrInWords(minor: number): string {
  const rupees = Math.floor(minor / 100);
  const paise = minor % 100;
  const words = numberToIndianWords(rupees);
  const main = `${words} Rupee${rupees === 1 ? '' : 's'}`;
  return paise > 0
    ? `${main} and ${numberToIndianWords(paise)} Paise Only.`
    : `${main} Only.`;
}

const ONES = ['Zero','One','Two','Three','Four','Five','Six','Seven','Eight','Nine','Ten',
              'Eleven','Twelve','Thirteen','Fourteen','Fifteen','Sixteen','Seventeen','Eighteen','Nineteen'];
const TENS = ['','','Twenty','Thirty','Forty','Fifty','Sixty','Seventy','Eighty','Ninety'];

function chunk(n: number): string {
  if (n < 20) return ONES[n];
  if (n < 100) return TENS[Math.floor(n / 10)] + (n % 10 ? ' ' + ONES[n % 10] : '');
  return ONES[Math.floor(n / 100)] + ' Hundred' + (n % 100 ? ' ' + chunk(n % 100) : '');
}

function numberToIndianWords(n: number): string {
  if (n === 0) return 'Zero';
  if (n < 0) return 'Minus ' + numberToIndianWords(-n);
  const parts: string[] = [];
  const crore = Math.floor(n / 10_000_000);
  n %= 10_000_000;
  const lakh = Math.floor(n / 100_000);
  n %= 100_000;
  const thousand = Math.floor(n / 1000);
  n %= 1000;
  if (crore) parts.push(chunk(crore) + ' Crore');
  if (lakh) parts.push(chunk(lakh) + ' Lakh');
  if (thousand) parts.push(chunk(thousand) + ' Thousand');
  if (n) parts.push(chunk(n));
  return parts.join(' ');
}
