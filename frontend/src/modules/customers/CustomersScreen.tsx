/**
 * Customers — phone-based loyalty.
 *
 *  - List customers (sortable by last visit)
 *  - Search by phone/name
 *  - Add manually (rare — most are auto-created from POS checkout)
 *  - Edit name / email / birthday / notes
 *  - See visit count, total spent, loyalty points
 */
import { useCallback, useEffect, useState } from 'react';
import {
  UserPlus, Edit2, Search, Loader2, AlertCircle, RefreshCw,
  Phone, Award, ShoppingBag, Trophy, Trash2,
} from 'lucide-react';

import { inr } from '@/lib/inr';
import { customers, type CustomerDTO } from '@/lib/erp-api';
import { ConfirmModal } from '@/components/ui/ConfirmDialog';
import Modal from '@/components/ui/Modal';
import { useNotifications } from '@/components/ui/Notifications';
import { SkeletonCard } from '@/components/ui/Skeleton';

const RANK_STYLES: Record<string, string> = {
  Rookie: 'text-fg-muted border-bg-border',
  Player: 'text-accent border-accent/40',
  Pro: 'text-accent-purple border-accent-purple/40',
  Legend: 'text-accent-gold border-accent-gold/50',
};

function RankBadge({ rank }: { rank: string }) {
  return (
    <span className={`chip text-[10px] font-semibold ${RANK_STYLES[rank] ?? 'text-fg-muted border-bg-border'}`}>
      <Trophy size={10} className="inline mr-1"/>{rank}
    </span>
  );
}

