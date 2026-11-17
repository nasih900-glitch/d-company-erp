import { useCallback, useEffect, useRef, useState } from 'react';
import { api, readAccessToken, readSessionGeneration } from '@/lib/api';
import { readStoredTerminalId } from '@/lib/operational-context';
import { useAuth } from '@/modules/auth/AuthContext';
import { hasExactPermission } from '@/lib/effective-permissions';
import Modal from '@/components/ui/Modal';

export const SHARED_ATTENDANCE_KEY = 'dcompany_shared_attendance_v1';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
interface Person { id: string; name: string; on_duty: boolean; attendance_id: string | null }
interface Saved { version: 1; key: string; actor: string; company: string; branch: string; terminal: string; person: Person; break_minutes: number | null }
interface Receipt { id: string; user_id: string; branch_id: string; clock_in_at: string; clock_out_at: string | null; break_minutes: number; break_confirmed: boolean; source: string; updated_at: string; staff_name: string }
function same(a: unknown, b: unknown) { return JSON.stringify(a) === JSON.stringify(b); }
function check(value: unknown, message: string): asserts value { if (!value) throw new Error(message); }
function keys(value: object, fields: string[]) { return Object.keys(value).sort().join() === fields.sort().join(); }
function personValid(p: Person) {
  return p && keys(p, ['id', 'name', 'on_duty', 'attendance_id']) && UUID.test(p.id) && typeof p.name === 'string' &&
    p.name.length > 0 && p.name.length <= 200 && typeof p.on_duty === 'boolean' &&
    (p.attendance_id === null || UUID.test(p.attendance_id)) && p.on_duty === (p.attendance_id !== null);
}
export function readSharedAttendance(): Saved | null {
  const raw = localStorage.getItem(SHARED_ATTENDANCE_KEY);
  if (raw === null) return null;
  check(raw.length > 0 && raw.length <= 8192, 'Saved attendance could not be verified. Keep browser data.');
  const p = JSON.parse(raw) as Saved;
  check(p && keys(p, ['version', 'key', 'actor', 'company', 'branch', 'terminal', 'person', 'break_minutes']) &&
    p.version === 1 && [p.actor, p.company, p.branch, p.terminal].every(x => UUID.test(x)) && personValid(p.person) &&
    /^shared-attendance:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(p.key) &&
    (p.person.on_duty ? Number.isInteger(p.break_minutes) && p.break_minutes! >= 0 && p.break_minutes! <= 1440 : p.break_minutes === null),
  'Saved attendance could not be verified. Keep browser data.');
  return p;
}
function instant(value: string) { return typeof value === 'string' && value.includes('T') && /(?:Z|[+-]\d\d:\d\d)$/.test(value) && Number.isFinite(Date.parse(value)); }
export function validateSharedReceipt(p: Saved, r: Receipt): Receipt {
  check(r && keys(r, ['id', 'user_id', 'branch_id', 'clock_in_at', 'clock_out_at', 'break_minutes', 'break_confirmed', 'source', 'updated_at', 'staff_name']) &&
    UUID.test(r.id) && r.user_id === p.person.id && r.branch_id === p.branch && instant(r.clock_in_at) && instant(r.updated_at) &&
    typeof r.source === 'string' && r.source.length > 0 && r.source.length <= 30 && typeof r.staff_name === 'string' && r.staff_name.length > 0 && r.staff_name.length <= 200 &&
    Number.isInteger(r.break_minutes) && r.break_minutes >= 0 && r.break_minutes <= 1440 && typeof r.break_confirmed === 'boolean',
  'The attendance receipt could not be verified. Keep the saved request.');
  if (p.person.on_duty) check(r.id === p.person.attendance_id && r.clock_out_at !== null && instant(r.clock_out_at) &&
    r.break_confirmed && r.break_minutes === p.break_minutes && Date.parse(r.clock_out_at) - Date.parse(r.clock_in_at) >= r.break_minutes * 60_000,
  'The clock-out receipt does not match the saved request.');
  else check(r.clock_out_at === null && r.break_minutes === 0, 'The clock-in receipt does not match the saved request.');
  return { id: r.id, user_id: r.user_id, branch_id: r.branch_id, clock_in_at: r.clock_in_at, clock_out_at: r.clock_out_at,
    break_minutes: r.break_minutes, break_confirmed: r.break_confirmed, source: r.source, updated_at: r.updated_at, staff_name: r.staff_name };
}
let sending = false;
export default function SharedAttendancePanel({ embedded = false }: { embedded?: boolean }) {
  const { me, terminalId } = useAuth();
  const scope = [readSessionGeneration(), me?.user_id, me?.company_id, me?.branch_id, terminalId,
    me?.roles?.slice().sort().join(), me?.effective_permissions?.slice().sort().join()].join(':');
  return <SharedAttendanceScoped key={scope} embedded={embedded}/>;
}
function SharedAttendanceScoped({ embedded }: { embedded: boolean }) {
  const { me, terminalId } = useAuth();
  const generation = readSessionGeneration();
  const [people, setPeople] = useState<Person[]>([]), [cursor, setCursor] = useState<string | null>(null);
  const [ready, setReady] = useState(false), [saved, setSaved] = useState<Saved | null>(null);
  const [selected, setSelected] = useState<Person | null>(null), [retry, setRetry] = useState<Saved | null>(null);
  const [password, setPassword] = useState(''), [breaks, setBreaks] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null), [notice, setNotice] = useState<string | null>(null);
  const alive = useRef(true), directoryEpoch = useRef(0), cursorRef = useRef(cursor);
  cursorRef.current = cursor;
  const invalidateDirectory = useCallback(() => { directoryEpoch.current++; }, []);
  const current = useCallback(() => alive.current && readSessionGeneration() === generation && readStoredTerminalId() === terminalId, [generation, terminalId]);
  const refresh = useCallback(async (more = false) => {
    const epoch = ++directoryEpoch.current;
    setBusy(true); setReady(false); setError(null);
    try {
      const { data } = await api.get<{ items: Person[]; next_cursor: string | null }>('/staff/shared-attendance/people', {
        params: more && cursorRef.current ? { after_id: cursorRef.current } : undefined,
      });
      if (!current() || epoch !== directoryEpoch.current) return;
      check(Array.isArray(data.items) && data.items.length <= 50 && data.items.every(personValid) &&
        new Set(data.items.map(p => p.id)).size === data.items.length && (data.next_cursor === null || UUID.test(data.next_cursor)), 'Attendance directory is invalid.');
      setPeople(old => more ? [...old, ...data.items] : data.items);
      setCursor(data.next_cursor); setSaved(readSharedAttendance()); setReady(true);
    } catch (e) { if (current() && epoch === directoryEpoch.current) setError((e as Error).message); }
    finally { if (current() && epoch === directoryEpoch.current) setBusy(false); }
  }, [current]);
  useEffect(() => {
    alive.current = true;
    const dismiss = () => { setPassword(''); setSelected(null); setRetry(null); };
    const hide = () => { if (document.hidden) dismiss(); };
    const storage = () => { dismiss(); try { setSaved(readSharedAttendance()); } catch (e) { setError((e as Error).message); } };
    storage();
    document.addEventListener('visibilitychange', hide); window.addEventListener('storage', storage);
    return () => { alive.current = false; invalidateDirectory(); document.removeEventListener('visibilitychange', hide); window.removeEventListener('storage', storage); };
  }, [invalidateDirectory]);
  useEffect(() => {
    if (!me || !terminalId || !hasExactPermission(me, 'staff.attendance.write')) return;
    const changed = () => { void refresh(); };
    changed(); window.addEventListener('dcompany-daily-work-changed', changed);
    return () => { invalidateDirectory(); window.removeEventListener('dcompany-daily-work-changed', changed); };
  }, [me, terminalId, refresh, invalidateDirectory]);
  const confirm = async () => {
    if (sending || !terminalId || !me?.branch_id || !password) return;
    if (!navigator.locks) { setError('Use a supported secure browser to record attendance safely.'); return; }
    const displayedRetry = retry, displayedPerson = selected, secret = password;
    setPassword('');
    return navigator.locks.request(SHARED_ATTENDANCE_KEY, { ifAvailable: true }, async lock => {
      if (!lock || !current()) { if (current()) setError('Another attendance action is active. Wait for its result.'); return; }
      sending = true; setBusy(true); setError(null);
      const bearer = readAccessToken();
      try {
        const old = readSharedAttendance(); let p = old;
        if (displayedRetry) check(p && same(p, displayedRetry), 'The saved attendance changed. Review it again before entering a password.');
        else {
          check(old === null && displayedPerson && ready && people.some(person => same(person, displayedPerson)), 'Refresh and resolve saved attendance before recording another action.');
          const value = displayedPerson.on_duty ? Number(breaks) : null;
          check(!displayedPerson.on_duty || breaks.trim() !== '' && Number.isInteger(value) && value! >= 0 && value! <= 1440, 'Enter actual break minutes, including 0.');
          p = { version: 1, key: `shared-attendance:${crypto.randomUUID()}`, actor: me.user_id, company: me.company_id,
            branch: me.branch_id!, terminal: terminalId, person: displayedPerson, break_minutes: value };
          localStorage.setItem(SHARED_ATTENDANCE_KEY, JSON.stringify(p));
          check(same(readSharedAttendance(), p), 'Attendance could not be saved safely.');
        }
        check(p && current() && bearer && p.actor === me.user_id && p.company === me.company_id && p.branch === me.branch_id && p.terminal === terminalId, 'Return to the original attendance workspace.');
        setSaved(p); setSelected(null); setRetry(null);
        const body = { user_id: p.person.id, password: secret, ...(p.person.on_duty ? { attendance_id: p.person.attendance_id, break_minutes: p.break_minutes } : {}) };
        const abort = new AbortController(), timeout = setTimeout(() => abort.abort(), 20000);
        const response = await fetch(`${api.defaults.baseURL}/staff/shared-attendance/clock-${p.person.on_duty ? 'out' : 'in'}`, {
          signal: abort.signal, method: 'POST', credentials: 'omit', redirect: 'error', headers: {
            'Content-Type': 'application/json', Authorization: `Bearer ${bearer}`, 'X-Terminal-Id': terminalId, 'Idempotency-Key': p.key,
          }, body: JSON.stringify(body),
        }).finally(() => clearTimeout(timeout));
        check(response.ok, `Attendance returned HTTP ${response.status}. The saved request is retained; re-enter the same staff member's password to retry.`);
        const raw = await response.text(); check(raw.length <= 32768, 'Attendance response could not be verified.');
        const receipt = validateSharedReceipt(p, JSON.parse(raw) as Receipt);
        check(current() && readAccessToken() === bearer, 'The attendance workspace changed. Keep the saved request.');
        const key = `${SHARED_ATTENDANCE_KEY}:resolved:${p.key}`, archive = JSON.stringify({ request: p, receipt });
        const previous = localStorage.getItem(key); check(previous === null || previous === archive, 'The retained attendance receipt changed. Keep the saved request.');
        localStorage.setItem(key, archive); check(localStorage.getItem(key) === archive, 'The receipt could not be saved safely.');
        check(current() && same(readSharedAttendance(), p), 'The attendance workspace changed.');
        localStorage.removeItem(SHARED_ATTENDANCE_KEY); check(localStorage.getItem(SHARED_ATTENDANCE_KEY) === null, 'The saved action could not be settled safely.');
        setSaved(null); setReady(false); setNotice(`${p.person.name}: clock-${p.person.on_duty ? 'out' : 'in'} recorded. Signed-in account and shop shift unchanged.`);
        window.dispatchEvent(new Event('dcompany-daily-work-changed'));
      } catch (e) {
        if (current()) { setError((e as Error).message); try { setSaved(readSharedAttendance()); } catch { setReady(false); } }
      } finally { sending = false; if (current()) setBusy(false); }
    });
  };
  if (!me || !hasExactPermission(me, 'staff.attendance.write')) return null;
  const person = retry?.person ?? selected;
  const ownsSaved = saved && saved.actor === me.user_id && saved.company === me.company_id && saved.branch === me.branch_id && saved.terminal === terminalId;
  return <section className={embedded ? 'space-y-3' : 'card space-y-3 mb-4'} aria-label="Shared tablet attendance">
    <h4 className="font-medium">Staff clock in / out</h4>
    <p className="text-sm text-fg-muted">Use each employee's own password. The signed-in account and shop shift stay unchanged.</p>
    {notice && <p role="status">{notice}</p>}{error && <p role="alert" className="text-accent-bad">{error}</p>}
    <button className="btn btn-ghost" disabled={busy} onClick={() => void refresh()}>Refresh staff attendance</button>
    {saved ? ownsSaved ? <button className="btn btn-primary" disabled={busy} onClick={() => setRetry(saved)}>Retry retained attendance · {saved.person.name}</button>
      : <p>Return to the original workspace to resolve a saved staff clock action.</p>
      : ready && people.map(p => <button className="btn btn-ghost" key={p.id} disabled={busy} onClick={() => { setSelected(p); setBreaks(''); }}>{p.name} · {p.on_duty ? 'On duty / clock out' : 'Clock in'}</button>)}
    {cursor && !saved && <button className="btn btn-ghost" disabled={busy} onClick={() => void refresh(true)}>Load more staff</button>}
    <Modal open={Boolean(person)} onClose={() => { setPassword(''); setSelected(null); setRetry(null); }} title={`${person?.name ?? 'Staff'} · clock-${person?.on_duty ? 'out' : 'in'}`}>
      <div className="space-y-3">
        <label className="block">Staff password<input type="password" className="input" value={password} onChange={e => setPassword(e.target.value)} autoComplete="off" maxLength={256}/></label>
        {person?.on_duty && <label className="block">Actual break minutes, including 0<input className="input" inputMode="numeric" value={retry ? String(retry.break_minutes ?? '') : breaks} readOnly={Boolean(retry)} onChange={e => setBreaks(e.target.value)}/></label>}
        <button className="btn btn-primary" disabled={busy || !password} onClick={() => void confirm()}>Confirm clock-{person?.on_duty ? 'out' : 'in'}</button>
      </div>
    </Modal>
  </section>;
}
