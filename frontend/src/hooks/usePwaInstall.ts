/**
 * usePwaInstall — exposes the right install affordance per platform.
 *
 *   Chrome / Edge / Android Chrome     → captures beforeinstallprompt, fires .prompt()
 *   iOS Safari                         → no native API; we show step-by-step instructions
 *   Already installed (display=standalone) → returns canInstall=false, installed=true
 *
 * The component decides how to render based on:
 *   - canInstall       (Chrome path available)
 *   - showIosHint      (iOS Safari path)
 *   - installed        (already added to home screen)
 */
import { useEffect, useState } from 'react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export function usePwaInstall() {
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null);
  const [installed, setInstalled] = useState(false);

  useEffect(() => {
    // Detect "already installed" — standalone display mode or iOS standalone.
    const isStandalone =
      window.matchMedia?.('(display-mode: standalone)').matches ||
      (window.navigator as { standalone?: boolean }).standalone === true;
    setInstalled(isStandalone);

    function onBeforeInstall(e: Event) {
      e.preventDefault();
      setDeferred(e as BeforeInstallPromptEvent);
    }
    function onInstalled() {
      setDeferred(null);
      setInstalled(true);
    }
    window.addEventListener('beforeinstallprompt', onBeforeInstall);
    window.addEventListener('appinstalled', onInstalled);
    return () => {
      window.removeEventListener('beforeinstallprompt', onBeforeInstall);
      window.removeEventListener('appinstalled', onInstalled);
    };
  }, []);

  async function prompt() {
    if (!deferred) return false;
    await deferred.prompt();
    const r = await deferred.userChoice;
    setDeferred(null);
    return r.outcome === 'accepted';
  }

  const ua = typeof navigator !== 'undefined' ? navigator.userAgent : '';
  const isIOS = /iPad|iPhone|iPod/.test(ua) && !/CriOS|FxiOS/.test(ua); // exclude Chrome/Firefox on iOS
  const isAndroid = /Android/.test(ua);
  const isMac = /Mac/.test(ua) && !isIOS;

  return {
    canInstall: !!deferred,        // Chrome/Edge/Android Chrome native flow
    showIosHint: isIOS && !installed,
    installed,
    isIOS,
    isAndroid,
    isMac,
    prompt,
  };
}
