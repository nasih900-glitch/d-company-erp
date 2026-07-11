export function registerServiceWorker() {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return;

  window.addEventListener('load', () => {
    navigator.serviceWorker.getRegistrations()
      .then((registrations) => Promise.all(registrations.map((registration) => registration.unregister())))
      .then(async () => {
        if (!('caches' in window)) return;
        const keys = await caches.keys();
        await Promise.all(
          keys
            .filter((key) => key.startsWith('dcompany-erp-shell'))
            .map((key) => caches.delete(key)),
        );
      })
      .catch(() => {
        // Cache cleanup is best-effort; app startup must not depend on it.
      });
  });
}
