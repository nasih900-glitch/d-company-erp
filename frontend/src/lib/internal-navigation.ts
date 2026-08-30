/**
 * Closed validation for destinations rendered by React Router.
 *
 * React Router v6 has no patched release for its backslash open-redirect
 * advisory. D Company therefore admits only ordinary in-app path segments at
 * every data-driven navigation boundary. Absolute URLs, scheme-relative URLs,
 * backslashes, encoded separators, dot segments, query strings, fragments,
 * whitespace, and control characters all fail closed to a known local route.
 */
const INTERNAL_APP_ROUTE = /^(?:\/$|\/[A-Za-z0-9][A-Za-z0-9/_-]*)$/;

export function isInternalAppRoute(value: unknown): value is string {
  return typeof value === 'string'
    && value === value.trim()
    && INTERNAL_APP_ROUTE.test(value)
    && !value.includes('//');
}

export function internalAppRouteOr(
  value: unknown,
  fallback: string = '/gaming',
): string {
  if (!isInternalAppRoute(fallback)) {
    throw new Error('Internal navigation fallback must be a safe local route.');
  }
  return isInternalAppRoute(value) ? value : fallback;
}
