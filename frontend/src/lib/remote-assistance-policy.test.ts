import { describe, expect, it } from 'vitest';

import {
  buildRemoteAssistanceCommand,
  isRemoteAssistanceModule,
  isRemoteFrameStale,
  REMOTE_ASSISTANCE_COMMANDS,
  REMOTE_ASSISTANCE_MODULES,
} from './remote-assistance-policy';
import {
  isCompletePairingCode,
  normalizePairingCodeInput,
} from '@/modules/remote-assistance/remote-assistance-state';

const COMMAND_ID = 'c9b4d1a8-5887-4ed5-9912-b9d90a47c1b4';

describe('remote-assistance command policy', () => {
  it('exposes only the approved ERP commands and navigation modules', () => {
    expect(REMOTE_ASSISTANCE_COMMANDS).toEqual([
      'navigate',
      'refresh',
      'collect_diagnostics',
    ]);
    expect(REMOTE_ASSISTANCE_MODULES).toEqual(['help']);
    expect(isRemoteAssistanceModule('gaming')).toBe(false);
    expect(isRemoteAssistanceModule('pos')).toBe(false);
    expect(isRemoteAssistanceModule('shift')).toBe(false);
    expect(isRemoteAssistanceModule('finance')).toBe(false);
    expect(isRemoteAssistanceModule('settings')).toBe(false);
    expect(isRemoteAssistanceModule('raw_tap')).toBe(false);
  });

  it('builds an allowlisted command without accepting arbitrary payload fields', () => {
    expect(buildRemoteAssistanceCommand(
      { type: 'navigate', module: 'help' },
      3,
      COMMAND_ID,
    )).toEqual({
      command_id: COMMAND_ID,
      sequence: 3,
      type: 'navigate',
      module: 'help',
    });

    expect(buildRemoteAssistanceCommand(
      { type: 'refresh' },
      4,
      COMMAND_ID,
    )).toEqual({ command_id: COMMAND_ID, sequence: 4, type: 'refresh' });
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

describe('remote-assistance pairing code policy', () => {
  it('canonicalizes read-out separators and lowercase without accepting lookalike symbols', () => {
    expect(normalizePairingCodeInput('ab3d-ef5g 7h9j')).toBe('AB3DEF5G7H9J');
    expect(isCompletePairingCode('AB3DEF5G7H9J')).toBe(true);
    expect(isCompletePairingCode('AB3DEI5G7H9J')).toBe(false);
    expect(isCompletePairingCode('AB3DEO5G7H9J')).toBe(false);
    expect(isCompletePairingCode('AB3DEF5G7H9')).toBe(false);
  });
});
