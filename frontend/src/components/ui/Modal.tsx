/**
 * Lightweight accessible modal.
 *
 *  <Modal open={...} onClose={...} title="Add ingredient">
 *    <form>…</form>
 *  </Modal>
 *
 * Keyboard:
 *   - Esc       closes
 *   - Click on backdrop closes
 *
 * Mobile: full-screen sheet on <md, centered card on ≥md.
 */
import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';

let bodyOverflowBeforeFirstModal = '';
let nextModalId = 0;
const openModalStack: number[] = [];
const modalEntries = new Map<number, {
  overlay: HTMLElement;
  panel: HTMLElement;
  returnFocus: HTMLElement | null;
}>();
const backgroundInertBefore = new Map<HTMLElement, boolean>();
let backgroundObserver: MutationObserver | null = null;

function syncModalEnvironment() {
  const topId = openModalStack.at(-1) ?? null;
  const overlays = new Set(Array.from(modalEntries.values(), (entry) => entry.overlay));
  for (const child of Array.from(document.body.children)) {
    if (!(child instanceof HTMLElement)) continue;
    if (overlays.has(child)) {
      backgroundInertBefore.delete(child);
      child.inert = Number(child.dataset.modalOverlayId) !== topId;
    } else if (topId !== null) {
      if (!backgroundInertBefore.has(child)) backgroundInertBefore.set(child, child.inert);
      child.inert = true;
    }
  }
  if (topId !== null) return;
  backgroundInertBefore.forEach((wasInert, element) => {
    if (element.isConnected) element.inert = wasInert;
  });
  backgroundInertBefore.clear();
}

function focusTargets(panel: HTMLElement): HTMLElement[] {
  return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter((element) => {
    if (element.closest('[hidden], [aria-hidden="true"], [inert]')) return false;
    let current: HTMLElement | null = element;
    while (current && current !== panel.parentElement) {
      const style = window.getComputedStyle(current);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
      current = current.parentElement;
    }
    return true;
  });
}

export interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  locked?: boolean;
  contentClassName?: string;
  printMode?: 'receipt';
}

