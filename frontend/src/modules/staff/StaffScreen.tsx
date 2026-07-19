/**
 * Staff screen — full CRUD on users.
 *
 * Live mode: hits /api/v1/staff/users
 * Demo mode: falls back to the seeded STAFF roster.
 *
 * Capabilities:
 *  - Add user after central-email OTP approval
 *  - Edit user (name, phone, role, status)
 *  - Reset password after central-email OTP approval
 *  - Suspend / activate
 *  - Soft-delete
 */
import { useEffect, useState } from 'react';
import {
  Phone, UserPlus, Edit2, KeyRound, ShieldOff, ShieldCheck, Trash2,
  Mail, AlertCircle, Loader2, Clock, LogIn, LogOut,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { STAFF, type StaffMember } from '@/lib/demo-data';
import { attendance, auth, staff, type OnShiftDTO, type UserDTO, type RoleDTO } from '@/lib/erp-api';
import { roleLabel } from '@/lib/roles';
import { useAuth } from '@/modules/auth/AuthContext';
import Modal from '@/components/ui/Modal';
import { ConfirmModal } from '@/components/ui/ConfirmDialog';

const ROLE_COLOR: Record<string, string> = {
  super_owner: 'border-accent-gold/70 text-accent-gold',
  owner: 'border-accent-gold/40 text-accent-gold',
  partner: 'border-accent-purple/40 text-accent-purple',
  manager: 'border-accent/40 text-accent',
  cashier: 'border-fg-muted/40 text-fg-muted',
  kitchen: 'border-fg-muted/40 text-fg-muted',
  gaming_supervisor: 'border-fg-muted/40 text-fg-muted',
  auditor: 'border-fg-muted/40 text-fg-muted',
  staff: 'border-fg-muted/40 text-fg-muted',
};

const DEFAULT_ROLES: RoleDTO[] = [
  { code: 'owner', name: 'Owner', description: 'Business owner title' },
  { code: 'partner', name: 'Partner', description: 'Business partner title' },
  { code: 'manager', name: 'Manager', description: 'Operations manager title' },
  { code: 'cashier', name: 'Cashier', description: 'Cashier title' },
  { code: 'kitchen', name: 'Kitchen', description: 'Kitchen team title' },
  { code: 'gaming_supervisor', name: 'Gaming Supervisor', description: 'Gaming team title' },
  { code: 'auditor', name: 'Auditor', description: 'Audit staff title' },
  { code: 'staff', name: 'Staff', description: 'General staff — no Inventory or Insights/Reports access by default' },
];

const ROLE_DESCRIPTIONS = new Map(DEFAULT_ROLES.map((role) => [role.code, role.description]));

export default function StaffScreen() {
  const [users, setUsers] = useState<UserDTO[]>([]);
  const [roles, setRoles] = useState<RoleDTO[]>(DEFAULT_ROLES);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [editUser, setEditUser] = useState<UserDTO | null>(null);
  const [pwdUser, setPwdUser] = useState<UserDTO | null>(null);
  const [deleteUser, setDeleteUser] = useState<UserDTO | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const { me } = useAuth();

  async function load() {
    setLoading(true);
    setError(null);
    try {
      if (LIVE_MODE) {
        const [u, r] = await Promise.all([staff.listUsers(), staff.listRoles().catch(() => DEFAULT_ROLES)]);
        setUsers(u);
        if (r.length) {
          setRoles(r.map((role) => ({
            ...role,
            description: ROLE_DESCRIPTIONS.get(role.code) ?? role.description,
          })));
        }
      } else {
        setUsers(STAFF.map(staffToDTO));
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed to load staff');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  async function confirmDelete() {
    if (!deleteUser) return;
    setDeleteBusy(true);
    try {
      await staff.deleteUser(deleteUser.id);
      setDeleteUser(null);
      await load();
    } catch (e) {
      alert((e as Error).message);
    } finally {
      setDeleteBusy(false);
    }
  }

  async function onToggleStatus(u: UserDTO) {
    const next = u.status === 'active' ? 'suspended' : 'active';
    try {
      await staff.updateUser(u.id, { status: next });
      await load();
    } catch (e) {
      alert((e as Error).message);
    }
  }

  const onShift = users.filter((u) => u.status === 'active').length;

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Staff</h2>
          <p className="text-fg-muted text-sm">
            {users.length} user{users.length === 1 ? '' : 's'} · {onShift} active · Staff titles
          </p>
        </div>
        <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
          <UserPlus size={14}/> Add user
        </button>
      </header>

      {LIVE_MODE && <AttendanceWidget/>}

      {error && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
          <AlertCircle size={16}/> {error}
        </div>
      )}

      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3 md:gap-4">
          {users.map((u) => (
            <UserCard
              key={u.id}
              user={u}
              onEdit={() => setEditUser(u)}
              onPassword={() => setPwdUser(u)}
              onToggle={() => onToggleStatus(u)}
              onDelete={() => setDeleteUser(u)}
            />
          ))}
          {!users.length && (
            <div className="card text-fg-muted text-sm">No users yet. Add the first one.</div>
          )}
        </div>
      )}

      {addOpen && (
        <AddUserModal
          onClose={() => setAddOpen(false)}
          onSuccess={() => { setAddOpen(false); load(); }}
        />
      )}
      {editUser && (
        <EditUserModal
          user={editUser}
          roles={roles}
          currentUserId={me?.user_id ?? null}
          onClose={() => setEditUser(null)}
          onSuccess={() => { setEditUser(null); load(); }}
        />
      )}
      {pwdUser && (
        <PasswordModal
          user={pwdUser}
          onClose={() => setPwdUser(null)}
          onSuccess={() => setPwdUser(null)}
        />
      )}
      {deleteUser && (
        <ConfirmModal
          title="Delete staff user"
          message={`Delete ${deleteUser.name}? This cannot be undone.`}
          confirmLabel="Delete"
          danger
          busy={deleteBusy}
          onConfirm={confirmDelete}
          onCancel={() => setDeleteUser(null)}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------- Attendance
function AttendanceWidget() {
  const { me } = useAuth();
  const [onShift, setOnShift] = useState<OnShiftDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      setOnShift(await attendance.onShift());
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'failed to load on-shift list');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  const myEntry = onShift.find((s) => s.user_id === me?.user_id);
  const clockedIn = !!myEntry;

  async function handleClockIn() {
    if (!me?.branch_id) {
      setErr('No branch on this account — ask an owner to assign one before clocking in.');
      return;
    }
    setBusy(true); setErr(null);
    try {
      await attendance.clockIn(me.branch_id);
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'clock-in failed');
    } finally {
      setBusy(false);
    }
  }

  async function handleClockOut() {
    setBusy(true); setErr(null);
    try {
      await attendance.clockOut();
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'clock-out failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card mb-4">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <div className="font-semibold flex items-center gap-2">
            <Clock size={15}/> Attendance
          </div>
          <p className="text-xs text-fg-muted mt-0.5">
            {clockedIn
              ? `You clocked in at ${formatClockTime(myEntry!.clock_in_at)}`
              : 'You are not clocked in right now'}
          </p>
        </div>
        <button
          className={`btn ${clockedIn ? 'btn-ghost' : 'btn-primary'} !min-h-[36px]`}
          onClick={clockedIn ? handleClockOut : handleClockIn}
          disabled={busy || loading}
        >
          {busy
            ? <Loader2 className="animate-spin" size={14}/>
            : clockedIn ? <LogOut size={14}/> : <LogIn size={14}/>}
          {clockedIn ? 'Clock out' : 'Clock in'}
        </button>
      </div>

      {err && <div className="mt-3"><ErrorRow text={err}/></div>}

      <div className="mt-4 pt-3 border-t border-bg-border/60">
        <div className="text-xs text-fg-muted mb-2">
          On shift now {onShift.length ? `(${onShift.length})` : ''}
        </div>
        {loading ? (
          <div className="text-xs text-fg-muted flex items-center gap-2">
            <Loader2 className="animate-spin" size={12}/> Loading…
          </div>
        ) : onShift.length ? (
          <div className="flex flex-wrap gap-2">
            {onShift.map((s) => (
              <span key={s.id} className="chip text-xs">
                {s.user_name ?? s.user_email ?? 'Staff'} · since {formatClockTime(s.clock_in_at)}
              </span>
            ))}
          </div>
        ) : (
          <div className="text-xs text-fg-muted">Nobody is clocked in right now.</div>
        )}
      </div>
    </div>
  );
}
function formatClockTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

// ---------------------------------------------------------------- UserCard
function UserCard({
  user, onEdit, onPassword, onToggle, onDelete,
}: {
  user: UserDTO;
  onEdit: () => void;
  onPassword: () => void;
  onToggle: () => void;
  onDelete: () => void;
}) {
  const primaryRole = user.roles[0] ?? 'cashier';
  return (
    <div className="card">
      <div className="flex items-start justify-between mb-3">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-10 h-10 rounded-full bg-gradient-to-br from-accent to-accent-purple grid place-items-center font-bold text-bg flex-shrink-0">
            {user.name.split(' ').map((p) => p[0]).slice(0, 2).join('')}
          </div>
          <div className="min-w-0">
            <div className="font-semibold truncate">{user.name}</div>
            <div className="text-xs text-fg-muted truncate">{roleLabel(primaryRole)}</div>
          </div>
        </div>
        <div className={`chip ${user.status === 'active' ? ROLE_COLOR[primaryRole] : 'border-accent-bad/40 text-accent-bad'}`}>
          {user.status === 'active' ? 'Active' : 'Suspended'}
        </div>
      </div>
      <div className="space-y-1 text-xs text-fg-muted mb-3">
        <div className="flex items-center gap-2 truncate"><Mail size={11} className="shrink-0"/> {user.email}</div>
        {user.phone && (
          <div className="flex items-center gap-2"><Phone size={11}/> {user.phone}</div>
        )}
      </div>
      <div className="flex gap-1 flex-wrap pt-3 border-t border-bg-border/60">
        <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs" onClick={onEdit}>
          <Edit2 size={11}/> Edit
        </button>
        <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs" onClick={onPassword}>
          <KeyRound size={11}/> Password
        </button>
        <button
          className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs"
          onClick={onToggle}
        >
          {user.status === 'active'
            ? <><ShieldOff size={11}/> Suspend</>
            : <><ShieldCheck size={11}/> Activate</>}
        </button>
        <button
          className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs hover:!text-accent-bad ml-auto"
          onClick={onDelete}
        >
          <Trash2 size={11}/>
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- Add
function AddUserModal({
  onClose, onSuccess,
}: { onClose: () => void; onSuccess: () => void }) {
  const [form, setForm] = useState({
    email: '', name: '', password: '', confirm: '', phone: '',
  });
  const [challenge, setChallenge] = useState<{ id: string; destination: string } | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      if (!challenge) {
        if (form.password !== form.confirm) throw new Error('Passwords do not match');
        const result = await auth.requestRegistration({
          email: form.email.trim(),
          name: form.name.trim(),
          password: form.password,
          phone: form.phone.trim() || undefined,
        });
        setChallenge({ id: result.challenge_id, destination: result.destination });
      } else {
        await auth.confirmRegistration(challenge.id, code);
        onSuccess();
      }
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={onClose} title="Add user" size="md">
      <form onSubmit={submit} className="space-y-3">
        {!challenge ? (
          <>
            <Field label="Full name">
              <input className="input" required value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}/>
            </Field>
            <Field label="Email">
              <input type="email" className="input" required value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })}/>
            </Field>
            <Field label="Phone (optional)">
              <input className="input" value={form.phone}
                onChange={(e) => setForm({ ...form, phone: e.target.value })}/>
            </Field>
            <Field label="Password (at least 10 characters)">
              <input type="password" className="input" required minLength={10} value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}/>
            </Field>
            <Field label="Confirm password">
              <input type="password" className="input" required minLength={10} value={form.confirm}
                onChange={(e) => setForm({ ...form, confirm: e.target.value })}/>
            </Field>
          </>
        ) : (
          <>
            <div className="rounded-lg border border-accent-gold/30 bg-accent-gold/10 p-3 text-sm text-accent-gold">
              Approval code sent to {challenge.destination}
            </div>
            <Field label="6-digit approval code">
              <input className="input text-center text-xl tracking-[0.35em]" required autoFocus
                inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}"
                value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}/>
            </Field>
          </>
        )}
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <UserPlus size={14}/>}
            {busy ? 'Please wait…' : challenge ? 'Create approved login' : 'Send approval code'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- Edit
function EditUserModal({
  user, roles, currentUserId, onClose, onSuccess,
}: {
  user: UserDTO;
  roles: RoleDTO[];
  currentUserId: string | null;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const protectedOwner = user.id === currentUserId;
  const roleOptions = roles.filter((role) => role.code !== 'super_owner');
  const [form, setForm] = useState({
    name: user.name,
    phone: user.phone ?? '',
    role_code: user.roles[0] ?? 'cashier',
    status: user.status,
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      await staff.updateUser(user.id, {
        name: form.name.trim(),
        phone: form.phone.trim() || undefined,
        role_code: protectedOwner ? undefined : form.role_code,
        status: form.status,
      });
      onSuccess();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={onClose} title={`Edit ${user.name}`}>
      <form onSubmit={submit} className="space-y-3">
        <Field label="Email"><input className="input" value={user.email} disabled/></Field>
        <Field label="Full name">
          <input className="input" required value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <Field label="Phone">
          <input className="input" value={form.phone}
            onChange={(e) => setForm({ ...form, phone: e.target.value })}/>
        </Field>
        <Field label="Role">
          {protectedOwner ? (
            <input className="input" value="Owner" disabled/>
          ) : (
            <select className="input" value={form.role_code}
              onChange={(e) => setForm({ ...form, role_code: e.target.value })}>
              {roleOptions.map((r) => (
                <option key={r.code} value={r.code}>{r.name}</option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Status">
          <select className="input" value={form.status}
            onChange={(e) => setForm({ ...form, status: e.target.value as 'active' | 'suspended' })}>
            <option value="active">Active</option>
            <option value="suspended">Suspended</option>
          </select>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            Save
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- Password
function PasswordModal({
  user, onClose, onSuccess,
}: { user: UserDTO; onClose: () => void; onSuccess: () => void }) {
  const [pwd, setPwd] = useState('');
  const [confirmPwd, setConfirmPwd] = useState('');
  const [challenge, setChallenge] = useState<{ id: string; destination: string } | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      if (!challenge) {
        const result = await auth.requestPasswordReset(user.email);
        setChallenge({ id: result.challenge_id, destination: result.destination });
      } else {
        if (pwd !== confirmPwd) throw new Error('Passwords do not match');
        await auth.confirmPasswordReset(challenge.id, code, pwd);
        setDone(true);
        setTimeout(onSuccess, 900);
      }
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={onClose} title={`Reset password — ${user.name}`}>
      <form onSubmit={submit} className="space-y-3">
        {!challenge ? (
          <div className="rounded-lg border border-bg-border bg-bg-raised/40 p-3 text-sm text-fg-muted">
            Request central approval to reset the password for <span className="font-medium text-fg">{user.email}</span>.
          </div>
        ) : (
          <>
            <div className="rounded-lg border border-accent-gold/30 bg-accent-gold/10 p-3 text-sm text-accent-gold">
              Approval code sent to {challenge.destination}
            </div>
            <Field label="6-digit approval code">
              <input className="input text-center text-xl tracking-[0.35em]" required autoFocus
                inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}"
                value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}/>
            </Field>
            <Field label="New password (at least 10 characters)">
              <input type="password" className="input" required minLength={10} value={pwd}
                onChange={(e) => setPwd(e.target.value)}/>
            </Field>
            <Field label="Confirm new password">
              <input type="password" className="input" required minLength={10} value={confirmPwd}
                onChange={(e) => setConfirmPwd(e.target.value)}/>
            </Field>
          </>
        )}
        {err && <ErrorRow text={err}/>}
        {done && <div className="text-sm text-accent-good">Password updated.</div>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy || done}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <KeyRound size={14}/>}
            {busy ? 'Please wait…' : challenge ? 'Reset approved password' : 'Send approval code'}
          </button>
        </div>
      </form>
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
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
      <AlertCircle size={14}/> {text}
    </div>
  );
}
function staffToDTO(s: StaffMember): UserDTO {
  return {
    id: s.id,
    email: `${s.id}@demo.local`,
    name: s.name,
    phone: s.phone,
    status: 'active',
    roles: [s.role],
    last_login_at: null,
  };
}
