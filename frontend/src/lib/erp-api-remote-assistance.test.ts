import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import {
  remoteAssistance,
  type RemoteAssistanceEndReason,
} from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const INSTALLATION_ID = '5b2d6639-5da5-4b2f-89fa-61a6b5a8b700';
const GRANT_ID = '0878831b-ef18-49f1-9c16-f2f8cbdfac04';
const SESSION_ID = '267d32f3-e98c-4d83-a402-7a132a181dd1';
const KEY_ID = '712fa29d-c5da-4be0-9804-ec659d5038bd';
const MUTATION_ID = '625ed2bc-fdb4-42d4-9412-017a3917147d';

describe('remote-assistance API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('matches the complete session end-reason contract', () => {
    const employeeDeclined: RemoteAssistanceEndReason = 'grant_declined';
    expect(employeeDeclined).toBe('grant_declined');
  });

  it('loads protected device and session state with cancellation and filters', async () => {
    const controller = new AbortController();
    vi.mocked(api.get)
      .mockResolvedValueOnce({ data: { items: [] } })
      .mockResolvedValueOnce({ data: { items: [] } });

    await remoteAssistance.listDevices(controller.signal);
    await remoteAssistance.listSessions({
      installation_id: INSTALLATION_ID,
      status: 'active',
      limit: 50,
      offset: 0,
    }, controller.signal);

    expect(api.get).toHaveBeenNthCalledWith(1, '/remote-assistance/devices', {
      signal: controller.signal,
    });
    expect(api.get).toHaveBeenNthCalledWith(2, '/remote-assistance/sessions', {
      params: {
        installation_id: INSTALLATION_ID,
        status: 'active',
        limit: 50,
        offset: 0,
      },
      signal: controller.signal,
    });
  });

  it('retains caller-supplied UUIDv4 mutation identities in every state change', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: {} });

    await remoteAssistance.requestGrant({
      request_id: MUTATION_ID,
      installation_id: INSTALLATION_ID,
      grant_kind: 'one_time',
      grant_ttl_seconds: 600,
      session_ttl_seconds: 900,
    });
    await remoteAssistance.createSession({
      session_id: MUTATION_ID,
      installation_id: INSTALLATION_ID,
      grant_id: GRANT_ID,
      session_ttl_seconds: 900,
    });
    await remoteAssistance.startSession(SESSION_ID, MUTATION_ID);
    await remoteAssistance.endSession(SESSION_ID, MUTATION_ID);
    await remoteAssistance.revokeGrant(GRANT_ID, MUTATION_ID);

    expect(api.post).toHaveBeenNthCalledWith(1, '/remote-assistance/requests', {
      request_id: MUTATION_ID,
      installation_id: INSTALLATION_ID,
      grant_kind: 'one_time',
      grant_ttl_seconds: 600,
      session_ttl_seconds: 900,
    });
    expect(api.post).toHaveBeenNthCalledWith(2, '/remote-assistance/sessions', {
      session_id: MUTATION_ID,
      installation_id: INSTALLATION_ID,
      grant_id: GRANT_ID,
      session_ttl_seconds: 900,
    });
    expect(api.post).toHaveBeenNthCalledWith(
      3,
      `/remote-assistance/sessions/${SESSION_ID}/start`,
      { start_id: MUTATION_ID },
    );
    expect(api.post).toHaveBeenNthCalledWith(
      4,
      `/remote-assistance/sessions/${SESSION_ID}/end`,
      { end_id: MUTATION_ID },
    );
    expect(api.post).toHaveBeenNthCalledWith(
      5,
      `/remote-assistance/grants/${GRANT_ID}/revoke`,
      { revoke_id: MUTATION_ID },
    );
  });

  it('uses the protected idempotent device-key approval and revocation contract', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: {} });

    await remoteAssistance.approveDeviceKey(KEY_ID, {
      approval_id: MUTATION_ID,
      pairing_code: 'AB3DEF5G7H9J',
    });
    await remoteAssistance.revokeDeviceKey(KEY_ID, MUTATION_ID);

    expect(api.post).toHaveBeenNthCalledWith(
      1,
      `/remote-assistance/device-keys/${KEY_ID}/approve`,
      { approval_id: MUTATION_ID, pairing_code: 'AB3DEF5G7H9J' },
    );
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      `/remote-assistance/device-keys/${KEY_ID}/revoke`,
      { revocation_id: MUTATION_ID },
    );
  });

  it('sends only a validated allowlisted command and parses redacted-frame metadata', async () => {
    const frame = new Blob(['jpeg'], { type: 'image/jpeg' });
    vi.mocked(api.post).mockResolvedValue({ data: { status: 'pending' } });
    vi.mocked(api.get).mockResolvedValue({
      data: frame,
      headers: {
        'x-frame-id': 'frame-1',
        'x-frame-sequence': '7',
        'x-frame-width': '1280',
        'x-frame-height': '800',
        'x-frame-received-at': '2026-08-30T12:00:00Z',
      },
    });

    await remoteAssistance.sendCommand(
      SESSION_ID,
      { type: 'navigate', module: 'pos' },
      4,
      MUTATION_ID,
    );
    await remoteAssistance.getCommand(SESSION_ID, MUTATION_ID);
    await expect(remoteAssistance.frame(SESSION_ID)).resolves.toMatchObject({
      blob: frame,
      frame_id: 'frame-1',
      sequence: 7,
      width: 1280,
      height: 800,
      received_at: '2026-08-30T12:00:00Z',
    });

    expect(api.post).toHaveBeenCalledWith(
      `/remote-assistance/sessions/${SESSION_ID}/commands`,
      {
        command_id: MUTATION_ID,
        sequence: 4,
        type: 'navigate',
        module: 'pos',
      },
      { signal: undefined },
    );
    expect(api.get).toHaveBeenNthCalledWith(
      1,
      `/remote-assistance/sessions/${SESSION_ID}/commands/${MUTATION_ID}`,
      { signal: undefined },
    );
    expect(api.get).toHaveBeenNthCalledWith(
      2,
      `/remote-assistance/sessions/${SESSION_ID}/frame`,
      { responseType: 'blob', signal: undefined },
    );
  });
});
