import { describe, expect, it } from 'vitest';

import type { BranchDTO, CompanyDTO } from '@/lib/erp-api';
import {
  buildReceiptBusinessDetails,
  formatPlaceOfSupply,
  receiptConfigurationIssue,
  receiptDocumentTitle,
} from './receipt-business';

const company: CompanyDTO = {
  id: 'company-1',
  name: 'D Company',
  legal_name: 'D Company Private Limited',
  currency: 'INR',
  timezone: 'Asia/Kolkata',
  country: 'IN',
  gstin: '32ABCDE1234F1Z5',
  pan: 'ABCDE1234F',
  gst_registration_type: 'regular',
  is_composition: false,
  e_invoicing_enabled: false,
  fiscal_year_start_month: 4,
  google_sheets_webhook_url: null,
};

const branch: BranchDTO = {
  id: 'branch-1',
  name: 'Nilambur',
  code: 'NB',
  address: 'Nilambur, Malappuram, Kerala',
  timezone: 'Asia/Kolkata',
  opens_at: null,
  closes_at: null,
  state_code: '32',
  fssai_license_no: null,
  trade_license_no: null,
  branch_gstin: null,
};

describe('receipt business details', () => {
  it('uses live legal and branch fields without inventing missing licences', () => {
    const details = buildReceiptBusinessDetails(company, branch, 'Nasih');

    expect(details.supplierName).toBe('D Company Private Limited');
    expect(details.address).toBe('Nilambur, Malappuram, Kerala');
    expect(details.gstin).toBe('32ABCDE1234F1Z5');
    expect(details.fssaiLicenseNo).toBeNull();
    expect(details.tradeLicenseNo).toBeNull();
    expect(details.cashierName).toBe('Nasih');
    expect(receiptDocumentTitle(details, false)).toBe('Tax Invoice');
  });

  it('never labels an unregistered sale as a tax invoice', () => {
    const details = buildReceiptBusinessDetails(
      { ...company, gstin: null, gst_registration_type: 'unregistered' },
      branch,
    );

    expect(receiptDocumentTitle(details, false)).toBe('Sale Receipt');
    expect(receiptConfigurationIssue(details)).toBeNull();
  });

  it('requires registered businesses to configure a GSTIN', () => {
    const details = buildReceiptBusinessDetails({ ...company, gstin: null }, branch);

    expect(receiptConfigurationIssue(details)).toContain('GST setup is incomplete');
  });

  it('labels composition documents as bills of supply', () => {
    const details = buildReceiptBusinessDetails(
      { ...company, gst_registration_type: 'composition', is_composition: true },
      branch,
    );

    expect(receiptDocumentTitle(details, false)).toBe('Bill of Supply');
  });

  it('formats the Kerala place of supply without a demo-data dependency', () => {
    expect(formatPlaceOfSupply('32')).toBe('32-Kerala');
    expect(formatPlaceOfSupply('29')).toBe('29');
    expect(formatPlaceOfSupply(null)).toBe('Not recorded');
  });
});
