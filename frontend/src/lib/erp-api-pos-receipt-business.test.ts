import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { pos, type ReceiptBusinessDTO } from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
  },
}));

const receiptIdentity: ReceiptBusinessDTO = {
  brand_name: 'D Company',
  supplier_name: 'D Company Private Limited',
  branch_name: 'Nilambur',
  address: 'Nilambur, Kerala',
  gstin: '32ABCDE1234F1Z5',
  gst_registration_type: 'regular',
  is_composition: false,
  fssai_license_no: null,
  trade_license_no: null,
  state_code: '32',
  timezone: 'Asia/Kolkata',
  upi_vpa: 'merchant@ybl',
};

describe('POS receipt-business API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('loads the least-privilege POS projection rather than admin settings', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: receiptIdentity });

    await expect(pos.receiptBusiness()).resolves.toEqual(receiptIdentity);
    expect(api.get).toHaveBeenCalledWith('/pos/receipt-business');
  });
});
