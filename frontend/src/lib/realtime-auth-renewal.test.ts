import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const session = vi.hoisted(() => ({
  readAccessToken: vi.fn<() => string | null>(),
  readSessionGeneration: vi.fn<() => number>(),
  renewSessionAccessToken: vi.fn<() => Promise<string>>(),
}));

vi.mock('./api', () => ({
  BASE_URL: '/api/v1',
  readAccessToken: session.readAccessToken,
  readSessionGeneration: session.readSessionGeneration,
  renewSessionAccessToken: session.renewSessionAccessToken,
}));

class TestSocket {
  static OPEN = 1;
  static CONNECTING = 0;
  static instances: TestSocket[] = [];
  readyState = TestSocket.CONNECTING;
  onopen: (() => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  send = vi.fn();
  close = vi.fn(() => { this.readyState = 3; });
  constructor() { TestSocket.instances.push(this); }
  open() { this.readyState = TestSocket.OPEN; this.onopen?.(); }
  closed(code = 1006) { this.readyState = 3; this.onclose?.({ code }); }
  changed(resource: string) {
    this.onmessage?.({ data: JSON.stringify({ type: 'changed', resource }) });
  }
  authenticated() { this.onmessage?.({ data: JSON.stringify({ type: 'connected' }) }); }
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}

async function flushPromises(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

function renewalFailure(code: 'network_error' | 'unauthorized' | 'session_changed'): Error {
  return Object.assign(new Error(code), { code });
}

describe('realtime access expiry renewal', () => {
  let realtime: typeof import('./realtime');

  beforeEach(async () => {
    vi.resetModules();
    vi.useFakeTimers();
    TestSocket.instances = [];
    session.readAccessToken.mockReset().mockReturnValue('expired-access');
    session.readSessionGeneration.mockReset().mockReturnValue(1);
    session.renewSessionAccessToken.mockReset().mockImplementation(async () => {
      session.readAccessToken.mockReturnValue('renewed-access');
      return 'renewed-access';
    });
    vi.stubGlobal('WebSocket', TestSocket);
    vi.stubGlobal('window', Object.assign(new EventTarget(), {
      location: { protocol: 'http:', host: 'localhost:5173' },
    }));
    vi.stubGlobal('document', Object.assign(new EventTarget(), { visibilityState: 'visible' }));
    realtime = await import('./realtime');
  });

  afterEach(() => {
    realtime.disconnectRealtime();
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('renews after natural WebSocket expiry without waiting for a REST request', async () => {
    realtime.connectRealtime();
    const expired = TestSocket.instances[0];
    expired.open();
    expired.authenticated();

    expired.closed(4401);
    await flushPromises();

    expect(session.renewSessionAccessToken).toHaveBeenCalledOnce();
    expect(TestSocket.instances).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1_000);
    const renewed = TestSocket.instances[1];
    renewed.open();
    expect(renewed.send).toHaveBeenCalledWith(JSON.stringify({ token: 'renewed-access' }));
  });

  it('does not reconnect an expired account after logout and a new login during renewal', async () => {
    const pendingRenewal = deferred<string>();
    session.renewSessionAccessToken.mockReturnValue(pendingRenewal.promise);
    realtime.connectRealtime();
    const expired = TestSocket.instances[0];
    expired.open();
    expired.closed(4401);
    await flushPromises();

    realtime.disconnectRealtime();
    session.readAccessToken.mockReturnValue('account-b-access');
    session.readSessionGeneration.mockReturnValue(2);
    realtime.connectRealtime();
    const accountB = TestSocket.instances[1];
    accountB.open();
    accountB.authenticated();
    pendingRenewal.resolve('stale-account-a-access');
    await flushPromises();
    await vi.advanceTimersByTimeAsync(1_000);

    expect(TestSocket.instances).toHaveLength(2);
    expect(accountB.close).not.toHaveBeenCalled();
    expect(accountB.send).toHaveBeenCalledWith(JSON.stringify({ token: 'account-b-access' }));
  });

  it('ignores an old close callback when the account changes before AuthContext disconnects', async () => {
    realtime.connectRealtime();
    const accountA = TestSocket.instances[0];
    accountA.open();

    session.readAccessToken.mockReturnValue('account-b-access');
    session.readSessionGeneration.mockReturnValue(2);
    accountA.closed(4401);
    await flushPromises();
    await vi.advanceTimersByTimeAsync(1_000);

    expect(session.renewSessionAccessToken).not.toHaveBeenCalled();
    expect(TestSocket.instances).toHaveLength(1);
  });

  it('retires a connecting socket before it can send the replacement account token', () => {
    realtime.connectRealtime();
    const accountA = TestSocket.instances[0];

    session.readAccessToken.mockReturnValue('account-b-access');
    session.readSessionGeneration.mockReturnValue(2);
    accountA.open();

    expect(accountA.send).not.toHaveBeenCalled();
    expect(accountA.close).toHaveBeenCalledOnce();
  });

  it('replaces an old open socket when the new account connects', () => {
    realtime.connectRealtime();
    const accountA = TestSocket.instances[0];
    accountA.open();

    session.readAccessToken.mockReturnValue('account-b-access');
    session.readSessionGeneration.mockReturnValue(2);
    realtime.connectRealtime();
    const accountB = TestSocket.instances[1];
    accountB.open();
    accountA.closed(4401);

    expect(accountA.close).toHaveBeenCalledOnce();
    expect(accountB.send).toHaveBeenCalledWith(JSON.stringify({ token: 'account-b-access' }));
    expect(session.renewSessionAccessToken).not.toHaveBeenCalled();
  });

  it('does not dispatch an old account frame after the session changes', () => {
    const refresh = vi.fn();
    realtime.subscribeRealtime('gaming', refresh);
    realtime.connectRealtime();
    const accountA = TestSocket.instances[0];
    accountA.open();
    accountA.authenticated();
    refresh.mockClear();

    session.readAccessToken.mockReturnValue('account-b-access');
    session.readSessionGeneration.mockReturnValue(2);
    accountA.changed('gaming');

    expect(refresh).not.toHaveBeenCalled();
    expect(accountA.close).toHaveBeenCalledOnce();
  });

  it('keeps credentials and backs off after a temporary renewal failure', async () => {
    session.renewSessionAccessToken.mockRejectedValue(renewalFailure('network_error'));
    realtime.connectRealtime();
    const expired = TestSocket.instances[0];
    expired.open();
    expired.closed(4401);
    await flushPromises();

    expect(TestSocket.instances).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(999);
    expect(TestSocket.instances).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(TestSocket.instances).toHaveLength(2);
    expect(session.readAccessToken()).toBe('expired-access');
  });

  it.each(['unauthorized', 'session_changed'] as const)(
    'does not reconnect after a definitive or superseded renewal (%s)',
    async (code) => {
      session.renewSessionAccessToken.mockRejectedValue(renewalFailure(code));
      realtime.connectRealtime();
      const expired = TestSocket.instances[0];
      expired.open();
      expired.closed(4401);
      await flushPromises();
      await vi.advanceTimersByTimeAsync(1_000);

      expect(TestSocket.instances).toHaveLength(1);
    },
  );

  it('backs off rejected handshakes and resets only after authenticated connected', async () => {
    realtime.connectRealtime();
    const first = TestSocket.instances[0];
    first.open();
    first.closed(4401);
    await flushPromises();

    await vi.advanceTimersByTimeAsync(999);
    expect(TestSocket.instances).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    const second = TestSocket.instances[1];
    second.open();
    second.closed(4401);
    await flushPromises();

    await vi.advanceTimersByTimeAsync(1_999);
    expect(TestSocket.instances).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    const authenticated = TestSocket.instances[2];
    authenticated.open();
    authenticated.authenticated();
    authenticated.closed(1006);

    await vi.advanceTimersByTimeAsync(999);
    expect(TestSocket.instances).toHaveLength(3);
    await vi.advanceTimersByTimeAsync(1);
    expect(TestSocket.instances).toHaveLength(4);
  });
});
