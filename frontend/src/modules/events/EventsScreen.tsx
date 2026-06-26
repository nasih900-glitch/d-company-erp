/**
 * Events screen — full live CRUD on projector screenings.
 *
 *  - List all events (upcoming + past)
 *  - Add event (name, type, screen, start/end, capacity, ticket price)
 *  - Edit event (everything + status: scheduled/live/ended/cancelled)
 *  - Delete event (only if no tickets sold)
 *  - Sell tickets (1+ to same customer)
 *  - List tickets for an event
 *  - Check in a ticket
 *
 * Demo mode still works against in-memory state.
 */
import { useEffect, useState } from 'react';
import {
  Tv, Plus, Edit2, Trash2, Users,
  Ticket as TicketIcon, MapPin, AlertCircle, Loader2, RefreshCw,
  Check, X as XIcon, CalendarClock,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { EVENTS } from '@/lib/demo-data';
import { inr } from '@/lib/inr';
import {
  events as eventsApi, type EventDTO, type EventTicketDTO,
} from '@/lib/erp-api';
import { useAuth } from '@/modules/auth/AuthContext';
import Modal from '@/components/ui/Modal';

const TYPE_LABEL: Record<EventDTO['event_type'], string> = {
  football: '⚽ Football',
  cricket: '🏏 Cricket',
  movie: '🎬 Movie',
  esports: '🎮 E-sports',
  other: '📺 Other',
};
const TYPE_COLOR: Record<EventDTO['event_type'], string> = {
  football: 'border-accent-good/40 text-accent-good',
  cricket: 'border-accent-gold/40 text-accent-gold',
  movie: 'border-accent-purple/40 text-accent-purple',
  esports: 'border-accent/40 text-accent',
  other: 'border-fg-muted/40 text-fg-muted',
};
const STATUS_COLOR: Record<EventDTO['status'], string> = {
  scheduled: 'border-accent/40 text-accent',
  live: 'border-accent-good/40 text-accent-good',
  ended: 'border-fg-muted/40 text-fg-muted',
  cancelled: 'border-accent-bad/40 text-accent-bad',
};

export default function EventsScreen() {
  const { me, demo } = useAuth();
  const canManageEvents = Boolean(demo || me?.protected_access);
  const [rows, setRows] = useState<EventDTO[]>([]);
  const [includeP, setIncludeP] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<EventDTO | null>(null);
  const [sellingFor, setSellingFor] = useState<EventDTO | null>(null);
  const [ticketsFor, setTicketsFor] = useState<EventDTO | null>(null);

  async function load() {
    setLoading(true); setError(null);
    try {
      if (LIVE_MODE) {
        setRows(await eventsApi.listAll(includeP));
      } else {
        setRows(EVENTS.map(demoToDTO));
      }
    } catch (e) {
      setError((e as Error).message);
    } finally { setLoading(false); }
  }
  useEffect(() => { load(); }, [includeP]);

  async function onDelete(ev: EventDTO) {
    if (!confirm(`Delete "${ev.name}"?`)) return;
    try { await eventsApi.delete(ev.id); await load(); }
    catch (e) { alert((e as Error).message); }
  }

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Events</h2>
          <p className="text-fg-muted text-sm">
            Projector screenings · ticket sales · 18% GST (SAC 999692)
          </p>
        </div>
        <div className="flex gap-2 flex-wrap">
          <label className="flex items-center gap-2 text-sm text-fg-muted">
            <input type="checkbox" checked={includeP} onChange={(e) => setIncludeP(e.target.checked)}/>
            Show past
          </label>
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          {canManageEvents && (
            <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
              <Plus size={14}/> New event
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
      ) : !rows.length ? (
        <div className="card text-fg-muted text-sm">
          No events yet. Click <b>New event</b> to schedule your first screening.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3 md:gap-4">
          {rows.map((ev) => (
            <EventCard key={ev.id}
              ev={ev}
              canManage={canManageEvents}
              onEdit={() => setEdit(ev)}
              onDelete={() => onDelete(ev)}
              onSell={() => setSellingFor(ev)}
              onViewTickets={() => setTicketsFor(ev)}/>
          ))}
        </div>
      )}

      {canManageEvents && addOpen && <EventForm onClose={() => setAddOpen(false)}
        onSuccess={() => { setAddOpen(false); load(); }}/>}
      {canManageEvents && edit && <EventForm event={edit} onClose={() => setEdit(null)}
        onSuccess={() => { setEdit(null); load(); }}/>}
      {sellingFor && <SellTicketForm event={sellingFor}
        onClose={() => setSellingFor(null)}
        onSuccess={() => { setSellingFor(null); load(); }}/>}
      {ticketsFor && <TicketsModal event={ticketsFor}
        onClose={() => setTicketsFor(null)}
        onChange={load}/>}
    </div>
  );
}

