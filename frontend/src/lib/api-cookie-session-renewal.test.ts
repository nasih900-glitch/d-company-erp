import {
  AxiosHeaders,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => { values.delete(key); },
    setItem: (key, value) => { values.set(key, value); },
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}

const SESSION_RENEWAL_LOCK = 'dcompany-cookie-session-renewal';

type PendingLock = {
  cancelled: boolean;
  run: () => void;
};

class SerialLockManager {
  readonly requestedNames: string[] = [];
  maxActive = 0;
  private active = 0;
  private locked = false;
  private readonly queue: PendingLock[] = [];

  get activeCount(): number {
    return this.active;
  }

  request<T>(
    name: string,
    options: LockOptions,
    callback: (lock: Lock | null) => PromiseLike<T> | T,
  ): Promise<T> {
    this.requestedNames.push(name);
    return new Promise<T>((resolve, reject) => {
      const pending: PendingLock = {
        cancelled: false,
        run: () => {
          if (pending.cancelled) {
            this.runNext();
            return;
          }
          options.signal?.removeEventListener('abort', onAbort);
          this.locked = true;
          this.active += 1;
          this.maxActive = Math.max(this.maxActive, this.active);
          Promise.resolve(callback({ name, mode: 'exclusive' } as Lock))
            .then(resolve, reject)
            .finally(() => {
              this.active -= 1;
              this.locked = false;
              this.runNext();
            });
        },
      };
      const onAbort = () => {
        pending.cancelled = true;
        reject(new DOMException('The lock request was aborted.', 'AbortError'));
      };
      if (options.signal?.aborted) {
        onAbort();
        return;
      }
      options.signal?.addEventListener('abort', onAbort, { once: true });
      this.queue.push(pending);
      this.runNext();
    });
  }

  private runNext(): void {
    if (this.locked) return;
    const next = this.queue.shift();
    next?.run();
  }
}

