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
import { Link } from 'react-router-dom';
import {
  Phone, UserPlus, Edit2, KeyRound, ShieldOff, ShieldCheck, Trash2,
  Mail, AlertCircle, Loader2, Clock,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { STAFF, type StaffMember } from '@/lib/demo-data';
import { auth, staff, type UserDTO, type RoleDTO } from '@/lib/erp-api';
import { roleLabel } from '@/lib/roles';
import { useAuth } from '@/modules/auth/AuthContext';
import Modal from '@/components/ui/Modal';
import { ConfirmModal } from '@/components/ui/ConfirmDialog';
import { useNotifications } from '@/components/ui/Notifications';
import { SkeletonCard } from '@/components/ui/Skeleton';
import {
  buildStaffAccessPatch,
  canChangeStaffAccess,
  editableStaffRoleOptions,
} from './staff-edit-policy';

const ROLE_COLOR: Record<string, string> = {
  super_owner: 'border-accent-gold/70 text-accent-gold',
  co_owner: 'border-accent-gold/60 text-accent-gold',
  owner: 'border-accent-gold/40 text-accent-gold',
  partner: 'border-accent-purple/40 text-accent-purple',
  manager: 'border-accent/40 text-accent',
  cashier: 'border-fg-muted/40 text-fg-muted',
  gaming_cashier: 'border-fg-muted/40 text-fg-muted',
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
  { code: 'gaming_cashier', name: 'Gaming & POS staff', description: 'Gaming, POS and own shifts — no business management' },
  { code: 'kitchen', name: 'Kitchen', description: 'Kitchen team title' },
  { code: 'gaming_supervisor', name: 'Gaming Supervisor', description: 'Gaming team title' },
  { code: 'auditor', name: 'Auditor', description: 'Audit staff title' },
  { code: 'staff', name: 'Staff', description: 'General staff — no Inventory or Insights/Reports access by default' },
];

const ROLE_DESCRIPTIONS = new Map(DEFAULT_ROLES.map((role) => [role.code, role.description]));

export default function StaffScreen() {
  const notifications = useNotifications();
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
      const deletedName = deleteUser.name;
      setDeleteUser(null);
      await load();
      notifications.success(`${deletedName} was removed from staff.`, { title: 'Staff user deleted' });
    } catch (e) {
      notifications.error((e as Error).message, { title: 'Could not delete staff user' });
    } finally {
      setDeleteBusy(false);
    }
  }

  async function onToggleStatus(u: UserDTO) {
    const next = u.status === 'active' ? 'suspended' : 'active';
    try {
      await staff.updateUser(u.id, { status: next });
      await load();
      notifications.success(`${u.name} is now ${next}.`, { title: 'Staff status updated' });
    } catch (e) {
      notifications.error((e as Error).message, { title: 'Could not update staff status' });
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
        <SkeletonCard />
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3 md:gap-4">
          {users.map((u) => (
            <UserCard
              key={u.id}
              user={u}
              canChangeAccess={canChangeStaffAccess({
                callerUserId: me?.user_id ?? null,
                targetUserId: u.id,
                targetRoles: [...u.roles, ...(u.managed_roles ?? [])],
                callerHasAuditAccess: Boolean(me?.audit_access),
              })}
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
          onSuccess={() => {
            setAddOpen(false);
            void load();
            notifications.success('The staff account was created.', { title: 'User added' });
          }}
        />
      )}
      {editUser && (
        <EditUserModal
          user={editUser}
          roles={roles}
          currentUserId={me?.user_id ?? null}
          canManageOwnerAccess={Boolean(me?.audit_access)}
          onClose={() => setEditUser(null)}
          onSuccess={() => {
            setEditUser(null);
            void load();
            notifications.success('The staff account was updated.', { title: 'Changes saved' });
          }}
        />
      )}
      {pwdUser && (
        <PasswordModal
          user={pwdUser}
          onClose={() => setPwdUser(null)}
          onSuccess={() => {
            setPwdUser(null);
            notifications.success('The password was reset.', { title: 'Password updated' });
          }}
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

// Keep account administration separate from actual-break attendance recording.
function AttendanceWidget() {
  return <div className="card mb-4 flex flex-wrap items-center justify-between gap-3">
    <div><h3 className="flex items-center gap-2 font-semibold"><Clock size={16} /> Individual time & attendance</h3>
      <p className="mt-1 text-sm text-fg-muted">Record actual breaks, review monthly hours and keep draft salary proposals separate from payments.</p></div>
    <Link className="btn btn-ghost" to="/staff/time">Open working time</Link>
  </div>;
}

// ---------------------------------------------------------------- UserCard
function UserCard({
  user, canChangeAccess, onEdit, onPassword, onToggle, onDelete,
}: {
  user: UserDTO;
  canChangeAccess: boolean;
  onEdit: () => void;
  onPassword: () => void;
  onToggle: () => void;
  onDelete: () => void;
}) {
  const primaryRole = user.managed_roles?.[0] ?? user.roles[0] ?? 'cashier';
  const accessNote = user.managed_roles?.includes('super_owner')
    ? 'Audit owner · full operational access'
    : user.managed_roles?.includes('co_owner')
      ? 'Full operational access · Audit hidden'
      : user.managed_roles?.includes('owner')
        ? 'Owner title · standard shift accountability'
        : null;
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
            {accessNote && (
              <div className="mt-1 flex items-center gap-1 text-[11px] text-fg-muted">
                <ShieldCheck size={11} className="shrink-0" aria-hidden="true"/>
                <span>{accessNote}</span>
              </div>
            )}
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
        {canChangeAccess && (
          <>
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
              aria-label={`Delete ${user.name}`}
              onClick={onDelete}
            >
              <Trash2 size={11}/>
            </button>
          </>
        )}
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
  user, roles, currentUserId, canManageOwnerAccess, onClose, onSuccess,
}: {
  user: UserDTO;
  roles: RoleDTO[];
  currentUserId: string | null;
  canManageOwnerAccess: boolean;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const isSelf = user.id === currentUserId;
  const originalRoleCode = user.managed_roles?.[0] ?? user.roles[0] ?? 'cashier';
  const effectiveRoles = [...user.roles, ...(user.managed_roles ?? [])];
  const isOwner = effectiveRoles.some((role) => (
    role === 'owner' || role === 'co_owner' || role === 'super_owner'
  ));
  const isProtectedOwner = effectiveRoles.includes('super_owner');
  const accessChangesLocked = isSelf || isProtectedOwner || (isOwner && !canManageOwnerAccess);
  const roleOptions = editableStaffRoleOptions(roles, canManageOwnerAccess);
  const [form, setForm] = useState({
    name: user.name,
    phone: user.phone ?? '',
    role_code: originalRoleCode,
    status: user.status,
  });
  const [roleSelectionChanged, setRoleSelectionChanged] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      const accessPatch = buildStaffAccessPatch({
        originalRoleCode,
        selectedRoleCode: form.role_code,
        roleSelectionChanged,
        originalStatus: user.status,
        selectedStatus: form.status,
        accessChangesLocked,
      });
      await staff.updateUser(user.id, {
        name: form.name.trim(),
        phone: form.phone.trim() || null,
        ...accessPatch,
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
          {accessChangesLocked ? (
            <input
              className="input"
              value={roleOptions.find((role) => role.code === originalRoleCode)?.name
                ?? roleLabel(originalRoleCode)}
              disabled
            />
          ) : (
            <select className="input" value={form.role_code}
              onChange={(e) => {
                setRoleSelectionChanged(true);
                setForm({ ...form, role_code: e.target.value });
              }}>
              {roleOptions.map((r) => (
                <option key={r.code} value={r.code}>{r.name}</option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Status">
          {accessChangesLocked ? (
            <input className="input" value={form.status === 'active' ? 'Active' : 'Suspended'} disabled/>
          ) : (
            <select className="input" value={form.status}
              onChange={(e) => setForm({ ...form, status: e.target.value as 'active' | 'suspended' })}>
              <option value="active">Active</option>
              <option value="suspended">Suspended</option>
            </select>
          )}
        </Field>
        {accessChangesLocked && (
          <p className="text-xs text-fg-muted">
            {isSelf
              ? 'Role and status are locked on your own account.'
              : isProtectedOwner
                ? 'Protected audit-owner access cannot be changed from Staff.'
              : 'Only the protected owner can change access for an owner account.'}
          </p>
        )}
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
            <p className="text-sm text-fg-muted">
              Use the code sent for this request in this same form. Requesting another code cancels earlier codes. Your password changes only after confirmation.
            </p>
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
    managed_roles: null,
    last_login_at: null,
  };
}
