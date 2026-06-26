/**
 * Settings — tabbed page.
 *
 * Tabs:
 *   - Account     change your own password
 *   - Company     name, GSTIN, PAN, timezone
 *   - Branches    list + create + edit branches; per-branch FSSAI + GSTIN
 *   - Pricing     menu, gaming, events, memberships
 *   - Sheets      (existing Google Sheets integration wizard)
 */
import { useState } from 'react';
import { User, Building2, Sheet, Store, Crown, IndianRupee } from 'lucide-react';

import AccountTab from './tabs/AccountTab';
import CompanyTab from './tabs/CompanyTab';
import BranchesTab from './tabs/BranchesTab';
import SheetsTab from './tabs/SheetsTab';
import MembershipsTab from './tabs/MembershipsTab';
import PricingTab from './tabs/PricingTab';

type Tab = 'account' | 'company' | 'branches' | 'pricing' | 'sheets' | 'memberships';

export default function SettingsScreen() {
  const [tab, setTab] = useState<Tab>('account');

  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Settings</h2>
        <p className="text-fg-muted text-sm">
          Personal account, company profile, branches, pricing, integrations.
        </p>
      </header>

      <div className="scroll-strip flex gap-1 mb-6 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'account'}  onClick={() => setTab('account')}>
          <User size={14}/> Account
        </TabBtn>
        <TabBtn active={tab === 'company'}  onClick={() => setTab('company')}>
          <Building2 size={14}/> Company
        </TabBtn>
        <TabBtn active={tab === 'branches'} onClick={() => setTab('branches')}>
          <Store size={14}/> Branches
        </TabBtn>
        <TabBtn active={tab === 'pricing'} onClick={() => setTab('pricing')}>
          <IndianRupee size={14}/> Pricing
        </TabBtn>
        <TabBtn active={tab === 'memberships'} onClick={() => setTab('memberships')}>
          <Crown size={14}/> Memberships
        </TabBtn>
        <TabBtn active={tab === 'sheets'}   onClick={() => setTab('sheets')}>
          <Sheet size={14}/> Google Sheets
        </TabBtn>
      </div>

      {tab === 'account'     && <AccountTab/>}
      {tab === 'company'     && <CompanyTab/>}
      {tab === 'branches'    && <BranchesTab/>}
      {tab === 'pricing'     && <PricingTab/>}
      {tab === 'memberships' && <MembershipsTab/>}
      {tab === 'sheets'      && <SheetsTab/>}
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