describe('same-origin cookie session renewal', () => {
  let locks: SerialLockManager;

  beforeEach(() => {
    vi.resetModules();
    locks = new SerialLockManager();
    const storage = memoryStorage();
    vi.stubGlobal('localStorage', storage);
    vi.stubGlobal('navigator', { locks });
    vi.stubGlobal('window', {
      location: {
        href: 'https://dcompany.example/pos',
        reload: vi.fn(),
      },
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('single-flights cookie renewal without exposing the refresh credential', async () => {
    const axiosModule = await import('axios');
    const pendingRefresh = deferred<AxiosResponse>();
    const post = vi.spyOn(axiosModule.default, 'post').mockReturnValue(pendingRefresh.promise);
    const session = await import('./api');
    session.installSessionTokens('access-a');

    const websocketRenewal = session.renewSessionAccessToken();
    const httpRenewal = session.renewSessionAccessToken();
    expect(post).toHaveBeenCalledOnce();
    expect(post.mock.calls[0]?.[1]).toEqual({});
    expect(post.mock.calls[0]?.[2]).toMatchObject({ withCredentials: true });

    pendingRefresh.resolve({
      config: post.mock.calls[0]?.[2] ?? {},
      data: { access_token: 'access-b', refresh_token: '' },
      headers: {},
      status: 200,
      statusText: 'OK',
    } as AxiosResponse);

    await expect(websocketRenewal).resolves.toBe('access-b');
    await expect(httpRenewal).resolves.toBe('access-b');
    expect(post).toHaveBeenCalledOnce();
    expect(locks.requestedNames).toEqual([SESSION_RENEWAL_LOCK]);
    expect(localStorage.getItem('refresh_token')).toBeNull();
  });

  it('serializes renewal across independent browser tabs before either rotates the cookie', async () => {
    const axiosModule = await import('axios');
    const firstRefresh = deferred<AxiosResponse>();
    const secondRefresh = deferred<AxiosResponse>();
    const post = vi.spyOn(axiosModule.default, 'post')
      .mockReturnValueOnce(firstRefresh.promise)
      .mockReturnValueOnce(secondRefresh.promise);
    const firstTab = await import('./api');
    firstTab.installSessionTokens('tab-a-access');
    vi.resetModules();
    const secondTab = await import('./api');
    secondTab.installSessionTokens('tab-b-access');

    const firstRenewal = firstTab.renewSessionAccessToken();
    const secondRenewal = secondTab.renewSessionAccessToken();
    expect(post).toHaveBeenCalledOnce();

    firstRefresh.resolve({
      config: post.mock.calls[0]?.[2] ?? {},
      data: { access_token: 'tab-a-renewed', refresh_token: '' },
      headers: {},
      status: 200,
      statusText: 'OK',
    } as AxiosResponse);
    await expect(firstRenewal).resolves.toBe('tab-a-renewed');
    await vi.waitFor(() => expect(post).toHaveBeenCalledTimes(2));

    secondRefresh.resolve({
      config: post.mock.calls[1]?.[2] ?? {},
      data: { access_token: 'tab-b-renewed', refresh_token: '' },
      headers: {},
      status: 200,
      statusText: 'OK',
    } as AxiosResponse);
    await expect(secondRenewal).resolves.toBe('tab-b-renewed');
    expect(locks.requestedNames).toEqual([
      SESSION_RENEWAL_LOCK,
      SESSION_RENEWAL_LOCK,
    ]);
    expect(locks.maxActive).toBe(1);
    expect(locks.activeCount).toBe(0);
  });

  it.each(['logout', 'replacement login'] as const)(
    'cancels a queued renewal after same-page %s',
    async (transition) => {
      const releaseOtherTab = deferred<void>();
      const otherTab = locks.request(
        SESSION_RENEWAL_LOCK,
        { mode: 'exclusive' },
        () => releaseOtherTab.promise,
      );
      const axiosModule = await import('axios');
      const post = vi.spyOn(axiosModule.default, 'post');
      const session = await import('./api');
      session.installSessionTokens('account-a-access');

      const queuedRenewal = session.renewSessionAccessToken();
      expect(locks.requestedNames).toEqual([
        SESSION_RENEWAL_LOCK,
        SESSION_RENEWAL_LOCK,
      ]);
      if (transition === 'logout') {
        session.clearSessionCredentials();
      } else {
        session.installSessionTokens('account-b-access');
      }

      await expect(queuedRenewal).rejects.toMatchObject({ code: 'session_changed' });
      expect(post).not.toHaveBeenCalled();
      expect(session.readAccessToken()).toBe(
        transition === 'logout' ? null : 'account-b-access',
      );
      releaseOtherTab.resolve(undefined);
      await otherTab;
    },
  );

  it('releases the cross-tab lock after a transient failure so the next tab can renew', async () => {
    const axiosModule = await import('axios');
    const post = vi.spyOn(axiosModule.default, 'post')
      .mockRejectedValueOnce(new Error('temporary network failure'))
      .mockResolvedValueOnce({
        config: {},
        data: { access_token: 'tab-b-renewed', refresh_token: '' },
        headers: {},
        status: 200,
        statusText: 'OK',
      } as AxiosResponse);
    const firstTab = await import('./api');
    firstTab.installSessionTokens('tab-a-access');
    vi.resetModules();
    const secondTab = await import('./api');
    secondTab.installSessionTokens('tab-b-access');

    const firstRenewal = firstTab.renewSessionAccessToken();
    const secondRenewal = secondTab.renewSessionAccessToken();

    await expect(firstRenewal).rejects.toMatchObject({ code: 'network_error' });
    await expect(secondRenewal).resolves.toBe('tab-b-renewed');
    expect(post).toHaveBeenCalledTimes(2);
    expect(firstTab.readAccessToken()).toBe('tab-a-access');
    expect(firstTab.hasSessionCandidate()).toBe(true);
    expect(secondTab.readAccessToken()).toBe('tab-b-renewed');
    expect(locks.maxActive).toBe(1);
    expect(locks.activeCount).toBe(0);
  });

  it('preserves browser credentials and does not send when Web Locks are unavailable', async () => {
    vi.stubGlobal('navigator', {});
    const axiosModule = await import('axios');
    const post = vi.spyOn(axiosModule.default, 'post');
    const session = await import('./api');
    session.installSessionTokens('still-valid-access');

    await expect(session.renewSessionAccessToken()).rejects.toMatchObject({
      code: 'network_error',
    });

    expect(post).not.toHaveBeenCalled();
    expect(session.readAccessToken()).toBe('still-valid-access');
    expect(session.hasSessionCandidate()).toBe(true);
    expect(window.location.reload).not.toHaveBeenCalled();
  });

  it('aborts and ignores an old cookie renewal after logout and a new login', async () => {
    const axiosModule = await import('axios');
    const oldRefresh = deferred<AxiosResponse>();
    const currentRefresh = deferred<AxiosResponse>();
    const post = vi.spyOn(axiosModule.default, 'post')
      .mockReturnValueOnce(oldRefresh.promise)
      .mockReturnValueOnce(currentRefresh.promise);
    const session = await import('./api');
    session.installSessionTokens('access-a');
    const oldRenewal = session.renewSessionAccessToken();
    expect(post).toHaveBeenCalledOnce();

    session.clearSessionCredentials();
    session.installSessionTokens('account-b-access');
    expect(post.mock.calls[0]?.[2]?.signal?.aborted).toBe(true);
    const accountBRenewal = session.renewSessionAccessToken();
    expect(post).toHaveBeenCalledOnce();
    oldRefresh.resolve({
      config: post.mock.calls[0]?.[2] ?? {},
      data: { access_token: 'stale-account-a-access', refresh_token: '' },
      headers: {},
      status: 200,
      statusText: 'OK',
    } as AxiosResponse);

    await expect(oldRenewal).rejects.toMatchObject({ code: 'session_changed' });
    await vi.waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(post.mock.calls[1]?.[2]?.signal?.aborted).toBe(false);
    const joinedAccountBRenewal = session.renewSessionAccessToken();
    expect(post).toHaveBeenCalledTimes(2);
    currentRefresh.resolve({
      config: post.mock.calls[1]?.[2] ?? {},
      data: { access_token: 'account-b-renewed', refresh_token: '' },
      headers: {},
      status: 200,
      statusText: 'OK',
    } as AxiosResponse);
    await expect(accountBRenewal).resolves.toBe('account-b-renewed');
    await expect(joinedAccountBRenewal).resolves.toBe('account-b-renewed');
    expect(session.readAccessToken()).toBe('account-b-renewed');
  });

  it('uses the registered auth owner instead of reloading on rejected boot restore', async () => {
    const axiosModule = await import('axios');
    const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
    const rejection = new axiosModule.AxiosError(
      'revoked',
      undefined,
      config,
      undefined,
      {
        config,
        data: {},
        headers: {},
        status: 401,
        statusText: 'Unauthorized',
      } as AxiosResponse,
    );
    vi.spyOn(axiosModule.default, 'post').mockRejectedValue(rejection);
    const session = await import('./api');
    const authOwner = vi.fn();
    session.setForcedLogoutHandler(authOwner);

    await expect(session.restoreSessionFromRefresh()).rejects.toMatchObject({
      code: 'unauthorized',
      status: 401,
    });

    expect(authOwner).toHaveBeenCalledOnce();
    expect(locks.requestedNames).toEqual([SESSION_RENEWAL_LOCK]);
    expect(locks.maxActive).toBe(1);
    expect(locks.activeCount).toBe(0);
    expect(window.location.reload).not.toHaveBeenCalled();
    expect(session.hasSessionCandidate()).toBe(false);
  });

  it('does not attempt renewal or reload for an explicitly signed-out browser', async () => {
    localStorage.setItem('dcompany_cookie_session_signed_out', '1');
    const axiosModule = await import('axios');
    const post = vi.spyOn(axiosModule.default, 'post');
    const session = await import('./api');
    const authOwner = vi.fn();
    session.setForcedLogoutHandler(authOwner);

    await expect(session.restoreSessionFromRefresh()).rejects.toMatchObject({
      code: 'unauthorized',
      status: 401,
    });

    expect(post).not.toHaveBeenCalled();
    expect(authOwner).not.toHaveBeenCalled();
    expect(window.location.reload).not.toHaveBeenCalled();
  });
});
