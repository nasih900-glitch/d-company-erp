// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { usePaidFocusedField } from './use-paid-focused-field';
let root: Root, host: HTMLDivElement, current: boolean, frames: Map<number, FrameRequestCallback>, sequence: number, visual: EventTarget & { offsetTop: number; height: number };
const authority = () => current;
function Harness() {
  const ref = usePaidFocusedField(authority);
  return <section ref={ref}><div style={{ overflowY: 'auto' }} data-scroll><label>Reason<textarea defaultValue="Preserve the exact draft"/></label></div><button>Review</button></section>;
}
beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  current = true; frames = new Map(); sequence = 0;
  vi.stubGlobal('requestAnimationFrame', (f: FrameRequestCallback) => { frames.set(++sequence, f); return sequence; });
  vi.stubGlobal('cancelAnimationFrame', (n: number) => frames.delete(n));
  visual = Object.assign(new EventTarget(), { offsetTop: 0, height: 420 }); vi.stubGlobal('visualViewport', visual); vi.stubGlobal('innerHeight', 420);
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); document.body.replaceChildren(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
async function render() { await act(async () => root.render(<Harness/>)); }
function flush() { for (let n = 0; n < 2; n++) { const pending = [...frames.values()]; frames.clear(); pending.forEach(f => f(0)); } }
function bounds(top: number, height: number) { return { top, bottom: top + height, height, left: 0, right: 300, width: 300, x: 0, y: top, toJSON: () => ({}) }; }
it('coalesces natural resize events, scrolls the measured owner and preserves exact field/focus/caret', async () => {
  const scroll = vi.spyOn(window, 'scrollBy').mockImplementation(() => {}); await render(); expect(scroll).not.toHaveBeenCalled();
  const field = host.querySelector('textarea')!, owner = host.querySelector('label')!;
  vi.spyOn(owner, 'getBoundingClientRect').mockReturnValue(bounds(792, 80)); field.focus(); field.setSelectionRange(4, 9);
  window.dispatchEvent(new Event('resize')); window.dispatchEvent(new Event('resize')); visual.dispatchEvent(new Event('resize'));
  expect(frames.size).toBe(1); flush(); expect(scroll).toHaveBeenCalledExactlyOnceWith({ top: 452, behavior: 'instant' }); expect(frames.size).toBe(0);
  expect(document.activeElement).toBe(field); expect(field.value).toBe('Preserve the exact draft'); expect([field.selectionStart, field.selectionEnd]).toEqual([4, 9]);
});
it('uses the visual viewport offset and scrolls an actual clipping ancestor before the document', async () => {
  const doc = vi.spyOn(window, 'scrollBy').mockImplementation(() => {}); await render(); visual.offsetTop = 30; visual.height = 300;
  const field = host.querySelector('textarea')!, owner = host.querySelector('label')!, parent = host.querySelector<HTMLElement>('[data-scroll]')!;
  Object.defineProperties(parent, { scrollHeight: { value: 600 }, clientHeight: { value: 280 } });
  vi.spyOn(parent, 'getBoundingClientRect').mockReturnValue(bounds(30, 280));
  let offset = 0; vi.spyOn(owner, 'getBoundingClientRect').mockImplementation(() => bounds(400 - offset, 80));
  const scroll = vi.spyOn(parent, 'scrollBy').mockImplementation(options => { offset += (options as ScrollToOptions).top!; });
  field.focus(); visual.dispatchEvent(new Event('resize')); flush();
  expect(scroll).toHaveBeenCalledExactlyOnceWith({ top: 170, behavior: 'instant' }); expect(doc).not.toHaveBeenCalled();
});
it.each(['outside focus', 'changed authority', 'unmount', 'already visible'] as const)('does no resize scroll for %s', async why => {
  const scroll = vi.spyOn(window, 'scrollBy').mockImplementation(() => {}); await render();
  const field = host.querySelector('textarea')!, owner = host.querySelector('label')!;
  vi.spyOn(owner, 'getBoundingClientRect').mockReturnValue(bounds(why === 'already visible' ? 100 : 792, 80));
  field.focus(); window.dispatchEvent(new Event('resize'));
  if (why === 'outside focus') { const outside = document.createElement('input'); document.body.append(outside); outside.focus(); }
  if (why === 'changed authority') current = false;
  if (why === 'unmount') { act(() => root.unmount()); root = createRoot(host); }
  flush(); expect(scroll).not.toHaveBeenCalled();
});
