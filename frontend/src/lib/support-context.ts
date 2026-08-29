/** Privacy-safe, in-memory context for the most recent failed API action. */

export interface FailedSupportAction {
  lastAction: string;
  errorCode: string;
  capturedAt: string;
}

let lastFailure: FailedSupportAction | null = null;

const UUID_SEGMENT = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const INTEGER_SEGMENT = /^\d+$/;
const OPAQUE_SEGMENT = /^[0-9a-f]{24,}$/i;

export function normalizeSupportRoute(rawUrl: string): string {
  let pathname: string;
  try {
    pathname = new URL(rawUrl, 'https://support-context.invalid').pathname;
  } catch {
    pathname = rawUrl.split(/[?#]/, 1)[0] || '/unknown';
  }
  const normalized = pathname
    .split('/')
    .map((segment) => (
      UUID_SEGMENT.test(segment) || INTEGER_SEGMENT.test(segment) || OPAQUE_SEGMENT.test(segment)
        ? ':id'
        : segment
    ))
    .join('/');
  return normalized.slice(0, 90) || '/unknown';
}

function safeErrorCode(code: string | null | undefined, status?: number): string {
  const normalized = code?.trim().replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 100);
  if (normalized) return normalized;
  return status ? `HTTP_${status}` : 'NETWORK_ERROR';
}

export function recordFailedSupportAction(input: {
  method?: string;
  url?: string;
  errorCode?: string | null;
  status?: number;
}): void {
  const method = (input.method || 'REQUEST').toUpperCase().replace(/[^A-Z]/g, '').slice(0, 10)
    || 'REQUEST';
  const route = normalizeSupportRoute(input.url || '/unknown');
  lastFailure = {
    lastAction: `${method} ${route}`.slice(0, 120),
    errorCode: safeErrorCode(input.errorCode, input.status),
    capturedAt: new Date().toISOString(),
  };
}

export function readLastFailedSupportAction(): FailedSupportAction | null {
  return lastFailure ? { ...lastFailure } : null;
}

export function clearLastFailedSupportActionForTests(): void {
  lastFailure = null;
}