// ---------------------------------------------------------------- EventCard
function EventCard({
  ev, canManage, onEdit, onDelete, onSell, onViewTickets,
}: {
  ev: EventDTO;
  canManage: boolean;
  onEdit: () => void;
  onDelete: () => void;
  onSell: () => void;
  onViewTickets: () => void;
}) {
  const startDate = new Date(ev.starts_at);
  const pct = ev.capacity > 0 ? (ev.sold / ev.capacity) * 100 : 0;
  const full = ev.sold >= ev.capacity;

  return (
    <div className="card">
      <div className="flex items-start justify-between mb-3 gap-2">
        <div className="min-w-0">
          <div className="font-bold truncate flex items-center gap-2">
            <Tv size={14} className="text-accent shrink-0"/>{ev.name}
          </div>
          <div className="text-xs text-fg-muted truncate">{ev.description || ev.screen}</div>
        </div>
        <div className="flex flex-col gap-1 items-end shrink-0">
          <span className={`chip text-[10px] ${TYPE_COLOR[ev.event_type]}`}>{TYPE_LABEL[ev.event_type]}</span>
          <span className={`chip text-[10px] ${STATUS_COLOR[ev.status]}`}>{ev.status}</span>
        </div>
      </div>

      <div className="space-y-1.5 text-xs text-fg-muted mb-3">
        <Row icon={<CalendarClock size={11}/>}>
          {startDate.toLocaleString('en-IN', {
            weekday: 'short', day: '2-digit', month: 'short',
            hour: '2-digit', minute: '2-digit',
          })}
        </Row>
        <Row icon={<MapPin size={11}/>}>{ev.screen}</Row>
        <Row icon={<Users size={11}/>}>
          {ev.sold} / {ev.capacity} sold · {ev.remaining} left
        </Row>
        <Row icon={<TicketIcon size={11}/>}>
          {inr(ev.base_ticket_price_minor)} per ticket (incl. 18% GST)
        </Row>
      </div>

      <div className="h-1.5 rounded-full bg-bg-raised overflow-hidden mb-3">
        <div className={`h-full ${full ? 'bg-accent-bad' : pct > 75 ? 'bg-accent-gold' : 'bg-accent-good'}`}
          style={{ width: `${Math.min(100, pct)}%` }}/>
      </div>

      <div className="flex gap-1 flex-wrap pt-3 border-t border-bg-border/60">
        <button className="btn btn-primary !min-h-[32px] !py-1 !px-2 text-xs" onClick={onSell}
          disabled={full || ev.status !== 'scheduled' && ev.status !== 'live'}>
          <TicketIcon size={11}/> Sell ticket
        </button>
        <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs" onClick={onViewTickets}>
          Tickets ({ev.sold})
        </button>
        {canManage && (
          <>
            <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs" onClick={onEdit}>
              <Edit2 size={11}/>
            </button>
            <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs hover:!text-accent-bad ml-auto"
              onClick={onDelete}>
              <Trash2 size={11}/>
            </button>
          </>
        )}
      </div>
    </div>
  );
}

function Row({ icon, children }: { icon: React.ReactNode; children: React.ReactNode }) {
  return <div className="flex items-center gap-2">{icon} {children}</div>;
}

