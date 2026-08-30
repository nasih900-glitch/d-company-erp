import { describe, expect, it } from 'vitest';

import {
  buildRemoteAssistanceCommand,
  isRemoteAssistanceModule,
  isRemoteFrameStale,
  REMOTE_ASSISTANCE_COMMANDS,
  REMOTE_ASSISTANCE_MODULES,
} from './remote-assistance-policy';

const COMMAND_ID = 'c9b4d1a8-5887-4ed5-9912-b9d90a47c1b4';

describe('remote-assistance command policy', () => {
  it('exposes only the approved ERP commands and navigation modules', () => {
    expect(REMOTE_ASSISTANCE_COMMANDS).toEqual([
      'navigate',
      'refresh',
      'sync_now',
      'collect_diagnostics',
    ]);
    expect(REMOTE_ASSISTANCE_MODULES).toEqual([
      'dashboard',
      'gaming',
      'pos',
      'shift',
      'help',
    ]);
    expect(isRemoteAssistanceModule('finance')).toBe(false);
    expect(isRemoteAssistanceModule('settings')).toBe(false);
    expect(isRemoteAssistanceModule('raw_tap')).toBe(false);
  });

  it('builds an allowlisted command without accepting arbitrary payload fields', () => {
    expect(buildRemoteAssistanceCommand(
      { type: 'navigate', module: 'gaming' },
      3,
      COMMAND_ID,
    )).toEqual({
      command_id: COMMAND_ID,
      sequence: 3,
      type: 'navigate',
      module: 'gaming',
    });

    expect(buildRemoteAssistanceCommand(
      { type: 'sync_now' },
      4,
      COMMAND_ID,
    )).toEqual({ command_id: COMMAND_ID, sequence: 4, type: 'sync_now' });
  });

  it('rejects an invalid module, sequence, command type, or id at runtime', () => {
    expect(() => buildRemoteAssistanceCommand(
      { type: 'navigate', module: 'finance' } as never,
      1,
      COMMAND_ID,
    )).toThrow(/navigation target/i);
    expect(() => buildRemoteAssistanceCommand(
      { type: 'tap', x: 10, y: 10 } as never,
      1,
      COMMAND_ID,
    )).toThrow(/not permitted/i);
    expect(() => buildRemoteAssistanceCommand(
      { type: 'end_session' } as never,
      1,
      COMMAND_ID,
    )).toThrow(/not permitted/i);
    expect(() => buildRemoteAssistanceCommand(
      { type: 'refresh' },
      0,
      COMMAND_ID,
    )).toThrow(/positive integer/i);
    expect(() => buildRemoteAssistanceCommand(
      { type: 'refresh' },
      1,
      'predictable-id',
    )).toThrow(/UUIDv4/i);
  });
});

describe('remote frame freshness', () => {
  it('treats missing, invalid and old frames as stale without hiding the last image', () => {
    const now = Date.parse('2026-08-30T12:00:20Z');
    expect(isRemoteFrameStale(null, now)).toBe(true);
    expect(isRemoteFrameStale('invalid', now)).toBe(true);
    expect(isRemoteFrameStale('2026-08-30T12:00:00Z', now)).toBe(true);
    expect(isRemoteFrameStale('2026-08-30T12:00:10Z', now)).toBe(false);
  });
});
