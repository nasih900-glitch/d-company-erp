/**
 * Styled replacements for the browser's native confirm()/prompt() — those
 * block synchronously, look unstyled/inconsistent next to the rest of the
 * app, and are easy to dismiss by accident on a touch device. Callers
 * conditionally render these the same way every other modal in the app is
 * rendered (`{state && <ConfirmModal .../>}`), then resolve via callback.
 */
import { useState } from 'react';
import { Loader2 } from 'lucide-react';

import Modal from './Modal';

export function ConfirmModal({
  title, message, confirmLabel = 'Confirm', danger, onConfirm, onCancel, busy,
}: {
  title: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  busy?: boolean;
}) {
  return (
    <Modal open onClose={onCancel} title={title} size="sm">
      <p className="text-sm text-fg-muted mb-4">{message}</p>
      <div className="flex justify-end gap-2">
        <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={busy}>Cancel</button>
        <button type="button" className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} onClick={onConfirm} disabled={busy}>
          {busy ? <Loader2 className="animate-spin" size={14}/> : null} {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}

export function PromptModal({
  title, label, placeholder, confirmLabel = 'Confirm', required = true, onSubmit, onCancel, busy,
}: {
  title: string;
  label: string;
  placeholder?: string;
  confirmLabel?: string;
  required?: boolean;
  onSubmit: (value: string) => void;
  onCancel: () => void;
  busy?: boolean;
}) {
  const [value, setValue] = useState('');
  return (
    <Modal open onClose={onCancel} title={title} size="sm">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (required && !value.trim()) return;
          onSubmit(value.trim());
        }}
        className="space-y-3"
      >
        <label className="block">
          <span className="text-xs text-fg-muted">{label}</span>
          <input
            className="input mt-1"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder={placeholder}
            autoFocus
            required={required}
          />
        </label>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null} {confirmLabel}
          </button>
        </div>
      </form>
    </Modal>
  );
}
