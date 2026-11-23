import { isRecord, isUuid, paidAccessIdentity } from './paid-bill-contract';

const KEY = 'dcompany:root-workspace-epoch:v1';
const CHANNEL = 'dcompany-root-workspace-v1';
let epoch = 0, uncertain = false, observing = false;
let channel: BroadcastChannel | null = null;
let ordinary: (() => { lineage: string; endpoint: string }) | null = null;
const listeners = new Set<() => void>();
function changed() { epoch++; for (const listener of listeners) { try { listener(); } catch { /* Observer failures cannot interrupt ordinary work. */ } } }
function renewal(old: string | null, next: string | null) {
  try {
    const payload = (v: string | null): unknown => JSON.parse(atob(v!.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    const a = payload(old), b = payload(next);
    return isRecord(a) && isRecord(b) && isUuid(a.session_family_id) && isUuid(b.session_family_id)
      && Number.isSafeInteger(a.auth_version) && Number.isSafeInteger(b.auth_version)
      && paidAccessIdentity(old) === paidAccessIdentity(next)
      && ['protected_access', 'audit_access'].every(k => typeof a[k] === 'boolean' && a[k] === b[k])
      && Array.isArray(a.roles) && Array.isArray(b.roles)
      && a.roles.every(r => typeof r === 'string') && b.roles.every(r => typeof r === 'string')
      && JSON.stringify([...a.roles].sort()) === JSON.stringify([...b.roles].sort());
  } catch { return false; }
}
function onStorage(event: StorageEvent) {
  if (event.key === 'access_token' && renewal(event.oldValue, event.newValue)) return;
  if (event.key === null || [KEY, 'terminal_id', 'access_token', 'dcompany_cookie_session_signed_out'].includes(event.key)) changed();
}
function observe() {
  if (observing || typeof window === 'undefined') return;
  observing = true;
  try {
    channel = new BroadcastChannel(CHANNEL);
    channel.onmessage = event => {
      if (event.data?.kind === 'workspace-changed') {
        if (event.data.uncertain === true) uncertain = true;
        changed();
      }
    };
  } catch { uncertain = true; }
  window.addEventListener('storage', onStorage);
}
/** Best effort for ordinary operations; a failed publication only closes recovery leases. */
export function advanceRootWorkspaceEpoch() {
  try {
    const nonce = crypto.randomUUID();
    localStorage.setItem(KEY, nonce);
    if (localStorage.getItem(KEY) !== nonce) uncertain = true;
  } catch { uncertain = true; }
  try {
    const sender = channel ?? new BroadcastChannel(CHANNEL);
    sender.postMessage({ kind: 'workspace-changed', uncertain });
    if (sender !== channel) sender.close();
  } catch { uncertain = true; }
  changed();
}
export function registerOrdinaryWorkspace(reader: () => { lineage: string; endpoint: string }) {
  ordinary = reader;
  changed();
}
export function rootWorkspaceSnapshot() {
  observe();
  if (uncertain || !channel) throw new Error('Recovery coordination could not be verified. Keep browser data and reload.');
  return JSON.stringify([epoch, localStorage.getItem(KEY), localStorage.getItem('terminal_id'), ordinary?.() ?? null]);
}
export function subscribeRootWorkspace(listener: () => void) {
  observe(); listeners.add(listener); return () => {
    listeners.delete(listener);
    if (!listeners.size) { channel?.close(); channel = null; observing = false; window.removeEventListener('storage', onStorage); }
  };
}

export function ordinaryWorkspaceEndpoint() { return ordinary?.().endpoint ?? null; }
