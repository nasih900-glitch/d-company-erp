/**
 * Settings — tabbed page.
 *
 * Tabs:
 *   - Account     change your own password
 *   - Company     name, GSTIN, PAN, timezone
 *   - Shop        one operational location and its internal workspace
 *   - Pricing     menu, gaming, events, memberships
 *   - Sheets      (existing Google Sheets integration wizard)
 *   - Devices     explicitly authorised tablet health and recorded update offers
 */
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import {
  Building2,
  Crown,
  ChevronLeft,
  ChevronRight,
  Gift,
  HeartPulse,
  IndianRupee,
  Sheet,
  ShieldCheck,
  Store,
  TabletSmartphone,
  User,
} from 'lucide-react';

import { useAuth } from '@/modules/auth/AuthContext';
import { SkeletonCard } from '@/components/ui/Skeleton';
import AccountTab from './tabs/AccountTab';
import CompanyTab from './tabs/CompanyTab';
import BranchesTab from './tabs/BranchesTab';
import SheetsTab from './tabs/SheetsTab';
import MembershipsTab from './tabs/MembershipsTab';
import PricingTab from './tabs/PricingTab';
import LoyaltyProgramTab from './tabs/LoyaltyProgramTab';
import AccessControlTab from './tabs/AccessControlTab';
import { GAMING_CENTRE_FEATURES } from '@/lib/product-profile';
import { hasSupportAccess } from '@/lib/admin-access';

const DevicesUpdatesTab = lazy(() => import('./tabs/DevicesUpdatesTab'));
const SystemHealthTab = lazy(() => import('./tabs/SystemHealthTab'));

type Tab =
  | 'account'
  | 'company'
  | 'branches'
  | 'pricing'
  | 'points'
  | 'sheets'
  | 'memberships'
  | 'access'
  | 'health'
  | 'devices';

