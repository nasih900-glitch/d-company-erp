import { describe, expect, it } from 'vitest';

import { OperationalContextError } from '@/lib/operational-context';
import {
  canRenderPosWithoutShift,
  classifyPosInitialLoadFailure,
  posInitialLoadFailureCopy,
  posLoadPreconditionError,
  posShiftIssueCopy,
} from './pos-load-state';

describe('POS initial load state', () => {
  it('keeps a valid no-shift response out of the backend-unreachable state', () => {
    const error = new OperationalContextError(
      'no_open_shift',
      'No shift is open. Open a shift from the Shift tab before taking orders.',
    );

    expect(canRenderPosWithoutShift(error)).toBe(true);
    expect(classifyPosInitialLoadFailure(error)).toBe('precondition');
    expect(posInitialLoadFailureCopy('precondition').title).toBe('POS setup needs attention');
    expect(posInitialLoadFailureCopy('precondition').title).not.toContain('backend');
  });

  it('distinguishes connectivity, server and local setup failures', () => {
    expect(classifyPosInitialLoadFailure(
      Object.assign(new Error('Network request failed'), { code: 'network_error' }),
    )).toBe('connectivity');
    expect(classifyPosInitialLoadFailure(
      Object.assign(new Error('Internal failure'), { code: 'internal_error', status: 500 }),
    )).toBe('server');
    expect(classifyPosInitialLoadFailure(
      posLoadPreconditionError('This device has no Combined register.'),
    )).toBe('precondition');
  });

  it('provides an actionable employee-safe connectivity message', () => {
    const copy = posInitialLoadFailureCopy('connectivity');
    expect(copy.title).toBe('POS is offline');
    expect(copy.guidance).toContain('Check this device\'s connection');
    expect(copy.guidance).toContain('saved local bill remains untouched');
    expect(copy.guidance).not.toContain('docker compose');
  });

  it('never directs staff to open a shift when verification itself is offline', () => {
    expect(posShiftIssueCopy('precondition')).toEqual({
      title: 'A shift is required before billing',
      showShiftLink: true,
    });
    expect(posShiftIssueCopy('connectivity')).toEqual({
      title: 'Shift verification is offline',
      showShiftLink: false,
    });
  });
});
