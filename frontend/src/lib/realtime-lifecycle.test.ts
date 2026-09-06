import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./api', () => ({ BASE_URL: '/api/v1', readAccessToken: () => 'test-token' }));

class TestSocket {
  static OPEN = 1;
  static CONNECTING = 0;
  static instances: TestSocket[] = [];
  readyState = TestSocket.CONNECTING;
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  send = vi.fn();
  close = vi.fn(() => { this.readyState = 3; });
  constructor() { TestSocket.instances.push(this); }
  open() { this.readyState = TestSocket.OPEN; this.onopen?.(); }
  closed() { this.readyState = 3; this.onclose?.(); }
  changed(resource: string) {
    this.onmessage?.({ data: JSON.stringify({ type: 'changed', resource }) });
  }
  authenticated() { this.onmessage?.({ data: JSON.stringify({ type: 'connected' }) }); }
}

describe('realtime connection ownership', () => {
  let realtime: typeof import('./realtime');

  beforeEach(async () => {
    vi.resetModules();
    vi.useFakeTimers();
    TestSocket.instances = [];
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

  it('keeps the new login connection when the prior logout finishes closing late', () => {
    realtime.connectRealtime();
    const previous = TestSocket.instances[0];
    previous.open();
    realtime.disconnectRealtime();
    realtime.connectRealtime();
    const current = TestSocket.instances[1];
    current.open();

    previous.closed();
    realtime.connectRealtime();
    vi.advanceTimersByTime(1000);

    expect(TestSocket.instances).toHaveLength(2);
    expect(current.close).not.toHaveBeenCalled();
  });

  it('ignores late events from a retired connection after a new login', () => {
    const refresh = vi.fn();
    realtime.subscribeRealtime('gaming', refresh);
    realtime.connectRealtime();
    const previous = TestSocket.instances[0];
    previous.open();
    realtime.disconnectRealtime();
    realtime.connectRealtime();
    const current = TestSocket.instances[1];

    previous.open();
    previous.changed('gaming');

    expect(current.send).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    current.open();
    current.changed('gaming');
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('keeps a watchdog replacement alive when the dead socket closes late', () => {
    const refresh = vi.fn();
    realtime.subscribeRealtime('gaming', refresh);
    realtime.connectRealtime();
    const previous = TestSocket.instances[0];
    previous.open();
    vi.advanceTimersByTime(61_000);
    expect(previous.close).toHaveBeenCalledOnce();
    const current = TestSocket.instances[1];
    current.open();
    current.authenticated();
    expect(refresh).toHaveBeenCalledOnce();

    previous.closed();
    vi.advanceTimersByTime(1000);
    realtime.connectRealtime();

    expect(TestSocket.instances).toHaveLength(2);
    current.changed('gaming');
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('refreshes after server authentication so changes during the handshake are not lost', () => {
    const refresh = vi.fn();
    realtime.subscribeRealtime('gaming', refresh);
    realtime.connectRealtime();
    const current = TestSocket.instances[0];
    current.open();
    expect(refresh).not.toHaveBeenCalled();

    current.authenticated();
    expect(refresh).toHaveBeenCalledOnce();
  });
});
