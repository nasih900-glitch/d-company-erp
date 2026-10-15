// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { customers } from '@/lib/erp-api';
import { GamingCustomerPicker } from './GamingCustomerPicker';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
afterEach(() => vi.restoreAllMocks());

describe('Optional inline Gaming customer details', () => {
  it('keeps guest starts anonymous and disclosure free of customer writes', () => {
    const list = vi.spyOn(customers, 'list');
    const upsert = vi.spyOn(customers, 'upsert');
    const onSelect = vi.fn();
    const container = document.createElement('div');
    const root = createRoot(container);
    try {
      act(() => root.render(<GamingCustomerPicker compact stationId="station-1" disabled={false}
        name="" phone="" onSelect={onSelect} onNameChange={vi.fn()} onPhoneChange={vi.fn()} />));
      const details = container.querySelector('details');
      expect(details?.open).toBe(false);
      expect(container.querySelector('summary')?.textContent).toContain('Customer (optional)');
      expect(container.querySelector('[required]')).toBeNull();
      // The native disclosure changes visibility only; it must never resolve or save an identity.
      if (details) details.open = true;
      expect(container.querySelector('input[type="search"]')).not.toBeNull();
      expect(list).not.toHaveBeenCalled();
      expect(upsert).not.toHaveBeenCalled();
      expect(onSelect).not.toHaveBeenCalled();
    } finally {
      act(() => root.unmount());
    }
  });

  it('keeps the explicit late-guest flow expanded and personal details optional', () => {
    const container = document.createElement('div');
    const root = createRoot(container);
    try {
      act(() => root.render(<GamingCustomerPicker stationId="station-1" saveContext="join" disabled={false}
        name="" phone="" onSelect={vi.fn()} onNameChange={vi.fn()} onPhoneChange={vi.fn()} />));
      expect(container.querySelector('details')).toBeNull();
      const guest = Array.from(container.querySelectorAll('button')).find(button => button.textContent === 'Guest player');
      act(() => guest?.click());
      expect(container.querySelector('input[type="tel"]')).toBeNull();
      expect(container.querySelector('[required]')).toBeNull();
      expect(container.textContent).toContain('No personal details are required');
    } finally {
      act(() => root.unmount());
    }
  });
});
