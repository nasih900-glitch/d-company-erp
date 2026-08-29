import { useEffect, useState } from 'react';
import {
  Plus, Edit2, Save, Loader2, AlertCircle, MapPin, MonitorSmartphone,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { settings, type BranchDTO, type TerminalDTO } from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';
import { SkeletonCard } from '@/components/ui/Skeleton';

const TERMINAL_PURPOSE_LABELS: Record<TerminalDTO['purpose'], string> = {
  cafe_pos: 'Legacy POS-only setup',
  gaming: 'Legacy Gaming-only setup',
  hybrid: 'Combined workspace',
};

export default function BranchesTab() {
  const [rows, setRows] = useState<BranchDTO[]>([]);
  const [terminals, setTerminals] = useState<TerminalDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<BranchDTO | null>(null);
  const [terminalForm, setTerminalForm] = useState<{
    branch: BranchDTO;
    terminal?: TerminalDTO;
  } | null>(null);

  async function load() {
    if (!LIVE_MODE) { setLoading(false); return; }
    setLoading(true); setErr(null);
    try {
      const [branchRows, terminalRows] = await Promise.all([
        settings.listBranches(),
        settings.listTerminals(),
      ]);
      setRows(branchRows);
      setTerminals(terminalRows);
    } catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (loading) return <SkeletonCard />;
  if (!LIVE_MODE) return <div className="card text-fg-muted">Available in live mode only.</div>;

  return (
    <div className="space-y-3">
      <div className="card flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <div>
          <p className="font-semibold">One shop, one simple workspace</p>
          <p className="mt-1 text-sm text-fg-muted">
            The app selects the active workspace automatically. Its internal identity keeps
            shifts and receipts accountable without adding a step for staff.
          </p>
        </div>
        {rows.length === 0 && (
          <button className="btn btn-primary shrink-0" onClick={() => setAddOpen(true)}>
            <Plus size={14}/> Add shop
          </button>
        )}
      </div>

      {err && <ErrorRow text={err}/>}

      <div className="space-y-3">
        {rows.map((b) => {
          // The operational endpoint intentionally returns active workspaces
          // only. Archived terminal identities remain server-side for audit
          // and accounting references, not as routine owner choices.
          const branchTerminals = terminals.filter((terminal) => terminal.branch_id === b.id);
          return (
            <div key={b.id} className="card">
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-3 min-w-0">
                <div className="p-2 bg-bg-raised rounded-lg text-fg-muted">
                  <MapPin size={16}/>
                </div>
                <div className="min-w-0">
                  <div className="font-bold truncate">{b.name}</div>
                  {b.address && <div className="text-xs text-fg-muted">{b.address}</div>}
                  <div className="text-xs text-fg-muted mt-1 flex gap-3 flex-wrap">
                    {b.code && <span>Code: <b>{b.code}</b></span>}
                    {b.state_code && <span>State: <b>{b.state_code}</b></span>}
                    {b.fssai_license_no && <span>FSSAI: <b className="font-mono">{b.fssai_license_no}</b></span>}
                    {b.branch_gstin && <span>GSTIN: <b className="font-mono">{b.branch_gstin}</b></span>}
                  </div>
                </div>
              </div>
              <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs" onClick={() => setEdit(b)}>
                <Edit2 size={11}/> Edit
              </button>
            </div>

            <div className="mt-4 border-t border-bg-border pt-4">
              <div className="mb-2 flex items-center justify-between gap-3">
                <div>
                  <p className="text-sm font-semibold">Workspace</p>
                  <p className="text-xs text-fg-muted">
                    Gaming, POS and shift operation use this workspace together.
                  </p>
                </div>
                {branchTerminals.length === 0 && (
                  <button
                    className="btn btn-secondary !min-h-[36px] !px-3 !py-1.5 text-xs"
                    onClick={() => setTerminalForm({ branch: b })}
                  >
                    <Plus size={13}/> Create shared workspace
                  </button>
                )}
              </div>

              {branchTerminals.length > 1 && (
                <div className="mb-3 rounded-lg border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm">
                  <p className="font-semibold text-accent-bad">Shared workspace conflict</p>
                  <p className="mt-1 text-fg-muted">
                    {branchTerminals.length} active workspaces are configured. Staff operations stay
                    blocked. Ask support to run the protected consolidation after every open shift,
                    pending bill and saved device change has been reconciled.
                  </p>
                </div>
              )}

              <div className="grid gap-2 md:grid-cols-2">
                {branchTerminals.map((terminal) => (
                  <div
                    key={terminal.id}
                    className="flex min-w-0 items-center justify-between gap-3 rounded-lg border border-bg-border bg-bg-raised p-3"
                  >
                    <div className="flex min-w-0 items-center gap-3">
                      <MonitorSmartphone size={18} className="shrink-0 text-fg-muted"/>
                      <div className="min-w-0">
                        <p className="truncate text-sm font-semibold">{terminal.name}</p>
                        <p className="text-xs font-medium text-accent">
                          {TERMINAL_PURPOSE_LABELS[terminal.purpose]}
                        </p>
                        <p className="truncate text-xs text-fg-muted">
                          {terminal.device_id || 'Selected automatically for staff'}
                        </p>
                      </div>
                    </div>
                    <button
                      className="btn btn-ghost !min-h-[32px] !px-2 !py-1 text-xs"
                      onClick={() => setTerminalForm({ branch: b, terminal })}
                    >
                      <Edit2 size={11}/> Settings
                    </button>
                  </div>
                ))}
              </div>
              {branchTerminals.length === 0 && (
                <div className="mt-2 rounded-lg border border-dashed border-bg-border p-4 text-sm text-fg-muted">
                  No workspace is configured yet. Add one combined workspace to begin.
                </div>
              )}
            </div>
            </div>
          );
        })}
        {!rows.length && (
          <div className="card text-fg-muted text-sm">No shop location exists yet.</div>
        )}
      </div>

      {addOpen && <BranchForm onClose={() => setAddOpen(false)}
        onSuccess={() => { setAddOpen(false); load(); }}/>}
      {edit && (
        <BranchForm
          branch={edit}
          onClose={() => setEdit(null)}
          onSuccess={() => { setEdit(null); load(); }}
        />
      )}
      {terminalForm && (
        <TerminalForm
          branch={terminalForm.branch}
          terminal={terminalForm.terminal}
          onClose={() => setTerminalForm(null)}
          onSuccess={() => { setTerminalForm(null); load(); }}
        />
      )}
    </div>
  );
}

function BranchForm({
  branch, onClose, onSuccess,
}: { branch?: BranchDTO; onClose: () => void; onSuccess: () => void }) {
  const isEdit = !!branch;
  const [form, setForm] = useState<Partial<BranchDTO>>({
    name: branch?.name ?? '',
    code: branch?.code ?? '',
    address: branch?.address ?? '',
    timezone: branch?.timezone ?? 'Asia/Kolkata',
    opens_at: branch?.opens_at ?? '09:00',
    closes_at: branch?.closes_at ?? '23:30',
    state_code: branch?.state_code ?? '32',
    fssai_license_no: branch?.fssai_license_no ?? '',
    trade_license_no: branch?.trade_license_no ?? '',
    branch_gstin: branch?.branch_gstin ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [idempotencyKey] = useState(() => `shop-create:${crypto.randomUUID()}`);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const payload: Partial<BranchDTO> = {
        ...form,
        name: form.name?.trim(),
        code: form.code?.trim() || null,
        address: form.address?.trim() || null,
        timezone: form.timezone?.trim() || null,
        opens_at: form.opens_at?.trim() || null,
        closes_at: form.closes_at?.trim() || null,
        state_code: form.state_code?.trim() || null,
        fssai_license_no: form.fssai_license_no?.trim() || null,
        trade_license_no: form.trade_license_no?.trim() || null,
        branch_gstin: form.branch_gstin?.trim() || null,
      };
      if (isEdit) await settings.updateBranch(branch!.id, payload);
      else await settings.createBranch(payload, idempotencyKey);
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${branch!.name}` : 'New shop'} size="lg">
      <form onSubmit={submit} className="space-y-3">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <Field label="Name"><input className="input" required value={form.name ?? ''}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/></Field>
          <Field label="Short code (e.g. MN for Main)">
            <input className="input font-mono" maxLength={4} value={form.code ?? ''}
              onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })}/>
          </Field>
        </div>
        <Field label="Address">
          <textarea className="input" rows={2} value={form.address ?? ''}
            onChange={(e) => setForm({ ...form, address: e.target.value })}/>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="GST state code (Kerala = 32)">
            <input className="input font-mono" maxLength={2} value={form.state_code ?? ''}
              inputMode="numeric"
              onChange={(e) => setForm({ ...form, state_code: e.target.value.replace(/\D/g, '') })}/>
          </Field>
          <Field label="Timezone">
            <input className="input" value={form.timezone ?? ''}
              onChange={(e) => setForm({ ...form, timezone: e.target.value })}/>
          </Field>
          <Field label="Opens"><input className="input" placeholder="09:00" value={form.opens_at ?? ''}
            onChange={(e) => setForm({ ...form, opens_at: e.target.value })}/></Field>
          <Field label="Closes"><input className="input" placeholder="23:30" value={form.closes_at ?? ''}
            onChange={(e) => setForm({ ...form, closes_at: e.target.value })}/></Field>
        </div>
        <Field label="FSSAI license (14 digits)">
          <input className="input font-mono" maxLength={14} value={form.fssai_license_no ?? ''}
            onChange={(e) => setForm({ ...form, fssai_license_no: e.target.value })}/>
        </Field>
        <Field label="Trade license no.">
          <input className="input font-mono" value={form.trade_license_no ?? ''}
            onChange={(e) => setForm({ ...form, trade_license_no: e.target.value })}/>
        </Field>
        <Field label="Shop GSTIN (if different from company)">
          <input className="input font-mono" maxLength={15} value={form.branch_gstin ?? ''}
            onChange={(e) => setForm({ ...form, branch_gstin: e.target.value.toUpperCase() })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Save size={14}/>}
            {isEdit ? 'Save' : 'Create'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function TerminalForm({
  branch, terminal, onClose, onSuccess,
}: {
  branch: BranchDTO;
  terminal?: TerminalDTO;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const isEdit = Boolean(terminal);
  const [name, setName] = useState(terminal?.name ?? '');
  const [deviceId, setDeviceId] = useState(terminal?.device_id ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [idempotencyKey] = useState(() => `terminal-create:${crypto.randomUUID()}`);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const normalizedName = name.trim();
    if (!normalizedName) {
      setErr('Enter a workspace name, such as Main Workspace.');
      return;
    }

    setBusy(true);
    setErr(null);
    try {
      const normalizedDeviceId = deviceId.trim();
      if (terminal) {
        await settings.updateTerminal(terminal.id, {
          name: normalizedName,
          device_id: normalizedDeviceId || null,
          purpose: 'hybrid',
        });
      } else {
        await settings.createTerminal({
          branch_id: branch.id,
          name: normalizedName,
          purpose: 'hybrid',
          ...(normalizedDeviceId ? { device_id: normalizedDeviceId } : {}),
        }, idempotencyKey);
      }
      onSuccess();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={isEdit ? 'Shared workspace settings' : `Create workspace for ${branch.name}`}
      size="sm"
    >
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-fg-muted">
          D Company uses one shared Combined workspace for Gaming, POS and shifts. Staff do
          not choose a work area when they sign in.
        </p>
        <Field label="Workspace name">
          <input
            className="input"
            required
            autoFocus
            placeholder="Main Workspace"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <div className="rounded-lg border border-bg-border bg-bg-raised p-3">
          <p className="text-xs text-fg-muted">Mode</p>
          <p className="mt-1 text-sm font-semibold">Combined — Gaming, POS and Shift</p>
        </div>
        {isEdit && terminal!.purpose !== 'hybrid' && (
          <p className="text-xs text-accent-warning">
            This is a legacy split workspace. Saving converts it to the shared Combined mode.
            Close any open shift attached to it before saving.
          </p>
        )}
        <Field label="Device ID (optional)">
          <input
            className="input font-mono"
            placeholder="Leave blank until a tablet is assigned"
            value={deviceId}
            onChange={(e) => setDeviceId(e.target.value)}
          />
        </Field>
        <p className="text-xs text-fg-muted">
          Renaming keeps existing shifts, orders, receipts, and audit history attached.
        </p>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy || !name.trim()}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Save size={14}/>}
            {isEdit ? 'Save workspace' : 'Create workspace'}
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
