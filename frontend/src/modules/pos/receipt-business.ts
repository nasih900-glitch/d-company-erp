import type { BranchDTO, CompanyDTO } from '@/lib/erp-api';

export interface ReceiptBusinessDetails {
  brandName: string;
  supplierName: string;
  branchName: string | null;
  address: string | null;
  gstin: string | null;
  gstRegistrationType: string;
  isComposition: boolean;
  fssaiLicenseNo: string | null;
  tradeLicenseNo: string | null;
  stateCode: string | null;
  cashierName: string | null;
  timezone: string;
  upiVpa: string | null;
}

const clean = (value: string | null | undefined): string | null => {
  const normalized = value?.trim();
  return normalized || null;
};

export function buildReceiptBusinessDetails(
  company: CompanyDTO,
  branch: BranchDTO | null,
  cashierName?: string | null,
): ReceiptBusinessDetails {
  return {
    brandName: company.name.trim(),
    supplierName: clean(company.legal_name) ?? company.name.trim(),
    branchName: clean(branch?.name),
    address: clean(branch?.address),
    gstin: clean(branch?.branch_gstin) ?? clean(company.gstin),
    gstRegistrationType: company.gst_registration_type.trim().toLowerCase(),
    isComposition: company.is_composition,
    fssaiLicenseNo: clean(branch?.fssai_license_no),
    tradeLicenseNo: clean(branch?.trade_license_no),
    stateCode: clean(branch?.state_code),
    cashierName: clean(cashierName),
    timezone: clean(branch?.timezone) ?? company.timezone,
    upiVpa: clean(company.upi_vpa),
  };
}

export function receiptDocumentTitle(
  business: ReceiptBusinessDetails,
  isPlatformDelivery: boolean,
): string {
  if (isPlatformDelivery) return 'Platform Delivery Bill';
  if (business.isComposition || business.gstRegistrationType === 'composition') {
    return 'Bill of Supply';
  }
  if (business.gstRegistrationType !== 'unregistered' && business.gstin) {
    return 'Tax Invoice';
  }
  return 'Sale Receipt';
}

export function receiptConfigurationIssue(business: ReceiptBusinessDetails): string | null {
  const registered = business.gstRegistrationType !== 'unregistered';
  if (registered && !business.gstin) {
    return 'GST setup is incomplete. Add the correct GSTIN in Settings, or mark the company as unregistered, before charging an order.';
  }
  if (
    business.gstin
    && !/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/.test(
      business.gstin.toUpperCase(),
    )
  ) {
    return 'GSTIN format is invalid. Correct it in Settings before charging an order.';
  }
  if (!business.stateCode || !/^[0-9]{2}$/.test(business.stateCode)) {
    return 'Branch GST state code is missing or invalid. Set the two-digit code in Settings before charging an order.';
  }
  if (business.gstin && !business.gstin.startsWith(business.stateCode)) {
    return 'The GSTIN state code does not match this branch. Correct the branch or GSTIN in Settings.';
  }
  if ((business.gstRegistrationType === 'composition') !== business.isComposition) {
    return 'GST composition settings disagree. Correct the registration type in Settings before charging an order.';
  }
  return null;
}

/**
 * Build a standard UPI deep link (upi://pay?...) for a dynamic, amount-locked
 * pay QR. Returns null when no merchant VPA is configured. The VPA is left
 * literal (contains only [A-Za-z0-9.\-_]@[A-Za-z]); other fields are encoded.
 */
export function buildUpiPayLink(
  business: ReceiptBusinessDetails,
  amountMinor: number,
  note?: string | null,
): string | null {
  if (!business.upiVpa) return null;
  const amount = (amountMinor / 100).toFixed(2);
  const parts = [
    `pa=${business.upiVpa}`,
    `pn=${encodeURIComponent(business.supplierName || business.brandName)}`,
    `am=${amount}`,
    'cu=INR',
  ];
  const cleanNote = note?.trim();
  if (cleanNote) parts.push(`tn=${encodeURIComponent(cleanNote)}`);
  return `upi://pay?${parts.join('&')}`;
}

export function formatPlaceOfSupply(stateCode: string | null | undefined): string {
  const code = clean(stateCode);
  if (!code) return 'Not recorded';
  return code === '32' ? '32-Kerala' : code;
}
