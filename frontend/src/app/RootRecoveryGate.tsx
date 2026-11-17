import { lazy, Suspense } from 'react';
import { useLocation } from 'react-router-dom';
import { HISTORICAL_PAID_ROUTE } from '@/lib/paid-bill-historical-api';
import HistoricalPaidBillRecovery from '@/modules/pos/HistoricalPaidBillRecovery';

const OrdinaryApplication = lazy(() => import('./OrdinaryApplication'));
export default function RootRecoveryGate() {
  const location = useLocation();
  if (location.pathname === HISTORICAL_PAID_ROUTE) {
    if (location.search || location.hash) return <main className="p-6">Use the saved-work recovery entry without additional URL details. Keep browser data.</main>;
    return <HistoricalPaidBillRecovery key={location.key}/>;
  }
  return <Suspense fallback={<p role="status" className="p-6">Loading…</p>}><OrdinaryApplication/></Suspense>;
}
