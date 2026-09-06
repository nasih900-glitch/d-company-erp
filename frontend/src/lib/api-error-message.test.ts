import { describe, expect, it } from 'vitest';
import { apiFailureMessage } from './api-error-message';

describe('staff-facing API failures', () => {
  it('preserves useful business errors including shift opener and next action', () => {
    const message = 'Opened by Rafi at 18:00. Stop the active session before closing this shift.';
    expect(apiFailureMessage(message, 409)).toBe(message);
  });
  it('does not present raw HTTP failures when the proxy cannot reach the API', () => {
    expect(apiFailureMessage(undefined, 502)).toContain('temporarily unable');
    expect(apiFailureMessage(undefined, 502)).toContain('confirm the latest status');
    expect(apiFailureMessage(undefined, 502)).not.toContain('502');
  });
  it('explains network failure and ambiguous timeout without claiming nothing was saved', () => {
    expect(apiFailureMessage(undefined)).toContain('Check the connection');
    expect(apiFailureMessage(undefined, 504)).toContain('whether the action completed');
  });
  it('offers an access-recovery step for a missing permission', () => {
    expect(apiFailureMessage('', 403)).toContain('Ask an owner to check your access');
  });
});