const FOCUSABLE_SELECTOR = [
  'button:not([disabled])',
  'a[href]',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

export default function Modal({
  open,
  onClose,
  title,
  children,
  size = 'md',
  locked = false,
  contentClassName = '',
  printMode,
}: ModalProps) {
  const onCloseRef = useRef(onClose);
  const lockedRef = useRef(locked);
  const overlayRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const modalIdRef = useRef<number | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const restoreFocusFrameRef = useRef<number | null>(null);
  const wasOpenRef = useRef(false);
  const titleId = useId();
  if (modalIdRef.current === null) modalIdRef.current = ++nextModalId;
  if (open && !wasOpenRef.current) {
    returnFocusRef.current = typeof document !== 'undefined'
      && document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    wasOpenRef.current = true;
  } else if (!open) {
    wasOpenRef.current = false;
  }

  useEffect(() => {
    onCloseRef.current = onClose;
    lockedRef.current = locked;
  }, [locked, onClose]);

  useEffect(() => {
    if (!open) return;
    if (restoreFocusFrameRef.current !== null) {
      window.cancelAnimationFrame(restoreFocusFrameRef.current);
      restoreFocusFrameRef.current = null;
    }
    const modalId = modalIdRef.current!;
    const overlay = overlayRef.current;
    const panel = panelRef.current;
    if (!overlay || !panel) return;
    const returnFocus = returnFocusRef.current;
    const initiallyFocused = document.activeElement instanceof HTMLElement
      && panel.contains(document.activeElement)
      && focusTargets(panel).includes(document.activeElement)
      ? document.activeElement
      : null;
    const onKey = (e: KeyboardEvent) => {
      if (openModalStack[openModalStack.length - 1] !== modalId) return;
      if (e.key === 'Escape' && !lockedRef.current) {
        e.preventDefault();
        onCloseRef.current();
        return;
      }
      if (e.key !== 'Tab') return;
      const focusable = focusTargets(panel);
      if (!focusable.length) {
        e.preventDefault();
        panelRef.current?.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && (document.activeElement === first || !panelRef.current?.contains(document.activeElement))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (document.activeElement === last || !panelRef.current?.contains(document.activeElement))) {
        e.preventDefault();
        first.focus();
      }
    };
    openModalStack.push(modalId);
    modalEntries.set(modalId, { overlay, panel, returnFocus });
    document.addEventListener('keydown', onKey);
    if (openModalStack.length === 1) {
      bodyOverflowBeforeFirstModal = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      backgroundObserver = new MutationObserver(syncModalEnvironment);
      backgroundObserver.observe(document.body, { childList: true });
    }
    syncModalEnvironment();
    const focusFrame = window.requestAnimationFrame(() => {
      if (openModalStack.at(-1) !== modalId) return;
      const focusable = focusTargets(panel);
      const preferred = focusable.find((element) => element.hasAttribute('autofocus'));
      const alreadyFocused = document.activeElement instanceof HTMLElement
        && panel.contains(document.activeElement)
        && focusable.includes(document.activeElement)
        ? document.activeElement
        : null;
      const initialFocusStillEligible = initiallyFocused?.isConnected
        && focusable.includes(initiallyFocused)
        ? initiallyFocused
        : null;
      (alreadyFocused ?? initialFocusStillEligible ?? preferred ?? focusable[0] ?? panel)?.focus();
    });
    return () => {
      const wasTop = openModalStack.at(-1) === modalId;
      window.cancelAnimationFrame(focusFrame);
      document.removeEventListener('keydown', onKey);
      const stackIndex = openModalStack.lastIndexOf(modalId);
      if (stackIndex >= 0) openModalStack.splice(stackIndex, 1);
      const restoredFocus = modalEntries.get(modalId)?.returnFocus ?? returnFocus;
      modalEntries.forEach((entry) => {
        if (entry.returnFocus && overlay.contains(entry.returnFocus)) {
          entry.returnFocus = returnFocus;
        }
      });
      modalEntries.delete(modalId);
      syncModalEnvironment();
      if (openModalStack.length === 0) {
        backgroundObserver?.disconnect();
        backgroundObserver = null;
        document.body.style.overflow = bodyOverflowBeforeFirstModal;
      }
      if (!wasTop) return;
      // React StrictMode replays effects without remounting the DOM. Restoring
      // synchronously during that cleanup steals focus from a child's autoFocus,
      // and the replay has no new autoFocus commit. The replay cancels this frame.
      restoreFocusFrameRef.current = window.requestAnimationFrame(() => {
        restoreFocusFrameRef.current = null;
        if (openModalStack.includes(modalId)) return;
        const active = document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null;
        if (
          active
          && active !== document.body
          && active.isConnected
          && !overlay.contains(active)
        ) return;
        if (restoredFocus?.isConnected && !restoredFocus.closest('[inert]')) {
          restoredFocus.focus();
        } else {
          const nextTop = modalEntries.get(openModalStack.at(-1) ?? -1);
          (nextTop ? focusTargets(nextTop.panel)[0] ?? nextTop.panel : null)?.focus();
        }
      });
    };
  }, [open]);

  if (!open) return null;

  const sizeClass = {
    sm: 'md:max-w-sm',
    md: 'md:max-w-lg',
    lg: 'md:max-w-2xl',
    xl: 'md:max-w-5xl',
  }[size];
  const receiptPrint = printMode === 'receipt';

  const modalContent = (
    <div
      ref={overlayRef}
      data-modal-overlay-id={modalIdRef.current}
      data-print-modal={receiptPrint ? 'receipt' : undefined}
      className={`fixed inset-0 z-[100] flex items-stretch md:items-center justify-center bg-bg/80 backdrop-blur-sm overflow-y-auto ${receiptPrint
        ? 'print:static print:inset-auto print:z-auto print:block print:overflow-visible print:bg-transparent print:backdrop-blur-none'
        : ''}`}
      onClick={(event) => {
        if (
          !locked
          && event.target === event.currentTarget
          && openModalStack[openModalStack.length - 1] === modalIdRef.current
        ) onClose();
      }}
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      <div
        ref={panelRef}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        className={`modal-panel flex max-h-[100dvh] w-full flex-col border border-bg-border bg-bg-surface shadow-2xl md:my-8 md:max-h-[90dvh] md:rounded-2xl ${sizeClass} ${receiptPrint
          ? 'print:block print:max-h-none print:w-auto print:max-w-none print:overflow-visible print:rounded-none print:border-0 print:bg-white print:text-black print:shadow-none'
          : ''}`}
        style={{
          paddingBottom: 'env(safe-area-inset-bottom)',
        }}
      >
        <header
          className={`flex shrink-0 items-center justify-between border-b border-bg-border p-4 ${receiptPrint ? 'print:hidden' : ''}`}
          style={{ paddingTop: 'max(1rem, env(safe-area-inset-top))' }}
        >
          <h3 id={titleId} className="min-w-0 break-words pr-3 text-lg font-bold">{title}</h3>
          {locked ? null : <button
            onClick={onClose}
            aria-label="Close"
            className="shrink-0 p-1.5 -m-1.5 rounded-lg hover:bg-bg-raised active:scale-90 transition"
          >
            <X size={18} />
          </button>}
        </header>
        <div className={`p-4 flex-1 ${contentClassName || 'overflow-y-auto'} ${receiptPrint ? 'print:block print:overflow-visible print:p-0' : ''}`}>{children}</div>
      </div>
    </div>
  );
  return typeof document === 'undefined' ? modalContent : createPortal(modalContent, document.body);
}
