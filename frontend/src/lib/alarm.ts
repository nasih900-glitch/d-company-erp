/**
 * Shared alarm primitives for the gaming session timer and the held-order
 * aging alarm.
 *
 * PLATFORM SPLIT — this matters, and it was previously broken on Android:
 *
 *   Web browser  → Web Audio chime + the browser Notification API.
 *   Native app   → a real OS notification (Android notification shade /
 *                  iOS banner) on a high-importance channel, plus haptic
 *                  vibration, plus the same audible chime.
 *
 * Why the split exists: inside an Android WebView the browser `Notification`
 * constructor is simply not available, so the old implementation's
 * `typeof Notification === 'undefined'` guard meant every alarm
 * notification silently did nothing on the tablet. Staff would get no alert
 * at all for an overtime PS5 session or an ageing held order — the two
 * things these alarms exist for.
 *
 * Web Audio alone is also not enough on a shop-floor tablet: JS timers and
 * AudioContext stop when the screen sleeps or the app is backgrounded. An
 * OS-level notification is the only thing that still fires then, which is
 * why the native path posts one rather than only beeping.
 */

import { Capacitor } from '@capacitor/core';
import { LocalNotifications } from '@capacitor/local-notifications';
import { Haptics, ImpactStyle } from '@capacitor/haptics';

export const ALARM_REPEAT_MS = 30_000;

const ALARMS_ENABLED_KEY = 'dcompany_alarms_enabled';
/** High-importance channel so Android actually makes noise + heads-up. */
const ALARM_CHANNEL_ID = 'dcompany_alarms';

const isNative = () => Capacitor.isNativePlatform();

// Defaults to on — matches the always-on behavior every build before this
// setting existed, so upgrading the app doesn't silently go quiet.
export function alarmsEnabled(): boolean {
  try {
    const raw = localStorage.getItem(ALARMS_ENABLED_KEY);
    return raw === null ? true : raw === 'true';
  } catch {
    return true;
  }
}

export function setAlarmsEnabled(enabled: boolean): void {
  try { localStorage.setItem(ALARMS_ENABLED_KEY, String(enabled)); } catch { /* storage unavailable */ }
}

export function fmtClock(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    : `${m}:${String(s).padStart(2, '0')}`;
}

/**
 * Create the Android notification channel. Must run before the first
 * notification: on Android the channel's importance is fixed at creation
 * time and cannot be raised later, so a channel created implicitly at
 * default importance would leave alarms silent forever.
 */
let channelReady: Promise<void> | null = null;

/**
 * Create the alarm channel at app startup rather than lazily on the first
 * alarm. Two reasons: the channel then shows up in Android's per-app
 * notification settings immediately (so the owner can verify/adjust it
 * before relying on it), and there's no window in which an alarm could
 * land on the plugin's default channel — which is IMPORTANCE_DEFAULT and
 * therefore silent with no heads-up, i.e. an alarm nobody notices.
 */
export function initAlarms(): void {
  void ensureChannel();
}

function ensureChannel(): Promise<void> {
  if (!isNative() || Capacitor.getPlatform() !== 'android') return Promise.resolve();
  if (!channelReady) {
    channelReady = LocalNotifications.createChannel({
      id: ALARM_CHANNEL_ID,
      name: 'Session & order alarms',
      description: 'Gaming session overtime and ageing held orders',
      importance: 5,       // IMPORTANCE_HIGH — heads-up + sound
      visibility: 1,       // VISIBILITY_PUBLIC — readable on the lock screen
      sound: undefined,    // default alarm sound
      vibration: true,
      lights: true,
    }).catch(() => { /* channel already exists, or unsupported — non-fatal */ });
  }
  return channelReady;
}

export type AlarmPermission = 'granted' | 'denied' | 'default' | 'unsupported';

/** Current permission state, across both platforms. */
export async function alarmPermission(): Promise<AlarmPermission> {
  if (isNative()) {
    try {
      const res = await LocalNotifications.checkPermissions();
      if (res.display === 'granted') return 'granted';
      if (res.display === 'denied') return 'denied';
      return 'default';
    } catch {
      return 'unsupported';
    }
  }
  if (typeof Notification === 'undefined') return 'unsupported';
  return Notification.permission as AlarmPermission;
}

/** Ask for permission. On Android 13+ this shows the OS POST_NOTIFICATIONS dialog. */
export async function requestAlarmPermission(): Promise<AlarmPermission> {
  if (isNative()) {
    try {
      await ensureChannel();
      const res = await LocalNotifications.requestPermissions();
      return res.display === 'granted' ? 'granted'
        : res.display === 'denied' ? 'denied' : 'default';
    } catch {
      return 'unsupported';
    }
  }
  if (typeof Notification === 'undefined') return 'unsupported';
  try {
    return (await Notification.requestPermission()) as AlarmPermission;
  } catch {
    return 'unsupported';
  }
}

// Synthesized two-tone chime — no audio file needed, works offline.
export function playAlarmTone() {
  if (!alarmsEnabled()) return;

  // A tablet face-down on the counter is a real scenario; vibration is
  // often the thing that actually gets noticed.
  if (isNative()) {
    void Haptics.impact({ style: ImpactStyle.Heavy }).catch(() => { /* no vibrator */ });
  }

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
    // Audio unavailable (autoplay policy, unsupported browser) — the OS
    // notification and the on-screen banner still cover it.
  }
}

/**
 * Stable small integer id per alarm tag, so re-firing the same logical
 * alarm replaces its notification instead of stacking a new one every 30s
 * (which would bury the shade under dozens of duplicates during one long
 * overtime session). Mirrors the web Notification `tag` behaviour.
 */
function idForTag(tag: string): number {
  let h = 0;
  for (let i = 0; i < tag.length; i++) h = (h * 31 + tag.charCodeAt(i)) | 0;
  return Math.abs(h) % 100000;
}

/**
 * Post an alarm notification. Named for what it does rather than the
 * transport, because the transport now differs per platform.
 */
export function notifyAlarm(title: string, body: string, tag: string): void {
  if (!alarmsEnabled()) return;

  if (isNative()) {
    void (async () => {
      try {
        await ensureChannel();
        const perm = await LocalNotifications.checkPermissions();
        if (perm.display !== 'granted') return;
        await LocalNotifications.schedule({
          notifications: [{
            id: idForTag(tag),
            title,
            body,
            channelId: ALARM_CHANNEL_ID,
            schedule: { at: new Date(Date.now() + 100) },
            smallIcon: 'ic_launcher',
            ongoing: false,
            autoCancel: true,
          }],
        });
      } catch {
        // Never let a notification failure break the calling screen.
      }
    })();
    return;
  }

  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
  try {
    new Notification(title, { body, tag });
  } catch {
    // Notification construction can throw on some mobile browsers — alarm/visual still cover it.
  }
}
