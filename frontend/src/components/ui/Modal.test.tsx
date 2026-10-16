// @vitest-environment happy-dom

import { act, StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Modal from './Modal';

const reactActEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT?: boolean;
};

let root: Root;
let host: HTMLDivElement;

beforeEach(() => {
  reactActEnvironment.IS_REACT_ACT_ENVIRONMENT = true;
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
    callback(0);
    return 1;
  });
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  vi.restoreAllMocks();
  document.body.replaceChildren();
  delete reactActEnvironment.IS_REACT_ACT_ENVIRONMENT;
});

describe('Modal keyboard containment', () => {
  it('keeps React autofocus through StrictMode effect replay without stealing later user focus', () => {
    let nextFrame = 0;
    const pendingFrames = new Map<number, FrameRequestCallback>();
    vi.mocked(window.requestAnimationFrame).mockImplementation((callback) => {
      nextFrame += 1;
      pendingFrames.set(nextFrame, callback);
      return nextFrame;
    });
    vi.mocked(window.cancelAnimationFrame).mockImplementation((frame) => {
      pendingFrames.delete(frame);
    });
    const flushFrames = () => act(() => {
      const callbacks = [...pendingFrames.values()];
      pendingFrames.clear();
      callbacks.forEach((callback) => callback(0));
    });
    const trigger = document.createElement('button');
    const laterTarget = document.createElement('button');
    document.body.insertBefore(trigger, host);
    document.body.insertBefore(laterTarget, host);
    trigger.focus();

    act(() => root.render(
      <StrictMode>
        <Modal open title="Open shift" onClose={vi.fn()}>
          <input aria-label="Opening cash" autoFocus />
        </Modal>
      </StrictMode>,
    ));
    flushFrames();

    const cashInput = document.querySelector<HTMLInputElement>('[aria-label="Opening cash"]')!;
    expect(document.activeElement).toBe(cashInput);

    act(() => root.render(null));
    laterTarget.focus();
    flushFrames();
    expect(document.activeElement).toBe(laterTarget);
  });

  it('restores an eligible child that focused itself during mount without an autofocus attribute', () => {
    let focusFrame: FrameRequestCallback | null = null;
    vi.mocked(window.requestAnimationFrame).mockImplementationOnce((callback) => {
      focusFrame = callback;
      return 2;
    });

    act(() => root.render(
      <Modal open title="Cash count" onClose={vi.fn()}>
        <input
          aria-label="Cash counted"
          ref={(element) => element?.focus()}
        />
      </Modal>,
    ));

    const input = document.querySelector<HTMLInputElement>('[aria-label="Cash counted"]')!;
    expect(input.hasAttribute('autofocus')).toBe(false);
    expect(document.activeElement).toBe(input);
    input.blur();
    act(() => focusFrame?.(0));
    expect(document.activeElement).toBe(input);
  });

  it('marks only an explicit receipt modal as unbounded print content', () => {
    act(() => root.render(
      <Modal open title="Receipt" onClose={vi.fn()} printMode="receipt">
        <div>Long receipt</div>
      </Modal>,
    ));

    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')!;
    const panel = dialog.querySelector<HTMLElement>('.modal-panel')!;
    const header = panel.querySelector('header')!;
    expect(dialog.dataset.printModal).toBe('receipt');
    expect(dialog.className).toContain('print:static');
    expect(panel.className).toContain('print:max-h-none');
    expect(panel.className).toContain('print:overflow-visible');
    expect(header.className).toContain('print:hidden');
  });

  it('skips hidden controls, traps Tab, closes on Escape, and returns focus', () => {
    const trigger = document.createElement('button');
    trigger.textContent = 'Open';
    document.body.insertBefore(trigger, host);
    trigger.focus();
    const onClose = vi.fn();

    act(() => root.render(
      <Modal open title="Keyboard test" onClose={onClose}>
        <button hidden>Hidden</button>
        <div aria-hidden="true"><button>Also hidden</button></div>
        <button autoFocus>First action</button>
        <button>Last action</button>
      </Modal>,
    ));

    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')!;
    const close = dialog.querySelector<HTMLButtonElement>('[aria-label="Close"]')!;
    const first = Array.from(dialog.querySelectorAll('button')).find(
      (button) => button.textContent === 'First action',
    )!;
    const last = Array.from(dialog.querySelectorAll('button')).find(
      (button) => button.textContent === 'Last action',
    )!;
    expect(document.activeElement).toBe(first);
    expect(host.inert).toBe(true);

    last.focus();
    act(() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true })));
    expect(document.activeElement).toBe(close);
    close.focus();
    act(() => document.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Tab', shiftKey: true, bubbles: true,
    })));
    expect(document.activeElement).toBe(last);

    act(() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })));
    expect(onClose).toHaveBeenCalledTimes(1);
    act(() => root.render(null));
    expect(trigger.inert).toBe(false);
    expect(document.activeElement).toBe(trigger);
  });

  it('keeps only the top dialog active, survives out-of-order close, and protects locked checkout', () => {
    const trigger = document.createElement('button');
    trigger.textContent = 'Open';
    document.body.insertBefore(trigger, host);
    trigger.focus();
    const closeLower = vi.fn();
    const closeLocked = vi.fn();

    const render = (lower: boolean, locked: boolean) => act(() => root.render(<>
      {lower && <Modal key="lower" open title="Lower" onClose={closeLower}><button autoFocus>Lower action</button></Modal>}
      {locked && <Modal key="locked" open locked title="Payment confirmation" onClose={closeLocked}><button autoFocus>Pay action</button></Modal>}
    </>));

    render(true, false);
    const lowerAction = Array.from(document.querySelectorAll('button')).find(
      (button) => button.textContent === 'Lower action',
    ) as HTMLButtonElement;
    lowerAction.focus();
    render(true, true);
    const dialogs = document.querySelectorAll<HTMLElement>('[role="dialog"]');
    expect(dialogs).toHaveLength(2);
    expect(dialogs[0].inert).toBe(true);
    expect(dialogs[1].inert).toBe(false);

    act(() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })));
    expect(closeLocked).not.toHaveBeenCalled();
    act(() => dialogs[1].dispatchEvent(new MouseEvent('click', { bubbles: true })));
    expect(closeLocked).not.toHaveBeenCalled();

    render(false, true);
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1);
    expect(document.querySelector<HTMLElement>('[role="dialog"]')!.inert).toBe(false);
    render(false, false);
    expect(document.activeElement).toBe(trigger);
    expect(host.inert).toBe(false);
  });
});
