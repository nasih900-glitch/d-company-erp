/**
 * Settings — tabbed page.
 *
 * Tabs:
 *   - Account     change your own password
 *   - Company     name, GSTIN, PAN, timezone
 *   - Shop        one operational location and its internal workspace
 *   - Pricing     menu, gaming, events, memberships
 *   - Sheets      (existing Google Sheets integration wizard)
 */
import { useEffect, useState } from 'react';
import { User, Building2, Sheet, Store, Crown, IndianRupee, ShieldCheck } from 'lucide-react';

import { useAuth } from '@/modules/auth/AuthContext';
import AccountTab from './tabs/AccountTab';
import CompanyTab from './tabs/CompanyTab';
import BranchesTab from './tabs/BranchesTab';
import SheetsTab from './tabs/SheetsTab';
import MembershipsTab from './tabs/MembershipsTab';
import PricingTab from './tabs/PricingTab';
import AccessControlTab from './tabs/AccessControlTab';
import { GAMING_CENTRE_FEATURES } from '@/lib/product-profile';

type Tab = 'account' | 'company' | 'branches' | 'pricing' | 'sheets' | 'memberships' | 'access';

export default function SettingsScreen() {
  const { me, demo } = useAuth();
  // Access Control is itself gated by admin.audit.read on the backend — a
  // co_owner (protected_access=true, audit_access=false) must not see this
  // tab, so it keys off audit_access specifically, not protected_access.
  const hasAuditAccess = Boolean(demo || me?.audit_access);
  const effectivePermissions = new Set(me?.effective_permissions ?? []);
  const canManageSettings = Boolean(
    demo || me?.protected_access || effectivePermissions.has('settings.manage'),
  );
  const canManageMemberships = Boolean(
    GAMING_CENTRE_FEATURES.memberships
    && (demo || me?.protected_access || effectivePermissions.has('memberships.manage')),
  );
  const [tab, setTab] = useState<Tab>('account');

  useEffect(() => {
    const tabAllowed = (
      tab === 'account'
      || (tab === 'memberships' && canManageMemberships)
      || (tab === 'access' && hasAuditAccess)
      || (
        (['company', 'branches', 'pricing', 'sheets'] as Tab[]).includes(tab)
        && canManageSettings
      )
    );
    if (!tabAllowed) setTab('account');
  }, [tab, canManageMemberships, canManageSettings, hasAuditAccess]);

  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Settings</h2>
        <p className="text-fg-muted text-sm">
          Your account and, when authorised, shop, pricing, and integrations.
        </p>
      </header>

      <div className="scroll-strip flex gap-1 mb-6 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'account'}  onClick={() => setTab('account')}>
          <User size={14}/> Account
        </TabBtn>
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
      </div>

      {tab === 'account'     && <AccountTab/>}
      {tab === 'company' && canManageSettings && <CompanyTab/>}
      {tab === 'branches' && canManageSettings && <BranchesTab/>}
      {tab === 'pricing' && canManageSettings && <PricingTab/>}
      {tab === 'memberships' && canManageMemberships && <MembershipsTab/>}
      {tab === 'sheets' && canManageSettings && <SheetsTab/>}
      {tab === 'access' && hasAuditAccess && <AccessControlTab/>}
    </div>
  );
}

function TabBtn({
  active, onClick, children,
}: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button onClick={onClick}
      className={`shrink-0 px-4 py-2 text-sm font-medium border-b-2 -mb-px whitespace-nowrap flex items-center gap-1.5
        ${active ? 'border-accent text-accent' : 'border-transparent text-fg-muted hover:text-fg'}`}>
      {children}
    </button>
  );
}
