import axios, {
  AxiosError,
  AxiosHeaders,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios';
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  type MockInstance,
  vi,
} from 'vitest';

import {
  api,
  clearSessionCredentials,
  installSessionTokens,
  readAccessToken,
  renewSessionAccessToken,
  setForcedLogoutHandler,
} from './api';

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
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function refreshResponse(accessToken: string, refreshToken: string): AxiosResponse {
  return {
    config: { headers: new AxiosHeaders() } as InternalAxiosRequestConfig,
    data: { access_token: accessToken, refresh_token: refreshToken },
    headers: {},
    status: 200,
    statusText: 'OK',
  };
}

function refreshFailure(status?: number): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response: AxiosResponse | undefined = status === undefined ? undefined : {
    config,
    data: {},
    headers: {},
    status,
    statusText: 'Rejected',
  };
  return new AxiosError('refresh failed', undefined, config, undefined, response);
}

describe('shared session renewal boundary', () => {
  let postSpy: MockInstance<typeof axios.post>;
  let forcedLogout: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    const storage = memoryStorage();
    vi.stubGlobal('localStorage', storage);
    postSpy = vi.spyOn(axios, 'post');
    forcedLogout = vi.fn();
    setForcedLogoutHandler(forcedLogout);
    clearSessionCredentials();
    installSessionTokens('access-a', 'refresh-a');
  });

  afterEach(() => {
    vi.restoreAllMocks();
    setForcedLogoutHandler(null);
    clearSessionCredentials();
    vi.unstubAllGlobals();
  });

  it('single-flights one renewal across an HTTP 401 and WebSocket expiry', async () => {
    const pendingRefresh = deferred<AxiosResponse>();
    postSpy.mockReturnValue(pendingRefresh.promise);

    const httpRequest = api.get('/protected', {
      adapter: async (config) => {
        if (config.headers.get('Authorization') === 'Bearer access-b') {
          return {
            config,
            data: { ok: true },
            headers: {},
            status: 200,
            statusText: 'OK',
          };
        }
        const rejected: AxiosResponse = {
          config,
          data: {},
          headers: {},
          status: 401,
          statusText: 'Unauthorized',
        };
        throw new AxiosError('expired', undefined, config, undefined, rejected);
      },
    });

    await vi.waitFor(() => expect(postSpy).toHaveBeenCalledOnce());
    const websocketRenewal = renewSessionAccessToken();
    expect(postSpy).toHaveBeenCalledOnce();

    pendingRefresh.resolve(refreshResponse('access-b', 'refresh-b'));

    await expect(websocketRenewal).resolves.toBe('access-b');
    await expect(httpRequest).resolves.toMatchObject({ data: { ok: true } });
    expect(postSpy).toHaveBeenCalledOnce();
    expect(readAccessToken()).toBe('access-b');
    expect(localStorage.getItem('refresh_token')).toBe('refresh-b');
  });

  it('keeps native JSON renewal independent of Web Locks', async () => {
    vi.stubGlobal('navigator', {});
    postSpy.mockResolvedValue(refreshResponse('access-b', 'refresh-b'));

    await expect(renewSessionAccessToken()).resolves.toBe('access-b');

    expect(postSpy).toHaveBeenCalledOnce();
    expect(postSpy.mock.calls[0]?.[1]).toEqual({ refresh_token: 'refresh-a' });
    expect(readAccessToken()).toBe('access-b');
    expect(localStorage.getItem('refresh_token')).toBe('refresh-b');
  });

  it('does not let an old renewal overwrite a logout followed by a new account login', async () => {
    const pendingRefresh = deferred<AxiosResponse>();
    postSpy.mockReturnValue(pendingRefresh.promise);
    const renewal = renewSessionAccessToken();
    await vi.waitFor(() => expect(postSpy).toHaveBeenCalledOnce());

    clearSessionCredentials();
    installSessionTokens('access-b', 'refresh-b');
    pendingRefresh.resolve(refreshResponse('stale-access-a', 'stale-refresh-a'));

    await expect(renewal).rejects.toMatchObject({ code: 'session_changed' });
    expect(readAccessToken()).toBe('access-b');
    expect(localStorage.getItem('refresh_token')).toBe('refresh-b');
    expect(forcedLogout).not.toHaveBeenCalled();
  });

  it('does not let an old rejected renewal log out the replacement account', async () => {
    const pendingRefresh = deferred<AxiosResponse>();
    postSpy.mockReturnValue(pendingRefresh.promise);
    const renewal = renewSessionAccessToken();
    await vi.waitFor(() => expect(postSpy).toHaveBeenCalledOnce());

    clearSessionCredentials();
    installSessionTokens('access-b', 'refresh-b');
    pendingRefresh.reject(refreshFailure(401));

    await expect(renewal).rejects.toMatchObject({ code: 'session_changed' });
    expect(readAccessToken()).toBe('access-b');
    expect(localStorage.getItem('refresh_token')).toBe('refresh-b');
    expect(forcedLogout).not.toHaveBeenCalled();
  });

  it('never retries an old account HTTP request with the new account token', async () => {
    const requestStarted = deferred<void>();
    const deliverOldRejection = deferred<void>();
    postSpy.mockRejectedValue(new Error('stale request must not refresh'));
    const oldRequest = api.get('/financial-report', {
      adapter: async (config) => {
        requestStarted.resolve(undefined);
        await deliverOldRejection.promise;
        const rejected: AxiosResponse = {
          config,
          data: {},
          headers: {},
          status: 401,
          statusText: 'Unauthorized',
        };
        throw new AxiosError('expired', undefined, config, undefined, rejected);
      },
    });
    await requestStarted.promise;

    clearSessionCredentials();
    installSessionTokens('access-b', 'refresh-b');
    deliverOldRejection.resolve(undefined);

    await expect(oldRequest).rejects.toMatchObject({ code: 'session_changed' });
    expect(postSpy).not.toHaveBeenCalled();
    expect(readAccessToken()).toBe('access-b');
    expect(localStorage.getItem('refresh_token')).toBe('refresh-b');
  });

  it('blocks account replacement between renewal and asynchronous retry dispatch', async () => {
    const pendingRefresh = deferred<AxiosResponse>();
    postSpy.mockReturnValue(pendingRefresh.promise);
    let adapterCalls = 0;
    const oldRequest = api.post('/financial-write', { account: 'a' }, {
      adapter: async (config) => {
        adapterCalls += 1;
        const rejected: AxiosResponse = {
          config,
          data: {},
          headers: {},
          status: 401,
          statusText: 'Unauthorized',
        };
        throw new AxiosError('expired', undefined, config, undefined, rejected);
      },
    });
    await vi.waitFor(() => expect(postSpy).toHaveBeenCalledOnce());

    const originalRequest = api.request.bind(api);
    const retryDispatch = vi.spyOn(api, 'request').mockImplementationOnce((config) => {
      clearSessionCredentials();
      installSessionTokens('access-b', 'refresh-b');
      return originalRequest(config);
    });
    pendingRefresh.resolve(refreshResponse('renewed-access-a', 'renewed-refresh-a'));

    await expect(oldRequest).rejects.toMatchObject({ code: 'session_changed' });
    expect(retryDispatch).toHaveBeenCalledOnce();
    expect(adapterCalls).toBe(1);
    expect(readAccessToken()).toBe('access-b');
    expect(localStorage.getItem('refresh_token')).toBe('refresh-b');
  });

  it('preserves credentials after a temporary renewal failure', async () => {
    postSpy.mockRejectedValue(refreshFailure());

    await expect(renewSessionAccessToken()).rejects.toMatchObject({ code: 'network_error' });

    expect(readAccessToken()).toBe('access-a');
    expect(localStorage.getItem('refresh_token')).toBe('refresh-a');
    expect(forcedLogout).not.toHaveBeenCalled();
  });

  it.each([401, 403])('logs out after definitive refresh rejection %s', async (status) => {
    postSpy.mockRejectedValue(refreshFailure(status));

    await expect(renewSessionAccessToken()).rejects.toMatchObject({
      code: 'unauthorized',
      status,
    });

    expect(readAccessToken()).toBeNull();
    expect(localStorage.getItem('refresh_token')).toBeNull();
    expect(forcedLogout).toHaveBeenCalledOnce();
  });
});
