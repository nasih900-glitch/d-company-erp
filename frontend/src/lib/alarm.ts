/**
 * Shared alarm primitives — synthesized audio (no file, works offline) +
 * browser notifications + a mm:ss/h:mm:ss clock formatter. Used by the
 * gaming session timer alarm and the held-order aging alarm.
 */

export const ALARM_REPEAT_MS = 30_000;

export function fmtClock(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    : `${m}:${String(s).padStart(2, '0')}`;
}

// Synthesized two-tone chime — no audio file needed, works offline.
export function playAlarmTone() {
  try {
    const Ctx = window.AudioContext
      || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const now = ctx.currentTime;
    [0, 0.24].forEach((offset, i) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.value = i === 0 ? 880 : 660;
      gain.gain.setValueAtTime(0.0001, now + offset);
      gain.gain.exponentialRampToValueAtTime(0.32, now + offset + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + 0.2);
      osc.connect(gain).connect(ctx.destination);
      osc.start(now + offset);
      osc.stop(now + offset + 0.22);
    });
    setTimeout(() => ctx.close(), 700);
  } catch {
    // Audio unavailable (autoplay policy, unsupported browser) — visual + notification still fire.
  }
}

export function notifyBrowser(title: string, body: string, tag: string) {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
  try {
    new Notification(title, { body, tag });
  } catch {
    // Notification construction can throw on some mobile browsers — alarm/visual still cover it.
  }
}
