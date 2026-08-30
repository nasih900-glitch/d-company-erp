import type { RemoteAssistanceCommandDTO } from '@/lib/erp-api';

export const COMMAND_CONFIRMATION_TIMEOUT_MS = 9_000;
export const COMMAND_CONFIRMATION_POLL_MS = 750;

export class RemoteCommandConfirmationTimeoutError extends Error {
  constructor() {
    super('The tablet did not confirm the queued command before the confirmation window ended.');
    this.name = 'RemoteCommandConfirmationTimeoutError';
  }
}

type Wait = (delayMs: number, signal: AbortSignal) => Promise<void>;

function abortableDelay(delayMs: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'));
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', abort);
      resolve();
    }, delayMs);
    const abort = () => {
      window.clearTimeout(timer);
      reject(new DOMException('Aborted', 'AbortError'));
    };
    signal.addEventListener('abort', abort, { once: true });
  });
}

export async function waitForRemoteCommandResolution({
  initial,
  load,
  signal,
  timeoutMs = COMMAND_CONFIRMATION_TIMEOUT_MS,
  pollIntervalMs = COMMAND_CONFIRMATION_POLL_MS,
  now = Date.now,
  wait = abortableDelay,
}: {
  initial: RemoteAssistanceCommandDTO;
  load: (signal: AbortSignal) => Promise<RemoteAssistanceCommandDTO>;
  signal: AbortSignal;
  timeoutMs?: number;
  pollIntervalMs?: number;
  now?: () => number;
  wait?: Wait;
}): Promise<RemoteAssistanceCommandDTO> {
  if (initial.status !== 'pending') return initial;
  const deadline = now() + timeoutMs;
  let command = initial;

  while (command.status === 'pending') {
    const remainingMs = deadline - now();
    if (remainingMs <= 0) throw new RemoteCommandConfirmationTimeoutError();
    await wait(Math.min(pollIntervalMs, remainingMs), signal);
    command = await load(signal);
  }

  return command;
}

export function isAbortError(error: unknown): boolean {
  const candidate = error as { name?: string; code?: string } | null;
  return candidate?.name === 'AbortError'
    || candidate?.name === 'CanceledError'
    || candidate?.code === 'ERR_CANCELED';
}
