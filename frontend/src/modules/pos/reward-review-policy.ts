import type { CustomerDTO, OrderDTO, RewardDTO } from '@/lib/erp-api';

export interface RewardReviewIntent {
  reward: RewardDTO;
  orderId: string;
  checkoutVersion: number;
  customerId: string;
  customerPhone: string;
}

export function captureRewardReview(
  reward: RewardDTO,
  orderId: string | undefined,
  checkoutVersion: number | undefined,
  customer: CustomerDTO | null,
): RewardReviewIntent | null {
  if (!orderId || !checkoutVersion || !customer) return null;
  return {
    reward,
    orderId,
    checkoutVersion,
    customerId: customer.id,
    customerPhone: customer.phone,
  };
}

/** The confirmation must authorize exactly the bill/customer/version shown. */
export function matchesRewardReview(
  review: RewardReviewIntent,
  canonical: OrderDTO,
  currentCustomer: CustomerDTO | null,
): boolean {
  return canonical.id === review.orderId
    && canonical.checkout_version === review.checkoutVersion
    && canonical.customer_phone === review.customerPhone
    && currentCustomer?.id === review.customerId
    && currentCustomer.phone === review.customerPhone;
}
