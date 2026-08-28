import { AxiosError, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';

import { api } from './api';
import {
  clearLastFailedSupportActionForTests,
  normalizeSupportRoute,
  readLastFailedSupportAction,
  recordFailedSupportAction,
} from './support-context';

describe('privacy-safe failed action context', () => {
  beforeEach(clearLastFailedSupportActionForTests);

  it('drops query strings and normalizes identifiers without retaining request data', () => {
    expect(normalizeSupportRoute(
      '/api/v1/pos/orders/0bb197be-8c9f-4d14-8726-098841af0276/payments?phone=secret',
    )).toBe('/api/v1/pos/orders/:id/payments');

    recordFailedSupportAction({
      method: 'post',
      url: '/api/v1/pos/orders/0bb197be-8c9f-4d14-8726-098841af0276/payments?phone=secret',
      errorCode: 'payment declined: token=secret',
      status: 422,
    });
    expect(readLastFailedSupportAction()).toMatchObject({
      lastAction: 'POST /api/v1/pos/orders/:id/payments',
      errorCode: 'payment_declined__token_secret',
    });
    expect(JSON.stringify(readLastFailedSupportAction())).not.toContain('phone');
  });

  it('uses a non-sensitive fallback for connection failures', () => {
    recordFailedSupportAction({ method: 'get', url: '/api/v1/menu' });
    expect(readLastFailedSupportAction()?.errorCode).toBe('NETWORK_ERROR');
  });

  it('is populated by the shared API interceptor without retaining request details', async () => {
    await expect(api.request({
      method: 'post',
      url: '/pos/orders/0bb197be-8c9f-4d14-8726-098841af0276/payments?phone=secret',
      data: { card_number: '4111111111111111' },
      headers: { Authorization: 'Bearer secret-token' },
      adapter: async (config) => {
        const response: AxiosResponse = {
          config,
          data: { error: { code: 'payment_declined', message: 'Payment was declined.' } },
          headers: {},
          status: 422,
          statusText: 'Unprocessable Entity',
        };
        throw new AxiosError(
          'Request failed with status code 422',
          'ERR_BAD_REQUEST',
          config,
          undefined,
          response,
        );
      },
    })).rejects.toThrow('Payment was declined.');

    const stored = JSON.stringify(readLastFailedSupportAction());
    expect(readLastFailedSupportAction()).toMatchObject({
      lastAction: 'POST /pos/orders/:id/payments',
      errorCode: 'payment_declined',
    });
    expect(stored).not.toContain('phone');
    expect(stored).not.toContain('4111111111111111');
    expect(stored).not.toContain('secret-token');
  });
});
