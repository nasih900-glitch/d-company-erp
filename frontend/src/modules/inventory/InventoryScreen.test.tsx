// @vitest-environment happy-dom
import { act, StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import InventoryScreen from './InventoryScreen';
const m = vi.hoisted(() => ({ ingredients: vi.fn(), suppliers: vi.fn(), branches: vi.fn(), post: vi.fn(), success: vi.fn(), error: vi.fn() }));
vi.mock('@/lib/erp-api', () => ({ inventory: { listIngredients: m.ingredients, listSuppliers: m.suppliers, listBranches: m.branches, postGRN: m.post } }));
vi.mock('@/components/ui/Notifications', () => ({ useNotifications: () => ({ success: m.success, error: m.error }) }));
const ingredient = (id: string, name: string) => ({ id, sku: id, name, base_unit: 'g', current_qty: 5, reorder_threshold: 0, reorder_qty: 0, avg_cost_minor: 100 });
const first = ingredient('ingredient-1', 'Existing ingredient'), second = ingredient('ingredient-2', 'Second ingredient');
const supplier = { id: 'supplier-1', name: 'Verified supplier' }, branch = { id: 'branch-1', name: 'Verified branch' };
const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (reason: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
let root: Root, host: HTMLDivElement;
beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks(); m.ingredients.mockReset().mockResolvedValue([first, second]); m.suppliers.mockReset().mockResolvedValue([supplier]); m.branches.mockReset().mockResolvedValue([branch]); m.post.mockReset().mockResolvedValue({ id: 'receipt-1' });
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); document.body.replaceChildren(); vi.restoreAllMocks(); });
async function render(strict = false) { await act(async () => root.render(strict ? <StrictMode><InventoryScreen/></StrictMode> : <InventoryScreen/>)); }
function button(name: string): HTMLButtonElement { const found = [...document.querySelectorAll('button')].find(n => n.getAttribute('aria-label') === name || n.textContent?.trim() === name); if (!found) throw new Error('Missing button ' + name); return found; }
async function click(name: string) { await act(async () => button(name).click()); }
function field(label: string, parent: ParentNode = document): HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement {
  const owner = [...parent.querySelectorAll('label')].find(n => n.querySelector('span')?.textContent === label);
  const input = owner?.querySelector('input,select,textarea') as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
  if (!input) throw new Error('Missing associated field ' + label); return input;
}
async function set(label: string, value: string, parent: ParentNode = document) {
  const node = field(label, parent); await act(async () => { Object.getOwnPropertyDescriptor(Object.getPrototypeOf(node), 'value')!.set!.call(node, value); node.dispatchEvent(new Event(node instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true })); });
}
const lines = () => [...document.querySelectorAll('fieldset')];
async function draft() { await click('Receive stock (GRN)'); await set('Supplier', supplier.id); await set('Supplier invoice no.', 'INV-1'); await set('Notes (optional)', 'Draft remains'); await set('Quantity', '2.50', lines()[0]); await set('Unit cost (rupees/base unit)', '100.50', lines()[0]); await set('Expiry (optional)', '2027-01-02', lines()[0]); await set('Lot code (optional)', 'DRAFT-LOT', lines()[0]); }
async function submit() { await act(async () => document.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))); }
it.each(['suppliers', 'branches'] as const)('initial %s failure is unverified, not empty inventory; named retry verifies all required references', async failed => {
  m[failed].mockRejectedValueOnce(new Error('Required reference unavailable')); await render();
  expect(document.querySelector('[role=alert]')?.textContent).toContain('No verified inventory snapshot');
  expect(host.textContent).not.toContain('Stock value'); expect(host.textContent).not.toContain('No suppliers yet'); expect(button('Receive stock (GRN)').disabled).toBe(true);
  await click('Retry inventory'); expect(host.querySelector('[role=alert]')).toBeNull(); expect(host.textContent).toContain('Existing ingredient'); expect(button('Receive stock (GRN)').disabled).toBe(false);
  expect(m.ingredients).toHaveBeenCalledTimes(2); expect(m.suppliers).toHaveBeenCalledTimes(2); expect(m.branches).toHaveBeenCalledTimes(2);
});
it('failed supplier refresh keeps an atomic stale snapshot and the open draft, blocks submit, and recovers without losing any value', async () => {
  await render(); await draft(); const previous = { qty: field('Quantity', lines()[0]).value, cost: field('Unit cost (rupees/base unit)', lines()[0]).value, lot: field('Lot code (optional)', lines()[0]).value };
  m.ingredients.mockResolvedValueOnce([ingredient('unverified', 'Unverified partial replacement')]); m.suppliers.mockRejectedValueOnce(new Error('Supplier read failed')); m.branches.mockResolvedValueOnce([{ id: 'unverified-branch', name: 'Unverified branch' }]);
  expect(button('Refresh inventory references').closest('[role=dialog]')).not.toBeNull();
  await click('Refresh inventory references'); expect(document.querySelector('[role=alert]')?.textContent).toContain('last verified snapshot'); expect(host.textContent).toContain('Existing ingredient'); expect(host.textContent).not.toContain('Unverified partial replacement'); expect(document.body.textContent).not.toContain('Unverified branch');
  expect(field('Branch').value).toBe(branch.id); expect(field('Supplier').value).toBe(supplier.id); expect(button('Record receipt').disabled).toBe(true);
  await submit(); expect(m.post).not.toHaveBeenCalled(); await click('Retry inventory references');
  expect(document.querySelector('[role=alert]')).toBeNull(); expect(button('Record receipt').disabled).toBe(false); expect(document.querySelector('[role=dialog]')?.textContent).not.toContain('submission is paused');
  expect(field('Quantity', lines()[0]).value).toBe(previous.qty); expect(field('Unit cost (rupees/base unit)', lines()[0]).value).toBe(previous.cost); expect(field('Lot code (optional)', lines()[0]).value).toBe(previous.lot);
  expect(field('Supplier invoice no.').value).toBe('INV-1'); expect(field('Notes (optional)').value).toBe('Draft remains'); expect(field('Expiry (optional)', lines()[0]).value).toBe('2027-01-02');
});
it('branch refresh failure retains its verified branch instead of an empty dropdown', async () => {
  await render(); await draft(); m.branches.mockRejectedValueOnce(new Error('Branch read failed')); await click('Refresh inventory references');
  expect(field('Branch').value).toBe(branch.id); expect(field('Branch').textContent).toContain(branch.name); expect(button('Record receipt').disabled).toBe(true); expect(document.body.textContent).toContain('draft is kept');
});
it.each(['resolve', 'reject'] as const)('an obsolete %s after StrictMode cleanup cannot overwrite the newer verified snapshot', async oldOutcome => {
  const old = deferred<unknown>(); m.ingredients.mockReturnValueOnce(old.promise).mockResolvedValueOnce([ingredient('fresh', 'Newer verified ingredient')]);
  await render(true); expect(host.textContent).toContain('Newer verified ingredient');
  await act(async () => { if (oldOutcome === 'resolve') old.resolve([ingredient('old', 'Obsolete ingredient')]); else old.reject(new Error('Obsolete read failed')); });
  expect(host.textContent).toContain('Newer verified ingredient'); expect(host.textContent).not.toContain('Obsolete ingredient'); expect(host.querySelector('[role=alert]')).toBeNull(); expect(button('Receive stock (GRN)').disabled).toBe(false);
});
it('two grouped labelled lines retain the exact second draft after indexed first-line removal', async () => {
  await render(); await draft(); await click('Add line'); await set('Ingredient', second.id, lines()[1]); await set('Quantity', '3.50', lines()[1]); await set('Unit cost (rupees/base unit)', '20.50', lines()[1]); await set('Expiry (optional)', '2027-02-03', lines()[1]); await set('Lot code (optional)', 'SECOND-LOT', lines()[1]);
  for (const line of lines()) for (const name of ['Ingredient', 'Quantity', 'Unit cost (rupees/base unit)', 'Expiry (optional)', 'Lot code (optional)']) { const control = field(name, line); expect(control.labels?.length).toBe(1); expect(control.labels?.[0].textContent).toContain(name); }
  expect(lines()[1].querySelector('legend')?.textContent).toBe('Line 2'); await click('Remove line 1');
  expect(lines()).toHaveLength(1); expect(lines()[0].querySelector('legend')?.textContent).toBe('Line 1'); expect(button('Remove line 1')).toBeTruthy();
  expect(field('Ingredient', lines()[0]).value).toBe(second.id); expect(field('Quantity', lines()[0]).value).toBe('3.50'); expect(field('Unit cost (rupees/base unit)', lines()[0]).value).toBe('20.50'); expect(field('Expiry (optional)', lines()[0]).value).toBe('2027-02-03'); expect(field('Lot code (optional)', lines()[0]).value).toBe('SECOND-LOT');
  await submit(); expect(m.post).toHaveBeenCalledTimes(1); expect(m.post.mock.calls[0][0]).toEqual({ branch_id: branch.id, supplier_id: supplier.id, supplier_invoice_no: 'INV-1', notes: 'Draft remains', lines: [{ ingredient_id: second.id, qty: 3.5, unit_cost_minor: 2050, expires_at: '2027-02-03T00:00:00.000Z', lot_code: 'SECOND-LOT' }] });
});
it('confirmed GRN write remains confirmed when its separate read-back fails and uses no duplicate posting', async () => {
  await render(); await draft(); m.suppliers.mockRejectedValueOnce(new Error('Readback unavailable')); await submit();
  expect(m.post).toHaveBeenCalledTimes(1); expect(m.success).toHaveBeenCalledWith('The stock receipt was recorded.', { title: 'GRN saved' }); expect(m.error).not.toHaveBeenCalled(); expect(document.querySelector('[role=alert]')?.textContent).toContain('last verified snapshot'); expect(document.querySelector('[role=dialog]')).toBeNull();
});
it('retrying a failed GRN write preserves the existing lifetime key and canonical money payload', async () => {
  await render(); await draft(); m.post.mockRejectedValueOnce(new Error('Transport reply lost')); await submit(); expect(document.querySelector('[role=dialog]')).not.toBeNull(); await submit();
  expect(m.post).toHaveBeenCalledTimes(2); expect(m.post.mock.calls[1]).toEqual(m.post.mock.calls[0]); expect(m.post.mock.calls[0][1]).toMatch(/^grn:/); expect(m.post.mock.calls[0][0].lines[0].unit_cost_minor).toBe(10050);
});
it('actual Inventory subview buttons expose their selected state while switching real content', async () => {
  await render(); expect(button('Ingredients').getAttribute('aria-pressed')).toBe('true'); expect(button('Suppliers').getAttribute('aria-pressed')).toBe('false'); await click('Suppliers');
  expect(button('Suppliers').getAttribute('aria-pressed')).toBe('true'); expect(button('Ingredients').getAttribute('aria-pressed')).toBe('false'); expect(host.textContent).toContain('Verified supplier');
});