export default function SettingsScreen() {
  const { me, demo } = useAuth();
  // Access Control is itself gated by admin.audit.read on the backend — a
  // co_owner (protected_access=true, audit_access=false) must not see this
  // tab, so it keys off audit_access specifically, not protected_access.
  const hasAuditAccess = Boolean(demo || me?.audit_access);
  // System Health follows the exact tenant support permission. It is separate
  // from Audit Log, Access Control, and global Android release authority.
  const canViewSystemHealth = hasSupportAccess(me);
  // Release control is a separate server-issued capability bound to the exact
  // configured company and user. Never infer it from audit, protected-owner,
  // demo, role, or settings permissions; an older response missing the field
  // therefore safely denies access as well.
  const canManageDeviceUpdates = me?.release_control_access === true;
  const effectivePermissions = new Set(me?.effective_permissions ?? []);
  const canManageSettings = Boolean(
    demo || me?.protected_access || effectivePermissions.has('settings.manage'),
  );
  const canManageMemberships = Boolean(
    GAMING_CENTRE_FEATURES.memberships
    && (demo || me?.protected_access || effectivePermissions.has('memberships.manage')),
  );
  const [tab, setTab] = useState<Tab>('account');
  const tabStripRef = useRef<HTMLDivElement>(null);
  const [tabOverflow, setTabOverflow] = useState({ left: false, right: false });
  const updateTabOverflow = useCallback(() => {
    const strip = tabStripRef.current;
    if (!strip) return;
    setTabOverflow({
      left: strip.scrollLeft > 2,
      right: strip.scrollLeft + strip.clientWidth < strip.scrollWidth - 2,
    });
  }, []);

  useEffect(() => {
    const tabAllowed = (
      tab === 'account'
      || (tab === 'memberships' && canManageMemberships)
      || (tab === 'access' && hasAuditAccess)
      || (tab === 'health' && canViewSystemHealth)
      || (tab === 'devices' && canManageDeviceUpdates)
      || (
        (['company', 'branches', 'pricing', 'points', 'sheets'] as Tab[]).includes(tab)
        && canManageSettings
      )
    );
    if (!tabAllowed) setTab('account');
  }, [
    tab,
    canManageDeviceUpdates,
    canManageMemberships,
    canManageSettings,
    canViewSystemHealth,
    hasAuditAccess,
  ]);

  useEffect(() => {
    const strip = tabStripRef.current;
    if (!strip) return undefined;
    const frame = window.requestAnimationFrame(updateTabOverflow);
    const observer = typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(updateTabOverflow);
    observer?.observe(strip);
    if (!observer) window.addEventListener('resize', updateTabOverflow);
    return () => {
      window.cancelAnimationFrame(frame);
      observer?.disconnect();
      if (!observer) window.removeEventListener('resize', updateTabOverflow);
    };
  }, [
    canManageDeviceUpdates,
    canManageMemberships,
    canManageSettings,
    canViewSystemHealth,
    hasAuditAccess,
    updateTabOverflow,
  ]);

  const scrollTabs = (direction: -1 | 1) => {
    tabStripRef.current?.scrollBy({ left: direction * 240, behavior: 'smooth' });
  };

  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Settings</h2>
        <p className="text-fg-muted text-sm">
          Your account and, when authorised, shop, pricing, and integrations.
        </p>
      </header>

      <div className="relative mb-6 -mx-3 md:mx-0">
      <div ref={tabStripRef} onScroll={updateTabOverflow}
        className="scroll-strip flex gap-1 border-b border-bg-border px-3 md:px-0"
        role="navigation" aria-label="Settings sections">
        <TabBtn active={tab === 'account'}  onClick={() => setTab('account')}>
          <User size={14}/> Account
        </TabBtn>
        {canViewSystemHealth && (
          <TabBtn active={tab === 'health'} onClick={() => setTab('health')}>
            <HeartPulse size={14}/> System Health
          </TabBtn>
        )}
        {canManageSettings && (
          <>
            <TabBtn active={tab === 'company'} onClick={() => setTab('company')}>
              <Building2 size={14}/> Company
            </TabBtn>
            <TabBtn active={tab === 'branches'} onClick={() => setTab('branches')}>
              <Store size={14}/> Shop
            </TabBtn>
            <TabBtn active={tab === 'pricing'} onClick={() => setTab('pricing')}>
              <IndianRupee size={14}/> Pricing
            </TabBtn>
            <TabBtn active={tab === 'points'} onClick={() => setTab('points')}>
              <Gift size={14}/> Points
            </TabBtn>
            <TabBtn active={tab === 'sheets'} onClick={() => setTab('sheets')}>
              <Sheet size={14}/> Google Sheets
            </TabBtn>
          </>
        )}
        {canManageMemberships && (
          <TabBtn active={tab === 'memberships'} onClick={() => setTab('memberships')}>
            <Crown size={14}/> Memberships
          </TabBtn>
        )}
        {hasAuditAccess && (
          <TabBtn active={tab === 'access'} onClick={() => setTab('access')}>
            <ShieldCheck size={14}/> Access Control
          </TabBtn>
        )}
        {canManageDeviceUpdates && (
          <TabBtn active={tab === 'devices'} onClick={() => setTab('devices')}>
            <TabletSmartphone size={14}/> Devices &amp; Updates
          </TabBtn>
        )}
      </div>
      {tabOverflow.left && (
        <button type="button" aria-label="Show earlier settings tabs" onClick={() => scrollTabs(-1)}
          className="absolute left-1 top-1/2 -translate-y-1/2 rounded-full border border-bg-border bg-bg-surface/95 p-2 shadow-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent">
          <ChevronLeft size={16}/>
        </button>
      )}
      {tabOverflow.right && (
        <button type="button" aria-label="Show more settings tabs" onClick={() => scrollTabs(1)}
          className="absolute right-1 top-1/2 -translate-y-1/2 rounded-full border border-bg-border bg-bg-surface/95 p-2 shadow-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent">
          <ChevronRight size={16}/>
        </button>
      )}
      </div>

      {tab === 'account'     && <AccountTab/>}
      {tab === 'company' && canManageSettings && <CompanyTab/>}
      {tab === 'branches' && canManageSettings && <BranchesTab/>}
      {tab === 'pricing' && canManageSettings && <PricingTab/>}
      {tab === 'points' && canManageSettings && <LoyaltyProgramTab/>}
      {tab === 'memberships' && canManageMemberships && <MembershipsTab/>}
      {tab === 'sheets' && canManageSettings && <SheetsTab/>}
      {tab === 'access' && hasAuditAccess && <AccessControlTab/>}
      {tab === 'health' && canViewSystemHealth && (
        <Suspense fallback={<SkeletonCard lines={8} />}>
          <SystemHealthTab />
        </Suspense>
      )}
      {tab === 'devices' && canManageDeviceUpdates && (
        <Suspense fallback={<SkeletonCard lines={6} />}>
          <DevicesUpdatesTab />
        </Suspense>
      )}
    </div>
  );
}

function TabBtn({
  active, onClick, children,
}: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" onClick={onClick}
      className={`shrink-0 px-4 py-2 text-sm font-medium border-b-2 -mb-px whitespace-nowrap flex items-center gap-1.5
        ${active ? 'border-accent text-accent' : 'border-transparent text-fg-muted hover:text-fg'}`}>
      {children}
    </button>
  );
}
