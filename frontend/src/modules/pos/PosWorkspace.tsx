import { lazy } from 'react';
import { LIVE_MODE } from '@/lib/demo';
import { hasExactPermission } from '@/lib/effective-permissions';
import { useAuth } from '@/modules/auth/AuthContext';

const LivePOSScreen = lazy(() => import('./LivePOSScreen'));
const POSScreen = lazy(() => import('./POSScreen'));
const OrdersAndShiftsScreen = lazy(() => import('./OrdersAndShiftsScreen'));

/** Readers never mount checkout effects, draft ownership, or claim acquisition. */
export default function PosWorkspace() {
  const { me } = useAuth();
  if (!hasExactPermission(me, 'pos.read')) return <div className="card text-sm text-fg-muted" role="status">POS access is unavailable for this account.</div>;
  if (!hasExactPermission(me, 'pos.write')) return <OrdersAndShiftsScreen />;
  return LIVE_MODE ? <LivePOSScreen /> : <POSScreen />;
}
