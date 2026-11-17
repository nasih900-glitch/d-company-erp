// @vitest-environment happy-dom
import { act, Suspense } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Me } from '@/modules/auth/AuthContext';
import PosWorkspace from './PosWorkspace';
import LivePOSScreen from './LivePOSScreen';
import { clearOwnerWorkspaces } from '@/lib/owner-workspace';
import type { OrderDTO } from '@/lib/erp-api';
import { normalizePosRetryDraft, type PosRetryDraft } from '@/lib/retry-drafts';

const mocks = vi.hoisted(() => ({
  me: null as Me | null, orders: vi.fn(), receipts: vi.fn(), create: vi.fn(), claim: vi.fn(), publish: vi.fn(), pay: vi.fn(), bundle: vi.fn(), void: vi.fn(), discount: vi.fn(), release: vi.fn(), getOrder: vi.fn(),
}));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({ me: mocks.me, demo: false, terminalId: 'terminal', terminalReady: true, terminalOptions: [] }) }));
vi.mock('@/components/ui/Notifications', () => ({ useNotifications: () => ({ success: vi.fn(), info: vi.fn(), error: vi.fn(), warning: vi.fn() }) }));
vi.mock('@/lib/realtime', () => ({ subscribeRealtime: () => () => undefined }));
vi.mock('@/hooks/useRealtimeRefresh', () => ({ useRealtimeRefresh: () => undefined }));
vi.mock('@/lib/erp-api', () => ({
  menu: {
    items: async () => [{ id: 'drink', name: 'Test Cola', sku: 'COLA', type: 'drink', category_id: 'drinks', is_available: true, base_price_minor: 1000, tax_rate: 0, hsn_code: null, price_includes_tax: false, variants: [], modifier_groups: [] }],
    categories: async () => [{ id: 'drinks', name: 'Drinks', is_gaming_centre_catalog: true }],
  },
  shifts: { list: async () => [{ id: 'shift', company_id: 'company', branch_id: 'branch', terminal_id: 'terminal', status: 'open', opened_by: 'actor', opened_at: '2026-11-07T08:00:00Z', opening_float_minor: 0, pos_collections_minor: 0, membership_collections_minor: 0, gross_collections_minor: 0 }] },
  orders: { list: mocks.orders, get: mocks.getOrder },
  receipts: { list: mocks.receipts },
  gaming: { listStations: async () => [] },
  staff: { listUsers: async () => [] },
  customers: {}, memberships: {},
  captureCustomerDirectoryEvidence: vi.fn(),
  settings: { getLoyaltyProgram: async () => ({ company_id: 'company', revision: 1, rules: { minor_per_point: 1 } }) },
  pos: {
    createOrder: mocks.create, claimCheckout: mocks.claim, publishCheckout: mocks.publish, recordPayment: mocks.pay,
    recordPaymentBundle: mocks.bundle, voidOrder: mocks.void, applyDiscount: mocks.discount, releaseCheckout: mocks.release, getOrder: mocks.getOrder,
    receiptBusiness: async () => ({ brand_name: 'Test Shop', supplier_name: 'Test Shop', branch_name: 'Shop', address: null, gstin: null, gst_registration_type: 'unregistered', is_composition: false, fssai_license_no: null, trade_license_no: null, state_code: '32', timezone: 'Asia/Kolkata', upi_vpa: null }),
  },
}));

