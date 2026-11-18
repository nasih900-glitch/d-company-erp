// @vitest-environment happy-dom
import { act, StrictMode, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { afterEach, expect, it } from 'vitest';
import { clearOwnerWorkspaces } from './owner-workspace';
import { useInlineWorkspaceFocus } from './use-inline-workspace-focus';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let root: Root | undefined;
afterEach(() => { act(() => root?.unmount()); root = undefined; document.body.replaceChildren(); });

function Workspace({ initiallyOpen = false, removeInvoker = false, scope = 'actor-a' }) {
  const [open, setOpen] = useState(initiallyOpen);
  const [selected, setSelected] = useState('one');
  const [, rerender] = useState(0);
  const focus = useInlineWorkspaceFocus(open, scope);
  const navigate = useNavigate();
  return <div ref={focus.fallbackRef} tabIndex={-1} data-testid="fallback">
    {open && <section><h3 ref={focus.headingRef} tabIndex={-1}>Panel {selected}</h3>
      <button onClick={() => { focus.closeToInvoker(); setOpen(false); }}>Return</button>
      <input aria-label="Inside panel"/>
    </section>}
    {(!removeInvoker || !open) && ['one', 'two'].map(id => <button key={id} onClick={event => {
      focus.openFrom(event.currentTarget); setSelected(id); setOpen(true);
    }}>Open {id}</button>)}
    <button onClick={() => rerender(value => value + 1)}>Refresh</button>
    <button onClick={() => navigate('/another-workspace')}>Navigate</button>
    <button onClick={() => { clearOwnerWorkspaces(); rerender(value => value + 1); }}>New login</button>
  </div>;
}
function setup(props = {}) {
  const outside = document.createElement('input'); outside.setAttribute('aria-label', 'Other work');
  const host = document.createElement('div'); document.body.append(outside, host); outside.focus();
  root = createRoot(host);
  act(() => root!.render(<StrictMode><MemoryRouter><Workspace {...props}/></MemoryRouter></StrictMode>));
  return { outside, render: (scope: string) => act(() => root!.render(
    <StrictMode><MemoryRouter><Workspace {...props} scope={scope}/></MemoryRouter></StrictMode>,
  )) };
}
function button(text: string) {
  const value = [...document.querySelectorAll('button')].find(node => node.textContent === text);
  expect(value).toBeTruthy(); return value!;
}
function activate(text: string) {
  const target = button(text); target.focus(); act(() => target.click()); return target;
}

it('enters committed inline content from a focused invoker and returns to that exact element', () => {
  setup();
  const invoker = activate('Open one');
  expect(document.activeElement?.textContent).toBe('Panel one');
  activate('Return');
  expect(document.activeElement).toBe(invoker);
});
it('does not autofocus normal closed or restored-open mounts', () => {
  const first = setup(); expect(document.activeElement).toBe(first.outside);
  act(() => root!.unmount()); root = undefined; document.body.replaceChildren();
  const restored = setup({ initiallyOpen: true });
  expect(document.activeElement).toBe(restored.outside);
});
it('does not pull normal nonmodal keyboard focus back on refresh', () => {
  const { outside } = setup(); activate('Open one'); outside.focus();
  act(() => button('Refresh').click());
  expect(document.activeElement).toBe(outside);
});
it('uses the current workspace fallback when a completed action removed its invoker', () => {
  setup({ removeInvoker: true }); activate('Open one'); activate('Return');
  expect(document.activeElement).toBe(document.querySelector('[data-testid="fallback"]'));
});
it('a second explicit receipt selection gets a new entry and the new invoking row', () => {
  setup(); activate('Open one'); const second = activate('Open two');
  expect(document.activeElement?.textContent).toBe('Panel two');
  activate('Return'); expect(document.activeElement).toBe(second);
});
it('does not restore into a different account', () => {
  const { outside, render } = setup(); activate('Open one'); render('actor-b'); outside.focus();
  act(() => button('Return').click()); expect(document.activeElement).toBe(outside);
});
it('does not restore across a route change or a new authenticated workspace generation', () => {
  const { outside } = setup(); activate('Open one'); act(() => button('Navigate').click()); outside.focus();
  act(() => button('Return').click()); expect(document.activeElement).toBe(outside);
  activate('Open one'); act(() => button('New login').click()); outside.focus();
  act(() => button('Return').click()); expect(document.activeElement).toBe(outside);
});
it('unmount never moves focus back to a previous screen', () => {
  const { outside } = setup(); activate('Open one'); outside.focus();
  act(() => root!.unmount()); root = undefined;
  expect(document.activeElement).toBe(outside);
});
