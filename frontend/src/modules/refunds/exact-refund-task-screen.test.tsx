// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { deferred, id, refundTask, scope } from '@/test-fixtures/paid-bill';
const m = vi.hoisted(() => ({ auth: {} as Record<string, unknown>, getRequest: vi.fn(), listOrders: vi.fn(), listRequests: vi.fn(), listShifts: vi.fn(), request: vi.fn() }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => m.auth }));
vi.mock('@/hooks/useOnlineStatus', () => ({ useOnlineStatus: () => true }));
vi.mock('@/components/ui/Notifications', () => ({ useNotifications: () => ({ success: vi.fn(), error: vi.fn() }) }));
vi.mock('@/lib/realtime', () => ({ subscribeRealtime: () => () => {} }));
vi.mock('@/lib/erp-api', () => ({ refunds: m, shifts: { list: m.listShifts }, receipts: { get: vi.fn() }, orders: {} }));
vi.mock('@/lib/paid-bill-api', () => ({ currentPaidEndpoint: () => 'http://localhost:3000/api/v1', capturePaidBillLease: (_scope: unknown, current: () => boolean) => ({ current }) }));
import RefundsScreen from './RefundsScreen';
let root: Root, host: HTMLDivElement;
beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks(); localStorage.clear();
  m.auth = { me: { user_id: id(2), company_id: id(1), branch_id: id(3), protected_access: true, effective_permissions: ['pos.refund'] }, terminalReady: true, terminalId: id(4), terminalOptions: [] };
  m.listOrders.mockResolvedValue([]); m.listRequests.mockResolvedValue([]); m.listShifts.mockResolvedValue([]); m.getRequest.mockResolvedValue(refundTask());
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); document.body.replaceChildren(); localStorage.clear(); });
const url = '/refunds?refund_request_id=' + id(7) + '&receipt_order_id=' + id(5) + '&refund_amount_minor=4500';
async function render() { await act(async () => root.render(<MemoryRouter initialEntries={[url]}><RefundsScreen/></MemoryRouter>)); }
it('loads an exact task outside the latest200 independently and never offers new-refund creation', async () => {
  await render(); expect(m.getRequest).toHaveBeenCalledWith(id(7)); expect(host.textContent).toContain('Existing bill-change refund task');
  expect(host.textContent).toContain('45.00'); expect(host.textContent).not.toContain('Find a paid order'); expect(host.textContent).not.toContain('Request refund'); expect(m.request).not.toHaveBeenCalled();
});
it.each(['settled', 'withdrawn'] as const)('keeps old completed %s task visible even when absent from history windows', async status => {
  m.getRequest.mockResolvedValue({ ...refundTask(), status, settled_at: status === 'settled' ? '2026-11-13T00:00:00Z' : null, settled_by: status === 'settled' ? id(2) : null, refund_id: status === 'settled' ? id(30) : null, withdrawn_at: status === 'withdrawn' ? '2026-11-13T00:00:00Z' : null, withdrawn_by: status === 'withdrawn' ? id(2) : null });
  await render(); expect(host.textContent).toContain(id(7)); expect(host.textContent).not.toContain('Find a paid order'); expect(m.request).not.toHaveBeenCalled();
});
it.each(['missing', 'scope', 'amount'])('retains exact-target refusal on %s and never falls back to receipt creation', async failure => {
  if (failure === 'missing') m.getRequest.mockRejectedValue(new Error('Task404 unavailable'));
  else m.getRequest.mockResolvedValue({ ...refundTask(), ...(failure === 'scope' ? { terminal_id: id(99) } : { amount_minor: 1 }) });
  await render(); expect(host.querySelector('[role=alert]')).not.toBeNull(); expect(host.textContent).not.toContain('Find a paid order'); expect(m.request).not.toHaveBeenCalled();
});
it('does not publish a late exact task into a changed authenticated workspace', async () => {
  const wait = deferred<unknown>(); m.getRequest.mockReturnValueOnce(wait.promise).mockRejectedValueOnce(new Error('New account task lookup denied')); await render();
  m.auth = { ...m.auth, me: { user_id: id(90), company_id: scope.companyId, branch_id: scope.branchId, effective_permissions: ['pos.refund'] } };
  await render(); await act(async () => wait.resolve(refundTask())); expect(host.textContent).not.toContain('45.00'); expect(m.request).not.toHaveBeenCalled();
});
