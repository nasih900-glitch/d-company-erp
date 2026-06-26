/**
 * Google Sheets sink — client-side webhook poster.
 *
 * Apps Script web apps reject browser fetches with strict CORS rules unless
 * the request is sent as `text/plain`. We exploit that: the body is JSON
 * but the Content-Type is "text/plain;charset=utf-8". Apps Script still
 * parses it via e.postData.contents.
 *
 * URL + status are stored in localStorage so demo mode persists settings
 * across page reloads.
 */

const KEY_URL = 'gsheets.webhook_url';
const KEY_LAST_SYNC = 'gsheets.last_sync_at';
const KEY_LAST_ERROR = 'gsheets.last_error';

export type SinkKind = 'order' | 'ticket' | 'event' | 'ping';

export interface GsheetsSettings {
  url: string | null;
  last_sync_at: string | null;
  last_error: string | null;
}

export function getSettings(): GsheetsSettings {
  return {
    url: localStorage.getItem(KEY_URL),
    last_sync_at: localStorage.getItem(KEY_LAST_SYNC),
    last_error: localStorage.getItem(KEY_LAST_ERROR),
  };
}

export function setWebhookUrl(url: string | null): void {
  if (url) {
    localStorage.setItem(KEY_URL, url);
  } else {
    localStorage.removeItem(KEY_URL);
    localStorage.removeItem(KEY_LAST_SYNC);
    localStorage.removeItem(KEY_LAST_ERROR);
  }
}

function markSync(error: string | null): void {
  localStorage.setItem(KEY_LAST_SYNC, new Date().toISOString());
  if (error) localStorage.setItem(KEY_LAST_ERROR, error);
  else localStorage.removeItem(KEY_LAST_ERROR);
}

/**
 * POST to the configured webhook. Fire-and-forget — never throws,
 * never blocks order completion. On failure, logs to console + updates
 * `last_error` so the user can see what went wrong on the settings page.
 */
export async function pushToSheet(kind: SinkKind, payload: unknown): Promise<boolean> {
  const url = localStorage.getItem(KEY_URL);
  if (!url) return false; // not configured — silently no-op
  try {
    const r = await fetch(url, {
      method: 'POST',
      // Apps Script accepts plain text and parses the JSON itself,
      // which bypasses the otherwise-blocked CORS preflight.
      headers: { 'Content-Type': 'text/plain;charset=utf-8' },
      body: JSON.stringify({ kind, payload }),
      redirect: 'follow',
    });
    if (!r.ok) {
      markSync(`HTTP ${r.status}`);
      return false;
    }
    const result = await r.json().catch(() => ({ ok: false }));
    if (!result.ok) {
      markSync(result.error || 'unknown error');
      return false;
    }
    markSync(null);
    return true;
  } catch (e) {
    markSync((e as Error).message || 'network error');
    return false;
  }
}

/** Manual ping for the Settings page "Test connection" button. */
export async function testConnection(url: string): Promise<{ ok: boolean; message: string }> {
  if (!url.startsWith('https://script.google.com/')) {
    return {
      ok: false,
      message:
        'URL must start with https://script.google.com/macros/s/ — make sure you copied the WEB APP URL, not the editor URL.',
    };
  }
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=utf-8' },
      body: JSON.stringify({ kind: 'ping', payload: {} }),
      redirect: 'follow',
    });
    if (!r.ok) {
      return { ok: false, message: `Server returned HTTP ${r.status}. Re-deploy the Apps Script.` };
    }
    const result = await r.json().catch(() => ({ ok: false, error: 'invalid JSON response' }));
    if (result.ok) {
      markSync(null);
      return { ok: true, message: result.message || 'Connected.' };
    }
    return { ok: false, message: result.error || 'Unknown error from the Apps Script.' };
  } catch (e) {
    return { ok: false, message: (e as Error).message || 'Network error.' };
  }
}
