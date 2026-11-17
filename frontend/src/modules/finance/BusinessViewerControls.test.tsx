// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Me } from '@/modules/auth/AuthContext';
import FinanceScreen from './FinanceScreen';
import StaffScreen from '@/modules/staff/StaffScreen';

const mocks = vi.hoisted(() => ({
  me: null as Me | null, listExpenses: vi.fn(), listPartners: vi.fn(), listCapital: vi.fn(), listUsers: vi.fn(),
  listRoles: vi.fn(), updateUser: vi.fn(), listManualCollections: vi.fn(), listTipPayouts: vi.fn(), listAssets: vi.fn(),
  listExpenseReceipts: vi.fn(), createExpense: vi.fn(), createPartner: vi.fn(), createCapitalEntry: vi.fn(),
  createManualCollection: vi.fn(), createTipPayout: vi.fn(), createAsset: vi.fn(), uploadExpenseReceipt: vi.fn(), reviewExpenseReceipts: vi.fn(),
}));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({ me: mocks.me, demo: false }) }));
vi.mock('@/components/ui/Notifications', () => ({ useNotifications: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }) }));
vi.mock('@/hooks/useRealtimeRefresh', () => ({ useRealtimeRefresh: () => undefined }));
vi.mock('@/lib/erp-api', () => ({
  finance: { ...mocks, listBranches: async () => [{ id: 'branch', name: 'Shop' }], partnerProfitSplit: async () => null, distributableProfit: async () => null, businessMetrics: async () => null },
  settings: { listExpenseCategories: async () => [{ id: 'category', name: 'General' }] },
  shifts: { list: async () => [] },
  accounting: { trialBalance: async () => ({ lines: [] }) },
  reports: { daily: () => new Promise(() => undefined) },
  insights: { costingCoverage: async () => null },
  pos: { receiptBusiness: async () => ({ timezone: 'Asia/Kolkata' }) },
  staff: { listUsers: mocks.listUsers, listRoles: mocks.listRoles, updateUser: mocks.updateUser },
  auth: {},
}));

