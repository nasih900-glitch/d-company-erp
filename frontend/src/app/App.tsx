import { lazy, Suspense, type ReactNode } from 'react';
import { Route, Routes, Navigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';

import AppShell from '@/components/layout/AppShell';
import RequireAuth from '@/modules/auth/RequireAuth';
import { useAuth } from '@/modules/auth/AuthContext';
import { hasAdminSystemAccess, hasAuditAccess } from '@/lib/admin-access';
import { LIVE_MODE } from '@/lib/demo';
import { canAccessRefunds } from '@/modules/refunds/refund-policy';
import { canViewMemberships } from '@/modules/memberships/membership-policy';
import { internalAppRouteOr } from '@/lib/internal-navigation';
import {
  canManageGamingCentreProducts,
  GAMING_CENTRE_FEATURES,
  webLandingRouteFor,
  WEB_PRODUCT_PROFILE,
  type WebFeature,
} from '@/lib/product-profile';

const Login = lazy(() => import('@/modules/auth/Login'));
const POSScreen = lazy(() => import('@/modules/pos/POSScreen'));
const LivePOSScreen = lazy(() => import('@/modules/pos/LivePOSScreen'));
const OrdersAndShiftsScreen = lazy(() => import('@/modules/pos/OrdersAndShiftsScreen'));
const CustomersScreen = lazy(() => import('@/modules/customers/CustomersScreen'));
const PublicMenuScreen = lazy(() => import('@/modules/public/PublicMenuScreen'));
const KitchenScreen = lazy(() => import('@/modules/kitchen/KitchenScreen'));
const InsightsScreen = lazy(() => import('@/modules/insights/InsightsScreen'));
const AuditScreen = lazy(() => import('@/modules/audit/AuditScreen'));
const BugReportsScreen = lazy(() => import('@/modules/bug-reports/BugReportsScreen'));
const TablesScreen = lazy(() => import('@/modules/tables/TablesScreen'));
const ReservationsScreen = lazy(() => import('@/modules/reservations/ReservationsScreen'));
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
const RefundsScreen = lazy(() => import('@/modules/refunds/RefundsScreen'));
const MembershipsScreen = lazy(() => import('@/modules/memberships/MembershipsScreen'));
const DeviceCentreScreen = lazy(() => import('@/modules/remote-assistance/DeviceCentreScreen'));

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

function AuditAccessOnly({ children }: { children: ReactNode }) {
  const { me, demo } = useAuth();
  if (demo || hasAuditAccess(me)) return <>{children}</>;
  return <Navigate to="/pos" replace />;
}

export function AdminSystemOnly({ children }: { children: ReactNode }) {
  const { me } = useAuth();
  if (hasAdminSystemAccess(me)) return <>{children}</>;
  return <Navigate to="/pos" replace />;
}

function RefundAccessOnly({ children }: { children: ReactNode }) {
  const { me, demo } = useAuth();
  if (demo || canAccessRefunds(me)) return <>{children}</>;
  return <Navigate to="/pos" replace />;
}

function MembershipAccessOnly({ children }: { children: ReactNode }) {
  const { me, demo } = useAuth();
  if (demo || canViewMemberships(me)) return <>{children}</>;
  return <Navigate to="/pos" replace />;
}

function FeatureOnly({
  feature,
  children,
  fallback = WEB_PRODUCT_PROFILE.defaultRoute,
}: {
  feature: WebFeature;
  children: ReactNode;
  fallback?: string;
}) {
  if (GAMING_CENTRE_FEATURES[feature]) return <>{children}</>;
  return <Navigate to={internalAppRouteOr(fallback)} replace />;
}

function ProfileOwnerOnly({ children }: { children: ReactNode }) {
  const { me, demo } = useAuth();
  if (demo || me?.protected_access) return <>{children}</>;
  return <Navigate to={internalAppRouteOr(WEB_PRODUCT_PROFILE.defaultRoute)} replace />;
}

function ModuleAccessOnly({ module, children }: { module: string; children: ReactNode }) {
  const { me, demo } = useAuth();
  if (demo || me?.protected_access || me?.accessible_modules?.includes(module)) {
    return <>{children}</>;
  }
  const fallback = me?.accessible_modules?.includes('gaming')
    ? '/gaming'
    : me?.accessible_modules?.includes('pos') ? '/pos' : '/workspace-unavailable';
  return <Navigate to={internalAppRouteOr(fallback)} replace />;
}

function ProductManagementOnly({ children }: { children: ReactNode }) {
  const { me, demo } = useAuth();
  if (canManageGamingCentreProducts(me, demo)) return <>{children}</>;
  return <Navigate to={internalAppRouteOr(WEB_PRODUCT_PROFILE.defaultRoute)} replace />;
}

function ProfileLanding() {
  const { me, demo } = useAuth();
  if (demo) return <Navigate to="/analytics" replace />;
  if (!me) return <Navigate to="/login" replace />;
  return <Navigate to={internalAppRouteOr(webLandingRouteFor(me))} replace />;
}

function WorkspaceUnavailable() {
  return (
    <div className="card mx-auto mt-8 max-w-xl text-center">
      <h2 className="text-lg font-semibold">No Gaming Centre workspace is assigned</h2>
      <p className="mt-2 text-sm text-fg-muted">
        This account does not currently have Gaming or POS access. Ask an owner to update the
        account permissions, or use Help to report an access problem.
      </p>
    </div>
  );
}

function MenuRoute() {
  const { me, loading } = useAuth();
  if (loading) {
    return <RouteFallback />;
  }
  if (!me) {
    return GAMING_CENTRE_FEATURES.publicMenu
      ? <Screen><PublicMenuScreen /></Screen>
      : <Navigate to="/login" replace />;
  }
  if (!GAMING_CENTRE_FEATURES.menuManagement) {
    return <Navigate to={internalAppRouteOr(WEB_PRODUCT_PROFILE.defaultRoute)} replace />;
  }
  return (
    <AppShell>
      <ProductManagementOnly>
        <Screen>
          <MenuScreen />
        </Screen>
      </ProductManagementOnly>
    </AppShell>
  );
}

export default function App() {
  return (
    <Routes>
      {/* Public — no auth */}
      <Route
        path="/public/menu"
        element={
          <FeatureOnly feature="publicMenu" fallback="/login">
            <Screen><PublicMenuScreen /></Screen>
          </FeatureOnly>
        }
      />
      <Route path="/menu" element={<MenuRoute />} />

      {/* Kitchen Display — requires login but no shell */}
      <Route path="/kitchen" element={
        <RequireAuth>
          <FeatureOnly feature="kitchen">
            <ModuleAccessOnly module="kitchen"><Screen><KitchenScreen /></Screen></ModuleAccessOnly>
          </FeatureOnly>
        </RequireAuth>
      } />

      <Route path="/login" element={<Screen><Login /></Screen>} />
      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<ProfileLanding />} />
        <Route path="/workspace-unavailable" element={<WorkspaceUnavailable />} />
        <Route path="/pos" element={
          <FeatureOnly feature="pos"><ModuleAccessOnly module="pos"><Screen>{LIVE_MODE ? <LivePOSScreen /> : <POSScreen />}</Screen></ModuleAccessOnly></FeatureOnly>
        } />
        <Route path="/operations" element={
          <FeatureOnly feature="shifts"><ModuleAccessOnly module="pos"><Screen><OrdersAndShiftsScreen /></Screen></ModuleAccessOnly></FeatureOnly>
        } />
        <Route path="/tables" element={
          <FeatureOnly feature="tables"><ModuleAccessOnly module="tables"><Screen><TablesScreen /></Screen></ModuleAccessOnly></FeatureOnly>
        } />
        <Route path="/reservations" element={
          <FeatureOnly feature="reservations"><ModuleAccessOnly module="tables"><Screen><ReservationsScreen /></Screen></ModuleAccessOnly></FeatureOnly>
        } />
        <Route path="/inventory" element={
          <FeatureOnly feature="stock"><ModuleAccessOnly module="inventory"><Screen><InventoryScreen /></Screen></ModuleAccessOnly></FeatureOnly>
        } />
        <Route path="/gaming" element={
          <FeatureOnly feature="gaming"><ModuleAccessOnly module="gaming"><Screen><GamingScreen /></Screen></ModuleAccessOnly></FeatureOnly>
        } />
        <Route path="/events" element={
          <FeatureOnly feature="events"><Screen><EventsScreen /></Screen></FeatureOnly>
        } />
        <Route path="/finance" element={
          <FeatureOnly feature="finance"><ProfileOwnerOnly><Screen><FinanceScreen /></Screen></ProfileOwnerOnly></FeatureOnly>
        } />
        <Route path="/ocr" element={
          <FeatureOnly feature="ocr"><ModuleAccessOnly module="ocr"><Screen><OcrScreen /></Screen></ModuleAccessOnly></FeatureOnly>
        } />
        <Route path="/staff" element={
          <FeatureOnly feature="staffAdmin"><ProfileOwnerOnly><Screen><StaffScreen /></Screen></ProfileOwnerOnly></FeatureOnly>
        } />
        <Route path="/customers" element={
          <FeatureOnly feature="customers"><Screen><CustomersScreen /></Screen></FeatureOnly>
        } />
        <Route
          path="/memberships"
          element={
            <FeatureOnly feature="memberships">
              <Screen><MembershipAccessOnly><MembershipsScreen /></MembershipAccessOnly></Screen>
            </FeatureOnly>
          }
        />
        <Route path="/insights" element={
          <FeatureOnly feature="advancedInsights"><ProfileOwnerOnly><Screen><InsightsScreen /></Screen></ProfileOwnerOnly></FeatureOnly>
        } />
        <Route path="/audit" element={
          <FeatureOnly feature="audit"><Screen><AuditAccessOnly><AuditScreen /></AuditAccessOnly></Screen></FeatureOnly>
        } />
        <Route
          path="/bug-reports"
          element={
            <FeatureOnly feature="supportInbox">
              <Screen><AdminSystemOnly><BugReportsScreen /></AdminSystemOnly></Screen>
            </FeatureOnly>
          }
        />
        <Route
          path="/device-centre"
          element={
            <FeatureOnly feature="settings">
              <Screen><AdminSystemOnly><DeviceCentreScreen /></AdminSystemOnly></Screen>
            </FeatureOnly>
          }
        />
        <Route path="/analytics" element={
          <FeatureOnly feature="dashboard"><ProfileOwnerOnly><Screen><AnalyticsScreen /></Screen></ProfileOwnerOnly></FeatureOnly>
        } />
        <Route path="/reports" element={
          <FeatureOnly feature="reports"><ProfileOwnerOnly><Screen><ReportsScreen /></Screen></ProfileOwnerOnly></FeatureOnly>
        } />
        <Route
          path="/refunds"
          element={
            <FeatureOnly feature="refundsWorkspace">
              <Screen><RefundAccessOnly><RefundsScreen /></RefundAccessOnly></Screen>
            </FeatureOnly>
          }
        />
        <Route path="/settings" element={
          <FeatureOnly feature="settings"><ProfileOwnerOnly><Screen><SettingsScreen /></Screen></ProfileOwnerOnly></FeatureOnly>
        } />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
