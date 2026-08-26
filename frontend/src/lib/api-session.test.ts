import { describe, expect, it } from 'vitest';

import { isSameOriginHttpApi } from './api';

describe('web session transport boundary', () => {
  it('uses cookie mode for relative and absolute same-origin API URLs', () => {
    expect(isSameOriginHttpApi('/api/v1', 'https://dcompany.duckdns.org/pos')).toBe(true);
    expect(isSameOriginHttpApi(
      'https://dcompany.duckdns.org/api/v1',
      'https://dcompany.duckdns.org/settings',
    )).toBe(true);
  });

  it('keeps native and cross-origin clients on the JSON token contract', () => {
    expect(isSameOriginHttpApi(
      'https://dcompany.duckdns.org/api/v1',
      'capacitor://localhost/pos',
    )).toBe(false);
    expect(isSameOriginHttpApi(
      'https://api.dcompany.example/api/v1',
      'https://erp.dcompany.example/pos',
    )).toBe(false);
  });

  it('fails closed for malformed locations', () => {
    expect(isSameOriginHttpApi('/api/v1', 'not a URL')).toBe(false);
  });
});