let root: Root, host: HTMLDivElement;
const identity: Me = {
  user_id: 'actor', company_id: 'company', branch_id: 'branch', name: 'Staff', email: 'staff@example.test',
  roles: ['auditor'], protected_access: false, audit_access: false, release_control_access: false,
  accessible_modules: ['pos', 'gaming'], effective_permissions: ['pos.read', 'gaming.read'],
};
beforeEach(() => {
  vi.clearAllMocks(); clearOwnerWorkspaces(); localStorage.clear(); localStorage.setItem('terminal_id', 'terminal');
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  Object.defineProperty(navigator, 'locks', { configurable: true, value: {
    request: (_name: string, _options: unknown, callback: (lock: { name: string }) => Promise<unknown>) => callback({ name: _name }),
  } });
  mocks.me = identity;
  mocks.orders.mockResolvedValue([]); mocks.receipts.mockResolvedValue({ items: [], next_cursor: null, has_more: false });
  mocks.release.mockResolvedValue({});
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); localStorage.clear(); });
async function render(node: React.ReactNode) {
  await act(async () => { root.render(<MemoryRouter initialEntries={['/pos?resume=held']}><Suspense fallback={<p>Loading workspace</p>}>{node}</Suspense></MemoryRouter>); });
}
async function clickText(text: string) {
  const button = [...document.querySelectorAll('button')].find((element) => element.textContent?.trim().includes(text));
  expect(button, text).toBeDefined();
  await act(async () => button?.click());
}
function expectNoMutation() {
  for (const mutation of [mocks.create, mocks.claim, mocks.publish, mocks.pay, mocks.bundle, mocks.void]) expect(mutation).not.toHaveBeenCalled();
}

it('Viewer route mounts receipt operations and never mounts checkout, claim or payment effects', async () => {
  preparedBill(false);
  mocks.me = identity;
  await import('./OrdersAndShiftsScreen');
  await render(<PosWorkspace />);
  expect(host.textContent).toContain('Operations');
  expect(host.textContent).not.toContain('POS · Live');
  expect(mocks.receipts).toHaveBeenCalled();
  expectNoMutation();
});

it('explicit empty permissions cannot mount live checkout despite an Owner title and protected flag', async () => {
  mocks.me = { ...identity, roles: ['owner'], protected_access: true, effective_permissions: [] };
  await import('./OrdersAndShiftsScreen'); await render(<PosWorkspace />);
  expect(host.textContent).not.toContain('POS · Live');
  expectNoMutation();
});

it('Store Staff live POS can build a sale and edit manual discounts while queued POS void is absent', async () => {
  mocks.me = { ...identity, roles: ['store_staff'], effective_permissions: ['pos.read', 'pos.write', 'pos.discount.large', 'pos.shift.open', 'pos.shift.close', 'gaming.read', 'gaming.write', 'menu.read'] };
  mocks.orders.mockResolvedValue([{ id: 'held', status: 'held', source_label: 'Gaming table', total_minor: 1000, items_count: 1, created_at: new Date().toISOString(), held_at: new Date().toISOString() }]);
  await render(<LivePOSScreen />);
  expect(host.textContent).toContain('POS · Live');
  expect(host.textContent).not.toContain('NaN');
  await clickText('Test Cola');
  expect(document.querySelector('[aria-label="Increase Test Cola quantity"]')).not.toBeNull();
  expect(document.querySelector('[aria-label="Custom discount amount in rupees"]')).not.toBeNull();
  await clickText('Incoming orders');
  expect(host.textContent).toContain('Gaming table');
  expect(document.querySelector('button[title="Void this queued order with a reason"]')).toBeNull();
  expect(mocks.void).not.toHaveBeenCalled();
});

