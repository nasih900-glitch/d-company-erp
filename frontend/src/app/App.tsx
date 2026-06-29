import { lazy, Suspense, type ReactNode } from 'react';
import { Route, Routes, Navigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';

import AppShell from '@/components/layout/AppShell';
import RequireAuth from '@/modules/auth/RequireAuth';
import { useAuth } from '@/modules/auth/AuthContext';
import { LIVE_MODE } from '@/lib/demo';

const Login = lazy(() => import('@/modules/auth/Login'));
const POSScreen = lazy(() => import('@/modules/pos/POSScreen'));
const LivePOSScreen = lazy(() => import('@/modules/pos/LivePOSScreen'));
const OrdersAndShiftsScreen = lazy(() => import('@/modules/pos/OrdersAndShiftsScreen'));
const CustomersScreen = lazy(() => import('@/modules/customers/CustomersScreen'));
const PublicMenuScreen = lazy(() => import('@/modules/public/PublicMenuScreen'));
const KitchenScreen = lazy(() => import('@/modules/kitchen/KitchenScreen'));
const InsightsScreen = lazy(() => import('@/modules/insights/InsightsScreen'));
const AuditScreen = lazy(() => import('@/modules/audit/AuditScreen'));
const TablesScreen = lazy(() => import('@/modules/tables/TablesScreen'));
const MenuScreen = lazy(() => import('@/modules/menu/MenuScreen'));
const InventoryScreen = lazy(() => import('@/modules/inventory/InventoryScreen'));
const GamingScreen = lazy(() => import('@/modules/gaming/GamingScreen'));
const EventsScreen = lazy(() => import('@/modules/events/EventsScreen'));
const FinanceScreen = lazy(() => import('@/modules/finance/FinanceScreen'));
const OcrScreen = lazy(() => import('@/modules/ocr/OcrScreen'));
const StaffScreen = lazy(() => import('@/modules/staff/StaffScreen'));
const AnalyticsScreen = lazy(() => import('@/modules/analytics/AnalyticsScreen'));
const ReportsScreen = lazy(() => import('@/modules/reports/ReportsScreen'));
const SettingsScreen = lazy(() => import('@/modules/settings/SettingsScreen'));

function RouteFallback() {
  return (
    <div className="flex min-h-[50vh] items-center justify-center bg-bg text-fg-muted">
      <Loader2 className="mr-2 animate-spin" size={18} aria-hidden="true" />
      <span className="text-sm">Loading</span>
    </div>
  );
}

function Screen({ children }: { children: ReactNode }) {
  return <Suspense fallback={<RouteFallback />}>{children}</Suspense>;
}

function SuperOwnerOnly({ children }: { children: ReactNode }) {
  const { me, demo } = useAuth();
  if (demo || me?.protected_access) return <>{children}</>;
  return <Navigate to="/pos" replace />;
}

function MenuRoute() {
  const { me, loading } = useAuth();
  if (loading) {
    return <RouteFallback />;
  }
  if (!me) return <Screen><PublicMenuScreen /></Screen>;
  return (
    <AppShell>
      <Screen>
        <MenuScreen />
      </Screen>
    </AppShell>
  );
}

export default function App() {
  return (
    <Routes>
      {/* Public — no auth */}
      <Route path="/public/menu" element={<Screen><PublicMenuScreen /></Screen>} />
      <Route path="/menu" element={<MenuRoute />} />

      {/* Kitchen Display — requires login but no shell */}
      <Route path="/kitchen" element={
        <RequireAuth><Screen><KitchenScreen /></Screen></RequireAuth>
      } />

      <Route path="/login" element={<Screen><Login /></Screen>} />
      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/pos" replace />} />
        <Route path="/pos" element={<Screen>{LIVE_MODE ? <LivePOSScreen /> : <POSScreen />}</Screen>} />
        <Route path="/operations" element={<Screen><OrdersAndShiftsScreen /></Screen>} />
        <Route path="/tables" element={<Screen><TablesScreen /></Screen>} />
        <Route path="/inventory" element={<Screen><SuperOwnerOnly><InventoryScreen /></SuperOwnerOnly></Screen>} />
        <Route path="/gaming" element={<Screen><GamingScreen /></Screen>} />
        <Route path="/events" element={<Screen><EventsScreen /></Screen>} />
        <Route path="/finance" element={<Screen><FinanceScreen /></Screen>} />
        <Route path="/ocr" element={<Screen><OcrScreen /></Screen>} />
        <Route path="/staff" element={<Screen><SuperOwnerOnly><StaffScreen /></SuperOwnerOnly></Screen>} />
        <Route path="/customers" element={<Screen><CustomersScreen /></Screen>} />
        <Route path="/insights" element={<Screen><InsightsScreen /></Screen>} />
        <Route path="/audit" element={<Screen><SuperOwnerOnly><AuditScreen /></SuperOwnerOnly></Screen>} />
        <Route path="/analytics" element={<Screen><AnalyticsScreen /></Screen>} />
        <Route path="/reports" element={<Screen><ReportsScreen /></Screen>} />
        <Route path="/settings" element={<Screen><SuperOwnerOnly><SettingsScreen /></SuperOwnerOnly></Screen>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
