import { beforeEach, describe, expect, it, vi } from 'vitest';

const session = vi.hoisted(() => ({ token: '' }));

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
  },
  readAccessToken: () => session.token,
}));

import { api } from './api';
import { customers, pos } from './erp-api';

function tokenFor(companyId: string): string {
  return `header.${btoa(JSON.stringify({ company_id: companyId }))}.signature`;
}

describe('customer directory revision client contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
    });
    session.token = tokenFor('company-a');
  });

  it('captures only the authenticated tenant response and sends the matching pair', async () => {
    vi.mocked(api.get)
      .mockResolvedValueOnce({
        data: [],
        headers: {
          'x-customer-directory-revision': '7',
          'x-customer-directory-company-id': 'company-b',
        },
      })
      .mockResolvedValueOnce({
        data: [],
        headers: {
          'x-customer-directory-revision': '3',
          'x-customer-directory-company-id': 'company-a',
        },
      });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'customer-a' } });

    await customers.list();
    await customers.list();
    await customers.upsert({ phone: '9876543210', name: 'Amina' });

    expect(api.post).toHaveBeenCalledWith('/customers', {
      phone: '9876543210',
      name: 'Amina',
      customer_directory_revision: 3,
      customer_directory_company_id: 'company-a',
    });
  });

  it('uses caller-retained evidence for an idempotent checkout replay', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'order-1' } });
    const request = {
      type: 'takeaway' as const,
      shift_id: 'shift-1',
      lines: [{ menu_item_id: 'item-1', qty: 1 }],
      customer_phone: '9876543210',
    };

    await pos.createOrder(request, 'order:one', {
      customer_directory_revision: 2,
      customer_directory_company_id: 'company-a',
    });
    await pos.createOrder(request, 'order:one', {
      customer_directory_revision: 2,
      customer_directory_company_id: 'company-a',
    });

    for (const call of vi.mocked(api.post).mock.calls) {
      expect(call[1]).toMatchObject({
        customer_directory_revision: 2,
        customer_directory_company_id: 'company-a',
      });
    }
  });

  it('sends explicit legacy missing evidence without adopting a later revision', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: [],
      headers: {
        'x-customer-directory-revision': '9',
        'x-customer-directory-company-id': 'company-a',
      },
    });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'order-1' } });
    await customers.list();
    await pos.createOrder({
      type: 'takeaway',
      shift_id: 'shift-1',
      lines: [{ menu_item_id: 'item-1', qty: 1 }],
      customer_phone: '9876543210',
    }, 'legacy-order', null);

    expect(api.post).toHaveBeenCalledWith(
      '/pos/orders',
      {
        type: 'takeaway',
        shift_id: 'shift-1',
        lines: [{ menu_item_id: 'item-1', qty: 1 }],
        customer_phone: '9876543210',
      },
      { headers: { 'Idempotency-Key': 'legacy-order' } },
    );
  });
});
