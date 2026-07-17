/**
 * Gaming screen — stations + sessions (live + demo).
 *
 *  - List stations (PS5, VR, simulator, projector, shisha, streaming)
 *  - Add station (admin)
 *  - Edit / disable / delete station (admin)
 *  - Start session (live mode hits backend; demo runs a JS timer)
 *  - Stop session (records elapsed × rate; payment remains an explicit POS step)
 *  - Pause session (demo only — hidden in live mode until the backend supports it)
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Play, Square, Pause, PlayCircle, Gamepad2, Glasses, CarFront, Tv,
  Plus, Edit2, Trash2, Loader2, AlertCircle, RefreshCw, Settings, Flame,
  Timer, TimerOff, X, BellRing, BellOff, Bell, Send,
  Ban,
} from 'lucide-react';

import { ALARM_REPEAT_MS, fmtClock, notifyBrowser, playAlarmTone } from '@/lib/alarm';
import { LIVE_MODE } from '@/lib/demo';
import { STATIONS, type Station as DemoStation } from '@/lib/demo-data';
import { inr } from '@/lib/inr';
import { APP_STORE_REVIEW, isAppStoreAllowedType } from '@/lib/app-store-compliance';
import { gaming, shifts, type GamingPackageDTO, type StationDTO } from '@/lib/erp-api';
import { resolveRequiredOpenShift } from '@/lib/operational-context';
import { useAuth } from '@/modules/auth/AuthContext';
import Modal from '@/components/ui/Modal';

const ICON: Record<StationDTO['type'], React.ReactNode> = {
  ps5:       <Gamepad2 size={22}/>,
  vr:        <Glasses size={22}/>,
  simulator: <CarFront size={22}/>,
  projector: <Tv size={22}/>,
  hookah:    <Flame size={22}/>,
  streaming: <Tv size={22}/>,
};
const TYPE_LABEL: Record<StationDTO['type'], string> = {
  ps5: 'PlayStation 5',
  vr: 'VR Pod',
  simulator: 'Racing Simulator',
  projector: 'Projector',
  hookah: 'Shisha',
  streaming: 'Streaming Booth',
};

type LocalSession = {
  station_id: string;
  start_at: number;
  status: 'active' | 'paused' | 'ended';
  pausedMs: number;
  pause_started_at?: number;
  backend_session_id?: string;
  timer_minutes?: number | null;
  timer_ends_at?: number | null;
  ended_minutes?: number;
  ended_amount_minor?: number;
  package_id?: string | null;
  extra_controllers?: number;
  // Fixed, locked-in price for a package session — never recomputed from
  // elapsed time (see gaming/router.py stop_session). Undefined/null for an
  // open-ended (non-package) session, which bills off elapsed time instead.
  locked_amount_minor?: number | null;
};

const DURATION_PRESETS = [
  { label: 'No timer', minutes: null },
  { label: '30m', minutes: 30 },
  { label: '1h', minutes: 60 },
  { label: '2h', minutes: 120 },
] as const;

// Sessions only ever loaded once at mount otherwise — on a multi-device
// floor, if another staff member stops/starts/extends a session from a
// different screen, this screen would show stale state (a stopped session
// still ticking as "overtime") until someone happened to reload the page.
const GAMING_SESSIONS_POLL_MS = 30_000;

function notifyTimerExpired(stationName: string) {
  notifyBrowser(
    `⏰ ${stationName} — time's up`,
    'The session timer has ended. Stop the session or extend the timer.',
    `dcompany-timer-${stationName}`,
  );
}

export default function GamingScreen() {
  const { me, terminalId, terminalReady } = useAuth();
  const canManageStations = true;
  const [stations, setStations] = useState<StationDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sessions, setSessions] = useState<Record<string, LocalSession>>({});
  const [, setTick] = useState(0); // force overtime/countdown recompute every second
  const [pendingDuration, setPendingDuration] = useState<Record<string, number | null>>({});
  const [customDurationFor, setCustomDurationFor] = useState<string | null>(null);
  // Member phone attached at session start — carried through to the order
  // created at send-to-pos so loyalty points accrue automatically without
  // the cashier re-entering it at POS checkout (gaming/router.py already
  // wires GamingSession.customer_phone -> order.customer_phone -> points).
  const [sessionPhone, setSessionPhone] = useState<Record<string, string>>({});
  const [packages, setPackages] = useState<GamingPackageDTO[]>([]);
  const [pickerVariant, setPickerVariant] = useState<Record<string, string>>({});
  const [pickerControllers, setPickerControllers] = useState<Record<string, number>>({});
  const [mutedStations, setMutedStations] = useState<Record<string, boolean>>({});
  const [sendingToPos, setSendingToPos] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<string | null>(null);
  const [sendErrors, setSendErrors] = useState<Record<string, string>>({});
  const [notifPermission, setNotifPermission] = useState<NotificationPermission | 'unsupported'>(
    typeof Notification === 'undefined' ? 'unsupported' : Notification.permission,
  );

  // Refs so the 1s alarm-check interval (subscribed once) always sees fresh data
  // without re-subscribing every render.
  const sessionsRef = useRef(sessions);
  useEffect(() => { sessionsRef.current = sessions; }, [sessions]);
  const stationsRef = useRef(stations);
  useEffect(() => { stationsRef.current = stations; }, [stations]);
  const mutedRef = useRef(mutedStations);
  useEffect(() => { mutedRef.current = mutedStations; }, [mutedStations]);
  const lastAlarmAtRef = useRef<Record<string, number>>({});

  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<StationDTO | null>(null);
  const [manageMode, setManageMode] = useState(false);

  async function load() {
    setLoading(true); setError(null);
    try {
      if (LIVE_MODE) {
        const [stationRows, activeSessions, pausedSessions, endedSessions, packageRows] = await Promise.all([
          gaming.listStations(),
          gaming.listSessions('active'),
          gaming.listSessions('paused'),
          // Only stopped sessions still awaiting POS matter operationally.
          // Fetch enough to cover every station instead of letting paid
          // history push an older unbilled session out of the default window.
          gaming.listSessions('ended', { unbilledOnly: true, limit: 500 }),
          gaming.listPackages(),
        ]);
        setStations(stationRows.filter((station) => isAppStoreAllowedType(station.type)));
        setPackages(packageRows);
        // Rehydrate running sessions, AND any stopped-but-not-yet-sent session,
        // so a page refresh never silently drops an unbilled amount from view.
        setSessions((prev) => {
          const next: Record<string, LocalSession> = {};
          for (const gs of [...activeSessions, ...pausedSessions]) {
            next[gs.station_id] = prev[gs.station_id]?.backend_session_id === gs.id
              ? prev[gs.station_id]
              : {
                  station_id: gs.station_id,
                  start_at: new Date(gs.start_at).getTime(),
                  status: gs.status === 'paused' ? 'paused' : 'active',
                  pausedMs: 0,
                  backend_session_id: gs.id,
                  timer_minutes: gs.timer_minutes,
                  timer_ends_at: gs.timer_ends_at ? new Date(gs.timer_ends_at).getTime() : null,
                  package_id: gs.package_id,
                  extra_controllers: gs.extra_controllers,
                  locked_amount_minor: gs.package_id ? gs.amount_minor : null,
                };
          }
          for (const gs of endedSessions) {
            if (gs.order_id || next[gs.station_id]) continue;
            next[gs.station_id] = {
              station_id: gs.station_id,
              start_at: new Date(gs.start_at).getTime(),
              status: 'ended',
              pausedMs: 0,
              backend_session_id: gs.id,
              ended_minutes: gs.billable_minutes ?? 0,
              ended_amount_minor: gs.amount_minor ?? 0,
            };
          }
          return next;
        });
      } else {
        setStations(STATIONS.map(demoToDTO).filter((station) => isAppStoreAllowedType(station.type)));
      }
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  // Re-sync with the server periodically so another device's stop/start/
  // extend shows up here without a manual reload. load()'s merge logic
  // (see above) already reuses the existing local session object when the
  // backend session id hasn't changed, so this doesn't disrupt the locally
  // ticking countdown for sessions nothing happened to.
  useEffect(() => {
    if (!LIVE_MODE) return;
    const id = setInterval(() => { void load(); }, GAMING_SESSIONS_POLL_MS);
    return () => clearInterval(id);
  }, []);

  // Live ticking timer + alarm check. One interval drives both the countdown
  // re-render and the "has any timer just expired" scan, using refs so it
  // always reads current data without resubscribing every render.
  useEffect(() => {
    const id = setInterval(() => {
      setTick((n) => n + 1);
      const now = Date.now();
      for (const s of Object.values(sessionsRef.current)) {
        if (!s.timer_ends_at || now < s.timer_ends_at) continue;
        if (mutedRef.current[s.station_id]) continue;
        const last = lastAlarmAtRef.current[s.station_id];
        if (last && now - last < ALARM_REPEAT_MS) continue;
        lastAlarmAtRef.current[s.station_id] = now;
        playAlarmTone();
        const station = stationsRef.current.find((st) => st.id === s.station_id);
        notifyTimerExpired(station?.name ?? 'A station');
      }
    }, 1000);
    return () => clearInterval(id);
  }, []);

  function enableAlarmNotifications() {
    if (typeof Notification === 'undefined') return;
    Notification.requestPermission().then((perm) => setNotifPermission(perm));
  }

  function toggleMute(stationId: string) {
    setMutedStations((m) => ({ ...m, [stationId]: !m[stationId] }));
  }

  async function ensureShiftId(station: StationDTO) {
    const companyId = me?.company_id;
    const branchId = me?.branch_id;
    if (!companyId || !branchId) {
      throw new Error('This account has no branch assigned. Assign one before starting a session.');
    }
    if (!terminalReady || !terminalId) {
      throw new Error('Select the POS terminal used by this device before starting a session.');
    }
    return resolveRequiredOpenShift({
      scope: { companyId, branchId, terminalId },
      stationBranchId: station.branch_id,
      listOpenShifts: () => shifts.list(true),
    });
  }

  async function startSession(
    st: StationDTO,
    customer = '',
    pkg?: { packageId: string; extraControllers: number },
    phone = '',
  ) {
    const timerMinutes = pkg ? null : pendingDuration[st.id] ?? null;
    let backendId: string | undefined;
    let timerEndsAt: number | null = timerMinutes ? Date.now() + timerMinutes * 60000 : null;
    let packageId: string | undefined;
    let extraControllers = 0;
    let lockedAmountMinor: number | null = null;
    if (LIVE_MODE) {
      try {
        const shiftId = await ensureShiftId(st);
        const r = await gaming.startSession({
          station_id: st.id,
          shift_id: shiftId,
          customer_name: customer || undefined,
          customer_phone: phone.trim() || undefined,
          timer_minutes: timerMinutes ?? undefined,
          package_id: pkg?.packageId,
          extra_controllers: pkg?.extraControllers,
        });
        backendId = r.id;
        timerEndsAt = r.timer_ends_at ? new Date(r.timer_ends_at).getTime() : null;
        packageId = r.package_id ?? undefined;
        extraControllers = r.extra_controllers;
        lockedAmountMinor = packageId ? r.amount_minor ?? null : null;
      } catch (e) {
        alert(`Cannot start session: ${(e as Error).message}`);
        return;
      }
    }
    setSessions((s) => ({
      ...s,
      [st.id]: {
        station_id: st.id,
        start_at: Date.now(),
        status: 'active',
        pausedMs: 0,
        backend_session_id: backendId,
        timer_minutes: pkg ? (timerEndsAt ? Math.round((timerEndsAt - Date.now()) / 60000) : null) : timerMinutes,
        timer_ends_at: timerEndsAt,
        package_id: packageId,
        extra_controllers: extraControllers,
        locked_amount_minor: lockedAmountMinor,
      },
    }));
    setPendingDuration((p) => ({ ...p, [st.id]: null }));
    setPickerControllers((p) => ({ ...p, [st.id]: 0 }));
    setSessionPhone((p) => ({ ...p, [st.id]: '' }));
    setCustomDurationFor(null);
  }

  async function setStationTimer(st: StationDTO, minutes: number | null) {
    const s = sessions[st.id];
    if (!s) return;
    const timerEndsAt = minutes ? s.start_at + minutes * 60000 : null;
    if (LIVE_MODE && s.backend_session_id) {
      try { await gaming.setSessionTimer(s.backend_session_id, minutes); }
      catch (e) { alert(`Could not update timer: ${(e as Error).message}`); return; }
    }
    setSessions((map) => ({
      ...map,
      [st.id]: { ...s, timer_minutes: minutes, timer_ends_at: timerEndsAt },
    }));
    // Re-arm: a changed timer should alarm fresh next time it expires, not immediately.
    delete lastAlarmAtRef.current[st.id];
    setMutedStations((m) => (m[st.id] ? { ...m, [st.id]: false } : m));
  }

  function extendTimer(st: StationDTO, addMinutes: number) {
    const s = sessions[st.id];
    if (!s) return;
    const elapsedMinutesNow = Math.max(0, Math.ceil((Date.now() - s.start_at) / 60000));
    const baseMinutes = s.timer_minutes ?? elapsedMinutesNow;
    setStationTimer(st, Math.min(1440, baseMinutes + addMinutes));
  }

  // A package session is a prepaid, fixed-price slot — extending it is a
  // paid action (buy an extension package), not a free timer bump like
  // extendTimer above (which only applies to open-ended, no-package sessions).
  async function extendPackageSession(st: StationDTO, extensionPackageId: string) {
    const s = sessions[st.id];
    if (!s?.backend_session_id) return;
    try {
      const r = await gaming.extendSessionWithPackage(s.backend_session_id, extensionPackageId);
      setSessions((map) => ({
        ...map,
        [st.id]: {
          ...s,
          timer_minutes: r.timer_minutes,
          timer_ends_at: r.timer_ends_at ? new Date(r.timer_ends_at).getTime() : s.timer_ends_at,
          locked_amount_minor: r.amount_minor ?? s.locked_amount_minor,
        },
      }));
      delete lastAlarmAtRef.current[st.id];
      setMutedStations((m) => (m[st.id] ? { ...m, [st.id]: false } : m));
    } catch (e) {
      alert(`Could not extend session: ${(e as Error).message}`);
    }
  }

  function packagesFor(stationType: string, kind: 'base' | 'extension') {
    return packages.filter((p) => p.station_type === stationType && p.kind === kind);
  }

  function variantsFor(stationType: string) {
    return Array.from(new Set(packagesFor(stationType, 'base').map((p) => p.variant)));
  }

  function pauseSession(st: StationDTO) {
    const s = sessions[st.id];
    if (!s || s.status === 'paused') return;
    setSessions((map) => ({
      ...map,
      [st.id]: { ...s, status: 'paused', pause_started_at: Date.now() },
    }));
  }
  function resumeSession(st: StationDTO) {
    const s = sessions[st.id];
    if (!s || s.status !== 'paused' || !s.pause_started_at) return;
    setSessions((map) => ({
      ...map,
      [st.id]: {
        ...s, status: 'active',
        pausedMs: s.pausedMs + (Date.now() - s.pause_started_at!),
        pause_started_at: undefined,
      },
    }));
  }
  async function stopSession(st: StationDTO) {
    const s = sessions[st.id];
    if (!s) return;
    const elapsedMs = Date.now() - s.start_at - s.pausedMs;
    let elapsedMin = Math.max(1, Math.ceil(elapsedMs / 60000));
    let amount = Math.ceil((elapsedMin / 60) * st.rate_per_hour_minor);
    if (LIVE_MODE && s.backend_session_id) {
      try {
        const ended = await gaming.stopSession(s.backend_session_id);
        elapsedMin = ended.billable_minutes ?? elapsedMin;
        amount = ended.amount_minor ?? amount;
      }
      catch (e) { alert(`Stop failed: ${(e as Error).message}`); return; }
      delete lastAlarmAtRef.current[st.id];
      setMutedStations((m) => (st.id in m ? { ...m, [st.id]: false } : m));
      // Keep the tile visible in a "stopped" state — staff must explicitly
      // send it to POS, same accountability rule as shift opening.
      setSessions((map) => ({
        ...map,
        [st.id]: { ...s, status: 'ended', ended_minutes: elapsedMin, ended_amount_minor: amount },
      }));
      return;
    }
    // Demo mode has no real backend order to send to — keep the old flow.
    alert(`Session ended\n\nStation: ${st.name}\nDuration: ${elapsedMin} min\nSession estimate: ${inr(amount)}`);
    setSessions((map) => {
      const next = { ...map };
      delete next[st.id];
      return next;
    });
    delete lastAlarmAtRef.current[st.id];
    setMutedStations((m) => (st.id in m ? { ...m, [st.id]: false } : m));
  }

  async function sendToPos(st: StationDTO) {
    const s = sessions[st.id];
    if (!s?.backend_session_id) return;
    setSendingToPos(st.id);
    setSendErrors((errs) => {
      if (!(st.id in errs)) return errs;
      const next = { ...errs };
      delete next[st.id];
      return next;
    });
    try {
      await gaming.sendToPos(s.backend_session_id);
      setSessions((map) => {
        const next = { ...map };
        delete next[st.id];
        return next;
      });
    } catch (e) {
      setSendErrors((errs) => ({ ...errs, [st.id]: (e as Error).message }));
    } finally {
      setSendingToPos(null);
    }
  }

  async function cancelSession(st: StationDTO) {
    const current = sessions[st.id];
    if (!current?.backend_session_id) return;
    const reason = prompt(
      `Why are you cancelling the stopped session for ${st.name}?\n\n`
      + 'This keeps an audit trail and removes it from billing.',
    );
    if (!reason?.trim()) return;
    setCancelling(st.id);
    setSendErrors((errors) => {
      const next = { ...errors };
      delete next[st.id];
      return next;
    });
    try {
      await gaming.cancelSession(current.backend_session_id, reason.trim());
      setSessions((all) => {
        const next = { ...all };
        delete next[st.id];
        return next;
      });
    } catch (e) {
      setSendErrors((errors) => ({ ...errors, [st.id]: (e as Error).message }));
    } finally {
      setCancelling(null);
    }
  }

  async function deleteStation(st: StationDTO) {
    if (!confirm(`Delete station ${st.code}?`)) return;
    try { await gaming.deleteStation(st.id); await load(); }
    catch (e) { alert((e as Error).message); }
  }

  const activeCount = useMemo(
    () => Object.values(sessions).filter((s) => s.status === 'active').length,
    [sessions],
  );
  const overtimeStations = (() => {
    const now = Date.now();
    return Object.values(sessions)
      .filter((s) => s.timer_ends_at && now >= s.timer_ends_at)
      .map((s) => stations.find((st) => st.id === s.station_id)?.name ?? 'Unknown station');
  })();

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Gaming lounge</h2>
          <p className="text-fg-muted text-sm">
            {stations.length} stations · {activeCount} active session{activeCount === 1 ? '' : 's'}
          </p>
        </div>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          {canManageStations && (
            <button className={`btn ${manageMode ? 'btn-primary' : 'btn-ghost'}`}
              onClick={() => setManageMode(!manageMode)}>
              <Settings size={14}/> {manageMode ? 'Done' : 'Manage'}
            </button>
          )}
          {canManageStations && manageMode && (
            <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
              <Plus size={14}/> New station
            </button>
          )}
        </div>
      </header>

      {error && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
          <AlertCircle size={14}/> {error}
        </div>
      )}

      {overtimeStations.length > 0 && (
        <div className="card mb-4 border-accent-bad/50 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2 font-bold">
          <BellRing size={16} className="animate-pulse"/>
          {overtimeStations.length} station{overtimeStations.length === 1 ? '' : 's'} timed out: {overtimeStations.join(', ')}
        </div>
      )}

      {notifPermission === 'default' && (
        <div className="card mb-4 border-accent/40 bg-accent/10 text-sm flex items-center justify-between gap-3 flex-wrap">
          <span className="flex items-center gap-2"><Bell size={14}/> Turn on browser alerts so staff get notified the moment a session's timer ends.</span>
          <button className="btn btn-ghost !py-1.5" onClick={enableAlarmNotifications}>Enable notifications</button>
        </div>
      )}

      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : !stations.length ? (
        <div className="card text-fg-muted text-sm">
          {canManageStations
            ? <>No stations yet. Click <b>Manage → New station</b> to add PS5, VR, simulator, shisha, or streaming stations.</>
            : 'No stations configured yet.'}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3 md:gap-4">
          {stations.map((st) => {
            const session = sessions[st.id];
            const phone = sessionPhone[st.id] ?? '';
            const elapsedMs = session
              ? (session.status === 'paused' && session.pause_started_at
                  ? session.pause_started_at - session.start_at - session.pausedMs
                  : Date.now() - session.start_at - session.pausedMs)
              : 0;
            const elapsedMin = Math.floor(elapsedMs / 60000);
            // A package session's price is locked in at start (see backend
            // gaming/router.py stop_session) and never grows with elapsed
            // time — showing the elapsed*rate estimate here would be flat
            // wrong, not just approximate.
            const amount = session?.locked_amount_minor != null
              ? session.locked_amount_minor
              : Math.ceil((elapsedMs / 3600000) * st.rate_per_hour_minor);

            return (
              <div key={st.id} className={`card ${session ? 'border-accent/40' : ''}`}>
                <div className="flex items-start gap-3 mb-3">
                  <div className="p-3 rounded-xl bg-bg-raised text-accent">{ICON[st.type]}</div>
                  <div className="flex-1 min-w-0">
                    <div className="font-bold truncate">{st.name}</div>
                    <div className="text-xs text-fg-muted truncate">
                      {st.code} · {TYPE_LABEL[st.type]} · {inr(st.rate_per_hour_minor)}/hr
                    </div>
                    {!st.is_active && (
                      <span className="chip text-[10px] border-accent-bad/40 text-accent-bad mt-1">
                        Inactive
                      </span>
                    )}
                  </div>
                  {canManageStations && manageMode && (
                    <div className="flex flex-col gap-1">
                      <button className="text-fg-muted hover:text-accent p-1"
                        onClick={() => setEdit(st)}>
                        <Edit2 size={12}/>
                      </button>
                      <button className="text-fg-muted hover:text-accent-bad p-1"
                        onClick={() => deleteStation(st)}>
                        <Trash2 size={12}/>
                      </button>
                    </div>
                  )}
                </div>

                {session?.status === 'ended' ? (
                  <>
                    <div className="bg-bg-raised rounded-lg p-3 mb-3">
                      <div className="text-xs text-fg-muted">Session ended</div>
                      <div className="flex justify-between items-baseline mt-1">
                        <div className="text-xl font-bold font-mono">
                          {session.ended_minutes ?? 0} min
                        </div>
                        <div className="text-2xl font-bold font-mono text-accent">
                          {inr(session.ended_amount_minor ?? 0)}
                        </div>
                      </div>
                      {sendErrors[st.id] && (
                        <div className="mt-2 pt-2 border-t border-bg-border text-xs text-accent-bad flex items-center gap-1.5">
                          <AlertCircle size={12}/> {sendErrors[st.id]}
                        </div>
                      )}
                    </div>
                    <div className="grid grid-cols-[1fr_auto] gap-2">
                      <button className="btn btn-primary"
                        disabled={sendingToPos === st.id || cancelling === st.id}
                        onClick={() => sendToPos(st)}>
                        {sendingToPos === st.id
                          ? <Loader2 className="animate-spin" size={14}/>
                          : <Send size={14}/>} Send to POS
                      </button>
                      <button className="btn btn-ghost text-accent-bad"
                        disabled={sendingToPos === st.id || cancelling === st.id}
                        title="Cancel with an audit reason"
                        onClick={() => cancelSession(st)}>
                        {cancelling === st.id
                          ? <Loader2 className="animate-spin" size={14}/>
                          : <Ban size={14}/>} Cancel
                      </button>
                    </div>
                  </>
                ) : session ? (
                  <>
                    <div className="bg-bg-raised rounded-lg p-3 mb-3">
                      <div className="flex justify-between items-baseline">
                        <div>
                          <div className="text-xs text-fg-muted">Elapsed</div>
                          <div className="text-2xl font-bold font-mono">
                            {Math.floor(elapsedMin / 60)}:{String(elapsedMin % 60).padStart(2, '0')}
                          </div>
                        </div>
                        <div className="text-right">
                          <div className="text-xs text-fg-muted">
                            {session?.locked_amount_minor != null ? 'Package price' : 'Running bill'}
                          </div>
                          <div className="text-2xl font-bold font-mono text-accent">
                            {inr(amount)}
                          </div>
                        </div>
                      </div>
                      {session.status === 'paused' && (
                        <div className="mt-2 text-xs text-accent-gold flex items-center gap-1">
                          <Pause size={11}/> Paused
                        </div>
                      )}

                      {session.timer_ends_at ? (() => {
                        const remainingMs = session.timer_ends_at! - Date.now();
                        const overtime = remainingMs <= 0;
                        const lowTime = !overtime && remainingMs <= 5 * 60000;
                        const clock = fmtClock(Math.abs(Math.round(remainingMs / 1000)));
                        const extensionOptions = session.package_id ? packagesFor(st.type, 'extension') : [];
                        return (
                          <div className={`mt-2 pt-2 border-t border-bg-border flex items-center justify-between gap-2 flex-wrap ${
                            overtime ? 'text-accent-bad' : lowTime ? 'text-accent-gold' : 'text-fg-muted'
                          }`}>
                            <div className="flex items-center gap-1.5 text-sm font-mono font-bold">
                              <Timer size={13}/> {overtime ? `+${clock} over` : `${clock} left`}
                            </div>
                            <div className="flex items-center gap-1 flex-wrap">
                              {overtime && (
                                <button className="text-fg-muted hover:text-accent p-0.5"
                                  onClick={() => toggleMute(st.id)}
                                  title={mutedStations[st.id] ? 'Unmute alarm' : 'Mute alarm for this station'}>
                                  {mutedStations[st.id] ? <BellOff size={13}/> : <Bell size={13}/>}
                                </button>
                              )}
                              {session.package_id ? (
                                extensionOptions.length > 0 ? extensionOptions.map((ext) => (
                                  <button key={ext.id}
                                    className="chip text-[10px] !border-accent-gold/50 text-accent-gold hover:!border-accent-gold"
                                    onClick={() => {
                                      if (!confirm(`Add ${ext.name} for ${inr(ext.price_minor)}? This charges the customer's bill.`)) return;
                                      extendPackageSession(st, ext.id);
                                    }}
                                    title={`Paid extension: ${ext.name} · ${inr(ext.price_minor)}`}>
                                    +{ext.duration_minutes}m · {inr(ext.price_minor)}
                                  </button>
                                )) : (
                                  <span className="text-[10px] text-fg-muted">No extension for this package</span>
                                )
                              ) : (
                                <>
                                  <button className="chip text-[10px] hover:border-accent"
                                    onClick={() => extendTimer(st, 15)} title="Add 15 minutes">
                                    +15m
                                  </button>
                                  <button className="text-fg-muted hover:text-accent-bad p-0.5"
                                    onClick={() => setStationTimer(st, null)} title="Clear timer">
                                    <X size={13}/>
                                  </button>
                                </>
                              )}
                            </div>
                          </div>
                        );
                      })() : (
                        <div className="mt-2 pt-2 border-t border-bg-border flex items-center justify-between gap-2 text-xs text-fg-muted">
                          <span className="flex items-center gap-1"><TimerOff size={12}/> No timer</span>
                          <div className="flex gap-1">
                            {[30, 60, 120].map((m) => (
                              <button key={m} className="chip text-[10px] hover:border-accent"
                                onClick={() => setStationTimer(st, m)}>
                                +{m >= 60 ? `${m / 60}h` : `${m}m`}
                              </button>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                    <div className="flex gap-2">
                      {!LIVE_MODE && (session.status === 'active' ? (
                        <button className="btn btn-ghost flex-1" onClick={() => pauseSession(st)}>
                          <Pause size={14}/> Pause
                        </button>
                      ) : (
                        <button className="btn btn-ghost flex-1" onClick={() => resumeSession(st)}>
                          <PlayCircle size={14}/> Resume
                        </button>
                      ))}
                      <button className="btn btn-primary flex-1 !bg-accent-bad hover:!bg-accent-bad/80"
                        onClick={() => stopSession(st)}>
                        <Square size={14}/> End session
                      </button>
                    </div>
                  </>
                ) : variantsFor(st.type).length > 0 ? (() => {
                  const variants = variantsFor(st.type);
                  const variant = pickerVariant[st.id] ?? variants[0];
                  const tiers = packagesFor(st.type, 'base').filter((p) => p.variant === variant);
                  const controllers = pickerControllers[st.id] ?? 0;
                  const showControllerStepper = st.type === 'ps5' && variant === 'dual';
                  return (
                    <>
                      <input type="tel" placeholder="Member phone (optional) — points accrue automatically"
                        className="input !py-1.5 text-xs w-full mb-2"
                        value={phone}
                        onChange={(e) => setSessionPhone((s) => ({ ...s, [st.id]: e.target.value }))}/>
                      {variants.length > 1 && (
                        <div className="flex items-center gap-1.5 mb-2">
                          {variants.map((v) => (
                            <button key={v}
                              className={`chip text-[11px] capitalize ${variant === v ? '!border-accent !text-accent' : 'hover:border-accent'}`}
                              onClick={() => {
                                setPickerVariant((s) => ({ ...s, [st.id]: v }));
                                // Extra-controller count only makes sense for
                                // the dual variant's stepper — stop it from
                                // silently surviving a switch to single.
                                setPickerControllers((s) => ({ ...s, [st.id]: 0 }));
                              }}>
                              {v}
                            </button>
                          ))}
                        </div>
                      )}
                      <div className="grid grid-cols-1 gap-1.5 mb-2">
                        {tiers.map((tier) => (
                          <button key={tier.id}
                            className="btn btn-ghost !justify-between !py-1.5 text-xs"
                            onClick={() => startSession(st, '', { packageId: tier.id, extraControllers: showControllerStepper ? controllers : 0 }, phone)}
                            disabled={!st.is_active}>
                            <span>{tier.name}</span>
                            <span className="font-mono font-bold">{inr(tier.price_minor)}</span>
                          </button>
                        ))}
                      </div>
                      {showControllerStepper && (
                        <div className="flex items-center justify-between gap-2 mb-2 text-xs text-fg-muted">
                          <span>Extra controllers (₹30/hr, min ₹30)</span>
                          <div className="flex items-center gap-2">
                            <button className="chip !px-2 text-[11px]"
                              onClick={() => setPickerControllers((s) => ({ ...s, [st.id]: Math.max(0, controllers - 1) }))}>
                              −
                            </button>
                            <span className="w-4 text-center font-mono">{controllers}</span>
                            <button className="chip !px-2 text-[11px]"
                              onClick={() => setPickerControllers((s) => ({ ...s, [st.id]: Math.min(6, controllers + 1) }))}>
                              +
                            </button>
                          </div>
                        </div>
                      )}
                    </>
                  );
                })() : (
                  <>
                    <input type="tel" placeholder="Member phone (optional) — points accrue automatically"
                      className="input !py-1.5 text-xs w-full mb-2"
                      value={phone}
                      onChange={(e) => setSessionPhone((s) => ({ ...s, [st.id]: e.target.value }))}/>
                    <div className="flex items-center gap-1.5 mb-2 flex-wrap">
                      {DURATION_PRESETS.map((p) => (
                        <button key={p.label}
                          className={`chip text-[11px] ${(pendingDuration[st.id] ?? null) === p.minutes && customDurationFor !== st.id ? '!border-accent !text-accent' : 'hover:border-accent'}`}
                          onClick={() => { setPendingDuration((s) => ({ ...s, [st.id]: p.minutes })); setCustomDurationFor(null); }}>
                          {p.label}
                        </button>
                      ))}
                      <button
                        className={`chip text-[11px] ${customDurationFor === st.id ? '!border-accent !text-accent' : 'hover:border-accent'}`}
                        onClick={() => setCustomDurationFor(customDurationFor === st.id ? null : st.id)}>
                        Custom
                      </button>
                    </div>
                    {customDurationFor === st.id && (
                      <div className="flex items-center gap-2 mb-2">
                        <input type="number" min={1} max={1440} placeholder="minutes"
                          className="input !py-1.5 text-sm flex-1"
                          value={pendingDuration[st.id] ?? ''}
                          onChange={(e) => setPendingDuration((s) => ({
                            ...s, [st.id]: e.target.value ? Math.max(1, Math.min(1440, Number(e.target.value))) : null,
                          }))}/>
                        <span className="text-xs text-fg-muted">min</span>
                      </div>
                    )}
                    <button className="btn btn-primary w-full" onClick={() => startSession(st, '', undefined, phone)}
                      disabled={!st.is_active}>
                      <Play size={14}/> Start session
                      {pendingDuration[st.id] ? ` · ${pendingDuration[st.id]}m` : ''}
                    </button>
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}

      {canManageStations && addOpen && <StationForm onClose={() => setAddOpen(false)}
        onSuccess={() => { setAddOpen(false); load(); }}/>}
      {canManageStations && edit && <StationForm station={edit} onClose={() => setEdit(null)}
        onSuccess={() => { setEdit(null); load(); }}/>}
    </div>
  );
}

// ---------------------------------------------------------------- StationForm
function StationForm({
  station, onClose, onSuccess,
}: { station?: StationDTO; onClose: () => void; onSuccess: () => void }) {
  const isEdit = !!station;
  const [form, setForm] = useState({
    code: station?.code ?? '',
    name: station?.name ?? '',
    type: station?.type ?? 'ps5' as StationDTO['type'],
    rate_rupees: station ? (station.rate_per_hour_minor / 100).toFixed(2) : '200',
    is_active: station?.is_active ?? true,
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const rate_per_hour_minor = Math.round(parseFloat(form.rate_rupees || '0') * 100);
      if (isEdit) {
        await gaming.updateStation(station!.id, {
          name: form.name, rate_per_hour_minor, is_active: form.is_active,
        });
      } else {
        await gaming.createStation({
          code: form.code, name: form.name, type: form.type, rate_per_hour_minor,
        });
      }
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  const hasPricingUnlock =
    !!localStorage.getItem('pricing_token') &&
    Number(localStorage.getItem('pricing_token_expires_at') || '0') > Date.now();

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${station!.name}` : 'New gaming station'}>
      <form onSubmit={submit} className="space-y-3">
        {!hasPricingUnlock && LIVE_MODE && (
          <div className="p-2.5 rounded-lg bg-accent-gold/10 border border-accent-gold/30 text-accent-gold text-xs">
            Station setup changes are pricing locked. Unlock pricing in Settings before creating, editing, or deleting stations.
          </div>
        )}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Code (e.g. PS5-01, VR-02, SIM-01)">
            <input className="input font-mono" required disabled={isEdit}
              value={form.code}
              onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })}/>
          </Field>
          <Field label="Type">
            <select className="input" disabled={isEdit} value={form.type}
              onChange={(e) => setForm({ ...form, type: e.target.value as StationDTO['type'] })}>
              <option value="ps5">PlayStation 5</option>
              <option value="vr">VR Pod</option>
              <option value="simulator">Racing Simulator</option>
              <option value="projector">Projector</option>
              {!APP_STORE_REVIEW && <option value="hookah">Hookah Lounge</option>}
              <option value="streaming">Streaming Booth</option>
            </select>
          </Field>
        </div>
        <Field label="Display name">
          <input className="input" required value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <Field label="Rate per hour (₹, incl. 18% GST)">
          <input type="number" min={0} step="0.01" required className="input font-mono text-right"
            value={form.rate_rupees}
            onChange={(e) => setForm({ ...form, rate_rupees: e.target.value })}/>
        </Field>
        {isEdit && (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={form.is_active}
              onChange={(e) => setForm({ ...form, is_active: e.target.checked })}/>
            Station is active (available for sessions)
          </label>
        )}
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            {isEdit ? 'Save' : 'Create'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs text-fg-muted">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}
function ErrorRow({ text }: { text: string }) {
  return (
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
      <AlertCircle size={14}/> {text}
    </div>
  );
}
function demoToDTO(s: DemoStation): StationDTO {
  return {
    id: s.id, branch_id: 'demo-branch', code: s.code, name: s.name,
    type: s.type as StationDTO['type'],
    rate_per_hour_minor: 20000, is_active: true,
  };
}
