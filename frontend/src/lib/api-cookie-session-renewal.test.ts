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

describe('same-origin cookie session renewal', () => {
  beforeEach(() => {
    vi.resetModules();
    const storage = memoryStorage();
    vi.stubGlobal('localStorage', storage);
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
    expect(localStorage.getItem('refresh_token')).toBeNull();
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
    expect(post).toHaveBeenCalledTimes(2);
    expect(post.mock.calls[1]?.[2]?.signal?.aborted).toBe(false);
    oldRefresh.resolve({
      config: post.mock.calls[0]?.[2] ?? {},
      data: { access_token: 'stale-account-a-access', refresh_token: '' },
      headers: {},
      status: 200,
      statusText: 'OK',
    } as AxiosResponse);

    await expect(oldRenewal).rejects.toMatchObject({ code: 'session_changed' });
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
