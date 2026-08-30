export const REMOTE_ASSISTANCE_MODULES = [
  'dashboard',
  'gaming',
  'pos',
  'shift',
  'help',
] as const;

export type RemoteAssistanceModule = typeof REMOTE_ASSISTANCE_MODULES[number];

export const REMOTE_ASSISTANCE_COMMANDS = [
  'navigate',
  'refresh',
  'sync_now',
  'collect_diagnostics',
] as const;

export type RemoteAssistanceCommandType = typeof REMOTE_ASSISTANCE_COMMANDS[number];

export type SafeRemoteAssistanceCommand =
  | { type: 'navigate'; module: RemoteAssistanceModule }
  | { type: Exclude<RemoteAssistanceCommandType, 'navigate'> };

export interface RemoteAssistanceCommandRequest {
  command_id: string;
  sequence: number;
  type: RemoteAssistanceCommandType;
  module?: RemoteAssistanceModule;
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MODULE_SET = new Set<string>(REMOTE_ASSISTANCE_MODULES);
const COMMAND_SET = new Set<string>(REMOTE_ASSISTANCE_COMMANDS);

export function createRemoteMutationId(): string {
  if (typeof crypto === 'undefined' || typeof crypto.randomUUID !== 'function') {
    throw new Error('Secure request IDs are unavailable in this browser.');
  }
  return crypto.randomUUID();
}

export function isRemoteAssistanceModule(value: unknown): value is RemoteAssistanceModule {
  return typeof value === 'string' && MODULE_SET.has(value);
}

export function isRemoteAssistanceCommandType(
  value: unknown,
): value is RemoteAssistanceCommandType {
  return typeof value === 'string' && COMMAND_SET.has(value);
}

/**
 * This is the final client-side safety boundary before a command reaches the
 * API. Keeping it independent of the UI prevents a future control from
 * bypassing the same allowlist by calling the wrapper directly.
 */
export function buildRemoteAssistanceCommand(
  command: SafeRemoteAssistanceCommand,
  sequence: number,
  commandId: string,
): RemoteAssistanceCommandRequest {
  if (!Number.isSafeInteger(sequence) || sequence < 1) {
    throw new Error('Remote-assistance command sequence must be a positive integer.');
  }
  if (!UUID_V4.test(commandId)) {
    throw new Error('Remote-assistance command ID must be a UUIDv4.');
  }
  if (!isRemoteAssistanceCommandType(command.type)) {
    throw new Error('Remote-assistance command is not permitted.');
  }
  if (command.type === 'navigate') {
    if (!isRemoteAssistanceModule(command.module)) {
      throw new Error('Remote-assistance navigation target is not permitted.');
    }
    return {
      command_id: commandId,
      sequence,
      type: command.type,
      module: command.module,
    };
  }
  return { command_id: commandId, sequence, type: command.type };
}

export const REMOTE_FRAME_STALE_AFTER_MS = 15_000;

export function isRemoteFrameStale(
  capturedAt: string | null,
  now = Date.now(),
  staleAfterMs = REMOTE_FRAME_STALE_AFTER_MS,
): boolean {
  if (!capturedAt) return true;
  const captured = Date.parse(capturedAt);
  return !Number.isFinite(captured) || now - captured > staleAfterMs;
}
