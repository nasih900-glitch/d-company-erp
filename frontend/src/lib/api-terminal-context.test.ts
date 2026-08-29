import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { api, isBugReportApiRequest } from './api';

function memoryStorage(initial: Record<string, string>): Storage {
  const values = new Map(Object.entries(initial));
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => { values.delete(key); },
    setItem: (key, value) => { values.set(key, value); },
  };
}

async function terminalHeaderFor(
  url: string,
  headers?: AxiosRequestConfig['headers'],
): Promise<unknown> {
  let captured: unknown = null;
  await api.request({
    method: 'get',
    url,
    headers,
    adapter: async (config) => {
      captured = config.headers.get('X-Terminal-Id') ?? null;
      const response: AxiosResponse = {
        config,
        data: {},
        headers: {},
        status: 200,
        statusText: 'OK',
      };
      return response;
    },
  });
  return captured;
}

describe('terminal header request boundary', () => {
  beforeEach(() => {
    const storage = memoryStorage({ terminal_id: 'current-terminal' });
    vi.stubGlobal('window', {
      localStorage: storage,
    });
    vi.stubGlobal('localStorage', storage);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each([
    '/bug-reports',
    '/bug-reports/mine?limit=20',
    '/bug-reports/report-1/public-replies',
    '/api/v1/bug-reports/report-1/attachments/attachment-1',
    'https://erp.example/api/v1/bug-reports/inbox-summary',
  ])('omits the current terminal from Support request %s', async (url) => {
    expect(isBugReportApiRequest(url)).toBe(true);
    expect(await terminalHeaderFor(url)).toBeNull();
  });

  it('removes an inherited or caller-supplied terminal from Support requests', async () => {
    expect(await terminalHeaderFor('/bug-reports', {
      'X-Terminal-Id': 'stale-terminal',
    })).toBeNull();
  });

  it('continues attaching the current terminal to operational requests only', async () => {
    expect(await terminalHeaderFor('/pos/orders')).toBe('current-terminal');
    expect(await terminalHeaderFor('/bug-reports-export')).toBe('current-terminal');
    expect(await terminalHeaderFor('/diagnostics?next=/bug-reports')).toBe('current-terminal');
  });
});
