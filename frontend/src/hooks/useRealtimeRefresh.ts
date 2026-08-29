import { useEffect, useMemo, useRef } from 'react';

import { subscribeRealtime } from '@/lib/realtime';

type Refresh = () => void | Promise<void>;

interface RealtimeRefreshOptions {
  resources: readonly string[];
  refresh: Refresh;
  enabled?: boolean;
  debounceMs?: number;
}

/**
 * Coalesces resource invalidations into one authoritative REST refresh.
 *
 * A single POS payment can broadcast orders, shifts, finance, customers and
 * inventory in quick succession. Owner screens commonly depend on several of
 * those resources, so subscribing each one directly would run the same GET
 * repeatedly and allow an older response to land after a newer one. This hook
 * serialises refreshes and guarantees one trailing pass when a change arrives
 * while a request is already running.
 */
export function useRealtimeRefresh({
  resources,
  refresh,
  enabled = true,
  debounceMs = 180,
}: RealtimeRefreshOptions): void {
  const refreshRef = useRef(refresh);
  useEffect(() => { refreshRef.current = refresh; }, [refresh]);

  const resourceKey = useMemo(
    () => [...new Set(resources)].sort().join('\u0000'),
    [resources],
  );

  useEffect(() => {
    if (!enabled || !resourceKey) return undefined;

    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let running = false;
    let trailing = false;

    const run = async () => {
      if (disposed) return;
      if (running) {
        trailing = true;
        return;
      }
      running = true;
      try {
        await refreshRef.current();
      } catch {
        // Screens own their user-facing error state. A failed refresh must not
        // kill the realtime subscription or create an unhandled rejection.
      } finally {
        running = false;
        if (!disposed && trailing) {
          trailing = false;
          timer = setTimeout(() => {
            timer = null;
            void run();
          }, 0);
        }
      }
    };

    const schedule = () => {
      if (disposed) return;
      if (running) {
        trailing = true;
        return;
      }
      if (timer) return;
      timer = setTimeout(() => {
        timer = null;
        void run();
      }, debounceMs);
    };

    const unsubscribers = resourceKey
      .split('\u0000')
      .map((resource) => subscribeRealtime(resource, schedule));

    return () => {
      disposed = true;
      if (timer) clearTimeout(timer);
      unsubscribers.forEach((unsubscribe) => unsubscribe());
    };
  }, [debounceMs, enabled, resourceKey]);
}
