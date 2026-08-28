import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { settings } from './erp-api';

vi.mock('./api', () => ({
  api: {
    post: vi.fn(),
    patch: vi.fn(),
  },
}));

describe('settings terminal API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('retains the caller idempotency key when creating the single shop', async () => {
    const response = {
      id: 'main-shop',
      name: 'Main Shop',
      code: 'MAIN',
      address: null,
      timezone: 'Asia/Kolkata',
      opens_at: null,
      closes_at: null,
      state_code: '32',
      fssai_license_no: null,
      trade_license_no: null,
      branch_gstin: null,
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });

    await expect(settings.createBranch({
      name: 'Main Shop',
      code: 'MAIN',
    }, 'shop-create:attempt-1')).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledWith(
      '/settings/branches',
      { name: 'Main Shop', code: 'MAIN' },
      { headers: { 'Idempotency-Key': 'shop-create:attempt-1' } },
    );
  });

  it('retains the caller idempotency key when creating a terminal', async () => {
    const response = {
      id: 'cafe-pos',
      branch_id: 'main-shop',
      name: 'Cafe POS',
      purpose: 'cafe_pos',
      device_id: null,
      last_seen_at: null,
    };
    vi.mocked(api.post).mockResolvedValue({ data: response });

    await expect(settings.createTerminal({
      branch_id: 'main-shop',
      name: 'Cafe POS',
      purpose: 'cafe_pos',
    }, 'terminal-create:attempt-1')).resolves.toEqual(response);
    expect(api.post).toHaveBeenCalledWith(
      '/settings/terminals',
      { branch_id: 'main-shop', name: 'Cafe POS', purpose: 'cafe_pos' },
      { headers: { 'Idempotency-Key': 'terminal-create:attempt-1' } },
    );
  });

  it('renames a terminal in place and can explicitly clear its device binding', async () => {
    const response = {
      id: 'terminal-1',
      branch_id: 'main-shop',
      name: 'Cafe POS',
      purpose: 'cafe_pos',
      device_id: null,
      last_seen_at: null,
    };
    vi.mocked(api.patch).mockResolvedValue({ data: response });

    await expect(settings.updateTerminal('terminal-1', {
      name: 'Cafe POS',
      purpose: 'cafe_pos',
      device_id: null,
    })).resolves.toEqual(response);
    expect(api.patch).toHaveBeenCalledWith(
      '/settings/terminals/terminal-1',
      { name: 'Cafe POS', purpose: 'cafe_pos', device_id: null },
    );
  });
});