export default function CustomersScreen() {
  const notifications = useNotifications();
  const [rows, setRows] = useState<CustomerDTO[]>([]);
  const [q, setQ] = useState('');
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<CustomerDTO | null>(null);
  const [deleteCustomer, setDeleteCustomer] = useState<CustomerDTO | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);

  const load = useCallback(async (search = '') => {
    setLoading(true); setErr(null);
    try { setRows(await customers.list(search.trim() || undefined)); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const onSearch = (e: React.FormEvent) => { e.preventDefault(); void load(q); };

  async function confirmDelete() {
    if (!deleteCustomer || deleteBusy) return;
    setDeleteBusy(true);
    try {
      await customers.remove(deleteCustomer.id);
      const customerName = deleteCustomer.name || deleteCustomer.phone;
      setDeleteCustomer(null);
      await load(q);
      notifications.success(`${customerName} was removed from customer search.`, {
        title: 'Customer deleted',
      });
    } catch (e) {
      notifications.error((e as Error).message, { title: 'Could not delete customer' });
    } finally {
      setDeleteBusy(false);
    }
  }

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
            Phone-based loyalty · auto-saved at checkout · 2 points per ₹10 spent on gaming
          </p>
        </div>
        <div className="flex w-full gap-2 sm:w-auto">
          <button className="btn btn-ghost shrink-0" onClick={() => load(q)}><RefreshCw size={14}/></button>
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
        <SkeletonCard />
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
                <th className="text-left p-3">Rank</th>
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
                  <td className="p-3"><RankBadge rank={c.gaming_rank}/></td>
                  <td className="p-3 text-fg-muted text-xs">
                    {c.last_visit_at ? new Date(c.last_visit_at).toLocaleDateString('en-IN') : '—'}
                  </td>
                  <td className="p-3 text-right">
                    <button className="text-fg-muted hover:text-accent" onClick={() => setEdit(c)}>
                      <Edit2 size={14}/>
                    </button>
                    <button
                      aria-label={`Delete ${c.name || c.phone}`}
                      className="text-fg-muted hover:text-accent-bad ml-2"
                      onClick={() => setDeleteCustomer(c)}
                    >
                      <Trash2 size={14}/>
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
                  <div className="flex shrink-0 gap-1.5">
                    <button
                      aria-label={`Edit ${c.name || c.phone}`}
                      className="btn btn-ghost !min-h-[32px] !min-w-[32px] !px-2 !py-1"
                      onClick={() => setEdit(c)}
                    >
                      <Edit2 size={14}/>
                    </button>
                    <button
                      aria-label={`Delete ${c.name || c.phone}`}
                      className="btn btn-ghost !min-h-[32px] !min-w-[32px] !px-2 !py-1 text-accent-bad"
                      onClick={() => setDeleteCustomer(c)}
                    >
                      <Trash2 size={14}/>
                    </button>
                  </div>
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
                <div className="mt-2 flex justify-center">
                  <RankBadge rank={c.gaming_rank}/>
                </div>
                <div className="mt-2 text-xs text-fg-muted">
                  Last visit: {c.last_visit_at ? new Date(c.last_visit_at).toLocaleDateString('en-IN') : '—'}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {addOpen && (
        <CustomerForm onClose={() => setAddOpen(false)} onSuccess={() => {
          setAddOpen(false);
          void load();
          notifications.success('The customer was added.', { title: 'Customer saved' });
        }}/>
      )}
      {edit && (
        <CustomerForm customer={edit} onClose={() => setEdit(null)} onSuccess={() => {
          setEdit(null);
          void load();
          notifications.success('The customer details were updated.', { title: 'Changes saved' });
        }}/>
      )}
      {deleteCustomer && (
        <ConfirmModal
          title="Delete customer"
          message={
            `Delete ${deleteCustomer.name || deleteCustomer.phone}? This removes their name, phone and notes. `
            + 'Their visit and spend history stays on past orders and reports, but they will no longer be found '
            + 'by search or auto-filled at checkout.'
          }
          confirmLabel="Delete customer"
          danger
          busy={deleteBusy}
          onConfirm={() => { void confirmDelete(); }}
          onCancel={() => { if (!deleteBusy) setDeleteCustomer(null); }}
        />
      )}
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
  const [phoneUnlocked, setPhoneUnlocked] = useState(false);
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
        const phone = form.phone.trim();
        await customers.update(customer!.id, {
          ...body,
          ...(phoneUnlocked && phone && phone !== customer!.phone ? { phone } : {}),
        });
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
          <div className="flex gap-2">
            <input className="input font-mono flex-1" required disabled={isEdit && !phoneUnlocked} autoFocus={!isEdit}
              value={form.phone}
              onChange={(e) => setForm({ ...form, phone: e.target.value })}/>
            {isEdit && !phoneUnlocked && (
              <button type="button" className="btn btn-ghost shrink-0 !text-xs"
                onClick={() => setPhoneUnlocked(true)}>
                Fix typo
              </button>
            )}
          </div>
          {isEdit && phoneUnlocked && (
            <p className="text-xs text-accent-gold mt-1">
              This is the customer's loyalty identity — changing it moves their points and visit
              history to the new number, it does not create a second customer.
            </p>
          )}
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
          <div className="pt-3 border-t border-bg-border text-xs space-y-3">
            <div className="grid grid-cols-3 gap-3">
              <Mini icon={<ShoppingBag size={11}/>} label="Visits" value={customer!.visit_count.toString()}/>
              <Mini icon={<Phone size={11}/>} label="Total spend" value={inr(customer!.total_spent_minor)}/>
              <Mini icon={<Award size={11}/>} label="Points" value={customer!.loyalty_points.toString()}/>
            </div>
            <div className="rounded-lg border border-bg-border p-3">
              <div className="flex items-center justify-between mb-2">
                <RankBadge rank={customer!.gaming_rank}/>
                <span className="text-fg-muted">
                  {customer!.lifetime_gaming_points_earned} gaming pts earned lifetime
                </span>
              </div>
              {customer!.next_gaming_rank && customer!.next_gaming_rank_floor != null ? (
                <>
                  <div className="h-1.5 rounded-full bg-bg-border overflow-hidden">
                    <div
                      className="h-full bg-accent-gold"
                      style={{
                        width: `${Math.min(100, Math.max(0, Math.round(
                          ((customer!.lifetime_gaming_points_earned - customer!.gaming_rank_floor)
                            / Math.max(1, customer!.next_gaming_rank_floor - customer!.gaming_rank_floor)
                          ) * 100
                        )))}%`,
                      }}
                    />
                  </div>
                  <div className="mt-1.5 text-fg-muted">
                    {customer!.points_to_next_gaming_rank} pts to {customer!.next_gaming_rank}
                  </div>
                </>
              ) : (
                <div className="text-accent-gold font-semibold">Top rank reached</div>
              )}
            </div>
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
