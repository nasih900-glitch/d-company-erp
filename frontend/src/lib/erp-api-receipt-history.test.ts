import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import { receipts } from './erp-api';

vi.mock('./api', () => ({ api: { get: vi.fn() } }));

describe('canonical receipt history API', () => {
  beforeEach(() => vi.clearAllMocks());

  it('keeps the search query and its opaque cursor in the same request scope', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: { items: [], next_cursor: null, has_more: false },
    });

    await receipts.list({ q: 'DC/26-27/000042', cursor: 'query-bound-cursor', limit: 50 });

    expect(api.get).toHaveBeenCalledWith('/pos/receipts', {
      params: { q: 'DC/26-27/000042', cursor: 'query-bound-cursor', limit: 50 },
    });
  });

  it('preserves the legacy no-query page contract used by receipt sync', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: { items: [], next_cursor: null, has_more: false },
    });

    await receipts.list({ cursor: 'legacy-cursor', limit: 100 });

    expect(api.get).toHaveBeenCalledWith('/pos/receipts', {
      params: { cursor: 'legacy-cursor', limit: 100 },
    });
  });
});
