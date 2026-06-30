/**
 * Gaming screen — stations + sessions (live + demo).
 *
 *  - List stations (PS5, VR, simulator, projector, shisha, streaming)
 *  - Add station (admin)
 *  - Edit / disable / delete station (admin)
 *  - Start session (live mode hits backend; demo runs a JS timer)
 *  - Stop session (computes elapsed × rate, prints bill)
 *  - Pause session (demo only — backend doesn't have pause yet)
 */
import { useEffect, useMemo, useState } from 'react';
import {
  Play, Square, Pause, PlayCircle, Gamepad2, Glasses, CarFront, Tv,
  Plus, Edit2, Trash2, Loader2, AlertCircle, RefreshCw, Settings, Flame,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { STATIONS, type Station as DemoStation } from '@/lib/demo-data';
import { inr } from '@/lib/inr';
import { APP_STORE_REVIEW, isAppStoreAllowedType } from '@/lib/app-store-compliance';
import { gaming, type StationDTO } from '@/lib/erp-api';
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
  status: 'active' | 'paused';
  pausedMs: number;
  pause_started_at?: number;
  backend_session_id?: string;
};

export default function GamingScreen() {
  const { me, demo } = useAuth();
  const canManageStations = Boolean(demo || me?.protected_access);
  const [stations, setStations] = useState<StationDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sessions, setSessions] = useState<Record<string, LocalSession>>({});
  const [, setTick] = useState(0); // forces re-render every second

  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<StationDTO | null>(null);
  const [manageMode, setManageMode] = useState(false);

  async function load() {
    setLoading(true); setError(null);
    try {
      if (LIVE_MODE) setStations((await gaming.listStations()).filter((station) => isAppStoreAllowedType(station.type)));
      else setStations(STATIONS.map(demoToDTO).filter((station) => isAppStoreAllowedType(station.type)));
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  // Live ticking timer
  useEffect(() => {
    const id = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, []);

  async function startSession(st: StationDTO, customer = '') {
    let backendId: string | undefined;
    if (LIVE_MODE) {
      try {
        // Need a shift_id; for now use a placeholder UUID. Real fix: open/track shifts.
        const r = await gaming.startSession({
          station_id: st.id,
          shift_id: '00000000-0000-0000-0000-000000000000',
          customer_name: customer || undefined,
        });
        backendId = r.id;
      } catch (e) {
        alert(`Cannot start session: ${(e as Error).message}\n\nDid you open a shift in POS first?`);
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
      },
    }));
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
    const elapsedMin = Math.max(1, Math.ceil(elapsedMs / 60000));
    const amount = Math.ceil((elapsedMin / 60) * st.rate_per_hour_minor);
    if (LIVE_MODE && s.backend_session_id) {
      try { await gaming.stopSession(s.backend_session_id); }
      catch (e) { alert(`Stop failed: ${(e as Error).message}`); return; }
    }
    alert(
      `Session ended\n\nStation: ${st.name}\nDuration: ${elapsedMin} min\nAmount: ${inr(amount)}\n\n` +
      `Charge the customer ${inr(amount)} via POS.`
    );
    setSessions((map) => {
      const next = { ...map };
      delete next[st.id];
      return next;
    });
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
            const elapsedMs = session
              ? (session.status === 'paused' && session.pause_started_at
                  ? session.pause_started_at - session.start_at - session.pausedMs
                  : Date.now() - session.start_at - session.pausedMs)
              : 0;
            const elapsedMin = Math.floor(elapsedMs / 60000);
            const amount = Math.ceil((elapsedMs / 3600000) * st.rate_per_hour_minor);

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

                {session ? (
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
                          <div className="text-xs text-fg-muted">Running bill</div>
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
                    </div>
                    <div className="flex gap-2">
                      {session.status === 'active' ? (
                        <button className="btn btn-ghost flex-1" onClick={() => pauseSession(st)}>
                          <Pause size={14}/> Pause
                        </button>
                      ) : (
                        <button className="btn btn-ghost flex-1" onClick={() => resumeSession(st)}>
                          <PlayCircle size={14}/> Resume
                        </button>
                      )}
                      <button className="btn btn-primary flex-1 !bg-accent-bad hover:!bg-accent-bad/80"
                        onClick={() => stopSession(st)}>
                        <Square size={14}/> Stop &amp; bill
                      </button>
                    </div>
                  </>
                ) : (
                  <button className="btn btn-primary w-full" onClick={() => startSession(st)}
                    disabled={!st.is_active}>
                    <Play size={14}/> Start session
                  </button>
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

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${station!.name}` : 'New gaming station'}>
      <form onSubmit={submit} className="space-y-3">
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
    id: s.id, code: s.code, name: s.name,
    type: s.type as StationDTO['type'],
    rate_per_hour_minor: 20000, is_active: true,
  };
}
