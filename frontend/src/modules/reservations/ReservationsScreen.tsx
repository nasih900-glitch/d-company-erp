/**
 * Reservations — makes the two booking flows that already existed
 * (table reservations, gaming station bookings) visible to staff after
 * creation. Until this screen, both were create-only: a booking landed in
 * the database and was never seen or actioned again.
 *
 * Tabs:
 *   Tables   — dine-in table bookings (held -> seated/no_show/cancelled)
 *   Gaming   — PS5/VR/simulator station bookings (held -> consumed/no_show/cancelled)
 *
 * Both tabs list upcoming bookings only (today onward) and offer the same
 * three actions on a still-pending ("held") row: mark arrived, no-show, or
 * cancel. Once a booking leaves "held" it's terminal — the action buttons
 * disappear and only the settled status chip remains.
 */
import { useCallback, useEffect, useState } from 'react';
import {
  CalendarClock, LayoutGrid, Gamepad2, Loader2, AlertCircle, RefreshCw,
  UserCheck, UserX, Ban,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { inr } from '@/lib/inr';
import {
  reservations, gaming,
  type ReservationDTO, type GamingBookingDTO,
} from '@/lib/erp-api';
import { subscribeRealtime } from '@/lib/realtime';

type Tab = 'tables' | 'gaming';
// Fallback only — real-time push (see subscribeRealtime below) is what
// actually keeps this current; this just covers a missed/dropped push.
const RESERVATIONS_POLL_MS = 120_000;

export default function ReservationsScreen() {
  const [tab, setTab] = useState<Tab>('tables');

  return (
    <div>
      <header className="mb-6">
        <h2 className="text-2xl font-bold">Reservations</h2>
        <p className="text-fg-muted text-sm">
          Upcoming table &amp; gaming station bookings
        </p>
      </header>

      <div className="scroll-strip flex gap-1 mb-6 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'tables'} onClick={() => setTab('tables')}>
          <LayoutGrid size={14}/> Tables
        </TabBtn>
        <TabBtn active={tab === 'gaming'} onClick={() => setTab('gaming')}>
          <Gamepad2 size={14}/> Gaming
        </TabBtn>
      </div>

      {tab === 'tables' ? <TableReservationsTab/> : <GamingBookingsTab/>}
    </div>
  );
}

// ============================================================================
// Table reservations
// ============================================================================
function TableReservationsTab() {
  const [rows, setRows] = useState<ReservationDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!LIVE_MODE) { setLoading(false); return; }
    setLoading(true); setErr(null);
    try { setRows(await reservations.list()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (!LIVE_MODE) return;
    const refresh = () => { reservations.list().then(setRows).catch(() => {}); };
    const unsubscribe = subscribeRealtime('tables', refresh);
    const id = setInterval(refresh, RESERVATIONS_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, []);

  async function act(id: string, status: 'seated' | 'no_show' | 'cancelled') {
    setBusyId(id); setErr(null);
    try {
      const updated = await reservations.updateStatus(id, status);
      setRows((prev) => prev.map((r) => (r.id === id ? updated : r)));
    } catch (e) { setErr((e as Error).message); }
    finally { setBusyId(null); }
  }

  if (!LIVE_MODE) return <div className="card text-fg-muted text-sm">Reservations are live-mode only.</div>;
  if (loading) return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;

  return (
    <div>
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          {rows.length} upcoming reservation{rows.length === 1 ? '' : 's'}
        </p>
        <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
      </div>

      {err && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {err}
      </div>}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No upcoming reservations. New bookings taken from <b>Tables</b> will show up here.
        </div>
      ) : (
        <div className="space-y-3">
          {rows.map((r) => (
            <div key={r.id} className="card">
              <div className="flex justify-between items-start gap-3 flex-wrap">
                <div>
                  <div className="font-bold flex items-center gap-2">
                    <span className="chip text-[10px]">Table {r.table_code}</span>
                    {r.guest_name} · {r.party_size} guest{r.party_size === 1 ? '' : 's'}
                  </div>
                  <div className="text-xs text-fg-muted flex items-center gap-1 mt-1">
                    <CalendarClock size={12}/> {new Date(r.starts_at).toLocaleString('en-IN')}
                    {r.contact ? ` · ${r.contact}` : ''}
                  </div>
                  {r.notes && <div className="text-xs text-fg-muted mt-1">{r.notes}</div>}
                </div>
                <StatusActions
                  status={r.status}
                  busy={busyId === r.id}
                  onArrived={() => act(r.id, 'seated')}
                  onNoShow={() => act(r.id, 'no_show')}
                  onCancel={() => act(r.id, 'cancelled')}
                  arrivedLabel="Mark arrived"
                />
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Gaming station bookings
// ============================================================================
function GamingBookingsTab() {
  const [rows, setRows] = useState<GamingBookingDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!LIVE_MODE) { setLoading(false); return; }
    setLoading(true); setErr(null);
    try { setRows(await gaming.listBookings()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (!LIVE_MODE) return;
    const refresh = () => { gaming.listBookings().then(setRows).catch(() => {}); };
    const unsubscribe = subscribeRealtime('gaming', refresh);
    const id = setInterval(refresh, RESERVATIONS_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, []);

  async function act(id: string, status: 'consumed' | 'no_show' | 'cancelled') {
    setBusyId(id); setErr(null);
    try {
      const updated = await gaming.updateBookingStatus(id, status);
      setRows((prev) => prev.map((b) => (b.id === id ? updated : b)));
    } catch (e) { setErr((e as Error).message); }
    finally { setBusyId(null); }
  }

  if (!LIVE_MODE) return <div className="card text-fg-muted text-sm">Bookings are live-mode only.</div>;
  if (loading) return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;

  return (
    <div>
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          {rows.length} upcoming booking{rows.length === 1 ? '' : 's'}
        </p>
        <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
      </div>

      {err && <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm mb-3 flex items-center gap-2">
        <AlertCircle size={14}/> {err}
      </div>}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No upcoming station bookings. New bookings taken from <b>Gaming</b> will show up here.
        </div>
      ) : (
        <div className="space-y-3">
          {rows.map((b) => (
            <div key={b.id} className="card">
              <div className="flex justify-between items-start gap-3 flex-wrap">
                <div>
                  <div className="font-bold flex items-center gap-2">
                    <span className="chip text-[10px]">{b.station_code}</span>
                    {b.guest_name} · {b.party_size} guest{b.party_size === 1 ? '' : 's'}
                  </div>
                  <div className="text-xs text-fg-muted flex items-center gap-1 mt-1">
                    <CalendarClock size={12}/> {new Date(b.starts_at).toLocaleString('en-IN')}
                    {b.contact ? ` · ${b.contact}` : ''}
                    {b.deposit_minor > 0 ? ` · Deposit ${inr(b.deposit_minor)}` : ''}
                  </div>
                </div>
                <StatusActions
                  status={b.status}
                  busy={busyId === b.id}
                  onArrived={() => act(b.id, 'consumed')}
                  onNoShow={() => act(b.id, 'no_show')}
                  onCancel={() => act(b.id, 'cancelled')}
                  arrivedLabel="Mark used"
                />
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ============================================================================
// bits
// ============================================================================
function StatusActions({ status, busy, onArrived, onNoShow, onCancel, arrivedLabel }: {
  status: string; busy: boolean;
  onArrived: () => void; onNoShow: () => void; onCancel: () => void;
  arrivedLabel: string;
}) {
  if (status !== 'held') {
    return (
      <span className={`chip text-[10px] shrink-0 ${
        status === 'seated' || status === 'consumed' ? 'border-accent-good/40 text-accent-good' :
        status === 'no_show' ? 'border-accent-bad/40 text-accent-bad' :
                               'border-fg-muted/40 text-fg-muted'
      }`}>{status.replace('_', ' ')}</span>
    );
  }
  return (
    <div className="flex gap-1.5 shrink-0">
      <button className="btn btn-ghost !py-1 !px-2 text-xs" disabled={busy} onClick={onArrived}>
        {busy ? <Loader2 className="animate-spin" size={12}/> : <UserCheck size={12}/>} {arrivedLabel}
      </button>
      <button className="btn btn-ghost !py-1 !px-2 text-xs" disabled={busy} onClick={onNoShow}>
        <UserX size={12}/> No-show
      </button>
      <button className="btn btn-ghost !py-1 !px-2 text-xs text-accent-bad" disabled={busy} onClick={onCancel}>
        <Ban size={12}/> Cancel
      </button>
    </div>
  );
}

function TabBtn({ active, onClick, children }: {
  active: boolean; onClick: () => void; children: React.ReactNode;
}) {
  return (
    <button onClick={onClick}
      className={`shrink-0 px-4 py-2 text-sm font-medium border-b-2 -mb-px whitespace-nowrap flex items-center gap-1.5
        ${active ? 'border-accent text-accent' : 'border-transparent text-fg-muted hover:text-fg'}`}>
      {children}
    </button>
  );
}