function preparedBill(held: boolean) {
  mocks.me = { ...identity, roles: ['store_staff'], effective_permissions: ['pos.read', 'pos.write', 'pos.discount.large', 'pos.shift.open', 'pos.shift.close', 'gaming.read', 'gaming.write', 'menu.read'] };
  const order: OrderDTO = {
    id: 'prepared', invoice_no: null, fiscal_year: null, status: 'held', type: 'takeaway', table_id: null, source_label: 'Prepared test bill',
    subtotal_minor: 1000, discount_minor: 0, manual_discount_minor: 0, points_redeemed_minor: 0, points_redeemed: 0,
    cgst_minor: 0, sgst_minor: 0, igst_minor: 0, cess_minor: 0, tax_minor: 0, round_off_minor: 0, tip_minor: 0,
    total_minor: 1000, paid_minor: 0, due_minor: 1000, free_gaming_minutes_applied: 0, free_hookah_count_applied: 0,
    delivery_via: null, place_of_supply_state_code: '32', customer_name: null, customer_phone: null, customer_gstin: null, customer_state_code: null,
    opened_at: new Date().toISOString(), closed_at: null, invoice_issued_at: null, held_at: new Date().toISOString(), checkout_version: 1,
    lines: [{ menu_item_id: 'drink', variant_id: null, modifiers: null, name: 'Test Cola', sku: 'COLA', hsn_or_sac: '2202', qty: 1,
      unit_price_minor: 1000, line_total_minor: 1000, taxable_value_minor: 1000, tax_rate: 0, cgst_minor: 0, sgst_minor: 0, igst_minor: 0, note: null }],
  };
  mocks.getOrder.mockResolvedValue(order);
  mocks.claim.mockResolvedValue({ order_id: order.id, claim_token: 'fresh-claim', expires_at: new Date(Date.now() + 600_000).toISOString(), order_total_minor: 1000, due_minor: 1000, paid_minor: 0, order_version: 1 });
  const snapshot = { shiftId: 'shift', cart: [], orderType: 'takeaway' as const, deliveryVia: 'inhouse' as const, deliveryStateCode: '32', customerName: '', customerPhone: '', customerDirectoryEvidenceCaptured: true as const };
  const draft: PosRetryDraft = {
    version: 2, shiftId: 'shift', cart: [], orderType: 'takeaway', deliveryVia: 'inhouse', deliveryStateCode: '32', customerName: '', customerPhone: '',
    resumingOrderId: held ? order.id : undefined,
    retry: { key: 'saved-attempt', phase: 'awaiting_payment', paymentMethod: 'card', pendingOrderId: order.id, resumingOrderId: held ? order.id : undefined,
      orderTotalMinor: 1000, paymentAmountMinor: 1000, checkoutClaimRequired: true,
      checkoutClaimToken: 'saved-claim', checkoutClaimOrderVersion: 1, checkoutClaimExpiresAt: new Date(Date.now() + 600_000).toISOString(), snapshot },
  };
  expect(normalizePosRetryDraft(draft)?.retry?.key).toBe('saved-attempt');
  localStorage.setItem('pos-draft:company:branch:actor:terminal', JSON.stringify(draft));
}

it('Store Staff can cancel a held payment by releasing its claim without POS void authority', async () => {
  preparedBill(true); await render(<LivePOSScreen />);
  expect(document.body.textContent).toContain('Recover checkout');
  await clickText('No payment · Cancel');
  await clickText('Cancel bill');
  expect(mocks.release).toHaveBeenCalledWith('prepared', 'fresh-claim');
  expect(mocks.void).not.toHaveBeenCalled();
  expect(mocks.pay).not.toHaveBeenCalled();
  expect(document.querySelector('[role="dialog"]')).toBeNull();
});

it('discarding a saved direct bill requires Owner review and preserves the attempt for Store Staff', async () => {
  preparedBill(false); await render(<LivePOSScreen />);
  expect(document.body.textContent).toContain('Recover checkout');
  expect(document.querySelector('[role="dialog"]')?.textContent).toContain('An Owner must review it to discard the unpaid bill');
  expect([...document.querySelectorAll('button')].some((button) => /No payment · Cancel|Cancel bill|Cancel prepared bill/.test(button.textContent ?? ''))).toBe(false);
  expect(mocks.void).not.toHaveBeenCalled();
  expect(mocks.pay).not.toHaveBeenCalled();
  const retained = JSON.parse(localStorage.getItem('pos-draft:company:branch:actor:terminal') ?? '{}') as PosRetryDraft;
  expect(retained.retry?.key).toBe('saved-attempt');
  expect(retained.retry?.pendingOrderId).toBe('prepared');
});
