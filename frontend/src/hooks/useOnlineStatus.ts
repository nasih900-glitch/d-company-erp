/**
 * useOnlineStatus — tracks browser connectivity (navigator.onLine +
 * online/offline events) so the web app can tell staff honestly when the
 * live backend is unreachable, instead of leaving a failed request as the
 * only signal. Web has no offline queue: POS, tables, and every other
 * screen need a live connection to the server to do anything.
 */
import { useEffect, useState } from 'react';

export function useOnlineStatus(): boolean {
  const [isOnline, setIsOnline] = useState(
    typeof navigator === 'undefined' ? true : navigator.onLine,
  );

  useEffect(() => {
    function onOnline() {
      setIsOnline(true);
    }
    function onOffline() {
      setIsOnline(false);
    }
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, []);

  return isOnline;
}
