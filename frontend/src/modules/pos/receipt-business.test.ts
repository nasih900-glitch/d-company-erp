import { describe, expect, it } from 'vitest';

import type { ReceiptBusinessDTO } from '@/lib/erp-api';
import {
  buildReceiptBusinessDetails,
  buildUpiPayLink,
  formatPlaceOfSupply,
  receiptConfigurationIssue,
  receiptDocumentTitle,
} from './receipt-business';

const receiptIdentity: ReceiptBusinessDTO = {
  brand_name: 'D Company',
  supplier_name: 'D Company Private Limited',
  branch_name: 'Nilambur',
  address: 'Nilambur, Malappuram, Kerala',
  gstin: '32ABCDE1234F1Z5',
  gst_registration_type: 'regular',
  is_composition: false,
  fssai_license_no: null,
  trade_license_no: null,
  state_code: '32',
  timezone: 'Asia/Kolkata',
  upi_vpa: null,
};

describe('upi pay link', () => {
  it('returns null when no VPA is configured', () => {
    const details = buildReceiptBusinessDetails(receiptIdentity, 'Nasih');
    expect(buildUpiPayLink(details, 18000)).toBeNull();
  });

  it('builds an amount-locked upi link with the merchant VPA left literal', () => {
    const details = buildReceiptBusinessDetails(
      { ...receiptIdentity, upi_vpa: 'Q530001220@ybl' },
    );
    const link = buildUpiPayLink(details, 18000, 'D Company');
    expect(link).toBe(
      'upi://pay?pa=Q530001220@ybl&pn=D%20Company%20Private%20Limited&am=180.00&cu=INR&tn=D%20Company',
    );
  });
});

describe('receipt business details', () => {
  it('uses live legal and branch fields without inventing missing licences', () => {
    const details = buildReceiptBusinessDetails(receiptIdentity, 'Nasih');

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
      { ...receiptIdentity, gstin: null, gst_registration_type: 'unregistered' },
    );

    expect(receiptDocumentTitle(details, false)).toBe('Sale Receipt');
    expect(receiptConfigurationIssue(details)).toBeNull();
  });

  it('requires registered businesses to configure a GSTIN', () => {
    const details = buildReceiptBusinessDetails({ ...receiptIdentity, gstin: null });

    expect(receiptConfigurationIssue(details)).toContain('GST setup is incomplete');
  });

  it('blocks payment when the timezone would throw while printing the receipt', () => {
    const details = buildReceiptBusinessDetails(
      { ...receiptIdentity, timezone: 'Asia/Kolkatta' },
    );

    expect(receiptConfigurationIssue(details)).toContain('Business timezone is invalid');
  });

  it('blocks payment when the branch overrides the company with a bad timezone', () => {
    const details = buildReceiptBusinessDetails({ ...receiptIdentity, timezone: 'GMT+5:30' });

    expect(receiptConfigurationIssue(details)).toContain('Business timezone is invalid');
  });

  it('labels composition documents as bills of supply', () => {
    const details = buildReceiptBusinessDetails(
      { ...receiptIdentity, gst_registration_type: 'composition', is_composition: true },
    );

    expect(receiptDocumentTitle(details, false)).toBe('Bill of Supply');
  });

  it('formats the Kerala place of supply without a demo-data dependency', () => {
    expect(formatPlaceOfSupply('32')).toBe('32-Kerala');
    expect(formatPlaceOfSupply('29')).toBe('29');
    expect(formatPlaceOfSupply(null)).toBe('Not recorded');
  });
});
