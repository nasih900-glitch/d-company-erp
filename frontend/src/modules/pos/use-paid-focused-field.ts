import { useEffect, useRef } from 'react';

/** Resize repair only: keep the existing focused owner, draft and caret. */
export function usePaidFocusedField(current: () => boolean) {
  const ref = useRef<HTMLElement>(null);
  useEffect(() => {
    const root = ref.current;
    if (!root) return;
    let first = 0, second = 0, target: HTMLElement | null = null;
    const repair = () => {
      const active = document.activeElement;
      if (!current() || active !== target || !(active instanceof HTMLElement) || !root.contains(active) || !active.matches('input,textarea,select,button')) return;
      const owner = active.closest('label') ?? active;
      const vv = window.visualViewport;
      const top = Math.max(0, vv?.offsetTop ?? 0), bottom = Math.min(window.innerHeight, vv ? vv.offsetTop + vv.height : window.innerHeight);
      if (!Number.isFinite(top) || !Number.isFinite(bottom) || bottom <= top) return;
      const delta = (start: number, end: number) => {
        const box = owner.getBoundingClientRect();
        const field = box.height <= end - start ? box : active.getBoundingClientRect();
        return field.top < start ? field.top - start : field.bottom > end ? field.bottom - end : 0;
      };
      // Inner scroll owners may clip independently of the document/visual viewport.
      for (let parent = owner.parentElement; parent && parent !== document.body && parent !== document.documentElement; parent = parent.parentElement) {
        if (parent.scrollHeight <= parent.clientHeight || !/(auto|scroll|overlay)/.test(getComputedStyle(parent).overflowY)) continue;
        const box = parent.getBoundingClientRect();
        const start = Math.max(top, box.top + parent.clientTop), end = Math.min(bottom, box.top + parent.clientTop + parent.clientHeight);
        if (end > start) { const by = delta(start, end); if (by) parent.scrollBy({ top: by, behavior: 'instant' }); }
      }
      const by = delta(top, bottom);
      if (by) window.scrollBy({ top: by, behavior: 'instant' });
    };
    const schedule = () => {
      target = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      if (first || second) return;
      first = requestAnimationFrame(() => { first = 0; second = requestAnimationFrame(() => { second = 0; repair(); }); });
    };
    window.addEventListener('resize', schedule);
    window.visualViewport?.addEventListener('resize', schedule);
    root.addEventListener('focusin', schedule);
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    observer?.observe(root);
    return () => {
      cancelAnimationFrame(first); cancelAnimationFrame(second); observer?.disconnect();
      window.removeEventListener('resize', schedule); window.visualViewport?.removeEventListener('resize', schedule); root.removeEventListener('focusin', schedule);
    };
  }, [current]);
  return ref;
}
