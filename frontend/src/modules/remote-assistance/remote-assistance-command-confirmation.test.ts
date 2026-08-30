import { describe, expect, it, vi } from 'vitest';

import type { RemoteAssistanceCommandDTO } from '@/lib/erp-api';
import {
  RemoteCommandConfirmationTimeoutError,
  isAbortError,
  waitForRemoteCommandResolution,
} from './remote-assistance-command-confirmation';
import { commandRejectionMessage } from './remote-assistance-state';

function command(
  status: RemoteAssistanceCommandDTO['status'],
): RemoteAssistanceCommandDTO {
  return {
    command_id: 'c9b4d1a8-5887-4ed5-9912-b9d90a47c1b4',
    session_id: '267d32f3-e98c-4d83-a402-7a132a181dd1',
    sequence: 4,
    type: 'refresh',
    module: null,
    status,
    issued_by_user_id: 'd33a2d44-8d08-45a4-a1c6-4217b1eae9de',
    issued_at: '2026-08-30T12:00:00Z',
    resolved_by_user_id: status === 'pending' ? null : 'ae5c8060-b147-4837-92d8-61d174e34a53',
    resolved_at: status === 'pending' ? null : '2026-08-30T12:00:01Z',
    rejection_reason_code: status === 'rejected' ? 'module_unavailable' : null,
  };
}

const noWait = async () => undefined;

describe('remote command confirmation', () => {
  it('polls a queued command until the tablet acknowledges it', async () => {
    const load = vi.fn()
      .mockResolvedValueOnce(command('pending'))
      .mockResolvedValueOnce(command('acknowledged'));

    await expect(waitForRemoteCommandResolution({
      initial: command('pending'),
      load,
      signal: new AbortController().signal,
      wait: noWait,
    })).resolves.toMatchObject({ status: 'acknowledged' });
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('returns the tablet rejection for precise handling', async () => {
    const rejected = await waitForRemoteCommandResolution({
      initial: command('pending'),
      load: vi.fn().mockResolvedValue(command('rejected')),
      signal: new AbortController().signal,
      wait: noWait,
    });
    expect(rejected).toMatchObject({
      status: 'rejected',
      rejection_reason_code: 'module_unavailable',
    });
    expect(commandRejectionMessage(rejected.rejection_reason_code))
      .toBe('That ERP module is not available on the tablet.');
  });

  it('times out without claiming the queued command succeeded', async () => {
    let now = 0;
    await expect(waitForRemoteCommandResolution({
      initial: command('pending'),
      load: vi.fn().mockResolvedValue(command('pending')),
      signal: new AbortController().signal,
      timeoutMs: 10,
      pollIntervalMs: 5,
      now: () => now,
      wait: async (delayMs) => { now += delayMs; },
    })).rejects.toBeInstanceOf(RemoteCommandConfirmationTimeoutError);
  });

  it('surfaces a polling network failure without converting it to success', async () => {
    const networkFailure = Object.assign(new Error('offline'), { code: 'network_error' });
    await expect(waitForRemoteCommandResolution({
      initial: command('pending'),
      load: vi.fn().mockRejectedValue(networkFailure),
      signal: new AbortController().signal,
      wait: noWait,
    })).rejects.toBe(networkFailure);
  });

  it('stops polling when the route or selected session aborts', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(waitForRemoteCommandResolution({
      initial: command('pending'),
      load: vi.fn(),
      signal: controller.signal,
    })).rejects.toMatchObject({ name: 'AbortError' });
    expect(isAbortError({ name: 'CanceledError', code: 'ERR_CANCELED' })).toBe(true);
  });
});
