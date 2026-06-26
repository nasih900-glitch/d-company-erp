/**
 * Customers — phone-based loyalty.
 *
 *  - List customers (sortable by last visit)
 *  - Search by phone/name
 *  - Add manually (rare — most are auto-created from POS checkout)
 *  - Edit name / email / birthday / notes
 *  - See visit count, total spent, loyalty points
 */
import { useEffect, useState } from 'react';
import {
  UserPlus, Edit2, Search, Loader2, AlertCircle, RefreshCw,
  Phone, Award, ShoppingBag,
} from 'lucide-react';

import { inr } from '@/lib/inr';
import { customers, type CustomerDTO } from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';

export default function CustomersScreen() {
  const [rows, setRows] = useState<CustomerDTO[]>([]);
  const [q, setQ] = useState('');
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<CustomerDTO | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try { setRows(await customers.list(q.trim() || undefined)); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  const onSearch = (e: React.FormEvent) => { e.preventDefault(); load(); };

  const totals = {
    customers: rows.length,
    spend: rows.reduce((s, c) => s + c.total_spent_minor, 0),
    visits: rows.reduce((s, c) => s + c.visit_count, 0),
    points: rows.reduce((s, c) => s + c.loyalty_points, 0),
  };

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Customers</h2>
          <p className="text-fg-muted text-sm">
            Phone-based loyalty · auto-saved at checkout · 1 point per ₹10 spent
          </p>
        </div>
        <div className="flex w-full gap-2 sm:w-auto">
          <button className="btn btn-ghost shrink-0" onClick={load}><RefreshCw size={14}/></button>
          <button className="btn btn-primary flex-1 sm:flex-none" onClick={() => setAddOpen(true)}>
            <UserPlus size={14}/> Add customer
          </button>
        </div>
      </header>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
        <Stat label="Customers"    value={totals.customers.toString()}/>
        <Stat label="Total visits" value={totals.visits.toString()}/>
        <Stat label="Total spend"  value={inr(totals.spend)}/>
        <Stat label="Points awarded" value={totals.points.toString()}/>
      </div>

      <form onSubmit={onSearch} className="mb-4 grid grid-cols-[auto_minmax(0,1fr)] items-center gap-2 sm:max-w-md sm:grid-cols-[auto_minmax(0,1fr)_auto]">
        <Search size={16} className="text-fg-muted"/>
        <input value={q} onChange={(e) => setQ(e.target.value)}
          placeholder="Search by phone or name…"
          className="input !min-h-[40px] !py-2 flex-1"/>
        <button type="submit" className="btn btn-ghost !min-h-[40px] col-span-2 sm:col-span-1">Find</button>
      </form>

      {err && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
          <AlertCircle size={14}/> {err}
        </div>
      )}

      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : !rows.length ? (
        <div className="card text-fg-muted text-sm">
          No customers yet. Once you start taking orders with a phone number, they'll appear here automatically.
        </div>
      ) : (
        <div className="card !p-0 overflow-hidden">
          <table className="hidden w-full text-sm md:table">
            <thead className="bg-bg-raised">
              <tr>
                <th className="text-left p-3">Name</th>
                <th className="text-left p-3">Phone</th>
                <th className="text-right p-3">Visits</th>
                <th className="text-right p-3">Total spent</th>
                <th className="text-right p-3">Points</th>
                <th className="text-left p-3">Last visit</th>
                <th className="p-3"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} className="border-b border-bg-border/60 last:border-0">
                  <td className="p-3 font-medium">{c.name || <span className="text-fg-muted italic">— no name —</span>}</td>
                  <td className="p-3 font-mono text-xs">{c.phone}</td>
                  <td className="p-3 text-right font-mono">{c.visit_count}</td>
                  <td className="p-3 text-right font-mono">{inr(c.total_spent_minor)}</td>
                  <td className="p-3 text-right font-mono text-accent-gold">
                    <Award size={11} className="inline mr-1"/>{c.loyalty_points}
                  </td>
                  <td className="p-3 text-fg-muted text-xs">
                    {c.last_visit_at ? new Date(c.last_visit_at).toLocaleDateString('en-IN') : '—'}
                  </td>
                  <td className="p-3 text-right">
                    <button className="text-fg-muted hover:text-accent" onClick={() => setEdit(c)}>
                      <Edit2 size={14}/>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mobile-card-list md:hidden">
            {rows.map((c) => (
              <div key={c.id} className="mobile-record-card">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="truncate font-semibold">
                      {c.name || <span className="text-fg-muted italic">No name</span>}
                    </div>
                    <div className="mt-1 flex items-center gap-1 font-mono text-xs text-fg-muted">
                      <Phone size={11}/> <span className="truncate">{c.phone}</span>
                    </div>
                  </div>
                  <button
                    aria-label={`Edit ${c.name || c.phone}`}
                    className="btn btn-ghost shrink-0 !min-h-[32px] !min-w-[32px] !px-2 !py-1"
                    onClick={() => setEdit(c)}
                  >
                    <Edit2 size={14}/>
                  </button>
                </div>
                <div className="mt-3 grid grid-cols-3 gap-2 text-center text-xs">
                  <div>
                    <div className="text-fg-muted">Visits</div>
                    <div className="font-mono font-semibold">{c.visit_count}</div>
                  </div>
                  <div>
                    <div className="text-fg-muted">Spent</div>
                    <div className="font-mono font-semibold">{inr(c.total_spent_minor)}</div>
                  </div>
                  <div>
                    <div className="text-fg-muted">Points</div>
                    <div className="font-mono font-semibold text-accent-gold">
                      <Award size={11} className="inline mr-1"/>{c.loyalty_points}
                    </div>
                  </div>
                </div>
                <div className="mt-2 text-xs text-fg-muted">
                  Last visit: {c.last_visit_at ? new Date(c.last_visit_at).toLocaleDateString('en-IN') : '—'}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {addOpen && <CustomerForm onClose={() => setAddOpen(false)}
        onSuccess={() => { setAddOpen(false); load(); }}/>}
      {edit && <CustomerForm customer={edit} onClose={() => setEdit(null)}
        onSuccess={() => { setEdit(null); load(); }}/>}
    </div>
  );
}

function CustomerForm({
  customer, onClose, onSuccess,
}: { customer?: CustomerDTO; onClose: () => void; onSuccess: () => void }) {
  const isEdit = !!customer;
  const [form, setForm] = useState({
    phone: customer?.phone ?? '',
    name: customer?.name ?? '',
    email: customer?.email ?? '',
    birthday: customer?.birthday ? customer.birthday.slice(0, 10) : '',
    notes: customer?.notes ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const body = {
        name: form.name.trim() || undefined,
        email: form.email.trim() || undefined,
        birthday: form.birthday ? new Date(form.birthday).toISOString() : undefined,
        notes: form.notes.trim() || undefined,
      };
      if (isEdit) {
        await customers.update(customer!.id, body);
      } else {
        await customers.upsert({ phone: form.phone.trim(), ...body });
      }
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${customer!.name || customer!.phone}` : 'New customer'}>
      <form onSubmit={submit} className="space-y-3">
        <Field label="Phone (10+ digits, +country if needed)">
          <input className="input font-mono" required disabled={isEdit} autoFocus
            value={form.phone}
            onChange={(e) => setForm({ ...form, phone: e.target.value })}/>
        </Field>
        <Field label="Name">
          <input className="input" value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <Field label="Email (optional)">
          <input type="email" className="input" value={form.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}/>
        </Field>
        <Field label="Birthday (optional)">
          <input type="date" className="input" value={form.birthday}
            onChange={(e) => setForm({ ...form, birthday: e.target.value })}/>
        </Field>
        <Field label="Notes">
          <textarea className="input" rows={2} value={form.notes}
            onChange={(e) => setForm({ ...form, notes: e.target.value })}/>
        </Field>
        {isEdit && (
          <div className="grid grid-cols-3 gap-3 pt-3 border-t border-bg-border text-xs">
            <Mini icon={<ShoppingBag size={11}/>} label="Visits" value={customer!.visit_count.toString()}/>
            <Mini icon={<Phone size={11}/>} label="Total spend" value={inr(customer!.total_spent_minor)}/>
            <Mini icon={<Award size={11}/>} label="Points" value={customer!.loyalty_points.toString()}/>
          </div>
        )}
        {err && (
          <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
            <AlertCircle size={14}/> {err}
          </div>
        )}
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
function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="card">
      <div className="text-xs text-fg-muted uppercase tracking-wider">{label}</div>
      <div className="text-2xl font-bold mt-1">{value}</div>
    </div>
  );
}
function Mini({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="text-center">
      <div className="text-fg-muted flex items-center justify-center gap-1">{icon} {label}</div>
      <div className="font-mono font-bold mt-1">{value}</div>
    </div>
  );
}
