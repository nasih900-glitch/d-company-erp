import { describe, expect, it } from 'vitest';

import { internalAppRouteOr, isInternalAppRoute } from './internal-navigation';

describe('internal navigation boundary', () => {
  it.each([
    '/',
    '/gaming',
    '/device-centre',
    '/orders/1234-5678',
  ])('accepts a normal in-app route: %s', (route) => {
    expect(isInternalAppRoute(route)).toBe(true);
    expect(internalAppRouteOr(route)).toBe(route);
  });

  it.each([
    'https://evil.example',
    '//evil.example/path',
    '/\\evil.example/path',
    '\\evil.example/path',
    '/%5cevil.example/path',
    '/%2f%2fevil.example/path',
    'javascript:alert(1)',
    '/gaming?next=https://evil.example',
    '/gaming#https://evil.example',
    '/gaming/../pos',
    ' /gaming',
    '/gaming\n',
    '',
    null,
    undefined,
  ])('rejects an external or ambiguous destination: %s', (route) => {
    expect(isInternalAppRoute(route)).toBe(false);
    expect(internalAppRouteOr(route)).toBe('/gaming');
  });

  it('rejects an unsafe fallback instead of creating a second redirect path', () => {
    expect(() => internalAppRouteOr('/gaming', '//evil.example')).toThrow(
      'Internal navigation fallback must be a safe local route.',
    );
  });
});
