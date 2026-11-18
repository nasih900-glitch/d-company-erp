// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { pos, type OrderDTO } from '@/lib/erp-api';
import RetainedMistakeCorrectionDialog from './RetainedMistakeCorrectionDialog';

vi.mock('@/lib/erp-api', () => ({ pos: {
  getOrder: vi.fn(), claimCheckout: vi.fn(), reviewRetainedMistake: vi.fn(),
  correctRetainedMistake: vi.fn(), releaseCheckout: vi.fn(),
} }));
const order = {
  id: 'bill', status: 'held', type: 'session', checkout_version: 3, total_minor: 8000,
  source_label: 'Retained Gaming bill',
  lines: [{ id: 'line', name: 'Cold can', qty: 1, line_total_minor: 8000 }],
  gaming_void_provenance: { original_receipt_id: 'original', gaming_session_id: 'session',
    financial_evidence_locked: true, mistake_correction_available: true },
} as OrderDTO;
const evidence = { order_id: 'bill', original_receipt_id: 'original', gaming_session_id: 'session',
  expected_checkout_version: 3, review_hash: 'a'.repeat(64), total_minor: 8000, item_count: 1 };
let root: Root;
const corrected = vi.fn();
const closed = vi.fn();
function button(label: string): HTMLButtonElement {
  return [...document.querySelectorAll('button')].find((node) => node.textContent === label)!;
}
async function render() {
  await act(async () => root.render(<RetainedMistakeCorrectionDialog order={order} onClose={closed} onCorrected={corrected} />));
}
async function attest() {
  await act(async () => {
    const reason = document.querySelector('textarea')!;
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(reason, 'All entered by mistake, none supplied');
    reason.dispatchEvent(new Event('input', { bubbles: true }));
    document.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
  });
}
beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  root = createRoot(document.body.appendChild(document.createElement('div')));
  vi.mocked(pos.getOrder).mockResolvedValue(order);
  vi.mocked(pos.claimCheckout).mockResolvedValue({ order_id: 'bill', claim_token: 'secret-claim',
    order_version: 3, order_total_minor: 8000, paid_minor: 0 } as Awaited<ReturnType<typeof pos.claimCheckout>>);
  vi.mocked(pos.reviewRetainedMistake).mockResolvedValue(evidence);
  vi.mocked(pos.releaseCheckout).mockResolvedValue(undefined);
  vi.mocked(pos.correctRetainedMistake).mockResolvedValue({ correction_id: 'correction', order_id: 'bill',
    original_receipt_id: 'original', gaming_session_id: 'session', order_status: 'void' });
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  vi.resetAllMocks();
});

describe('Owner retained mistake correction', () => {
  it('requires exact reviewed items, reason and explicit not-supplied attestation', async () => {
    await render();
    expect(document.body.textContent).toContain('1 × Cold can');
    expect(button('Cancel mistaken items').disabled).toBe(true);
    await attest();
    expect(button('Cancel mistaken items').disabled).toBe(false);
    await act(async () => button('Cancel mistaken items').click());
    expect(pos.correctRetainedMistake).toHaveBeenCalledWith('bill', {
      original_receipt_id: 'original', expected_checkout_version: 3, review_hash: 'a'.repeat(64),
      all_items_mistaken_not_supplied: true, reason: 'All entered by mistake, none supplied',
    }, 'secret-claim', expect.any(String));
    expect(corrected).toHaveBeenCalledWith('bill');
  });

  it('freezes and retries the exact command after a lost response', async () => {
    vi.mocked(pos.correctRetainedMistake).mockRejectedValueOnce(new Error('Connection lost'));
    await render();
    await attest();
    await act(async () => button('Cancel mistaken items').click());
    expect(document.querySelector('textarea')!.disabled).toBe(true);
    expect(document.querySelector<HTMLInputElement>('input[type="checkbox"]')!.disabled).toBe(true);
    expect(corrected).not.toHaveBeenCalled();
    await act(async () => button('Retry same cancellation').click());
    const calls = vi.mocked(pos.correctRetainedMistake).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[1]).toEqual(calls[0]);
    expect(corrected).toHaveBeenCalledOnce();
  });

  it('rejects a changed checkout review before it can send a correction', async () => {
    vi.mocked(pos.reviewRetainedMistake).mockResolvedValue({ ...evidence, expected_checkout_version: 4 });
    await render();
    expect(document.body.textContent).toContain('changed during review');
    expect(button('Cancel mistaken items').disabled).toBe(true);
    expect(pos.correctRetainedMistake).not.toHaveBeenCalled();
  });

  it('treats an unverified successful response as unresolved and keeps the same retry', async () => {
    vi.mocked(pos.correctRetainedMistake).mockResolvedValueOnce({ correction_id: 'correction', order_id: 'other-bill',
      original_receipt_id: 'original', gaming_session_id: 'session', order_status: 'void' });
    await render();
    await attest();
    await act(async () => button('Cancel mistaken items').click());
    expect(document.querySelector('[role="alert"]')!.textContent).toContain('result is unresolved');
    expect(document.body.textContent).not.toContain('Nothing else was changed');
    expect(corrected).not.toHaveBeenCalled();
    expect(button('Close')).toBeDefined();
    await act(async () => button('Retry same cancellation').click());
    const calls = vi.mocked(pos.correctRetainedMistake).mock.calls;
    expect(calls[1]).toEqual(calls[0]);
    expect(corrected).toHaveBeenCalledOnce();
  });

});
