const CACHE_PREFIX = 'dcompany-erp-shell';

async function clearOldCaches() {
  if (!self.caches) return;
  const keys = await caches.keys();
  await Promise.all(
    keys
      .filter((key) => key.startsWith(CACHE_PREFIX))
      .map((key) => caches.delete(key)),
  );
}

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(clearOldCaches());
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    clearOldCaches()
      .then(() => self.clients.claim())
      .then(() => self.registration.unregister()),
  );
});
