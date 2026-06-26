/**
 * InstallButton — renders the right affordance for the user's device.
 *
 * Chrome/Edge desktop, Chrome Android: one-tap install button using the
 * native beforeinstallprompt event.
 *
 * iOS Safari: a "Install" link that opens a modal with the Share → Add to
 * Home Screen instructions (Apple does not expose a programmatic install).
 *
 * Already-installed PWAs: nothing rendered.
 */
import { useState } from 'react';
import { Download, Share as ShareIcon, Plus, X, CheckCircle2 } from 'lucide-react';

import { usePwaInstall } from '@/hooks/usePwaInstall';

export default function InstallButton() {
  const { canInstall, showIosHint, installed, isAndroid, isMac, prompt } = usePwaInstall();
  const [showIosModal, setShowIosModal] = useState(false);
  const [justInstalled, setJustInstalled] = useState(false);

  if (installed || justInstalled) {
    return (
      <div className="mb-3 rounded-xl bg-accent-good/10 border border-accent-good/30 px-3 py-2 text-xs text-accent-good flex items-center gap-2">
        <CheckCircle2 size={14} /> Installed
      </div>
    );
  }

  // Native install (Chrome/Edge/Android Chrome)
  if (canInstall) {
    return (
      <button
        onClick={async () => {
          const accepted = await prompt();
          if (accepted) setJustInstalled(true);
        }}
        className="btn btn-primary w-full mb-3"
      >
        <Download size={16} /> Install app
      </button>
    );
  }

  // iOS Safari — only path is to show instructions
  if (showIosHint) {
    return (
      <>
        <button
          onClick={() => setShowIosModal(true)}
          className="btn btn-ghost w-full mb-3 border-accent/40 text-accent"
        >
          <Download size={16} /> Install app
        </button>
        {showIosModal && (
          <IosInstallHint onClose={() => setShowIosModal(false)} isAndroid={isAndroid} isMac={isMac} />
        )}
      </>
    );
  }

  // Desktop fallback hint (rare — Firefox, Safari on macOS, etc.)
  return null;
}

function IosInstallHint({
  onClose, isAndroid, isMac,
}: { onClose: () => void; isAndroid: boolean; isMac: boolean }) {
  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-end md:items-center justify-center bg-bg/80 backdrop-blur-sm md:p-4"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="bg-bg-surface border border-bg-border rounded-t-2xl md:rounded-2xl shadow-glow w-full md:max-w-sm max-h-[90vh] overflow-auto"
        style={{ paddingBottom: 'max(1rem, env(safe-area-inset-bottom))' }}
      >
        <div className="flex items-center justify-between p-4 border-b border-bg-border">
          <h3 className="font-semibold">Install D Company</h3>
          <button onClick={onClose} className="text-fg-muted hover:text-fg p-1 -m-1">
            <X size={20} />
          </button>
        </div>
        <div className="p-5 space-y-4">
          {isMac ? (
            <p className="text-sm text-fg-muted">
              In Safari, install icons aren't available. The app already works full-screen — bookmark this page (⌘D) for one-tap access, or use Chrome / Edge for one-click install.
            </p>
          ) : (
            <>
              <p className="text-sm text-fg-muted">
                {isAndroid ? 'On Android Chrome:' : 'On your iPhone or iPad:'}
              </p>
              <Step n={1} icon={<ShareIcon size={18} />}>
                Tap the <b>Share</b> button at the bottom of Safari.
              </Step>
              <Step n={2} icon={<Plus size={18} />}>
                Scroll down → tap <b>Add to Home Screen</b>.
              </Step>
              <Step n={3} icon={<CheckCircle2 size={18} />}>
                Tap <b>Add</b>. The D Company icon appears on your home screen.
              </Step>
              <p className="text-xs text-fg-muted pt-2 border-t border-bg-border">
                After installing, open the app from the home screen icon — it runs full-screen, no browser bar, just like a regular app.
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function Step({ n, icon, children }: { n: number; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-3">
      <div className="w-7 h-7 rounded-full bg-bg-raised grid place-items-center text-xs font-bold flex-shrink-0">
        {n}
      </div>
      <div className="flex-1 text-sm flex items-center gap-2">
        {children}
        <span className="text-fg-muted">{icon}</span>
      </div>
    </div>
  );
}
