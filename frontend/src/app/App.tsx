import type { ReactNode } from 'react';
import { Route, Routes, Navigate } from 'react-router-dom';

import AppShell from '@/components/layout/AppShell';
import RequireAuth from '@/modules/auth/RequireAuth';
import { useAuth } from '@/modules/auth/AuthContext';
import Login from '@/modules/auth/Login';
import POSScreen from '@/modules/pos/POSScreen';
import LivePOSScreen from '@/modules/pos/LivePOSScreen';
import OrdersAndShiftsScreen from '@/modules/pos/OrdersAndShiftsScreen';
import CustomersScreen from '@/modules/customers/CustomersScreen';
import PublicMenuScreen from '@/modules/public/PublicMenuScreen';
import KitchenScreen from '@/modules/kitchen/KitchenScreen';
import InsightsScreen from '@/modules/insights/InsightsScreen';
import AuditScreen from '@/modules/audit/AuditScreen';
import { LIVE_MODE } from '@/lib/demo';
import TablesScreen from '@/modules/tables/TablesScreen';
import MenuScreen from '@/modules/menu/MenuScreen';
import InventoryScreen from '@/modules/inventory/InventoryScreen';
import GamingScreen from '@/modules/gaming/GamingScreen';
import EventsScreen from '@/modules/events/EventsScreen';
import FinanceScreen from '@/modules/finance/FinanceScreen';
import OcrScreen from '@/modules/ocr/OcrScreen';
import StaffScreen from '@/modules/staff/StaffScreen';
import AnalyticsScreen from '@/modules/analytics/AnalyticsScreen';
import ReportsScreen from '@/modules/reports/ReportsScreen';
import SettingsScreen from '@/modules/settings/SettingsScreen';

function SuperOwnerOnly({ children }: { children: ReactNode }) {
  const { me, demo } = useAuth();
  if (demo || me?.protected_access) return <>{children}</>;
  return <Navigate to="/pos" replace />;
}

function MenuRoute() {
  const { me, loading } = useAuth();
  if (loading) {
    return <div className="min-h-screen bg-bg" />;
  }
  if (!me) return <PublicMenuScreen />;
  return (
    <AppShell>
      <MenuScreen />
    </AppShell>
  );
}

export default function App() {
  return (
    <Routes>
      {/* Public — no auth */}
      <Route path="/public/menu" element={<PublicMenuScreen />} />
      <Route path="/menu" element={<MenuRoute />} />

      {/* Kitchen Display — requires login but no shell */}
      <Route path="/kitchen" element={
        <RequireAuth><KitchenScreen /></RequireAuth>
      } />

      <Route path="/login" element={<Login />} />
      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/pos" replace />} />
        <Route path="/pos" element={LIVE_MODE ? <LivePOSScreen /> : <POSScreen />} />
        <Route path="/operations" element={<OrdersAndShiftsScreen />} />
        <Route path="/tables" element={<TablesScreen />} />
        <Route path="/inventory" element={<SuperOwnerOnly><InventoryScreen /></SuperOwnerOnly>} />
        <Route path="/gaming" element={<GamingScreen />} />
        <Route path="/events" element={<EventsScreen />} />
        <Route path="/finance" element={<FinanceScreen />} />
        <Route path="/ocr" element={<OcrScreen />} />
        <Route path="/staff" element={<SuperOwnerOnly><StaffScreen /></SuperOwnerOnly>} />
        <Route path="/customers" element={<CustomersScreen />} />
        <Route path="/insights" element={<InsightsScreen />} />
        <Route path="/audit" element={<SuperOwnerOnly><AuditScreen /></SuperOwnerOnly>} />
        <Route path="/analytics" element={<AnalyticsScreen />} />
        <Route path="/reports" element={<ReportsScreen />} />
        <Route path="/settings" element={<SuperOwnerOnly><SettingsScreen /></SuperOwnerOnly>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