let root: Root, host: HTMLDivElement;
const viewer = {
  user_id: 'actor', company_id: 'company', branch_id: 'branch', name: 'Viewer', email: 'viewer@example.test',
  roles: ['auditor'], protected_access: false, audit_access: false, release_control_access: false, accessible_modules: ['finance', 'staff'],
  effective_permissions: ['finance.read', 'staff.read', 'analytics.read', 'analytics.export', 'pos.read'],
} satisfies Me;
const expense = { id: 'expense', branch_id: 'branch', category_id: null, paid_at: '2026-11-07T08:00:00Z', amount_minor: 1000, paid_via: 'bank', vendor_name: 'Example vendor', receipt_status: 'pending' };
const movement = { id: 'movement', branch_id: 'branch', method: 'upi', amount_minor: 1000, source_ref: 'Synthetic record', created_by: 'actor', created_at: '2026-11-07T08:00:00Z', effective_at: '2026-11-07T08:00:00Z', business_date: '2026-11-07', business_timezone: 'Asia/Kolkata' };
beforeEach(() => {
  vi.clearAllMocks();
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  mocks.me = viewer;
  mocks.listExpenses.mockResolvedValue([expense]);
  mocks.listPartners.mockResolvedValue([{ id: 'partner', name: 'Example partner', ownership_pct: 50, capital_balance_minor: 1000 }]);
  mocks.listCapital.mockResolvedValue([{ ...movement, type: 'invest', settlement_account: 'bank' }]);
  mocks.listManualCollections.mockResolvedValue([movement]);
  mocks.listTipPayouts.mockResolvedValue([movement]);
  mocks.listAssets.mockResolvedValue([]);
  mocks.listExpenseReceipts.mockResolvedValue([]);
  mocks.listUsers.mockResolvedValue([{ id: 'target', email: 'target@example.test', name: 'Target', phone: null, status: 'active', roles: ['manager', 'auditor'] }]);
  mocks.listRoles.mockResolvedValue(['owner', 'store_staff', 'auditor', 'manager', 'gaming_cashier'].map((code) => ({ code, name: code })));
  mocks.updateUser.mockResolvedValue({});
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
async function render(node: React.ReactNode) {
  await act(async () => { root.render(<MemoryRouter>{node}</MemoryRouter>); });
}
async function click(label: string) {
  const button = [...document.querySelectorAll('button')].find((element) => element.textContent?.trim() === label);
  expect(button, `button ${label}`).toBeDefined();
  await act(async () => { button?.click(); });
}
function buttons() { return [...document.querySelectorAll('button')].map((button) => button.textContent?.trim()); }

it('Business Viewer reads expenses and receipts without add, void, correction, upload or review controls', async () => {
  await render(<FinanceScreen />); await click('Expenses');
  expect(host.textContent).toContain('Example vendor');
  expect(buttons()).not.toContain('Add expense');
  expect(document.querySelector('[aria-label="Void expense"]')).toBeNull();
  expect(document.querySelector('[aria-label="Correct closed-shift expense"]')).toBeNull();
  const receipt = [...host.querySelectorAll('button')].find((button) => /receipt/i.test(button.textContent ?? ''));
  expect(receipt).toBeDefined();
  await act(async () => receipt?.click());
  expect(document.querySelector('[role="dialog"]')).not.toBeNull();
  for (const label of ['Take photo', 'Choose receipt', 'Upload receipt', 'Save review']) expect(buttons()).not.toContain(label);
});

it.each([['Manual collections', 'Add manual collection'], ['Tip payouts', 'Pay out tips'], ['Fixed assets', 'Add asset']])('Business Viewer has no write controls in %s', async (tab, addLabel) => {
  await render(<FinanceScreen />); await click(tab);
  expect(buttons()).not.toContain(addLabel);
  expect(buttons()).not.toContain('Void');
  expect(buttons()).not.toContain('Correct');
});

it('Business Viewer can inspect partner capital without partner, movement or capital void controls', async () => {
  await render(<FinanceScreen />); await click('Partners');
  expect(host.textContent).toContain('Example partner');
  expect(buttons()).not.toContain('Add partner'); expect(buttons()).not.toContain('Record movement');
  await click('Ledger'); expect(document.querySelector('[role="dialog"]')).not.toBeNull();
  expect(buttons()).not.toContain('Void');
});

it('Business Viewer staff directory shows every assigned role and no identity write controls', async () => {
  await render(<StaffScreen />);
  expect(host.textContent).toContain('Manager, Business Viewer');
  for (const label of ['Add user', 'Edit', 'Password', 'Suspend', 'Delete']) expect(buttons()).not.toContain(label);
  expect(host.textContent).not.toContain('Individual time & attendance');
});

it('an authorized writer explicitly replaces a multi-role account with the displayed Viewer role', async () => {
  mocks.me = { ...viewer, roles: ['owner'], effective_permissions: ['staff.read', 'staff.write'] };
  mocks.listUsers.mockResolvedValue([{ id: 'target', email: 'target@example.test', name: 'Target', phone: null, status: 'active', roles: ['auditor', 'manager'] }]);
  await render(<StaffScreen />); await click('Edit');
  await click('Use only the selected role when saving');
  const form = document.querySelector('[role="dialog"] form');
  await act(async () => { form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(mocks.updateUser).toHaveBeenCalledWith('target', { name: 'Target', phone: null, role_code: 'auditor' });
});

it('Store Staff built-in discounts do not display or resend the legacy supplemental grant', async () => {
  mocks.me = { ...viewer, roles: ['owner'], effective_permissions: ['staff.read', 'staff.write'] };
  mocks.listUsers.mockResolvedValue([{ id: 'target', email: 'target@example.test', name: 'Target', phone: null, status: 'active', roles: ['store_staff'], manual_discount_allowed: true }]);
  await render(<StaffScreen />); await click('Edit');
  const dialog = document.querySelector('[role="dialog"]');
  expect(dialog?.textContent).toContain('manual discounts');
  expect(dialog?.textContent).not.toContain('Allow manual POS discounts');
  expect([...dialog!.querySelectorAll('optgroup[label="Everyday roles"] option')].map((option) => option.getAttribute('value')))
    .toEqual(['store_staff', 'auditor']);
  const form = dialog?.querySelector('form');
  await act(async () => { form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(mocks.updateUser).toHaveBeenCalledWith('target', { name: 'Target', phone: null });
});

it('legacy Manager writes require exact permission and a revoked permission closes the mutation form', async () => {
  mocks.me = { ...viewer, roles: ['manager'], effective_permissions: ['finance.read', 'finance.write'] };
  await render(<FinanceScreen />); await click('Expenses'); await click('Add expense');
  expect(document.querySelector('[role="dialog"]')).not.toBeNull();
  mocks.me = { ...viewer, roles: ['owner'], protected_access: true, effective_permissions: ['finance.read'] };
  await render(<FinanceScreen />);
  expect(document.querySelector('[role="dialog"]')).toBeNull();
  expect(buttons()).not.toContain('Add expense');
  expect(mocks.createExpense).not.toHaveBeenCalled();
});

it('Owner normalization preserves the explicitly selected legacy discount setting', async () => {
  mocks.me = { ...viewer, roles: ['owner'], effective_permissions: ['staff.read', 'staff.write'] };
  mocks.listUsers.mockResolvedValue([{ id: 'target', email: 'target@example.test', name: 'Target', phone: null, status: 'active', roles: ['gaming_cashier', 'manager', 'pos_discount'], manual_discount_allowed: true }]);
  await render(<StaffScreen />); await click('Edit'); await click('Use only the selected role when saving');
  const form = document.querySelector('[role="dialog"] form');
  await act(async () => { form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(mocks.updateUser).toHaveBeenCalledWith('target', { name: 'Target', phone: null, role_code: 'gaming_cashier', manual_discount_allowed: true });
});
