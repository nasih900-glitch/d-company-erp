// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
import { registerServiceWorker } from './registerServiceWorker';
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
it.each(['loading', 'complete'] as const)('ordinary cleanup runs once when registration occurs at %s', async ready => {
  vi.stubEnv('PROD', true); vi.spyOn(document, 'readyState', 'get').mockReturnValue(ready);
  const unregister = vi.fn(async () => true), getRegistrations = vi.fn(async () => [{ unregister }]);
  Object.defineProperty(navigator, 'serviceWorker', { configurable: true, value: { getRegistrations } });
  const remove = vi.fn(async () => true); vi.stubGlobal('caches', { keys: async () => ['dcompany-erp-shell-old', 'unrelated-cache'], delete: remove });
  registerServiceWorker();
  if (ready === 'loading') { expect(getRegistrations).not.toHaveBeenCalled(); window.dispatchEvent(new Event('load')); }
  await vi.waitFor(() => expect(remove).toHaveBeenCalledTimes(1)); window.dispatchEvent(new Event('load'));
  expect(getRegistrations).toHaveBeenCalledTimes(1); expect(unregister).toHaveBeenCalledTimes(1); expect(remove).toHaveBeenCalledWith('dcompany-erp-shell-old');
});
