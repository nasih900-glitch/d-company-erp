import type { PosPaymentBundleDTO, PosPaymentMethodDTO } from '@/lib/erp-api';
import type { CheckoutPaymentBundleSubmission } from '@/lib/retry-drafts';

export type SplitPaymentReceiptLeg = PosPaymentBundleDTO['payments'][number];

const PAYMENT_METHODS = new Set<PosPaymentMethodDTO>([
  'cash',
  'card',
  'upi',
  'qr',
  'wallet',
]);

function normalizedReference(value: string | null | undefined): string | null {
  const normalized = value?.trim();
  return normalized || null;
}

/**
 * Accept split-receipt details only when the authoritative bundle response
 * exactly matches the durable settlement plan that was submitted. A malformed
 * or unrelated response must never be attached to the printable invoice.
 */
export function verifiedSplitPaymentReceipt(
  result: PosPaymentBundleDTO,
  submission: CheckoutPaymentBundleSubmission,
  expectedShiftId: string,
): SplitPaymentReceiptLeg[] | null {
  const expectedDueMinor = submission.body.expected_due_minor;
  if (
    result.order_id !== submission.orderId
    || result.shift_id !== expectedShiftId
    || result.order_status !== 'paid'
    || !result.invoice_no?.trim()
    || !Number.isSafeInteger(expectedDueMinor)
    || expectedDueMinor <= 0
    || !Number.isSafeInteger(result.total_amount_minor)
    || result.total_amount_minor !== expectedDueMinor
    || result.payments.length !== submission.body.payments.length
    || result.payments.length < 2
    || result.payments.length > 5
  ) {
    return null;
  }

  const expectedByMethod = new Map(
    submission.body.payments.map((payment) => [payment.method, payment]),
  );
  if (expectedByMethod.size !== submission.body.payments.length) return null;

  const seen = new Set<PosPaymentMethodDTO>();
  let totalMinor = 0;
  for (const payment of result.payments) {
    if (
      !PAYMENT_METHODS.has(payment.method)
      || seen.has(payment.method)
      || !payment.id?.trim()
      || !payment.paid_at?.trim()
      || !Number.isSafeInteger(payment.amount_minor)
      || payment.amount_minor <= 0
    ) {
      return null;
    }
    seen.add(payment.method);

    const expected = expectedByMethod.get(payment.method);
    if (
      !expected
      || payment.amount_minor !== expected.amount_minor
      || normalizedReference(payment.ref_external) !== normalizedReference(expected.ref_external)
    ) {
      return null;
    }

    if (payment.method === 'cash') {
      if (
        !Number.isSafeInteger(expected.tendered_minor)
        || (expected.tendered_minor as number) < payment.amount_minor
        || payment.tendered_minor !== expected.tendered_minor
        || payment.change_minor !== (expected.tendered_minor as number) - payment.amount_minor
      ) {
        return null;
      }
    } else if (payment.tendered_minor !== null || payment.change_minor !== null) {
      return null;
    }

    totalMinor += payment.amount_minor;
    if (!Number.isSafeInteger(totalMinor)) return null;
  }

  if (totalMinor !== expectedDueMinor) return null;
  return result.payments.map((payment) => ({ ...payment }));
}
