import { isOperationalContextError } from '@/lib/operational-context';

export type PosInitialLoadFailureKind =
  | 'connectivity'
  | 'precondition'
  | 'server'
  | 'unknown';

type ErrorDetails = {
  code?: unknown;
  status?: unknown;
};

export function posLoadPreconditionError(message: string): Error {
  return Object.assign(new Error(message), { code: 'pos_precondition' as const });
}

export function canRenderPosWithoutShift(error: unknown): boolean {
  return isOperationalContextError(error);
}

export function classifyPosInitialLoadFailure(error: unknown): PosInitialLoadFailureKind {
  if (isOperationalContextError(error)) return 'precondition';

  const details = error as ErrorDetails | null;
  const code = details?.code;
  const status = details?.status;
  if (code === 'pos_precondition' || code === 'business_rule') return 'precondition';
  if (code === 'network_error' || status === 408 || status === 504) return 'connectivity';
  if (code === 'internal_error' || (typeof status === 'number' && status >= 500)) return 'server';
  if (typeof status === 'number' && status >= 400 && status < 500) return 'precondition';
  return 'unknown';
}

export function posInitialLoadFailureCopy(kind: PosInitialLoadFailureKind): {
  title: string;
  guidance: string;
} {
  switch (kind) {
    case 'connectivity':
      return {
        title: 'POS is offline',
        guidance: 'Check this device\'s connection, then retry. Any saved local bill remains untouched.',
      };
    case 'precondition':
      return {
        title: 'POS setup needs attention',
        guidance: 'Follow the instruction above, then retry. No bill or payment was changed.',
      };
    case 'server':
      return {
        title: 'POS is temporarily unavailable',
        guidance: 'The server responded with a problem. Wait a moment, then retry; no bill or payment was changed.',
      };
    case 'unknown':
      return {
        title: 'POS could not load',
        guidance: 'Retry once. If this continues, use Help to send a report; no bill or payment was changed.',
      };
  }
}

export function posShiftIssueCopy(kind: PosInitialLoadFailureKind | null): {
  title: string;
  showShiftLink: boolean;
} {
  if (kind === 'precondition') {
    return { title: 'A shift is required before billing', showShiftLink: true };
  }
  if (kind === 'connectivity') {
    return { title: 'Shift verification is offline', showShiftLink: false };
  }
  return { title: 'Shift status could not be verified', showShiftLink: false };
}
