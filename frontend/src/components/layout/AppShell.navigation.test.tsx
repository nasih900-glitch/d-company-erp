// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter, NavLink, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { navigationTarget } from './AppShell';

function LocationProbe() {
  const location = useLocation();
  return <output data-location>{location.pathname}{location.search}</output>;
}

describe('AppShell operations navigation', () => {
  it('renders and follows the known Shift destination with its query intact', () => {
    const container = document.createElement('div');
    document.body.append(container);
    const root = createRoot(container);
    act(() => root.render(
      <MemoryRouter initialEntries={['/gaming']}>
        <NavLink to={navigationTarget({ to: '/operations?tab=shifts' })}>Shift</NavLink>
        <LocationProbe />
      </MemoryRouter>,
    ));

    const link = container.querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/operations?tab=shifts');
    act(() => link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })));
    expect(container.querySelector('[data-location]')?.textContent).toBe('/operations?tab=shifts');

    act(() => root.unmount());
    container.remove();
  });
});
