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
import { useEffect } from 'react';
import { X } from 'lucide-react';

export interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  size?: 'sm' | 'md' | 'lg';
}

export default function Modal({ open, onClose, title, children, size = 'md' }: ModalProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
    };
  }, [open, onClose]);

  if (!open) return null;

  const sizeClass = {
    sm: 'md:max-w-sm',
    md: 'md:max-w-lg',
    lg: 'md:max-w-2xl',
  }[size];

  return (
    <div
      className="fixed inset-0 z-[100] flex items-stretch md:items-center justify-center bg-bg/80 backdrop-blur-sm overflow-y-auto"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={title}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className={`modal-panel flex max-h-[100dvh] w-full flex-col border border-bg-border bg-bg-surface shadow-2xl md:my-8 md:max-h-[90dvh] md:rounded-2xl ${sizeClass}`}
        style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}
      >
        <header
          className="flex shrink-0 items-center justify-between border-b border-bg-border p-4"
          style={{ paddingTop: 'max(1rem, env(safe-area-inset-top))' }}
        >
          <h3 className="min-w-0 break-words pr-3 text-lg font-bold">{title}</h3>
          <button
            onClick={onClose}
            aria-label="Close"
            className="shrink-0 p-1.5 -m-1.5 rounded-lg hover:bg-bg-raised"
          >
            <X size={18} />
          </button>
        </header>
        <div className="p-4 overflow-y-auto flex-1">{children}</div>
      </div>
    </div>
  );
}
