// @vitest-environment happy-dom
import { act, StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '@/modules/auth/AuthContext';
import type { PaymentTenderPreviewDTO, TenderReceiptDTO } from '@/lib/erp-api';
import { clearOwnerWorkspaces, ownerScope } from '@/lib/owner-workspace';
import TenderCorrections from './TenderCorrections';
import { TenderJournal } from './tender-journal';
const mocks = vi.hoisted(() => ({
  me: null as Me | null,
  unlock: vi.fn(),
  history: vi.fn(),
  preparePayment: vi.fn(),
  prepareDaily: vi.fn(),
  commitPayment: vi.fn(),
  commitDaily: vi.fn(),
}));
vi.mock('@/modules/auth/AuthContext', () => ({
  useAuth: () => ({ me: mocks.me }),
}));
vi.mock('@/lib/erp-api', () => ({ tenderCorrections: mocks }));
const identity = {
  user_id: 'owner-1',
  company_id: 'company-1',
  branch_id: 'branch-1',
  audit_access: true,
  effective_permissions: ['finance.tender.correct'],
} as Me;
const preview = {
  payment_id: 'payment-1',
  order_id: 'order-1',
  invoice_no: 'SYN-0001',
  branch_id: 'branch-1',
  business_date: '2026-10-18',
  business_timezone: 'Asia/Kolkata',
  source_fingerprint: 'ab'.repeat(32),
  amount_minor: 27000,
  original_method: 'cash',
  effective_method: 'upi',
  before_cash_minor: 33000,
  before_upi_minor: 8000,
  after_cash_minor: 6000,
  after_upi_minor: 35000,
  total_change_minor: 0,
} as PaymentTenderPreviewDTO;
let root: Root, host: HTMLDivElement;
const env = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT?: boolean;
};
beforeEach(() => {
  env.IS_REACT_ACT_ENVIRONMENT = true;
  clearOwnerWorkspaces();
  const data = new Map<string, string>();
  vi.stubGlobal('navigator', {
    locks: {
      request: async (_name: string, run: () => Promise<unknown>) => run(),
    },
  });
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, value);
    },
    removeItem: (key: string) => {
      data.delete(key);
    },
    clear: () => data.clear(),
    get length() {
      return data.size;
    },
  });
  mocks.me = identity;
  vi.clearAllMocks();
  mocks.unlock.mockResolvedValue({
    token: 'ephemeral-owner-token',
    history_token: 'ephemeral-read-only-history-token',
    expires_in: 300,
  });
  mocks.history.mockResolvedValue([]);
  mocks.preparePayment.mockResolvedValue(preview);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  delete env.IS_REACT_ACT_ENVIRONMENT;
});
async function render() {
  await act(async () =>
    root.render(
      <StrictMode>
        <MemoryRouter>
          <TenderCorrections paymentId="payment-1" />
        </MemoryRouter>
      </StrictMode>,
    ),
  );
}
function button(text: string) {
  const found = [...host.querySelectorAll('button')].find((node) =>
    node.textContent?.includes(text),
  );
  if (!found) throw new Error('Missing button ' + text);
  return found;
}
async function click(text: string) {
  await act(async () => button(text).click());
}
async function fill(selector: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(selector)!;
  if (!input) throw new Error('Missing input ' + selector);
  await act(async () => {
    Object.getOwnPropertyDescriptor(
      HTMLInputElement.prototype,
      'value',
    )!.set!.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
}
async function unlock() {
  await fill('input[type="password"]', 'synthetic-owner-password');
  await act(async () =>
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })),
  );
}
async function prepareAndConfirm() {
  await click('Prepare source');
  const inputs = host.querySelectorAll<HTMLInputElement>(
    'input:not([type="password"]):not([type="checkbox"])',
  );
  await fill(
    'input[maxlength="500"]',
    'Synthetic staff confirmed payment rail',
  );
  await act(async () =>
    host.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click(),
  );
  expect(inputs.length).toBeGreaterThan(0);
}
describe('Protected tender workflow in StrictMode', () => {
  it.each(['getItem', 'removeItem'] as const)('keeps rejected exact evidence when storage %s fails during reviewed discard', async (operation) => {
    mocks.commitPayment.mockRejectedValue({status:409, code:'conflict', message:'Source changed'});
    await render(); await unlock(); await prepareAndConfirm(); await click('Confirm reviewed correction');
    const saved = new TenderJournal(identity, localStorage).read();
    expect(saved).not.toBeNull();
    const failed = vi.spyOn(localStorage, operation).mockImplementation(() => { throw new Error('Storage access denied; saved request kept'); });
    await click('Reviewed rejection');
    expect(host.textContent).toContain('Storage access denied');
    expect(host.textContent).toContain('Recover saved result');
    expect(host.textContent).toContain(saved!.key);
    expect(mocks.commitPayment).toHaveBeenCalledOnce();
    expect(mocks.unlock).toHaveBeenCalledOnce();
    failed.mockRestore();
    expect(new TenderJournal(identity, localStorage).read()).toEqual(saved);
  });

  it('finishes unlock and source preview after effect replay and never stores the password or token', async () => {
    await render();
    await unlock();
    expect(host.textContent).toContain('Prepare source preview');
    await click('Prepare source');
    expect(host.textContent).toContain('Complete Cash leg ₹270.00');
    expect(host.textContent).toContain('Bill total change ₹0.00');
    expect(localStorage.length).toBe(0);
    expect(mocks.unlock).toHaveBeenCalledTimes(1);
    expect(mocks.history.mock.calls[0][1]).toBe(
      'ephemeral-read-only-history-token',
    );
    expect(mocks.commitPayment).not.toHaveBeenCalled();
  });
  it('retains an interrupted exact command across remount, requires reauthentication and replays the same body/key', async () => {
    mocks.commitPayment.mockRejectedValueOnce(
      Object.assign(new Error('Connection interrupted'), { status: 503 }),
    );
    await render();
    await unlock();
    await prepareAndConfirm();
    await click('Confirm reviewed correction');
    expect(host.textContent).toContain('Saved request needs its final receipt');
    const [body, key] = mocks.commitPayment.mock.calls[0];
    expect(new TenderJournal(identity, localStorage).read()?.key).toBe(key);
    await act(async () => root.render(null));
    await render();
    expect(mocks.commitPayment).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('Confirm owner access');
    mocks.commitPayment.mockResolvedValueOnce({
      id: 'receipt-1',
      receipt_reference: 'COR-SYN-0001',
      operation: 'payment_method_corrected',
      company_id: identity.company_id,
      branch_id: identity.branch_id,
      recorded_by: identity.user_id,
      payment_id: 'payment-1',
      source_fingerprint: body.source_fingerprint,
      reason: body.reason,
      idempotency_key: key,
      financial_effect: true,
      business_date: '2026-10-18',
      business_timezone: 'Asia/Kolkata',
      recorded_at: '2026-10-19T12:00:00Z',
      cash_delta_minor: -27000,
      upi_delta_minor: 27000,
      total_difference_minor: 0,
    } as TenderReceiptDTO);
    await unlock();
    await click('Recover saved result');
    expect(mocks.commitPayment.mock.calls[1].slice(0, 2)).toEqual([body, key]);
    expect(new TenderJournal(identity, localStorage).read()).toBeNull();
    expect(host.textContent).toContain('Correction recorded');
  });
  it('does not forget an uncertain action when the owner unlock expires', async () => {
    vi.useFakeTimers();
    const journal = new TenderJournal(identity, localStorage);
    journal.save({
      version: 1,
      scope: ownerScope(identity),
      key: 'tender:web:uncertain',
      operation: 'payment',
      body: {
        payment_id: 'payment-1',
        source_fingerprint: 'ab'.repeat(32),
        reason: 'Uncertain original response',
      },
    });
    await render();
    await unlock();
    await act(async () => vi.advanceTimersByTime(300001));
    expect(host.textContent).toContain('Confirm owner access');
    expect(journal.read()?.key).toBe('tender:web:uncertain');
    expect(mocks.commitPayment).not.toHaveBeenCalled();
  });
  it('refuses correction UI without the declared permission even for an Audit owner', async () => {
    mocks.me = { ...identity, effective_permissions: [] };
    await render();
    expect(host.textContent).toBe('');
    expect(mocks.unlock).not.toHaveBeenCalled();
  });
  it('ignores a delayed owner response after logout/account transition', async () => {
    let resolve!: (value: { token: string; expires_in: number }) => void;
    mocks.unlock.mockImplementation(
      () =>
        new Promise((next) => {
          resolve = next;
        }),
    );
    await render();
    await fill('input[type="password"]', 'synthetic-owner-password');
    act(() =>
      host
        .querySelector('form')!
        .dispatchEvent(
          new Event('submit', { bubbles: true, cancelable: true }),
        ),
    );
    clearOwnerWorkspaces();
    mocks.me = null;
    await act(async () => root.render(null));
    await act(async () =>
      resolve({ token: 'old-account-token', expires_in: 300 }),
    );
    expect(mocks.history).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
  });
  it('never adopts another tab bill request from this bill Confirm action', async () => {
    await render();
    await unlock();
    await prepareAndConfirm();
    const other = {
      version: 1 as const,
      scope: ownerScope(identity),
      key: 'tender:web:other-tab',
      operation: 'payment' as const,
      body: {
        payment_id: 'different-bill-payment',
        source_fingerprint: 'cd'.repeat(32),
        reason: 'Other tab reviewed its own source',
      },
    };
    new TenderJournal(identity, localStorage).save(other);
    await click('Confirm reviewed correction');
    expect(mocks.commitPayment).not.toHaveBeenCalled();
    expect(host.textContent).toContain('different-bill-payment');
    expect(host.textContent).toContain('Recover saved result');
    expect(new TenderJournal(identity, localStorage).read()).toEqual(other);
  });
  it('keeps an in-progress or unknown conflict uncertain instead of allowing a fresh command', async () => {
    mocks.commitPayment.mockRejectedValue(
      Object.assign(new Error('Request is still committing'), {
        status: 409,
        code: 'idempotency_in_progress',
      }),
    );
    await render();
    await unlock();
    await prepareAndConfirm();
    await click('Confirm reviewed correction');
    expect(new TenderJournal(identity, localStorage).read()).not.toBeNull();
    expect(host.textContent).toContain('Recover saved result');
    expect(host.textContent).not.toContain(
      'Reviewed rejection — prepare fresh source',
    );
  });
  it('replays its retained exact pending command after another tab recovered and cleared shared storage', async () => {
    mocks.commitPayment.mockRejectedValueOnce(
      Object.assign(new Error('Reply lost'), { status: 503 }),
    );
    await render();
    await unlock();
    await prepareAndConfirm();
    await click('Confirm reviewed correction');
    const [body, key] = mocks.commitPayment.mock.calls[0];
    const otherTab = new TenderJournal(identity, localStorage);
    const saved = otherTab.read()!;
    otherTab.clear(saved);
    mocks.commitPayment.mockResolvedValueOnce({
      id: 'receipt-recovered',
      receipt_reference: 'COR-RECOVERED',
      operation: 'payment_method_corrected',
      company_id: identity.company_id,
      branch_id: identity.branch_id,
      recorded_by: identity.user_id,
      payment_id: body.payment_id,
      source_fingerprint: body.source_fingerprint,
      reason: body.reason,
      evidence_reference: body.evidence_reference,
      idempotency_key: key,
      financial_effect: true,
      business_date: '2026-10-18',
      business_timezone: 'Asia/Kolkata',
      recorded_at: '2026-10-19T12:00:00Z',
    } as TenderReceiptDTO);
    await click('Recover saved result');
    expect(mocks.commitPayment.mock.calls[1].slice(0, 2)).toEqual([body, key]);
    expect(host.textContent).toContain('Correction recorded');
    expect(otherTab.read()).toBeNull();
  });
});