// ---------------------------------------------------------------- EventForm
function EventForm({
  event, onClose, onSuccess,
}: { event?: EventDTO; onClose: () => void; onSuccess: () => void }) {
  const isEdit = !!event;
  const [form, setForm] = useState({
    name: event?.name ?? '',
    description: event?.description ?? '',
    event_type: event?.event_type ?? 'football' as EventDTO['event_type'],
    screen: event?.screen ?? 'Main Screen',
    starts_at: event ? toLocalInput(event.starts_at) : '',
    ends_at: event?.ends_at ? toLocalInput(event.ends_at) : '',
    capacity: event?.capacity?.toString() ?? '60',
    price_rupees: event ? (event.base_ticket_price_minor / 100).toFixed(2) : '199',
    poster_url: event?.poster_url ?? '',
    status: event?.status ?? 'scheduled' as EventDTO['status'],
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const base_price_minor = Math.round(parseFloat(form.price_rupees || '0') * 100);
      const capacity = parseInt(form.capacity, 10);
      const body = {
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        event_type: form.event_type,
        screen: form.screen,
        starts_at: new Date(form.starts_at).toISOString(),
        ends_at: form.ends_at ? new Date(form.ends_at).toISOString() : undefined,
        capacity,
        base_ticket_price_minor: base_price_minor,
        poster_url: form.poster_url || undefined,
      };
      if (isEdit) {
        await eventsApi.update(event!.id, { ...body, status: form.status });
      } else {
        await eventsApi.create(body);
      }
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${event!.name}` : 'New event'} size="lg">
      <form onSubmit={submit} className="space-y-3">
        <Field label="Event name">
          <input className="input" required value={form.name} autoFocus
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <Field label="Description (optional)">
          <input className="input" value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}/>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Type">
            <select className="input" value={form.event_type}
              onChange={(e) => setForm({ ...form, event_type: e.target.value as EventDTO['event_type'] })}>
              <option value="football">⚽ Football</option>
              <option value="cricket">🏏 Cricket</option>
              <option value="movie">🎬 Movie</option>
              <option value="esports">🎮 E-sports</option>
              <option value="other">📺 Other</option>
            </select>
          </Field>
          <Field label="Screen / room">
            <input className="input" value={form.screen}
              onChange={(e) => setForm({ ...form, screen: e.target.value })}/>
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Starts at">
            <input type="datetime-local" required className="input" value={form.starts_at}
              onChange={(e) => setForm({ ...form, starts_at: e.target.value })}/>
          </Field>
          <Field label="Ends at (optional)">
            <input type="datetime-local" className="input" value={form.ends_at}
              onChange={(e) => setForm({ ...form, ends_at: e.target.value })}/>
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Capacity">
            <input type="number" min={1} required className="input font-mono"
              value={form.capacity}
              onChange={(e) => setForm({ ...form, capacity: e.target.value })}/>
          </Field>
          <Field label="Ticket price (₹, incl. 18% GST)">
            <input type="number" min={0} step="0.01" required className="input font-mono text-right"
              value={form.price_rupees}
              onChange={(e) => setForm({ ...form, price_rupees: e.target.value })}/>
          </Field>
        </div>
        {isEdit && (
          <Field label="Status">
            <select className="input" value={form.status}
              onChange={(e) => setForm({ ...form, status: e.target.value as EventDTO['status'] })}>
              <option value="scheduled">Scheduled</option>
              <option value="live">Live</option>
              <option value="ended">Ended</option>
              <option value="cancelled">Cancelled</option>
            </select>
          </Field>
        )}
        <Field label="Poster image URL (optional)">
          <input className="input font-mono text-xs" value={form.poster_url}
            onChange={(e) => setForm({ ...form, poster_url: e.target.value })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            {isEdit ? 'Save' : 'Create event'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- Sell ticket
function SellTicketForm({
  event, onClose, onSuccess,
}: { event: EventDTO; onClose: () => void; onSuccess: () => void }) {
  const [form, setForm] = useState({
    customer_name: '', customer_phone: '', seat: '', qty: '1', note: '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [issued, setIssued] = useState<EventTicketDTO[] | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const out = await eventsApi.sellTickets(event.id, {
        customer_name: form.customer_name.trim(),
        customer_phone: form.customer_phone.trim() || undefined,
        seat: form.seat.trim() || undefined,
        qty: parseInt(form.qty, 10),
        note: form.note.trim() || undefined,
      });
      setIssued(out);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  if (issued) {
    const totalPrice = issued.reduce((s, t) => s + t.price_paid_minor, 0);
    return (
      <Modal open onClose={() => { onClose(); onSuccess(); }} title="Tickets issued ✓">
        <div className="space-y-3">
          <div className="text-sm text-fg-muted">
            {issued.length} ticket{issued.length === 1 ? '' : 's'} issued for <b>{event.name}</b>
          </div>
          <div className="card !p-0 overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-bg-raised">
                <tr><th className="text-left p-2">Ticket #</th><th className="text-right p-2">Paid</th></tr>
              </thead>
              <tbody>
                {issued.map((t) => (
                  <tr key={t.id} className="border-b border-bg-border/60">
                    <td className="p-2 font-mono text-xs">{t.ticket_no}</td>
                    <td className="p-2 text-right font-mono">{inr(t.price_paid_minor)}</td>
                  </tr>
                ))}
                <tr className="font-bold">
                  <td className="p-2">Total</td>
                  <td className="p-2 text-right font-mono">{inr(totalPrice)}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <button className="btn btn-primary w-full" onClick={() => { onClose(); onSuccess(); }}>
            <Check size={14}/> Done
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal open onClose={onClose} title={`Sell tickets — ${event.name}`}>
      <form onSubmit={submit} className="space-y-3">
        <div className="text-xs text-fg-muted">
          {event.remaining} of {event.capacity} seats remaining · {inr(event.base_ticket_price_minor)} each
        </div>
        <Field label="Customer name">
          <input className="input" required autoFocus value={form.customer_name}
            onChange={(e) => setForm({ ...form, customer_name: e.target.value })}/>
        </Field>
        <Field label="Phone (optional)">
          <input className="input" value={form.customer_phone}
            onChange={(e) => setForm({ ...form, customer_phone: e.target.value })}/>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Seat (optional)">
            <input className="input" value={form.seat}
              onChange={(e) => setForm({ ...form, seat: e.target.value })}/>
          </Field>
          <Field label="Quantity">
            <input type="number" min={1} max={event.remaining} required className="input font-mono"
              value={form.qty} onChange={(e) => setForm({ ...form, qty: e.target.value })}/>
          </Field>
        </div>
        <Field label="Note (optional)">
          <input className="input" value={form.note}
            onChange={(e) => setForm({ ...form, note: e.target.value })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-between items-center pt-2">
          <div className="text-sm text-fg-muted">
            Total: <b>{inr(event.base_ticket_price_minor * (parseInt(form.qty) || 1))}</b>
          </div>
          <div className="flex gap-2">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? <Loader2 className="animate-spin" size={14}/> : <TicketIcon size={14}/>}
              Issue ticket{parseInt(form.qty) > 1 ? 's' : ''}
            </button>
          </div>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- Tickets list
function TicketsModal({
  event, onClose, onChange,
}: { event: EventDTO; onClose: () => void; onChange: () => void }) {
  const [rows, setRows] = useState<EventTicketDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try { setRows(await eventsApi.listTickets(event.id)); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  async function checkIn(t: EventTicketDTO) {
    try { await eventsApi.checkIn(event.id, t.id); await load(); onChange(); }
    catch (e) { alert((e as Error).message); }
  }

  return (
    <Modal open onClose={onClose} title={`${event.name} — tickets`} size="lg">
      {loading ? (
        <div className="text-fg-muted flex items-center gap-2"><Loader2 className="animate-spin" size={14}/> Loading…</div>
      ) : err ? <ErrorRow text={err}/> :
      !rows.length ? (
        <div className="text-fg-muted text-sm">No tickets sold yet.</div>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-fg-muted">
              <th className="py-2">Ticket #</th>
              <th>Customer</th>
              <th>Phone</th>
              <th>Status</th>
              <th className="text-right">Paid</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.id} className="border-t border-bg-border/60">
                <td className="py-2 font-mono text-xs">{t.ticket_no}</td>
                <td>{t.customer_name || '—'}</td>
                <td className="text-fg-muted text-xs">{t.customer_phone || '—'}</td>
                <td>
                  <span className={`chip text-[10px] ${
                    t.status === 'checked_in' ? 'border-accent-good/40 text-accent-good' :
                    t.status === 'sold'       ? 'border-accent/40 text-accent' :
                                                'border-accent-bad/40 text-accent-bad'}`}>
                    {t.status === 'checked_in' ? '✓ in' : t.status}
                  </span>
                </td>
                <td className="text-right font-mono">{inr(t.price_paid_minor)}</td>
                <td className="text-right">
                  {t.status === 'sold' && (
                    <button className="btn btn-ghost !min-h-[28px] !py-0.5 !px-2 text-xs"
                      onClick={() => checkIn(t)}>
                      <Check size={11}/> Check in
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------- helpers
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
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2 mb-3">
      <XIcon size={14}/> {text}
    </div>
  );
}
function toLocalInput(iso: string): string {
  // Convert ISO timestamp → "YYYY-MM-DDTHH:MM" for <input type="datetime-local">
  const d = new Date(iso);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function demoToDTO(e: typeof EVENTS[0]): EventDTO {
  return {
    id: e.id, name: e.name, description: null,
    event_type: 'football', screen: 'Main Screen',
    starts_at: e.starts_at, ends_at: null,
    capacity: e.capacity, sold: e.sold,
    remaining: e.capacity - e.sold,
    base_ticket_price_minor: e.base_ticket_price_minor,
    sac_code: '999692', tax_rate: e.tax_rate,
    status: 'scheduled', poster_url: null,
  };
}
