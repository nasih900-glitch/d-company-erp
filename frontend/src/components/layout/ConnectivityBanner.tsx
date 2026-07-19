import { WifiOff } from 'lucide-react';

import { useOnlineStatus } from '@/hooks/useOnlineStatus';

// Honest connectivity notice — mirrors the native app's NetworkBanner copy.
// The web app has no offline queue: it needs a live connection to the
// backend to take orders, record payments, or read stock/reports.
export default function ConnectivityBanner() {
  const isOnline = useOnlineStatus();
  if (isOnline) return null;

  return (
    <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-sm flex items-start gap-3">
      <WifiOff size={18} className="text-accent-bad shrink-0 mt-0.5" />
      <div>
        <div className="font-semibold text-accent-bad">Server connection is offline</div>
        <p className="mt-1 text-fg-muted">
          D Company ERP needs a live connection to the backend for billing, audit, stock, and
          reports. Reconnect before taking orders or recording payments.
        </p>
      </div>
    </div>
  );
}
