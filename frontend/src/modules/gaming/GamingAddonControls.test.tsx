// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { SessionAddonPickerModal } from './GamingAddonControls';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let root: Root | null = null;
let container: HTMLDivElement | null = null;

afterEach(() => {
  if (root) act(() => root?.unmount());
  container?.remove();
  root = null;
  container = null;
});

describe('Gaming add-on review', () => {
  it('requires a review step before creating the immutable session item', () => {
    container = document.createElement('div');
    document.body.append(container);
    root = createRoot(container);
    const onSubmit = vi.fn();
    const item = {
      id: 'drink-1', category_id: 'drinks', sku: 'COLA', name: 'Cold can', type: 'drink',
      base_price_minor: 1_200, tax_rate: 0.18, hsn_code: null, price_includes_tax: true,
      is_available: true, variants: [], modifier_groups: [],
    };
    act(() => root?.render(
      <SessionAddonPickerModal
        stationName="PS5 2"
        sessionId="session-12345678"
        items={[item]}
        attempt={null}
        busy={false}
        requestError={null}
        onSubmit={onSubmit}
        onClose={() => undefined}
      />,
    ));

    const review = Array.from(container.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Review item'));
    expect(review).toBeDefined();
    expect(review?.disabled).toBe(true);
    const itemChoice = Array.from(container.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Cold can'));
    expect(itemChoice).toBeDefined();
    act(() => itemChoice?.click());
    expect(review?.disabled).toBe(false);
    act(() => review?.click());
    expect(onSubmit).not.toHaveBeenCalled();
    expect(container.textContent).toContain('Review before adding');
    expect(container.textContent).toContain('PS5 2');
    expect(container.textContent).toContain('session-12345678');
    expect(container.textContent).toContain('1 × ₹12.00 unit');

    // A background catalogue refresh cannot silently re-rate the reviewed
    // immutable draft. The server will reject stale pricing and require a new review.
    act(() => root?.render(
      <SessionAddonPickerModal
        stationName="PS5 2" sessionId="session-12345678"
        items={[{ ...item, base_price_minor: 1_500 }]}
        attempt={null} busy={false} requestError={null}
        onSubmit={onSubmit} onClose={() => undefined}
      />,
    ));
    expect(container.textContent).toContain('1 × ₹12.00 unit');

    const confirm = Array.from(container.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Confirm add to session'));
    act(() => confirm?.click());
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      menu_item_id: 'drink-1', qty: 1, expected_unit_price_minor: 1_200,
    }));
  });

  it('shows and retries the exact uncertain receipt rather than the refreshed catalogue price', () => {
    container = document.createElement('div');
    document.body.append(container);
    root = createRoot(container);
    const onSubmit = vi.fn();
    const body = {
      client_line_id: 'line-1', menu_item_id: 'drink-1', variant_id: null,
      modifiers: [], qty: 2, expected_unit_price_minor: 1_200, note: null,
    };
    act(() => root?.render(
      <SessionAddonPickerModal
        stationName="PS5 2" sessionId="session-12345678"
        items={[{
          id: 'drink-1', category_id: 'drinks', sku: 'COLA', name: 'Cold can', type: 'drink',
          base_price_minor: 1_500, tax_rate: 0.18, hsn_code: null, price_includes_tax: true,
          is_available: true, variants: [], modifier_groups: [],
        }]}
        attempt={{ sessionId: 'session-12345678', idempotencyKey: 'gaming-addon-add:1', body }}
        busy={false} requestError="Response not confirmed"
        onSubmit={onSubmit} onClose={() => undefined}
      />,
    ));
    expect(container.textContent).toContain('2 × ₹12.00 unit');
    expect(container.textContent).toContain('₹24.00');
    const retry = Array.from(container.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Retry exact item'));
    act(() => retry?.click());
    expect(onSubmit).toHaveBeenCalledWith(body);
  });
});
